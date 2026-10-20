# Voice input from the remote's mic button (Redmi G Pro 27U fork)

Status: design, not built; step 0 passed. Fork-only: this is for the Redmi G Pro 27U; upstream doesn't need it.

## Goal

Hold the remote's mic button, speak English or Russian, let go: the words are typed into the text field
in focus (SmartTube search, a browser, LeanKeyboard). Apps that ask Android for speech recognition, such as
LeanKeyboard's mic key, get the same recognizer. All on the monitor: no internet, no PC, no account.

## What was measured (2026-10-20)

- **The button reaches the launcher.** HomeKeyService gets it as keyCode 0 (KEYCODE_UNKNOWN), scanCode 63,
  with separate down and up events, so hold-to-talk works. Xiaomi's own voice app
  (`com.xiaomi.voicecontrol`) is disabled by the debloat list and stays disabled.
- **The remote's mic is an audio input** on the monitor: "RC In", AUDIO_DEVICE_IN_WIRED_HEADSET, in
  `dumpsys media.audio_policy`. Recording from it works (step 0).
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

## Step 0: checked on the monitor (2026-10-20)

A throwaway probe in HomeKeyService recorded while the button was held; the clips went through the model on
the monitor. English "Hi, how are you doing" and Russian "Привет, как твои дела?" came back exactly, at RTF
0.31. What it showed:

- **The audio path:** the remote sends `AUDIO_START` on press and `AUDIO_STOP` on release, over BLE GATT.
  Xiaomi's Bluetooth app `com.mi.bluetooth` (log tags `MIBT.AtvVoice`, `MIBT.DomesticRc`) decodes it and
  feeds the audio HAL `audio_rc_dev`, which shows up as input "MIBT.VoiceControl" (TYPE_WIRED_HEADSET).
  It is phone quality (codec `ADPCM_4KHz`), delivered as 16 kHz mono. **`com.mi.bluetooth` must stay
  enabled**; `com.xiaomi.voicecontrol` is not needed.
- **AudioRecord** with source VOICE_RECOGNITION, 16 kHz mono PCM 16 and that device preferred works from
  the accessibility service in the background, with DP video in front: not silenced, no foreground service
  needed.
- **read() blocks once the remote stops sending.** The key-up handler must call `AudioRecord.stop()` itself
  (that makes read() return); setting a flag for the reading thread leaves it stuck with the mic open. Keep a
  time cap as well.
- "AudioFlinger: read failed: framesRead=0" in the log is the HAL waiting for the next packet; it's harmless.
- The firmware's own handler logs "voice key long pressed" and checks for the Sogou keyboard, then does
  nothing more while Xiaomi's voice app is disabled.
- **Still open:** where text goes without a text field (does SmartTube open a search from
  `https://www.youtube.com/results?search_query=...` or ACTION_SEARCH?).

## Design

- **Button** (HomeKeyService): scanCode 63 down starts recording, up stops it and transcribes. A press
  shorter than about 300 ms does nothing, so a tap doesn't type noise. Decided per press, like TV and Home
  (`micTaken`), and never over the clock screensaver.
- **Recorder** (`VoiceRecorder`): AudioRecord as in step 0, on its own thread, with a 30 s cap. The key-up
  calls `AudioRecord.stop()` directly. It keeps a level for the overlay.
- **Recognizer** (`SpeechEngine`): the sherpa-onnx AAR (`sherpa-onnx-static-link-onnxruntime`,
  abiFilters arm64-v8a), OfflineRecognizer with the NeMo CTC model, 4 threads.
  - The model loads on the first press, on a background thread; never on the main thread (keys time out
    after 500 ms).
  - It stays loaded for 10 minutes after the last use, then is released (230 MB).
  - Decoding runs on the same background thread.
- **Output**, in order:
  1. A focused editable node (`findFocus(FOCUS_INPUT)`, which needs canRetrieveWindowContent, already on):
     ACTION_SET_TEXT with the field's text plus the transcript.
  2. Otherwise, a SmartTube search with the transcript, if that turns out to work (see step 0, still open).
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
