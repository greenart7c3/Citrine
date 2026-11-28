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
`relayClient` (the `RelayClient`, `data/`), the crash report cache, the Pokey
listener and the cleanup job. There is no static accessor (step 1 removed
`Citrine.instance`): code takes its dependencies through its constructor, the
`object` helpers get them once from `Citrine`'s constructor (`install`), Android
entry points read the container from their own context (`Context.citrine`) and
composables read the CompositionLocals `MainActivity` provides
(`ui/AppDependencies.kt`). Two globals remain: the `Settings` object (read and
written from about 30 files) and the nullable `CustomWebSocketService.server`.
There are two ViewModels (`HomeViewModel`, `LogcatViewModel`) plus the one inside
`ui/components/DatabaseInfo.kt`.

### Where the pain is

- Two files are over 950 lines: `service/RelayAggregator.kt` (2138) and
  `server/CustomWebSocketServer.kt` (1579). Next are
  `provider/CitrineContentProvider.kt` (729), `server/EventRepository.kt` (686),
  `database/EventDao.kt` (622) and `service/NsiteManager.kt` (607).
- `Citrine.instance` was the only way most code got its dependencies (30
  files), so almost nothing could be constructed in a test without the
  Application; step 1 removed it.
- `ui/` reached Room DAOs directly in 9 files; since step 3 it does not.
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
  import Room or call a DAO (ViewModels exempt), `Context.citrine` (or a cast to
  `Citrine`) stays in the entry points the system constructs, `service/` and `database/` do not depend on Compose UI, and
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

### 1. Replace `Citrine.instance` with constructor injection (done)

Define the narrow interfaces in `data/` (the settings, the event store, the
outbound relay client) and have `Citrine` implement or hold them. Pass them in
through constructors (the server, the services, the provider's query code) or as
parameters of the `object` helpers. Entry points the system constructs
(activities, services, receivers, workers, the provider) keep reading the
container from their context (`Context.citrine`). Shrinks `citrineInstanceUsers`.

Done so far: the receivers (`BootBroadcastReceiver`, `ClipboardReceiver`),
`EventBroadcastWorker`, `CitrineContentProvider` and `WebSocketServerService`
read `Context.citrine` instead of `Citrine.instance`. `CustomWebSocketServer`
takes the application context, the app scope and an `onEphemeralEventStored`
callback through its constructor, and hands the scope to each `Connection`.
`EventSubscription`, which `EventDao` calls statically, gets the app scope, the
exception handler and the out-of-memory report sink once from `Citrine`'s
constructor (`install`), before the ContentProvider can store an event, and
passes them to each `Subscription` and `SubscriptionManager`.

The outbound relay connections are `data/RelayClient` (the Quartz client, its
WebSocket factory and the proxy, onion and private-address decisions),
implemented by `relays/AppRelayClient` and held by `Citrine` as `relayClient`.
Every caller takes the `RelayClient` itself; `Citrine` has no forwarders to it.

The `object` helpers get their narrow dependencies once from `Citrine`'s
constructor (`install`), like `EventSubscription`: `EventRebroadcaster` and
`ListImporter` the `RelayClient`, `CrashReportSender` the `RelayClient` and the
app scope, `EventDownloader` the application context, the `RelayClient` and the
app scope, `RelayIdentity` the application context and `GroupManager` the
application context and the app scope, `NsiteManager` the application context
and the `RelayClient`, `TorManager` the application context and the app scope,
and `RelayAggregator` the application context, the `RelayClient` and the
exception handler of its scope. Composables get the narrow dependencies from
CompositionLocals in `ui/AppDependencies.kt` (`LocalRelayClient`,
`LocalAppScope`, `LocalPokeyListener`, `LocalCrashReportCache`, each listed under
`allowedCompositionLocals` in `config/detekt/compose.yml`), which `MainActivity`
provides with `appDependencies(citrine)`, or a plain `LocalContext` where that is
all they need. `Citrine.cancelJob()` is a companion function (it only touches the
static `job`), and the Pokey receiver registration is `service/PokeyListener`. A context handed over from the
constructor is only read once the Application is attached. The ViewModels take
their dependencies through a `factory` used as the screen's `viewModel(...)`
argument (`HomeViewModel.factory(appContext, appScope)`,
`LogcatViewModel.factory(appContext)`).

Step 1 is done: `Citrine.instance` is gone, and with it its allow-list. What is
left of the container on the Application is reached only through
`Context.citrine` in the entry points, which `contextCitrineUsers` pins.

### 2. Give the big screens ViewModels (done)

`HomeScreen`, `SettingsScreen` and the `settings/` screens,
`DownloadYourEventsUserScreen`, `RebroadcastScreen`, `ContactsScreen`: hoist state
into a `…ViewModel` and keep a stateless content composable. Removes their
`LongMethod`/`ComposableParamOrder` baseline entries.

Done so far: `HomeScreen` keeps the ViewModel, the activity launchers and the
scopes, and lays out stateless sections (`RunningRelaySection`,
`StoppedRelaySection`, `AggregatorSection`, `DatabaseButtons`, `ToolButtons`,
`ConnectionsSection` and the dialogs) that take plain values and callbacks; the
external-signer login is `HomeViewModel.loginWithExternalSigner`. The dialog flags
stay in composition (`HomeDialogState`) so they reset as before. `CitrineScaffold`
splits into `CitrineNavHost`, nav-graph builders and `FeedScreen`, and takes
`HomeViewModel` as a default parameter; it provides its own `ViewModelStoreOwner`
around `HomeScreen`, whose default `viewModel(...)` then resolves the same instance,
so the ViewModel is never forwarded (the Home route's back-stack entry would
otherwise own a second one, since the bottom bar pops it on every tab switch).

`SettingsScreen`, `NetworkSettingsScreen`, `RelayInfoSettingsScreen` and
`RetentionSettingsScreen`: a `…ViewModel` each holds the form's state, initialised
from `Settings` as before, and runs its apply logic at the same moments; the screens
keep the toasts, clipboard and signer launchers. `SettingsScreen` lays out section
composables; the three list screens use private `LazyListScope` item builders
(`relaySwitchItems`, `connectionItems`, `preservedKindsItems`, …), since
`stickyHeader` needs that scope and a composable wrapper would merge the items.
Unsaved edits now survive a configuration change.

The other settings screens follow the same shape: `AccessControlSettingsViewModel`,
`AggregatorSettingsViewModel`, `Nip86SettingsViewModel`, `WebClientsSettingsViewModel`,
`NsiteRelaysSettingsViewModel`, `BrowseNsitesViewModel` and `BackupSettingsViewModel`.
The editable lists share two state holders, `EditableSet` (a set and the field a new
entry is typed in) and `EditableBanList` (NIP-86 bans with their reasons), which the
item builders edit directly.

The remaining long screens split the same way, each keeping its state and actions
and laying out private sections: `ContactsScreen`, `CrashReportScreen`,
`DatabaseInfo`, `DownloadYourEventsUserScreen` and its relay picker,
`ImportFromListsDialog`, `NsiteRow` and `RebroadcastScreen` (whose form and
validation are a remembered `RebroadcastFormState`). `HomeViewModel.importDatabase`
splits into `importLines`/`importLine`, and `StoragePickerHelper` takes its pickers
and permission launcher as two small groups. Step 2 is done: nothing under `ui/` has
a grandfathered size or complexity finding left.

### 3. Keep `ui/` off the database (done)

Move each screen's loading and writing into its ViewModel and hand the screen
only what it shows; delete the screen from `databaseInUi`. `DatabaseInfo.kt`'s
ViewModel moves to a file of its own.

Done so far: `DatabaseInfo` (`DatabaseInfoViewModel` in its own file counts the
stored events per kind as `KindCount` rows and deletes a kind on the app scope).
`SettingsScreen` and `AggregatorSettingsScreen` call `RelayAggregator.onConfigChanged()`,
which opens the database from its installed context. `Nip86SettingsScreen`:
`Nip86SettingsViewModel.purgeEventsOf` deletes the banned authors' events.
`ContactsScreen`: `ContactsViewModel` loads the stored contact lists and the
outbox relay list. `RebroadcastScreen`: `RebroadcastViewModel` finds the author's
relays (the stored NIP-65 list, else fetched, else the contact list's relays) and
starts the rebroadcast job on the app scope. `HomeScreen`: `HomeViewModel` opens
the database itself for export and import, wipes it (`deleteAllEvents`, stopping
and restarting the aggregator around it) and exposes the upgrade flag. The
per-kind event list in `CitrineScaffold`: `FeedViewModel` owns the `Pager`
(cached in its scope, so it is no longer rebuilt on every recomposition) and
hands the screen `Event`s. `DownloadYourEventsUserScreen`:
`DownloadYourEventsViewModel` looks up the stored contact and relay lists (else
fetches them) for the relay picker. The `databaseInUi` allow-list is empty, so
the rule now fails on any `ui/` file outside a ViewModel that touches the
database.

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

### 6. Clear the mechanical Compose findings (done)

`ModifierMissing`, `ModifierWithoutDefault`, `ComposableParamOrder`,
`LambdaParameterEventTrailing`, `MultipleEmitters`, `ViewModelInjection`: one rule
per change, regenerating the baseline each time.

Done: every composable that emits UI takes `modifier: Modifier = Modifier` and
applies it at its root (21 `ModifierMissing`, 4 `ModifierWithoutDefault`; callers
keep the default, so nothing renders differently), required parameters and event
callbacks come before the optional ones (10 `ComposableParamOrder`, 10
`LambdaParameterEventTrailing`; trailing-lambda callers name the callback), and
`RebroadcastProgress` is a `ColumnScope` extension rather than wrapping its rows in a
`Column` of its own, which would drop the parent's spacing (`MultipleEmitters`).
46 entries in one change. The `ViewModelInjection` entry for `CitrineScaffold`'s
`HomeViewModel` stays until step 2 hoists `HomeScreen`'s state: as a default
parameter it would only become a `ViewModelForwarding` finding.

### 7. Clear the remaining baseline findings

`ReturnCount`, `ThrowsCount`, `ComplexCondition`, `NestedBlockDepth`,
`LongParameterList`, `TooManyFunctions`, then `CyclomaticComplexMethod` and
`LongMethod` one function at a time, and finally the `NoComments` grandfathering,
module by module: each comment deleted, said through a name or type, moved into
public KDoc, or kept under `@Suppress("NoComments")` when it documents a
constraint outside our code.
