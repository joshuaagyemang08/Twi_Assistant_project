# Twi Assistant

## Build
- Android Studio Giraffe+
- Min SDK 26
- Compose Material3

## Run
1. Grant microphone, phone, SMS permissions
2. Tap icon → speak a Twi command

## Replace Audio
- Record WAV files
- Replace in assets/audio/twi/

## ASR
- Plug in Vosk or PocketSphinx in `asr/` module

## 4.1.2 Backend Tools
The backend logic of this assistant runs entirely on-device inside the Android app, using a set of Android APIs and small helper modules to handle wake word detection, speech processing, intent interpretation, and device action execution.

Key tools/components used in this codebase:
- Hotword (wake word) engine: Android `SpeechRecognizer` partial results via `hotword/SimpleKeywordHotwordEngine` running in a foreground service (`hotword/HotwordService`).
- Android `MediaRecorder` API: used for recording audio clips when using the record-until-silence pathway (see `asr/SilenceDetectingMediaRecorder`).
- Custom silence detection: implemented on top of `MediaRecorder` to stop recording automatically when the user finishes speaking.
- OkHttp: HTTP client for calling external ASR/NLP services (`okhttp3.OkHttpClient`).
- Ghana NLP ASR API (optional): when configured via `BuildConfig.GHANA_NLP_ASR_URL` / `BuildConfig.GHANA_NLP_ASR_KEY`, recorded audio is transcribed using `asr/GhanaNlpAsrProvider` and `asr/SilenceRecordingAsrRecognizer`.
- Intent interpretation: rule-based parsing in `nlu/IntentParser` and dialog flow in `dialog/DialogManager`.
- Android `ContactsContract` API: runtime contact lookup for call/SMS actions (`device_control/DeviceActions`).
- Android system action APIs: dial/call, SMS intents, app launching, camera, alarms (`AlarmClock`), and brightness changes (`Settings.System`) in `device_control/DeviceActions`.

Optional / pluggable tools (not included by default in this repo):
- Picovoice Porcupine: recommended for reliable offline wake-word detection (would replace the `SpeechRecognizer`-based hotword engine). Requires adding the Porcupine SDK + access key.
- OpenAI (e.g., GPT-4o): can be integrated for translation and intent classification via structured prompts; requires an API key and network access.
