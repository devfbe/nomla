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

package koeln.becker.nomla.mumble.diagnostics.export

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import koeln.becker.nomla.mumble.diagnostics.TelemetryTable
import org.junit.Test

class TelemetryCsvTest {
    @Test
    fun `header and NaN as empty`() {
        val table = TelemetryTable(
            name = "t",
            columns = listOf("a", "b"),
            timesMs = doubleArrayOf(0.0, 10.0),
            values = floatArrayOf(1.5f, Float.NaN, Float.NaN, 2f),
            rows = 2,
        )

        assertThat(csv(table)).isEqualTo("t_ms,a,b\n0.0,1.5,\n10.0,,2\n")
    }

    @Test
    fun `rounding, signs and infinities`() {
        val table = TelemetryTable(
            name = "t",
            columns = listOf("v"),
            timesMs = doubleArrayOf(0.04, 12.36, -3.0, 1.0, 2.0, 3.0),
            values = floatArrayOf(-0.001f, 3.14159f, -23.456f, 0.05f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY),
            rows = 6,
        )

        assertThat(csv(table)).isEqualTo("t_ms,v\n0.0,0\n12.4,3.14\n-3.0,-23.46\n1.0,0.05\n2.0,inf\n3.0,-inf\n")
    }

    @Test
    fun `the default locale does not change the decimal point`() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val table = TelemetryTable("t", listOf("a"), doubleArrayOf(1.5), floatArrayOf(2.25f), 1)
            assertThat(csv(table)).isEqualTo("t_ms,a\n1.5,2.25\n")
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `rows beyond rows are not written`() {
        val table = TelemetryTable("t", listOf("a"), doubleArrayOf(0.0, 10.0), floatArrayOf(1f, 2f), 1)

        assertThat(csv(table)).isEqualTo("t_ms,a\n0.0,1\n")
    }

    private fun csv(table: TelemetryTable): String = StringBuilder().also { TelemetryCsv.write(table, it) }.toString()
}
