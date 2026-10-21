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

package koeln.becker.nomla

/**
 * What a headset / Bluetooth media button (play-pause, headset hook) does while connected.
 * The [prefValue] strings are the entry values of `@array/mediaButtonActionValues`.
 */
enum class MediaButtonAction(val prefValue: String) {
    /** Media buttons are ignored. */
    NONE("none"),

    /** Toggle push-to-talk in PTT mode, toggle self-mute in every other transmit mode. */
    AUTO("auto"),

    /** Always toggle self-mute. */
    MUTE("mute");

    companion object {
        fun fromPrefValue(value: String?): MediaButtonAction =
            entries.firstOrNull { it.prefValue == value } ?: AUTO
    }
}
