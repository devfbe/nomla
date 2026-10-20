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

package se.lublin.mumla.chat

import androidx.exifinterface.media.ExifInterface
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/** Generates real PNG bytes on the JVM so decoding tests never depend on checked-in fixtures. */
object TestImages {
    fun png(width: Int, height: Int, rgb: Int = 0x336699): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(rgb)
        g.fillRect(0, 0, width, height)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    /** Real JPEG bytes: the only format whose EXIF orientation the platform decoder acts on. */
    fun jpeg(width: Int, height: Int, rgb: Int = 0x336699): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(rgb)
        g.fillRect(0, 0, width, height)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", out)
        return out.toByteArray()
    }

    /**
     * Rewrites [image] with `Orientation` set to [orientation], one of `ExifInterface`'s eight
     * `ORIENTATION_*` values. Goes through a temporary file because `ExifInterface.saveAttributes`
     * needs a seekable target; [suffix] picks the container (`.jpg` writes an APP1 segment, `.png`
     * an `eXIf` chunk).
     */
    fun withExifOrientation(image: ByteArray, orientation: Int, suffix: String = ".jpg"): ByteArray {
        val file = File.createTempFile("mumla-test-image", suffix)
        try {
            file.writeBytes(image)
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    fun dataUri(png: ByteArray): String = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
}
