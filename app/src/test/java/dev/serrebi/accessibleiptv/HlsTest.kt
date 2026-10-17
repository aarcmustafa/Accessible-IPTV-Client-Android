package dev.serrebi.accessibleiptv

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** HLS downloads: the playlist, then its segments in order, one request at a time. */
class HlsTest {

	@Test fun aMasterPlaylistPointsAtItsFirstVariant() {
		val body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8\n" +
			"#EXT-X-STREAM-INF:BANDWIDTH=3000000\nhigh/index.m3u8"
		assertTrue(Hls.isMaster(body))
		assertEquals("http://h/live/low/index.m3u8", Hls.firstVariant(body, "http://h/live/master.m3u8"))
		assertFalse(Hls.isMaster("#EXTM3U\n#EXTINF:6,\nseg1.ts"))
	}

	@Test fun segmentsAndTagsOfAMediaPlaylist() {
		val body = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:7\n" +
			"#EXTINF:6.0,\nseg1.ts\n#EXTINF:6.0,\nhttp://other/seg2.ts\n#EXT-X-ENDLIST"
		assertEquals(listOf("http://h/live/seg1.ts", "http://other/seg2.ts"), Hls.segments(body, "http://h/live/index.m3u8"))
		assertEquals(7L, Hls.mediaSequence(body))
		assertTrue(Hls.hasEndList(body))
	}

	@Test fun downloadConcatenatesTheSegmentsInOrderAndOneAtATime() {
		val files = mapOf(
			"http://h/index.m3u8" to "#EXTM3U\n#EXTINF:4,\na.ts\n#EXTINF:4,\nb.ts\n#EXT-X-ENDLIST\n",
			"http://h/a.ts" to "AAA",
			"http://h/b.ts" to "BBB",
		)
		val log = mutableListOf<String>()
		var inFlight = false
		val out = ByteArrayOutputStream()
		val wrote = Hls.download(
			open = { url ->
				check(!inFlight) { "HLS downloads must run one request at a time" }
				inFlight = true
				log += url
				object : ByteArrayInputStream(files.getValue(url).toByteArray()) {
					override fun close() {
						inFlight = false
						super.close()
					}
				}
			},
			url = "http://h/index.m3u8",
			out = out,
			done = { false },
			sleep = {},
		)
		assertTrue(wrote)
		assertEquals("AAABBB", out.toString())
		assertEquals(listOf("http://h/index.m3u8", "http://h/a.ts", "http://h/b.ts"), log)
	}

	@Test fun aLivePlaylistIsFollowedAndEverySegmentIsFetchedOnce() {
		val media = listOf(
			"#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:4,\ns1.ts\n",
			"#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:4,\ns1.ts\n#EXTINF:4,\ns2.ts\n",
			"#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:4,\ns1.ts\n#EXTINF:4,\ns2.ts\n#EXTINF:4,\ns3.ts\n#EXT-X-ENDLIST\n",
		)
		var playlists = 0
		val fetched = mutableListOf<String>()
		val out = ByteArrayOutputStream()
		val wrote = Hls.download(
			open = { url ->
				val body = if (url == "http://h/index.m3u8") media[minOf(playlists++, media.size - 1)]
				else url.substringAfterLast('/').substringBefore('.').also { fetched += url }
				ByteArrayInputStream(body.toByteArray())
			},
			url = "http://h/index.m3u8",
			out = out,
			done = { false },
			sleep = {},
		)
		assertTrue(wrote)
		assertEquals(listOf("http://h/s1.ts", "http://h/s2.ts", "http://h/s3.ts"), fetched)
		assertEquals("s1s2s3", out.toString())
	}

	@Test fun aFragmentedPlaylistFetchesItsInitialisationSegmentFirst() {
		val files = mapOf(
			"http://h/index.m3u8" to "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\nseg1.m4s\n#EXT-X-ENDLIST\n",
			"http://h/init.mp4" to "INIT",
			"http://h/seg1.m4s" to "ONE",
		)
		val out = ByteArrayOutputStream()
		Hls.download(open = { url -> ByteArrayInputStream(files.getValue(url).toByteArray()) }, url = "http://h/index.m3u8", out = out, done = { false }, sleep = {})
		assertEquals("INITONE", out.toString())
	}

	@Test fun aStopEndsTheDownloadBeforeTheNextSegment() {
		val files = mapOf(
			"http://h/index.m3u8" to "#EXTM3U\n#EXTINF:4,\na.ts\n#EXTINF:4,\nb.ts\n#EXT-X-ENDLIST\n",
			"http://h/a.ts" to "AAA",
			"http://h/b.ts" to "BBB",
		)
		var opened = 0
		val out = ByteArrayOutputStream()
		val wrote = Hls.download(
			open = { url -> opened++; ByteArrayInputStream(files.getValue(url).toByteArray()) },
			url = "http://h/index.m3u8",
			out = out,
			done = { opened >= 2 },
			sleep = {},
		)
		assertTrue(wrote)
		assertEquals("AAA", out.toString())
	}
}
