# Architecture and the clean-code guardrails

This document describes how Citrine is put together today, where it is going,
and which checks keep it moving in that direction. The checks are ratchets: they
pass on today's code, new code cannot make things worse, and the debt is listed
in files that may only shrink. It follows the same architecture and guardrails
as Amber (`docs/architecture.md` there).

## Today

### Modules

```
:app                  Android app: relay server (Ktor), Room, foreground service, ContentProvider, UI (Compose)
:architecture-tests   Konsist rules for the layering below (test-only module)
:unused-code          cross-module "nothing references this" checker (build-only)
:detekt-rules         Citrine's own detekt rules (NoComments)
```

`AGENTS.md` describes each component in detail.

### The ingestion paths

Every event, whichever way it arrives, converges on
`CustomWebSocketServer.innerProcessEvent()` and from there on
`EventDao.insertEventWithTags()`, which fans it out to live subscriptions
(`EventSubscription.executeAll()`):

1. WebSocket `EVENT` from a client → `CustomWebSocketServer`
2. Events fetched from other relays (`RelayAggregator`, `EventDownloader`), the
   ContentProvider, Pokey broadcasts (`PokeyReceiver`) and the UI (`HomeViewModel`,
   `ContactsScreen`) → `CustomWebSocketService.server?.innerProcessEvent(...)`
3. NIP-29 group events the relay signs itself (`GroupManager`, `Nip29Handler`)
   → `server.innerProcessEvent(...)`

Bulk imports and downloads (`HomeViewModel`, `HomeScreen`,
`DownloadYourEventsUserScreen`, `WebSocketServerService`) set
`Citrine.isImportingEvents`, which blocks concurrent database mutations.

Reads go through `EventRepository` (raw SQL from `EventFilter`s, by design) for
`REQ`/`COUNT`, and through `CitrineContentProvider` for IPC, which mirrors
`SubscriptionManager`'s NIP-42 filtering.

### The dependency container

`Citrine` is the Application class and the container: `applicationScope`,
`client` (the outbound `NostrClient`), the crash report cache and the cleanup
job. Code reaches it through the static `Citrine.instance`, from 30 files
(`ui/` included). Two more globals do the same job: the `Settings` object
(read and written from about 30 files) and the nullable
`CustomWebSocketService.server`. There are two ViewModels (`HomeViewModel`,
`LogcatViewModel`) plus the one inside `ui/components/DatabaseInfo.kt`.

### Where the pain is

- Two files are over 950 lines: `service/RelayAggregator.kt` (2138) and
  `server/CustomWebSocketServer.kt` (1579). Next are
  `provider/CitrineContentProvider.kt` (729), `server/EventRepository.kt` (686),
  `database/EventDao.kt` (622) and `service/NsiteManager.kt` (607).
- `Citrine.instance` is the only way most code gets its dependencies, so almost
  nothing can be constructed in a test without the Application.
- `ui/` reaches Room DAOs directly in 9 files.
- The detekt baseline holds 1076 grandfathered findings in the app: 936
  `NoComments`, 38 `LongMethod`, 32 `CyclomaticComplexMethod` and about 70
  mechanical Compose and small logic findings.
- Line coverage of the app's unit tests is 7.5%.

## Target layering

```
ui/            Composables + ViewModels: render state, send events
  ↓
domain/        use cases and the relay policy (what is accepted, replaced,
               deleted, who may read what) — pure
  ↓
data/          Room, SharedPreferences, outbound relays, files — behind narrow interfaces
```

The UI never touches a database, a relay client or the `Citrine` container: it
talks to a ViewModel, which talks to an interface implemented in `data/`. Manual
constructor injection is enough; there is no Hilt, which keeps the
reproducible-build and dependency-verification footprint small.

## The guardrails

- **detekt** (`config/detekt/detekt.yml`, `config/detekt/compose.yml`,
  `config/detekt/citrine.yml`) — size and complexity (`LongMethod` 60,
  `LargeClass` 600, `CyclomaticComplexMethod` 15, `NestedBlockDepth` 4,
  `LongParameterList`, `TooManyFunctions`, `ComplexCondition`, `ReturnCount`,
  `ThrowsCount`), `ForbiddenMethodCall` (Compose's locale-dependent
  `toLowerCase`/`toUpperCase`), the Compose rules (`ModifierMissing`,
  `ViewModelForwarding`, `ViewModelInjection`, `MutableParams`, …), the unused-code
  rules and `NoComments` (`tools/detekt-rules`). Existing violations are in each
  module's `detekt-baseline.xml`; regenerate with `./gradlew detektBaselineAll`,
  which rewrites the files from the current findings, so fixed code disappears
  from them. Never add an entry by hand.
- **Unused code** (`tools/unused-code`, the `unusedCode` task run by
  `detektAll`) — non-private declarations nothing references and unused catch
  parameters, across every module. It has no baseline.
- **Warnings are errors** — `allWarningsAsErrors` in every module; suppress a
  deliberate one at its site.
- **Architecture tests** (`tools/architecture-tests`, Konsist) — `ui/` does not
  import Room or call a DAO (ViewModels exempt), `Citrine.instance` cannot spread
  to a new file, `Context.citrine` stays in the entry points the system
  constructs, `service/` and `database/` do not depend on Compose UI, and
  ViewModels are named `ViewModel`. The
  allow-lists at the bottom of `ArchitectureTest.kt` are ratchets too: they fail
  when an entry no longer violates the rule, so they cannot silently rot.
- **Coverage** (Kover) — `./gradlew koverXmlReport koverHtmlReport` writes one
  merged report to `build/reports/kover` (generated code excluded); `:app` has a
  minimum line bound that `:app:koverVerify` enforces, a ratchet set just below its
  measured coverage. CI runs the reports and `koverVerify` and uploads the HTML
  report.
- **Dependency verification** — `gradle/verification-metadata.xml` pins the
  SHA-256 of every dependency and plugin artifact.

## Roadmap

The steps follow the order Amber's roadmap took (injection, ViewModels, `ui/`
off the database, splitting the large files and the container, then the
baseline), applied to Citrine's own debt.

Each step is a small, separately mergeable change, and each one should delete
the baseline or allow-list lines it makes obsolete. Do not add to them.

### 1. Replace `Citrine.instance` with constructor injection

Define the narrow interfaces in `data/` (the settings, the event store, the
outbound relay client) and have `Citrine` implement or hold them. Pass them in
through constructors (the server, the services, the provider's query code) or as
parameters of the `object` helpers. Entry points the system constructs
(activities, services, receivers, workers, the provider) keep reading the
container from their context (`Context.citrine`). Shrinks `citrineInstanceUsers`.

Done so far: the receivers (`BootBroadcastReceiver`, `ClipboardReceiver`),
`EventBroadcastWorker`, `CitrineContentProvider` and `WebSocketServerService`
read `Context.citrine` instead of `Citrine.instance`.

### 2. Give the big screens ViewModels

`HomeScreen`, `SettingsScreen` and the `settings/` screens,
`DownloadYourEventsUserScreen`, `RebroadcastScreen`, `ContactsScreen`: hoist state
into a `…ViewModel` and keep a stateless content composable. Removes their
`LongMethod`/`ComposableParamOrder` baseline entries.

### 3. Keep `ui/` off the database

Move each screen's loading and writing into its ViewModel and hand the screen
only what it shows; delete the screen from `databaseInUi`. `DatabaseInfo.kt`'s
ViewModel moves to a file of its own.

### 4. Split the large server and service files

`RelayAggregator`, `CustomWebSocketServer` (message handling per verb, the
HTTP/NIP-86/NIP-11 routes, validation) and `CitrineContentProvider` (one function
per URI). Removes their `LargeClass`, `LongMethod` and `CyclomaticComplexMethod`
entries.

### 5. Split `Citrine`

The outbound client and its WebSocket factory, the ephemeral/expired cleanup
job and the crash report cache each move into a class of their own that
`Citrine` constructs and holds, so `Citrine` is left with wiring and the
Application callbacks.

### 6. Clear the mechanical Compose findings

`ModifierMissing`, `ModifierWithoutDefault`, `ComposableParamOrder`,
`LambdaParameterEventTrailing`, `MultipleEmitters`, `ViewModelInjection`: one rule
per change, regenerating the baseline each time.

### 7. Clear the remaining baseline findings

`ReturnCount`, `ThrowsCount`, `ComplexCondition`, `NestedBlockDepth`,
`LongParameterList`, `TooManyFunctions`, then `CyclomaticComplexMethod` and
`LongMethod` one function at a time, and finally the `NoComments` grandfathering,
module by module: each comment deleted, said through a name or type, moved into
public KDoc, or kept under `@Suppress("NoComments")` when it documents a
constraint outside our code.
