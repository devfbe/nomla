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

import com.google.common.truth.Truth.assertThat
import koeln.becker.nomla.mumble.audio.capture.EchoCancellationMode
import koeln.becker.nomla.mumble.audio.capture.VadConfig
import koeln.becker.nomla.mumble.audio.capture.VoiceActivityDetector
import koeln.becker.nomla.mumble.audio.inputmode.ActivityInputMode
import koeln.becker.nomla.mumble.audio.inputmode.ContinuousInputMode
import koeln.becker.nomla.mumble.audio.inputmode.ToggleInputMode
import koeln.becker.nomla.mumble.diagnostics.CaptureDescription
import koeln.becker.nomla.mumble.diagnostics.PipelineDescription
import org.junit.Test

/**
 * What a recording keeps of a pipeline's settings (`PipelineDescription.settings`): a fixed set of
 * setting keys whose values are numbers, flags and enum identifiers, never anything a user typed.
 */
class PipelineSettingsDescriptionTest {
    private val config = AudioConfig(
        settings = PipelineSettings(inputSampleRate = 16_000, amplitudeBoost = 1.5f),
        halfDuplex = true,
        routedDeviceType = 7,
        echoCancellation = EchoCancellationMode.WEBRTC,
    )

    private val modes = mapOf(
        "voice_activity" to ActivityInputMode(VoiceActivityDetector(VadConfig.DEFAULT)),
        "push_to_talk" to ToggleInputMode(),
        "continuous" to ContinuousInputMode(),
    )

    @Test
    fun `the settings are described by fixed keys with plain values`() {
        for ((name, mode) in modes) {
            val described = describePipelineSettings(config, mode)

            assertThat(described.keys).containsExactly(
                "input_mode", "input_sample_rate", "noise_suppression", "rnnoise_attenuation_limit_db",
                "speex_noise_suppress_db", "echo_cancellation", "vad", "amplitude_boost", "half_duplex",
                "android_noise_suppressor", "android_automatic_gain_control",
                "echo_cancellation_voice_call_source", "opus_fec", "opus_loss_percent", "opus_dtx",
            ).inOrder()
            assertThat(described["input_mode"]).isEqualTo(name)
            assertThat(described["input_sample_rate"]).isEqualTo("16000")
            assertThat(described["amplitude_boost"]).isEqualTo("1.5")
            assertThat(described["half_duplex"]).isEqualTo("true")
            // Numbers, flags, identifiers and the VAD config's own fields: nothing free-form.
            for (value in described.values) assertThat(value).matches("[A-Za-z0-9_.,=() -]*")
        }
    }

    @Test
    fun `only voice activity describes a detector`() {
        assertThat(describePipelineSettings(config, ToggleInputMode())["vad"]).isEqualTo("none")
        assertThat(describePipelineSettings(config, modes.getValue("voice_activity"))["vad"]).isNotEqualTo("none")
    }

    @Test
    fun `the encoder's values are added to a description`() {
        val description = PipelineDescription(
            CaptureDescription(sampleRate = 48_000),
            describePipelineSettings(config, ContinuousInputMode()),
            emptySet(),
        ).withEncoder(bitrate = 24_000, framesPerPacket = 4)

        assertThat(description.settings["bitrate"]).isEqualTo("24000")
        assertThat(description.settings["frames_per_packet"]).isEqualTo("4")
    }
}
