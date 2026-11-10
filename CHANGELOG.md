## Citrine 3.2.2

- "Download your events" now shows live progress ("18,800 events · 2/3 relays") instead of sitting on the first relay
- Fixed event download hanging forever when a relay stops answering
- Event import is about 3x faster: tag and event inserts no longer resolve a row id per row
- Relay connections are dropped and redialed when the network changes (Wi-Fi ↔ mobile, VPN up/down, back online), matching Amber
- Oversized events are now rejected: the max event content size defaults to 1 MiB, is advertised in NIP-11 and can be changed in the relay info settings (0 disables it)
- Outbound frames are written into a single buffer and the outbound queue has a byte budget, further reducing memory pressure
- Updated dependencies
- Updated translations

Download it with [Zapstore](https://zapstore.dev/apps/com.greenart7c3.citrine), [Obtainium](https://github.com/ImranR98/Obtainium), [f-droid](https://f-droid.org/packages/com.greenart7c3.citrine)  or download it directly in the [releases page
](https://github.com/greenart7c3/Citrine/releases/tag/v3.2.2)

If you like my work consider making a [donation](https://greenart7c3.com)

## Verifying the release

In order to verify the release, you'll need to have `gpg` or `gpg2` installed on your system. Once you've obtained a copy (and hopefully verified that as well), you'll first need to import the keys that have signed this release if you haven't done so already:

``` bash
gpg --keyserver hkps://keys.openpgp.org --recv-keys 44F0AAEB77F373747E3D5444885822EED3A26A6D
```

Once you have his PGP key you can verify the release (assuming `manifest-v3.2.2.txt` and `manifest-v3.2.2.txt.sig` are in the current directory) with:

``` bash
gpg --verify manifest-v3.2.2.txt.sig manifest-v3.2.2.txt
```

You should see the following if the verification was successful:

``` bash
gpg: Signature made Fri 13 Sep 2024 08:06:52 AM -03
gpg:                using RSA key 44F0AAEB77F373747E3D5444885822EED3A26A6D
gpg: Good signature from "greenart7c3 <greenart7c3@proton.me>"
```

That will verify the signature on the main manifest page which ensures integrity and authenticity of the binaries you've downloaded locally. Next, depending on your operating system you should then re-calculate the sha256 sum of the binary, and compare that with the following hashes:

``` bash
cat manifest-v3.2.2.txt
```

One can use the `shasum -a 256 <file name here>` tool in order to re-compute the sha256 hash of the target binary for your operating system. The produced hash should be compared with the hashes listed above and they should match exactly.

## Citrine 3.2.1

- Fixed the events feed showing every event dated "21 Jan": event timestamps (epoch seconds) were formatted as if they were milliseconds

Download it with [Zapstore](https://zapstore.dev/apps/com.greenart7c3.citrine), [Obtainium](https://github.com/ImranR98/Obtainium), [f-droid](https://f-droid.org/packages/com.greenart7c3.citrine)  or download it directly in the [releases page
](https://github.com/greenart7c3/Citrine/releases/tag/v3.2.1)

If you like my work consider making a [donation](https://greenart7c3.com)

## Verifying the release

In order to verify the release, you'll need to have `gpg` or `gpg2` installed on your system. Once you've obtained a copy (and hopefully verified that as well), you'll first need to import the keys that have signed this release if you haven't done so already:

``` bash
gpg --keyserver hkps://keys.openpgp.org --recv-keys 44F0AAEB77F373747E3D5444885822EED3A26A6D
```

Once you have his PGP key you can verify the release (assuming `manifest-v3.2.1.txt` and `manifest-v3.2.1.txt.sig` are in the current directory) with:

``` bash
gpg --verify manifest-v3.2.1.txt.sig manifest-v3.2.1.txt
```

You should see the following if the verification was successful:

``` bash
gpg: Signature made Fri 13 Sep 2024 08:06:52 AM -03
gpg:                using RSA key 44F0AAEB77F373747E3D5444885822EED3A26A6D
gpg: Good signature from "greenart7c3 <greenart7c3@proton.me>"
```

That will verify the signature on the main manifest page which ensures integrity and authenticity of the binaries you've downloaded locally. Next, depending on your operating system you should then re-calculate the sha256 sum of the binary, and compare that with the following hashes:

``` bash
cat manifest-v3.2.1.txt
```

One can use the `shasum -a 256 <file name here>` tool in order to re-compute the sha256 hash of the target binary for your operating system. The produced hash should be compared with the hashes listed above and they should match exactly.

## Older versions

Release notes for previous versions live in [docs/changelogs](docs/changelogs/README.md).
