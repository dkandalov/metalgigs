# 16. The page is one static file, and what script adds to it keeps no state the browser already holds

Accepted. Recorded 2026-11-17, describing the scripts in `gigs.hbs` and the rules of its stylesheet that stand in
for script, from the comments that had grown on them.

## Context

The published page is one html file, rendered once a day and served as it stands. It reads as a listing with
no script at all. Three things have been added to it since - a search, an about panel, and dropping a day that
has passed - and a visitor leaves it for a gig's own page and comes back. Each of
the three could be script holding state of its own, and each time that state had something to fall out of step
with: what was typed, what the browser restored on the way back, what was already hidden.

## Decision

**The search box is built by script rather than sitting in the template**, so it only ever appears when there
is script to run it. The page is otherwise entirely static, and a box that silently does nothing is worse than
no box. It is put in front of the about button at runtime.

**What shows is decided by the field's own state, in the stylesheet.** The clear button is shown by the field
holding a query rather than by script, so there is nothing to keep in step with what was typed. On a phone the
field is collapsed to a button (ADR 17) and the field itself is what grows on focus - nothing toggles it open,
so the expansion needs no script and has no state to keep in sync. It stays open while it holds a query, or
blurring would hide what was typed.

**The about panel is a popover.** `popovertarget` is the whole mechanism: the browser shows and hides the panel,
centres it, puts it above everything, and closes it on Escape or a click outside, with no script. The
stylesheet only has to say what it looks like.

**The search field is deliberately not `autocomplete=off`.** iOS empties a field marked that way when it
restores a page from the back/forward cache, while keeping the rest of the DOM as it was - so coming back from a
gig left the listing filtered by a query no longer on screen, with nothing to explain it and nothing to clear.
Measured on iPhone Chrome: `pageshow` persisted, scripts not re-run, script-set attributes intact, and only the
fields left without that attribute still holding what had been typed.

**The query is applied again on `pageshow`.** A desktop rebuilds the page on the way back rather than restoring
it whole, so the field gets its value back but the filtering that went with it does not - the value is assigned
straight onto the field, without the `input` event the search listens for. Filtering again from whatever the
field ends up holding covers that, and costs nothing where the page came back already filtered.

**A day that has passed is removed by script, by London's date.** The render drops gigs before its own today, so
a day only turns up once the published page has outlived the render that produced it - a missed daily run, a tab
left open overnight, a cached copy. It is removed rather than hidden, because the search sets `.hidden` on every
group from whether it still holds a matching card, which would bring a hidden one back. London rather than the
visitor's own zone: these are London gigs, and a visitor further east would otherwise lose tonight's while it is
still to come.

## Consequences

With script off there is no search, and a passed day stays until the next render; the listing is all there.

The search's behaviour is in two places. What the field looks like at any moment is in the stylesheet, keyed on
`:focus` and `:placeholder-shown`, and the script only filters; a change to one has to be read against the
other, and the label for this decision stands on the script alone.

The back/forward measurement is from iPhone Chrome alone. Nothing checks it, so a
browser changing what it restores shows up as a listing filtered by nothing on screen, or unfiltered under a
query.

## Alternatives rejected

**The search box in the template** - it does nothing without script. **Script opening the field and showing the
clear button** - state to keep in step with what was typed. **`autocomplete=off`** - iOS empties the field on
the way back and leaves the listing filtered. **Hiding a passed day** - the search un-hides it. **The visitor's
own date** - a visitor east of London loses tonight's gigs.
