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

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import com.google.common.truth.Truth.assertThat
import koeln.becker.nomla.mumble.audio.capture.AndroidAudioEffects
import koeln.becker.nomla.mumble.audio.capture.AudioSourcePolicy
import koeln.becker.nomla.mumble.audio.capture.EchoCancellationMode
import koeln.becker.nomla.mumble.audio.routing.AudioDeviceCategory
import org.junit.Test

/**
 * A media route (`AudioRouter.isMediaRoute`) only works outside the communication mode: in it the
 * platform plays media where calls go. So its pipeline must ask for nothing that takes the mode or
 * the call source, whatever the user switched on for a Bluetooth headset.
 */
class MediaPlaybackConfigTest {
    private val settings = AudioSettings(
        echoCancellationOverrides = mapOf(AudioDeviceCategory.BLUETOOTH to true),
        pipeline = PipelineSettings(
            audioStream = AudioManager.STREAM_VOICE_CALL,
            androidEffects = AndroidAudioEffects(true, true),
            echoCancellationVoiceCallSource = true,
        ),
    )

    @Test
    fun `a media route plays as music and records the plain microphone`() {
        val config = settings.toAudioConfig(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, mediaPlayback = true)

        assertThat(config.playbackStream).isEqualTo(AudioManager.STREAM_MUSIC)
        assertThat(config.echoCancellation).isEqualTo(EchoCancellationMode.NONE)
        assertThat(AudioSourcePolicy.needsCommunicationMode(config.androidEffects, config.echoCancellation))
            .isFalse()
        val source = AudioSourcePolicy.resolve(
            MediaRecorder.AudioSource.MIC, config.androidEffects, config.echoCancellation,
            config.captureRoute, config.voiceCallSource,
        )
        assertThat(source).isEqualTo(MediaRecorder.AudioSource.MIC)
    }

    @Test
    fun `the same headset on the call route keeps what the user switched on`() {
        val config = settings.toAudioConfig(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)

        assertThat(config.playbackStream).isEqualTo(AudioManager.STREAM_VOICE_CALL)
        assertThat(config.echoCancellation).isEqualTo(EchoCancellationMode.WEBRTC)
        assertThat(config.androidEffects).isEqualTo(settings.pipeline.androidEffects)
        assertThat(config.captureRoute).isEqualTo(AudioDeviceCategory.BLUETOOTH)
        assertThat(config.voiceCallSource).isTrue()
    }
}
