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
