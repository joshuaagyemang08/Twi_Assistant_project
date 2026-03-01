package com.example.twiassistant

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.material3.MaterialTheme
import com.example.twiassistant.asr.AndroidSpeechRecognizerTwi
import com.example.twiassistant.asr.AndroidSpeechRecognizerEnglish
import com.example.twiassistant.dialog.DialogManager
import com.example.twiassistant.device_control.DeviceActions
import com.example.twiassistant.nlu.IntentParser
import com.example.twiassistant.ui_icon.AssistantHomeScreen
import com.example.twiassistant.viewmodel.AssistantViewModel
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var assistantViewModel: AssistantViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        assistantViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return AssistantViewModel(
                    parser = IntentParser(),
                    dialogManager = DialogManager(),
                    recognizerProvider = { onFinal, onPartial, onError ->
                        val ghanaKey = BuildConfig.GHANA_NLP_ASR_KEY
                        val ghanaUrl = if (BuildConfig.GHANA_NLP_ASR_URL.isNotBlank()) {
                            BuildConfig.GHANA_NLP_ASR_URL
                        } else {
                            // Default to GhanaNLP ASR v2 endpoint when only a key is provided.
                            "https://translation-api.ghananlp.org/asr/v2/transcribe"
                        }

                        if (ghanaKey.isNotBlank()) {
                            val httpClient = OkHttpClient.Builder()
                                .connectTimeout(60, TimeUnit.SECONDS)
                                .readTimeout(180, TimeUnit.SECONDS)
                                .writeTimeout(180, TimeUnit.SECONDS)
                                .callTimeout(240, TimeUnit.SECONDS)
                                .build()

                            val provider = com.example.twiassistant.asr.GhanaNlpAsrProvider(
                                client = httpClient,
                                baseUrl = ghanaUrl,
                                apiKey = ghanaKey
                            )
                            com.example.twiassistant.asr.SilenceRecordingAsrRecognizer(
                                context = this@MainActivity,
                                provider = provider,
                                onFinal = onFinal,
                                onPartial = onPartial,
                                onError = onError
                            )
                        } else {
                            AndroidSpeechRecognizerTwi(
                                context = this@MainActivity,
                                onFinal = onFinal,
                                onPartial = onPartial,
                                onErrorMessage = onError
                            )
                        }
                    },
                    englishRecognizerProvider = { onFinal, onPartial, onError ->
                        AndroidSpeechRecognizerEnglish(
                            context = this@MainActivity,
                            onFinal = onFinal,
                            onPartial = onPartial,
                            onErrorMessage = onError
                        )
                    },
                    translationApiKey = if (BuildConfig.GOOGLE_TRANSLATE_API_KEY.isNotBlank()) {
                        BuildConfig.GOOGLE_TRANSLATE_API_KEY
                    } else if (BuildConfig.GHANA_NLP_TRANSLATION_KEY.isNotBlank()) {
                        BuildConfig.GHANA_NLP_TRANSLATION_KEY
                    } else {
                        BuildConfig.GHANA_NLP_ASR_KEY
                    },
                    googleApiKey = BuildConfig.GOOGLE_TRANSLATE_API_KEY,
                    googleSearchCx = BuildConfig.GOOGLE_SEARCH_CX,
                    geminiApiKey = BuildConfig.GEMINI_API_KEY,
                    httpClient = OkHttpClient.Builder()
                        .connectTimeout(60, TimeUnit.SECONDS)
                        .readTimeout(180, TimeUnit.SECONDS)
                        .writeTimeout(180, TimeUnit.SECONDS)
                        .callTimeout(240, TimeUnit.SECONDS)
                        .build(),
                    deviceActions = DeviceActions(this@MainActivity)
                ) as T
            }
        })[AssistantViewModel::class.java]

        setContent {
            MaterialTheme {
                AssistantHomeScreen(viewModel = assistantViewModel)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
