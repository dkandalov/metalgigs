---
name: daily-update
description: Run the full daily refresh (scrape, classify, render) and publish it - committing the changed files and pushing to GitHub - or inspect and manage the launchd agent that runs it automatically. Use when asked to do the daily update, refresh and publish the gigs page, update and push the site, or to check/fix/schedule the automatic daily job.
---

Run `.claude/scripts/daily-update.sh [force]` from the project root using the Bash tool. It runs `daily-update` (see the `run-main` skill for what that does), then commits and pushes.

**This pushes to the `origin` remote on GitHub**, and it pushes *every* unpushed commit on the branch, not only the one it just made. Say so when reporting, and check with the user first if they haven't clearly asked for the push in this conversation — running the individual commands via the `run-main` skill does the same work without publishing anything.

It needs `ANTHROPIC_API_KEY` in the environment (`classify` makes a real paid API call per unclassified gig). The script fails fast with a clear message if it's missing. Note the key lives in `~/.zshrc`, which a non-interactive shell doesn't load, so a bare Bash-tool invocation won't see it — `source ~/.zshrc >/dev/null 2>&1;` before the script fixes that without ever printing the value.

Expect it to take minutes rather than seconds when there's a backlog: `classify` is one API call per gig. Use a generous Bash timeout.

What to expect, and how to report each case:

- **Already done today.** `daily-update` skips if the log already holds a render whose `logicalDate` is today, printing "Skipping - already updated for <date>". The script then finds nothing to commit and just pushes anything outstanding. Report that no update was needed; don't rerun with `force` unless the user asks, since forcing re-scrapes every venue.
- **Normal run.** Report the gig counts from the `== scrape ==` / `== classify ==` / `== render ==` sections — how many gigs were classified and their Metal/Other split, how many are on the rendered page — then the commit and that it was pushed.
- **Some gigs couldn't be classified.** `classify` reports them under "Could not classify N gig(s) - they stay Pending" and continues. Those then block `render`, so the script aborts before committing (see below). They will fail identically on every future run, so they need `classify override` — use the `classify-unclassified-gigs` skill.
- **Render refused to publish.** If any upcoming gig is still unclassified, `render` fails fast and the script stops before committing or pushing. This is not data loss: the scrape and classification results are already in the append-only log, and a later run picks them up. Settle the gigs (again, the `classify-unclassified-gigs` skill) and rerun. Report the unresolved list to the user rather than reaching for `render force`, which publishes a page knowingly missing those gigs.

The script stages only `events.ndjson`, `index.html` and `images/` — deliberately not `git add -A`, since the working tree also carries untracked local editor and tool config. If the user expects some other file to be published, that's a change to the script, not something to work around by staging it by hand.

## The scheduled job

A launchd agent runs the same script automatically. Four files are involved, and only the first is in the repo:

| file | in git | purpose |
| --- | --- | --- |
| `.claude/scripts/daily-update.sh` | yes | what actually runs |
| `~/Library/LaunchAgents/london.metalgigs.daily.plist` | **no** | the agent definition |
| `~/.metalgigs.env` | **no** | holds `ANTHROPIC_API_KEY`, `chmod 600` |
| `~/Library/Logs/metalgigs-daily.log` | no | combined stdout/stderr of every run |

Because the plist and the env file live outside the repo, a rebuilt machine loses both. The plist is reproduced in full at the end of this section for exactly that reason.

**Managing it.** The label is `london.metalgigs.daily`, and `gui/$(id -u)` is the user's domain:

```bash
launchctl print gui/$(id -u)/london.metalgigs.daily      # state, schedule, last exit code
launchctl kickstart -p gui/$(id -u)/london.metalgigs.daily   # run it now
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/london.metalgigs.daily.plist   # load
launchctl bootout gui/$(id -u)/london.metalgigs.daily    # unload
```

Editing the plist requires a `bootout` then `bootstrap` to take effect. `launchctl load`/`unload` are the deprecated spellings; prefer the above. To check whether a run worked, read `~/Library/Logs/metalgigs-daily.log` and the `last exit code` from `launchctl print` — exit 0 with "Skipping - already updated for <date>" is the normal outcome for every fire after the day's first.

**Why it fires three times** (09:00, 14:00, 20:00) rather than once: `daily-update` gates itself on whether the log already holds a render for today, so the second and third fires exit immediately. That makes a closed laptop at 09:00 a non-event rather than a missed day, with no risk of updating twice. launchd also runs a missed calendar fire when the machine wakes, so the two behaviours compound. `RunAtLoad` is false — bootstrapping the agent must not itself publish.

**Why the environment matters.** launchd gives a job almost nothing, which breaks this two ways, and the script guards both up front:

- `ANTHROPIC_API_KEY` lives in `~/.zshrc`, which no non-interactive shell reads. The script falls back to `~/.metalgigs.env`. That file is deliberately *not* the plist, since plists are world-readable by default. Recreate it from an interactive shell where the key is set, so the value is never echoed: `umask 077 && printf 'export ANTHROPIC_API_KEY=%s\n' "$ANTHROPIC_API_KEY" > ~/.metalgigs.env`
- ImageMagick lives in `/opt/homebrew/bin`, which isn't on launchd's default `PATH`, so the plist sets `PATH` explicitly. This one is dangerous rather than merely broken: `render` reports a failed image conversion per gig and completes anyway, so without `magick` an unattended run would publish a page of missing images and push it. The script therefore treats a missing ImageMagick as fatal.

**What still needs a human.** The job publishes to a live site with nobody reviewing it, so a broken scraper's output goes straight out; the unresolved-gig check in `render` and `set -e` are the only brakes. And a gig the classifier can't judge leaves the backlog non-empty, which fails `render` and so fails the job every day until someone settles it — `classify status` says what's stuck, and the `classify-unclassified-gigs` skill resolves it. The log is never rotated and `scrape` prints every gig it sees, so it grows by roughly 450 lines a day.

**The plist**, for recreating it:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key><string>london.metalgigs.daily</string>
    <key>ProgramArguments</key>
    <array><string>/Users/dk/Projects/metalgigs/.claude/scripts/daily-update.sh</string></array>
    <key>WorkingDirectory</key><string>/Users/dk/Projects/metalgigs</string>
    <key>EnvironmentVariables</key>
    <dict><key>PATH</key><string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string></dict>
    <key>StartCalendarInterval</key>
    <array>
        <dict><key>Hour</key><integer>9</integer><key>Minute</key><integer>0</integer></dict>
        <dict><key>Hour</key><integer>14</integer><key>Minute</key><integer>0</integer></dict>
        <dict><key>Hour</key><integer>20</integer><key>Minute</key><integer>0</integer></dict>
    </array>
    <key>RunAtLoad</key><false/>
    <key>StandardOutPath</key><string>/Users/dk/Library/Logs/metalgigs-daily.log</string>
    <key>StandardErrorPath</key><string>/Users/dk/Library/Logs/metalgigs-daily.log</string>
</dict>
</plist>
```

The paths are absolute and specific to this machine — `ProgramArguments`, `WorkingDirectory` and both log paths all need changing if the repo or user moves.
