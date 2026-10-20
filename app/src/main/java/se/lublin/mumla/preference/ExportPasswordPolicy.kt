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

package se.lublin.mumla.preference

/** What the export's password dialog does with what was typed. */
enum class ExportPasswordDecision {
    /** Both fields empty: ask once more, the file will not be protected. */
    CONFIRM_UNPROTECTED,

    /** The confirmation differs from the password. */
    MISMATCH,

    /** Export under the password. */
    EXPORT,
}

/** The decisions behind `CertificateExportActivity`'s password dialog, testable without an Activity. */
object ExportPasswordPolicy {
    fun decide(password: CharSequence, confirmation: CharSequence): ExportPasswordDecision = when {
        password.isEmpty() && confirmation.isEmpty() -> ExportPasswordDecision.CONFIRM_UNPROTECTED
        password.toString() != confirmation.toString() -> ExportPasswordDecision.MISMATCH
        else -> ExportPasswordDecision.EXPORT
    }
}
