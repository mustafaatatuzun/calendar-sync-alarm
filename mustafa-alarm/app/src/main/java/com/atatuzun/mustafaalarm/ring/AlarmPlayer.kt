package com.atatuzun.mustafaalarm.ring

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.atatuzun.mustafaalarm.R
import com.atatuzun.mustafaalarm.data.settings.AlarmSettings
import com.atatuzun.mustafaalarm.data.settings.SoundMode
import com.atatuzun.mustafaalarm.domain.FadeCurve

/**
 * Plays the alarm on the ALARM stream, loops it, fades it in, vibrates (spec §8). Main thread only.
 * While playing it holds an active remote-volume media session, so Android hands every volume-key press to
 * [onVolumeKey] — also over the lock screen, where the key-focused window is SystemUI, not RingingActivity.
 */
class AlarmPlayer(private val context: Context, private val onVolumeKey: () -> Unit = {}) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var media: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var volumeKeys: MediaSession? = null
    private var restoreVolume: Int? = null
    private var fadeStart = 0L
    private var target = 1f

    private val fade = object : Runnable {
        override fun run() {
            val volume = target * FadeCurve.volumeAt(SystemClock.elapsedRealtime() - fadeStart)
            media?.setVolume(volume, volume)
            if (volume < target) handler.postDelayed(this, 500)
        }
    }

    /**
     * Starts playback.
     *
     * @param sound            URI to play; null uses the bundled default (before first unlock, or when
     *                         the chosen sound cannot be opened).
     * @param settings         current alarm settings.
     * @param savedRestoreVolume non-null on a crash-restart: the pre-ring volume that was already
     *                         persisted to DataStore before the process was killed. The alarm stream
     *                         is already at max, so we skip raising and just record the restore target.
     *                         Null on a normal first ring: read current, raise, record.
     */
    fun start(sound: Uri?, settings: AlarmSettings, savedRestoreVolume: Int? = null) {
        if (settings.increaseDeviceVolume) {
            // runCatching: changing volume can throw under some Do Not Disturb modes; ringing must go on regardless.
            runCatching {
                if (savedRestoreVolume != null) {
                    // Crash recovery: alarm stream already at max. Just record the restore target.
                    restoreVolume = savedRestoreVolume
                } else {
                    // Fresh start: read current level, raise to max, record the level to restore on stop().
                    val before = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                    audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                    restoreVolume = before
                }
            }
        }
        target = settings.volumePercent.coerceIn(1, 100) / 100f
        if (settings.soundMode != SoundMode.VIBRATION_ONLY) {
            media = sound?.let { open(it) } ?: open(null)
            if (settings.fadeIn) {
                fadeStart = SystemClock.elapsedRealtime()
                fade.run()
            } else {
                media?.setVolume(target, target)
            }
        }
        if (settings.soundMode != SoundMode.SOUND_ONLY) {
            vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator.also {
                it.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
                )
            }
        }
        volumeKeys = runCatching { claimVolumeKeys() }.getOrNull()
    }

    private fun claimVolumeKeys(): MediaSession = MediaSession(context, "MustafaAlarm ringing").apply {
        // Without a Callback the session drops volume-adjust messages before they reach the VolumeProvider.
        setCallback(object : MediaSession.Callback() {}, handler)
        setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 1, 1) {
            override fun onAdjustVolume(direction: Int) = onVolumeKey()
        })
        setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
        isActive = true
    }

    fun stop() {
        volumeKeys?.runCatching { isActive = false; release() }
        volumeKeys = null
        handler.removeCallbacks(fade)
        media?.runCatching { stop(); release() }
        media = null
        vibrator?.cancel()
        vibrator = null
        restoreVolume?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
        restoreVolume = null
    }

    private fun open(uri: Uri?): MediaPlayer? = runCatching {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            if (uri != null) setDataSource(context, uri)
            else context.resources.openRawResourceFd(R.raw.default_alarm).use { setDataSource(it) }
            isLooping = true
            prepare()
            start()
        }
    }.getOrNull()
}
