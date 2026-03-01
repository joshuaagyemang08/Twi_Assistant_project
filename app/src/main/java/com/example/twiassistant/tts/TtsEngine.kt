package com.example.twiassistant.tts

interface TtsEngine {
    fun speak(text: String, onComplete: (() -> Unit)? = null)
    fun stop()
    fun isSpeaking(): Boolean
    fun shutdown()
    fun switchToEnglish()
    fun switchToTwi()
}
