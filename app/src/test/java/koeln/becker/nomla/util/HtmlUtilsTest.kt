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

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric for the escaping, which is the platform's TextUtils.htmlEncode. */
@RunWith(RobolectricTestRunner::class)
class HtmlUtilsTest {
    @Test
    fun theHostnameOfALinkAndNoneOfAnythingElse() {
        mapOf(
            "https://example.org/path?q=1" to "example.org",
            "not a link" to null,
            "http://exa mple.org/" to null,
        ).forEach { (link, host) -> assertWithMessage(link).that(HtmlUtils.getHostnameFromLink(link)).isEqualTo(host) }
    }

    /** Escapes and UTF-8 sequences are decoded; `+`, dangling percents and non-ASCII literals stay. */
    @Test
    fun percentDecoding() {
        mapOf(
            "a%2Fb%3D+c" to "a/b=+c",
            "%C3%A4" to "ä",
            "100%" to "100%",
            "%zz" to "%zz",
            "😀%20x" to "😀 x",
        ).forEach { (encoded, decoded) ->
            assertWithMessage(encoded).that(HtmlUtils.percentDecode(encoded)).isEqualTo(decoded)
        }
    }

    @Test
    fun outgoingMarkupWrapsLinksAndConvertsNewlines() {
        mapOf(
            "see https://a.org/x\nnow" to "see <a href=\"https://a.org/x\">https://a.org/x</a><br>now",
            "hello" to "hello",
            "" to "",
            "one\ntwo\nthree" to "one<br>two<br>three",
        ).forEach { (text, html) ->
            assertWithMessage(text).that(HtmlUtils.markupOutgoingMessage(text)).isEqualTo(html)
        }
    }

    @Test
    fun outgoingMarkupEscapesText() {
        mapOf(
            "<b>a</b> & \"q\"" to "&lt;b&gt;a&lt;/b&gt; &amp; &quot;q&quot;",
            "a<https://a.org/\"x>" to "a&lt;<a href=\"https://a.org/\">https://a.org/</a>&quot;x&gt;",
        ).forEach { (text, html) ->
            assertWithMessage(text).that(HtmlUtils.markupOutgoingMessage(text)).isEqualTo(html)
        }
    }

    @Test
    fun outgoingMarkupKeepsPunctuationAndUnbalancedParenthesesOutOfLinks() {
        val a = "<a href=\"https://a.org/x\">https://a.org/x</a>"
        val wiki = "https://de.wikipedia.org/wiki/Foo_(Bar)"
        val wikiLink = "<a href=\"$wiki\">$wiki</a>"
        mapOf(
            "see https://a.org/x." to "see $a.",
            "https://a.org/x, then" to "$a, then",
            "look https://a.org/x!" to "look $a!",
            "(https://a.org/x)" to "($a)",
            wiki to wikiLink,
            "($wiki)." to "($wikiLink).",
        ).forEach { (text, html) ->
            assertWithMessage(text).that(HtmlUtils.markupOutgoingMessage(text)).isEqualTo(html)
        }
    }

    @Test
    fun outgoingMarkupEscapesAmpersandsInLinks() {
        val encoded = "https://a.org/s?a=1&amp;b=2"
        assertWithMessage("query")
            .that(HtmlUtils.markupOutgoingMessage("https://a.org/s?a=1&b=2 ok"))
            .isEqualTo("<a href=\"$encoded\">$encoded</a> ok")
    }
}
