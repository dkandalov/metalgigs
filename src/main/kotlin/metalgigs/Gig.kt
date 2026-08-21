package metalgigs

import java.time.LocalDate
import java.time.Month

data class Gig(
    val id: GigId,
    val title: GigTitle,
    val date: GigDate,
    val posterUrl: PosterUrl,
    val description: GigDescription,
)

data class GigId(val venueId: VenueId, val url: GigUrl)

// A gig is identified by where it lives, so tidying a url adds a gig to the log rather than changing
// one - which makes it a decision to take once and keep.
data class GigUrl(val value: String) {
    init {
        require(value.isNotBlank()) { "A gig url can't be blank - a gig with no url can be neither identified nor linked to" }
    }
    override fun toString() = value
}

// Refused in the type rather than reported by a check, though that costs the venue its whole listing
// for the run: whitespace out of markup is a source that has stopped parsing rather than one gig
// that came out wrong, and the gigs beside it on that listing are no better for having landed the
// right side of it. The length bound stays a check for the opposite reason - it is a measured
// threshold that a wordier promoter could cross honestly.
data class GigTitle(val value: String) {
    init {
        require(value.isNotBlank()) { "A gig title can't be blank - a source whose title selector matched nothing has stopped parsing" }
        val odd = value.filter { (it.isWhitespace() && it != ' ') || it.isISOControl() || it in invisibleCharacters }
        require(odd.isEmpty()) {
            val named = odd.toSortedSet().joinToString(", ") { "U+%04X".format(it.code) }
            "A gig title holds no whitespace but ordinary spaces, and \"$value\" carries $named. A selector " +
                "matching a card's markup rather than its heading is what puts them there, so a source has to let " +
                "them reach here rather than tidying them away"
        }
        require(value == value.trim(' ') && !value.contains("  ")) {
            "A gig title is trimmed and singly spaced - a venue that types otherwise is its own source's to normalise: \"$value\""
        }
    }
    override fun toString() = value
}

// None was in a scraped title as of the migration on 2026-08-21, and none has a place in one: a
// zero-width space, a byte order mark or a soft hyphen reaches a title only by being carried out of
// markup along with it.
private val invisibleCharacters = setOf('​', '‌', '‍', '﻿', '­')

data class GigDate(val value: LocalDate) : Comparable<GigDate> {
    val year: Int get() = value.year
    val monthValue: Int get() = value.monthValue

    constructor(year: Int, month: Month, dayOfMonth: Int) : this(LocalDate.of(year, month, dayOfMonth))
    constructor(year: Int, month: Int, dayOfMonth: Int) : this(LocalDate.of(year, month, dayOfMonth))

    override fun compareTo(other: GigDate) = value.compareTo(other.value)
    override fun toString() = value.toString()

    companion object {
        fun parse(text: String) = GigDate(LocalDate.parse(text))
    }
}

data class PosterUrl(val value: String) {
    init {
        require(value.isNotBlank()) { "A poster url can't be blank - a source with no poster for a gig must find one or fail" }
    }
    override fun toString() = value
}

data class GigDescription(val value: String) {
    override fun toString() = value
}
