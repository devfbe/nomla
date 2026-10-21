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

package koeln.becker.nomla.preference

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExportPasswordPolicyTest {
    @Test
    fun `both fields empty asks to confirm an unprotected export`() {
        assertThat(ExportPasswordPolicy.decide("", "")).isEqualTo(ExportPasswordDecision.CONFIRM_UNPROTECTED)
    }

    @Test
    fun `a confirmation that differs is a mismatch, also when one side is empty`() {
        assertThat(ExportPasswordPolicy.decide("secret", "other")).isEqualTo(ExportPasswordDecision.MISMATCH)
        assertThat(ExportPasswordPolicy.decide("secret", "")).isEqualTo(ExportPasswordDecision.MISMATCH)
        assertThat(ExportPasswordPolicy.decide("", "secret")).isEqualTo(ExportPasswordDecision.MISMATCH)
    }

    @Test
    fun `matching non empty passwords export under the password`() {
        assertThat(ExportPasswordPolicy.decide("secret", "secret")).isEqualTo(ExportPasswordDecision.EXPORT)
    }
}
