---
name: approve-tests
description: Approve pending approval-test output (http4k-testing-approval .actual files) as the new .approved baseline. Use when an approval test fails because rendered output legitimately changed and needs to be re-approved.
---

This project uses http4k's `http4k-testing-approval` for approval tests (e.g. `AppTest`'s HTML-rendering test). When such a test's expected output changes on purpose, the test fails and writes a `.actual` file next to the existing `.approved` file under `src/test/resources/`.

Before approving:
1. Run the tests (see the `run-tests` skill) and find the failing approval test(s).
2. Read the new `.actual` file(s) and check the content is correct — it reflects what changed and does not hide an unintended regression.

Once you've confirmed the output is correct, run `.claude/scripts/approve-tests.sh` from the project root using the Bash tool. It finds every `*.actual` file under `src/test/resources` and renames it to `*.approved`, overwriting the previous baseline. Then re-run the tests to confirm they pass.

Never run this script without reading the `.actual` content first — it blindly promotes whatever was produced, so approving a broken render would bake the regression in as the new baseline.
