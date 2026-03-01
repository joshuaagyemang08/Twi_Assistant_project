package com.example.twiassistant.asr

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONTokener
import org.json.JSONObject
import java.io.File

class GhanaNlpAsrProvider(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val defaultLanguage: String = "tw",
) {
    data class Result(
        val text: String,
        val language: String? = null,
        val confidence: Double? = null,
        val rawJson: String? = null,
    )

    /**
     * GhanaNLP ASR v2
     * POST https://translation-api.ghananlp.org/asr/v2/transcribe?language={language}
     * Header: Ocp-Apim-Subscription-Key: <key>
     * Body: binary audio
     */
    fun transcribe(audioFile: File, language: String = defaultLanguage): Result {
        val url = buildUrl(language)
        Log.d(TAG, "GhanaNlp ASR -> POST $url")

        // GhanaNLP docs mention audio/mpeg, but this app records WAV.
        // Send an accurate content-type to maximize compatibility.
        val mediaType = when (audioFile.extension.lowercase()) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "mpeg" -> "audio/mpeg"
            else -> "application/octet-stream"
        }.toMediaType()

        val requestBody = audioFile.asRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("Ocp-Apim-Subscription-Key", apiKey)
            .addHeader("Subscription-Key", apiKey)
            .addHeader("Accept", "application/json")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("GhanaNLP ASR error ${response.code}: ${response.message}. Body: $body")
            }

            return parse(body)
        }
    }

    private fun buildUrl(language: String): String {
        val trimmed = baseUrl.trim()
        return when {
            trimmed.contains("{language}") -> trimmed.replace("{language}", language)
            trimmed.contains("?language=") -> trimmed
            trimmed.endsWith("/asr/v2/transcribe") || trimmed.endsWith("/asr/v2/transcribe/") ->
                trimmed.trimEnd('/') + "?language=$language"
            else -> {
                // If user passes the full endpoint (common), just append query safely.
                val base = trimmed.trimEnd('/')
                if (base.contains("?")) "$base&language=$language" else "$base?language=$language"
            }
        }
    }

    private fun parse(raw: String): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return Result(text = "", rawJson = raw)
        }

        // GhanaNLP may return either a JSON object OR a JSON string literal on success.
        // Example:
        //   "rɛ kɔfu"
        return when (val parsed = JSONTokener(trimmed).nextValue()) {
            is JSONObject -> {
                val text = when {
                    parsed.has("text") -> parsed.optString("text")
                    parsed.has("transcription") -> parsed.optString("transcription")
                    parsed.has("transcript") -> parsed.optString("transcript")
                    parsed.has("result") -> parsed.optString("result")
                    else -> ""
                }.trim()

                val lang = parsed.optString("language", "")
                val confidence = if (parsed.has("confidence")) parsed.optDouble("confidence") else null

                Result(
                    text = text,
                    language = lang,
                    confidence = confidence,
                    rawJson = raw,
                )
            }

            is String -> Result(text = parsed.trim(), rawJson = raw)

            else -> Result(text = trimmed, rawJson = raw)
        }
    }

    private companion object {
        private const val TAG = "GhanaNlpAsrProvider"
    }
}
