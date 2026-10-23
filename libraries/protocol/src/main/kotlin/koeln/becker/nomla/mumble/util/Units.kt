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

package koeln.becker.nomla.mumble.util

/** Reads a byte unsigned: `b.toInt() and BYTE_MASK` is 0 to 255. */
public const val BYTE_MASK: Int = 0xFF

/** Nanoseconds per microsecond, as `System.nanoTime` differences are scaled. */
public const val NANOS_PER_MICRO: Long = 1_000L

/** Nanoseconds per millisecond. */
public const val NANOS_PER_MILLI: Long = 1_000_000L
