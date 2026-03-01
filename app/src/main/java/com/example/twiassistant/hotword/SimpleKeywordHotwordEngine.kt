package com.example.twiassistant.hotword

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Lightweight hotword detector using Android SpeechRecognizer partial results.
 * Pros: no external key or SDK. Cons: requires network and keeps mic open.
 */
class SimpleKeywordHotwordEngine(
    private val context: Context,
    private val keyword: String,
    private val locale: Locale = Locale("en", "GH")
) : HotwordEngine {

    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var lastWakeMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var restartFailures = 0
    private var onWakeCallback: (() -> Unit)? = null

    override fun start(onWake: () -> Unit) {
        if (active) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available")
            return
        }
        active = true
        onWakeCallback = onWake
        createRecognizer()
        startListening()
    }

    override fun stop() {
        active = false
        onWakeCallback = null
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.stopListening() } catch (_: Throwable) {}
        try { recognizer?.cancel() } catch (_: Throwable) {}
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
    }

    private fun createRecognizer() {
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    // We successfully entered a listening session; clear backoff.
                    restartFailures = 0
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onPartialResults(partialResults: Bundle?) {
                    if (partialResults == null) return
                    val text = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (containsKeyword(text)) {
                        val now = System.currentTimeMillis()
                        if (now - lastWakeMs > MIN_WAKE_GAP_MS) {
                            lastWakeMs = now
                            onWakeCallback?.invoke()
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    // Keep looping after each result.
                    scheduleRestart()
                }

                override fun onError(error: Int) {
                    // Restart unless stopped.
                    val extraDelay = when (error) {
                        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 12000L
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 5000L
                        SpeechRecognizer.ERROR_CLIENT -> 5000L
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        SpeechRecognizer.ERROR_NETWORK -> 6000L
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 1500L
                        else -> null
                    }

                    // Recreate on some errors to clear internal bad state.
                    val shouldRecreate = when (error) {
                        SpeechRecognizer.ERROR_CLIENT,
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
                        SpeechRecognizer.ERROR_SERVER -> true
                        else -> false
                    }
                    scheduleRestart(extraDelayMs = extraDelay, lastError = error, recreateRecognizer = shouldRecreate)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        if (!active) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Try to keep the session open a bit longer so partials can catch the wake word.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
        }
        try {
            recognizer?.startListening(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start listening", t)
        }
    }

    private fun scheduleRestart(extraDelayMs: Long? = null, lastError: Int? = null, recreateRecognizer: Boolean = false) {
        if (!active) return
        restartFailures = (restartFailures + 1).coerceAtMost(5)
        handler.removeCallbacksAndMessages(null)

        // Base delay (normal loop) + simple backoff for repeated failures.
        val baseDelay = extraDelayMs ?: RESTART_DELAY_MS
        val backoff = (restartFailures * 350L).coerceAtMost(2500L)
        val delayMs = baseDelay + backoff

        if (lastError != null) {
            Log.w(TAG, "SpeechRecognizer error=$lastError (${errorName(lastError)}); restarting in ${delayMs}ms")
        }

        handler.postDelayed({
            if (!active) return@postDelayed
            try {
                // cancel() is safer than stopListening() when restarting loops
                recognizer?.cancel()
            } catch (_: Throwable) {}
            try {
                if (recreateRecognizer) {
                    createRecognizer()
                }
                startListening()
            } catch (t: Throwable) {
                Log.e(TAG, "Restart listen failed", t)
                if (restartFailures >= 5) {
                    stop()
                }
            }
        }, delayMs)
    }

    private fun containsKeyword(texts: List<String>?): Boolean {
        if (texts == null) return false
        return texts.any { it.contains(keyword, ignoreCase = true) }
    }

    private fun errorName(code: Int): String = when (code) {
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

    companion object {
        private const val TAG = "SimpleHotwordEngine"
        private const val MIN_WAKE_GAP_MS = 3000L
        private const val RESTART_DELAY_MS = 1200L
    }
}
