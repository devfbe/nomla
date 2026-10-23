/*
 * Copyright (C) 2025 Daniel Lublin
 * Copyright (C) 2026 William Goodspeed
 * Copyright (C) 2026 The Nomla Authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package koeln.becker.nomla.audio

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import koeln.becker.nomla.mumble.audio.CapturePreview
import koeln.becker.nomla.mumble.audio.MeterReading
import koeln.becker.nomla.mumble.audio.PcmPlaybackSinkFactory
import koeln.becker.nomla.mumble.audio.capture.EchoCancellationMode
import koeln.becker.nomla.mumble.audio.capture.PcmCaptureSourceFactory
import koeln.becker.nomla.mumble.audio.capture.VadConfig
import koeln.becker.nomla.mumble.audio.routing.AudioDeviceCategory
import koeln.becker.nomla.mumble.exception.AudioInitializationException
import koeln.becker.nomla.mumble.util.NomlaLog
import koeln.becker.nomla.Settings
import koeln.becker.nomla.ui.isGranted

/**
 * The microphone test behind a level meter: a [CapturePreview] built from the user's settings, its
 * readings handed to [onReading] on the main thread. Main thread only. It captures from
 * [captureFactory] and plays back to [sinkFactory]; screens get one from `AppContainer.micCheck`.
 *
 * It takes the microphone and may switch the phone to communication mode, which quietens other
 * apps' audio, so callers start it only when the user asks and stop it when their screen pauses.
 */
class MicCheck(
    private val context: Context,
    private val captureFactory: PcmCaptureSourceFactory,
    private val sinkFactory: PcmPlaybackSinkFactory,
    private val onReading: (MeterReading) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var preview: CapturePreview? = null

    val isRunning: Boolean get() = preview != null

    /**
     * (Re)starts the test, with [loopback] playing back what would be sent, and the echo canceller
     * the user has for [echoCategory]. Returns the voice gate it measures with, or null when the
     * microphone is unavailable: no permission, or another app holds it.
     */
    fun start(loopback: Boolean, echoCategory: AudioDeviceCategory): VadConfig? {
        stop()
        if (!context.isGranted(Manifest.permission.RECORD_AUDIO)) return null
        val settings = Settings.getInstance(context)
        val vad = settings.vadConfig
        lateinit var started: CapturePreview
        started = CapturePreview(
            context.getSystemService(AudioManager::class.java),
            vad,
            settings.noiseSuppressionMode,
            settings.speexNoiseSuppressDb,
            if (settings.isEchoCancellationEnabled(echoCategory)) EchoCancellationMode.WEBRTC
            else EchoCancellationMode.NONE,
            settings.androidAudioEffects,
            loopback,
            // A capture thread that outlived stop()'s join must not reach a stopped test's meter.
            onReading = { reading -> mainHandler.post { if (preview === started) onReading(reading) } },
            rnnoiseAttenuationLimitDb = settings.rnnoiseAttenuationLimitDb,
            captureFactory = captureFactory,
            sinkFactory = sinkFactory,
            route = echoCategory,
            echoCancellationVoiceCallSource = settings.isEchoCancellationVoiceCallSource,
        )
        return try {
            started.start()
            preview = started
            vad
        } catch (e: AudioInitializationException) {
            NomlaLog.w(TAG, "microphone test unavailable", e)
            null
        }
    }

    /** Forgets what the voice gate has learned about the room. */
    fun recalibrate() {
        preview?.recalibrate()
    }

    /** Releases the microphone and the audio mode; readings already posted are dropped. */
    fun stop() {
        preview?.stop()
        preview = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    private companion object {
        private const val TAG = "MicCheck"
    }
}
