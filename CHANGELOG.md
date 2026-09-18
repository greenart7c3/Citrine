## Citrine 3.2.0

- Fixed silent crashes (OOM) with the relay aggregator enabled: REQ queries now stream results in batches instead of materializing the full match set in memory
- Bounded relay aggregator memory: capped outbound relays at 200 and bounded caches
- Added an option to hide the graph in the show events page
- Out-of-memory errors are now logged to the in-app log screen so they can be diagnosed
- Updated dependencies (including Quartz 1.15.2)
- Updated translations

Download it with [Zapstore](https://zapstore.dev/apps/com.greenart7c3.citrine), [Obtainium](https://github.com/ImranR98/Obtainium), [f-droid](https://f-droid.org/packages/com.greenart7c3.citrine)  or download it directly in the [releases page
](https://github.com/greenart7c3/Citrine/releases/tag/v3.2.0)

If you like my work consider making a [donation](https://greenart7c3.com)

## Verifying the release

In order to verify the release, you'll need to have `gpg` or `gpg2` installed on your system. Once you've obtained a copy (and hopefully verified that as well), you'll first need to import the keys that have signed this release if you haven't done so already:

``` bash
gpg --keyserver hkps://keys.openpgp.org --recv-keys 44F0AAEB77F373747E3D5444885822EED3A26A6D
```

Once you have his PGP key you can verify the release (assuming `manifest-v3.2.0.txt` and `manifest-v3.2.0.txt.sig` are in the current directory) with:

``` bash
gpg --verify manifest-v3.2.0.txt.sig manifest-v3.2.0.txt
```

You should see the following if the verification was successful:

``` bash
gpg: Signature made Fri 13 Sep 2024 08:06:52 AM -03
gpg:                using RSA key 44F0AAEB77F373747E3D5444885822EED3A26A6D
gpg: Good signature from "greenart7c3 <greenart7c3@proton.me>"
```

That will verify the signature on the main manifest page which ensures integrity and authenticity of the binaries you've downloaded locally. Next, depending on your operating system you should then re-calculate the sha256 sum of the binary, and compare that with the following hashes:

``` bash
cat manifest-v3.2.0.txt
```

One can use the `shasum -a 256 <file name here>` tool in order to re-compute the `sha256` hash of the target binary for your operating system. The produced hash should be compared with the hashes listed above and they should match exactly.

## Citrine 3.1.1

- Fixed an NIP-42 auth bypass where kind-22242 AUTH events were added to the connection without signature verification
- Scoped REQ subscription ids per connection so a duplicate id from one socket can no longer clobber another's subscription
- NIP-45: COUNT filters now aggregate into a single count response without EOSE and no longer register live subscriptions
- Fixed NIP-42 access control denying authenticated users filtering kinds-less {"#p":[<own pubkey>]}
- Replaceable events now tie-break on the lowest id at equal created_at (NIP-01)
- ids and authors filters are now exact 64-hex matches (NIP-01)
- Standardized OK message prefixes for duplicate, deleted, and blocked events (NIP-01/09)
- NIP-11: relay info document is now built quote-safe and advertises Access-Control-Allow-Headers; dropped deprecated NIP-04
- NIP-86: added unbanpubkey and unallowpubkey; allowpubkey no longer unbans as a side effect

Download it with [Zapstore](https://zapstore.dev/apps/com.greenart7c3.citrine), [Obtainium](https://github.com/ImranR98/Obtainium), [f-droid](https://f-droid.org/packages/com.greenart7c3.citrine)  or download it directly in the [releases page
](https://github.com/greenart7c3/Citrine/releases/tag/v3.1.1)

If you like my work consider making a [donation](https://greenart7c3.com)

## Verifying the release

In order to verify the release, you'll need to have `gpg` or `gpg2` installed on your system. Once you've obtained a copy (and hopefully verified that as well), you'll first need to import the keys that have signed this release if you haven't done so already:

``` bash
gpg --keyserver hkps://keys.openpgp.org --recv-keys 44F0AAEB77F373747E3D5444885822EED3A26A6D
```

Once you have his PGP key you can verify the release (assuming `manifest-v3.1.1.txt` and `manifest-v3.1.1.txt.sig` are in the current directory) with:

``` bash
gpg --verify manifest-v3.1.1.txt.sig manifest-v3.1.1.txt
```

You should see the following if the verification was successful:

``` bash
gpg: Signature made Fri 13 Sep 2024 08:06:52 AM -03
gpg:                using RSA key 44F0AAEB77F373747E3D5444885822EED3A26A6D
gpg: Good signature from "greenart7c3 <greenart7c3@proton.me>"
```

That will verify the signature on the main manifest page which ensures integrity and authenticity of the binaries you've downloaded locally. Next, depending on your operating system you should then re-calculate the sha256 sum of the binary, and compare that with the following hashes:

``` bash
cat manifest-v3.1.1.txt
```

One can use the `shasum -a 256 <file name here>` tool in order to re-compute the `sha256` hash of the target binary for your operating system. The produced hash should be compared with the hashes listed above and they should match exactly.

## Citrine 3.1.0

- Added NIP-29 relay-based groups support
- Added NIP-86 relay management API and settings screen
- Added a tool to rebroadcast stored events to selected relays
- Offer to purge stored events when banning a pubkey locally
- Added configurable REJECTED_KINDS to block non-publishable artifact kinds
- Added import-from-lists picker to access control settings
- Added nsite (NIP-5A) support to Web Clients
- Modernized the browse nsites list: website icons, search bar, sort by last update, and install progress
- Added a setting to choose relays for fetching nsites, with dedicated defaults (nsite.run, nos.lol, nostr.land)
- Show nsite description and restyle nsite lists as cards with an author header
- Performance improvements on the WebSocket and REQ query hot paths
- Removed permessage-deflate extension from the WebSocket server
- Fixed Tor not starting/stopping when the expose-via-Tor setting changes
- Always stop Tor when the relay service is destroyed
- Show the home screen without waiting for the service to bind
- Subscribe to all kinds when the aggregator kinds list is empty
- Move the aggregator kinds reset button below the text field
- Persist logs to a local database and gate logcat to debug builds
- Updated Gradle, Kotlin, and project dependencies
- Updated translations

Download it with [Zapstore](https://zapstore.dev/apps/com.greenart7c3.citrine), [Obtainium](https://github.com/ImranR98/Obtainium), [f-droid](https://f-droid.org/packages/com.greenart7c3.citrine)  or download it directly in the [releases page
](https://github.com/greenart7c3/Citrine/releases/tag/v3.1.0)

If you like my work consider making a [donation](https://greenart7c3.com)

## Verifying the release

In order to verify the release, you'll need to have `gpg` or `gpg2` installed on your system. Once you've obtained a copy (and hopefully verified that as well), you'll first need to import the keys that have signed this release if you haven't done so already:

``` bash
gpg --keyserver hkps://keys.openpgp.org --recv-keys 44F0AAEB77F373747E3D5444885822EED3A26A6D
```

Once you have his PGP key you can verify the release (assuming `manifest-v3.1.0.txt` and `manifest-v3.1.0.txt.sig` are in the current directory) with:

``` bash
gpg --verify manifest-v3.1.0.txt.sig manifest-v3.1.0.txt
```

You should see the following if the verification was successful:

``` bash
gpg: Signature made Fri 13 Sep 2024 08:06:52 AM -03
gpg:                using RSA key 44F0AAEB77F373747E3D5444885822EED3A26A6D
gpg: Good signature from "greenart7c3 <greenart7c3@proton.me>"
```

That will verify the signature on the main manifest page which ensures integrity and authenticity of the binaries you've downloaded locally. Next, depending on your operating system you should then re-calculate the sha256 sum of the binary, and compare that with the following hashes:

``` bash
cat manifest-v3.1.0.txt
```

One can use the `shasum -a 256 <file name here>` tool in order to re-compute the `sha256` hash of the target binary for your operating system. The produced hash should be compared with the hashes listed above and they should match exactly.

## Citrine 3.0.1

- Fixed a crash when unregistering an unregistered Pokey receiver

Download it with [Zapstore](https://zapstore.dev/apps/com.greenart7c3.citrine), [Obtainium](https://github.com/ImranR98/Obtainium), [f-droid](https://f-droid.org/packages/com.greenart7c3.citrine)  or download it directly in the [releases page
](https://github.com/greenart7c3/Citrine/releases/tag/v3.0.1)

If you like my work consider making a [donation](https://greenart7c3.com)

## Verifying the release

In order to verify the release, you'll need to have `gpg` or `gpg2` installed on your system. Once you've obtained a copy (and hopefully verified that as well), you'll first need to import the keys that have signed this release if you haven't done so already:

``` bash
gpg --keyserver hkps://keys.openpgp.org --recv-keys 44F0AAEB77F373747E3D5444885822EED3A26A6D
```

Once you have his PGP key you can verify the release (assuming `manifest-v3.0.1.txt` and `manifest-v3.0.1.txt.sig` are in the current directory) with:

``` bash
gpg --verify manifest-v3.0.1.txt.sig manifest-v3.0.1.txt
```

You should see the following if the verification was successful:

``` bash
gpg: Signature made Fri 13 Sep 2024 08:06:52 AM -03
gpg:                using RSA key 44F0AAEB77F373747E3D5444885822EED3A26A6D
gpg: Good signature from "greenart7c3 <greenart7c3@proton.me>"
```

That will verify the signature on the main manifest page which ensures integrity and authenticity of the binaries you've downloaded locally. Next, depending on your operating system you should then re-calculate the sha256 sum of the binary, and compare that with the following hashes:

``` bash
cat manifest-v3.0.1.txt
```

One can use the `shasum -a 256 <file name here>` tool in order to re-compute the `sha256` hash of the target binary for your operating system. The produced hash should be compared with the hashes listed above and they should match exactly.

## Citrine 3.0.0

- Added Negentropy (NIP-77) support
- Added external signer and NIP-42 AUTH support to the relay aggregator
- Honor NIP-51 mute lists in the relay aggregator
- Cap the relay aggregator at 3 relays per author with configurable source and indexer relays
- Reuse cached follow/mute/metadata on aggregator restart and network change
- Pause the relay aggregator on limited/restricted networks
- Filter onion relay URLs from the aggregator when the outbound proxy is disabled
- Reject reposts that embed protected events
- Show local, Wi-Fi, and Tor addresses with copy actions on the home screen
- Redesigned settings screen split into a hub with category sub-screens
- Preserve mute lists from age-based deletion by default
- Added option to preserve specific event kinds from age-based deletion
- Made the ephemeral mute response a setting, defaulting to off
- Reduced relay aggregator battery drain
- Performance improvements on the relay hot path and event-receive path
- Fixed WebSocket connections that sometimes don't close
- Skip duplicate foreground service notifications
- Dedupe AUTH challenges so external signers are not re-prompted
- Updated Gradle and refreshed library dependencies
- Updated translations

Download it with [Zapstore](https://zapstore.dev/apps/com.greenart7c3.citrine), [Obtainium](https://github.com/ImranR98/Obtainium), [f-droid](https://f-droid.org/packages/com.greenart7c3.citrine)  or download it directly in the [releases page
](https://github.com/greenart7c3/Citrine/releases/tag/v3.0.0)

If you like my work consider making a [donation](https://greenart7c3.com)

## Verifying the release

In order to verify the release, you'll need to have `gpg` or `gpg2` installed on your system. Once you've obtained a copy (and hopefully verified that as well), you'll first need to import the keys that have signed this release if you haven't done so already:

``` bash
gpg --keyserver hkps://keys.openpgp.org --recv-keys 44F0AAEB77F373747E3D5444885822EED3A26A6D
```

Once you have his PGP key you can verify the release (assuming `manifest-v3.0.0.txt` and `manifest-v3.0.0.txt.sig` are in the current directory) with:

``` bash
gpg --verify manifest-v3.0.0.txt.sig manifest-v3.0.0.txt
```

You should see the following if the verification was successful:

``` bash
gpg: Signature made Fri 13 Sep 2024 08:06:52 AM -03
gpg:                using RSA key 44F0AAEB77F373747E3D5444885822EED3A26A6D
gpg: Good signature from "greenart7c3 <greenart7c3@proton.me>"
```

That will verify the signature on the main manifest page which ensures integrity and authenticity of the binaries you've downloaded locally. Next, depending on your operating system you should then re-calculate the sha256 sum of the binary, and compare that with the following hashes:

``` bash
cat manifest-v3.0.0.txt
```

One can use the `shasum -a 256 <file name here>` tool in order to re-compute the `sha256` hash of the target binary for your operating system. The produced hash should be compared with the hashes listed above and they should match exactly.
