package de.kleinabersmart.doorbellintercom

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Video (und, falls vorhanden, Ton) direkt per RTSP von der Kamera – nutzt
 * Googles offizielles ExoPlayer-RTSP-Modul (offener Standard, kein
 * Reverse-Engineering wie beim Baichuan-Protokoll). Kein go2rtc mehr nötig.
 *
 * Der Ton läuft über eine eigene RTSP-Verbindung (Sub-Stream, nur die Tonspur
 * wird abgespielt): In einem gemeinsamen Player wartet der Ton auf das Bild, das
 * bei der Klingel rund 0,4 s später ankommt – das verlängert die Verzögerung beim
 * Gegensprechen und damit das Echo. Lehnt die Kamera die zweite Verbindung ab,
 * läuft der Ton wie früher über die Video-Verbindung.
 *
 * Bei einem Live-Stream entspricht der Pufferinhalt der Verzögerung zum Live-Bild.
 * Wächst er (z. B. nach einem Aussetzer), spielt der Player etwas schneller ab,
 * bis er wieder beim Startpuffer angekommen ist (siehe catchUp).
 */
@OptIn(UnstableApi::class) // DefaultLoadControl, RtspMediaSource, PlayerView-Einstellungen
class DirectRtspClient(
    private val context: Context,
    private val rtspUrl: String,
    private val playerView: PlayerView,
    private val keepAspectRatio: Boolean = true,
    private var muted: Boolean = false,
    private val startBufferMs: Int = AppConfig.VIDEO_START_BUFFER_DEFAULT_MS,
    private val rebufferMs: Int = AppConfig.VIDEO_REBUFFER_DEFAULT_MS,
    private val onConnected: () -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    /** Ein Player mit eigenem Nachhol-Zustand. */
    private class Stream(val player: ExoPlayer, val name: String, val targetBufferMs: Long) {
        var speed = 1f
        var stallsSinceStatus = 0
    }

    private var video: Stream? = null
    private var audio: Stream? = null
    /** Player, der den Ton ausgibt: der eigene Audio-Player oder – als Rückfall – der Video-Player. */
    private val soundPlayer: ExoPlayer? get() = (audio ?: video)?.player
    private var separateAudio = true
    private val mainHandler = Handler(Looper.getMainLooper())
    private var catchUpTicks = 0
    private var ducked = false

    /** Reolink: Sub-Stream statt Main-Stream – die Kamera schickt das Video auch hier mit. */
    private val audioUrl = rtspUrl.substringBeforeLast('/') + "/" +
        rtspUrl.substringAfterLast('/').replace("_main", "_sub")

    private val unduckRunnable = Runnable {
        ducked = false
        Log.d(TAG, "Dämpfung aus")
        applyVolume()
    }

    private val catchUpRunnable = object : Runnable {
        override fun run() {
            catchUp()
            mainHandler.postDelayed(this, CATCH_UP_INTERVAL_MS)
        }
    }

    fun start() {
        val videoPlayer = buildPlayer()
        val videoStream = Stream(videoPlayer, "Video", maxOf(startBufferMs.toLong(), MIN_VIDEO_TARGET_BUFFER_MS))
        video = videoStream
        if (separateAudio) videoPlayer.disableTrackType(C.TRACK_TYPE_AUDIO)

        playerView.useController = false
        playerView.resizeMode = if (keepAspectRatio) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        playerView.player = videoPlayer

        videoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                countStall(videoStream, state)
                if (state == Player.STATE_READY) {
                    mainHandler.post { onConnected() }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                mainHandler.post {
                    onError(context.getString(R.string.status_rtsp_error, error.errorCodeName, error.message))
                }
            }
        })
        videoPlayer.play(rtspUrl)

        if (separateAudio) {
            val audioPlayer = buildPlayer()
            val audioStream = Stream(audioPlayer, "Ton", maxOf(startBufferMs.toLong(), MIN_AUDIO_TARGET_BUFFER_MS))
            audio = audioStream
            audioPlayer.disableTrackType(C.TRACK_TYPE_VIDEO)
            audioPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) = countStall(audioStream, state)

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "Eigene Ton-Verbindung fehlgeschlagen (${error.errorCodeName}), " +
                        "Ton wieder über die Video-Verbindung", error)
                    // Nicht im Listener des Players freigeben, der gerade meldet.
                    mainHandler.post {
                        if (audio !== audioStream) return@post
                        separateAudio = false
                        stop()
                        start()
                    }
                }
            })
            audioPlayer.play(audioUrl)
        }

        // Stumm: Ton wird zwar mitgeladen, aber nicht ausgegeben (z. B. im
        // Klingelbildschirm, bevor der Anruf angenommen wurde).
        applyVolume()
        Log.d(TAG, if (separateAudio) "Ton über eigene Verbindung: ${audioUrl.substringAfterLast('/')}" else "Ton über die Video-Verbindung")
        mainHandler.postDelayed(catchUpRunnable, CATCH_UP_INTERVAL_MS)
    }

    private fun buildPlayer(): ExoPlayer {
        // Standard-LoadControl puffert 2,5 s vor dem Start – bei RTSP gibt es
        // keinen Live-Offset, der das begrenzt, und der Rückstand bleibt dauerhaft.
        // Min- und Max-Puffer sind bewusst groß: Sie begrenzen nur das Nachladen, und
        // ein pausiertes Nachladen würde die Daten im Socket stauen statt im Player.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ LOAD_BUFFER_MS,
                /* maxBufferMs = */ LOAD_BUFFER_MS,
                /* bufferForPlaybackMs = */ startBufferMs,
                /* bufferForPlaybackAfterRebufferMs = */ rebufferMs
            )
            .build()
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(AudioLevelMonitor()))
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                // Standard sind bis zu 250 ms Ton im AudioTrack – die kommen direkt auf die
                // Verzögerung (und damit aufs Echo) drauf. Nie kleiner als das Geräteminimum.
                .setAudioTrackBufferSizeProvider(
                    DefaultAudioTrackBufferSizeProvider.Builder()
                        .setMinPcmBufferDurationUs(AUDIO_TRACK_BUFFER_US)
                        .setMaxPcmBufferDurationUs(AUDIO_TRACK_BUFFER_US)
                        .build()
                )
                .build()
        }
        return ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()
    }

    private fun ExoPlayer.disableTrackType(trackType: Int) {
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, true)
            .build()
    }

    private fun ExoPlayer.play(url: String) {
        val mediaSource = RtspMediaSource.Factory()
            // TCP-interleaved statt UDP: robuster im LAN, kein separates Öffnen
            // von RTP-Ports/UDP-Firewall-Themen nötig.
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(url))
        setMediaSource(mediaSource)
        prepare()
        playWhenReady = true
    }

    /** Kurze BUFFERING-Phasen kommen bei RTSP sehr häufig vor, die zählt nur der Status. */
    private fun countStall(stream: Stream, state: Int) {
        if (state == Player.STATE_BUFFERING) {
            stream.stallsSinceStatus++
        } else if (state != Player.STATE_READY) {
            Log.d(TAG, "${stream.name}: Zustand ${stateName(state)}, Puffer ${stream.player.totalBufferedDuration} ms")
        }
    }

    private fun catchUp() {
        val logStatus = ++catchUpTicks % STATUS_LOG_TICKS == 0
        listOfNotNull(video, audio).forEach { catchUp(it, logStatus) }
    }

    /**
     * Holt Rückstand zum Live-Bild durch leicht beschleunigte Wiedergabe auf (Tonhöhe
     * bleibt gleich). Hysterese, damit nicht ständig zwischen den Geschwindigkeiten
     * gewechselt wird: beschleunigen ab CATCH_UP_START_MS über dem Ziel, zurück auf
     * normal erst, wenn das Ziel bis auf CATCH_UP_STOP_MS erreicht ist.
     */
    private fun catchUp(stream: Stream, logStatus: Boolean) {
        val player = stream.player
        if (player.playbackState != Player.STATE_READY || !player.isPlaying) return
        val buffered = player.totalBufferedDuration
        val lag = buffered - stream.targetBufferMs
        if (logStatus) {
            Log.d(TAG, "${stream.name}: Puffer $buffered ms (Ziel ${stream.targetBufferMs} ms), " +
                "Geschwindigkeit ${stream.speed}, BUFFERING-Phasen ${stream.stallsSinceStatus}")
            stream.stallsSinceStatus = 0
        }
        val speed = when {
            lag > CATCH_UP_FAST_MS -> CATCH_UP_SPEED_FAST
            lag > CATCH_UP_START_MS -> CATCH_UP_SPEED
            lag <= CATCH_UP_STOP_MS -> 1f
            else -> stream.speed
        }
        if (speed != stream.speed) {
            Log.d(TAG, "${stream.name}: Puffer $buffered ms (Ziel ${stream.targetBufferMs} ms) → Geschwindigkeit $speed")
            stream.speed = speed
            player.playbackParameters = PlaybackParameters(speed)
        }
    }

    /** Ton ein-/ausschalten, ohne die Verbindung neu aufzubauen. */
    fun setMuted(muted: Boolean) {
        this.muted = muted
        applyVolume()
    }

    /**
     * Ton leiser stellen, solange man selbst spricht: Die Klingel nimmt die eigene Stimme
     * aus ihrem Lautsprecher wieder auf, und ohne Dämpfung hört man sich verzögert selbst.
     * Dieses Echo kommt erst nach der Puffer- und Netzwerkverzögerung an, daher wird
     * erst danach wieder laut gestellt.
     */
    fun setDucked(enabled: Boolean) {
        mainHandler.removeCallbacks(unduckRunnable)
        if (enabled) {
            if (!ducked) Log.d(TAG, "Dämpfung an")
            ducked = true
            applyVolume()
        } else if (ducked) {
            val delay = (soundPlayer?.totalBufferedDuration ?: 0L) + ECHO_DELAY_MARGIN_MS
            Log.d(TAG, "Dämpfung endet in $delay ms")
            mainHandler.postDelayed(unduckRunnable, delay)
        }
    }

    private fun applyVolume() {
        soundPlayer?.volume = when {
            muted -> 0f
            ducked -> DUCKED_VOLUME
            else -> 1f
        }
    }

    private fun stateName(state: Int) = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> state.toString()
    }

    fun stop() {
        mainHandler.removeCallbacks(catchUpRunnable)
        mainHandler.removeCallbacks(unduckRunnable)
        ducked = false
        playerView.player = null
        video?.player?.release()
        audio?.player?.release()
        video = null
        audio = null
    }

    companion object {
        private const val TAG = "DirectRtspClient"
        private const val LOAD_BUFFER_MS = 5000
        private const val CATCH_UP_INTERVAL_MS = 500L
        private const val AUDIO_TRACK_BUFFER_US = 100_000
        // Das Video der Klingel lässt sich nicht unter ~550 ms Puffer drücken (gemessen 530–700 ms,
        // dabei ständig kurze BUFFERING-Phasen). Ein kleineres Ziel würde nur dauerhaft beschleunigen.
        private const val MIN_VIDEO_TARGET_BUFFER_MS = 600L
        // Der Puffer enthält auch den Ton im AudioTrack und kann nicht darunter sinken. Ein
        // kleineres Ziel würde dauerhaft beschleunigen und Aussetzer samt Nachpuffern auslösen.
        private const val MIN_AUDIO_TARGET_BUFFER_MS = AUDIO_TRACK_BUFFER_US / 1000 + 100L
        /** Alle 5 s (bei CATCH_UP_INTERVAL_MS = 500) den Pufferstand ins Log schreiben. */
        private const val STATUS_LOG_TICKS = 10
        private const val CATCH_UP_START_MS = 250L
        private const val CATCH_UP_STOP_MS = 50L
        // Ab hier schneller aufholen, z. B. nach einem Aussetzer.
        private const val CATCH_UP_FAST_MS = 500L
        private const val CATCH_UP_SPEED = 1.1f
        private const val CATCH_UP_SPEED_FAST = 1.25f
        /** Lautstärke während des eigenen Sprechens (etwa −26 dB). */
        private const val DUCKED_VOLUME = 0.05f
        /**
         * Verzögerung des Echos zusätzlich zum Player-Puffer (Senden, Wiedergabe und
         * RTSP-Kodierung in der Klingel). Gemessen wurden etwa 1,0–1,3 s; dazu kommt
         * die Nachlaufzeit der Sprecherkennung, damit ist bis ~1,9 s Luft.
         */
        private const val ECHO_DELAY_MARGIN_MS = 1500L
    }
}
