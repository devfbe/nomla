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

import android.content.Context
import android.content.Intent
import com.google.common.truth.Truth.assertThat
import koeln.becker.nomla.chat.ImageShareExporter
import koeln.becker.nomla.mumble.diagnostics.CapturedTrack
import koeln.becker.nomla.mumble.diagnostics.DiagnosticCapture
import koeln.becker.nomla.testing.FileProviderCache
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
class DiagnosticShareTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private val dir: File get() = File(context.cacheDir, DiagnosticShare.DIRECTORY)

    private val review = CaptureReview(emptyMap(), "")

    @Before
    fun emptyTheShareDirectory() {
        FileProviderCache.clear()
        dir.deleteRecursively()
    }

    @Test
    fun `export clears the directory and returns a provider uri for the one file`() {
        val first = DiagnosticShare.export(context, capture(FIRST_START_MS), review)
        val second = DiagnosticShare.export(context, capture(SECOND_START_MS), review)

        assertThat(dir.list()!!.toList()).containsExactly("nomla-diagnose-20261030-123456.zip")
        assertThat(first.lastPathSegment).isEqualTo("nomla-diagnose-20261030-123400.zip")
        assertThat(second.scheme).isEqualTo("content")
        assertThat(second.authority).isEqualTo(context.packageName + ImageShareExporter.AUTHORITY_SUFFIX)
        assertThat(second.toString()).endsWith("/shared_diagnostics/nomla-diagnose-20261030-123456.zip")
        assertThat(File(dir, "nomla-diagnose-20261030-123456.zip").length()).isGreaterThan(0L)
    }

    @Test
    fun `share intent grants read on exactly that uri`() {
        val uri = DiagnosticShare.export(context, capture(SECOND_START_MS), review)

        val intent = DiagnosticShare.shareIntent(uri, "Nomla diagnostics")

        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(intent.type).isEqualTo("application/zip")
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0)
        @Suppress("DEPRECATION") // The typed overload needs API 33; minSdk is 31.
        assertThat(intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)).isEqualTo(uri)
        assertThat(intent.clipData!!.itemCount).isEqualTo(1)
        assertThat(intent.clipData!!.getItemAt(0).uri).isEqualTo(uri)
        assertThat(intent.component).isNull()
    }

    @Test
    fun `clear removes leftovers`() {
        dir.mkdirs()
        File(dir, "old.zip").writeText("x")
        File(dir, "partial.tmp").writeText("y")

        DiagnosticShare.clear(context)

        assertThat(dir.list().orEmpty().toList()).isEmpty()
    }

    /**
     * A double tap exports twice at once: export and clear are serialized, so exactly one whole ZIP
     * is left, the one of the export that ran last, and its URI is one of those returned.
     */
    @Test
    fun `two concurrent exports leave one whole zip`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(ROUNDS) {
                dir.deleteRecursively()
                val go = CountDownLatch(1)
                val exports = listOf(FIRST_START_MS, SECOND_START_MS).map { start ->
                    pool.submit<android.net.Uri> {
                        go.await()
                        DiagnosticShare.export(context, capture(start), review)
                    }
                }
                go.countDown()
                val uris = exports.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }

                val left = dir.listFiles()!!.toList()
                assertThat(left).hasSize(1)
                assertThat(uris.map { it.lastPathSegment }).contains(left.single().name)
                ZipFile(left.single()).use { zip -> assertThat(zip.getEntry("meta.json")).isNotNull() }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * The clear at process start removes what earlier processes left, never a ZIP an export of
     * this process already returned, however late the clear runs. It reads no clock: a leftover
     * that looks newer than the export (a clock set back) goes as well.
     */
    @Test
    fun `the clear at process start spares an export already made`() {
        val uri = DiagnosticShare.export(context, capture(SECOND_START_MS), review)
        val stale = File(dir, "from-an-earlier-process.zip").apply { writeText("x") }
        assertThat(stale.setLastModified(System.currentTimeMillis() + LATER_MS)).isTrue()

        DiagnosticShare.clearAtStart(context)

        assertThat(dir.list()!!.toList()).containsExactly(uri.lastPathSegment)
    }

    /** Without an export of this process, the clear at process start empties the directory. */
    @Test
    fun `the clear at process start removes every leftover`() {
        DiagnosticShare.clear(context)
        dir.mkdirs()
        val future = File(dir, "nomla-diagnose-20991231-235959.zip").apply { writeText("x") }
        assertThat(future.setLastModified(System.currentTimeMillis() + LATER_MS)).isTrue()
        File(dir, "old.zip").writeText("y")

        DiagnosticShare.clearAtStart(context)

        assertThat(dir.list().orEmpty().toList()).isEmpty()
    }

    private fun capture(startedAtMs: Long) = DiagnosticCapture(
        startedAtEpochMs = startedAtMs,
        durationMs = DURATION_MS,
        tracks = listOf(CapturedTrack("7_mix", RATE, ShortArray(SAMPLES), false)),
        tables = emptyList(),
        events = emptyList(),
        pipelines = emptyList(),
        serverShowedRecording = false,
        speakersWithoutTrack = 0,
        droppedFrames = 0,
        route = null,
    )

    private companion object {
        const val DURATION_MS = 10L
        const val RATE = 48_000
        const val SAMPLES = 480
        const val ROUNDS = 20
        const val TIMEOUT_SECONDS = 30L
        const val LATER_MS = 3_600_000L

        // 2026-10-30T12:34:00Z and 12:34:56Z
        const val FIRST_START_MS = 1_793_363_640_000L
        const val SECOND_START_MS = 1_793_363_696_000L
    }
}
