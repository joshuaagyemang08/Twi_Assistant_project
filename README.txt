Twi Assistant

Twi Assistant is an Android assistant app built with Kotlin, Jetpack Compose, and the Android SDK. It uses local device capabilities for speech, camera, contacts, SMS, calls, and network-based AI or translation features when configured.

Source Code

GitHub repository: https://github.com/joshuaagyemang08/Twi_Assistant_project

Requirements

- Android Studio Giraffe or newer
- JDK 17
- Android SDK 34
- A device or emulator with microphone support for voice features

Compile

Open the TwiAssistant project in Android Studio and let Gradle sync, or build from the command line:

cd TwiAssistant
gradlew.bat assembleDebug

On macOS or Linux, use ./gradlew assembleDebug instead.

Install And Run

From Android Studio

1. Open the TwiAssistant project.
2. Wait for Gradle sync to finish.
3. Run the app configuration on a connected device or emulator.

From The Command Line

cd TwiAssistant
gradlew.bat installDebug

This installs the debug build on a connected Android device or emulator.

Deploy

For local deployment, install the debug APK with Android Studio or installDebug.

For release deployment:

1. Set your signing configuration in Android Studio or in the Gradle files.
2. Build a release artifact:

cd TwiAssistant
gradlew.bat assembleRelease

3. If you want a Play Store upload package, build an Android App Bundle:

cd TwiAssistant
gradlew.bat bundleRelease

Configuration

Optional API keys and endpoints can be provided through local.properties or Gradle properties. The app reads values such as:

- GHANA_NLP_ASR_URL
- GHANA_NLP_ASR_KEY
- GHANA_NLP_TTS_URL
- GHANA_NLP_TTS_KEY
- GHANA_NLP_TRANSLATION_URL
- GHANA_NLP_TRANSLATION_KEY
- GOOGLE_TRANSLATE_API_KEY
- GOOGLE_SEARCH_CX
- GEMINI_API_KEY

Permissions

The app requests permissions for:

- Microphone recording
- Phone calls
- SMS sending and reading
- Contacts access
- Camera access
- Internet and network state

Notes

- The launcher activity is MainActivity.
- The app supports optional device actions such as calling, SMS, camera use, and speech processing.
- If you add or change external services, update the relevant Gradle properties and document the new setup steps here.

Manual

See MANUAL.txt for the user and setup manual.
