package com.scholarpulse.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * High-fidelity native audio synthesizer using dynamic PCM 16-bit sine wave generation.
 * Synthesizes exact harmonic sinusoidal audio frequencies matching v2/src/lib/audio.ts
 * with zero external MP3 dependencies.
 *
 * Supported chime types:
 * - focus_start: C5 (523.25 Hz) -> E5 (659.25 Hz) [0.4s]
 * - focus_end: C5 (523.25 Hz) -> E5 (659.25 Hz) -> G5 (783.99 Hz) -> C6 (1046.50 Hz) [0.6s]
 * - break_end: G5 (783.99 Hz) -> E5 (659.25 Hz) -> C5 (523.25 Hz) [0.5s]
 * - achievement: D5 (587.33 Hz) -> F#5 (739.99 Hz) -> A5 (880.00 Hz) -> D6 (1174.66 Hz) [0.7s]
 */
object NativeAudioEngine {
    private const val TAG = "NativeAudioEngine"
    private const val SAMPLE_RATE = 44100
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Pre-computed PCM byte cache to achieve zero-latency instant chime response
    private val pcmCache = HashMap<String, ByteArray>()

    init {
        try {
            pcmCache["focus_start"] = synthesizeChime("focus_start")
            pcmCache["focus_end"] = synthesizeChime("focus_end")
            pcmCache["break_end"] = synthesizeChime("break_end")
            pcmCache["achievement"] = synthesizeChime("achievement")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pre-synthesize PCM chimes", e)
        }
    }

    /**
     * Synthesizes audio samples matching Web Audio API oscillator & exponential gain ramp.
     */
    private fun synthesizeChime(type: String): ByteArray {
        val frequencies = when (type) {
            "focus_start" -> doubleArrayOf(523.25, 659.25)
            "focus_end" -> doubleArrayOf(523.25, 659.25, 783.99, 1046.50)
            "break_end" -> doubleArrayOf(783.99, 659.25, 523.25)
            "achievement" -> doubleArrayOf(587.33, 739.99, 880.00, 1174.66)
            else -> doubleArrayOf(523.25, 659.25)
        }

        val stepSeconds = 0.14f
        val noteDurationSeconds = 0.48f
        val attackSeconds = 0.03f
        val totalDurationSeconds = (frequencies.size - 1) * stepSeconds + noteDurationSeconds
        val totalSamples = (totalDurationSeconds * SAMPLE_RATE).toInt()
        val floatBuffer = FloatArray(totalSamples)

        for ((idx, freq) in frequencies.withIndex()) {
            val startSample = (idx * stepSeconds * SAMPLE_RATE).toInt()
            val noteSamples = (noteDurationSeconds * SAMPLE_RATE).toInt()

            for (n in 0 until noteSamples) {
                val sampleIdx = startSample + n
                if (sampleIdx >= totalSamples) break

                val t = n.toFloat() / SAMPLE_RATE
                val gain = if (t < attackSeconds) {
                    (t / attackSeconds) * 0.75f
                } else {
                    val decayProgress = (t - attackSeconds) / (noteDurationSeconds - attackSeconds)
                    0.75f * exp(-4.2 * decayProgress).toFloat()
                }

                val sine = sin(2.0 * PI * freq * t).toFloat()
                floatBuffer[sampleIdx] += gain * sine
            }
        }

        // Peak volume normalization to avoid audio clipping
        var maxAbs = 0f
        for (sample in floatBuffer) {
            val a = abs(sample)
            if (a > maxAbs) maxAbs = a
        }
        val scale = if (maxAbs > 0.01f) (0.85f / maxAbs) else 1.0f

        // Convert normalized floats to 16-bit PCM signed byte array (Little-Endian)
        val pcmBytes = ByteArray(totalSamples * 2)
        var byteIdx = 0
        for (sample in floatBuffer) {
            val normalized = (sample * scale).coerceIn(-1.0f, 1.0f)
            val shortVal = (normalized * 32767).toInt().toShort()
            pcmBytes[byteIdx++] = (shortVal.toInt() and 0xFF).toByte()
            pcmBytes[byteIdx++] = ((shortVal.toInt() shr 8) and 0xFF).toByte()
        }

        return pcmBytes
    }

    /**
     * Emits a synthesized harmonic chime on a background thread while ducking active audio.
     */
    fun playChime(context: Context, type: String = "focus_end") {
        val pcmData = pcmCache[type] ?: synthesizeChime(type)
        scope.launch {
            playPcmAudio(context.applicationContext, pcmData)
        }
    }

    private suspend fun playPcmAudio(context: Context, pcmData: ByteArray) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val audioTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(pcmData.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate AudioTrack", e)
            return
        }

        val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* transient ducking, no-op */ }
                .build()
        } else {
            null
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_NOTIFICATION,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }

            audioTrack.write(pcmData, 0, pcmData.size)
            audioTrack.play()

            // Sleep coroutine for the exact playback duration plus 60ms tail
            val durationMs = (pcmData.size.toLong() * 1000L) / (SAMPLE_RATE * 2)
            delay(durationMs + 60L)
        } catch (e: Exception) {
            Log.e(TAG, "Error during chime playback", e)
        } finally {
            try {
                audioTrack.stop()
                audioTrack.release()
            } catch (_: Exception) {}

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                    audioManager.abandonAudioFocusRequest(focusRequest)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.abandonAudioFocus(null)
                }
            } catch (_: Exception) {}
        }
    }
}
