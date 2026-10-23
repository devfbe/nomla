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

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.content.edit
import androidx.preference.PreferenceFragmentCompat
import koeln.becker.nomla.R
import koeln.becker.nomla.databinding.DialogKeyselectPreferenceBinding

/**
 * Waits for a key press and offers it as the value of a [KeySelectDialogPreference]. Shown by its
 * [PreferenceFragmentCompat] as a child fragment, which is where [preference] is looked up by the
 * [ARG_KEY] argument.
 */
class KeySelectPreferenceDialogFragment : AppCompatDialogFragment() {
    private var keyCode = 0
    private var positiveClicked = false
    private var binding: DialogKeyselectPreferenceBinding? = null

    private val preference: KeySelectDialogPreference
        get() = requireNotNull(
            (requireParentFragment() as PreferenceFragmentCompat)
                .findPreference<KeySelectDialogPreference>(requireArguments().getString(ARG_KEY).orEmpty()),
        )

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val preference = preference
        val view = layoutInflater.inflate(preference.dialogLayoutResource, null)
        binding = DialogKeyselectPreferenceBinding.bind(view)
        view.setOnKeyListener { _, code, event -> onKey(code, event) }
        view.isFocusableInTouchMode = true
        view.requestFocus()
        keyCode = requireNotNull(preference.sharedPreferences).getInt(preference.key, 0)
        showKey()
        return AlertDialog.Builder(requireContext())
            .setTitle(preference.dialogTitle)
            .setIcon(preference.dialogIcon)
            .setView(view)
            .setPositiveButton(preference.positiveButtonText) { _, _ -> positiveClicked = true }
            .setNegativeButton(preference.negativeButtonText, null)
            .setNeutralButton(R.string.reset_key) { _, _ ->
                keyCode = 0
                // Not the positive button, so stored here rather than on dismissal.
                persist(keyCode)
            }
            .create()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (positiveClicked) persist(keyCode)
    }

    /** Stores [value] if the preference's change listener accepts it. */
    private fun persist(value: Int) {
        val preference = preference
        if (preference.callChangeListener(value)) {
            requireNotNull(preference.sharedPreferences).edit { putInt(preference.key, value) }
        }
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

    companion object {
        const val ARG_KEY = "key"
    }
}
