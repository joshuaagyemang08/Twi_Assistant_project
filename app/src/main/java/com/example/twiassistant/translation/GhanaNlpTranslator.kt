package com.example.twiassistant.translation

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class GhanaNlpTranslator(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String
) {
    companion object {
        private const val TAG = "GhanaNlpTranslator"
    }
    
    fun translate(text: String, langPair: String): String? {
        Log.d(TAG, "Starting translation: text='$text', langPair='$langPair'")
        val url = baseUrl.trim()
        val key = apiKey.trim()
        
        Log.d(TAG, "API URL: '$url', API Key: '${key.take(8)}...'")
        
        if (url.isBlank() || key.isBlank()) {
            Log.e(TAG, "Missing URL or API key")
            return null
        }

        val json = JSONObject()
            .put("in", text)
            .put("lang", langPair)
            .toString()
        
        Log.d(TAG, "Request JSON: $json")

        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("Ocp-Apim-Subscription-Key", key)
            .addHeader("Subscription-Key", key)
            .build()
        
        Log.d(TAG, "Making HTTP request to: $url")

        return client.newCall(request).execute().use { resp ->
            Log.d(TAG, "HTTP Response Code: ${resp.code}")
            if (!resp.isSuccessful) {
                Log.e(TAG, "HTTP request failed with code: ${resp.code}, message: ${resp.message}")
                return@use null
            }
            
            val raw = resp.body?.string()?.trim().orEmpty()
            Log.d(TAG, "Raw response: '$raw'")
            
            if (raw.isBlank()) {
                Log.w(TAG, "Empty response body")
                return@use null
            }

            // The API may return either a plain string, a JSON string, or a JSON object.
            if (raw.startsWith("{") && raw.endsWith("}")) {
                val obj = JSONObject(raw)
                
                // Check for error responses first
                if (obj.has("type") && obj.optString("type").contains("Error", ignoreCase = true)) {
                    Log.e(TAG, "API returned error response")
                    return@use null
                }
                if (obj.has("error") || obj.has("message") && !obj.has("translation")) {
                    Log.e(TAG, "API returned error: ${obj.optString("error", obj.optString("message"))}")
                    return@use null
                }
                
                val candidates = listOf(
                    obj.optString("translation"),
                    obj.optString("translatedText"),
                    obj.optString("translated"),
                    obj.optString("out"),
                    obj.optString("result"),
                )
                val result = candidates.firstOrNull { it.isNotBlank() }
                Log.d(TAG, "Extracted translation: '$result'")
                return@use result
            }

            if (raw.startsWith('"') && raw.endsWith('"')) {
                // Wrap as a JSON object so JSONObject can decode escapes safely.
                val result = try {
                    JSONObject("{\"t\":$raw}").getString("t").trim().ifBlank { null }
                } catch (_: Throwable) {
                    raw.trim('"')
                }
                Log.d(TAG, "Extracted quoted translation: '$result'")
                return@use result
            }

            Log.d(TAG, "Using raw response as translation: '$raw'")
            raw
        }
    }
}
