/*
 * Copyright (C) 2025 Daniel Lublin
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

import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AlertDialog
import koeln.becker.nomla.R
import koeln.becker.nomla.databinding.DialogKeyselectPreferenceBinding

/** Waits for a key press and offers it as the value of a [KeySelectDialogPreference]. */
class KeySelectPreferenceDialogFragment : PreferenceValueDialogFragment<KeySelectDialogPreference>() {
    private var keyCode = 0
    private var binding: DialogKeyselectPreferenceBinding? = null

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        builder.setNeutralButton(R.string.reset_key) { _, _ ->
            keyCode = 0
            // Not the positive button, so stored here rather than on dismissal.
            persist(keyCode)
        }
    }

    override fun onBindDialogView(view: View) {
        binding = DialogKeyselectPreferenceBinding.bind(view)
        view.setOnKeyListener { _, code, event -> onKey(code, event) }
        view.isFocusableInTouchMode = true
        view.requestFocus()
        keyCode = storedValue(0)
        showKey()
    }

    private fun onKey(code: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (code == KeyEvent.KEYCODE_BACK) {
            dismiss()
        } else {
            keyCode = code
            showKey()
        }
        return true
    }

    private fun showKey() {
        val view = binding?.keySelectValueView ?: return
        if (keyCode == 0) {
            view.setText(R.string.no_ptt_key)
        } else {
            view.text = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        }
    }

    override fun currentValue(): Int = keyCode
}
