# Accessible IPTV Client for Android - agent notes

Android port of the desktop Accessible IPTV Client (serrebidev/Accessible-IPTV-Client).
One APK for phones, tablets and Android TV, Android 10 (API 29) and up.

## Build

- `gradlew testDebugUnitTest assembleDebug` - JVM tests + debug APK at `app/build/outputs/apk/debug/`.
  Run it before every release; all tests must pass.
- Tests (`app/src/test`): `ParsingTest`/`CoreTest` pure JVM; `ProviderTest`, `GuideTest`, `AppTest`, `ScreenTest`
  run under Robolectric (SDK 36, `robolectric.properties`) against `FakeServer` (JDK `HttpServer` on 127.0.0.1).
  JDK 21 needs `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` (set in `testOptions`).
  A new provider quirk or user-visible bug gets a test here first.
- `lintDebug` fails on media3 `UnstableApi` opt-in (old, not enforced by the build). Any other lint error is new.
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
- `Catchup.kt`, `AudioTracks.kt`, `M3uParser.kt`, `Xmltv.kt` - pure Kotlin ports of desktop rules; covered by `ParsingTest`.
- M3U channel id = `tvg-id|name` (+`#n` for repeats), never list position: favourites, remembered tracks and schedules key on it.
  `#EXTVLCOPT`/`http-user-agent` headers ride in the URL's `|Key=Value` tail; `x-tvg-url` fills a blank guide URL.
- `Recorder.kt` - specialUse foreground service writes `.ts` to MediaStore `Movies/AccessibleIPTV`; reconnects drops until the end time.
  Not dataSync: Android 15 stops dataSync after 6 h a day. `USE_EXACT_ALARM` (sideloaded, no Play policy) keeps exact alarms on
  Android 14+, where `SCHEDULE_EXACT_ALARM` is denied by default; without an exact alarm the background service start is refused.
- Guide import builds `prog_new`/`gch_new` and swaps on success: a failed download keeps the old guide.
- Help text is `res/raw/help.md`, not a string resource: aapt folds string newlines into spaces.

- `Stalker.kt` - MAG handshake; channel url is `stalker:<cmd>`, turned into a link per play by `App.resolveUrl` (never play `ch.url` directly; use `Player.open`).
- Sections live / movies / series (`App.load(section)`); movies and series load only when opened.
  Big lists MUST stream: `Net.forEachObject` + `JsonReader` cache reader. Reading get_vod_streams whole (130 MB) crashed with OOM. `largeHeap` is on.
- Cast: `CastPlayer.Builder(ctx).setLocalPlayer(exo)` wraps the local player and transfers by itself; skipped when Play services are missing.

- Player status lines go through `say(text, priority)`: spoken only up to Settings, Announcements (desktop levels 1-3).
- Debug builds log buffer depth every 2 s (tag AiptvPlayer, `beat`): tells a slow feed from a delivery gap.
- Player: live channels reconnect like the desktop (`Reconnect`: 0.4, 2, 4, 8, 8, 8 s, reset after 2 min); catch-up and files do not.
- Player traps: keep `FLAG_DETECT_ACCESS_UNITS` on the TS extractor (re-encoded IPTV H.264 has no AUD NALs -> audio only without it). Live streams arrive at real time: keep the short rebuffer target in `DefaultLoadControl`.
- Edge to edge: `App.keepContentClearOfSystemBars()` pads every activity's content by bars/cutout/IME. Do not remove; Samsung's taskbar covered the player buttons.

## Not yet ported

Stalker VOD, playlist scope filter (owner skipped it, 2026-10-13).

## Translations

13 languages, same as the desktop: `values-<lang>/strings.xml` + `raw-<lang>/help.md`. `TranslationTest` fails on a missing
key, changed placeholder, missing plural form or help section, so a new English string needs all 13 in the same commit.
Hungarian uses the desktop translator's terms (műsorújság, rögzítés, importálás). Apostrophes: typographic ’, never raw '.
Format-only strings are `translatable="false"`. `generateLocaleConfig` feeds Android 13+'s per-app language picker.
