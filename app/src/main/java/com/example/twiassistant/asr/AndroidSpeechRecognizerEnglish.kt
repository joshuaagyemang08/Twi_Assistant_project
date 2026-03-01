package com.example.twiassistant.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class AndroidSpeechRecognizerEnglish(
    context: Context,
    private val onFinal: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onErrorMessage: (String) -> Unit,
    private val locale: Locale = Locale.US,
) : SpeechRecognizerTwi {

    private val recognizer: SpeechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(context.applicationContext)

    private var listening = false

    private val languageTag: String = try {
        locale.toLanguageTag()
    } catch (_: Throwable) {
        "en-US"
    }

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // Use a language tag string (recommended by Android docs / common implementations).
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)

        // Give the user plenty of time to speak a name.
        // MINIMUM_LENGTH: How long to wait before detecting speech
        // POSSIBLY_COMPLETE: Short pause that might mean they're done
        // COMPLETE: Longer pause that definitely means they're done
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 8000)  // Wait up to 8s for speech to start
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)  // 2s pause = might be done
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)  // 3s pause = definitely done

        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    init {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()

                if (!text.isNullOrBlank()) {
                    onPartial(text)
                }
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()

                if (!text.isNullOrBlank()) {
                    onFinal(text)
                }
            }

            override fun onError(error: Int) {
                val wasListening = listening
                
                // Guard: If we weren't listening, this is a duplicate/spurious error callback.
                // Android SpeechRecognizer sometimes fires multiple onError() for a single error.
                // Ignore subsequent errors until the next successful start.
                if (!wasListening) {
                    Log.w("ASR_EN", "onError: code=$error IGNORED (not listening, likely duplicate callback)")
                    return
                }
                
                listening = false
                val msg = errorMessage(error)
                Log.e("ASR_EN", "onError: code=$error wasListening=$wasListening msg=$msg", Exception("Error stack trace"))
                onErrorMessage(msg)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    override fun startListening() {
        if (listening) {
            Log.w("ASR_EN", "startListening() called but already listening - ignoring")
            return
        }
        listening = true
        Log.d("ASR_EN", "startListening() - calling recognizer.startListening()", Exception("Stack trace"))
        recognizer.startListening(intent)
        Log.d("ASR_EN", "Listening started")
    }

    override fun stopListening() {
        try {
            recognizer.cancel()
            recognizer.stopListening()
        } catch (_: Exception) {
        }
        listening = false
    }

    private fun errorMessage(code: Int): String {
        val name = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
            else -> "ERROR_UNKNOWN"
        }

        val hint = when (code) {
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many recognition requests. Wait a few seconds and try again."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network issue. Check internet."
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match. Try speaking more clearly."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing."
            else -> ""
        }

        return if (hint.isBlank()) {
            "Speech error $code ($name)"
        } else {
            "Speech error $code ($name). $hint"
        }
    }
}
