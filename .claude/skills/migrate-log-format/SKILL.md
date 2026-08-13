---
name: migrate-log-format
description: Change an on-disk key in events.ndjson - rename a field, drop one, or change how it is written - using expand-contract, with a temporary test as both the migration and its verification. Use when renaming or removing a Gig/GigClassified/GigsRendered field whose Kondor converter key is already present in the log, or when any change to JGig/JGigClassified/JGigsRendered would stop existing log lines reading back identically.
---

`events.ndjson` is an append-only log of every observation, classification and render this project has
ever made, and its JSON keys are a contract with past-you: nothing rewrites old lines, so a converter
change that stops an old line reading back the way it did is silent data loss. This is the sequence for
changing a key safely, and the checks that actually catch a mistake.

## First: decide whether the key has to change at all

Renaming a Kotlin property is *not* this task. All the code is one Gradle module, so an IDE rename is
atomic and the compiler proves it complete. Only the on-disk key needs the ceremony below.

The two are independent, and they already differ deliberately: `JGig` and `JGigClassified` flatten
`GigId` to `venue`/`url` on disk rather than nesting it. If the model name is what bothers you, rename
the property and leave the key alone. Do the migration when the *stored* name is what's wrong, or when
a field's shape changes.

## The sequence

Each step is a state the app can actually run in, so each can be its own commit:

1. **Reader accepts both.** Add the new key's delegate alongside the old; read new, fall back to old.
2. **Writer emits the new key.** Reader still accepts both, so the log is legitimately mixed here.
3. **Rewrite the log** through the new-format writer into a second file.
4. **Verify** the rewritten file loads to exactly the same entries.
5. **Swap** the new file over `events.ndjson`.
6. **Contract**: delete the old key's delegate and the fallback.

Order matters at the end: contracting before the swap leaves the app unable to read its own log.
Either swap first, or land 5 and 6 in one commit so no runnable state is broken.

Between 3 and 5, don't let the daily job append — a scrape lands in the *old* file and is lost at swap
time. There is one writer, so just don't run it; a catch-up merge isn't worth building.

## Kondor mechanics you need for steps 1-2

Verified in `/Users/dk/Projects/_ref/kondor-json` (see the `http4k-reference` skill), not from memory:

- A nullable binder gives `JFieldMaybe` → `JsonPropOptional`, whose `appender` returns `propName to
  null` for a null value, and `JsonStyle.appendObjectFields` filters those out. `toNdJson` uses the
  default `jsonStyle = JsonStyle.singleLine`, which has `explicitNulls = false`. **So a delegate whose
  binder returns null writes nothing at all** — not `"key": null`. That is what makes a read-only
  legacy key possible.
- `singleLine` also has `sortedObjectFields = false`, so keys are written in delegate declaration
  order. Declare the new key where the old one was and the migrated lines differ in nothing else.
- `JsonPropOptional.getter` returns null for an absent key, so `+key` is null when missing.

Expand (step 1) — write the old key, accept either:

```kotlin
private val pageText by str(fun Gig.(): String? = description)
private val description by str(fun Gig.(): String? = null)

description = +description ?: +pageText ?: "",
```

Step 2 is swapping which binder returns the value and which returns `null`. Keep the lambda form on
both: a plain `Gig::description` reference resolves to the *required*-field overload instead.

## Steps 3-4: the migration is a temporary test

Write it under `src/test/kotlin/`, run it with the `run-tests` skill, delete it at step 6. Making the
migration and its verification the same file means what you verified is exactly what you ship:

```kotlin
@Test
fun `rewrites the whole log under the new key without changing what it loads`() {
    val old = File("events.ndjson")
    // deleted first because appendLogEntries appends - otherwise a second run doubles the log
    val new = File("events-new.ndjson").apply { delete() }

    val entries = readLogEntries(old)
    appendLogEntries(new, entries)

    expectThat(readLogEntries(new)).isEqualTo(entries)
    expectThat(new.readLines().size).isEqualTo(old.readLines().size)
}
```

`isEqualTo` on the entry lists is the real proof: these are data classes, so it compares every field
of every entry, not just the one being migrated. `File("events.ndjson")` resolves to the project root
because `tasks.test` in `build.gradle.kts` sets no `workingDir`.

After it passes, `git diff --stat events.ndjson` should show equal insertions and deletions, matching
the number of lines that carried the key. Multiplying the changed-line count by the difference in key
length should also account for the whole byte-size delta — if it doesn't, something else moved.

## Step 6: the check that can actually fail

Nothing above tests the contracted reader, and the obvious end-to-end checks don't either. **An
optional field is read as `""` when its key is missing, so a reader that has stopped seeing the key
parses the entire log without error and silently blanks every value.** Line counts, successful
parsing, and round-trip equality against the migrated file are all blind to this.

What catches it is a count. Before deleting the temporary test, point it at the live migrated file and
assert the number of non-blank values matches the number of keys the migration wrote:

```kotlin
val entries = readLogEntries(File("events.ndjson"))
expectThat(entries.size).isEqualTo(2601)
expectThat(entries.filterIsInstance<GigObserved>().count { it.gig.description.isNotBlank() })
    .isEqualTo(1339)
```

Get both numbers from the migrated file before contracting. Then delete the test.

## Traps

- **Don't verify with a render.** `renderGigsHtml` appends a `GigsRendered` entry and writes an archive
  file (`Main.kt`), so it mutates the log you just migrated and muddies the diff under review.
- Strikt has no `doesNotContain` for strings. Assert on a boolean with `isFalse()` instead.
- `events.ndjson` is tracked by git, so the swap in step 5 is recoverable — but check the file is
  committed and clean *before* overwriting it.
- Leave prompt text sent to the LLM alone. `GigClassifier` deliberately still says "Event page text"
  after the field became `description`: model input is behaviour, not naming.

Worked example: commit `46734ba`, renaming the gig's `pageText` field to `description` across 1339 log
lines.
