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
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputFilter
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.databinding.DialogQuickStartNameBinding

/**
 * Asks for the nickname "Just talk" joins with, prefilled with the default username. A valid name
 * is delivered as a fragment result under [REQUEST_KEY], to the fragment manager showing the
 * dialog; read it with [nickname].
 */
class QuickStartNameFragment : DialogFragment() {

    private lateinit var binding: DialogQuickStartNameBinding

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogQuickStartNameBinding.inflate(layoutInflater)
        val field = binding.quickStartName
        field.filters = arrayOf(InputFilter.LengthFilter(QuickStart.MAX_NICKNAME_LENGTH))
        // A recreated dialog gets the text it had back.
        if (savedInstanceState == null) {
            field.setText(Settings.getInstance(requireContext()).defaultUsername)
            field.selectAll()
        }
        field.doAfterTextChanged { binding.quickStartNameLayout.error = null }
        field.setOnEditorActionListener { _, actionId, _ ->
            (actionId == EditorInfo.IME_ACTION_GO).also { if (it) confirm() }
        }
        return MaterialAlertDialogBuilder(requireActivity())
            .setTitle(R.string.quick_start_name_title)
            .setView(binding.root)
            .setPositiveButton(R.string.quick_start_go, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        // Replaces the button's listener so that an invalid name does not dismiss.
        (requireDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { confirm() }
    }

    private fun confirm() {
        val name = binding.quickStartName.text.toString().trim()
        if (!QuickStart.isValidNickname(name)) {
            binding.quickStartNameLayout.error = getString(R.string.quick_start_name_invalid)
            return
        }
        setFragmentResult(REQUEST_KEY, Bundle().apply { putString(ARG_NICKNAME, name) })
        dismiss()
    }

    companion object {
        const val REQUEST_KEY = "quick_start_name"
        const val TAG = "quick_start_name"
        private const val ARG_NICKNAME = "nickname"

        /** The nickname of a [REQUEST_KEY] result. */
        fun nickname(result: Bundle): String = requireNotNull(result.getString(ARG_NICKNAME))
    }
}
