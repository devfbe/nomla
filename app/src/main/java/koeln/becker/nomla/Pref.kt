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

import android.content.SharedPreferences

/**
 * A preference: its key, as in the `settings_*.xml` screens, and the value it reads as while unset,
 * which must match the screen's `android:defaultValue` (`SettingsDefaultsTest` checks both).
 */
open class Pref<T>(val key: String, val default: T)

/**
 * A list preference whose entries are [entries], each stored as its [storedAs] string. It reads as
 * [defaultEntry] while unset and for any stored string no entry has, so [default] and the fallback
 * can never disagree.
 */
class EnumPref<E : Enum<E>>(
    key: String,
    val defaultEntry: E,
    private val entries: List<E>,
    private val storedAs: (E) -> String,
) : Pref<String>(key, storedAs(defaultEntry)) {

    /** The entry [stored] names, or [defaultEntry]. */
    fun decode(stored: String?): E = entries.firstOrNull { storedAs(it) == stored } ?: defaultEntry

    fun encode(entry: E): String = storedAs(entry)
}

/** The stored value of [pref], or its default. Supports Boolean, Int, Long, Float and String. */
@Suppress("UNCHECKED_CAST")
fun <T : Any> SharedPreferences.read(pref: Pref<T>): T = when (val default: Any = pref.default) {
    is Boolean -> getBoolean(pref.key, default)
    is Int -> getInt(pref.key, default)
    is Long -> getLong(pref.key, default)
    is Float -> getFloat(pref.key, default)
    is String -> getString(pref.key, default) ?: default
    else -> error("Unsupported preference type ${default::class} of ${pref.key}")
} as T

fun <T : Any> SharedPreferences.Editor.write(pref: Pref<T>, value: T) {
    when (value) {
        is Boolean -> putBoolean(pref.key, value)
        is Int -> putInt(pref.key, value)
        is Long -> putLong(pref.key, value)
        is Float -> putFloat(pref.key, value)
        is String -> putString(pref.key, value)
        else -> error("Unsupported preference type ${value::class} of ${pref.key}")
    }
}
