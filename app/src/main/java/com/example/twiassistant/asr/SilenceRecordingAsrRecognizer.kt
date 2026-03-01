package com.example.twiassistant.asr

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Records audio locally, then uploads it to GhanaNLP ASR when stopListening() is called.
 * (Despite the name, this version stops on user action rather than auto-silence.)
 */
class SilenceRecordingAsrRecognizer(
    private val context: Context,
    private val provider: GhanaNlpAsrProvider,
    private val onFinal: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onError: (String) -> Unit,
) : SpeechRecognizerTwi {

    private var recorder: AudioRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false
    private var uploadJob: Job? = null

    override fun startListening() {
        if (isRecording) return

        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        audioFile = File(dir, "ghana_asr_input.wav")

        recorder = AudioRecorder(audioFile!!)
        recorder?.start()
        isRecording = true

        // No true partials in this pathway.
        onPartial("")
        Log.d(TAG, "Recording started: ${audioFile?.absolutePath}")
    }

    override fun stopListening() {
        if (!isRecording) {
            // If we're not recording but there's an upload in progress, cancel it.
            uploadJob?.cancel()
            uploadJob = null
            return
        }
        isRecording = false

        // Cancel any previous upload job before starting a new one.
        uploadJob?.cancel()
        uploadJob = null

        recorder?.stop()
        recorder = null

        val file = audioFile
        if (file == null || !file.exists()) {
            onError("No recorded audio file")
            return
        }

        Log.d(TAG, "Recording stopped. Size=${file.length()} bytes")

        uploadJob = CoroutineScope(Dispatchers.IO).launch {
            val result = try {
                provider.transcribe(file)
            } catch (t: Throwable) {
                Log.e(TAG, "GhanaNLP transcribe failed", t)
                onError(t.message ?: "GhanaNLP transcribe failed")
                return@launch
            }

            Log.d(TAG, "ASR raw: ${result.rawJson}")

            if (result.text.isNotBlank()) {
                try {
                    onFinal(result.text)
                } catch (t: Throwable) {
                    Log.e(TAG, "Post-ASR handling failed", t)
                    onError(t.message ?: "Post-ASR handling failed")
                }
            } else {
                onError("Empty transcription from GhanaNLP")
            }
        }
    }

    /**
     * Abort any ongoing recording or upload immediately.
     * Used when switching to a different recognizer to ensure mic is released.
     */
    fun abort() {
        Log.d(TAG, "Aborting: uploadJob=${uploadJob != null} isRecording=$isRecording recorder=${recorder != null}")
        
        // Cancel any pending upload
        uploadJob?.cancel()
        uploadJob = null
        
        // Force stop the recorder
        isRecording = false
        try {
            recorder?.stop()
        } catch (t: Throwable) {
            Log.e(TAG, "Error stopping recorder during abort: ${t.message}")
        }
        recorder = null
        
        Log.d(TAG, "Abort complete")
    }

    private companion object {
        private const val TAG = "SilenceRecAsr"
    }
}
