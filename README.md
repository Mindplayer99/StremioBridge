# StremioBridge v1

StremioBridge adds one native CloudStream provider per profile. Browse catalogs from Xperience or AIOMetadata, open details from a preferred metadata addon, and pass playback links from AIOStreams to the CloudStream player. TorBox stays configured in AIOStreams. A combined addon can supply several resources.

## Install on Android

1. Open **CloudStream → Settings → Extensions → Add repository** and paste the following URL.

   ```text
   https://raw.githubusercontent.com/Mindplayer99/StremioBridge/main/dist/repo.json
   ```

2. Open the **StremioBridge** repository, tap **Install** beside the extension, then open its settings gear.
3. Select **Create profile**, enter its name and paste one configured install link per line. The final URL path ends in `/manifest.json` and may include query parameters. HTTP, HTTPS and `stremio://` links are supported. A configuration-required manifest offers a browser setup link. AIOStreams JSON settings exports belong in its own configurator.
4. Choose **Home rows**, then **Preferred metadata addon** and **Save + refresh**. Select **StremioBridge · your profile name** as the CloudStream Home provider. Include the profile in global search if your search provider selection is restricted.

| Addon in a typical profile | Resource switches to enable |
|---|---|
| Xperience | Catalog for personalized rows. Enable its other roles if your configuration provides them. |
| AIOMetadata | Catalog and meta. Select it as the preferred metadata addon. |
| AIOStreams | Stream. Enable subtitles if advertised. Resolved TorBox and Usenet HTTP links use the same path. |
| Separate subtitle addon | Subtitles. It receives the movie ID or the exact episode video ID. |

## Behavior and limitations

- Home initially selects up to three compatible rows per addon and eight total. The searchable picker can add additional rows, including fixed filters. Search-only and required-filter catalogs need the appropriate parameters. Catalog pagination uses actual response lengths, rebuilds missing offsets and stops on empty or repeated results.
- Metadata routing checks media type and declared ID prefixes. Returned IDs or explicit external IDs must match. Series and anime retain their season numbers, specials and exact video IDs, including Kitsu episodes inside a TVDB page. The bridge can fall back to catalog preview data.
- Direct HTTP, HLS and DASH use native playback. Signed URLs, Referer and request headers pass through. The bridge requests fresh media URLs when CloudStream invokes its playback callback. An extensionless link without filename or MIME hints uses direct video.
- Source labels and upstream order are preserved on emission. CloudStream applies its own quality/source profile and collapses identical URL strings, including variants that differ only in headers. The host can reuse its link cache for 20 minutes. Reload sources if a signed link expires.
- For several releases of the same episode, the bridge offers attached subtitles common to all playable mirrors and requests general subtitles by exact video ID. File-specific extras require a single mirror or shared hash and size. The legacy hash-path convention requires a known file hash.
- Native downloads support direct video and HLS. DASH is available for playback. Seeking and resuming depend on the media server. Cast requires a receiver-reachable URL. Header-dependent mirrors are omitted when casting.
- This release consumes resolved HTTP media. Raw torrents, magnet links, raw NZBs, archives, YouTube IDs, external webpages and DRM-specific sources are omitted. The inspected CloudStream host contains an opt-in torrent server. The bridge does not start torrent or debrid jobs. Xperience folders, Nuvio JS extensions, watch events and account synchronization are outside its supported protocol surface.
- CloudStream requires a dub-status bucket for anime episodes. The bridge uses the Subbed bucket for navigation. Actual audio availability comes from the selected stream.

## Profiles and privacy

Save validates every enabled manifest before committing the profile. Disable an offline addon in the draft to save other changes. Profiles, configured URLs and item references use Android app-private no-backup storage. CloudStream history receives opaque routing keys. Deleting a profile removes its references.

**Advanced → Copy encrypted full backup** preserves addon links, switches, priority and selected rows using a passphrase on Android 8+. The format uses PBKDF2-HMAC-SHA256 and AES-256-GCM. **Copy safe template** contains slot order and switches. Restore as a separate profile and re-enter links when using a template.

The bridge keeps signed playback links out of its persistent records. CloudStream, its downloader, subtitle handler and any external player receive the URLs needed to play. Host debug logs and exported downloads can contain sensitive data. Artwork URLs containing recognized credential parameters may be omitted.

## Build and distribute

The foundation is [TPBBridge commit 5d2fdf7](https://github.com/Mindplayer99/TPBBridge/commit/5d2fdf7a94f94e494296f0586f620797117e336e). This independent project uses its Gradle wrapper, build conventions and provider-registration pattern. Bridge source is licensed GPL-3.0-or-later. The Gradle wrapper retains its upstream licensing.

Build with JDK 17, Android SDK platform 35 and build-tools 34.0.0. The wrapper uses Gradle 8.12 with AGP 8.7.3, Kotlin 2.4.10 and CloudStream Gradle plugin `81b1d424d2`. R8/D8 9.1.57 handles Kotlin 2.4 metadata. The rolling CloudStream pre-release SDK used for this build has SHA-256 `8ea67e87d5e8c8169ce0f38c6e3042c4fb625b2a949de7e6d9eea2b9fd2e65e5`. The [inspected host source](https://github.com/recloudstream/cloudstream/tree/5d66bbeaf3b35c0c18ad2e0b9ce6b0aea4f5241c) contains its source sorting, link cache and media playback paths.

```sh
./gradlew :StremioBridge:testDebugUnitTest :StremioBridge:make makePluginsJson
python3 scripts/package_release.py
```

The package script verifies the DEX archive, entry point, size and SHA-256. It writes `dist/StremioBridge.cs3`, `dist/plugins.json` and `dist/repo.json`. The hosted repository uses those three files on the main branch. For a local install, copy the compiled file to `/sdcard/Cloudstream3/plugins/StremioBridge.cs3` and fully restart CloudStream. Avoid installing a second copy through another method.

For another device-accessible host, run `python3 scripts/package_release.py --base-url https://YOUR-HOST/YOUR-DIRECTORY`, serve all three files there, and enter the hosted `repo.json` URL in CloudStream.

## Validation

All 13 automated tests passed for this build. The test suite exercises URL encoding and redirects, resource routing, partial failures, pagination, movie and episode identities, headers, ordering, subtitle scoping, encrypted backup/restore and native settings. A Robolectric journey runs the published CloudStream Home, search, detail and link APIs against local synthetic addons on simulated Android 14. The compiled extension was also checked against the official Blue stable 4.9.1 APK for the host APIs it references. Device installation, media decoding, casting, downloading and real account playback require on-device verification.

References include the [Stremio manifest rules](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/manifest.md), [subtitle request specification](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/requests/defineSubtitlesHandler.md), [AIOStreams](https://docs.aiostreams.viren070.me/), [AIOMetadata](https://github.com/cedya77/aiometadata/tree/dev) and the [Xperience guide](https://xperience-app.com/guide).
