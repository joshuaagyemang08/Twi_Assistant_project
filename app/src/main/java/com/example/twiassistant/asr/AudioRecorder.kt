package com.example.twiassistant.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

@SuppressLint("MissingPermission")
class AudioRecorder(private val outputFile: File) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var workerThread: Thread? = null
    private val sampleRate = 16000
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    fun start() {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        audioRecord?.startRecording()
        isRecording = true
        workerThread = Thread {
            val buffer = ByteArray(bufferSize)
            val pcmStream = ByteArrayOutputStream()
            try {
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        pcmStream.write(buffer, 0, read)
                    }
                }
            } catch (_: Throwable) {
                // Best-effort: if recording stops unexpectedly, finalize whatever we captured.
            }
            val pcmData = pcmStream.toByteArray()
            writeWavFile(outputFile, pcmData, sampleRate, 1)
        }.also { it.start() }
    }

    private fun writeWavFile(file: File, pcmData: ByteArray, sampleRate: Int, channels: Int) {
        val byteRate = 16 * sampleRate * channels / 8
        val wavHeader = ByteArray(44)
        // ChunkID "RIFF"
        wavHeader[0] = 'R'.code.toByte(); wavHeader[1] = 'I'.code.toByte(); wavHeader[2] = 'F'.code.toByte(); wavHeader[3] = 'F'.code.toByte()
        // ChunkSize
        val chunkSize = 36 + pcmData.size
        wavHeader[4] = (chunkSize and 0xff).toByte(); wavHeader[5] = ((chunkSize shr 8) and 0xff).toByte();
        wavHeader[6] = ((chunkSize shr 16) and 0xff).toByte(); wavHeader[7] = ((chunkSize shr 24) and 0xff).toByte()
        // Format "WAVE"
        wavHeader[8] = 'W'.code.toByte(); wavHeader[9] = 'A'.code.toByte(); wavHeader[10] = 'V'.code.toByte(); wavHeader[11] = 'E'.code.toByte()
        // Subchunk1ID "fmt "
        wavHeader[12] = 'f'.code.toByte(); wavHeader[13] = 'm'.code.toByte(); wavHeader[14] = 't'.code.toByte(); wavHeader[15] = ' '.code.toByte()
        // Subchunk1Size (16 for PCM)
        wavHeader[16] = 16; wavHeader[17] = 0; wavHeader[18] = 0; wavHeader[19] = 0
        // AudioFormat (1 for PCM)
        wavHeader[20] = 1; wavHeader[21] = 0
        // NumChannels
        wavHeader[22] = channels.toByte(); wavHeader[23] = 0
        // SampleRate
        wavHeader[24] = (sampleRate and 0xff).toByte(); wavHeader[25] = ((sampleRate shr 8) and 0xff).toByte();
        wavHeader[26] = ((sampleRate shr 16) and 0xff).toByte(); wavHeader[27] = ((sampleRate shr 24) and 0xff).toByte()
        // ByteRate
        wavHeader[28] = (byteRate and 0xff).toByte(); wavHeader[29] = ((byteRate shr 8) and 0xff).toByte();
        wavHeader[30] = ((byteRate shr 16) and 0xff).toByte(); wavHeader[31] = ((byteRate shr 24) and 0xff).toByte()
        // BlockAlign
        wavHeader[32] = ((channels * 16) / 8).toByte(); wavHeader[33] = 0
        // BitsPerSample
        wavHeader[34] = 16; wavHeader[35] = 0
        // Subchunk2ID "data"
        wavHeader[36] = 'd'.code.toByte(); wavHeader[37] = 'a'.code.toByte(); wavHeader[38] = 't'.code.toByte(); wavHeader[39] = 'a'.code.toByte()
        // Subchunk2Size
        wavHeader[40] = (pcmData.size and 0xff).toByte(); wavHeader[41] = ((pcmData.size shr 8) and 0xff).toByte();
        wavHeader[42] = ((pcmData.size shr 16) and 0xff).toByte(); wavHeader[43] = ((pcmData.size shr 24) and 0xff).toByte()
        FileOutputStream(file).use { fos ->
            fos.write(wavHeader)
            fos.write(pcmData)
        }
    }

    fun stop() {
        isRecording = false

        // Stop the AudioRecord first to unblock the read loop.
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }

        // Wait for the background thread to finish writing the WAV file.
        val t = workerThread
        if (t != null) {
            try {
                t.join(3_000)
            } catch (_: InterruptedException) {
            }
            workerThread = null
        }

        try {
            audioRecord?.release()
        } catch (_: Throwable) {
        }
        audioRecord = null
    }
}
