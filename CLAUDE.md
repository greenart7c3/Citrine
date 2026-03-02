# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Citrine is a Nostr relay for Android. It runs a WebSocket server on-device that other Nostr clients can connect to via `ws://127.0.0.1:4869` (default). It is written in Kotlin with Jetpack Compose for UI, Ktor for the WebSocket server, and Room for local persistence.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Check lint (ktlint) — also runs automatically as a pre-commit hook
./gradlew ktlintCheck

# Auto-fix lint issues
./gradlew ktlintFormat

# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest

# Run a single test class
./gradlew :app:test --tests "com.greenart7c3.citrine.ExampleUnitTest"
```

The pre-commit hook runs `ktlintCheck` automatically. If it fails, run `ktlintFormat` before committing.

## Architecture

### Core Components

**`Citrine.kt`** — Application class. Holds global singletons:
- `applicationScope`: App-wide `CoroutineScope(Dispatchers.IO + SupervisorJob())`
- `client`: `NostrClient` used to broadcast events to external relays (via OkHttp WebSocket)
- `job`: Nullable `Job` for periodic cleanup (ephemeral/expired events)
- `isImportingEvents`: Global flag to prevent concurrent database mutations

**`server/Settings.kt`** — Global singleton (`object`) holding all relay configuration. Persisted via `LocalPreferences` to `EncryptedSharedPreferences`. Fields include port, allowed kinds/pubkeys, auth settings, deletion policies, and web client maps.

**`server/CustomWebSocketServer.kt`** — Ktor CIO WebSocket server. Handles all Nostr protocol messages (`EVENT`, `REQ`, `CLOSE`, `COUNT`, `AUTH`). Entry point for event validation and storage. Contains `VerificationResult` sealed hierarchy for event validation outcomes and `innerProcessEvent()` / `verifyEvent()` for the processing pipeline.

**`server/EventRepository.kt`** — Builds raw SQL queries dynamically from `EventFilter` objects. Handles tag joins, FTS search, author/kind filters, and limit/offset. Results are sent directly to WebSocket subscribers.

**`server/SubscriptionManager.kt`** — Executes a subscription by iterating its filters and calling `EventRepository.subscribe()`. Enforces NIP-42 auth requirements before querying.

**`server/EventSubscription.kt`** — In-memory registry of active subscriptions across all open WebSocket connections. Delivers new events to matching subscriptions when events are inserted.

**`service/WebSocketServerService.kt`** — Android foreground service hosting the WebSocket server. A periodic `Timer` (100s interval) triggers event cleanup and optional auto-backup. References the server via the global `CustomWebSocketService.server`.

**`service/LocalPreferences.kt`** — Serializes/deserializes `Settings` to/from `EncryptedSharedPreferences`. All settings changes must be saved via `LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)`.

**`service/EventBroadcastWorker.kt`** — `WorkManager` `CoroutineWorker` that broadcasts a stored event to external relays using the user's NIP-65 advertised relay list (kind 10002).

### Database Layer (`database/`)

Room database (`citrine_database`, version 11) with three entities:
- **`EventEntity`** — Core event fields (id, pubkey, kind, content, createdAt, sig)
- **`TagEntity`** — Flattened tags: `col0Name` (tag name), `col1Value`, `col2Differentiator`, `col3Amount`, `col4Plus` (overflow columns), plus denormalized `kind` and `pkEvent` foreign key
- **`EventFTS`** — FTS4 virtual table on `content` for full-text search

Key DAO patterns in `EventDao`:
- `insertEventWithTags()` — Transactional insert that also fans out to active subscriptions via `EventSubscription.executeAll()`
- Raw queries via `@RawQuery` used by `EventRepository` for dynamic filter generation
- Keyset pagination via `getByKindKeyset()` for the Feed screen

When adding database migrations, increment the version in `AppDatabase` and add a `MIGRATION_N_(N+1)` object.

### Android IPC (`provider/`)

**`CitrineContentProvider`** — Exposes events to other Android apps via `ContentResolver`. Supports query/insert/delete on `events` and `tags` URIs. Auth filtering mirrors `SubscriptionManager` logic. Contract constants are in `CitrineContract`.

### UI Layer (`ui/`)

Jetpack Compose with a bottom navigation bar (Home, Settings). Navigation is defined in `Route.kt`. Key screens:
- `HomeScreen` / `HomeViewModel` — relay start/stop, event count by kind, database controls
- `SettingsScreen` — all relay configuration
- `LogcatScreen` — in-app log viewer
- `ContactsScreen` / `DownloadYourEventsUserScreen` — event download helpers
- `Feed` — paginated event browser per kind (uses `EventPagingSource`)

### Nostr Protocol Notes

- Ephemeral events: kind 20000–29999, deleted 1 minute after creation if `deleteEphemeralEvents = true`
- Regular replaceable events: kinds 0, 3, 10000–19999 — only the newest is kept per pubkey+kind
- Parameterized replaceable events: kinds 30000–39999 — only the newest is kept per pubkey+kind+`d` tag
- Kind 5 delete events trigger deletion of referenced events owned by the same pubkey
- NIP-42 auth: when `authEnabled = true`, private event kinds and `p`-tagged filters require authenticated pubkeys

### External Libraries

- **Quartz** (`com.vitorpamplona.quartz`) — Nostr protocol primitives (Event, crypto, relay client)
- **Ktor** — WebSocket server (CIO engine) and client
- **Room** — SQLite ORM with FTS4 support
- **OkHttp** — HTTP/WebSocket client for outbound relay connections
- **Anon.storage** — SAF-based file picker used for import/export
