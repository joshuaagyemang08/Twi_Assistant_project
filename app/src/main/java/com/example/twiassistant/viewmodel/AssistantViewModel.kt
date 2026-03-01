package com.example.twiassistant.viewmodel

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.twiassistant.device_control.DeviceActions
import com.example.twiassistant.asr.DemoAsrEngine
import com.example.twiassistant.asr.SpeechRecognizerTwi
import com.example.twiassistant.dialog.DialogManager
import com.example.twiassistant.dialog.DialogState
import com.example.twiassistant.tts.TtsEngine
import com.example.twiassistant.nlu.IntentParser
import com.example.twiassistant.nlu.IntentResult
import com.example.twiassistant.nlu.BrightnessAction
import com.example.twiassistant.translation.GoogleTranslator
import android.graphics.Bitmap


import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

class AssistantViewModel(
    private val parser: IntentParser = IntentParser(),
    private val dialogManager: DialogManager = DialogManager(),
    recognizerProvider: ((onFinal: (String) -> Unit, onPartial: (String) -> Unit, onError: (String) -> Unit) -> SpeechRecognizerTwi)? = null,
    englishRecognizerProvider: ((onFinal: (String) -> Unit, onPartial: (String) -> Unit, onError: (String) -> Unit) -> SpeechRecognizerTwi)? = null,
    translationApiKey: String = "",
    googleApiKey: String = "",
    googleSearchCx: String = "",
    geminiApiKey: String = "",
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .build(),
    private val deviceActions: DeviceActions? = null,
    private val ttsEngine: TtsEngine? = null
) : ViewModel() {

    private val translator: GoogleTranslator? by lazy {
        if (translationApiKey.isNotBlank()) {
            GoogleTranslator(
                client = httpClient,
                apiKey = translationApiKey
            )
        } else {
            null
        }
    }
    


    private val translationCache = mutableMapOf<String, String>()

    private var lastUtterance = ""

    var lastHeard by mutableStateOf("")
        private set

    var dialogState by mutableStateOf(DialogState.IDLE)
        private set

    var executedAction by mutableStateOf("")
        private set

    var partialTranscript by mutableStateOf("")
        private set

    var lastError by mutableStateOf("")
        private set
        
    var isProcessingCommand by mutableStateOf(false)
        private set
    
    var isProcessingPhoto by mutableStateOf(false)
        private set
    
    var isAnalyzingSpeech by mutableStateOf(false)
        private set
    
    private var processingTimeoutJob: Job? = null
    private var speechAnalysisJob: Job? = null
    
    private fun setProcessing(value: Boolean) {
        isProcessingCommand = value
        
        // Cancel any existing timeout
        processingTimeoutJob?.cancel()
        
        // If setting to true, start a safety timeout
        if (value) {
            processingTimeoutJob = viewModelScope.launch {
                delay(10000) // 10 second timeout
                if (isProcessingCommand) {
                    Log.w("AssistantVM", "Processing state stuck - forcing reset after 10s timeout")
                    isProcessingCommand = false
                }
            }
        }
    }
    
    private fun setSpeechAnalysis(value: Boolean) {
        isAnalyzingSpeech = value
        
        // Cancel any existing timeout
        speechAnalysisJob?.cancel()
        
        // If setting to true, start a safety timeout
        if (value) {
            speechAnalysisJob = viewModelScope.launch {
                delay(5000) // 5 second timeout for speech analysis
                if (isAnalyzingSpeech) {
                    Log.w("AssistantVM", "Speech analysis stuck - forcing reset after 5s timeout")
                    isAnalyzingSpeech = false
                }
            }
        }
    }

    private suspend fun translateWithCache(text: String, langPair: String): String {
        translationCache[text]?.let { return it }
        return try {
            val result = withTimeoutOrNull(3000) {
                translator?.translate(text, langPair) ?: text
            } ?: text
            translationCache[text] = result
            result
        } catch (e: Exception) {
            text // fallback
        }
    }

    fun translateEnglishToTwi(text: String, callback: (String) -> Unit) {
        viewModelScope.launch {
            val result = translateWithCache(text, "en-tw")
            callback(result)
        }
    }

    fun translateTwiToEnglish(text: String, callback: (String) -> Unit) {
        viewModelScope.launch {
            val result = translateWithCache(text, "tw-en")
            callback(result)
        }
    }

    /**
     * Translate English to Twi with 3 second timeout and caching.
     */
    private suspend fun safeTranslateEnglishToTwi(text: String): String {
        translationCache[text]?.let { return it }
        return try {
            val result = withTimeoutOrNull(3000) {
                translator?.translate(text, "en-tw") ?: throw IllegalStateException("Translator not configured")
            }
            if (result != null) {
                translationCache[text] = result
                Log.d("Translation", "en->tw success: '$result'")
                result
            } else {
                Log.w("Translation", "en->tw timed out after 3 seconds")
                text
            }
        } catch (e: Exception) {
            Log.e("Translation", "en->tw failed: ${e.message}")
            text
        }
    }

    /**
     * Translate Twi to English with timeout and caching.
     */
    private suspend fun safeTranslateTwiToEnglish(text: String): String {
        translationCache[text]?.let { return it }
        
        Log.d("Translation", "Translating text: '$text'")
        
        // Single attempt with 3 second timeout
        try {
            val result = withTimeoutOrNull(3000) {
                translator?.translate(text, "tw-en") ?: throw IllegalStateException("Translator not configured")
            }
            
            if (result != null) {
                translationCache[text] = result
                Log.d("Translation", "Translation success: '$result'")
                return result
            } else {
                Log.w("Translation", "Translation timed out after 3 seconds")
                return text // Return original text on timeout
            }
        } catch (e: Exception) {
            Log.e("Translation", "Translation failed: ${e.message}")
            return text // Return original text on error
        }
    }

    enum class UiMode { DEFAULT, CALL, SMS, OPEN_APPS }
    var uiMode by mutableStateOf(UiMode.DEFAULT)
        private set

    private val recognizer: SpeechRecognizerTwi =
        recognizerProvider?.invoke(::handleFinal, ::handlePartial, ::handleError)
            ?: DemoAsrEngine(::handleFinal)

    private val englishRecognizer: SpeechRecognizerTwi? =
        englishRecognizerProvider?.invoke(::handleEnglishFinal, ::handleEnglishPartial, ::handleEnglishError)

    /**
     * Validates device actions and permissions
     */
    private fun validateDeviceActionsAndPermissions(): Boolean {
        if (deviceActions == null) {
            showFriendlyError("App esom. San bue no.")
            return false
        }

        if (!deviceActions.hasReadContactsPermission()) {
            showFriendlyError("Mepa wo kyɛw, ma me kwan na menh wɔ wo contacts mu.")
            return false
        }

        return true
    }

    /**
     * Shows a user-friendly error message
     */
    private fun showFriendlyError(message: String) {
        lastError = message
        executedAction = ""
        setProcessing(false)
        setSpeechAnalysis(false)
        dialogState = DialogState.IDLE
    }

    /**
     * Handles found contacts for both calls and messages
     */
    private fun handleFoundContacts(
        matches: List<DeviceActions.ContactMatch>, 
        originalName: String, 
        isCall: Boolean
    ) {
        if (matches.size == 1) {
            val contact = matches[0]
            if (isCall) {
                deviceActions?.dialOrCall(contact.number)
                executedAction = "Manya ${contact.displayName}. Merefrɛ no seesei."
            } else {
                _pendingChoice = PendingChoice.MessageChoice(contact = contact, originalName = originalName)
                executedAction = "Wopɛ sɛ mede dia ?wɔ he kyer??"
                setFlowState(FlowState.AwaitingCommandTwi)
                return
            }
            setFlowState(FlowState.Idle)
        } else {
            val choiceType = if (isCall) {
                PendingChoice.Call(candidates = matches)
            } else {
                PendingChoice.SmsName(candidates = matches)
            }
            _pendingChoice = choiceType
            executedAction = "Manya nnipa pii a w?n din s? $originalName. Fa bako."
            setFlowState(FlowState.Idle)
        }
    }

    sealed class PendingChoice {
        data class Call(val candidates: List<DeviceActions.ContactMatch>) : PendingChoice()
        data class SmsName(val candidates: List<DeviceActions.ContactMatch>) : PendingChoice()
        data class Sms(val body: String, val candidates: List<DeviceActions.ContactMatch>) : PendingChoice()
        data class WhatsApp(val body: String, val candidates: List<DeviceActions.ContactMatch>) : PendingChoice()
        data class MessageChoice(val contact: DeviceActions.ContactMatch, val originalName: String) : PendingChoice()
    }

    private var _pendingChoice: PendingChoice? = null
    
    // Store selected contacts for messaging
    private var selectedSmsContact: DeviceActions.ContactMatch? = null
    private var selectedWhatsAppContact: DeviceActions.ContactMatch? = null
    
    // All messages are now input in Twi and sent as English translations



    private enum class ActiveRecognizer { NONE, TWI, ENGLISH }
    private var activeRecognizer: ActiveRecognizer = ActiveRecognizer.NONE
    private var pendingEnglishStart: Job? = null
    private var englishAutoRetryAttempts: Int = 0

    enum class SlotMode { CALL, SMS, OPEN_APP }

    /**
     * Represents the current dialog state of the assistant.
     * Each state should have a clear entry and exit point.
     */
    sealed class FlowState {
        /** Idle: Waiting for user input or action. */
        data object Idle : FlowState()
        /** Awaiting a command in Twi. */
        data object AwaitingCommandTwi : FlowState()
        /** Awaiting a name in English for a specific slot mode (CALL/SMS). */
        data class AwaitingEnglishName(val mode: SlotMode, val attemptCount: Int = 0) : FlowState()
        /** Awaiting a name in Twi for a specific slot mode (CALL/SMS). */
        data class AwaitingTwiName(val mode: SlotMode, val attemptCount: Int = 0) : FlowState()
        /** Awaiting the SMS body for a given target. */
        data class AwaitingSmsBody(val targetName: String) : FlowState()
        /** Awaiting the WhatsApp message body for a given target. */
        data class AwaitingWhatsAppBody(val targetName: String) : FlowState()
        /** Awaiting app name to open. */
        data object AwaitingAppName : FlowState()
        /** Error state: contains a user-facing error message. */
        data class Error(val message: String) : FlowState()
    }

    var flowState: FlowState = FlowState.Idle
        private set
    private var greeted = false
    private var lastGreetingTime = 0L
    private var autoListeningStarted = false
    private var menuActive = false

    // Public accessor for menu state
    val isMenuActive: Boolean
        get() = menuActive
    
    // Public accessor for pending choice state  
    val pendingChoice: PendingChoice?
        get() = _pendingChoice

    private fun timeBasedGreeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Maakye"
            in 12..16 -> "Maaha"
            else -> "Maadwo"
        }
    }

    private fun getWelcomeMessage(): String {
        return "Akwaaba! Metumi aboa wo wɔ nneɛma abiɛsa ho: frɛ obi, kyer?w krataa, anaa bue apps."
        // Welcome! I can do three things: call someone, write messages, and open apps.
    }

    private fun timeBasedGreetingEnglish(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    /**
     * Sets the current dialog state and updates the UI mode accordingly.
     * Logs all state transitions for debugging and analytics.
     */
    private fun setFlowState(state: FlowState) {
        val prevState = flowState
        flowState = state
        uiMode = when (state) {
            is FlowState.AwaitingEnglishName -> when (state.mode) {
                SlotMode.CALL -> UiMode.CALL
                SlotMode.SMS -> UiMode.SMS
                SlotMode.OPEN_APP -> UiMode.OPEN_APPS
            }
            is FlowState.AwaitingTwiName -> when (state.mode) {
                SlotMode.CALL -> UiMode.CALL
                SlotMode.SMS -> UiMode.SMS
                SlotMode.OPEN_APP -> UiMode.OPEN_APPS
            }
            is FlowState.AwaitingSmsBody -> UiMode.SMS
            is FlowState.AwaitingWhatsAppBody -> UiMode.SMS
            is FlowState.AwaitingAppName -> UiMode.OPEN_APPS
            is FlowState.AwaitingCommandTwi -> UiMode.DEFAULT
            is FlowState.Error -> UiMode.DEFAULT
            is FlowState.Idle -> UiMode.DEFAULT
        }
        Log.d("DialogState", "Transition: $prevState -> $state")
    }

    private fun normalizeCommandText(text: String): String {
        // GhanaNLP / keyboards sometimes represent ? as 3.
        return text
            .lowercase(Locale.ROOT)
            .replace("fr3", "fr?")
            .replace("ky3r3", "ky?r?")
            .replace("ky3r?", "ky?r?")
            .replace("camera", "twa")  // English fallback
            .replace("photo", "twa")   // English fallback
    }

    private fun handleCallNameEnglish(name: String, currentState: FlowState.AwaitingEnglishName) {
        if (!validateDeviceActionsAndPermissions()) return
        
        // Show processing during contact search
        setSpeechAnalysis(true)
        executedAction = "Mehwehwɔ $name..."
        dialogState = DialogState.EXECUTE
        
        val actions = deviceActions!!
        val allCandidates = actions.findPhoneCandidatesByName(name, maxResults = 10)
        val exactMatches = findAllExactMatches(name, allCandidates)
        
        // Clear processing after search
        setSpeechAnalysis(false)
        
        // If we found matches, process them
        if (exactMatches.isNotEmpty()) {
            handleFoundContacts(exactMatches, name, isCall = true)
            return
        }

        val bestMatches = improvedFuzzyMatch(name, allCandidates).take(3)
        if (bestMatches.isNotEmpty()) {
            handleFoundContacts(bestMatches, name, isCall = true)
            return
        }

        // No matches found - switch to alternative language or give up
        when (currentState.attemptCount) {
            0 -> {
                // First English attempt failed, switch to Twi
                executedAction = "Mepa wo kyɛw, mentee din no yie. San bɛ m'aso bio."
                setFlowState(FlowState.AwaitingTwiName(SlotMode.CALL, attemptCount = 1))
            }
            else -> {
                // Second English attempt (after Twi) failed - give up after 3 total tries
                executedAction = "Menhu din yi wɔ wo contacts mu. Mepa wo kyɛw, hwɔ din no anaa ka 'home' sɛ wo pɛ sɛ wo k? home."
                setSpeechAnalysis(false)
                setFlowState(FlowState.Idle)
                dialogState = DialogState.IDLE
            }
        }
    }

    private fun handleSmsNameEnglish(name: String, currentState: FlowState.AwaitingEnglishName) {
        if (!validateDeviceActionsAndPermissions()) return
        
        // Show processing during contact search
        setSpeechAnalysis(true)
        executedAction = "Mehwehwɔ $name..."
        dialogState = DialogState.EXECUTE
        
        val actions = deviceActions!!
        val allCandidates = actions.findPhoneCandidatesByName(name, maxResults = 10)
        val exactMatches = findAllExactMatches(name, allCandidates)
        
        // Clear processing after search
        setSpeechAnalysis(false)
        
        // If we found matches, process them
        if (exactMatches.isNotEmpty()) {
            handleFoundContacts(exactMatches, name, isCall = false)
            return
        }

        val bestMatches = improvedFuzzyMatch(name, allCandidates).take(3)
        if (bestMatches.isNotEmpty()) {
            handleFoundContacts(bestMatches, name, isCall = false)
            return
        }

        // No matches found - switch to alternative language or give up
        when (currentState.attemptCount) {
            0 -> {
                // First English attempt failed, switch to Twi
                executedAction = "Mepa wo kyɛw, mentee din no yie. San bɛ m'aso bio."
                setFlowState(FlowState.AwaitingTwiName(SlotMode.SMS, attemptCount = 1))
            }
            else -> {
                // Second English attempt (after Twi) failed - give up after 3 total tries
                executedAction = "Menhu din yi wɔ wo contacts mu. Mepa wo kyɛw, hwɔ din no anaa ka 'home' sɛ wo pɛ sɛ wo k? home."
                setFlowState(FlowState.Idle)
            }
        }
    }

    // Removed: completePendingCallIfAny() - replaced by selectContact() and _pendingChoice system

    /**
     * Called when the UI shows.
     * Prompts in Twi but does NOT auto-start listening. User must press button.
     * Greets on each app open, but prevents greeting more than once per 5 seconds.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onAppOpened(canRecordAudio: Boolean) {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastGreeting = currentTime - lastGreetingTime
        
        // Greet only once per session or after significant time gap (30 seconds)
        if (!greeted || timeSinceLastGreeting > 30000) {
            greeted = true
            lastGreetingTime = currentTime
            setFlowState(FlowState.AwaitingCommandTwi)
            // Greeting - TTS engine will handle the pacing naturally
            executedAction = "Akwaaba! Metumi aboa wo wɔ nneɛma abiɛsa ho: frɛ obi, kyer?w krataa, anaa bue apps."
            lastError = ""
        }
    }

    fun startEnglishListening() {
        when (flowState) {
            is FlowState.AwaitingEnglishName -> {
                val eng = englishRecognizer
                if (eng == null) {
                    showFriendlyError("Me ntumi nkasa borɔfo. Fa Twi ka.")
                    return
                }

                pendingEnglishStart?.cancel()
                activeRecognizer = ActiveRecognizer.ENGLISH
                dialogState = DialogState.LISTENING
                Log.d("VM_ENGLISH", "Starting English recognition")
                eng.startListening()
            }
            
            is FlowState.AwaitingSmsBody, is FlowState.AwaitingWhatsAppBody -> {
                startTwiListening()
            }

            else -> {
                startTwiListening()
            }
        }
    }
    
    private fun startTwiListening() {
        // Default to Twi recognizer
        pendingEnglishStart?.cancel()
        pendingEnglishStart = null
        activeRecognizer = ActiveRecognizer.TWI
        dialogState = DialogState.LISTENING
        recognizer.startListening()
    }
    
    fun startListening() {
        when (flowState) {
            is FlowState.AwaitingEnglishName -> {
                val eng = englishRecognizer
                if (eng == null) {
                    showFriendlyError("Me ntumi nkasa borɔfo. Fa Twi ka.")
                    return
                }

                if (pendingEnglishStart?.isActive == true) {
                    Log.d("VM_ENGLISH", "English start already pending")
                    return
                }

                pendingEnglishStart?.cancel()
                activeRecognizer = ActiveRecognizer.ENGLISH
                dialogState = DialogState.LISTENING
                Log.d("VM_ENGLISH", "Starting English recognition")
                eng.startListening()
            }

            else -> {
                startTwiListening()
            }
        }
    }

    
    fun stopListening() {
        Log.d("VM_STOP", "stopListening() called: activeRecognizer=$activeRecognizer flowState=$flowState")

        // If we scheduled an English start but the user hit stop quickly, cancel it.
        pendingEnglishStart?.cancel()
        pendingEnglishStart = null

        when (activeRecognizer) {
            ActiveRecognizer.ENGLISH -> {
                Log.d("VM_STOP", "Stopping English recognizer")

                // IMPORTANT: If the user stopped English listening manually and we have a partial result,
                // finalize it instead of discarding it. This handles the case where the user speaks
                // a name, sees it on screen, and clicks Stop to continue.
                if (partialTranscript.isNotBlank() && flowState is FlowState.AwaitingEnglishName) {
                    Log.d("VM_STOP", "User stopped with partial result '$partialTranscript' - finalizing it")
                    val finalText = partialTranscript
                    partialTranscript = ""

                    try {
                        englishRecognizer?.stopListening()
                    } catch (_: Throwable) {
                    }

                    activeRecognizer = ActiveRecognizer.NONE
                    dialogState = DialogState.IDLE

                    // Process the partial result as the final name
                    handleEnglishFinal(finalText)
                    return
                }

                try {
                    englishRecognizer?.stopListening()
                } catch (_: Throwable) {
                }
            }

            ActiveRecognizer.TWI -> {
                Log.d("VM_STOP", "Stopping Twi recognizer")
                try {
                    recognizer.stopListening()
                } catch (_: Throwable) {
                }
            }

            ActiveRecognizer.NONE -> {
                Log.d("VM_STOP", "No active recognizer")
                // nothing
            }
        }

        activeRecognizer = ActiveRecognizer.NONE
        dialogState = DialogState.IDLE
    }

    private fun startEnglishListeningWithPrompt(promptTwi: String, mode: SlotMode) {
        stopListening()

        try {
            val rec = recognizer as? com.example.twiassistant.asr.SilenceRecordingAsrRecognizer
            rec?.abort()
        } catch (t: Throwable) {
            Log.e("VM_ENGLISH", "Abort failed: ${t.message}")
        }

        executedAction = promptTwi
        lastError = ""
        setFlowState(FlowState.AwaitingEnglishName(mode, attemptCount = 0))
        englishAutoRetryAttempts = 0

        activeRecognizer = ActiveRecognizer.NONE
        dialogState = DialogState.IDLE
    }

    private fun handleAppNameInput(appName: String) {
        val actions = deviceActions
        if (actions == null) {
            showFriendlyError("App esom. San bue no.")
            return
        }
        
        Log.d("APP_OPEN", "Searching for app: '$appName'")
        
        val matchingApps = actions.findMatchingApps(appName, maxResults = 5)
        
        if (matchingApps.isEmpty()) {
            val matchedAppName = findBestAppMatch(appName)
            if (matchedAppName != null) {
                speak("Merebue $matchedAppName.") {
                    val success = actions.launchAppByName(matchedAppName)
                    if (success) {
                        executedAction = "Merebue $matchedAppName."
                        setFlowState(FlowState.Idle)
                    } else {
                        showFriendlyError("Mepa wo kyɛw, mentumi nbue $matchedAppName")
                    }
                }
                return
            }
            
            val allApps = actions.getInstalledAppNames().take(5)
            showFriendlyError("Menhu '$appName' app no. Apps a me hu bi ne: ${allApps.joinToString(", ")}.")
            return
        }
        
        if (matchingApps.size == 1 || matchingApps[0].equals(appName, ignoreCase = true)) {
            val appToLaunch = matchingApps[0]
            
            speak("Merebue $appToLaunch.") {
                val success = actions.launchAppByName(appToLaunch)
                
                if (success) {
                    executedAction = "Merebue $appToLaunch."
                    setFlowState(FlowState.Idle)
                } else {
                    showFriendlyError("Mepa wo kyɛw, mentumi nbue $appToLaunch.")
                }
            }
        } else {
            val appChoicesText = matchingApps.take(3).joinToString(", ")
            showFriendlyError("Mahu apps ${matchingApps.size}. Fa bako: $appChoicesText")
        }
    }

    private fun digitsIn(text: String): String? {
        val cleaned = text.filter { it.isDigit() || it == '+' }
        val digits = cleaned.count { it.isDigit() }
        return if (digits >= 8) cleaned else null
    }

    // Find all exact matches for a name (not just first one)
    private fun findAllExactMatches(name: String, candidates: List<DeviceActions.ContactMatch>): List<DeviceActions.ContactMatch> {
        val cleanedInput = cleanContactName(name).lowercase()
        return candidates.filter { candidate ->
            val cleanedCandidate = cleanContactName(candidate.displayName).lowercase()
            cleanedCandidate == cleanedInput
        }
    }
    
    // Improved fuzzy matching with stricter rules like Siri
    private fun improvedFuzzyMatch(spokenName: String, candidates: List<DeviceActions.ContactMatch>): List<DeviceActions.ContactMatch> {
        val cleanSpoken = cleanContactName(spokenName).lowercase().trim()
        if (cleanSpoken.length < 2) return emptyList() // Too short to match
        
        // Score candidates with much stricter rules, cleaning names during comparison
        return candidates.mapNotNull { candidate ->
            val score = calculateStrictSimilarityScore(spokenName, candidate.displayName)
            if (score >= 0.6) { // Much higher threshold for better precision
                candidate.copy(score = score)
            } else null
        }.sortedByDescending { it.score }
    }
    
    // Much stricter similarity scoring algorithm
    private fun calculateStrictSimilarityScore(spoken: String, candidate: String): Double {
        // Clean both names for comparison
        val cleanSpoken = cleanContactName(spoken).lowercase()
        val cleanCandidate = cleanContactName(candidate).lowercase()
        
        // Exact match after cleaning gets highest priority
        if (cleanSpoken == cleanCandidate) return 1.0
        
        // PRIORITIZE exact word matches - if spoken name matches a complete word in candidate
        val candidateWords = cleanCandidate.split(" ")
        val spokenWords = cleanSpoken.split(" ")
        
        // If spoken is a single word and matches exactly a word in candidate, very high score
        if (spokenWords.size == 1 && candidateWords.contains(cleanSpoken)) {
            // Prefer shorter names: "John Smith" over "John Smith Jr."
            return if (candidateWords.size <= 2) 0.98 else 0.85
        }
        
        // If all spoken words match words in candidate, high score
        if (spokenWords.all { spokenWord -> candidateWords.any { it == spokenWord } }) {
            return 0.95
        }
        
        // Starts with - lower score to prioritize exact word matches
        if (cleanCandidate.startsWith(cleanSpoken) && cleanSpoken.length >= 3) return 0.75
        if (cleanSpoken.startsWith(cleanCandidate) && cleanCandidate.length >= 3) return 0.70
        
        // Stricter first 3 + last 2 letters matching
        if (cleanSpoken.length >= 4 && cleanCandidate.length >= 4) {
            val spokenStart = cleanSpoken.take(3)
            val candidateStart = cleanCandidate.take(3)
            
            // First 3 letters must match exactly
            if (spokenStart == candidateStart) {
                // Additional checks for longer names to avoid "Dorcas" matching "Doris"
                if (cleanSpoken.length >= 5 && cleanCandidate.length >= 5) {
                    val spokenEnd = cleanSpoken.takeLast(2)
                    val candidateEnd = cleanCandidate.takeLast(2)
                    
                    // Both first 3 AND last 2 must match for high score
                    if (spokenEnd == candidateEnd) {
                        // Additional check: middle section similarity
                        val spokenMiddle = cleanSpoken.drop(3).dropLast(2)
                        val candidateMiddle = cleanCandidate.drop(3).dropLast(2)
                        
                        if (spokenMiddle.isEmpty() && candidateMiddle.isEmpty()) {
                            return 0.85 // Perfect 5-letter match
                        } else if (spokenMiddle == candidateMiddle) {
                            return 0.85 // Perfect middle match too
                        } else if (spokenMiddle.contains(candidateMiddle) || candidateMiddle.contains(spokenMiddle)) {
                            return 0.75 // Partial middle match
                        } else {
                            return 0.65 // First 3 + last 2 match but different middle
                        }
                    }
                    // Only first 3 match, but names are long - be more strict
                    return 0.5  // Too low to pass 0.6 threshold
                } else {
                    // Short names (4 letters), just first 3 match - be cautious
                    return 0.7
                }
            }
        }
        
        // Check if spoken name is completely contained at word boundaries
        for (word in candidateWords) {
            if (word.startsWith(cleanSpoken) && cleanSpoken.length >= 3) {
                return 0.8
            }
        }
        
        // Very strict phonetic matching for common variations plus hybrid matching
        val strictPhoneticScore = calculateStrictPhoneticScore(cleanSpoken, cleanCandidate)
        if (strictPhoneticScore > 0.6) return strictPhoneticScore
        
        // Hybrid phonetic matching for Twi names and advanced algorithms
        val hybridScore = hybridPhoneticMatch(spoken, candidate)
        if (hybridScore > 0.75) return hybridScore
        
        // Debug logging for testing
        println("Contact matching: '$cleanSpoken' vs '$cleanCandidate' -> score: 0.0 (rejected)")
        
        // If none of above match well, return low score
        return 0.0
    }
    
    // Keep original strict phonetic matching as fallback
    private fun calculateStrictPhoneticScore(spoken: String, candidate: String): Double {
        // Only match very common phonetic variations
        val phoneticPairs = mapOf(
            "k" to "c", "c" to "k",  // Kevin/Cevin
            "ph" to "f", "f" to "ph", // Philip/Filip  
            "y" to "i", "i" to "y",   // Mary/Mari
            "z" to "s", "s" to "z"    // Suzy/Zuzy
        )
        
        var normalizedSpoken = spoken
        var normalizedCandidate = candidate
        
        // Apply phonetic substitutions
        phoneticPairs.forEach { (from, to) ->
            normalizedSpoken = normalizedSpoken.replace(from, to)
            normalizedCandidate = normalizedCandidate.replace(from, to)
        }
        
        // Only return high score if they're now exactly the same or very close
        return when {
            normalizedSpoken == normalizedCandidate -> 0.85
            normalizedCandidate.startsWith(normalizedSpoken) -> 0.75
            normalizedSpoken.startsWith(normalizedCandidate) && normalizedCandidate.length >= 3 -> 0.7
            else -> 0.0
        }
    }

    // Hybrid phonetic matching system for Twi names
    private fun hybridPhoneticMatch(spoken: String, candidate: String): Double {
        val cleanSpoken = cleanContactName(spoken).lowercase()
        val cleanCandidate = cleanContactName(candidate).lowercase()
        
        // 1. Exact match gets highest score
        if (cleanSpoken == cleanCandidate) return 1.0
        
        // 2. Jaro-Winkler for name variations (great for names like Adwoa/Awja)
        val jaroWinklerScore = jaroWinklerSimilarity(cleanSpoken, cleanCandidate)
        if (jaroWinklerScore >= 0.85) return jaroWinklerScore
        
        // 3. Twi-specific phonetic mappings
        val twiPhoneticScore = twiPhoneticSimilarity(cleanSpoken, cleanCandidate)
        if (twiPhoneticScore >= 0.8) return twiPhoneticScore
        
        // 4. Metaphone for sound-alike matching
        val metaphoneScore = metaphoneSimilarity(cleanSpoken, cleanCandidate)
        if (metaphoneScore >= 0.8) return metaphoneScore
        
        // 5. Levenshtein for typos (normalized)
        val levenshteinScore = normalizedLevenshteinSimilarity(cleanSpoken, cleanCandidate)
        if (levenshteinScore >= 0.75) return levenshteinScore
        
        return 0.0
    }
    
    // Jaro-Winkler similarity (great for names)
    private fun jaroWinklerSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        
        val matchWindow = maxOf(s1.length, s2.length) / 2 - 1
        if (matchWindow < 0) return 0.0
        
        val s1Matches = BooleanArray(s1.length)
        val s2Matches = BooleanArray(s2.length)
        
        var matches = 0
        var transpositions = 0
        
        // Find matches
        for (i in s1.indices) {
            val start = maxOf(0, i - matchWindow)
            val end = minOf(i + matchWindow + 1, s2.length)
            
            for (j in start until end) {
                if (s2Matches[j] || s1[i] != s2[j]) continue
                s1Matches[i] = true
                s2Matches[j] = true
                matches++
                break
            }
        }
        
        if (matches == 0) return 0.0
        
        // Count transpositions
        var k = 0
        for (i in s1.indices) {
            if (!s1Matches[i]) continue
            while (!s2Matches[k]) k++
            if (s1[i] != s2[k]) transpositions++
            k++
        }
        
        val jaro = (matches.toDouble() / s1.length + matches.toDouble() / s2.length + 
                   (matches - transpositions / 2.0) / matches) / 3.0
        
        // Winkler prefix bonus (up to 4 chars)
        val prefixLength = minOf(4, minOf(s1.length, s2.length))
        var commonPrefix = 0
        for (i in 0 until prefixLength) {
            if (s1[i] == s2[i]) commonPrefix++ else break
        }
        
        return jaro + (0.1 * commonPrefix * (1.0 - jaro))
    }
    
    // Twi-specific phonetic similarity
    private fun twiPhoneticSimilarity(spoken: String, candidate: String): Double {
        val twiMappings = mapOf(
            // Common Twi name variations
            "adwoa" to "awja", "awja" to "adwoa",
            "adjoa" to "adwoa", "adwoa" to "adjoa",
            "agyei" to "adjei", "adjei" to "agyei", 
            "akwasi" to "kwasi", "kwasi" to "akwasi",
            "akosua" to "akos", "akos" to "akosua",
            "yaa" to "ya", "ya" to "yaa",
            "abena" to "bena", "bena" to "abena",
            "ama" to "amma", "amma" to "ama",
            "kwame" to "kwami", "kwami" to "kwame",
            "kofi" to "coffee", "coffee" to "kofi",
            "kweku" to "kwaku", "kwaku" to "kweku"
        )
        
        // Check direct mapping
        if (twiMappings[spoken] == candidate || twiMappings[candidate] == spoken) {
            return 0.9
        }
        
        // Check if one contains the other (for shortened forms)
        if (spoken.length >= 3 && candidate.length >= 3) {
            if (candidate.contains(spoken) || spoken.contains(candidate)) {
                return 0.8
            }
        }
        
        return 0.0
    }
    
    // Simple Metaphone similarity (simplified version)
    private fun metaphoneSimilarity(s1: String, s2: String): Double {
        val meta1 = simpleMetaphone(s1)
        val meta2 = simpleMetaphone(s2)
        return if (meta1 == meta2 && meta1.isNotEmpty()) 0.8 else 0.0
    }
    
    private fun simpleMetaphone(word: String): String {
        if (word.isEmpty()) return ""
        
        var metaphone = word.uppercase()
            .replace(Regex("[AEIOUYHW]"), "") // Remove vowels and silent letters
            .replace("PH", "F")
            .replace("CK", "K")
            .replace("SH", "S")
            .replace("CH", "S")
            .replace("TH", "T")
            .replace("GH", "G")
            .replace(Regex("[^A-Z]"), "")
        
        // Keep first letter, remove duplicates
        if (metaphone.isNotEmpty()) {
            val first = metaphone[0]
            metaphone = first + metaphone.drop(1).fold("") { acc, char ->
                if (acc.isEmpty() || acc.last() != char) acc + char else acc
            }
        }
        
        return metaphone.take(4) // Limit to 4 characters
    }
    
    // Normalized Levenshtein similarity
    private fun normalizedLevenshteinSimilarity(s1: String, s2: String): Double {
        val maxLen = maxOf(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        
        val distance = levenshteinDistance(s1, s2)
        return 1.0 - (distance.toDouble() / maxLen)
    }
    
    // Levenshtein distance calculation
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (s1[i-1] == s2[j-1]) {
                    dp[i-1][j-1]
                } else {
                    1 + minOf(dp[i-1][j], dp[i][j-1], dp[i-1][j-1])
                }
            }
        }
        
        return dp[m][n]
    }
    
    // Enhanced contact name cleaning for better matching
    // Examples: "A. J Roy ??" -> "AJ Roy", "Dr. Smith, Jr." -> "Dr Smith Jr"
    private fun cleanContactName(name: String): String {
        return name
            // Remove emojis and symbols
            .replace(Regex("[\\p{So}\\p{Sk}\\p{Cs}]"), "") // Remove symbols, emojis, surrogates
            // Remove common punctuation that people don't say
            .replace(Regex("[.,;:!?'\"\\-_()\\[\\]{}]"), "") // Remove punctuation
            // Remove dots and periods specifically  
            .replace(".", "")
            // Replace multiple spaces with single space
            .replace(Regex("\\s+"), " ")
            // Keep all words, not just first name - important for "AJ Roy" matching "A. J Roy"
            .trim()
    }
    
    // Find best app match using phonetic/fuzzy matching
    private fun findBestAppMatch(spokenName: String): String? {
        val actions = deviceActions ?: return null
        val installedApps = actions.getInstalledAppNames()
        
        val cleanSpoken = spokenName.lowercase().trim()
        
        // Common app name mappings for phonetic matching
        val phoneticMappings = mapOf(
            "whatsapp" to listOf("whatsapp", "what's app", "watts app", "what app"),
            "facebook" to listOf("facebook", "face book"),  
            "instagram" to listOf("instagram", "insta", "gram"),
            "chrome" to listOf("chrome", "google chrome", "browser"),
            "youtube" to listOf("youtube", "you tube"),
            "gmail" to listOf("gmail", "g mail", "email", "mail"),
            "camera" to listOf("camera", "photo", "picture"),
            "gallery" to listOf("gallery", "photos", "pictures"),
            "settings" to listOf("settings", "setting"),
            "calculator" to listOf("calculator", "calc"),
            "calendar" to listOf("calendar", "cal"),
            "contacts" to listOf("contacts", "phone book"),
            "phone" to listOf("phone", "dialer", "call"),
            "messages" to listOf("messages", "message", "sms", "text"),
            "music" to listOf("music", "player", "songs")
        )
        
        // First try exact match
        val exactMatch = installedApps.find { it.equals(cleanSpoken, ignoreCase = true) }
        if (exactMatch != null) return exactMatch
        
        // Try phonetic mappings
        for ((appName, variants) in phoneticMappings) {
            if (variants.any { it.equals(cleanSpoken, ignoreCase = true) }) {
                val foundApp = installedApps.find { it.contains(appName, ignoreCase = true) }
                if (foundApp != null) return foundApp
            }
        }
        
        // Try contains matching
        val containsMatch = installedApps.find { 
            it.contains(cleanSpoken, ignoreCase = true) || 
            cleanSpoken.contains(it.split(" ").first(), ignoreCase = true) 
        }
        
        return containsMatch
    }
    
    // Translation helpers
    private suspend fun translateTwiToEnglish(text: String): String {
        val t = translator ?: return text
        return t.translate(text, "tw-en")?.ifBlank { null } ?: text
    }

    private suspend fun translateEnglishToTwi(text: String): String {
        val t = translator ?: return text
        return t.translate(text, "en-tw")?.ifBlank { null } ?: text
    }

    // Feature icon handlers - called when user taps feature icons
    fun onCallFeatureSelected() {
        uiMode = UiMode.CALL
        executedAction = "Mepa wo kyɛw, hena na wopɛ sɛ mefr??"
        setFlowState(FlowState.AwaitingEnglishName(SlotMode.CALL))
        lastError = ""
    }

    fun onMessageFeatureSelected() {
        uiMode = UiMode.SMS
        executedAction = "Hena na wopɛ sɛ mekyer?w krataa no k?ma?"
        setFlowState(FlowState.AwaitingEnglishName(SlotMode.SMS))
        lastError = ""
    }

    fun onOpenAppFeatureSelected() {
        uiMode = UiMode.OPEN_APPS
        executedAction = "App b?n na wopɛ sɛ mebue?"
        setFlowState(FlowState.AwaitingEnglishName(SlotMode.OPEN_APP))
        lastError = ""
    }

    private suspend fun readUnreadMessages(): String = withContext(Dispatchers.IO) {
        val actions = deviceActions ?: return@withContext "Entumi mma krataa no."
        if (!actions.hasReadSmsPermission()) {
            lastError = "Please allow Sheri to read inbox"
            return@withContext "Please allow Sheri to read inbox"
        }

        val messages = actions.getUnreadMessages(limit = 5)
        if (messages.isEmpty()) return@withContext "Nkyer?w foforo biara nni h?."

        val builder = StringBuilder()
        for ((index, msg) in messages.withIndex()) {
            val idxLabel = index + 1
            val bodyTwi = try {
                safeTranslateEnglishToTwi(msg.body)
            } catch (_: Throwable) {
                msg.body
            }
            builder.append("Nkyer?w $idxLabel, fi ${msg.address}. $bodyTwi. ")
        }
        builder.toString().trim()
    }

    // Parse a number from Twi or English speech
    private fun parseNumberFromText(text: String): Int? {
        val lower = text.lowercase(Locale.ROOT).trim()

        // Try direct digit
        val digits = lower.filter { it.isDigit() }
        if (digits.isNotEmpty()) {
            return digits.toIntOrNull()
        }

        // Twi number words
        val twiNumbers = mapOf(
            "baako" to 1, "biako" to 1,
            "mmienu" to 2, "mienu" to 2,
            "mmi?nsa" to 3, "miensa" to 3, "mmiensa" to 3,
            "?nan" to 4, "nan" to 4, "enan" to 4,
            "enum" to 5, "?num" to 5,
            "nsia" to 6,
            "nson" to 7,
            "nw?twe" to 8, "aw?twe" to 8,
            "nkron" to 9,
            "du" to 10
        )

        for ((word, num) in twiNumbers) {
            if (lower.contains(word)) return num
        }

        // English number words
        val englishNumbers = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10
        )

        for ((word, num) in englishNumbers) {
            if (lower.contains(word)) return num
        }

        return null
    }

    // Handle alarm time input
    private fun handleAlarmTimeInput(timeText: String) {
        val lower = timeText.lowercase(Locale.ROOT)

        // Try to parse hour and minute from the text
        // Patterns: "7 30", "7:30", "nn?nhwerew 7 simma 30", "7 o'clock", etc.
        val numbers = Regex("\\d+").findAll(lower).map { it.value.toIntOrNull() }.filterNotNull().toList()

        val hour: Int
        val minute: Int

        when {
            numbers.size >= 2 -> {
                hour = numbers[0]
                minute = numbers[1]
            }
            numbers.size == 1 -> {
                hour = numbers[0]
                minute = 0
            }
            else -> {
                // Try Twi number words
                val parsedNum = parseNumberFromText(timeText)
                if (parsedNum != null) {
                    hour = parsedNum
                    minute = 0
                } else {
                    lastError = "Mepa wo kyɛw, ka bere no bio. Ka nn?nhwerew ne simma no."
                    executedAction = ""
                    dialogState = DialogState.IDLE
                    return
                }
            }
        }

        if (hour < 0 || hour > 23) {
            lastError = "Mepa wo kyɛw, ka bere pa. Nn?nhwerew 0 k?si 23."
            executedAction = ""
            dialogState = DialogState.IDLE
            return
        }

        val actions = deviceActions
        if (actions == null) {
            lastError = "Entumi ansiesie alarm no."
            executedAction = ""
            setFlowState(FlowState.Idle)
            dialogState = DialogState.IDLE
            return
        }

        // Set the alarm - pass time as string "HH:MM"
        val timeStr = String.format(Locale.ROOT, "%d:%02d", hour, minute)
        val success = actions.setAlarm(timeStr)
        if (success) {
            executedAction = "Masiesie alarm ama wo wɔ $timeStr."
            lastError = ""
        } else {
            lastError = "Entumi ansiesie alarm no."
            executedAction = ""
        }
        setFlowState(FlowState.Idle)
        dialogState = DialogState.IDLE
    }

    private fun handlePartial(text: String) {
        // User started speaking - stop any ongoing TTS immediately
        if (text.isNotBlank()) {
            ttsEngine?.stop()
        }
        partialTranscript = text
        dialogState = DialogState.LISTENING
    }

    private fun handleFinal(text: String) {
        if (text == lastUtterance && dialogState == DialogState.EXECUTE) return
        
        lastUtterance = text
        partialTranscript = ""
        lastHeard = text
        
        // User finished speaking - stop any ongoing TTS
        ttsEngine?.stop()
        
        // Only three features: Calls, Messages, Open Apps
        when (val state = flowState) {
            is FlowState.AwaitingSmsBody -> {
                val body = text.trim()
                if (body.isBlank()) {
                    showFriendlyError("Ka asɛm no bio.")
                    return
                }

                // Show processing ONLY when translation work starts
                setSpeechAnalysis(true) // Analysis phase first
                executedAction = "Meresesa no..." // Translating...
                dialogState = DialogState.EXECUTE
                
                viewModelScope.launch {
                    // Switch to processing phase
                    setSpeechAnalysis(false)
                    setProcessing(true)
                    
                    val messageToSend = try {
                        withContext(Dispatchers.IO) { improvedTranslateTwi(body) }
                    } catch (e: Exception) {
                        Log.e("VM_SMS", "Translation failed", e)
                        body // Use original Twi text as fallback
                    }

                    val contact = selectedSmsContact
                    if (contact != null) {
                        val intent = IntentResult.SendSms(nameOrNumber = contact.number, body = messageToSend, c = 0.9f)
                        val result = performAction(intent)
                        executedAction = if (result != null) {
                            "asɛm no ak?"
                        } else {
                            "Entumi ansoma"
                        }
                        selectedSmsContact = null
                    } else {
                        showFriendlyError("Manhu din no wɔ wo contacts mu")
                    }
                    setProcessing(false)
                    setSpeechAnalysis(false)
                    setFlowState(FlowState.Idle)
                    dialogState = DialogState.IDLE
                }
                return
            }
            
            is FlowState.AwaitingWhatsAppBody -> {
                val body = text.trim()
                if (body.isBlank()) {
                    showFriendlyError("Ka asɛm no bio.")
                    return
                }
                // IMMEDIATELY show processing when translation starts
                setProcessing(true) // Start processing indicator first
                setSpeechAnalysis(false) // Clear analysis state
                executedAction = "Meresesa Twi k? borɔfo mu..." // Translating Twi to English...
                dialogState = DialogState.EXECUTE // Show we're actively working
                viewModelScope.launch {
                    // Always translate Twi input to English
                    val messageToSend = try {
                        Log.d("Translation", "Starting translation for WhatsApp body: '$body'")
                        withContext(Dispatchers.IO) { improvedTranslateTwi(body) }
                    } catch (e: Exception) {
                        Log.e("VM_WHATSAPP", "Translation failed for: '$body'", e)
                        // Fallback: send original Twi message
                        Log.w("Translation", "Using original Twi as fallback: '$body'")
                        executedAction = "Translation amfa. Mereka Twi mu ara..." // Translation failed... I'll send in Twi itself...
                        speak(executedAction)
                        body // Use original Twi text
                    }
                    
                    // Send WhatsApp with the processed message (translated or original)
                    val contact = selectedWhatsAppContact
                    val actions = deviceActions
                    if (contact != null && actions != null) {
                        val success = actions.sendWhatsAppMessage(contact.number, messageToSend)
                        executedAction = if (success) {
                            "Whats App asɛm no... ak?" // WhatsApp message... sent
                        } else {
                            "Whats App... entumi ansoma" // WhatsApp... could not send
                        }
                        selectedWhatsAppContact = null // Clear after sending
                    } else {
                        executedAction = "Whats App sending error - no contact or actions available"
                    }
                    setProcessing(false)
                    setSpeechAnalysis(false)
                    setFlowState(FlowState.Idle)
                    dialogState = DialogState.IDLE
                }
                return
            }
            
            is FlowState.AwaitingAppName -> {
                val appName = text.trim()
                if (appName.lowercase() in listOf("ka", "cancel", "back", "home", "fie")) {
                    executedAction = "Ok, me san k?? fie."
                    setFlowState(FlowState.Idle)
                    return
                }
                handleAppNameInput(appName)
                return
            }
            
            else -> {
                // Handle Twi name input in AwaitingTwiName state
                if (flowState is FlowState.AwaitingTwiName) {
                    handleTwiName(text.trim(), flowState as FlowState.AwaitingTwiName)
                    return
                }

                // Continue to voice commands processing
            }
        }

        // If we previously asked the user to choose between two contacts, treat this utterance as the choice.
        val pending = pendingChoice
        if (pending != null) {
            val result = handlePendingChoice(pending, text)
            if (result != null) {
                executedAction = result
                lastError = ""
            } else {
                lastError = "Mepa wo kyɛw, ka 'baako' anaa 'mmienu'."
                executedAction = ""
            }
            dialogState = DialogState.IDLE
            return
        }

        val lower = normalizeCommandText(text)

        // --- Calls ---
        if (lower.contains("fr?")) {
            uiMode = UiMode.CALL
            val number = digitsIn(lower)
            if (number != null) {
                val intent = IntentResult.CallNumber(number = number, c = 0.9f)
                dialogState = DialogState.EXECUTE
                val result = performAction(intent)
                executedAction = result ?: "Mentumi amfrɛ n?mba no"
                setFlowState(FlowState.Idle)
                return
            }
            startEnglishListeningWithPrompt(
                promptTwi = "Hwan na wopɛ sɛ wofr??",
                mode = SlotMode.CALL
            )
            return
        }

        // --- Messages ---
        if (lower.contains("ky?r?") || lower.contains("krataa") || lower.contains("sms")) {
            uiMode = UiMode.SMS
            startEnglishListeningWithPrompt(
                promptTwi = "Hwan na wopɛ sɛ wokyer??",
                mode = SlotMode.SMS
            )
            return
        }

        // --- Open Apps ---
        if (lower.contains("bue")) {
            uiMode = UiMode.OPEN_APPS
            startEnglishListeningWithPrompt(
                promptTwi = "D?n app na wopɛ sɛ mebue?",
                mode = SlotMode.OPEN_APP
            )
            return
        }



        // If not recognized, show friendly error
        showFriendlyError("mfomsoɔ bi aba. Mepa wo kyɛw, san ka asɛm no bio.")
    }

    /**
     * Handles Twi name input for both calls and messages
     */
    private fun handleTwiName(name: String, currentState: FlowState.AwaitingTwiName) {
        if (!validateDeviceActionsAndPermissions()) return
        
        // Show processing during contact search
        setSpeechAnalysis(true)
        executedAction = "Mehwehwɔ $name..."
        dialogState = DialogState.EXECUTE
        
        val actions = deviceActions!!
        val allCandidates = actions.findPhoneCandidatesByName(name, maxResults = 10)
        val exactMatches = findAllExactMatches(name, allCandidates)
        
        // Clear processing after search
        setSpeechAnalysis(false)
        
        // If we found matches, process them
        if (exactMatches.isNotEmpty()) {
            handleFoundContacts(exactMatches, name, isCall = currentState.mode == SlotMode.CALL)
            return
        }

        val bestMatches = improvedFuzzyMatch(name, allCandidates).take(3)
        if (bestMatches.isNotEmpty()) {
            handleFoundContacts(bestMatches, name, isCall = currentState.mode == SlotMode.CALL)
            return
        }

        // No matches found - try English one more time or give up
        when (currentState.attemptCount) {
            1 -> {
                // First Twi attempt failed, switch back to English (but don't tell user)
                executedAction = "Ka din no bio."
                setFlowState(FlowState.AwaitingEnglishName(currentState.mode, attemptCount = 2))
            }
            else -> {
                // Final attempt failed
                executedAction = "Mentumi anhu din no wɔ wo contacts mu. Hwɔ s? din no wɔ h? anaa ka 'ka' sɛ wopɛ sɛ wo san k? fie."
                setFlowState(FlowState.Idle)
            }
        }
    }

    private fun handleEnglishPartial(text: String) {
        Log.d("VM_ENGLISH", "handleEnglishPartial: text='$text' flowState=$flowState")
        partialTranscript = text
        dialogState = DialogState.LISTENING
    }

    private fun handleEnglishFinal(text: String) {
        val name = text.trim()
        Log.d("VM_ENGLISH", "handleEnglishFinal: text='$text' name='$name' flowState=$flowState")
        
        partialTranscript = ""
        lastHeard = name

        englishAutoRetryAttempts = 0

        activeRecognizer = ActiveRecognizer.NONE
        pendingEnglishStart?.cancel()
        pendingEnglishStart = null

        // Stop English recognizer session.
        try {
            englishRecognizer?.stopListening()
        } catch (_: Throwable) {
        }

        // Original flow continues...

        when (val state = flowState) {
            is FlowState.AwaitingEnglishName -> {
                Log.d("VM_ENGLISH", "Processing English name: $name, attempt: ${state.attemptCount}")
                if (name.isBlank()) {
                    showFriendlyError("Mepa wo kyɛw, ka din no bio.")
                    return
                }

                when (state.mode) {
                    SlotMode.CALL -> {
                        handleCallNameEnglish(name, state)
                    }

                    SlotMode.SMS -> {
                        handleSmsNameEnglish(name, state)
                    }
                    
                    SlotMode.OPEN_APP -> {
                        handleAppNameInput(name)
                    }
                }
            }

            is FlowState.AwaitingSmsBody, is FlowState.AwaitingWhatsAppBody -> {
                // Redirect to Twi for message content
                setSpeechAnalysis(false)
                showFriendlyError("Mepa wo kyɛw, ka asɛm no wɔ Twi mu.")
            }

            else -> {
                // Ignore unexpected English input  
                setSpeechAnalysis(false)
                dialogState = DialogState.IDLE
            }
        }
    }

    private fun handleEnglishError(message: String) {
        val current = flowState
        partialTranscript = ""
        dialogState = DialogState.IDLE
        activeRecognizer = ActiveRecognizer.NONE

        pendingEnglishStart?.cancel()
        pendingEnglishStart = null

        try {
            englishRecognizer?.stopListening()
        } catch (_: Throwable) {
        }

        val code = Regex("Speech error\\s+(\\d+)").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
        Log.e("VM_ERROR", "English error: $message")

        if (current is FlowState.AwaitingEnglishName) {
            val errorMsg = when (code) {
                6, 7 -> "Mentee. sɛ bio."
                5, 8 -> "Tw?n kakra na sɛ bio."
                9 -> "Mic kwan nni h?."
                else -> "asɛm bi aba. sɛ bio."
            }
            showFriendlyError(errorMsg)
            return
        }

        showFriendlyError("Mepa wo kyɛw, asɛm bi asi. San yɛ bio.")
        setFlowState(FlowState.AwaitingCommandTwi)
    }

    private fun lookupWikipediaAndRespond(query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                wikipediaLookup(query)
            } catch (t: Throwable) {
                null
            }

            val translated = try {
                translateInternetResultToTwi(result)
            } catch (_: Throwable) {
                result
            }

            withContext(Dispatchers.Main) {
                if (translated.isNullOrBlank()) {
                    lastError = "Entumi anhu ns?m wɔ internet so" // couldn't find info
                    executedAction = ""
                } else {
                    executedAction = translated
                    lastError = ""
                }
                dialogState = DialogState.IDLE
            }
        }
    }

    private suspend fun translateInternetResultToTwi(englishText: String?): String? {
        val eng = englishText?.trim().orEmpty()
        if (eng.isBlank()) return null

        // Keep payloads small to avoid API limits/timeouts.
        val compact = eng.replace("\n", " ").replace("\r", " ").replace(Regex("\\s+"), " ").trim()
        val clipped = if (compact.length > 800) compact.take(800) else compact

        val t = translator ?: return englishText
        return t.translate(clipped, langPair = "en-tw") ?: englishText
    }

    private suspend fun wikipediaLookup(query: String): String? {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.toString())
        val openSearchUrl = "https://en.wikipedia.org/w/api.php?action=opensearch&search=$encoded&limit=1&namespace=0&format=json"

        val openReq = Request.Builder().url(openSearchUrl).get().build()
        val openBody = httpClient.newCall(openReq).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string().orEmpty()
        }
        if (openBody.isBlank()) return null

        val arr = JSONArray(openBody)
        val titles = arr.optJSONArray(1)
        val descriptions = arr.optJSONArray(2)
        val title = titles?.optString(0).orEmpty()
        val desc = descriptions?.optString(0).orEmpty()
        if (title.isBlank() && desc.isNotBlank()) return desc
        if (title.isBlank()) return null

        val summaryTitle = URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8.toString())
        val summaryUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/$summaryTitle"
        val summaryReq = Request.Builder().url(summaryUrl).get().build()
        val summaryBody = httpClient.newCall(summaryReq).execute().use { resp ->
            if (!resp.isSuccessful) return desc.ifBlank { null }
            resp.body?.string().orEmpty()
        }
        if (summaryBody.isBlank()) return desc.ifBlank { null }

        val json = JSONObject(summaryBody)
        val extract = json.optString("extract").trim()
        return extract.ifBlank { desc.ifBlank { null } }
    }

    private fun handleError(message: String) {
        // Convert technical errors to user-friendly messages
        lastError = when {
            message.contains("Speech error 6", ignoreCase = true) || message.contains("ERROR_NO_MATCH", ignoreCase = true) ->
                "Mepa wo kyɛw, mentee. Mia ha na sɛ bio."
            message.contains("Speech error 7", ignoreCase = true) || message.contains("ERROR_SPEECH_TIMEOUT", ignoreCase = true) ->
                "Mepa wo kyɛw, mentee. Mia ha na sɛ bio."
            message.contains("Speech error 5", ignoreCase = true) || message.contains("ERROR_CLIENT", ignoreCase = true) ->
                "Mic no rey? adwuma. Tw?n kakra."
            message.contains("Speech error 8", ignoreCase = true) || message.contains("ERROR_RECOGNIZER_BUSY", ignoreCase = true) ->
                "Mic no rey? adwuma. Tw?n kakra."
            message.contains("Speech error 9", ignoreCase = true) || message.contains("permission", ignoreCase = true) ->
                "Mepa wo kyɛw, mic permission no nni h?."
            message.contains("network", ignoreCase = true) || message.contains("internet", ignoreCase = true) ->
                "Internet nni h?. Hwɔ wo connection."
            else -> message // Keep original message if not a known technical error
        }
        partialTranscript = ""
        dialogState = DialogState.IDLE
    }

    fun clearError() {
        lastError = ""
    }
    
    // Public methods for contact picker
    fun selectContact(contact: DeviceActions.ContactMatch) {
        // Stop any ongoing TTS since user took action
        ttsEngine?.stop()
        
        val pending = _pendingChoice ?: return
        
        // Clear any existing action first
        executedAction = ""
        
        when (pending) {
            is PendingChoice.Call -> {
                val actions = deviceActions
                if (actions != null && actions.hasCallPhonePermission()) {
                    actions.dialOrCall(contact.number)
                    executedAction = "Merefrɛ ${contact.displayName}. Mepa wo kyɛw, tw?n kakra."
                    // Speak the action
                    viewModelScope.launch {
                        delay(100) // Brief delay to let TTS engine process
                        ttsEngine?.speak(executedAction)
                    }
                } else {
                    executedAction = "Ma kwan na menya frɛ permission"
                }
                _pendingChoice = null
                setFlowState(FlowState.Idle)
                dialogState = DialogState.IDLE
            }
            is PendingChoice.SmsName -> {
                // Show SMS vs WhatsApp choice immediately  
                _pendingChoice = PendingChoice.MessageChoice(contact, "messaging")
                executedAction = "Wopɛ sɛ mede dia ?wɔ he kyer??"
                // Don't change flow state - let the UI handle the choice
            }
            is PendingChoice.MessageChoice -> {
                // This shouldn't happen, but handle gracefully
                _pendingChoice = null
                setFlowState(FlowState.Idle)
                dialogState = DialogState.IDLE
                executedAction = "Mey? bi. D?n bio?" // I did something. What else?
            }
            is PendingChoice.Sms -> {
                val actions = deviceActions
                if (actions != null) {
                    try {
                        actions.sendSms(contact.number, pending.body)
                        executedAction = "SMS k?? ${contact.displayName} nky?n."
                        // Speak the action
                        viewModelScope.launch {
                            delay(100)
                            ttsEngine?.speak(executedAction)
                        }
                    } catch (e: Exception) {
                        executedAction = "Entumi anka SMS no."
                    }
                } else {
                    executedAction = "SMS sending error"
                }
                _pendingChoice = null
                setFlowState(FlowState.Idle)
                dialogState = DialogState.IDLE
            }
            is PendingChoice.WhatsApp -> {
                val actions = deviceActions
                if (actions != null) {
                    try {
                        val success = actions.sendWhatsAppMessage(contact.number, pending.body)
                        executedAction = if (success) {
                            "WhatsApp message k?? ${contact.displayName} nky?n."
                        } else {
                            "Entumi amfa WhatsApp message no ansoma. ${contact.displayName} nni WhatsApp."
                        }
                        // Speak the action
                        viewModelScope.launch {
                            delay(100)
                            ttsEngine?.speak(executedAction)
                        }
                    } catch (e: Exception) {
                        executedAction = "Entumi amfa WhatsApp message no ansoma."
                    }
                } else {
                    executedAction = "WhatsApp sending error"
                }
                _pendingChoice = null
                setFlowState(FlowState.Idle)
                dialogState = DialogState.IDLE
            }
        }
    }
    
    fun clearPendingChoice() {
        // Stop any ongoing TTS when user dismisses overlay
        ttsEngine?.stop()
        
        _pendingChoice = null
        setFlowState(FlowState.Idle)
        dialogState = DialogState.IDLE
        executedAction = "Ma gyae. Ad?n bio na wo pɛ sɛ men y? ma wo?"
    }

    
    // Handle S M S vs WhatsApp choice - now goes directly to Twi input
    fun selectMessageType(contact: DeviceActions.ContactMatch, useWhatsApp: Boolean) {
        // Stop any ongoing TTS since user took action
        ttsEngine?.stop()
        
        // Clear the message choice to dismiss the popup
        clearPendingChoice()
        
        // Go directly to Twi message input (will translate to English before sending)
        if (useWhatsApp) {
            speak("Ka asɛm no wɔ Twi mu.") // Say the message in Twi
            setFlowState(FlowState.AwaitingWhatsAppBody(targetName = contact.number))
            selectedWhatsAppContact = contact
        } else {
            speak("Ka asɛm no wɔ Twi mu.") // Say the message in Twi
            setFlowState(FlowState.AwaitingSmsBody(targetName = contact.number))
            selectedSmsContact = contact
        }
    }
    
    // Improved context-aware translation with phrase-based algorithms
    private suspend fun improvedTranslateTwi(text: String): String {
        return try {
            val cleanedText = text.trim()
            if (cleanedText.isBlank()) return cleanedText
            
            Log.d("Translation", "Translating Twi text: '$cleanedText'")
            
            // Use Google Translate API for Twi to English translation
            val result = safeTranslateTwiToEnglish(cleanedText)
            Log.d("Translation", "Translation result: '$result'")
            result
        } catch (e: Exception) {
            Log.e("Translation", "Translation failed for '$text': ${e.message}", e)
            // Fallback to original text if API fails
            text
        }
    }

    private fun describeAction(intent: IntentResult): String = when (intent) {
        is IntentResult.CallContact -> "Refrɛ ${intent.name}"
        is IntentResult.CallNumber -> "Refrɛ n?mba ${intent.number}"
        is IntentResult.SendSms -> "Rekyer?w SMS k?ma ${intent.nameOrNumber}"
        is IntentResult.ReadMessages -> "Reb?kenkan nkyer?w"
        is IntentResult.SetAlarm -> "Resie aboter? wɔ ${intent.timeText}"
        is IntentResult.OpenApp -> "Rebue ${intent.appName}"
        is IntentResult.StatusQuery -> intent.topic
        is IntentResult.MenuSelection -> "Menu option ${intent.option}"
        is IntentResult.AdjustBrightness -> when (intent.action) {
            BrightnessAction.SET -> "Brightness to ${intent.percent ?: 50}%"
            BrightnessAction.UP -> "Increasing brightness"
            BrightnessAction.DOWN -> "Decreasing brightness"
            BrightnessAction.MAX -> "Brightness max"
            BrightnessAction.MIN -> "Brightness min"
        }
        IntentResult.Unknown -> "Hia ns?m bio"
    }

    private fun performAction(intent: IntentResult): String? {
        val actions = deviceActions ?: return describeAction(intent)
        return try {
            when (intent) {
                is IntentResult.CallContact -> {
                    if (!actions.hasReadContactsPermission()) {
                        "Please allow Sheri to access Contacts"
                    } else {
                        val number = actions.findPhoneNumberByName(intent.name)
                        if (number != null) {
                            if (!actions.hasCallPhonePermission()) {
                                "Please allow Sheri to make calls"
                            } else {
                                actions.dialOrCall(number)
                                "Refrɛ ${intent.name}"
                            }
                        } else {
                            val candidates = actions.findPhoneCandidatesByName(intent.name, maxResults = 3)
                            val prompt = maybeAskToDisambiguateCall(candidates)
                            prompt ?: "Sorry, I couldn't find a number for ${intent.name}"
                        }
                    }
                }
                is IntentResult.CallNumber -> {
                    if (!actions.hasCallPhonePermission()) {
                        "Please allow Sheri to make calls"
                    } else {
                        actions.dialOrCall(intent.number)
                        "Refrɛ n?mba ${intent.number}"
                    }
                }
                is IntentResult.SendSms -> {
                    if (intent.nameOrNumber.isBlank()) return null
                    val target = intent.nameOrNumber
                    val number = if (target.any { it.isDigit() }) {
                        target
                    } else {
                        if (!actions.hasReadContactsPermission()) {
                            return "Please allow Sheri to access Contacts"
                        }
                        actions.findPhoneNumberByName(target)
                            ?: run {
                                val candidates = actions.findPhoneCandidatesByName(target, maxResults = 2)
                                val prompt = maybeAskToDisambiguateSms(intent.body, candidates)
                                return prompt ?: "Sorry, I couldn't find a number for $target"
                            }
                    }

                    // Check SMS permission
                    if (!actions.hasSendSmsPermission()) {
                        return "Please allow Sheri to send SMS"
                    }

                    // Send the SMS and return appropriate response
                    val success = actions.sendSms(number, intent.body)
                    if (success) {
                        "Rekyer?w SMS k?ma $target"
                    } else {
                        "Could not send SMS to $target"
                    }
                }
                is IntentResult.SetAlarm -> {
                    val ok = actions.setAlarm(intent.timeText)
                    if (ok) "Alarm set for ${intent.timeText}" else null
                }
                is IntentResult.OpenApp -> {
                    val ok = actions.launchAppByName(intent.appName)
                    if (ok) "Rebue ${intent.appName}" else null
                }
                is IntentResult.StatusQuery -> "Status/phone info"
                is IntentResult.ReadMessages -> {
                    if (!actions.hasReadSmsPermission()) {
                        "Please allow Sheri to read inbox"
                    } else {
                        val msgs = actions.getUnreadMessages(limit = 3)
                        if (msgs.isEmpty()) {
                            "Nkyer?w foforo biara nni h?"
                        } else {
                            msgs.joinToString(". ") { m -> "Fi ${m.address}: ${m.body}" }
                        }
                    }
                }
                is IntentResult.AdjustBrightness -> {
                    // Check if we have permission first
                    if (!actions.canWriteSettings()) {
                        actions.openWriteSettingsPermission()
                        "Mepa wo kyɛw, mma Sheri akwan na ?nsesa hann. S? toggle no wɔ page a ?bɛbue no so."
                    } else {
                        val ok = actions.adjustBrightness(intent.action, intent.percent)
                        if (ok) {
                            when (intent.action) {
                                BrightnessAction.SET -> "Hann no ay? ${intent.percent ?: 50} percent"
                                BrightnessAction.UP -> "Hann no ak? soro"
                                BrightnessAction.DOWN -> "Hann no ak? fam"
                                BrightnessAction.MAX -> "Hann no ay? denden"
                                BrightnessAction.MIN -> "Hann no ay? kumaa"
                            }
                        } else {
                            "Entumi ansesa hann no"
                        }
                    }
                }
                else -> null
            }
        } catch (e: SecurityException) {
            val msg = e.message.orEmpty()
            when {
                msg.contains("READ_CONTACTS", ignoreCase = true) -> "Please allow Sheri to access Contacts"
                msg.contains("CALL_PHONE", ignoreCase = true) -> "Please allow Sheri to make calls"
                msg.contains("SEND_SMS", ignoreCase = true) -> "Please allow Sheri to send SMS"
                msg.contains("WRITE_SETTINGS", ignoreCase = true) -> "Please allow Sheri to modify system settings"
                else -> "Permission required"
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun maybeAskToDisambiguateCall(candidates: List<DeviceActions.ContactMatch>): String? {
        if (candidates.size < 2) return null
        val a = candidates[0]
        val b = candidates[1]
        val c = candidates.getOrNull(2)
        // Medium-confidence zone: ask the user instead of guessing.
        if (a.score < 0.55 || b.score < 0.50) return null

        val candidateList = if (c != null && c.score >= 0.50) listOf(a, b, c) else listOf(a, b)
        _pendingChoice = PendingChoice.Call(candidates = candidateList)
        
        return if (c != null && c.score >= 0.50) {
            "Me huu nnipa pii a w?n din te s? eyi. Wopɛ sɛ merefrɛ ${a.displayName}, ${b.displayName}, anaa ${c.displayName}? Ka 'baako', 'mmienu', anaa 'mmi?nsa'."
        } else {
            "Me huu nnipa pii a w?n din te s? eyi. Wopɛ sɛ merefrɛ ${a.displayName} anaa ${b.displayName}? Ka 'baako' anaa 'mmienu'."
        }
    }

    private fun maybeAskToDisambiguateSms(body: String, candidates: List<DeviceActions.ContactMatch>): String? {
        if (candidates.size < 2) return null
        val a = candidates[0]
        val b = candidates[1]
        if (a.score < 0.55 || b.score < 0.50) return null

        _pendingChoice = PendingChoice.Sms(body = body, candidates = listOf(a, b))
        return "Me huu nnipa pii a w?n din te s? eyi. Wopɛ sɛ mekyer?w SMS k?ma ${a.displayName} anaa ${b.displayName}? Ka 'baako' anaa 'mmienu'."
    }

    private fun maybeAskToDisambiguateSmsName(candidates: List<DeviceActions.ContactMatch>): String? {
        if (candidates.size < 2) return null
        val a = candidates[0]
        val b = candidates[1]
        if (a.score < 0.55 || b.score < 0.50) return null

        _pendingChoice = PendingChoice.SmsName(candidates = listOf(a, b))
        return "Me huu nnipa pii a w?n din te s? eyi. Wopɛ sɛ mekyer?w SMS k?ma ${a.displayName} anaa ${b.displayName}? Ka 'baako' anaa 'mmienu'."
    }

    private fun handlePendingChoice(pending: PendingChoice, utterance: String): String? {
        val actions = deviceActions ?: return null
        val lower = utterance.trim().lowercase()
        if (lower.isBlank()) return null

        val candidates = when (pending) {
            is PendingChoice.Call -> pending.candidates
            is PendingChoice.SmsName -> pending.candidates
            is PendingChoice.Sms -> pending.candidates
            is PendingChoice.WhatsApp -> pending.candidates
            is PendingChoice.MessageChoice -> emptyList() // This type doesn't have candidates
        }
        if (candidates.size < 2) {
            _pendingChoice = null
            return null
        }

        fun pickByIndex(i: Int): DeviceActions.ContactMatch? = candidates.getOrNull(i)

        val choice: DeviceActions.ContactMatch? = when {
            // Numeric
            Regex("\\b1\\b").containsMatchIn(lower) -> pickByIndex(0)
            Regex("\\b2\\b").containsMatchIn(lower) -> pickByIndex(1)
            Regex("\\b3\\b").containsMatchIn(lower) -> pickByIndex(2)

            // English
            lower.contains("first") || lower.contains("one") -> pickByIndex(0)
            lower.contains("second") || lower.contains("two") -> pickByIndex(1)
            lower.contains("third") || lower.contains("three") -> pickByIndex(2)

            // Twi
            lower.contains("baako") || lower.contains("nea edi kan") || lower.contains("edi kan") -> pickByIndex(0)
            lower.contains("mmienu") || lower.contains("abien") || lower.contains("nea ?to so abien") || lower.contains("?to so abien") -> pickByIndex(1)
            lower.contains("mmi?nsa") || lower.contains("abiesa") || lower.contains("nea ?to so abiesa") || lower.contains("?to so abiesa") -> pickByIndex(2)

            // Name match
            else -> {
                candidates.firstOrNull { candidate ->
                    val candidateName = candidate.displayName.lowercase()
                    val firstName = candidateName.split(" ").firstOrNull().orEmpty()
                    lower.contains(candidateName) || lower.contains(firstName)
                }            }
        }

        if (choice == null) return null

        _pendingChoice = null
        return when (pending) {
            is PendingChoice.Call -> {
                if (!actions.hasCallPhonePermission()) {
                    "Please allow Sheri to make calls"
                } else {
                    actions.dialOrCall(choice.number)
                    "Refrɛ ${choice.displayName}"
                }
            }
            is PendingChoice.SmsName -> {
                executedAction = "Ka asɛm a wopɛ sɛ mekyer? k?ma ${choice.displayName}." // say the message (Twi)
                lastError = ""
                setFlowState(FlowState.AwaitingSmsBody(targetName = choice.number))
                // User presses button manually - no auto-listen
                executedAction
            }
            is PendingChoice.Sms -> {
                actions.sendSms(choice.number, pending.body)
                "Rekyer?w SMS k?ma ${choice.displayName}"
            }
            is PendingChoice.WhatsApp -> {
                val success = actions.sendWhatsAppMessage(choice.number, pending.body)
                if (success) {
                    "Rekyer?w WhatsApp message k?ma ${choice.displayName}"
                } else {
                    "Entumi amfa WhatsApp message no ansoma k?ma ${choice.displayName}"
                }
            }
            is PendingChoice.MessageChoice -> {
                // This shouldn't happen since MessageChoice doesn't have candidates
                "Handled in different flow"
            }
        }
    }
    
    // Get current flow state description for user
    fun getCurrentStateDescription(): String {
        return when (val state = flowState) {
            is FlowState.Idle -> "Me gyina ha, ka biara a wob?ka" // I'm ready, say anything
            is FlowState.AwaitingSmsBody -> "Ka asɛm a wopɛ sɛ mek?ma ${state.targetName}" // Say the message you want to send to...
            is FlowState.AwaitingWhatsAppBody -> "Ka asɛm a wopɛ sɛ mek?ma ${state.targetName} wɔ Whats App so" // Say the message you want to send to... on WhatsApp
            is FlowState.AwaitingAppName -> "Ka app a wopɛ sɛ mebue no din" // Say the name of the app you want to open
            is FlowState.AwaitingAppName -> "Ka app a wopɛ sɛ mebue no din" // Say the name of the app you want to open
            is FlowState.Error -> state.message
            else -> "Medwene ho..." // I'm thinking about it...
        }
    }
    
    // Cancel current operation
    fun cancelCurrentOperation() {
        _pendingChoice = null
        setProcessing(false)
        setSpeechAnalysis(false)
        isProcessingPhoto = false
        setFlowState(FlowState.Idle)
        dialogState = DialogState.IDLE
        speak(" Me gyae... D?n bio?") // Ok, I've stopped... What else?
    }
    
    // Check if user said cancel command - more specific detection
    private fun isCancel(text: String): Boolean {
        val lowerText = text.lowercase().trim()
        // Only trigger cancel if the ENTIRE phrase is a cancel command
        // Not just if it contains these words somewhere in a longer sentence
        val exactCancelPhrases = listOf(
            "cancel",
            "stop",
            "gyae",  // stop in Twi
            "kwaa",  // cancel in Twi
            "gyae ?",  // stop it
            "gyae no",  // stop it
            "cancel it",
            "stop it"
        )
        
        // Check if the text is exactly a cancel phrase or starts with one
        return exactCancelPhrases.any { phrase ->
            lowerText == phrase || lowerText.startsWith("$phrase ") || lowerText.endsWith(" $phrase")
        }
    }
    
    // Smart speak method that filters technical messages
    private fun speak(text: String, onComplete: (() -> Unit)? = null) {
        // Only speak user-friendly messages
        val shouldSpeak = isUserFriendlyMessage(text)
        if (shouldSpeak) {
            executedAction = text
            lastError = ""
            dialogState = DialogState.IDLE
            
            if (onComplete != null) {
                if (ttsEngine != null) {
                    setProcessing(true)
                    ttsEngine.speak(text) {
                        setProcessing(false)
                        onComplete()
                    }
                } else {
                    // No TTS engine - just execute callback immediately
                    onComplete()
                }
            } else {
                ttsEngine?.speak(text, null)
            }
        } else {
            executedAction = ""
            lastError = ""
            dialogState = DialogState.IDLE
            onComplete?.invoke()
        }
    }
    
    private fun isUserFriendlyMessage(text: String): Boolean {
        val lowerText = text.lowercase()
        
        // Don't speak technical messages
        val technicalWords = listOf(
            "processing", "please allow", "permission denied", "error",
            "failed", "exception", "timeout", "null", "debug",
            "loading", "initializing", "retrying", "connecting",
            "warning", "trace", "log", "info", "verbose"
        )
        
        // Don't speak messages that look like developer debugging
        val debugPatterns = listOf(
            "handled in different flow",
            "feature disabled",
            "permission no longer needed",
            "system settings"
        )
        
        return !technicalWords.any { lowerText.contains(it) } && 
               !debugPatterns.any { lowerText.contains(it) } &&
               text.trim().isNotEmpty() && 
               text.length > 3 // Don't speak very short technical responses
    }
}
