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

package koeln.becker.nomla.diagnostics

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.widget.CheckBox
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings

/**
 * The notice before a diagnostic recording, in the owner's approved wording: the recording holds
 * the others' voices, everyone on the server sees it run, and a server that forbids it stops it.
 * "Don't show again" is remembered only when the user goes on to record.
 *
 * The answer goes as a fragment result under [REQUEST_KEY] to the fragment manager that shows the
 * dialog; read it with [start]. Dismissing it without a button answers nothing, which also starts
 * nothing. The server's recording flag is set by every recording, whether this dialog showed or not.
 */
class RecordingConsentDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // A dialog's view has no parent to inflate into.
        @SuppressLint("InflateParams")
        val view = requireActivity().layoutInflater.inflate(R.layout.dialog_recording_consent, null)
        val dontShow = view.findViewById<CheckBox>(R.id.consent_dont_show)
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.debug_consent_title)
            .setView(view)
            .setNegativeButton(R.string.debug_record_cancel) { _, _ -> answer(false) }
            .setPositiveButton(R.string.debug_record_start) { _, _ ->
                if (dontShow.isChecked) Settings.getInstance(requireContext()).isRecordingNoticeHidden = true
                answer(true)
            }
            .create()
    }

    private fun answer(start: Boolean) = setFragmentResult(REQUEST_KEY, Bundle().apply { putBoolean(ARG_START, start) })

    companion object {
        const val REQUEST_KEY = "recording_consent"
        const val TAG = "recording_consent"
        private const val ARG_START = "start"

        /** Whether the user chose to record, from a [REQUEST_KEY] result. */
        fun start(result: Bundle): Boolean = result.getBoolean(ARG_START)
    }
}
