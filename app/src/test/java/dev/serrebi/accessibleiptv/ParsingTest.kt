package dev.serrebi.accessibleiptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZoneId
import java.time.ZoneOffset

/** Pure-JVM rules: playlist parsing, catch-up URLs, guide times, provider links, retry budgets. */
class ParsingTest {
	@Test fun aFailedFiniteDownloadIsNotReportedAsSaved() {
		var reads = 0
		val input = object : java.io.InputStream() {
			override fun read(): Int = error("bulk reads only")
			override fun read(b: ByteArray, off: Int, len: Int): Int {
				if (reads++ > 0) throw IOException("Connection reset")
				b[off] = 1
				return 1
			}
		}
		org.junit.Assert.assertThrows(IOException::class.java) {
			Recorder.copy({ input }, ByteArrayOutputStream(), reconnect = false, done = { false })
		}
	}

	@Test fun storageFailuresAreNotRetriedAsNetworkDrops() {
		var opens = 0
		val out = object : java.io.OutputStream() {
			override fun write(b: Int) { throw IOException("Disk full") }
		}
		org.junit.Assert.assertThrows(IOException::class.java) {
			Recorder.copy({ opens++; ByteArrayInputStream(byteArrayOf(1)) }, out, reconnect = true, pauseMs = 0, maxMisses = 2, done = { false })
		}
		assertEquals("a full disk must not open another stream", 1, opens)
	}

	@Test fun aLiveConnectionCloseFailureStillReconnects() {
		var opens = 0
		val out = ByteArrayOutputStream()
		val ok = Recorder.copy({
			when (opens++) {
				0 -> object : ByteArrayInputStream("abc".toByteArray()) {
					override fun close() { throw IOException("Connection reset on close") }
				}
				1 -> ByteArrayInputStream("def".toByteArray())
				else -> throw IOException("Offline")
			}
		}, out, reconnect = true, pauseMs = 0, maxMisses = 2, done = { false })
		assertTrue(ok)
		assertEquals("abcdef", out.toString())
	}
	// ---- M3U ----

	@Test fun byteOrderMarkDoesNotHideTheHeader() {
		val ch = M3uParser.parse("s", "\uFEFF#EXTM3U catchup=\"xc\" catchup-days=\"2\"\n#EXTINF:-1,A\nhttp://h/live/u/p/1.ts")
		assertEquals(1, ch.size)
		assertEquals(2, ch[0].catchupDays)
	}

	@Test fun windowsLineEndings() {
		val ch = M3uParser.parse("s", "#EXTM3U\r\n#EXTINF:-1 group-title=\"News\",A\r\nhttp://h/1.ts\r\n")
		assertEquals("A", ch[0].name)
		assertEquals("News", ch[0].group)
		assertEquals("http://h/1.ts", ch[0].url)
	}

	@Test fun channelIdsSurviveTheProviderAddingChannels() {
		val before = M3uParser.parse("s", "#EXTM3U\n#EXTINF:-1 tvg-id=\"bbc1\",BBC One\nhttp://h/1.ts\n#EXTINF:-1,ITV\nhttp://h/2.ts")
		val after = M3uParser.parse("s", "#EXTM3U\n#EXTINF:-1,New One\nhttp://h/9.ts\n#EXTINF:-1,ITV\nhttp://h/2.ts\n#EXTINF:-1 tvg-id=\"bbc1\",BBC One\nhttp://h/1.ts")
		assertEquals(before.first { it.name == "BBC One" }.key, after.first { it.name == "BBC One" }.key)
		assertEquals(before.first { it.name == "ITV" }.key, after.first { it.name == "ITV" }.key)
	}

	@Test fun repeatedNamesStillGetDistinctKeys() {
		val ch = M3uParser.parse("s", "#EXTINF:-1,Same\nhttp://h/1.ts\n#EXTINF:-1,Same\nhttp://h/2.ts")
		assertEquals(2, ch.map { it.key }.toSet().size)
	}

	@Test fun vlcOptionsBecomeRequestHeaders() {
		val ch = M3uParser.parse("s", "#EXTINF:-1,A\n#EXTVLCOPT:http-user-agent=Mozilla/5.0 (X11)\n#EXTVLCOPT:http-referrer=http://site/\nhttp://h/1.ts")
		val (url, h) = M3uParser.splitHeaders(ch[0].url)
		assertEquals("http://h/1.ts", url)
		assertEquals("Mozilla/5.0 (X11)", h["User-Agent"])
		assertEquals("http://site/", h["Referer"])
	}

	@Test fun userAgentAttributeIsApplied() {
		val ch = M3uParser.parse("s", "#EXTINF:-1 http-user-agent=\"Kodi/20\",A\nhttp://h/1.ts")
		assertEquals("Kodi/20", M3uParser.splitHeaders(ch[0].url).second["User-Agent"])
	}

	@Test fun pipeTailInTheUrlWinsOverPlaylistOptions() {
		val ch = M3uParser.parse("s", "#EXTINF:-1,A\n#EXTVLCOPT:http-user-agent=FromOpt\nhttp://h/1.ts|User-Agent=FromUrl")
		assertEquals("FromUrl", M3uParser.splitHeaders(ch[0].url).second["User-Agent"])
	}

	@Test fun optionsDoNotLeakToTheNextChannel() {
		val ch = M3uParser.parse("s", "#EXTINF:-1,A\n#EXTVLCOPT:http-user-agent=X\nhttp://h/1.ts\n#EXTINF:-1,B\nhttp://h/2.ts")
		assertEquals("http://h/2.ts", ch[1].url)
	}

	@Test fun unquotedAndSingleQuotedAttributes() {
		val a = M3uParser.attributes("#EXTINF:-1 tvg-id=bbc1.uk group-title='UK News' tvg-logo=\"x\"")
		assertEquals("bbc1.uk", a["tvg-id"])
		assertEquals("UK News", a["group-title"])
		assertEquals("x", a["tvg-logo"])
	}

	@Test fun singleQuotedCommasDoNotStartTheChannelTitle() {
		val ch = M3uParser.parse("s", "#EXTINF:-1 group-title='UK, News' tvg-name=\"Children’s TV\",News, London\nhttp://h/1.ts").single()
		assertEquals("UK, News", ch.group)
		assertEquals("News, London", ch.name)
	}

	@Test fun anApostropheInsideABareAttributeDoesNotHideTheTitle() {
		val ch = M3uParser.parse("s", "#EXTINF:-1 tvg-id=kids'network,Kids Network\nhttp://h/1.ts").single()
		assertEquals("kids'network", ch.tvgId)
		assertEquals("Kids Network", ch.name)
	}

	@Test fun titleFallsBackToTvgNameThenUrl() {
		val ch = M3uParser.parse("s", "#EXTINF:-1 tvg-name=\"Named\",\nhttp://h/1.ts\n#EXTINF:-1\nhttp://h/2.ts")
		assertEquals("Named", ch[0].name)
		assertEquals("http://h/2.ts", ch[1].name)
	}

	@Test fun extgrpAppliesToOneChannelOnly() {
		val ch = M3uParser.parse("s", "#EXTINF:-1,A\n#EXTGRP:Sport\nhttp://h/1.ts\n#EXTINF:-1,B\nhttp://h/2.ts")
		assertEquals("Sport", ch[0].group)
		assertEquals("Ungrouped", ch[1].group)
	}

	@Test fun lowercaseTagsAreAccepted() {
		val ch = M3uParser.parse("s", "#extm3u\n#extinf:-1 group-title=\"G\",A\nhttp://h/1.ts")
		assertEquals("A", ch[0].name)
		assertEquals("G", ch[0].group)
	}

	@Test fun entryWithoutUrlIsDropped() {
		val ch = M3uParser.parse("s", "#EXTINF:-1,Broken\n#EXTINF:-1,Good\nhttp://h/1.ts")
		assertEquals(listOf("Good"), ch.map { it.name })
	}

	@Test fun headerGuides() {
		assertEquals(listOf("http://a/1.xml", "https://b/2.xml.gz"), M3uParser.headerGuides("#EXTM3U x-tvg-url=\"http://a/1.xml, https://b/2.xml.gz\"\n"))
		assertEquals(listOf("http://a/1.xml", "http://b/2.xml"), M3uParser.headerGuides("#EXTM3U url-tvg=http://a/1.xml,http://b/2.xml\n"))
		assertEquals(listOf("http://a/1.xml"), M3uParser.headerGuides("\uFEFF#EXTM3U x-tvg-url=\"HTTP://a/1.xml\""))
		// A playlist must never point the importer at a local file.
		assertEquals(emptyList<String>(), M3uParser.headerGuides("#EXTM3U x-tvg-url=\"file:///etc/passwd\""))
		assertEquals(emptyList<String>(), M3uParser.headerGuides("#EXTINF:-1,A\nhttp://h/1.ts"))
	}

	@Test fun brokenPercentEscapeInHeaderTailDoesNotCrash() {
		assertEquals("100%zz", M3uParser.splitHeaders("http://h/1.ts|Cookie=100%zz").second["Cookie"])
	}

	// ---- Catch-up ----

	private fun ch(url: String, attrs: Map<String, String>) = Channel("s", "1", "C", "G", url, attrs = attrs)
	private val start = 1_760_000_400_000L // 2025-10-09 09:00 UTC

	@Test fun xtreamCatchupKeepsQueryAndHeaders() {
		val url = Catchup.url(ch("http://h/live/u/p/42.ts?token=t|User-Agent=X", mapOf("catchup" to "xc")), start, start + 3_600_000, ZoneOffset.UTC)
		assertEquals("http://h/timeshift/u/p/60/2025-10-09:09-00/42.ts?token=t|User-Agent=X", url)
	}

	@Test fun xtreamCatchupUsesTheProvidersClock() {
		val c = ch("http://h/live/u/p/42.ts", mapOf("catchup" to "xc", "catchup-timezone" to "UTC"))
		assertEquals("http://h/timeshift/u/p/30/2025-10-09:09-00/42.ts", Catchup.url(c, start, start + 1_800_000, ZoneId.of("America/New_York")))
	}

	@Test fun catchupOffsetShiftsTheArchiveStart() {
		val c = ch("http://h/live/u/p/42.ts", mapOf("catchup" to "xc", "catchup-offset" to "1"))
		assertEquals("http://h/timeshift/u/p/30/2025-10-09:08-00/42.ts", Catchup.url(c, start, start + 1_800_000, ZoneOffset.UTC))
	}

	@Test fun templateTokens() {
		val c = ch("http://h/s.m3u8", mapOf("catchup-source" to "http://a/{Y}/{m}/{d}/{H}{M}?d={duration}&e={utcend}"))
		// A full URL template replaces the live URL (Kodi catchup="default"); it is never glued onto it.
		assertEquals("http://a/2025/10/09/0900?d=1800&e=${(start + 1_800_000) / 1000}",
			Catchup.url(c, start, start + 1_800_000, ZoneOffset.UTC))
	}

	@Test fun appendTemplateKeepsLiveUrlAndHeaders() {
		val c = ch("http://h/s.m3u8|User-Agent=X", mapOf("catchup" to "append", "catchup-source" to "?utc={utc}"))
		assertEquals("http://h/s.m3u8?utc=${start / 1000}|User-Agent=X", Catchup.url(c, start, start + 60_000, ZoneOffset.UTC))
	}

	@Test fun pathStyleAddsTheStreamId() {
		val c = ch("http://h/live/u/p/42.ts", mapOf("catchup" to "default", "catchup-source" to "http://a/archive"))
		assertEquals("http://a/archive/42/2025-10-09:09-00/30/", Catchup.url(c, start, start + 1_800_000, ZoneOffset.UTC))
	}

	@Test fun flussonicArchive() {
		val c = ch("http://h/ch/index.m3u8", mapOf("catchup-type" to "flussonic", "catchup-source" to "http://a/ch/123"))
		assertEquals("http://a/ch/123/20251009090000-30.m3u8", Catchup.url(c, start, start + 1_800_000, ZoneOffset.UTC))
	}

	@Test fun catchupWindow() {
		val c = ch("http://h/1.ts", mapOf("catchup-days" to "2"))
		assertTrue(Catchup.withinWindow(c, start, start + 86_400_000))
		assertFalse(Catchup.withinWindow(c, start, start + 3 * 86_400_000L))
		assertTrue(Catchup.withinWindow(ch("http://h/1.ts", emptyMap()), 0, start))
	}

	@Test fun catchupDaysFromTimeshiftAndDecimals() {
		assertEquals(3, ch("u", mapOf("timeshift" to "3")).catchupDays)
		assertEquals(7, ch("u", mapOf("catchup-days" to "7.0")).catchupDays)
		assertEquals(0, ch("u", mapOf("catchup-days" to "")).catchupDays)
		assertFalse(ch("u", emptyMap()).hasCatchup)
	}

	// ---- XMLTV ----

	@Test fun xmltvOffsets() {
		val base = 1_760_000_400_000L // 20251009090000 UTC
		assertEquals(base, Xmltv.parseTime("20251009090000 +0000"))
		assertEquals(base + 5 * 3_600_000, Xmltv.parseTime("20251009090000 -0500"))
		assertEquals(base - 5 * 3_600_000 - 1_800_000, Xmltv.parseTime("20251009090000 +0530"))
		assertEquals("a colon offset is still an offset", base - 5 * 3_600_000 - 1_800_000, Xmltv.parseTime("20251009090000 +05:30"))
		assertEquals(base - 3_600_000, Xmltv.parseTime("20251009090000+0100"))
		assertEquals(base, Xmltv.parseTime("20251009090000 junk"))
		assertNull(Xmltv.parseTime("20251309090000 +0000"))
		assertNull(Xmltv.parseTime("20250231090000"))
		assertNull(Xmltv.parseTime(null))
		assertNull(Xmltv.parseTime("2025100909"))
	}

	@Test fun nameKeys() {
		assertEquals(Xmltv.nameKey("CNN"), Xmltv.nameKey("US| CNN FHD"))
		assertEquals(Xmltv.nameKey("BBC One"), Xmltv.nameKey("bbc one hd"))
		assertNotEquals(Xmltv.nameKey("BBC One"), Xmltv.nameKey("BBC Two"))
		assertNotEquals(Xmltv.nameKey("Sky Sports 1"), Xmltv.nameKey("Sky Sports 2"))
		// A word that merely starts like a country code is not stripped.
		assertEquals("abcnews", Xmltv.nameKey("ABC News"))
	}

	// ---- Xtream ----

	@Test fun serverAddressForms() {
		assertEquals("http://h:8080", Xtream.normalizeServer("h:8080/"))
		assertEquals("http://h:8080", Xtream.normalizeServer("http://h:8080/player_api.php"))
		assertEquals("http://h:8080", Xtream.normalizeServer("http://h:8080/get.php?username=u&password=p&type=m3u_plus"))
		assertEquals("https://h/sub", Xtream.normalizeServer("  https://h/sub/  "))
	}

	@Test fun credentialsFromPanelLinks() {
		assertEquals(Triple("http://h:8080", "u", "p"), Xtream.credentialsFromUrl("http://h:8080/get.php?username=u&password=p&type=m3u_plus"))
		assertEquals(Triple("http://h:8080", "u", "p"), Xtream.credentialsFromUrl("http://h:8080/live/u/p/123.ts"))
		assertEquals(Triple("http://h", "u", "p"), Xtream.credentialsFromUrl("http://h/u/p/123"))
		assertEquals(Triple("http://h", "u", "p"), Xtream.credentialsFromUrl("http://h/timeshift/u/p/60/2025-10-09:09-00/1.ts"))
		assertEquals(Triple("http://h", "a b", "p"), Xtream.credentialsFromUrl("http://h/get.php?username=a%20b&password=p"))
		// False matches would show an account that does not exist.
		assertNull(Xtream.credentialsFromUrl("http://h/playlist.m3u"))
		assertNull(Xtream.credentialsFromUrl("http://h/a/b/c/d/123.ts"))
		assertNull(Xtream.credentialsFromUrl("content://media/1"))
		assertNull(Xtream.credentialsFromUrl("http://h/get.php?username=u"))
	}

	@Test fun stalkerKeepsItsEndpointPath() {
		assertEquals("http://p.tv/stalker_portal", Stalker.baseUrl("http://p.tv/stalker_portal/server/load.php"))
		assertEquals("http://p.tv", Stalker.baseUrl("p.tv/portal.php"))
	}

	// ---- Search ----

	private fun named(vararg n: String) = n.mapIndexed { i, s -> Channel("s", "$i", s, "G", "u$i") }

	@Test fun searchPutsExactAndWordStartFirst() {
		val hits = App.searchRanked(named("PEACOCK 03 Breeders Cup Challenge Live", "The Challenge", "Challenge", "Unchallenged", "BBC"), "challenge")
		assertEquals(listOf("Challenge", "The Challenge", "PEACOCK 03 Breeders Cup Challenge Live", "Unchallenged"), hits.map { it.name })
	}

	@Test fun searchIsCaseInsensitiveAndTrimsTheQuery() {
		assertEquals(listOf("BBC One"), App.searchRanked(named("BBC One", "ITV"), "  bbc ").map { it.name })
		assertEquals(2, App.searchRanked(named("A", "B"), "").size)
	}

	@Test fun xtreamAccountBehindAnM3uLink() {
		val src = Source("1", Source.TYPE_M3U, "Dispatcharr", "http://h:9191/get.php?username=me&password=pw")
		val x = App.xtreamBehind(src)!!
		assertEquals(Source.TYPE_XTREAM, x.type)
		assertEquals("http://h:9191", x.url)
		assertEquals("me", x.username)
		assertNull(App.xtreamBehind(src.copy(url = "http://h/list.m3u")))
	}

	// ---- Audio tracks ----

	@Test fun audioKeywordsAndRememberedTrack() {
		val names = listOf("English", "Deutsch Audiodeskription", "Polski")
		assertEquals(1, AudioTracks.choose(names, listOf(false, false, false), null, true, emptyList()))
		assertEquals(2, AudioTracks.choose(names, listOf(false, false, false), null, false, listOf("polski", "english")))
		assertEquals(0, AudioTracks.choose(names, listOf(false, false, false), "english", true, listOf("polski")))
		// A remembered track the stream no longer has falls through to the other rules.
		assertEquals(2, AudioTracks.choose(names, listOf(false, false, false), "Gone", false, listOf("polski")))
		assertFalse(AudioTracks.matches("Broadcast", "ad"))
		assertFalse(AudioTracks.matches("", "ad"))
	}

	// ---- Reconnect and recording ----

	@Test fun reconnectBudgetMatchesTheDesktop() {
		var now = 1_000L
		val r = Reconnect { now }
		assertEquals(listOf(400L, 2000L, 4000L, 8000L, 8000L, 8000L), List(6) { r.next() })
		assertNull(r.next())
		now += Reconnect.RESET_MS
		assertEquals(400L, r.next())
		r.reset()
		assertEquals(400L, r.next())
	}

	private fun stream(s: String) = ByteArrayInputStream(s.toByteArray())

	@Test fun downloadCopiesOnceToTheEnd() {
		val out = ByteArrayOutputStream()
		var opens = 0
		assertTrue(Recorder.copy({ opens++; stream("abc") }, out, reconnect = false, pauseMs = 0) { false })
		assertEquals("abc", out.toString())
		assertEquals(1, opens)
	}

	@Test fun recordingReconnectsAfterADropAndAppends() {
		val out = ByteArrayOutputStream()
		val parts = ArrayDeque(listOf("abc", "def"))
		var opens = 0
		val ok = Recorder.copy({ opens++; parts.removeFirstOrNull()?.let { stream(it) } ?: throw IOException("down") }, out, reconnect = true, pauseMs = 0, maxMisses = 3) { false }
		assertTrue(ok)
		assertEquals("abcdef", out.toString())
		assertEquals(2 + 3, opens) // two good opens, then three misses in a row end it
	}

	@Test fun recordingStopsWhenDone() {
		val out = ByteArrayOutputStream()
		var opens = 0
		assertFalse(Recorder.copy({ opens++; stream("x") }, out, reconnect = true, pauseMs = 0) { true })
		assertEquals(0, opens)
	}

	@Test fun recordingThatNeverConnectsWritesNothing() {
		val out = ByteArrayOutputStream()
		assertFalse(Recorder.copy({ throw IOException("refused") }, out, reconnect = true, pauseMs = 0, maxMisses = 2) { false })
		assertEquals(0, out.size())
	}

	// ---- Text ----

	@Test fun sentencesSkipBlanks() {
		assertEquals("A. B", Format.sentences(listOf("A", " ", "", "B")))
		assertEquals("", Format.sentences(emptyList()))
	}

	@Test fun versionCompareHandlesSuffixesAndLengths() {
		assertTrue(Updater.isNewer("0.2.6", "0.2.5"))
		assertTrue(Updater.isNewer("1.0", "0.99.99"))
		assertFalse(Updater.isNewer("0.2.5", "0.2.5.0"))
		assertFalse(Updater.isNewer("", "0.1"))
	}
}
