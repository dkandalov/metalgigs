package metalgigs

import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.isEqualTo
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GigTitleTest {

    @Test
    fun `refuses a blank title`() {
        assertFailsWith<IllegalArgumentException> { GigTitle("   ") }
    }

    // what a selector matching a card's container rather than its heading brings with it, and the
    // one shape no source is allowed to normalise away
    @Test
    fun `refuses whitespace that only markup puts in a title, naming what it found`() {
        val error = assertFailsWith<IllegalArgumentException> { GigTitle("Doom Night\n7pm") }

        expectThat(error.message!!).contains("U+000A")
    }

    // both appear in real listings - 229's promoters type U+00A0, Alexandra Palace's headings carry
    // U+202F - so their sources normalise them before this ever sees them
    @Test
    fun `refuses a non-breaking space`() {
        assertFailsWith<IllegalArgumentException> { GigTitle("Sholto plays David Axelrod ") }
        assertFailsWith<IllegalArgumentException> { GigTitle("Upside Down London ") }
    }

    @Test
    fun `refuses a zero-width space, a byte order mark and a soft hyphen`() {
        assertFailsWith<IllegalArgumentException> { GigTitle("Doom​Night") }
        assertFailsWith<IllegalArgumentException> { GigTitle("﻿Doom Night") }
        assertFailsWith<IllegalArgumentException> { GigTitle("Doom­Night") }
    }

    @Test
    fun `refuses a title that isn't trimmed, showing the title so the space can be seen`() {
        val error = assertFailsWith<IllegalArgumentException> { GigTitle("Ritual King ") }

        expectThat(error.message!!).contains("\"Ritual King \"")
    }

    @Test
    fun `refuses a run of two or more spaces`() {
        assertFailsWith<IllegalArgumentException> { GigTitle("Moving Pictures  (A Tribute to the music of Rush)") }
    }

    // two characters is a real gig title, and the punctuation venues use is none of this type's
    // business - only whitespace is
    @Test
    fun `takes an ordinary title however short or oddly punctuated`() {
        expectThat(GigTitle("LP").value).isEqualTo("LP")
        expectThat(GigTitle("UK FRESH '26' – 40th Anniversary").value).isEqualTo("UK FRESH '26' – 40th Anniversary")
        expectThat(GigTitle("Parish + Mägick Ritüal").value).isEqualTo("Parish + Mägick Ritüal")
    }
}
