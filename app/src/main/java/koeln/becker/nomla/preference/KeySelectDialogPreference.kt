/*
 * Copyright (C) 2014 Andrew Comminos
 * Copyright (C) 2020-2025 Daniel Lublin
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

import android.content.Context
import android.util.AttributeSet
import androidx.preference.DialogPreference
import koeln.becker.nomla.R

/** A key code preference, picked by pressing the key; 0 is none. */
class KeySelectDialogPreference(context: Context, attrs: AttributeSet?) : DialogPreference(context, attrs) {
    init {
        dialogLayoutResource = R.layout.dialog_keyselect_preference
    }
}
