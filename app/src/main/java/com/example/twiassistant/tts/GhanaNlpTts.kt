package com.example.twiassistant.tts

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

class GhanaNlpTts(
    private val context: Context,
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val subscriptionKey: String,
    private val language: String = "twi",
    private val speakerId: String = "female",
) : TtsEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var onCompleteCallback: (() -> Unit)? = null
    
    // Track last spoken text to prevent duplicates
    private var lastSpokenText: String = ""
    private var lastSpokenTime: Long = 0

    override fun speak(text: String, onComplete: (() -> Unit)?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onComplete?.invoke()
            return
        }
        if (subscriptionKey.isBlank()) {
            Log.w(TAG, "GhanaNlpTts speak() called but subscriptionKey is blank")
            onComplete?.invoke()
            return
        }
        
        // Prevent speaking the same text twice within a short window (2 seconds)
        val now = System.currentTimeMillis()
        if (trimmed == lastSpokenText && now - lastSpokenTime < 2000) {
            Log.d(TAG, "Skipping duplicate TTS within 2s: '$trimmed'")
            onComplete?.invoke()
            return
        }
        lastSpokenText = trimmed
        lastSpokenTime = now
        
        // Store callback
        onCompleteCallback = onComplete

        val url = baseUrl.trimEnd('/') + "/synthesize"
        enqueueSynthesize(url = url, text = trimmed, language = language, speakerId = speakerId, allowFallback = true)
    }

    override fun stop() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun enqueueSynthesize(
        url: String,
        text: String,
        language: String,
        speakerId: String,
        allowFallback: Boolean,
    ) {
        val payload = JSONObject()
            .put("text", text)
            .put("language", language)
            .put("speaker_id", speakerId)

        Log.d(TAG, "GhanaNLP TTS -> POST $url (language=$language speaker_id=$speakerId textLen=${text.length})")

        val request = Request.Builder()
            .url(url)
            .addHeader("Ocp-Apim-Subscription-Key", subscriptionKey)
            .addHeader("Subscription-Key", subscriptionKey)
            .addHeader("Accept", "audio/wav")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e(TAG, "GhanaNLP TTS request failed", e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        Log.e(TAG, "GhanaNLP TTS error ${response.code}: ${response.message}. Body: $body")

                        // The docs show inconsistent language codes ("tw" vs "twi").
                        // If the server rejects the language, retry once with the alternate.
                        if (allowFallback && response.code == 400 && body.contains("Language", ignoreCase = true)) {
                            val fallbackLanguage = when (language.lowercase()) {
                                "tw" -> "twi"
                                "twi" -> "tw"
                                else -> null
                            }
                            if (fallbackLanguage != null) {
                                Log.w(TAG, "Retrying TTS once with fallback language=$fallbackLanguage")
                                enqueueSynthesize(url, text, fallbackLanguage, speakerId, allowFallback = false)
                            }
                        }
                        return
                    }

                    val bytes = response.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        Log.e(TAG, "GhanaNLP TTS returned empty body")
                        return
                    }

                    val outFile = File(context.cacheDir, "ghana_tts_${System.currentTimeMillis()}.wav")
                    try {
                        outFile.writeBytes(bytes)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to write WAV to cache", t)
                        return
                    }

                    mainHandler.post {
                        try {
                            mediaPlayer?.stop()
                            mediaPlayer?.release()
                            mediaPlayer = null

                            mediaPlayer = MediaPlayer().apply {
                                setDataSource(outFile.absolutePath)
                                setOnCompletionListener {
                                    try {
                                        it.release()
                                    } finally {
                                        if (mediaPlayer === it) mediaPlayer = null
                                        // Best-effort cleanup
                                        outFile.delete()
                                        // Invoke completion callback
                                        onCompleteCallback?.invoke()
                                        onCompleteCallback = null
                                    }
                                }
                                setOnErrorListener { mp, _, _ ->
                                    try {
                                        mp.release()
                                    } finally {
                                        if (mediaPlayer === mp) mediaPlayer = null
                                        outFile.delete()
                                        // Invoke completion callback on error too
                                        onCompleteCallback?.invoke()
                                        onCompleteCallback = null
                                    }
                                    true
                                }
                                prepare()
                                start()
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "Failed to play GhanaNLP TTS audio", t)
                            outFile.delete()
                            // Invoke completion callback on exception
                            onCompleteCallback?.invoke()
                            onCompleteCallback = null
                        }
                    }
                }
            }
        })
    }

    override fun isSpeaking(): Boolean {
        return mediaPlayer?.isPlaying == true
    }

    override fun shutdown() {
        mainHandler.post {
            try {
                mediaPlayer?.stop()
            } catch (_: Throwable) {
            } finally {
                mediaPlayer?.release()
                mediaPlayer = null
            }
        }
    }
    
    override fun switchToEnglish() {
        // GhanaNLP TTS always uses the configured language/speakerId
        // For English mode, we could potentially change speakerId to an English speaker
        // But for now, we'll keep the same voice since it can handle English text
    }
    
    override fun switchToTwi() {
        // GhanaNLP TTS is already configured for Twi
        // This is the default mode, so no changes needed
    }

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val TAG = "GhanaNlpTts"
    }
}
