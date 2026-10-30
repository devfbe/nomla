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

import kotlin.math.abs
import koeln.becker.nomla.mumble.diagnostics.TelemetryTable

/**
 * Writes a [TelemetryTable] as CSV: a header `t_ms,<columns>`, then one line per row with `t_ms`
 * to 1 decimal and each value to at most 2 decimals (trailing zeros dropped: `1.5`, `2`).
 * `Float.NaN` is an empty cell, infinities are `inf`/`-inf`. Lines end in `\n`. The decimal point
 * is always `.`: numbers are formatted by hand, as with `Locale.ROOT`, whatever the default locale.
 */
internal object TelemetryCsv {
    private const val TIME_SCALE = 10.0
    private const val VALUE_SCALE = 100.0
    private const val TENTH = 10L
    private const val HUNDRED = 100L

    fun write(table: TelemetryTable, out: Appendable) {
        out.append("t_ms")
        for (column in table.columns) out.append(',').append(column)
        out.append('\n')
        val width = table.columns.size
        for (row in 0 until table.rows) {
            appendTime(out, table.timesMs[row])
            for (c in 0 until width) {
                out.append(',')
                appendValue(out, table.values[row * width + c])
            }
            out.append('\n')
        }
    }

    /** One decimal, always written: `0.0`, `12.4`, `-3.0`. */
    private fun appendTime(out: Appendable, ms: Double) {
        val tenths = Math.round(ms * TIME_SCALE)
        if (tenths < 0) out.append('-')
        val magnitude = abs(tenths)
        out.append((magnitude / TENTH).toString()).append('.').append((magnitude % TENTH).toString())
    }

    /** Up to two decimals without trailing zeros; NaN is empty. */
    private fun appendValue(out: Appendable, value: Float) {
        when {
            value.isNaN() -> return
            value == Float.POSITIVE_INFINITY -> out.append("inf")
            value == Float.NEGATIVE_INFINITY -> out.append("-inf")
            else -> {
                val hundredths = Math.round(value * VALUE_SCALE)
                if (hundredths < 0) out.append('-')
                val magnitude = abs(hundredths)
                out.append((magnitude / HUNDRED).toString())
                val fraction = magnitude % HUNDRED
                when {
                    fraction == 0L -> Unit
                    fraction % TENTH == 0L -> out.append('.').append((fraction / TENTH).toString())
                    else -> out.append('.').append((fraction / TENTH).toString()).append((fraction % TENTH).toString())
                }
            }
        }
    }
}
