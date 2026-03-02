package com.voisum.app.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Manages audio recording in AAC/M4A format.
 * Handles lifecycle, duration tracking, max duration enforcement, and cleanup.
 */
class AudioRecorderManager(private val context: Context) {

    interface RecordingCallback {
        fun onDurationUpdate(seconds: Int)
        fun onMaxDurationWarning()
        fun onMaxDurationReached()
        fun onError(message: String)
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false
    private var startTimeMs: Long = 0
    private var callback: RecordingCallback? = null
    private var maxDurationSeconds: Int = 180

    private val handler = Handler(Looper.getMainLooper())
    private val durationRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val elapsed = ((System.currentTimeMillis() - startTimeMs) / 1000).toInt()
            callback?.onDurationUpdate(elapsed)

            // Warning at maxDuration - 15 seconds
            if (elapsed == maxDurationSeconds - 15) {
                callback?.onMaxDurationWarning()
            }

            // Auto-stop at max duration
            if (elapsed >= maxDurationSeconds) {
                callback?.onMaxDurationReached()
                return
            }

            handler.postDelayed(this, 1000)
        }
    }

    fun setCallback(cb: RecordingCallback) {
        callback = cb
    }

    fun setMaxDuration(seconds: Int) {
        maxDurationSeconds = seconds
    }

    /**
     * Start recording to a new M4A file in the app cache directory.
     * Returns true if recording started successfully.
     */
    fun startRecording(): Boolean {
        if (isRecording) return false

        try {
            val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            outputFile = file

            recorder = createMediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            startTimeMs = System.currentTimeMillis()
            handler.post(durationRunnable)
            return true
        } catch (e: Exception) {
            cleanup()
            callback?.onError("Failed to start recording: ${e.message}")
            return false
        }
    }

    /**
     * Stop recording and return the output file.
     * Returns null if recording was not active or failed.
     */
    fun stopRecording(): File? {
        if (!isRecording) return null

        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            isRecording = false
            handler.removeCallbacks(durationRunnable)
            outputFile
        } catch (e: Exception) {
            cleanup()
            callback?.onError("Failed to stop recording: ${e.message}")
            null
        }
    }

    /**
     * Discard the current recording silently.
     * Used when user navigates away or keyboard dismisses mid-recording.
     */
    fun discardRecording() {
        cleanup()
    }

    fun isCurrentlyRecording(): Boolean = isRecording

    fun getCurrentFile(): File? = outputFile

    fun getElapsedSeconds(): Int {
        if (!isRecording) return 0
        return ((System.currentTimeMillis() - startTimeMs) / 1000).toInt()
    }

    private fun cleanup() {
        try {
            if (isRecording) {
                recorder?.stop()
            }
        } catch (_: Exception) {
            // Recorder may not be in a valid state
        }
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
        isRecording = false
        handler.removeCallbacks(durationRunnable)

        // Delete the file if it exists
        outputFile?.let {
            try {
                it.delete()
            } catch (_: Exception) {}
        }
        outputFile = null
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
    }
}
