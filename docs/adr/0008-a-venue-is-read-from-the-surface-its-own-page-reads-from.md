# 8. A venue is read from the surface its own page reads from, and paged by the link the page follows

Accepted. Recorded 2026-08-25, describing the listing sources under `scrape/venues`.

## Context

A venue's listing page is increasingly not a listing. Several render nothing server-side and fill themselves
from JSON; several render a first page and load the rest from an endpoint the page never names; one filters
in the browser with the category on the drop-down and nothing on the cards; one is an app shell. A page that
renders 24 of 200 events reads as a complete listing, and nothing about it looks wrong.

## Decision

Where a venue's page feeds itself from something, that something is read - found by reading what the page's
own script does, and documented beside the url.

| Venue | Surface | Why not the page |
| --- | --- | --- |
| Dice | `partners-endpoint.dice.fm/api/v2/events` | 229's page renders only Dice's event-list widget; it is also the only Dice surface saying where a gig lives (ADR 5) |
| AMG | `academymusicgroup.com/api/search/events` | the venue pages are Next.js SPAs rendering nothing server-side, paginating client-side |
| OVO Arena | `/events/calendar/{year}/{month}` | the cards carry no category, so nothing in that markup selects gigs; the calendar carries the Category the drop-down filters on (Music is 3) |
| The O2 | `/events/events_ajax/{offset}` | the page renders 24 events and its "Load More" arrives disabled, enabled by the script that fetches the rest |
| DHP | `…/themes/dhp/includes/ajax/ajax_guide.php` | the listing renders three months and its own pagination urls serve those same three |
| New Cross Inn | WordPress `admin-ajax.php` | the months dropdown does not navigate - it posts and swaps the listing in place |
| The Dev | Instagram `api/v1/users/web_profile_info` | the profile page is a script that fetches this |

The credentials these need are the public ones the page ships. Dice's widget config is inline in 229's page,
naming the partner id and API key; the key is shipped to every browser, and the widget script reads
`RUNTIME_API_URL`, appends `/api/v2/events` and sends it as `x-api-key`. It is not scoped to 229 - the same
key answers for any venue in the filter, which is what lets seven venues share it. Instagram's `X-IG-App-ID`
is the web client's own, sent by every logged-out browser; without it the endpoint answers a login wall.
DHP's guide parameters are read off the listing page, which carries them however far the walk has gone.

Two mechanical traps, both quiet. `form()` sets the body but not the content type, and both WordPress
endpoints fill `$_POST` from that header alone: without it New Cross Inn answers 400 and DHP answers 200 with
months dated `data-year="0"`. And Dice's `links.next` is served under a different host from the partner
endpoint it authenticates against, so only its query string is reused - as the widget itself does.

Filters are declared the way the site declares them. Dice's filter matches the venue's name as Dice writes it,
not any id: the numeric id a dice.fm venue page carries returns an empty listing rather than an error, and so
does a name only we use ("Blondies Bar" for Dice's "Blondies"). AMG's site path is the venue id with dashes
dropped, and only that - an Academy2 event names its own room, whose slug is no page. Union Chapel's cards
carry their own sortable timestamp, which beats parsing the human date beside it.

### Paging follows the listing's own next link

Where a listing pages, the source follows the link the page follows rather than guessing at `/events/page/N/`.
`maxPages` bounds only a pathological bug; the real stop is that link disappearing. Electric Brixton, Scala,
Windmill Brixton and Islington Assembly Hall work this way, `maxPages = 10` against twelve to eighteen a page.

Each site's own way of ending is honoured. **Windmill Brixton** keeps the next link on the last page, pointing
at "#" and marked disabled, so the disabled state stops it - following it would re-fetch the same page.
**The O2** stops on an empty batch, `maxBatches = 20` bounding a bug; `per_page` is in the query the site sends
but the server ignores it, so the path offset is the only way through. **DHP** cannot stop on an empty answer -
past the end of the guide it keeps answering with empty months - so it stops on the range the page declares:
The Garage's listing ended 28 Oct 2026 where its guide ran to June 2027. **OVO Arena** costs a request per
month, so the distance is decided rather than followed; counting empty months would stop early, the listing
read on 2026-08-17 having nothing in January 2027 and three gigs in each of February and March, so eighteen
covers the year the page can show. **New Cross Inn** follows the site's own list of which months have anything
rather than counting forward, deduping the opening month before the map so no event page is fetched twice.
**Dice** already returns every listing in one request at `page[size]=200` - the largest, 229's, is 77 events -
but that is a fact about today's listings, so it still follows `links.next`. **AMG** and **Eventim Apollo**
need no paging: `PageSize=500` is above what any venue lists, and Eventim Apollo's month bar filters what is
already there.

Several sites answer without a browser-like User-Agent with a 403 - Alexandra Palace, The Underworld, dice.fm,
and enough generally that `missingGigSays` sends one too (ADR 5). Sources that come back with nothing from an
API say so: both Dice and AMG check the response held events.

## Consequences

These are undocumented surfaces that can change or close without notice. The Dice key is the sharpest version:
shared across seven venues, so scoping or rotating it stops all seven at once. Where a source reads an API it
reads what the site's own front end reads, so a field disappearing breaks the site's page too. The stop
conditions are per site, each learned from that site behaving badly, and none generalises.

## Alternatives rejected

**Rendering the SPA listing pages** - they paginate client-side over data served whole. **Guessing pagination
urls** - the site's own next link is what tells the walk when to stop. **Stopping DHP on an empty answer** - it
answers with empty months for ever. **Counting empty months at OVO** - the real listing has a gap month with
bookings either side. **Trusting Dice's page size** - a fact about today. **dice.fm's own venue pages** - they
list events without a `perm_name`. **The O2's genre filter for the arena** - its programme is far wider than
indigo's and the filter has no metal in it, only Rock, so everything is taken and left to the classifier.
