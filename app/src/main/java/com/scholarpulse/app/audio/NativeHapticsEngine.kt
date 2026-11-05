package com.scholarpulse.app.audio

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Native Haptics Engine delivering crisp tactile waveforms across Android API levels.
 *
 * Supported styles:
 * - light: 15ms quick tick (EFFECT_TICK)
 * - medium: 30ms click (EFFECT_CLICK)
 * - heavy: 60ms solid click (EFFECT_HEAVY_CLICK)
 * - success: 2 short pulses [0, 40, 60, 40]
 * - warning: 2 pulses [0, 40, 80, 40]
 * - error: 3 assertive pulses [0, 50, 40, 50, 40, 80]
 */
object NativeHapticsEngine {
    private const val TAG = "NativeHapticsEngine"

    fun vibrate(context: Context, style: String = "light"): Boolean {
        return try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return false

            if (!vibrator.hasVibrator()) return false

            val normalizedStyle = style.lowercase()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val effect = when (normalizedStyle) {
                    "light" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                    "medium" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                    "heavy" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                    "success" -> VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), -1)
                    "warning" -> VibrationEffect.createWaveform(longArrayOf(0, 40, 80, 40), -1)
                    "error" -> VibrationEffect.createWaveform(longArrayOf(0, 50, 40, 50, 40, 80), -1)
                    else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                }
                vibrator.vibrate(effect)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                when (normalizedStyle) {
                    "light" -> vibrator.vibrate(VibrationEffect.createOneShot(15L, VibrationEffect.DEFAULT_AMPLITUDE))
                    "medium" -> vibrator.vibrate(VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE))
                    "heavy" -> vibrator.vibrate(VibrationEffect.createOneShot(60L, VibrationEffect.DEFAULT_AMPLITUDE))
                    "success" -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), -1))
                    "warning" -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 80, 40), -1))
                    "error" -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 50, 40, 50, 40, 80), -1))
                    else -> vibrator.vibrate(VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            } else {
                @Suppress("DEPRECATION")
                when (normalizedStyle) {
                    "light" -> vibrator.vibrate(15L)
                    "medium" -> vibrator.vibrate(30L)
                    "heavy" -> vibrator.vibrate(60L)
                    "success" -> vibrator.vibrate(longArrayOf(0, 40, 60, 40), -1)
                    "warning" -> vibrator.vibrate(longArrayOf(0, 40, 80, 40), -1)
                    "error" -> vibrator.vibrate(longArrayOf(0, 50, 40, 50, 40, 80), -1)
                    else -> vibrator.vibrate(30L)
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to perform haptic feedback for style: $style", e)
            false
        }
    }
}
