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

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/**
 * Turns a prepared bitmap into the `<img src="data:image/jpeg;base64,PAYLOAD"/>` element Mumble
 * clients exchange, at the highest JPEG quality whose message still fits the server's limit.
 *
 * The payload is percent-encoded, which [ImageSource.parse] undoes on the receiving side.
 */
object OutgoingImageEncoder {
    const val START_QUALITY = 97
    const val QUALITY_STEP = 10

    private const val PREFIX = "<img src=\"data:image/jpeg;base64,"
    private const val SUFFIX = "\"/>"

    /**
     * JPEG-compresses [bitmap], lowering the quality until [imageHtml] of the result fits
     * [maxMessageLength]. Returns null when even the lowest rung does not fit, or when the JPEG
     * encoder refuses this bitmap at every rung.
     *
     * [maxMessageLength] is the server's `ImageMessageLength`; zero (and anything below it) means
     * the server declared no limit.
     */
    fun compressToFit(bitmap: Bitmap, maxMessageLength: Int): ByteArray? =
        fit(bitmap, maxMessageLength)?.first

    /**
     * The wire form. This string, not the JPEG or its base64, is what Murmur measures against
     * `imagemessagelength` (UTF-16 length of the whole message); percent-encoding adds several
     * percent on top of the base64.
     */
    fun imageHtml(jpeg: ByteArray): String =
        PREFIX + URLEncoder.encode(Base64.encodeToString(jpeg, Base64.NO_WRAP), "UTF-8") + SUFFIX

    /** [compressToFit] + [imageHtml]; null when the image cannot be made to fit. */
    fun encode(bitmap: Bitmap, maxMessageLength: Int): String? =
        fit(bitmap, maxMessageLength)?.second

    /**
     * The quality ladder shared by both entry points. The message is built at every rung rather than
     * estimated, so what is measured is what is sent; that costs up to ten encodes of an already
     * clamped bitmap, on a background dispatcher.
     */
    private fun fit(bitmap: Bitmap, maxMessageLength: Int): Pair<ByteArray, String>? {
        var quality = START_QUALITY
        while (quality > 0) {
            val stream = ByteArrayOutputStream()
            // `false` means nothing was written (e.g. ALPHA_8); never send an empty payload.
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                val jpeg = stream.toByteArray()
                val html = imageHtml(jpeg)
                if (maxMessageLength <= 0 || html.length <= maxMessageLength) return jpeg to html
            }
            quality -= QUALITY_STEP
        }
        return null
    }
}
