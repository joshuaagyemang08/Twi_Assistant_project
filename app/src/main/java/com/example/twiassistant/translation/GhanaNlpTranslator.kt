package com.example.twiassistant.translation

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
    fun translate(text: String, langPair: String): String? {
        val url = baseUrl.trim()
        val key = apiKey.trim()
        if (url.isBlank() || key.isBlank()) return null

        val json = JSONObject()
            .put("in", text)
            .put("lang", langPair)
            .toString()

        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("Ocp-Apim-Subscription-Key", key)
            .build()

        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val raw = resp.body?.string()?.trim().orEmpty()
            if (raw.isBlank()) return@use null

            // The API may return either a plain string, a JSON string, or a JSON object.
            if (raw.startsWith("{") && raw.endsWith("}")) {
                val obj = JSONObject(raw)
                
                // Check for error responses first
                if (obj.has("type") && obj.optString("type").contains("Error", ignoreCase = true)) {
                    return@use null
                }
                if (obj.has("error") || obj.has("message") && !obj.has("translation")) {
                    return@use null
                }
                
                val candidates = listOf(
                    obj.optString("translation"),
                    obj.optString("translatedText"),
                    obj.optString("translated"),
                    obj.optString("out"),
                    obj.optString("result"),
                )
                return@use candidates.firstOrNull { it.isNotBlank() }
            }

            if (raw.startsWith('"') && raw.endsWith('"')) {
                // Wrap as a JSON object so JSONObject can decode escapes safely.
                return@use try {
                    JSONObject("{\"t\":$raw}").getString("t").trim().ifBlank { null }
                } catch (_: Throwable) {
                    raw.trim('"')
                }
            }

            raw
        }
    }
}
