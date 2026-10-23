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

package koeln.becker.nomla.util

import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DisplayNameTest {
    private val uri = Uri.parse("content://provider.example/doc/1")
    private val resolver = mockk<ContentResolver>()

    private fun answer(vararg names: String?): MatrixCursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { names.forEach { addRow(arrayOf(it)) } }

    @Test
    fun theFirstRowsNameIsTheDisplayNameAndTheCursorIsClosed() {
        val cursor = answer("alice.p12")
        every { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) } returns cursor

        assertThat(resolver.displayName(uri)).isEqualTo("alice.p12")
        assertThat(cursor.isClosed).isTrue()
    }

    @Test
    fun noCursorNoRowOrANullNameIsNoName() {
        every { resolver.query(uri, any(), null, null, null) } returns null
        assertThat(resolver.displayName(uri)).isNull()

        every { resolver.query(uri, any(), null, null, null) } returns answer()
        assertThat(resolver.displayName(uri)).isNull()

        every { resolver.query(uri, any(), null, null, null) } returns answer(null)
        assertThat(resolver.displayName(uri)).isNull()
    }

    @Test
    fun aProviderThatRefusesTheQueryGivesNoName() {
        every { resolver.query(uri, any(), null, null, null) } throws SecurityException("no grant")
        assertThat(resolver.displayName(uri)).isNull()

        every { resolver.query(uri, any(), null, null, null) } throws IllegalArgumentException("no such column")
        assertThat(resolver.displayName(uri)).isNull()
    }
}
