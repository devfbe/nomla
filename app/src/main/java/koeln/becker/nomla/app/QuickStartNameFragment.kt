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
package koeln.becker.nomla.app

import android.app.Dialog
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.ui.TextInputField
import koeln.becker.nomla.ui.textInputDialog

/**
 * Asks for the nickname "Just talk" joins with, prefilled with the default username. A valid name
 * is delivered as a fragment result under [REQUEST_KEY], to the fragment manager showing the
 * dialog; read it with [nickname].
 */
class QuickStartNameFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val field = TextInputField(
            requireContext(),
            getString(R.string.quick_start_name_hint),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
        )
        field.editText.filters = arrayOf(InputFilter.LengthFilter(QuickStart.MAX_NICKNAME_LENGTH))
        field.editText.setAutofillHints(View.AUTOFILL_HINT_USERNAME)
        // A recreated dialog gets the text it had back.
        if (savedInstanceState == null) {
            field.editText.setText(Settings.getInstance(requireContext()).defaultUsername)
            field.editText.selectAll()
        }
        return requireActivity().textInputDialog(
            R.string.quick_start_name_title,
            field,
            R.string.quick_start_go,
            imeAction = EditorInfo.IME_ACTION_GO,
            validate = { name ->
                if (QuickStart.isValidNickname(name.trim())) null else getString(R.string.quick_start_name_invalid)
            },
        ) { setFragmentResult(REQUEST_KEY, Bundle().apply { putString(ARG_NICKNAME, it.trim()) }) }
    }

    companion object {
        const val REQUEST_KEY = "quick_start_name"
        const val TAG = "quick_start_name"
        private const val ARG_NICKNAME = "nickname"

        /** The nickname of a [REQUEST_KEY] result. */
        fun nickname(result: Bundle): String = requireNotNull(result.getString(ARG_NICKNAME))
    }
}
