package com.example.twiassistant.tts

import android.content.Context

/**
 * Abena AI TTS has been removed from this project.
 * This wrapper is kept only for backward compatibility and uses on-device Android TTS.
 */
class AbenaAiTts(context: Context) {
    private val tts = AndroidTts(context.applicationContext)

    @Suppress("UNUSED_PARAMETER")
    fun speak(text: String, voice: String = "") {
        tts.speak(text)
    }

    fun shutdown() {
        tts.shutdown()
    }
}
