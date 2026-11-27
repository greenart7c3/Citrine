# AGENTS.md

Compact guide for agent sessions working in this repo. This file is the single source of truth; `CLAUDE.md` only points here.

## Toolchain

- **JDK 21 required** (CI uses temurin 21; `compileOptions`/`kotlin.jvmTarget` = 21). Builds fail on older JDKs.
- Product module `:app` (package `com.greenart7c3.citrine`; app entry is `Citrine.kt` (Application) + `MainActivity.kt`), plus three build-time tool modules under `tools/`: `:architecture-tests` (Konsist layering rules), `:unused-code` (the cross-module unused-code checker) and `:detekt-rules` (Citrine's own detekt rules). The target layering and the refactor roadmap are in `docs/architecture.md`.
- Dependency verification is on: `gradle/verification-metadata.xml` pins the SHA-256 of every dependency and plugin artifact, and the build fails on any artifact that is missing or doesn't match. After adding or bumping a dependency or plugin, regenerate it with `./gradlew --write-verification-metadata sha256 help <the tasks that use it>` (e.g. `assembleDebug assembleRelease bundleRelease test lint detektAll koverXmlReport`) and review the diff.
- Kotlin warnings are errors (`allWarningsAsErrors` in every module): fix the warning, or suppress a deliberate one at its site (`@Suppress("DEPRECATION")`).
- Versions: minSdk 26, targetSdk 36, compileSdk 37. Debug build adds `applicationIdSuffix = .debug` → debug applicationId is `com.greenart7c3.citrine.debug` (matters for content-provider authorities / intent filters).

## Commands

```bash
./gradlew ktlintCheck          # Kotlin style — runs on pre-commit
./gradlew ktlintFormat         # autofix ktlint issues
./gradlew detektAll            # complexity, Compose, unused-code and NoComments rules — runs on pre-commit
./gradlew detektBaselineAll    # regenerate the detekt baselines (they may only shrink)
./gradlew lint                 # Android Lint, every variant — runs on pre-commit and pre-push
./gradlew lintDebug            # Android Lint, debug only
./gradlew lintFix              # Android Lint — applies safe auto-fixes (may need 2 runs)
./gradlew test                 # unit tests, including :architecture-tests — runs on pre-push
./gradlew :architecture-tests:test  # the layering rules alone
./gradlew koverXmlReport koverHtmlReport :app:koverVerify  # coverage report + minimum bound
./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # release APK (unsigned unless SIGN_RELEASE set)
./gradlew :app:test --tests "com.greenart7c3.citrine.GroupStateTest"  # single test class
```

- **CI order is `ktlintCheck detektAll -> lint -> test -> koverVerify -> assembleDebug`** — match it locally before pushing.
- **Always run `ktlintCheck`, `detektAll` and `lint` before committing** (the pre-commit hook runs exactly these). All must report 0 errors (Android Lint warnings are OK).
- `detektAll` runs detekt's type-resolving tasks (`:app:detektDebug`, `:app:detektDebugUnitTest` and the tool modules) with `config/detekt/detekt.yml`, `config/detekt/compose.yml` (app only) and `config/detekt/citrine.yml`, then the `unusedCode` task. Every rule not listed in those files is off; suppress a deliberate finding at its site with `@Suppress("UnusedParameter")` etc. Existing findings are grandfathered in each module's `detekt-baseline.xml`. **Never add an entry by hand.** `./gradlew --no-parallel detektBaselineAll` regenerates the files from the current findings; a `+` line in its diff is a new violation that regenerating would grandfather, so fix the code instead of committing it.
- `unusedCode` (`tools/unused-code`, no baseline) fails on a non-private function, property, class or typealias whose name nothing references across every module's sources (Kotlin, XML, ProGuard rules, Gradle scripts and `tools/arti/src` for JNI callbacks), and on a catch parameter the block never reads (name it `_`). Mark code that only something outside the sources uses with `@Suppress("unused")`.
- **No comments** (`NoComments` in `tools/detekt-rules`): say it through names, types and structure instead of a comment. Allowed: KDoc on public or protected declarations of public classes, a license header, tool directives (`noinspection`, `ktlint-disable`, `@formatter:`, `language=`) and links to an issue, an RFC or a NIP. A comment about a constraint outside our code (a platform, vendor or protocol quirk) goes under `@Suppress("NoComments")` on its declaration. Existing comments are grandfathered in the baseline. After changing the rule, run `./gradlew --stop` first: the daemon keeps the old rule classes loaded.
- **Architecture tests** (`tools/architecture-tests`, Konsist): `ui/` does not import Room or call a DAO (`*ViewModel.kt` exempt), `Citrine.instance` cannot spread to a new file, `service/`/`database/` do not depend on Compose UI, and ViewModel subclasses end in `ViewModel`. The allow-lists in `ArchitectureTest.kt` may only shrink: the test fails when a listed file stops violating the rule, so delete the entry when you fix it.
- **Coverage**: `:app` has a minimum line-coverage bound (`minBound` in `app/build.gradle.kts`, set just below its measured coverage), enforced by `:app:koverVerify`; raise it when coverage improves, never lower it.
- **Git hooks auto-install**: `:app:preBuild` depends on the `installGitHook` task, which runs `git config core.hooksPath <repo>/git-hooks` (works in git worktrees too, where `.git` is a file). So the first build installs hooks; files must stay executable (`git update-index --chmod=+x`). `pre-commit` = `ktlintCheck detektAll lint`; `pre-push` = `test lint`.
- **No instrumented tests exist** (`app/src/androidTest` is absent). `./gradlew connectedAndroidTest` is a no-op; don't rely on it.

## Components

- `Citrine.kt` — `Application`. Global singletons: `applicationScope` (`Dispatchers.IO + SupervisorJob()`), `client` (`NostrClient` broadcasting to external relays), `job` (periodic cleanup of ephemeral/expired events), `isImportingEvents` (blocks concurrent database mutations).
- `server/CustomWebSocketServer.kt` — Ktor CIO WebSocket server handling `EVENT`/`REQ`/`CLOSE`/`COUNT`/`AUTH`; entry point for validation (`verifyEvent()`, `innerProcessEvent()`, `VerificationResult`).
- `server/EventRepository.kt` builds raw SQL from `EventFilter`s (tag joins, FTS, author/kind, limit/offset); `server/SubscriptionManager.kt` runs a subscription's filters (enforcing NIP-42 first); `server/EventSubscription.kt` is the in-memory registry of live subscriptions that new events fan out to.
- `service/WebSocketServerService.kt` — foreground service hosting the server, with a 100 s `Timer` for cleanup and optional auto-backup. `service/EventBroadcastWorker.kt` — `WorkManager` worker broadcasting an event to the relays of the user's NIP-65 list (kind 10002).
- `database/` — `EventEntity` (id/pubkey/kind/content/createdAt/sig/expiresAt), `TagEntity` (flattened `col0Name`…`col4Plus` plus denormalized `kind`/`pkEvent`), `EventFTS` (FTS4 on `content`). `EventDao.insertEventWithTags()` inserts transactionally and fans out via `EventSubscription.executeAll()`; `@RawQuery` serves `EventRepository`; `getByKindKeyset()` pages the feed.
- `provider/CitrineContentProvider.kt` — Android IPC over the `events`/`tags` URIs, mirroring `SubscriptionManager`'s auth filtering; contract constants in `CitrineContract`.
- `ui/` — Compose, routes in `Route.kt`, bottom nav Home/Settings: `HomeScreen` (start/stop, event counts, DB controls), `SettingsScreen`, `LogcatScreen`, `ContactsScreen`/`DownloadYourEventsUserScreen`, `Feed` (`EventPagingSource`).
- Libraries: Quartz (`com.vitorpamplona.quartz`), Ktor (CIO) server and client, Room with FTS4, OkHttp for outbound relays, Anon.storage (SAF) for import/export, Arti via JNI (`tools/arti`), Paging 3.

## Database

- Room DB file `citrine_database`, **version 13**, **6 entities**: `EventEntity`, `TagEntity`, `EventFTS`, plus `GroupEntity`, `GroupMemberEntity`, `GroupInviteEntity` (added in migration 12→13). `EventEntity.expiresAt` added in 11→12.
- Migrations are top-level `val MIGRATION_N_(N+1)` in `database/AppDatabase.kt`. When changing schema: add a `MIGRATION_N_(N+1)`, bump the `@Database(version = ...)` **and `TARGET_VERSION`** in **both** `AppDatabase` and `HistoryDatabase`, and `.addMigrations(...)` it on **both** builders.
- There are **three** separate databases: `citrine_database` (`AppDatabase`), `citrine_history_database` (`HistoryDatabase`, only `EventEntity`+`TagEntity`, shares the same migrations), and `logs/LogDatabase`.
- KSP exports Room schemas to `app/schemas/` (checked in) via `ksp { room.schemaLocation }`.

## Server architecture (non-obvious wiring)

- `service/CustomWebSocketService.kt` is a global `object` holding the single nullable `CustomWebSocketServer` instance (`CustomWebSocketService.server`). Started/stopped from `service/WebSocketServerService.kt` (foreground service). Many components feed events back into the server via `CustomWebSocketService.server?.innerProcessEvent(...)`.
- `server/Settings.kt` is a global `object`. Any settings change must be persisted with `LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)` (EncryptedSharedPreferences), or it's lost on restart.
- Outbound relay traffic can route through **Tor** (`service/TorManager.kt`, Arti 2.7 in-process via the JNI wrapper in `tools/arti`; prebuilt `libcitrine_arti.so` lives in `app/src/main/jniLibs`, rebuild with `tools/arti/build.sh`), not just direct OkHttp WebSockets.
- Nostr protocol rules enforced in `server/CustomWebSocketServer.kt`:
  - Ephemeral: kinds 20000–29999 (deleted 1 min after creation if `deleteEphemeralEvents`).
  - Regular replaceable: kinds 0, 3, 10000–19999 — newest per pubkey+kind.
  - Parameterized replaceable: kinds 30000–39999 — newest per pubkey+kind+`d` tag.
  - Kind 5 deletes referenced events by the same pubkey.
  - NIP-42: with `authEnabled`, private kinds and `p`-tagged filters require authenticated pubkeys.

## Release

- Local release signing only happens when the `SIGN_RELEASE` env var is set; it then reads `keystore.properties` at repo root (gitignored, not checked in). Without it, `assembleRelease` produces unsigned APKs.
- `./build.sh <version> <appName>` runs `clean bundleRelease` + `assembleRelease`, moves outputs to `~/release/`, then invokes `generate_manifest.sh`. (Does not set `SIGN_RELEASE`.)
- CI (`create-release.yml`, on `v*` tags) signs via GitHub secrets, not `keystore.properties`.
- ABI splits are enabled: builds emit per-ABI APKs **plus** a universal APK. Debug universal artifact: `app/build/outputs/apk/debug/app-universal-debug.apk`.
- **A version bump is one commit touching a fixed set of files**, tagged with a lightweight `v<version>` tag on that commit (CI's `create-release.yml` builds from the tag and creates the release with an empty body):
  - `app/build.gradle.kts`: `versionCode` + 1 and `versionName`. The version code is what F-Droid and Zapstore compare — never reuse one.
  - `CHANGELOG.md`: **only the new release section** — `## Citrine <version>`, the version's bullets, the download/donation footer and the `## Verifying the release` block. Copy the previous section and update the version, the `releases/tag/v<version>` URL and the `manifest-v<version>.txt` names. Older sections are deleted from this file: every version's notes live in `docs/changelogs/<version>.md`, and the file keeps a single closing line pointing there.
  - `docs/changelogs/<version>.md`: byte-identical to that section of `CHANGELOG.md` (check with `diff <(head -n <n> CHANGELOG.md) docs/changelogs/<version>.md`). `docs/changelogs/README.md`: add the new row at the top of the table.
  - `zapstore.yaml`: `release_notes: ./docs/changelogs/<version>.md`.
- `~/release_citrine.sh <tag> [appName] [--dry-run]` does the rest, **after the tag is pushed**: waits for the tag's CI run and the release it creates, builds the unsigned APKs locally, signs them via `~/sign_apks.sh` into `~/Downloads/signed_apks/<tag>`, sets the release body from the `## Citrine <version>` section of `CHANGELOG.md`, uploads the APKs + manifest, publishes to Zapstore (`SIGN_WITH` must be set) and posts the kind-1 changelog note. It reads `CHANGELOG.md` and `zapstore.yaml` from the working tree, not from the tag, so those must be committed and correct before it runs.
- **Remotes:** `origin` has two push URLs — the `nostr://` ngit remote and `https://github.com/greenart7c3/Citrine.git` — so a plain `git push` / `git push origin master` (tags the same way) updates both; never pass a URL or push to only one of them. If the GitHub leg fails with `could not read Username`, the `gh` credential helper path in `~/.config/git/config` went stale after a mise `gh` upgrade: point it at `/home/admin/.local/share/mise/shims/gh`.

## Dev / perf tooling

- `scripts/relay_bench.py` benchmarks the relay over WebSocket. Requires `pip install websockets` (optionally `coincurve` for faster signing). Run with `adb forward tcp:4869 tcp:4869` then `python3 relay_bench.py`, or `python3 relay_bench.py --url ws://<host>:4869`.

## Translations

- Translations live in `app/src/main/res/values-<locale>/strings.xml` and are maintained in-repo (no Crowdin). The shipped locales match Amber: `en, de, es, fr, in, it-rIT, ja, ko-rKR, pt-rBR, ru, th, tr, vi-rVN, zh`, pinned by `androidResources.localeFilters` in `app/build.gradle.kts` and listed in `app/src/main/res/xml/locales_config.xml`.
- **Strings must be translated in the same change.** Whenever you add or change a string in `app/src/main/res/values/strings.xml`, add the real translation to **every** `app/src/main/res/values-*/strings.xml` locale file before reporting the work as done. Never stop at English and never copy English into a locale file — write the actual translation, matching the existing tone/register of that file. Because `MissingTranslation` lint is disabled, a passing build does **not** mean the translations are present. Escape apostrophes as `\'` in languages that use them (French, Italian, Turkish, …), exactly like neighboring entries.

- For repository collaboration, use ngit and follow `.agents/skills/ngit/SKILL.md`.
