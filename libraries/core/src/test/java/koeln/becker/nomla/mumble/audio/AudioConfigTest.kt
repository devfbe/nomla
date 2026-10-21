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
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioConfigTest {
    /**
     * A media-stream track does not follow the communication device, so a routed device - any of
     * them, not only a headset - moves playback to the voice-call stream, and only an unrouted
     * session keeps the stream the settings chose.
     */
    @Test
    fun aRoutedDeviceMovesPlaybackToTheVoiceCallStream() {
        val unrouted = AudioConfig(PipelineSettings(audioStream = AudioManager.STREAM_ALARM))
        assertThat(unrouted.playbackStream).isEqualTo(AudioManager.STREAM_ALARM)
        for (type in listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )) {
            assertThat(unrouted.copy(routedDeviceType = type).playbackStream)
                .isEqualTo(AudioManager.STREAM_VOICE_CALL)
        }
    }
}
