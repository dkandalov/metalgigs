---
name: run-tests
description: Run the full Gradle test suite for this project. Use when asked to run tests, run the test suite, or verify the build/tests pass.
---

Run `.claude/scripts/run-tests.sh` from the project root using the Bash tool.

Report whether the build succeeded. If any tests failed, list the failing test names and summarize the cause — check the Gradle output first, and if more detail is needed, read `build/reports/tests/test/index.html` or the XML results under `build/test-results/test/`.
