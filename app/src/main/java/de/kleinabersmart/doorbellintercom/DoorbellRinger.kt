package de.kleinabersmart.doorbellintercom

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log

/**
 * Spielt beim Klingeln selbst Klingelton und Vibration ab, statt das der
 * Benachrichtigung zu überlassen: öffnet Android den Klingelbildschirm direkt
 * per Full-Screen-Intent (Display aus/gesperrt), bleibt deren Ton aus.
 *
 * Normal richtet sich das Klingeln nach dem Klingelmodus des Telefons
 * (lautlos: nichts, Vibration: nur vibrieren). Mit AppConfig.ringInSilentMode
 * klingelt es immer, dann über die Wecker-Lautstärke.
 */
object DoorbellRinger {

    private const val TAG = "DoorbellRinger"
    private val VIBRATION_PATTERN = longArrayOf(0, 1000, 1000)

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    /** Startet das Klingeln; endet nach AppConfig.ringDurationSeconds oder mit stop(). */
    fun start(context: Context) {
        handler.post {
            stopNow()
            val appContext = context.applicationContext
            val config = AppConfig(appContext)
            val ringerMode = appContext.getSystemService(AudioManager::class.java).ringerMode
            val ignoreRingerMode = config.ringInSilentMode
            val usage = if (ignoreRingerMode) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_RINGTONE

            if (ignoreRingerMode || ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                player = startSound(appContext, config.ringtoneUri, usage)
            }
            if (ignoreRingerMode || ringerMode != AudioManager.RINGER_MODE_SILENT) {
                vibrator = startVibration(appContext, usage)
            }
            handler.postDelayed(::stopNow, config.ringDurationSeconds * 1000L)
        }
    }

    fun stop() {
        handler.post(::stopNow)
    }

    private fun stopNow() {
        handler.removeCallbacksAndMessages(null)
        player?.run {
            runCatching { stop() }
            release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun startSound(context: Context, uri: Uri, usage: Int): MediaPlayer? {
        // Ein nicht mehr vorhandener Klingelton (z. B. aus einem importierten Backup) fällt auf den Standard zurück.
        val candidates = listOf(uri, Settings.System.DEFAULT_RINGTONE_URI).distinct()
        for (candidate in candidates) {
            val mediaPlayer = MediaPlayer()
            try {
                mediaPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mediaPlayer.setDataSource(context, candidate)
                mediaPlayer.isLooping = true
                mediaPlayer.prepare()
                mediaPlayer.start()
                return mediaPlayer
            } catch (e: Exception) {
                Log.w(TAG, "Klingelton $candidate nicht abspielbar", e)
                mediaPlayer.release()
            }
        }
        return null
    }

    private fun startVibration(context: Context, usage: Int): Vibrator? {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
        if (!vibrator.hasVibrator()) return null
        val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, 0)
        // Aus dem Hintergrund lässt Android nur Vibrationen mit Klingel- oder Wecker-Verwendung zu.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val vibrationUsage = if (usage == AudioAttributes.USAGE_ALARM) {
                VibrationAttributes.USAGE_ALARM
            } else {
                VibrationAttributes.USAGE_RINGTONE
            }
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(vibrationUsage))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(usage).build())
        }
        return vibrator
    }
}
