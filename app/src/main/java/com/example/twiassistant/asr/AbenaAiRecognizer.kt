package com.example.twiassistant.asr

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Placeholder for wiring Abena AI speech/intent API behind the existing SpeechRecognizerTwi interface.
 * Replace startListening/stopListening bodies with network calls to Abena AI and invoke callbacks
 * with final/partial transcripts or error messages.
 */
class AbenaAiRecognizer(
    private val context: Context,
    private val onFinal: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onError: (String) -> Unit
) : SpeechRecognizerTwi {
    private var recorder: AudioRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false

    override fun startListening() {
        // Record audio to a temp file
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        audioFile = File(dir, "abena_ai_input.wav")
        recorder = AudioRecorder(audioFile!!)
        recorder?.start()
        isRecording = true
    }

    override fun stopListening() {
        if (!isRecording) return
        isRecording = false
        recorder?.stop()
        recorder = null
        audioFile?.let { file ->
            sendToAbenaAi(file)
        }
    }

    private fun sendToAbenaAi(audioFile: File) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val apiKey = "sk_1ca6bbf243fa4a9ab4897d9864a4472a"
                // Playground endpoint (works but currently returns en); production endpoint 404s
                val url = "https://abena.mobobi.com/playground/api/v1/asr/transcribe/"
                val requestedModel = "model-aka-gh" // Akan Twi model
                val requestedLanguage = "aka"        // Akan/Twi code
                
                // Create client with timeouts
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build()
                
                val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("audio_file", audioFile.name, audioFile.asRequestBody("audio/wav".toMediaTypeOrNull()))
                    .addFormDataPart("language", requestedLanguage)
                    .addFormDataPart("language_code", requestedLanguage)
                    .addFormDataPart("model", requestedModel)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Accept-Language", requestedLanguage)
                    .post(requestBody)
                    .build()

                Log.d("AbenaAiRecognizer", "Sending audio to Abena API: $url")
                Log.d("AbenaAiRecognizer", "Audio file size: ${audioFile.length()} bytes")
                Log.d("AbenaAiRecognizer", "Requested language: $requestedLanguage, model: $requestedModel")

                val response = client.newCall(request).execute()
                Log.d("AbenaAiRecognizer", "Response code: ${response.code}")
                Log.d("AbenaAiRecognizer", "Response headers: ${response.headers}")

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    Log.d("AbenaAiRecognizer", "Raw response: $responseBody")

                    val jsonObject = JSONObject(responseBody)
                    val transcript = extractTranscript(jsonObject)
                    val serverLanguage = jsonObject.optString("language", "")
                    if (serverLanguage.isNotBlank() && !serverLanguage.equals(requestedLanguage, ignoreCase = true)) {
                        Log.w("AbenaAiRecognizer", "API returned language '$serverLanguage' instead of '$requestedLanguage'")
                        onError("Abena AI returned language '$serverLanguage' instead of '$requestedLanguage'")
                    }
                    if (transcript.isNotEmpty()) {
                        Log.d("AbenaAiRecognizer", "Extracted transcript: $transcript")
                        onFinal(transcript)
                    } else {
                        Log.w("AbenaAiRecognizer", "No transcript found in response")
                        onError("No transcript in API response")
                    }
                } else {
                    val code = response.code
                    val message = response.message
                    val errorBody = response.body?.string() ?: "No error details"
                    Log.e("AbenaAiRecognizer", "Abena API error: $code $message")
                    Log.e("AbenaAiRecognizer", "Error body: $errorBody")
                    onError("Abena AI error: $code $message - $errorBody")
                }
            } catch (e: IOException) {
                Log.e("AbenaAiRecognizer", "Network error: ${e.localizedMessage}")
                e.printStackTrace()
                onError("Network error: ${e.localizedMessage}")
            } catch (e: Exception) {
                Log.e("AbenaAiRecognizer", "Abena API exception: ${e.localizedMessage}")
                e.printStackTrace()
                onError("Abena AI exception: ${e.localizedMessage}")
            }
        }
    }
    
    private fun extractTranscript(jsonObject: JSONObject): String {
        return try {
            when {
                jsonObject.has("transcript") -> jsonObject.getString("transcript")
                jsonObject.has("text") -> jsonObject.getString("text")
                jsonObject.has("result") -> jsonObject.getString("result")
                jsonObject.has("transcription") -> jsonObject.getString("transcription")
                jsonObject.has("recognized_text") -> jsonObject.getString("recognized_text")
                else -> {
                    Log.w("AbenaAiRecognizer", "Unknown JSON structure: $jsonObject")
                    ""
                }
            }
        } catch (e: Exception) {
            Log.e("AbenaAiRecognizer", "JSON parsing error: ${e.localizedMessage}")
            ""
        }
    }
}
