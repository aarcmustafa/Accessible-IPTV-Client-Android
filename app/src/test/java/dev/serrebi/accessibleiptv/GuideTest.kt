package dev.serrebi.accessibleiptv

import dev.serrebi.accessibleiptv.FakeServer.Companion.gzip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The SQLite guide: import, matching, now/next, search, and keeping the old guide when an import fails. */
@RunWith(RobolectricTestRunner::class)
class GuideTest {
	private val server = FakeServer()
	private val epg get() = (RuntimeEnvironment.getApplication() as App).epg
	private val now = System.currentTimeMillis() / 60_000 * 60_000
	private val h = 3_600_000L

	@After fun stop() = server.close()

	private fun t(ms: Long) = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC)) + " +0000"

	private data class P(val ch: String, val start: Long, val stop: Long, val title: String, val desc: String = "")

	private fun xml(channels: Map<String, String>, progs: List<P>) = buildString {
		append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
		for ((id, name) in channels) append("<channel id=\"$id\"><display-name>$name</display-name><icon src=\"x\"/></channel>\n")
		for (p in progs) append("<programme start=\"${t(p.start)}\" stop=\"${t(p.stop)}\" channel=\"${p.ch}\"><title lang=\"en\">${p.title}</title><desc>${p.desc}</desc><credits><actor>A</actor></credits></programme>\n")
		append("</tv>\n")
	}

	private fun ch(name: String, tvg: String = "") = Channel("s", name, name, "G", "http://h/$name.ts", tvgId = tvg)

	private val bbc = ch("BBC One", "bbc1.uk")
	private val standard = mapOf("bbc1.uk" to "BBC One")
	private val ab = listOf(P("bbc1.uk", now - h, now + h, "A"), P("bbc1.uk", now + h, now + 2 * h, "B"))

	private fun import(vararg paths: String, channels: List<Channel> = listOf(bbc)) =
		epg.import(paths.map { server.url + it }, channels, now - 24 * h, now + 48 * h) {}

	@Test fun nowAndNext() {
		server.text("/g.xml", xml(standard, ab))
		assertEquals(2, import("/g.xml"))
		val nn = epg.nowNext(bbc, now)
		assertEquals("A", nn.now?.title)
		assertEquals("B", nn.next?.title)
	}

	@Test fun aGapHasNoNowOnlyANext() {
		server.text("/g.xml", xml(standard, listOf(P("bbc1.uk", now + h, now + 2 * h, "Later"))))
		import("/g.xml")
		val nn = epg.nowNext(bbc, now)
		assertNull("a programme that has not started is not on now", nn.now)
		assertEquals("Later", nn.next?.title)
	}

	@Test fun matchesByNameWhenThereIsNoTvgId() {
		server.text("/g.xml", xml(mapOf("x1" to "BBC One HD"), listOf(P("x1", now - h, now + h, "A"))))
		val noId = ch("UK: BBC One")
		import("/g.xml", channels = listOf(noId))
		assertEquals("A", epg.nowNext(noId, now).now?.title)
	}

	@Test fun tvgIdIgnoresCase() {
		server.text("/g.xml", xml(mapOf("BBC1.UK" to "Whatever"), listOf(P("BBC1.UK", now - h, now + h, "A"))))
		import("/g.xml")
		assertEquals("A", epg.nowNext(bbc, now).now?.title)
	}

	@Test fun unmatchedChannelsAndOldProgrammesAreNotStored() {
		server.text("/g.xml", xml(standard + ("other" to "Other"), ab + P("other", now, now + h, "X") + P("bbc1.uk", now - 30 * h, now - 29 * h, "Old")))
		assertEquals(2, import("/g.xml"))
	}

	@Test fun aChannelInTwoGuidesIsNotDoubled() {
		server.text("/1.xml", xml(standard, ab))
		server.text("/2.xml", xml(standard, ab))
		assertEquals(2, import("/1.xml", "/2.xml"))
		assertEquals("B", epg.nowNext(bbc, now).next?.title)
	}

	@Test fun gzipGuide() {
		server.bytes("/g.xml.gz", gzip(xml(standard, ab)))
		assertEquals(2, import("/g.xml.gz"))
	}

	@Test fun aFailedImportKeepsTheOldGuide() {
		server.text("/g.xml", xml(standard, ab))
		import("/g.xml")
		try { import("/g.xml", "/missing.xml"); fail() } catch (_: java.io.IOException) {}
		assertEquals("A", epg.nowNext(bbc, now).now?.title)
		assertNoStagingTables()
	}

	@Test fun aCutOffGuideKeepsTheOldGuide() {
		server.text("/g.xml", xml(standard, ab))
		import("/g.xml")
		server.text("/cut.xml", xml(standard, ab).substringBefore("<credits>"))
		try { import("/cut.xml"); fail() } catch (_: Exception) {}
		assertEquals("A", epg.nowNext(bbc, now).now?.title)
		assertNoStagingTables()
	}

	@Test fun reimportReplacesInsteadOfAdding() {
		server.text("/g.xml", xml(standard, ab))
		import("/g.xml")
		import("/g.xml")
		assertEquals(2, epg.schedule(bbc, now - 24 * h, now + 24 * h).size)
	}

	@Test fun searchTreatsWildcardsAsText() {
		server.text("/g.xml", xml(standard, listOf(P("bbc1.uk", now - h, now + h, "100% Hits"), P("bbc1.uk", now + h, now + 2 * h, "100 Greatest", "a_b"))))
		import("/g.xml")
		assertEquals(listOf("100% Hits"), epg.search("100%", listOf(bbc), now).map { it.first.title })
		assertEquals(listOf("100 Greatest"), epg.search("a_b", listOf(bbc), now).map { it.first.title })
		assertEquals(emptyList<String>(), epg.search("x_y", listOf(bbc), now).map { it.first.title })
	}

	@Test fun searchSkipsWhatHasEnded() {
		server.text("/g.xml", xml(standard, listOf(P("bbc1.uk", now - 3 * h, now - 2 * h, "News"), P("bbc1.uk", now + h, now + 2 * h, "News"))))
		import("/g.xml")
		assertEquals(1, epg.search("news", listOf(bbc), now).size)
	}

	@Test fun onNowListsEveryChannelSortedByTitle() {
		val itv = ch("ITV", "itv.uk")
		server.text("/g.xml", xml(standard + ("itv.uk" to "ITV"), listOf(P("bbc1.uk", now - h, now + h, "zebra"), P("itv.uk", now - h, now + h, "Apple"))))
		import("/g.xml", channels = listOf(bbc, itv))
		assertEquals(listOf("Apple" to "ITV", "zebra" to "BBC One"), epg.onNow(listOf(bbc, itv), now).map { it.first.title to it.second.name })
	}

	@Test fun xmlEntitiesAreDecoded() {
		server.text("/g.xml", xml(standard, listOf(P("bbc1.uk", now - h, now + h, "Tom &amp; Jerry"))))
		import("/g.xml")
		assertEquals("Tom & Jerry", epg.nowNext(bbc, now).now?.title)
	}

	@Test fun noGuideMeansEmptyNotACrash() {
		assertTrue(epg.nowNext(ch("Nothing"), now).isEmpty)
		assertEquals(emptyList<Programme>(), epg.schedule(ch("Nothing"), 0, Long.MAX_VALUE))
	}

	private fun assertNoStagingTables() {
		epg.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE name LIKE '%_new'", null).use { assertEquals(0, it.count) }
	}
}
