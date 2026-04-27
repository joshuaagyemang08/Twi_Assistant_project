# TTS Timing and Processing Improvements

## Overview
Enhanced the voice assistant to ensure TTS (Text-to-Speech) completes before executing commands, and added visual processing indicators for better user feedback.

## Changes Made

### 1. TTS Completion Callback System

#### TtsEngine Interface (`TtsEngine.kt`)
- **Updated**: Added `onComplete` callback parameter to `speak()` method
- **Purpose**: Allow callers to know when TTS has finished speaking

```kotlin
fun speak(text: String, onComplete: (() -> Unit)? = null)
```

#### AndroidTts Implementation (`AndroidTts.kt`)
- **Added**: `onCompleteCallback` field
- **Implemented**: `UtteranceProgressListener` to detect when TTS finishes
- **Callbacks**: Invokes `onComplete` when TTS done or on error

#### GhanaNlpTts Implementation (`GhanaNlpTts.kt`)
- **Added**: `onCompleteCallback` field
- **Updated**: `speak()` method to accept and store completion callback
- **Implemented**: MediaPlayer completion detection:
  - `OnCompletionListener` - invokes callback when audio finishes
  - `OnErrorListener` - invokes callback on playback error
  - Exception handling - invokes callback if playback fails

### 2. ViewModel Enhancements (`AssistantViewModel.kt`)

#### Processing State Tracking
- **Added**: `isProcessingCommand` state variable
- **Purpose**: Track when app is executing a command after TTS

#### speak() Method Updates
- **Updated**: Now accepts optional `onComplete` callback
- **Behavior**: 
  - Sets `isProcessingCommand = true` when callback provided
  - Executes callback after TTS completes
  - Sets `isProcessingCommand = false` after callback executes

#### App Opening Flow
- **Fixed**: App launches now happen AFTER TTS announcement completes
- **Example**: Says "Chrome rebue ama wo" (Chrome opening for you), THEN launches Chrome
- **Implementation**: Uses callback to execute `launchAppByName()` after speaking

```kotlin
speak("$appToLaunch rebue ama wo.") {
    val success = actions.launchAppByName(appToLaunch)
    // ... handle result
}
```

#### Translation Processing State
- **SMS Translation**: Sets `isProcessingCommand = true` during translation, clears after sending
- **WhatsApp Translation**: Same processing state management
- **Purpose**: Shows spinner in UI during translation operations

### 3. UI Improvements (`AssistantHomeScreen.kt`)

#### Processing State Detection
- **Updated**: `isProcessing` now includes `viewModel.isProcessingCommand`
- **Before**: Only checked `DialogState.EXECUTE`
- **After**: Also checks if command is being executed after TTS

```kotlin
val isProcessing = dialogState == DialogState.EXECUTE && !isSpeaking || viewModel.isProcessingCommand
```

#### Visual Processing Indicator
- **Changed**: Processing state now shows a spinner instead of sound waves
- **Widget**: `CircularProgressIndicator` (blue color, 32dp size, 3dp stroke)
- **Purpose**: Clearer visual feedback that app is working

#### Mic Button Behavior
- **Already Disabled**: Mic is disabled during `isProcessing` (line 664)
- **Click Protection**: `clickable(enabled = !isProcessing && !isSpeaking)`
- **Purpose**: Prevents user from speaking while command is executing

## User Experience Improvements

### Before
1. User: "Bue Chrome" (Open Chrome)
2. App: Opens Chrome while still speaking "Chrome rebue..."
3. User hears cut-off announcement
4. No visual feedback during translation/processing

### After
1. User: "Bue Chrome" (Open Chrome)
2. App: Speaks full announcement "Chrome rebue ama wo"
3. **After TTS completes**: Opens Chrome
4. During translation: Shows blue spinner with "Merekyerɛw..." (Processing...)
5. Mic button disabled/grayed during processing

## Technical Flow

### App Opening Example
```
User speaks → Speech recognized → handleAppNameInput() called
   ↓
Find matching app → speak("Opening Chrome") { callback }
   ↓
TTS starts → isProcessingCommand = true → UI shows spinner
   ↓
TTS finishes → callback executes → launchApp()
   ↓
isProcessingCommand = false → UI returns to normal
```

### Translation Example
```
User speaks message → handleFinal() called
   ↓
Set isProcessingCommand = true → UI shows spinner
   ↓
Translate Twi → English (async)
   ↓
Send message → isProcessingCommand = false
```

## State Indicators

### Visual States in UI
- **Idle**: Red gradient button, microphone icon
- **Listening**: Green gradient, animated sound waves
- **Speaking**: Amber gradient, speaker icon (🔊)
- **Processing**: Blue gradient, circular spinner
  - Shown during: Translation, command execution after TTS

### Status Text (Twi)
- **Idle**: "Mesrɛ wo, mia ha na kasa" (Please tap to speak)
- **Listening**: "Metie wo..." (Listening...)
- **Speaking**: "Merekasa..." (Speaking...)
- **Processing**: "Merekyerɛw..." (Processing...)

## Benefits
1. ✅ Commands execute AFTER announcements complete
2. ✅ Visual feedback during all processing operations
3. ✅ User cannot interrupt processing (mic disabled)
4. ✅ Clear state transitions
5. ✅ Better user experience - no cut-off speech
6. ✅ Professional appearance with spinner

## Testing Checklist
- [ ] Open app command: TTS completes before app launches
- [ ] Send SMS: Spinner shows during translation
- [ ] Send WhatsApp: Spinner shows during translation
- [ ] Mic disabled during processing
- [ ] Processing state clears after completion
- [ ] Error cases: Callback still executes (prevents stuck state)
