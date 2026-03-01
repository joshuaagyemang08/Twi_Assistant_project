package com.example.twiassistant.translation

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Google Cloud Translation API client for Twi <-> English translation
 * Uses REST API with API key authentication
 */
class GoogleTranslator(
    private val client: OkHttpClient,
    private val apiKey: String
) {
    companion object {
        private const val TAG = "GoogleTranslator"
        private const val BASE_URL = "https://translation.googleapis.com/language/translate/v2"
    }

    /**
     * Translate text between Twi and English
     * @param text Text to translate
     * @param langPair Language pair: "tw-en" for Twi to English, "en-tw" for English to Twi
     * @return Translated text
     */
    suspend fun translate(text: String, langPair: String): String {
        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is blank")
            throw IllegalStateException("Google Translate API key not configured")
        }

        val (sourceLanguage, targetLanguage) = when (langPair.lowercase()) {
            "tw-en" -> "tw" to "en"  // Twi to English
            "en-tw" -> "en" to "tw"  // English to Twi
            else -> throw IllegalArgumentException("Unsupported language pair: $langPair")
        }

        return try {
            val encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8.toString())
            
            // Build the request URL with query parameters
            val url = "$BASE_URL?key=$apiKey&q=$encodedText&source=$sourceLanguage&target=$targetLanguage&format=text"
            
            Log.d(TAG, "Translating: '$text' from $sourceLanguage to $targetLanguage")
            
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Translation failed with code ${response.code}: $errorBody")
                throw Exception("Google Translate API error: ${response.code} - $errorBody")
            }

            val responseBody = response.body?.string() ?: throw Exception("Empty response body")
            Log.d(TAG, "Raw response: $responseBody")
            
            // Parse JSON response
            val json = JSONObject(responseBody)
            val data = json.getJSONObject("data")
            val translations = data.getJSONArray("translations")
            
            if (translations.length() == 0) {
                throw Exception("No translations returned")
            }
            
            val translatedText = translations.getJSONObject(0).getString("translatedText")
            Log.d(TAG, "Translation successful: '$text' -> '$translatedText'")
            
            translatedText
        } catch (e: Exception) {
            Log.e(TAG, "Translation exception for '$text'", e)
            throw e
        }
    }

    /**
     * Detect the language of the given text
     * @param text Text to detect language for
     * @return Language code (e.g., "en", "tw")
     */
    suspend fun detectLanguage(text: String): String {
        if (apiKey.isBlank()) {
            throw IllegalStateException("Google Translate API key not configured")
        }

        return try {
            val encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8.toString())
            val url = "https://translation.googleapis.com/language/translate/v2/detect?key=$apiKey&q=$encodedText"
            
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                throw Exception("Language detection failed: ${response.code}")
            }

            val responseBody = response.body?.string() ?: throw Exception("Empty response body")
            val json = JSONObject(responseBody)
            val data = json.getJSONObject("data")
            val detections = data.getJSONArray("detections")
            
            if (detections.length() == 0) {
                throw Exception("No language detections returned")
            }
            
            val firstDetection = detections.getJSONArray(0)
            if (firstDetection.length() == 0) {
                throw Exception("Empty detection array")
            }
            
            val language = firstDetection.getJSONObject(0).getString("language")
            Log.d(TAG, "Detected language: $language for text: '$text'")
            
            language
        } catch (e: Exception) {
            Log.e(TAG, "Language detection exception", e)
            throw e
        }
    }
}
