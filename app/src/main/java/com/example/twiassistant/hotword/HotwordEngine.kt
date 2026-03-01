package com.example.twiassistant.hotword

interface HotwordEngine {
    fun start(onWake: () -> Unit)
    fun stop()
}
