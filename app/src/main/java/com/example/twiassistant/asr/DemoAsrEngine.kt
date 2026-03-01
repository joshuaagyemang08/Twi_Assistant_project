package com.example.twiassistant.asr

class DemoAsrEngine(private val onFinal: (String) -> Unit) : SpeechRecognizerTwi {
    override fun startListening() {
        // Demo: immediately return a fixed utterance
        onFinal.invoke("frɛ Ama") // "Call Ama"
    }

    override fun stopListening() {}
}
