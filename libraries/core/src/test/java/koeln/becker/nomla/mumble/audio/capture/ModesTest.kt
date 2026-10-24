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

package koeln.becker.nomla.mumble.audio.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModesTest {
    /** Pins the stored spelling: a wrong value would leave the settings list with nothing selected. */
    @Test
    fun `every constant keeps its on-disk value`() {
        assertThat(NoiseSuppressionMode.entries.associate { it.name to it.preferenceValue })
            .containsExactly("NONE", "none", "SPEEX", "speex", "RNNOISE", "rnnoise")
        assertThat(EchoCancellationMode.entries.associate { it.name to it.preferenceValue })
            .containsExactly("NONE", "none", "WEBRTC", "webrtc")
    }

    /** Two constants sharing an on-disk value would make one of them unreachable from settings. */
    @Test
    fun `preference values are distinct within each mode`() {
        assertThat(NoiseSuppressionMode.entries.map { it.preferenceValue })
            .containsNoDuplicates()
        assertThat(EchoCancellationMode.entries.map { it.preferenceValue })
            .containsNoDuplicates()
    }

    /** All four inputs: `||` mutated to `xor` differs only when both effects are ticked. */
    @Test
    fun `android effects any is true for every combination with an effect on`() {
        assertThat(AndroidAudioEffects(noiseSuppressor = false, automaticGainControl = false).any).isFalse()
        assertThat(AndroidAudioEffects(noiseSuppressor = true, automaticGainControl = false).any).isTrue()
        assertThat(AndroidAudioEffects(noiseSuppressor = false, automaticGainControl = true).any).isTrue()
        assertThat(AndroidAudioEffects(noiseSuppressor = true, automaticGainControl = true).any).isTrue()
    }

    /** Both off means "attach nothing". */
    @Test
    fun `android effects default to both off`() {
        assertThat(AndroidAudioEffects().noiseSuppressor).isFalse()
        assertThat(AndroidAudioEffects().automaticGainControl).isFalse()
    }
}
