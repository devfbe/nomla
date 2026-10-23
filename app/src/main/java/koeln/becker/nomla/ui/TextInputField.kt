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

package koeln.becker.nomla.ui

import android.content.Context
import android.content.DialogInterface
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import koeln.becker.nomla.databinding.DialogTextInputBinding

/**
 * A dialog's one-line text field, labelled [hint] (the label a screen reader announces) and typed
 * [inputType]. An error shown under it goes away with the next edit.
 */
class TextInputField(context: Context, hint: CharSequence, inputType: Int = InputType.TYPE_CLASS_TEXT) {
    private val binding = DialogTextInputBinding.inflate(LayoutInflater.from(context))

    /** The field with its padding, for a dialog's `setView`. */
    val view: View get() = binding.root

    val editText: TextInputEditText get() = binding.textInput

    val text: String get() = binding.textInput.text.toString()

    var error: CharSequence?
        get() = binding.textInputLayout.error
        set(value) {
            binding.textInputLayout.error = value
        }

    init {
        binding.textInputLayout.hint = hint
        binding.textInput.inputType = inputType
        binding.textInput.doAfterTextChanged { error = null }
    }
}

/**
 * A dialog that asks for one line of text: [title], [field], Cancel and [confirmLabel]. Confirming
 * hands the field's text to [onConfirm] and closes the dialog, unless [validate] returns an error,
 * which is shown under the field while the dialog stays open. With [imeAction] the keyboard's
 * action key confirms as the button does. [onCancel] runs on Cancel and on Back or a tap outside;
 * a DialogFragment's dialog leaves it out (the fragment takes the cancel listener for itself).
 * The dialog is created, not shown.
 */
@Suppress("LongParameterList") // What the text dialogs differ in, all but the essentials defaulted.
fun Context.textInputDialog(
    @StringRes title: Int,
    field: TextInputField,
    @StringRes confirmLabel: Int,
    imeAction: Int? = null,
    validate: (String) -> CharSequence? = { null },
    onCancel: (() -> Unit)? = null,
    onConfirm: (String) -> Unit,
): AlertDialog {
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(field.view)
        .setPositiveButton(confirmLabel, null)
        .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel?.invoke() }
        .apply { if (onCancel != null) setOnCancelListener { onCancel() } }
        .create()

    fun confirm() {
        val error = validate(field.text)
        field.error = error
        if (error != null) return
        onConfirm(field.text)
        dialog.dismiss()
    }

    if (imeAction != null) {
        field.editText.imeOptions = imeAction
        field.editText.setOnEditorActionListener { _, actionId, _ ->
            (actionId == imeAction).also { if (it) confirm() }
        }
    }
    // The button exists once the dialog is shown; its own listener would close on invalid text.
    dialog.setOnShowListener {
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { confirm() }
    }
    return dialog
}
