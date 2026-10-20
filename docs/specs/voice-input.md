# Voice input from the remote's mic button (Redmi G Pro 27U fork)

Status: built (2026-10-20), except the RecognitionService and the SmartTube search; see "As built".
Fork-only: this is for the Redmi G Pro 27U; upstream doesn't need it.

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

- **Through the remote, on the user's own speech** (16 kHz, ADPCM 4 kHz underneath, Russian-accented
  English), the FastConformer wrote English in Cyrillic: "Nature scenes" came out as "эчер Синс", "Latest AI
  news" as "Лейтест Эйай Ньюс". Its clean test clips had hidden this. On the same recordings:

  | Model | "Nature scenes" | "Привет, как дела?" | Decode | Load |
  |---|---|---|---|---|
  | FastConformer CTC int8, 10 languages | эчер Синс | correct | RTF 0.33 | 6.5-10 s |
  | Parakeet TDT-CTC 110M en int8 (131 MB) | Nature scenes. | n/a | RTF 0.34 | 6-8 s |
  | Parakeet TDT 0.6b v3 int8 | Nature scenes. | | RTF 1.24 | |
  | Whisper tiny int8, language given | Nature seems | Думаю, что я не делал. | 2-3 s fixed | ~2.5 s |

  Whisper tiny's language identification alone (sherpa's SpokenLanguageIdentification) got English and
  Russian right in 1-2 s, but it is another 240 MB. Loading is CPU-bound (ONNX Runtime preparing the
  model), not storage-bound: about the same from /data/local/tmp as from /sdcard.

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
- **Model delivery:** the install script downloads each model archive once (cached in
  `~/.cache/redmi-g-pro-27u-launcher`) and checks the model's SHA-256,
  takes `model.int8.onnx` and `tokens.txt`, and pushes them to
  `/sdcard/Android/data/com.gathin.tvhome/files/asr/<language>/`. Check that adb can write there on HyperOS;
  otherwise use `run-as` (debug builds only). Without the model, the mic button falls through to the
  firmware, as now.

## As built (2026-10-20)

Changes from the design above, after the measurements on the user's speech:

- **One model per language**, in `files/asr/en/` (Parakeet TDT-CTC 110M) and `files/asr/ru/` (the
  FastConformer). Only one is loaded at a time (about 250 MB). After a tap the new language's model loads
  once the language card closes (1.5 s), so tapping through languages loads only where you stop; a load
  already running finishes first.
- **A tap (under 400 ms) switches the language**, among those installed, and the card shows its name; the
  choice is kept in prefs, starting from the system language. The listening card waits the same 400 ms, so a
  tap doesn't flash it. A hold dictates, as designed.
- **Silence is cut** before recognition (`VoiceClip.trim`): pauses longer than 0.6 s keep 0.3 s at each end,
  and the silence before the first word and after the last is cut to 0.3 s. People hold the button well
  before and after speaking; a 6.8 s clip of two words became about 2 s.
- **A lone sentence loses its full stop** (`VoiceText.tidy`): Parakeet punctuates, a search box doesn't want it.
- **The card** is a pill with a mic badge; a halo follows the mic level (-55 to -30 dBFS) while listening and
  breathes while recognizing, and the language code sits at the pill's end.
- **Loading:** the install script converts each model to ONNX Runtime's `.ort` format on the PC (Python
  onnxruntime 1.28.0 and onnx in a venv in the cache, `--target_platform arm --optimization_style Fixed`), and
  the app prefers `model.int8.ort` over `model.int8.onnx`. Measured with the command line tool, two runs each:
  English 7.0 s to 4.7-5.1 s, Russian 6.7-7.0 s to 4.6-5.0 s, the same text. The rest is ONNX Runtime
  preparing the int8 weights for the CPU, whatever the file. To hide it, focus moving to a text field
  (an EditText, which browsers' fields report too; not password fields) starts loading the chosen
  language's model, so it's usually ready when the button is held.
- **Text goes to the field that had focus when you let go**, and only if it still has focus when the words
  are ready; never into a password field.
- **Debugging:** with a file `files/asr/debug` on the monitor, the last recording is kept as
  `files/asr/last.wav` and the text logged (tag TvHomeVoice), to compare with sherpa-onnx's command line
  tools. Off without the file.
- Not built yet: the RecognitionService (the remote only streams while the button is held, so the
  SpeechRecognizer API would need the built-in mic) and the SmartTube search for text with no field.

## Tests

- Unit: the transcript-merging rule (field text + transcript, spacing), the full-stop rule and the silence
  trimming (`VoiceTextTest`, `VoiceClipTest`).
- On the monitor: hold mic in SmartTube search, in a browser field, in the launcher (no field); English
  and Russian; a press during video playback (CPU load); 10 minutes idle frees the memory; standby and
  back (the force-stop drops the loaded model; the next press loads it again).
