package com.example.twiassistant.tts

import android.content.Context
import android.media.MediaPlayer

class AudioPrompter(private val context: Context) {

    fun play(assetName: String) {
        try {
            val afd = context.assets.openFd("audio/twi/$assetName")
            MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                prepare()
                start()
            }
        } catch (e: Exception) {
            // TODO fallback to Android TTS
        }
    }
}
