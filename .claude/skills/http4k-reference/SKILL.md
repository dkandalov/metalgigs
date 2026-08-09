---
name: http4k-reference
description: Look up exact http4k API signatures and behavior from the local http4k source checkout instead of guessing from memory or decompiling jars. Use whenever writing or debugging code that calls into http4k (or a library like Kondor whose source is also vendored here) and the exact method name, parameter, or default behavior isn't certain.
---

A full checkout of the http4k source lives locally at `/Users/dk/Projects/_ref/http4k` (a real git clone, not just jars — `Read`/`Grep` it directly).

This project has been burned before by guessing library APIs from memory (e.g. assuming Kondor's short-function names were `bindString`/`bindInt` when the real Kotlin source names are `str`/`num` — the guessed names only existed as `@JvmName` bytecode aliases) and by assuming defaults (e.g. assuming http4k's `OkHttp()` client follows HTTP redirects, when `PreCannedOkHttpClients.defaultOkHttpClient()` explicitly sets `.followRedirects(false)`). Reading the actual source avoids both classes of mistake and is faster than downloading/decompiling jars from Maven Central.

## Finding the right module

Each `org.http4k:http4k-X` Gradle dependency maps to a subdirectory under `core/`:

- `http4k-core` → `core/core/src/main/kotlin/org/http4k/`
- `http4k-client-okhttp` → `core/client/okhttp/src/main/kotlin/org/http4k/client/OkHttp.kt`
- `http4k-server-jetty` → `core/server/jetty/`
- `http4k-template-handlebars` → `core/template/handlebars/`, shared `ViewModel`/`TemplateRenderer` in `core/template/core/`
- `http4k-testing-approval` → `core/testing/approval/`
- `http4k-tools-traffic-capture` → `core/tools/traffic-capture/`
- `http4k-format-kondor-json` (not used in this project, but Kondor itself lives alongside at `core/format/kondor-json/` if ever needed)

When unsure which module a symbol lives in, just grep the whole checkout rather than guessing the path:

```bash
grep -rn "class FollowRedirects" /Users/dk/Projects/_ref/http4k/core --include=*.kt
```

## Workflow

1. Grep for the class/function name across `/Users/dk/Projects/_ref/http4k/core` (exclude `build/` — it contains compiled `.class` files and generated docs, not source).
2. Read the actual `src/main/kotlin` file to confirm the real signature, parameter names, and defaults — not the `src/test` usage, which can be misleading in isolation, but tests are a good secondary source for example usage.
3. Prefer this over `javap`/decompiling a resolved jar from `~/.gradle/caches`, and over downloading jars from Maven Central — both are slower and, for Kotlin, `javap` shows JVM-level names (`@JvmName` aliases) rather than the real Kotlin source names you need to write.
