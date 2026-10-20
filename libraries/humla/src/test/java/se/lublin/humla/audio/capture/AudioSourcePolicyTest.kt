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

package se.lublin.humla.audio.capture

import android.media.MediaRecorder
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.routing.AudioDeviceCategory

/**
 * All 2 x 2 x 2 corners of (noise suppressor, AGC, echo mode) are enumerated: `||` mutated to
 * `xor` survives a per-clause sweep, differing only at (true, true).
 */
class AudioSourcePolicyTest {
    private val mic = MediaRecorder.AudioSource.MIC
    private val voiceComm = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    private val voiceRecognition = MediaRecorder.AudioSource.VOICE_RECOGNITION
    private val noEffects = AndroidAudioEffects()
    private val noEcho = EchoCancellationMode.NONE

    /** Every corner of (noiseSuppressor, automaticGainControl, echo) with the answer it must give. */
    private val corners: List<Triple<AndroidAudioEffects, EchoCancellationMode, Boolean>> =
        listOf(false, true).flatMap { ns ->
            listOf(false, true).flatMap { agc ->
                EchoCancellationMode.entries.map { echo ->
                    Triple(
                        AndroidAudioEffects(noiseSuppressor = ns, automaticGainControl = agc),
                        echo,
                        ns || agc || echo != EchoCancellationMode.NONE,
                    )
                }
            }
        }

    /**
     * Swept over every corner: both effects at once is the one the `||`-to-`xor` mutation lives in.
     * WebRTC echo cancellation is ours, not the platform's, but it still needs the communication
     * mode the session's router holds anyway.
     */
    @Test
    fun `communication mode is needed exactly when an effect or a canceller is active`() {
        assertThat(corners).hasSize(8) // four effect corners times two cancellers
        val wrong = corners.filter {
            AudioSourcePolicy.needsCommunicationMode(it.first, it.second) != it.third
        }
        assertThat(wrong).isEmpty()
    }

    @Test
    fun `an effect always takes the voice-call source`() {
        val wrong = corners.filter {
            val resolved = AudioSourcePolicy.resolve(mic, it.first, it.second, AudioDeviceCategory.SPEAKER, false)
            resolved != (if (it.first.any) voiceComm else mic)
        }
        assertThat(wrong).isEmpty()
    }

    /** AEC3 alone wants the raw microphone, not the device's processing in front of it. */
    @Test
    fun `the webrtc canceller alone records from the requested source off bluetooth`() {
        val routes = listOf(null, AudioDeviceCategory.SPEAKER, AudioDeviceCategory.EARPIECE, AudioDeviceCategory.WIRED)
        for (route in routes) {
            assertThat(webrtc(mic, route)).isEqualTo(mic)
            assertThat(webrtc(voiceRecognition, route)).isEqualTo(voiceRecognition)
        }
    }

    /** A `MIC` capture would not follow the communication device to the SCO headset's microphone. */
    @Test
    fun `the webrtc canceller on bluetooth takes the voice-call source`() {
        assertThat(webrtc(mic, AudioDeviceCategory.BLUETOOTH)).isEqualTo(voiceComm)
    }

    @Test
    fun `the voice-call setting brings the old source back for the webrtc canceller`() {
        for (route in AudioDeviceCategory.entries + null) {
            assertThat(webrtc(mic, route, voiceCallSource = true)).isEqualTo(voiceComm)
        }
    }

    /** Without a canceller or an effect neither the route nor the setting moves the source. */
    @Test
    fun `plain capture ignores the route and the setting`() {
        for (route in AudioDeviceCategory.entries + null) {
            for (flag in listOf(false, true)) {
                assertThat(AudioSourcePolicy.resolve(mic, noEffects, noEcho, route, flag)).isEqualTo(mic)
            }
        }
    }

    @Test
    fun `an effect with the webrtc canceller takes the voice-call source on any route`() {
        val effects = AndroidAudioEffects(noiseSuppressor = true)
        for (route in AudioDeviceCategory.entries + null) {
            assertThat(AudioSourcePolicy.resolve(mic, effects, EchoCancellationMode.WEBRTC, route, false))
                .isEqualTo(voiceComm)
        }
    }

    /** The pass-through returns the caller's source, not a hardcoded MIC. */
    @Test
    fun `plain capture keeps whichever source was requested`() {
        assertThat(AudioSourcePolicy.resolve(voiceRecognition, noEffects, noEcho, null, false))
            .isEqualTo(voiceRecognition)
        assertThat(AudioSourcePolicy.resolve(mic, noEffects, noEcho, null, false)).isEqualTo(mic)
    }

    private fun webrtc(requested: Int, route: AudioDeviceCategory?, voiceCallSource: Boolean = false) =
        AudioSourcePolicy.resolve(requested, noEffects, EchoCancellationMode.WEBRTC, route, voiceCallSource)
}
