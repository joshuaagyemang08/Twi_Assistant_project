package com.example.twiassistant.hotword

import android.content.Context
import android.util.Log
import com.example.twiassistant.BuildConfig
import java.io.File

/**
 * Porcupine-backed hotword engine. Requires:
 * - A Picovoice access key in Gradle property PORCUPINE_ACCESS_KEY (BuildConfig.PORCUPINE_ACCESS_KEY).
 * - Keyword file asset at assets/hotword/boa_me.ppn (or change keywordAssetPath).
 * - Model file asset at assets/hotword/porcupine_params.pv (Picovoice base model).
 */
class PorcupineHotwordEngine(private val context: Context) : HotwordEngine {
    override fun start(onWake: () -> Unit) {
        Log.w(TAG, "Porcupine disabled; using SimpleKeywordHotwordEngine instead.")
    }

    override fun stop() {
        // no-op
    }

    companion object {
        private const val TAG = "PorcupineHotword"
    }
}
