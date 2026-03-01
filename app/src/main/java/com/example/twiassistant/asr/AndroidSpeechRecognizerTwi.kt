package com.example.twiassistant.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class AndroidSpeechRecognizerTwi(
    context: Context,
    private val onFinal: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onErrorMessage: (String) -> Unit
) : SpeechRecognizerTwi {

    private val recognizer: SpeechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(context.applicationContext)

    private var listening = false

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )

        // Use Ghana English locale by default (often better for Twi-accented English).
        val ghLocale = Locale("en", "GH")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, ghLocale)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, ghLocale)

        // Keep sessions open longer (helps users speak naturally and improves accuracy).
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 6000)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 8000)

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

                if (!text.isNullOrBlank()) {
                    onPartial(normalize(text))
                }
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()

                if (!text.isNullOrBlank()) {
                    onFinal(normalize(text))
                }
            }

            override fun onError(error: Int) {
                listening = false
                onErrorMessage(errorMessage(error))
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    override fun startListening() {
        if (listening) return
        listening = true
        recognizer.startListening(intent)
        Log.d("ASR", "Listening started")
    }

    override fun stopListening() {
        try {
            recognizer.cancel()
            recognizer.stopListening()
        } catch (_: Exception) {}
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
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy (often hotword + mic conflict)."
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

    // 🔑 This is where we convert English → Twi meaning
    private fun normalize(text: String): String {
        return text.lowercase()
            .replace("free", "frɛ")
            .replace("fray", "frɛ")
            .replace("call", "frɛ")
            .replace("text", "sms")
            .replace("message", "sms")
            .trim()
    }
}
