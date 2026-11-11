# StremioBridge v6

StremioBridge adds one native CloudStream provider per profile. Browse catalogs from Xperience or AIOMetadata, open details from a preferred metadata addon, and pass playback links from AIOStreams to the CloudStream player. TorBox stays configured in AIOStreams. A combined addon can supply several resources.

Version 6 fixes the Android ICU regex crash when opening the source formatter. Literal closing braces and brackets are escaped, parser regexes initialize lazily, and unexpected preview/apply errors are contained in the editor. The editor remains available. Native ICU checks now run in CI before building; they reproduce the old failure and check the corrected expressions.

Version 5 restored upstream source formatting by default, including original icons, line breaks and text from AIOStreams/Mechfire, PenguPlay and other addons. Each playback addon now has an optional bridge formatter with an editable preset and live example preview. Catalog discovery adds all compatible catalog rows, with independent visibility switches, and inline reordering moves the existing Views without rebuilding the settings page. Cast-photo support, theme handling, metadata routing, exact episode IDs and native playback remain in place. No runtime dependencies were added.

## Install on Android

1. Open **CloudStream → Settings → Extensions → Add repository** and paste the following URL.

   ```text
   https://raw.githubusercontent.com/Mindplayer99/StremioBridge/main/dist/repo.json
   ```

2. Open the **StremioBridge** repository, tap **Install** beside the extension, then open its settings gear.
3. Select **New profile**, enter its name, then open **Addons → Add addons** and paste one configured install link per line. The final URL path ends in `/manifest.json` and may include query parameters. HTTP, HTTPS and `stremio://` links are supported. A configuration-required manifest offers a browser setup link. AIOStreams JSON settings exports belong in its own configurator.
4. Catalog rows appear automatically under **Home rows**. Turn off unwanted rows, set the **Metadata provider**, then tap **Save changes**. Select **StremioBridge · your profile name** as the CloudStream Home provider. Include the profile in global search if your search provider selection is restricted.

| Addon in a typical profile | Resource switches to enable |
|---|---|
| Xperience | Catalog for personalized rows. Enable its other roles if your configuration provides them. |
| AIOMetadata | Catalog and meta. Select it as the preferred metadata addon. |
| AIOStreams | Stream. Enable subtitles if advertised. Resolved TorBox and Usenet HTTP links use the same path. |
| Separate subtitle addon | Subtitles. It receives the movie ID or the exact episode video ID. |

## Settings controls

| Control | What it changes |
|---|---|
| **Save changes** | Commits this profile. Validates new addons; ordinary edits use already validated manifests. |
| **Refresh manifest** | Fetches the addon's advertised features and catalogs. A failed refresh keeps the previous snapshot. This does not test whether a video plays. |
| **Browse & search** | Lets the addon supply Home catalogs and search results. |
| **Details & episodes** | Lets it supply descriptions, artwork and episode lists. **Metadata provider** gives that compatible addon an eight-second preference window while fallbacks also load. |
| **Playback / Subtitles** | Lets it supply playable sources or subtitle files. Switches appear only for advertised resources. |
| **Home rows** | Selects visible catalogs. Edit a row's filters, make a filtered copy, rename it or change its position. |
| **Open addon settings** | Opens the addon's browser configurator when advertised. If setup gives you a new install link, paste it under **Edit install link**. |
| **Edit order** | Shows up/down controls directly on Addons or Home rows. Moves the same row Views in place, keeping scroll and remote focus. Done ordering exits this mode; Save changes commits the draft. Redundant ordering buttons inside item details have been removed. |
| **Row visibility switch** | Hides a Home row without losing its filters, title or position. Off rows use muted styling and remain editable. |
| **Use bridge formatter** | Per playback addon, off by default. Off passes through original name, description and title unchanged, with icons and newlines preserved. |
| **Edit bridge formatter** | Edits name/details templates, shows a live example and can reset the default preset. Apply updates the profile draft; Save changes commits it. |
| **Group by addon · debrid first** | Uses CloudStream native source priorities: recognized debrid groups first, then HTTP groups in addon order. Preserves real resolution values and native quality preferences within each group. |
| **AMOLED black** | Uses a pure-black background in bridge settings when the host theme is dark. Off follows the host surface; the light theme stays light. This does not change CloudStream movie pages or player appearance. |

Edits stay in a draft until **Save changes**. Back from the profile offers Save, Discard or Keep editing. **Backup & profile** contains encrypted export, duplication and deletion; restore creates a separate profile.

## Behavior and limitations

- Home titles use friendly type names without duplicate technical suffixes or display-only None/All values. Actual catalog types, filter parameters, row identities and custom titles are preserved. The add-only catalog picker omits unfiltered rows already on Home; existing filtered rows can still have an unfiltered counterpart.
- Home discovers all advertised catalogs of supported media types in manifest order, without the former three-per-addon/eight-total cap. Search-only catalogs are excluded; catalogs requiring filters start off until configured. Existing setups receive missing rows once when migrating from earlier versions. New addon/catalog discovery appends new rows, preserving existing titles, filters, order and off states. Ordinary Save does not resurrect deleted rows. The picker remains available for batch additions; row details edit filters, create filtered copies, rename or remove rows. Catalog pagination uses actual response lengths, rebuilds missing offsets and stops on empty or repeated results.
- Metadata routing checks media type and declared ID prefixes. Returned IDs or explicit external IDs must match. Series and anime retain their season numbers, specials and exact video IDs, including Kitsu episodes inside a TVDB page. Compatible providers query concurrently with a 30-second overall deadline. A healthy fallback can be used after the eight-second preference window, or immediately when the preferred provider fails. Without a healthy complete response, the bridge retains the best safe preview. AIOMetadata `app_extras.cast` photos and character names map to native CloudStream actors; missing photos remain name-only. There is no separate native crew section supplied by this bridge.
- Direct HTTP, HLS and DASH use native playback. Signed URLs, Referer and request headers pass through. The bridge requests fresh media URLs when CloudStream invokes its playback callback. An extensionless link without filename or MIME hints uses direct video.
- Original formatting is the default. The bridge no longer strips symbols, collapses whitespace, prefixes addon names, truncates labels or flattens descriptions. The native host can still apply its own font, layout and quality suffix. Enabling the bridge formatter for one addon leaves other addons unchanged. The default preset draws on the Mechfire/PenguPlay layout: resolution/cache/release/video information, separate audio and language/subtitle lines, service/provider/container, and size. It uses explicit stream data where supplied and recognizable filename/text tags as fallbacks. It omits unknown fields rather than inventing them. URLs, headers, subtitle files and playback routing are unchanged by formatting. Ready addon batches still arrive before slower addons; CloudStream can collapse duplicate URLs and cache links for 20 minutes.
- Mixed HTTP and debrid results are classified per stream, including TorBox-backed HdHub entries. Cache status is separate from service identity: structured cached flags take precedence, explicit cached/uncached text is respected, and recognizable debrid ⚡/⏳ marks are retained. A TorBox name alone is not proof of caching. There are no new cache probes or debrid-job requests.
- Native player grouping uses bridge-owned source names scoped to this profile and addon. Debrid is identified from explicit stream data or a recognizable service name; unknown streams stay in their addon group. Native quality profiles with Hide negative sources enabled retain their own ordering and exclusions. Turning grouping off restores normal native sorting for these links. Only bridge-owned source priorities are written or cleared; quality settings and other providers are untouched. The generic **Play mirror** action sorts by resolution and does not honor these priorities. The bridge does not fake resolutions to override it. If AIOStreams aggregates several HTTP providers into one addon, their individual ordering remains upstream; install separate configured manifests for separately ordered bridge groups.
- Standard Stremio HTTP stream addons can be added alongside AIOStreams, including HdHub and configured PenguPlay/111477 manifests when available. Paste each addon’s own configured install link, enable Playback, order the addons, and save. Nuvio JavaScript extensions require their own Stremio counterpart. HdHub’s supplied manifest was checked live (1.0.7, movie/series stream support); representative HTTP payload tests cover names, headers and HLS. Authenticated PenguPlay/111477 playback was not live-tested, and no generic working install URL is claimed.
- For several releases of the same episode, the bridge offers attached subtitles common to all playable mirrors and requests general subtitles by exact video ID. File-specific extras require a single mirror or shared hash and size. The legacy hash-path convention requires a known file hash. Independent subtitle addons have a 12-second deadline each, queried concurrently, so a stalled service cannot keep the bridge callback waiting for the previous 45 seconds.
- Native downloads support direct video and HLS. DASH is available for playback. Seeking and resuming depend on the media server. Cast requires a receiver-reachable URL. Header-dependent mirrors are omitted when casting.
- This release consumes resolved HTTP media. Raw torrents, magnet links, raw NZBs, archives, YouTube IDs, external webpages and DRM-specific sources are omitted. The inspected CloudStream host contains an opt-in torrent server. The bridge does not start torrent or debrid jobs. Xperience folders, Nuvio JS extensions, watch events and account synchronization are outside its supported protocol surface.
- CloudStream requires a dub-status bucket for anime episodes. The bridge uses the Subbed bucket for navigation. Actual audio availability comes from the selected stream.

## Optional formatter templates

Leave **Use bridge formatter** off for AIOStreams/Mechfire or PenguPlay when their original style is already configured. Turn it on for an addon such as HdHub if you want the bridge preset. It can be turned off again without changing the addon’s own configuration.

Templates use `{field}` and optional blocks such as `[♬ {audio}]`. A block is omitted if any referenced field is missing; empty lines are omitted. Name and details support multiple lines. Invalid edits remain in the editor, and invalid imported templates safely fall back to original text.

Available fields: `{addon}`, `{resolution}`, `{cache}`, `{transport}`, `{quality}`, `{codec}`, `{visual}`, `{bitrate}`, `{audio}`, `{channels}`, `{languages}`, `{subtitles}`, `{service}`, `{provider}`, `{container}`, `{size}`, `{filename}`, `{fallback}`, `{originalName}`, `{originalDescription}`, `{originalTitle}`. These are simple local templates; AIOStreams macros are not executed. The preview is labeled example data and makes no network request.

## Profiles and privacy

Save validates enabled addons that have no validated manifest. Existing roles, row order and filters save using the last working snapshot. Refresh is a separate action. Successful refreshes update the draft, and failures retain the previous snapshot with an error message. Save changes commits the result. Profiles, configured URLs and item references use Android app-private no-backup storage. CloudStream history receives opaque routing keys. Deleting a profile removes its references.

**Backup & profile → Export encrypted backup** preserves addon links, switches, priority and selected rows using a passphrase on Android 8+. The format uses PBKDF2-HMAC-SHA256 and AES-256-GCM. Copy the encrypted text and keep its passphrase separately. **Restore encrypted backup** opens it as a separate profile. Backups created by v1 remain compatible.

The bridge keeps signed playback links out of its persistent records. CloudStream, its downloader, subtitle handler and any external player receive the URLs needed to play. Host debug logs and exported downloads can contain sensitive data. Artwork URLs containing recognized credential parameters may be omitted.

## Build and distribute

The foundation is [TPBBridge commit 5d2fdf7](https://github.com/Mindplayer99/TPBBridge/commit/5d2fdf7a94f94e494296f0586f620797117e336e). This independent project uses its Gradle wrapper, build conventions and provider-registration pattern. Bridge source is licensed GPL-3.0-or-later. The Gradle wrapper retains its upstream licensing.

Build with JDK 17, Android SDK platform 35 and build-tools 34.0.0. The wrapper uses Gradle 8.12 with AGP 8.7.3, Kotlin 2.4.10 and CloudStream Gradle plugin `81b1d424d2`. R8/D8 9.1.57 handles Kotlin 2.4 metadata. The rolling CloudStream pre-release SDK used for this build has SHA-256 `8ea67e87d5e8c8169ce0f38c6e3042c4fb625b2a949de7e6d9eea2b9fd2e65e5`. The [inspected host source](https://github.com/recloudstream/cloudstream/tree/5d66bbeaf3b35c0c18ad2e0b9ce6b0aea4f5241c) contains its source sorting, link cache and media playback paths.

```sh
python3 scripts/check_formatter_icu.py
./gradlew :StremioBridge:testDebugUnitTest :StremioBridge:make makePluginsJson
python3 scripts/package_release.py
```

The package script verifies the DEX archive, entry point, size and SHA-256. It writes `dist/StremioBridge.cs3`, `dist/plugins.json` and `dist/repo.json`. The hosted repository uses those three files on the main branch. For a local install, copy the compiled file to `/sdcard/Cloudstream3/plugins/StremioBridge.cs3` and fully restart CloudStream. Avoid installing a second copy through another method.

For another device-accessible host, run `python3 scripts/package_release.py --base-url https://YOUR-HOST/YOUR-DIRECTORY`, serve all three files there, and enter the hosted `repo.json` URL in CloudStream.

## Validation

All 45 tests pass for v6, with a separate native ICU gate that compiles 31 formatter patterns and checks four capture cases. The ICU check reproduces v5’s `U_REGEX_RULE_SYNTAX` at character 25 before validating the corrected patterns. Desktop JVM/Robolectric regex success alone did not establish Android compatibility; this was the gap in v5’s validation. The suite validates formatting preservation, per-addon opt-in and templates, mixed cache/service states, combined and separate metadata/subtitle routes, catalog migration/visibility, and in-place reordering in addition to the existing protocol, provider and visual journeys. Coverage includes protocol, provider, metadata timing/identity, cast data, source formatting/priorities, profile compatibility, settings, and visual journeys covering light/dark themes, 320dp phones, landscape, long labels, empty lists, multiple profiles, selected and disabled states, native Back, focus, scrolling, validation, dialog actions, draft preservation, simulated keyboard resize, AMOLED persistence, inline ordering, muted off rows, the formatter editor, and remote-focus retention. Tests also verify that arrow clicks retain the actual row and ScrollView objects. Actual Robolectric native Android renders are visually reviewed; these are not physical-device screenshots. Palette tests check text contrast of at least 4.5:1 across normal and interactive surfaces, including colors extracted from the CloudStream APK. The compiled extension is checked against the official Blue stable 4.9.1 and Red pre-release APKs for its referenced host APIs using `python3 scripts/check_host_api.py dist/StremioBridge.cs3 stable.apk pre.apk`. This is a DEX signature check, not end-to-end host testing. Real keyboard behavior, device installation, media decoding, casting, downloading and account playback require on-device verification.

References include the [Stremio manifest rules](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/manifest.md), [subtitle request specification](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/requests/defineSubtitlesHandler.md), [AIOStreams](https://docs.aiostreams.viren070.me/), [AIOMetadata](https://github.com/cedya77/aiometadata/tree/dev) and the [Xperience guide](https://xperience-app.com/guide).
