package com.example.twiassistant.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

class AndroidTts(context: Context) : TtsEngine, TextToSpeech.OnInitListener {

    private val tts: TextToSpeech = TextToSpeech(context, this)
    private var isReady: Boolean = false
    private var pendingText: String? = null
    private var onCompleteCallback: (() -> Unit)? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            
            // Try to set a natural sounding voice for Ghana English
            val ghanaLocale = Locale("en", "GH")
            val result = tts.setLanguage(ghanaLocale)
            
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback to US English with slower speech rate for better comprehension
                tts.language = Locale.US
            }
            
            // Set speech parameters for better quality and speed
            tts.setSpeechRate(1.1f) // Faster speech rate to reduce delays
            tts.setPitch(0.95f) // Slightly lower pitch for clarity
            
            // Add audio stream settings for better quality
            val audioParams = Bundle()
            audioParams.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)

            pendingText?.let {
                pendingText = null
                speak(it)
            }
        }
    }

    override fun speak(text: String, onComplete: (() -> Unit)?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onComplete?.invoke()
            return
        }
        
        // Filter out technical messages that users shouldn't hear
        val filteredText = filterTechnicalMessages(trimmed)
        if (filteredText.isEmpty()) {
            onComplete?.invoke()
            return
        }

        if (!isReady) {
            pendingText = filteredText
            onCompleteCallback = onComplete
            return
        }
        
        // Store callback for when speech completes
        onCompleteCallback = onComplete

        // Preprocess text for better pronunciation
        val processedText = preprocessTextForTts(filteredText)
        
        val params = Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.8f)
        
        // Set utterance completed listener
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "twiassistant") {
                    onCompleteCallback?.invoke()
                    onCompleteCallback = null
                }
            }
            
            override fun onError(utteranceId: String?) {
                onCompleteCallback?.invoke()
                onCompleteCallback = null
            }
        })
        
        tts.speak(processedText, TextToSpeech.QUEUE_FLUSH, params, "twiassistant")
    }
    
    private fun preprocessTextForTts(text: String): String {
        var processed = text
        
        // Fix SMS pronunciation - spell it out completely
        processed = processed.replace("SMS", "S. M. S.", ignoreCase = true)
        processed = processed.replace(" sms ", " S. M. S. ", ignoreCase = true)
        processed = processed.replace("sms", "S. M. S.", ignoreCase = true)
        
        // Fix WhatsApp pronunciation to avoid scratching
        processed = processed.replace("WhatsApp", "Whats App", ignoreCase = true)
        processed = processed.replace("whatsapp", "Whats App", ignoreCase = true)
        
        // Fix common stretching issues by reducing repetitive sounds
        processed = processed.replace("aaa+".toRegex(), "aa")
        processed = processed.replace("ɛɛ+".toRegex(), "ɛ")
        
        // Add natural pauses using SSML-style breaks (supported by Android TTS)
        processed = processed.replace("...", "<break time='800ms'/>")
        processed = processed.replace(". ", "<break time='500ms'/> ")
        processed = processed.replace("! ", "<break time='600ms'/> ")
        processed = processed.replace(", ", "<break time='300ms'/> ")
        
        return processed
    }
    
    private fun filterTechnicalMessages(text: String): String {
        val lowerText = text.lowercase()
        
        // Don't speak technical/debug messages but allow user messages
        val technicalPhrases = listOf(
            "processing...", "please allow", "permission denied", "failed",
            "exception", "null", "timeout", "retrying", "connecting",
            "initializing", "validating", "parsing", "debug", "log", "trace"
        )
        
        // Don't speak if it contains technical phrases
        if (technicalPhrases.any { lowerText.contains(it) }) {
            return ""
        }
        
        // Allow short meaningful responses like "Asem no akɔ" (Message sent)
        if (text.length < 5 && !lowerText.contains("akɔ") && !lowerText.contains("yɛ")) {
            return ""
        }
        
        return text
    }

    override fun stop() {
        tts.stop()
    }

    override fun isSpeaking(): Boolean {
        return isReady && tts.isSpeaking
    }

    override fun shutdown() {
        try {
            tts.stop()
        } finally {
            tts.shutdown()
        }
    }
    
    override fun switchToEnglish() {
        if (isReady) {
            tts.language = Locale.US
        }
    }
    
    override fun switchToTwi() {
        if (isReady) {
            // Try Ghana English, fallback to US English with slower rate
            val ghanaLocale = Locale("en", "GH")
            val result = tts.setLanguage(ghanaLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.language = Locale.US
            }
        }
    }
}
