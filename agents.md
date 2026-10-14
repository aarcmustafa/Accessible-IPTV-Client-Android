# Accessible IPTV Client for Android - agent notes

Android port of the desktop Accessible IPTV Client (serrebidev/Accessible-IPTV-Client).
One APK for phones, tablets and Android TV, Android 10 (API 29) and up.

## Build

- `gradlew testDebugUnitTest assembleDebug` - JVM tests + debug APK at `app/build/outputs/apk/debug/`.
- AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.20, Gradle 9.8.0, JDK 21, compile/target SDK 37, min SDK 29.
- `local.properties` needs `sdk.dir` with forward slashes (`C\:/...`); backslashes break Gradle with
  "filename, directory name, or volume label syntax is incorrect".
- Release is signed with the owner's debug keystore (same key as Beluga, cert SHA-256 e686d194...10ca) so in-app updates install over each other. A build machine without that key makes APKs that cannot update installed copies.
- Updates: `Updater.kt` reads GitHub Releases of serrebidev/Accessible-IPTV-Client-Android, takes the .apk asset, checks GitHub's sha256 digest, installs via PackageInstaller. Tag = `v` + versionName.

## Accessibility rules (non-negotiable)

The user is blind (TalkBack / Backtalk on Android, NVDA on desktop). Follow
cvs-health/android-view-accessibility-techniques.

- Android Views only, no Compose, no custom-drawn widgets.
- Form fields: visible TextView label with `android:labelFor`. A lone search/filter field uses its hint ("Search", "Filter") as its name, no extra label. Errors: `EditText.error` + move accessibility focus to it.
- Wording is blunt: one or two words on buttons, short status lines, no explanations in the UI. Explanations go in Help.
- List rows are ONE focus stop (`item_row.xml`, children `importantForAccessibility="no"`), speech set in code.
  Secondary actions go through `bindRow()` in `Ui.kt`: TalkBack custom actions + long-press/Menu-key popup. Never add per-row buttons.
- Section titles use `style="@style/Heading"` / `.heading()` so heading navigation works.
- Status changes go to a `accessibilityLiveRegion="polite"` status TextView; only milestones, no per-keystroke churn.
- Real buttons, always visible; no auto-hiding player overlay, no icon-only controls.
- D-pad: everything focusable, `row_bg.xml` draws a high-contrast focus ring for TV.

## Layout

- `App.kt` - process state: channel list (cached per source as JSON in filesDir), guide.
- `Epg.kt` - SQLite guide. Import streams XMLTV (gzip sniffed), keeps only guide channels a playlist channel matches (tvg-id, then `Xmltv.nameKey`), within the catch-up window to `epgDaysAhead`.
- `Xtream.kt` - player_api. Live URLs use `.ts`: Dispatcharr answers `.m3u8` with HTTP 500.
- `Catchup.kt`, `AudioTracks.kt`, `M3uParser.kt`, `Xmltv.kt` - pure Kotlin ports of desktop rules; covered by `CoreTest`.
- `Recorder.kt` - dataSync foreground service writes `.ts` to MediaStore `Movies/AccessibleIPTV`; schedules via exact alarms.

- `Stalker.kt` - MAG handshake; channel url is `stalker:<cmd>`, turned into a link per play by `App.resolveUrl` (never play `ch.url` directly; use `Player.open`).
- Sections live / movies / series (`App.load(section)`); movies and series load only when opened.
  Big lists MUST stream: `Net.forEachObject` + `JsonReader` cache reader. Reading get_vod_streams whole (130 MB) crashed with OOM. `largeHeap` is on.
- Cast: `CastPlayer.Builder(ctx).setLocalPlayer(exo)` wraps the local player and transfers by itself; skipped when Play services are missing.

- Player traps: keep `FLAG_DETECT_ACCESS_UNITS` on the TS extractor (re-encoded IPTV H.264 has no AUD NALs -> audio only without it). Live streams arrive at real time: keep the short rebuffer target in `DefaultLoadControl`.
- Edge to edge: `App.keepContentClearOfSystemBars()` pads every activity's content by bars/cutout/IME. Do not remove; Samsung's taskbar covered the player buttons.

## Not yet ported

Translations, Stalker VOD, an in-app updater.
