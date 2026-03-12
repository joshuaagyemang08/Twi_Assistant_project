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
    
    // Chunk queue for sequential playback
    private val chunkQueue = mutableListOf<String>()
    private var isPlayingChunks = false

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

        // Split text into chunks to prevent audio breakages
        val chunks = splitIntoChunks(trimmed)
        
        if (chunks.size == 1) {
            // Single chunk - speak directly
            val url = baseUrl.trimEnd('/') + "/synthesize"
            enqueueSynthesize(url = url, text = trimmed, language = language, speakerId = speakerId, allowFallback = true)
        } else {
            // Multiple chunks - queue them for sequential playback
            Log.d(TAG, "Splitting text into ${chunks.size} chunks for smoother playback")
            chunkQueue.clear()
            chunkQueue.addAll(chunks)
            playNextChunk()
        }
    }
    
    private fun splitIntoChunks(text: String): List<String> {
        // Split by sentence-ending punctuation (period, question mark, exclamation)
        val sentences = text.split(Regex("[.!?]+\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        
        if (sentences.isEmpty()) return listOf(text)
        
        val chunks = mutableListOf<String>()
        var currentChunk = StringBuilder()
        
        for (sentence in sentences) {
            // If adding this sentence exceeds max chunk size, save current chunk
            if (currentChunk.isNotEmpty() && currentChunk.length + sentence.length > MAX_CHUNK_SIZE) {
                chunks.add(currentChunk.toString().trim())
                currentChunk = StringBuilder()
            }
            
            if (currentChunk.isNotEmpty()) {
                currentChunk.append(" ")
            }
            currentChunk.append(sentence)
            
            // If chunk is getting large, finalize it
            if (currentChunk.length >= MAX_CHUNK_SIZE) {
                chunks.add(currentChunk.toString().trim())
                currentChunk = StringBuilder()
            }
        }
        
        // Add remaining text
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }
        
        // If no chunks were created, return original text
        return if (chunks.isEmpty()) listOf(text) else chunks
    }
    
    private fun playNextChunk() {
        if (chunkQueue.isEmpty()) {
            isPlayingChunks = false
            // All chunks played - invoke completion callback
            onCompleteCallback?.invoke()
            onCompleteCallback = null
            return
        }
        
        isPlayingChunks = true
        val chunk = chunkQueue.removeAt(0)
        val url = baseUrl.trimEnd('/') + "/synthesize"
        
        // Speak this chunk with a callback to play the next one
        enqueueSynthesizeChunk(url = url, text = chunk, language = language, speakerId = speakerId, allowFallback = true)
    }

    override fun stop() {
        // Clear chunk queue to stop sequential playback
        chunkQueue.clear()
        isPlayingChunks = false
        
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        
        // Clear any pending callbacks
        onCompleteCallback = null
    }
    
    private fun enqueueSynthesizeChunk(
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

        Log.d(TAG, "GhanaNLP TTS chunk -> POST $url (language=$language speaker_id=$speakerId textLen=${text.length})")

        val request = Request.Builder()
            .url(url)
            .addHeader("Ocp-Apim-Subscription-Key", subscriptionKey)
            .addHeader("Subscription-Key", subscriptionKey)
            .addHeader("Accept", "audio/wav")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e(TAG, "GhanaNLP TTS chunk request failed", e)
                // On failure, try to continue with next chunk
                playNextChunk()
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        Log.e(TAG, "GhanaNLP TTS chunk error ${response.code}: ${response.message}. Body: $body")

                        // The docs show inconsistent language codes ("tw" vs "twi").
                        // If the server rejects the language, retry once with the alternate.
                        if (allowFallback && response.code == 400 && body.contains("Language", ignoreCase = true)) {
                            val fallbackLanguage = when (language.lowercase()) {
                                "tw" -> "twi"
                                "twi" -> "tw"
                                else -> null
                            }
                            if (fallbackLanguage != null) {
                                Log.w(TAG, "Retrying TTS chunk once with fallback language=$fallbackLanguage")
                                enqueueSynthesizeChunk(url, text, fallbackLanguage, speakerId, allowFallback = false)
                            }
                        } else {
                            // On error, try to continue with next chunk
                            playNextChunk()
                        }
                        return
                    }

                    val bytes = response.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        Log.e(TAG, "GhanaNLP TTS chunk returned empty body")
                        // Continue with next chunk
                        playNextChunk()
                        return
                    }

                    val outFile = File(context.cacheDir, "ghana_tts_chunk_${System.currentTimeMillis()}.wav")
                    try {
                        outFile.writeBytes(bytes)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to write chunk WAV to cache", t)
                        // Continue with next chunk
                        playNextChunk()
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
                                        // Cleanup this chunk's file
                                        outFile.delete()
                                        // Play next chunk in sequence
                                        playNextChunk()
                                    }
                                }
                                setOnErrorListener { mp, _, _ ->
                                    try {
                                        mp.release()
                                    } finally {
                                        if (mediaPlayer === mp) mediaPlayer = null
                                        outFile.delete()
                                        // On error, try to continue with next chunk
                                        playNextChunk()
                                    }
                                    true
                                }
                                prepare()
                                start()
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "Failed to play GhanaNLP TTS chunk audio", t)
                            outFile.delete()
                            // On exception, try to continue with next chunk
                            playNextChunk()
                        }
                    }
                }
            }
        })
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
        private const val MAX_CHUNK_SIZE = 150 // Maximum characters per chunk to prevent audio breakages
    }
}
