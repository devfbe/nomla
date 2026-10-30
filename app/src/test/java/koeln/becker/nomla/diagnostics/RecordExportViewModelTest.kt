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

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import koeln.becker.nomla.mumble.diagnostics.DiagnosticCapture
import koeln.becker.nomla.mumble.testutil.idleMainLooper

/** The Recording page's export: one at a time, its result kept until the share sheet took it. */
@RunWith(RobolectricTestRunner::class)
class RecordExportViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val review = CaptureReview(emptyMap(), "")
    private val capture = DiagnosticCapture(
        startedAtEpochMs = 0, durationMs = 0, tracks = emptyList(), tables = emptyList(), events = emptyList(),
        pipelines = emptyList(), serverShowedRecording = true, speakersWithoutTrack = 0, droppedFrames = 0,
        route = null,
    )

    @Test
    fun `an export that throws ends in Failed, not in a crash`() {
        val exports = RecordExportViewModel(app, Dispatchers.Unconfined) { _, _, _ -> error("disk gone") }

        exports.export(capture, review)
        idleMainLooper()

        assertThat(exports.state.value).isEqualTo(ExportState.Failed)
    }

    @Test
    fun `a written zip is ready until shared`() {
        val uri = Uri.parse("content://test/zip")
        var calls = 0
        val exports = RecordExportViewModel(app, Dispatchers.Unconfined) { _, _, _ -> uri.also { calls++ } }

        exports.export(capture, review)
        idleMainLooper()
        assertThat(exports.state.value).isEqualTo(ExportState.Ready(uri))

        exports.shared()
        assertThat(exports.state.value).isEqualTo(ExportState.Idle)
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `reset drops a finished export`() {
        val exports = RecordExportViewModel(app, Dispatchers.Unconfined) { _, _, _ -> Uri.parse("content://test/zip") }
        exports.export(capture, review)
        idleMainLooper()

        exports.reset()

        assertThat(exports.state.value).isEqualTo(ExportState.Idle)
    }
}
