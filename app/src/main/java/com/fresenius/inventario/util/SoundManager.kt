package com.fresenius.inventario.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Plays scanner-style sounds for success and error events.
 * Uses ToneGenerator for immediate, reliable audio feedback.
 */
class SoundManager(private val context: Context) {

    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
    private val vibrator = context.getSystemService(Vibrator::class.java)

    /**
     * Play a short success beep (like a supermarket scanner).
     */
    fun playSuccess() {
        try {
            // Short, pleasant beep
            toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    /**
     * Play an error/warning sound (distinct from success).
     */
    fun playError() {
        try {
            // Two short low tones = error
            toneGenerator.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 400)
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 100, 100), -1))
        } catch (_: Exception) {}
    }

    fun release() {
        try {
            toneGenerator.release()
        } catch (_: Exception) {}
    }
}
