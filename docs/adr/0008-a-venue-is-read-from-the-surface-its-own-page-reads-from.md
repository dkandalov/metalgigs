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
| The Dev | Instagram's profile page itself | the page prefetches its own first screen of posts into a script beside the shell |
| Bush Hall | `bushhall.seetickets.com/search/all` | the venue's own What's On page is one iframe pointed at that listing and nothing else |

The credentials these need are the public ones the page ships. Dice's widget config is inline in 229's page,
naming the partner id and API key; the key is shipped to every browser, and the widget script reads
`RUNTIME_API_URL`, appends `/api/v2/events` and sends it as `x-api-key`. It is not scoped to 229 - the same
key answers for any venue in the filter, which is what lets seven venues share it. DHP's guide parameters are
read off the listing page, which carries them however far the walk has gone.

Instagram is the exception, having closed on 2026-09-02: `api/v1/users/web_profile_info`, which the page's
own script called, now answers 401 `require_login` to every logged-out client - curl, Chrome and Firefox
alike - whether or not the app id is sent. A logged-in session is recognised, but is throttled to a few reads
of one profile an hour, expires, belongs to an account, and has to live in a file on disk.

So The Dev is read from the page rather than from the call the page makes, which is the same rule the rest of
this table follows and simply took a second reading to apply. Instagram renders the profile as a shell and
prefetches its first screen of posts into a `data-sjs` script beside it - but only for a request shaped like a
browser opening the page. Without the `Sec-Fetch-Dest: document` / `Sec-Fetch-Mode: navigate` set the same url
returns the shell alone, 100KB lighter, which is why the page looked empty to every earlier reading of it, and
why a `fetch()` run from the page itself finds nothing either: it sends those headers saying cors. Nothing
authenticates this, so the throttle, the expiry and the credential all go with it.

Two costs. The payload sits inside Meta's own bootstrap envelope, so the timeline is cut out by balancing
braces from `xig_user_by_username` rather than by modelling an envelope that is about the page's own start-up.
And the picture is served at 480x640 where the endpoint gave 1080: the url is signed, so neither a larger
`stp` crop nor dropping it answers anything but 403, and the post's own `og:image` is a square crop that loses
the bottom of the flyer. The model reads the month off 480x640 without trouble, which is the only test that
matters, but it is less to read from than the flyer had before.

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
already there. **Bush Hall** needs none either and is checked rather than walked: See Tickets serves the whole
listing in one page and says so in its own pagination ("1 of 1"), while offering nothing to page with - the
sort control posts, and `page` and `pageSize` in the query string are ignored, answering the same 56 gigs
whatever they say. A second page cannot be fetched today to fit a walk to, so the source fails on the day that
text stops saying one page rather than silently reading the listing short.

Several sites answer without a browser-like User-Agent with a 403 - Alexandra Palace, The Underworld, dice.fm,
and enough generally that `missingGigSays` sends one too (ADR 5). See Tickets wants more than the one header:
it answers an "Unusual Traffic Detected" page under a 403, and scores that on how complete the header set is
rather than on any single header - the User-Agent alone is refused, as is the User-Agent with any two of
Accept-Language, the four `Sec-Fetch-*` and the three `sec-ch-ua*`, while any three of those groups are let
through. So Bush Hall sends the set Chrome sends on a navigation, kept whole rather than trimmed to whichever
subset passes today, which is the same reading The Dev's `Sec-Fetch-*` headers came from: a request shaped
like a browser opening the page, and nothing authenticated. Sources that come back with nothing from an
API say so: both Dice and AMG check the response held events.

## Consequences

These are undocumented surfaces that can change or close without notice, and Instagram is the first to have
done it: a surface that was public in August was gone in September. What that cost was a reading rather than a
venue - the page was still serving the listing the whole time, to anyone whose request looked like a browser
opening it. The Dice key is the sharpest version still outstanding: shared across seven venues, so scoping or
rotating it stops all seven at once. The Dev's new reading is fragile in its own way: it rests on Instagram
prefetching the timeline into the page at all, which is an optimisation rather than a promise. Where a source
reads an API it reads what the site's own front end reads, so a field disappearing breaks the site's page too.
The stop
conditions are per site, each learned from that site behaving badly, and none generalises.

## Alternatives rejected

**Rendering the SPA listing pages** - they paginate client-side over data served whole. **Guessing pagination
urls** - the site's own next link is what tells the walk when to stop. **Stopping DHP on an empty answer** - it
answers with empty months for ever. **Counting empty months at OVO** - the real listing has a gap month with
bookings either side. **Trusting Dice's page size** - a fact about today. **dice.fm's own venue pages** - they
list events without a `perm_name`. **A logged-in Instagram session** - it works, and was built and then taken out again: recognised but throttled to a few profile reads an hour, expiring on its own schedule, tied to an account whose terms it breaks, and a credential in a file the daily job reads. All of it to fetch a page that answers unauthenticated. **Warming a cookie jar first** - visiting `instagram.com/` before the profile is answered with a 302 setting Instagram's own `sessionid`, which overwrites the seeded one in a name-keyed store, so the walk logs itself out. **The O2's genre filter for the arena** - its programme is far wider than
indigo's and the filter has no metal in it, only Rock, so everything is taken and left to the classifier.
