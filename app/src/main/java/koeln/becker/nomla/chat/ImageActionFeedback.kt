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

package koeln.becker.nomla.chat

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import koeln.becker.nomla.R
import java.io.IOException

/** Where a save or share of a chat picture tells the user how it went: a text, optionally with an action. */
internal fun interface ImageActionFeedback {
    fun show(@StringRes text: Int, @StringRes action: Int, onAction: () -> Unit)
}

/**
 * Saves the bytes [fetch] returns to the gallery on [io] and reports it through [feedback]: saved,
 * with a shortcut that opens the picture, or failed (fetch or save).
 */
internal suspend fun Fragment.saveChatImage(
    io: CoroutineDispatcher,
    feedback: ImageActionFeedback,
    fetch: suspend () -> ByteArray,
) {
    val context = requireContext()
    try {
        val bytes = fetch()
        val saved = withContext(io) { ImageGallerySaver(context).save(bytes) }
        feedback.show(R.string.chat_image_saved, R.string.chat_image_view) { context.viewSaved(saved) }
    } catch (_: ImageFetchException) {
        feedback.show(R.string.chat_image_save_failed, 0) {}
    } catch (_: IOException) {
        feedback.show(R.string.chat_image_save_failed, 0) {}
    }
}

/**
 * Exports the bytes [fetch] returns for [source] on [io] and offers them in a share chooser; a
 * failed fetch or export is reported through [feedback].
 */
internal suspend fun Fragment.shareChatImage(
    io: CoroutineDispatcher,
    source: String,
    feedback: ImageActionFeedback,
    fetch: suspend () -> ByteArray,
) {
    val context = requireContext()
    try {
        val bytes = fetch()
        val exported = withContext(io) { ImageShareExporter(context).export(source, bytes) }
        startActivity(shareChooser(context, exported))
    } catch (_: ImageFetchException) {
        feedback.show(R.string.chat_image_load_failed, 0) {}
    } catch (_: IOException) {
        feedback.show(R.string.chat_image_load_failed, 0) {}
    }
}
