---
name: comment-style
description: Decide whether a code comment should exist at all in this project, and how to word it. Use before writing any comment, when a rename makes one stale, and when reviewing comments - the default answer is not to write one.
---

The default is no comment. Comments are unversioned, untested prose that rots while the code around
them keeps working, so prefer deleting a weak one over rewording it.

A comment must pass both tests:

- **True.** Verify against the code first, and never assert a rationale you can't substantiate.
- **Not derivable.** If the declaration, the adjacent code, or a well-known pattern already says it, cut it.

Cut on sight: change history, renames, rejected alternatives ("checked here rather than on GigEvent"),
restatements of the declaration, when and by which caller a field gets populated, and explanations of
patterns like event sourcing.

Keep what reading the code can't recover: venue and site behaviour (inconsistent scraped date formats,
posters arriving as a css `background-image`, sites refusing non-browser User-Agents), measurements (why
`listedDetails` excludes `description`), library traps (a plain `GigEvent::description` reference
silently resolves to Kondor's required-field overload), and distinctions between similar things
(`logicalDate` versus `recordedAt`).

Wording: sentence capitalisation, ending in a full stop. A wrapped continuation line is not a sentence
start. An identifier keeps its own case when it leads - `// logicalDate is the date...`.

Apply opportunistically to comments you're already editing; don't sweep the tree. That most existing
comments predate this is not an argument for keeping them.
