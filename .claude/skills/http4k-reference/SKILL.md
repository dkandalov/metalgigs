---
name: http4k-reference
description: Look up exact http4k or Kondor API signatures and behavior from local source checkouts instead of guessing from memory or decompiling jars. Use whenever writing or debugging code that calls into http4k or Kondor (com.ubertob.kondor, e.g. the JAny/str/num JSON converters in GigsStore.kt) and the exact method name, parameter, or default behavior isn't certain.
---

Full source checkouts live locally — real git clones, not just jars, so `Read`/`Grep` them directly:

- http4k: `/Users/dk/Projects/_ref/http4k`
- Kondor: `/Users/dk/Projects/_ref/kondor-json` (this project depends on the `kondor-core` module only)

This project has been burned before by guessing library APIs from memory (e.g. assuming Kondor's short-function names were `bindString`/`bindInt` when the real Kotlin source names are `str`/`num` — the guessed names only existed as `@JvmName` bytecode aliases) and by assuming defaults (e.g. assuming http4k's `OkHttp()` client follows HTTP redirects, when `PreCannedOkHttpClients.defaultOkHttpClient()` explicitly sets `.followRedirects(false)`). Reading the actual source avoids both classes of mistake and is faster than downloading/decompiling jars from Maven Central.

## Finding the right module

### http4k

Each `org.http4k:http4k-X` Gradle dependency maps to a subdirectory under `core/`:

- `http4k-core` → `core/core/src/main/kotlin/org/http4k/`
- `http4k-client-okhttp` → `core/client/okhttp/src/main/kotlin/org/http4k/client/OkHttp.kt`
- `http4k-server-jetty` → `core/server/jetty/`
- `http4k-template-handlebars` → `core/template/handlebars/`, shared `ViewModel`/`TemplateRenderer` in `core/template/core/`
- `http4k-testing-approval` → `core/testing/approval/`
- `http4k-tools-traffic-capture` → `core/tools/traffic-capture/`

Note: `core/format/kondor-json/` in the http4k checkout is http4k's own *integration* module for Kondor (wiring it up as an http4k `AutoMarshalling`) — it is not the Kondor library itself and isn't used in this project. For Kondor's actual source, use the separate checkout below.

### Kondor

This project depends on `com.ubertob.kondor:kondor-core` (see `build.gradle.kts`). Its source is under `/Users/dk/Projects/_ref/kondor-json/kondor-core/src/main/kotlin/com/ubertob/kondor/`:

- `json/` — core converters: `JAny`, `JField`, the `str`/`num`/`obj`/etc. short functions (`ShortFunctions.kt`), NdJson helpers (`NdJson.kt`)
- `json/datetime/` — date/time converters (`JInstant`, `JLocalDate`, ...) and their `str`/`num` overloads (`ShortFunctions.kt`, `JDateTime.kt`) — these are a *separate* package from `json/`'s `str`/`num`, so using e.g. `Instant` fields requires importing both (see `GigsStore.kt`)
- `json/jsonnode/` — the `JsonNodeObject` deserialization context (the `+field` syntax)
- `json/parser/`, `json/schema/` — JSON parsing and schema generation, rarely needed here

When unsure which module or package a symbol lives in, just grep the whole checkout rather than guessing the path:

```bash
grep -rn "class FollowRedirects" /Users/dk/Projects/_ref/http4k/core --include=*.kt
grep -rn "fun.*str(" /Users/dk/Projects/_ref/kondor-json/kondor-core/src/main/kotlin --include=*.kt
```

## Workflow

1. Grep for the class/function name across the relevant checkout (exclude `build/` — it contains compiled `.class` files and generated docs, not source).
2. Read the actual `src/main/kotlin` file to confirm the real signature, parameter names, and defaults — not the `src/test` usage, which can be misleading in isolation, but tests are a good secondary source for example usage.
3. Prefer this over `javap`/decompiling a resolved jar from `~/.gradle/caches`, and over downloading jars from Maven Central — both are slower and, for Kotlin, `javap` shows JVM-level names (`@JvmName` aliases) rather than the real Kotlin source names you need to write.
