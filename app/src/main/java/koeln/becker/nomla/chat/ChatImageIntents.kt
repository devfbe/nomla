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

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import koeln.becker.nomla.R

/** A chooser that offers [exported] to other apps with a read grant. */
internal fun shareChooser(context: Context, exported: ImageShareExporter.Exported): Intent {
    // ClipData, not only EXTRA_STREAM: createChooser migrates the read grant to the
    // chooser only for the intent's data or ClipData.
    val send = Intent(Intent.ACTION_SEND)
        .setType(exported.mimeType)
        .putExtra(Intent.EXTRA_STREAM, exported.uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, exported.uri)
    return Intent.createChooser(send, context.getString(R.string.chat_image_share))
}

/** Opens [saved] in the user's gallery or viewer: the target is an external app, so implicit is correct. */
internal fun viewIntent(saved: ImageGallerySaver.Saved): Intent =
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(saved.uri, saved.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

/** Starts [viewIntent]; call on an activity context, so no NEW_TASK is needed. */
internal fun Context.viewSaved(saved: ImageGallerySaver.Saved) {
    try {
        startActivity(viewIntent(saved))
    } catch (_: ActivityNotFoundException) {
        // The image is saved; only the shortcut to it is unavailable.
    }
}
