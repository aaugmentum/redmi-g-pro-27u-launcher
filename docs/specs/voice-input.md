# Voice input from the remote's mic button (Redmi G Pro 27U fork)

Status: design, not built. Fork-only: this is for the Redmi G Pro 27U; upstream doesn't need it.

## Goal

Hold the remote's mic button, speak English or Russian, let go: the words are typed into the text field
in focus (SmartTube search, a browser, LeanKeyboard). Apps that ask Android for speech recognition, such as
LeanKeyboard's mic key, get the same recognizer. All on the monitor: no internet, no PC, no account.

## What was measured (2026-10-20)

- **The button reaches the launcher.** HomeKeyService gets it as keyCode 0 (KEYCODE_UNKNOWN), scanCode 63,
  with separate down and up events, so hold-to-talk works. Xiaomi's own voice app
  (`com.xiaomi.voicecontrol`) is disabled by the debloat list and stays disabled.
- **The remote's mic is an audio input** on the monitor: "RC In", AUDIO_DEVICE_IN_WIRED_HEADSET, in
  `dumpsys media.audio_policy`. Recording from it is not tested yet (step 0).
- **Hardware:** MediaTek mt5877, 4x Cortex-A73, 2.8 GB RAM (about 1.5 GB free with apps open).
- **Models**, sherpa-onnx v1.13.8 `sherpa-onnx-offline`, 4 threads, the models' own test clips:

  | Model | English | Russian | RTF | Peak RSS |
  |---|---|---|---|---|
  | NeMo FastConformer CTC int8, be/de/en/es/fr/hr/it/pl/ru/uk (128 MB) | correct | correct, punctuated | 0.39-0.43 | 230 MB |
  | NeMo Parakeet TDT 0.6b v3 int8 (640 MB) | correct | correct | 1.4-1.6 | 750 MB |

  Parakeet is slower than real time here and too big for the free RAM, so the FastConformer model is the
  one: `sherpa-onnx-nemo-fast-conformer-ctc-be-de-en-es-fr-hr-it-pl-ru-uk-20k-int8` from the
  `asr-models` release. It picks the language by itself. GigaAM v3 (Russian only, about 155 MB) is the
  better Russian model if a Russian-only mode is ever wanted.

## Step 0: go/no-go checks, before building the rest

1. **Mic capture.** From the launcher (debug build), record 5 s with AudioRecord (16 kHz, mono, PCM 16,
   source VOICE_RECOGNITION, preferred device = the AudioDeviceInfo named "RC In") while the button is
   held, and check the samples aren't silence. If they are, the remote may only stream after Xiaomi's
   voice stack asks it to: look at how `com.xiaomi.voicecontrol` and the BT service start it (REA can
   decompile them). That's the main risk.
2. **Mic access from the accessibility service.** The service runs in the background. Its binding should
   carry the process capabilities needed for the mic, but HyperOS may differ. If AudioRecord gets silence
   or an app-op denial, start a foreground service of type `microphone` for the length of the recording.
3. **Where text goes without a text field.** Check whether SmartTube opens a search from
   `https://www.youtube.com/results?search_query=...` or ACTION_SEARCH.

## Design

- **Button** (HomeKeyService): scanCode 63 down starts recording, up stops it and transcribes. A press
  shorter than about 300 ms does nothing, so a tap doesn't type noise. Decided per press, like TV and Home
  (`micTaken`), and never over the clock screensaver.
- **Recorder** (`VoiceRecorder`): AudioRecord as in step 0, on its own thread, with a 30 s cap. It keeps a
  level for the overlay.
- **Recognizer** (`SpeechEngine`): the sherpa-onnx AAR (`sherpa-onnx-static-link-onnxruntime`,
  abiFilters arm64-v8a), OfflineRecognizer with the NeMo CTC model, 4 threads.
  - The model loads on the first press, on a background thread; never on the main thread (keys time out
    after 500 ms).
  - It stays loaded for 10 minutes after the last use, then is released (230 MB).
  - Decoding runs on the same background thread.
- **Output**, in order:
  1. A focused editable node (`findFocus(FOCUS_INPUT)`, which needs canRetrieveWindowContent, already on):
     ACTION_SET_TEXT with the field's text plus the transcript.
  2. Otherwise, a SmartTube search with the transcript, if step 0.3 finds a way.
  3. Otherwise, the overlay shows the text for a few seconds.
- **Overlay** (`VoiceOverlay`), styled like the brightness bar: a mic icon and level while recording,
  then a dot animation while decoding, then the text.
- **RecognitionService** (`VoiceRecognitionService`): the same recorder and engine behind Android's
  SpeechRecognizer API, so LeanKeyboard's mic key and apps' voice buttons use it. The install script sets
  it as the default: `settings put secure voice_recognition_service com.gathin.tvhome/.VoiceRecognitionService`.
- **Permissions:** RECORD_AUDIO, granted over adb by the install script. No INTERNET: the launcher stays
  offline.
- **Model delivery:** the install script downloads the model archive once (cached next to the script),
  takes `model.int8.onnx` and `tokens.txt`, and pushes them to
  `/sdcard/Android/data/com.gathin.tvhome/files/asr/`. Check that adb can write there on HyperOS;
  otherwise use `run-as` (debug builds only). Without the model, the mic button falls through to the
  firmware, as now.

## Tests

- Unit: the transcript-merging rule (field text + transcript, spacing), and the press-length rule.
- On the monitor: hold mic in SmartTube search, in a browser field, in the launcher (no field); English
  and Russian; a press during video playback (CPU load); 10 minutes idle frees the memory; standby and
  back (the force-stop drops the loaded model; the next press loads it again).
