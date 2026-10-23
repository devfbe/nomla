/*
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

package koeln.becker.nomla.mumble.audio

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import koeln.becker.nomla.mumble.audio.capture.AndroidAudioEffects
import koeln.becker.nomla.mumble.audio.capture.CaptureRequest
import koeln.becker.nomla.mumble.audio.capture.EchoCancellationMode
import koeln.becker.nomla.mumble.audio.inputmode.ContinuousInputMode
import koeln.becker.nomla.mumble.audio.routing.AudioDeviceCategory
import koeln.becker.nomla.mumble.exception.AudioException
import koeln.becker.nomla.mumble.exception.AudioInitializationException
import koeln.becker.nomla.mumble.model.UserState
import koeln.becker.nomla.mumble.net.NomlaUdpMessageType
import koeln.becker.nomla.mumble.testutil.NoopEncodeListener
import koeln.becker.nomla.mumble.testutil.NoopOutputListener
import koeln.becker.nomla.mumble.testutil.SilentLogger

/**
 * A real pipeline needs a microphone, so what a JVM test can reach is the part of AudioHandler's
 * constructor that runs before the RECORD_AUDIO check.
 */
@RunWith(RobolectricTestRunner::class)
class AudioHandlerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val params = AudioSessionParams(
        UserState(1, "me", 0), 72_000, NomlaUdpMessageType.UDPVoiceOpus, 0, ContinuousInputMode(),
    )
    private val host = AudioHost(
        context,
        SilentLogger,
        NoopEncodeListener,
        NoopOutputListener,
    )

    private fun create(config: AudioConfig) = DefaultAudioHandlerFactory.create(host, config, params) {}

    @Test
    fun withoutTheRecordAudioPermissionCreationFailsWithAnAudioException() {
        val e = assertThrows(AudioException::class.java) { create(AudioConfig()) }
        assertThat(e).hasMessageThat().contains("RECORD_AUDIO")
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
    }

    /** The WebRTC canceller puts the manager into communication mode before the permission check. */
    @Test
    fun echoCancellationSwitchesToCommunicationMode() {
        assertThrows(AudioException::class.java) {
            create(AudioConfig(echoCancellation = EchoCancellationMode.WEBRTC))
        }
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_IN_COMMUNICATION)
    }

    @Test
    fun anAndroidEffectSwitchesToCommunicationMode() {
        val settings = PipelineSettings(androidEffects = AndroidAudioEffects(automaticGainControl = true))
        assertThrows(AudioException::class.java) { create(AudioConfig(settings)) }
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_IN_COMMUNICATION)
    }

    /** The request the microphone is opened with; the fake factory refuses it so nothing else runs. */
    private fun captureRequest(config: AudioConfig): CaptureRequest {
        shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        var request: CaptureRequest? = null
        assertThrows(AudioInitializationException::class.java) {
            AudioHandler(host, config, params, {}) {
                request = it
                throw AudioInitializationException("test")
            }
        }
        return requireNotNull(request)
    }

    /** The route and the setting reach the request, so the source resolves the same in both places. */
    @Test
    fun theCaptureRequestCarriesTheRouteAndTheVoiceCallSetting() {
        val aec = AudioConfig(
            echoCancellation = EchoCancellationMode.WEBRTC,
            routedDeviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )

        val speaker = captureRequest(aec)
        assertThat(speaker.audioSource).isEqualTo(MediaRecorder.AudioSource.MIC)
        assertThat(speaker.route).isEqualTo(AudioDeviceCategory.SPEAKER)
        assertThat(speaker.echoCancellationVoiceCallSource).isFalse()

        val sco = captureRequest(aec.copy(routedDeviceType = AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertThat(sco.audioSource).isEqualTo(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        assertThat(sco.route).isEqualTo(AudioDeviceCategory.BLUETOOTH)

        val asBefore = captureRequest(aec.copy(settings = PipelineSettings(echoCancellationVoiceCallSource = true)))
        assertThat(asBefore.audioSource).isEqualTo(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        assertThat(asBefore.echoCancellationVoiceCallSource).isTrue()
    }
}
