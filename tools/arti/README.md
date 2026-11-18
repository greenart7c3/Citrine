# Arti (built-in Tor)

Citrine's built-in Tor runs [Arti](https://gitlab.torproject.org/tpo/core/arti)
(Tor in Rust) in-process through a small JNI wrapper, `src/lib.rs`. The
wrapper starts a Tor client, puts a SOCKS5 proxy on `127.0.0.1` in front of it,
runs the relay's onion service (the `.onion` address clients use to reach this
relay) and reports bootstrap progress; Kotlin's `TorManager` drives it through
`com.greenart7c3.citrine.tor.ArtiNative`.

The wrapper is adapted from Amber's `tools/arti` (which adapted Amethyst's
`tools/arti-build`, <https://github.com/vitorpamplona/amethyst>, © 2025 Vitor
Pamplona, MIT License). The onion service surface is Citrine-specific.

## Prebuilt libraries

The libraries are built once with `build.sh` and committed; CI does not build Rust.

| App    | Path                                                                            | Built with                                                 |
| ------ | ------------------------------------------------------------------------------- | ---------------------------------------------------------- |
| Android | `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64,x86}/libcitrine_arti.so`   | `./build.sh` (cargo-ndk, NDK pinned in `build.sh`)         |

The ABIs must match `splits.abi` in `app/build.gradle.kts`.

## Rebuilding

Rebuild after changing `src/lib.rs`, `Cargo.toml` or `Cargo.lock`, and commit
the regenerated libraries together with the source change. Every library has
to come from the same source, so rebuild all of them.

Prerequisites: rustup (the compiler is pinned in `rust-toolchain.toml`; the
script adds the targets), plus `cargo install cargo-ndk` and the NDK revision
in `build.sh`.

```bash
./build.sh
```

The script checks that every library exports the JNI entry points and prints
their SHA-256 hashes.

## Reproducibility

Rebuilding from a fresh checkout (any path: `build.sh` remaps host paths out
of the binaries) gives byte-identical libraries with the pinned toolchain and
`Cargo.lock`. The APK packages `libcitrine_arti.so` unstripped
(`packaging.jniLibs.keepDebugSymbols` in `app/build.gradle.kts`), so the copy in
a release is the committed file itself:

```bash
unzip -p app-universal-debug.apk lib/arm64-v8a/libcitrine_arti.so | sha256sum
sha256sum app/src/main/jniLibs/arm64-v8a/libcitrine_arti.so
```

AGP still strips the app's other native libraries with the NDK pinned in
`app/build.gradle.kts` (`ndkVersion`).

## Patched dependency: `saturating-time`

`Cargo.toml` replaces `saturating-time` 0.5.0 (used by `tor-netdoc`) with the
copy in `patches/saturating-time`. Upstream's `find_limit` halves its step down
to 1ns and only stops on `None`, but Windows' `SystemTime` ticks in 100ns, so a
1-99ns `checked_sub` returns the same time instead of `None` and the loop never
ends: Arti then hangs at 15% ("fetching a consensus") with one core pinned the
first time it parses a consensus. The patch is kept from Amber for parity; it
only matters on Windows, which Citrine does not target today. Drop it once a
fixed release is out.

## Updating Arti

1. Bump `arti-client` and `tor-rtcompat` in `Cargo.toml` (and `version`, which
   tracks the Arti release: arti 2.7.0 = crates 0.47.0).
2. `cargo update -p arti-client -p tor-rtcompat` to refresh `Cargo.lock`.
3. Rebuild every library as above.

## JNI surface

| Kotlin (`ArtiNative`)                                            | Purpose                                                                                                                                                                                                              |
| ---------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `initialize(dataDir, legacyHiddenServiceDir)`                | Create the Tor client (state + cache under `dataDir`) and start the directory download in the background. A kmp-tor-era hidden service directory passed in `legacyHiddenServiceDir` has its C tor identity key imported first, so the relay keeps its .onion address; the legacy dir is removed only after a successful import |
| `isBootstrapped()` / `bootstrapProgressPermille()`               | Readiness and download progress, polled by `TorManager`                                                                                                                                                              |
| `startSocksProxy(port, username, password)` / `stopSocksProxy()` | Bind / release the SOCKS5 listener; the client stays alive. The listener only accepts RFC 1929 username/password auth with the given per-listener secret, which `TorSocksAuthenticator` hands to Java's SOCKS client |
| `startOnionService(virtualPort, targetPort)` / `stopOnionService()` | Run the relay's onion service (nickname `citrine`, identity key persisted in the client keystore), forwarding virtual `virtualPort` to `127.0.0.1:targetPort`                                                    |
| `getOnionHostname()`                                             | The relay's `.onion` address, read from the keystore (empty until the identity key exists)                                                                                                                           |
| `destroy()`                                                      | Drop the client so the next `initialize` starts over (used by `retry`)                                                                                                                                               |
| `setLogCallback(cb)` / `getVersion()`                            | Logging into Citrine's log and version string                                                                                                                                                                        |

The JNI symbol names are derived from the Kotlin package and class
(`Java_com_greenart7c3_citrine_tor_ArtiNative_*`), so `ArtiNative` must keep
that package and name, in sync with `src/lib.rs`.
