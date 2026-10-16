package dev.serrebi.accessibleiptv

import dev.serrebi.accessibleiptv.FakeServer.Companion.query
import dev.serrebi.accessibleiptv.FakeServer.Companion.reply
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Failure paths found during the release audit: rejected input must keep working data. */
@RunWith(RobolectricTestRunner::class)
class AuditTest {
	private val server = FakeServer()
	private val app get() = RuntimeEnvironment.getApplication() as App
	@After fun stop() = server.close()

	@Test fun anHtmlPlaylistKeepsCachedChannels() {
		val src = Source("s", Source.TYPE_M3U, "List", "${server.url}/list")
		app.prefs.sources = listOf(src)
		server.text("/list", "#EXTM3U\n#EXTINF:-1,News\nhttp://h/1.ts")
		runBlocking { app.load(App.LIVE, true) }
		server.text("/list", "<html><body>Login required</body></html>")
		assertEquals(1, runBlocking { app.load(App.LIVE, true) }.size)
		assertEquals(listOf("News"), app.channels.map { it.name })
	}

	@Test fun anXtreamErrorKeepsCachedChannels() {
		app.prefs.sources = listOf(Source("s", Source.TYPE_XTREAM, "List", server.url, "u", "p"))
		server.on("/player_api.php") { ex ->
			ex.reply(200, if (ex.query()["action"] == "get_live_streams") """[{"stream_id":1,"name":"News"}]""" else "[]")
		}
		runBlocking { app.load(App.LIVE, true) }
		server.text("/player_api.php", """{"user_info":{"auth":0}}""")
		assertEquals(1, runBlocking { app.load(App.LIVE, true) }.size)
		assertEquals(listOf("News"), app.channels.map { it.name })
	}

	@Test fun aStalkerErrorIsNotAnEmptyPlaylist() {
		server.on("/portal.php") { ex ->
			ex.reply(200, if (ex.query()["action"] == "handshake") """{"js":{"token":"T"}}""" else """{"js":false}""")
		}
		assertThrows(IOException::class.java) {
			Stalker(Source("s", Source.TYPE_STALKER, "Portal", server.url, mac = "00:1A:79:00:00:01")).channels()
		}
	}

	@Test fun anHtmlGuideKeepsTheOldGuide() {
		val ch = Channel("s", "1", "News", "", "http://h/1.ts", "news")
		server.text("/guide", "<tv><channel id=\"news\"><display-name>News</display-name></channel>" +
			"<programme channel=\"news\" start=\"20261015000000 +0000\" stop=\"20261015010000 +0000\"><title>Bulletin</title></programme></tv>")
		app.epg.import(listOf("${server.url}/guide"), listOf(ch), 0, Long.MAX_VALUE) {}
		server.text("/guide", "<html><body>Login required</body></html>")
		assertThrows(IOException::class.java) { app.epg.import(listOf("${server.url}/guide"), listOf(ch), 0, Long.MAX_VALUE) {} }
		assertEquals(listOf("Bulletin"), app.epg.schedule(ch, 0, Long.MAX_VALUE).map { it.title })
	}

	@Test fun wrongSettingTypesLeaveExistingSettingsUntouched() {
		app.prefs.epgDaysAhead = 5
		val bad = JSONObject("""{"app":"AccessibleIPTV-Android","settings":{"epgDaysAhead":{"s":"2"}}}""")
		assertThrows(IllegalArgumentException::class.java) { app.prefs.import(bad) }
		assertEquals(5, app.prefs.epgDaysAhead)
	}

	@Test fun malformedSourcesLeaveExistingSettingsUntouched() {
		app.prefs.epgDaysAhead = 5
		val bad = JSONObject("""{"app":"AccessibleIPTV-Android","settings":{"sources":{"s":"not json"}}}""")
		assertThrows(Exception::class.java) { app.prefs.import(bad) }
		assertEquals(5, app.prefs.epgDaysAhead)
		assertEquals(emptyList<Source>(), app.prefs.sources)
	}

	@Test fun forgettingASourceRemovesItsLoadedChannels() {
		val src = Source("s", Source.TYPE_M3U, "List", "${server.url}/list")
		app.prefs.sources = listOf(src)
		server.text("/list", "#EXTM3U\n#EXTINF:-1,News\nhttp://h/1.ts")
		runBlocking { app.load(App.LIVE, true) }
		app.prefs.sources = emptyList()
		app.forgetCache(src)
		assertTrue("deleted playlists must disappear from recent channels and guides too", app.channels.isEmpty())
	}

	@Test fun editingAStalkerSourceUsesTheNewPortal() {
		server.on("/portal.php") { ex ->
			ex.reply(200, if (ex.query()["action"] == "handshake") """{"js":{"token":"T"}}""" else """{"js":{"cmd":"http://old/live"}}""")
		}
		server.on("/new/portal.php") { ex ->
			ex.reply(200, if (ex.query()["action"] == "handshake") """{"js":{"token":"T2"}}""" else """{"js":{"cmd":"http://new/live"}}""")
		}
		val src = Source("s", Source.TYPE_STALKER, "Portal", server.url, mac = "00:1A:79:00:00:01")
		val ch = Channel("s", "1", "News", "", "stalker:cmd")
		app.prefs.sources = listOf(src)
		assertEquals("http://old/live", app.resolveUrl(ch))
		app.prefs.sources = listOf(src.copy(url = "${server.url}/new"))
		app.forgetCache(src)
		assertEquals("http://new/live", app.resolveUrl(ch))
	}

	@Test fun aPlaylistChangedDuringGuideImportStaysStale() {
		val src = Source("s", Source.TYPE_M3U, "List", "${server.url}/list", epgUrl = "${server.url}/guide")
		app.prefs.sources = listOf(src)
		server.text("/list", "#EXTM3U\n#EXTINF:-1 tvg-id=\"news\",News\nhttp://h/1.ts")
		runBlocking { app.load(App.LIVE, true) }
		val sent = CountDownLatch(1)
		val release = CountDownLatch(1)
		server.on("/guide") { ex ->
			ex.sendResponseHeaders(200, 0)
			ex.responseBody.write("<tv><channel id=\"news\"><display-name>News</display-name></channel>".toByteArray())
			ex.responseBody.flush()
			sent.countDown()
			check(release.await(10, TimeUnit.SECONDS))
			ex.responseBody.write("</tv>".toByteArray())
		}
		val result = java.util.concurrent.CompletableFuture<Int>()
		val importer = Thread { runCatching { runBlocking { app.importGuide {} } }.fold(result::complete, result::completeExceptionally) }.apply { start() }
		try {
			assertTrue(sent.await(10, TimeUnit.SECONDS))
			server.text("/list", "#EXTM3U\n#EXTINF:-1 tvg-id=\"news\",News\nhttp://h/1.ts\n#EXTINF:-1 tvg-id=\"sport\",Sport\nhttp://h/2.ts")
			runBlocking { app.load(App.LIVE, true) }
		} finally {
			release.countDown()
			importer.join(10_000)
		}
		result.get(1, TimeUnit.SECONDS)
		assertTrue("new channels were not in the imported guide", app.guideStale())
	}

	@Test fun restoredPlayerReloadsItsLiveChannel() {
		val src = Source("s", Source.TYPE_M3U, "List", "${server.url}/list")
		app.prefs.sources = listOf(src)
		server.text("/list", "#EXTM3U\n#EXTINF:-1,News\nhttp://h/1.ts")
		runBlocking { app.load(App.LIVE, true) }
		val ch = app.channels.single()
		@Suppress("UNCHECKED_CAST")
		(App::class.java.getDeclaredField("lists").apply { isAccessible = true }.get(app) as MutableMap<String, *>).clear()
		val intent = android.content.Intent(app, PlayerActivity::class.java).putExtra("key", ch.key).putExtra("title", ch.name).putExtra("url", "${server.url}/live.ts")
		val controller = org.robolectric.Robolectric.buildActivity(PlayerActivity::class.java, intent).setup()
		try {
			val a = controller.get()
			val end = System.currentTimeMillis() + 5_000
			while (app.channel(ch.key) == null && System.currentTimeMillis() < end) {
				org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
				Thread.sleep(10)
			}
			org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
			assertNotNull("a restored player must reload its channel cache", app.channel(ch.key))
			assertTrue(PlayerActivity::class.java.getDeclaredField("live").apply { isAccessible = true }.getBoolean(a))
		} finally { controller.pause().stop().destroy() }
	}

	@Test fun aStorageInsertFailureCleansUpAndReportsFailure() {
		val provider = object : android.content.ContentProvider() {
			override fun onCreate() = true
			override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = throw IOException("Storage unavailable")
			override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): android.database.Cursor? = null
			override fun getType(uri: android.net.Uri): String? = null
			override fun delete(uri: android.net.Uri, selection: String?, args: Array<out String>?) = 0
			override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = 0
		}
		provider.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "media" })
		org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", provider)
		val controller = org.robolectric.Robolectric.buildService(RecordService::class.java).create()
		val key = "s:1"
		Recorder.active[key] = Recorder.Active(key, "News", key, 0, 0)
		try {
			RecordService::class.java.getDeclaredMethod("record", String::class.java, String::class.java, String::class.java, String::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
				.apply { isAccessible = true }.invoke(controller.get(), key, "News", "", "${server.url}/live.ts", 0L, 0L)
			assertFalse(Recorder.isRecording(key))
			val notices = org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).allNotifications
			assertTrue(notices.any { it.extras.getCharSequence("android.title").toString() == app.getString(R.string.recording_failed, "News") })
		} finally { Recorder.active.clear(); controller.destroy() }
	}

	@Test fun aSlowOldSearchCannotReplaceNewResults() {
		app.prefs.lastUpdateCheck = System.currentTimeMillis()
		val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
		val a = controller.get()
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		@Suppress("UNCHECKED_CAST")
		val lists = App::class.java.getDeclaredField("lists").apply { isAccessible = true }.get(app) as MutableMap<String, List<Channel>>
		val old = Channel("s", "1", "Old", "", "http://h/1.ts")
		lists[App.LIVE] = object : AbstractList<Channel>() {
			override val size = 1
			override fun get(index: Int): Channel {
				entered.countDown()
				check(release.await(10, TimeUnit.SECONDS))
				return old
			}
		}
		val show = MainActivity::class.java.getDeclaredMethod("showChannels").apply { isAccessible = true }
		val field = a.findViewById<android.widget.EditText>(R.id.filter)
		field.setText("Old")
		val first = show.invoke(a) as kotlinx.coroutines.Job
		try {
			assertTrue(entered.await(5, TimeUnit.SECONDS))
			lists[App.LIVE] = listOf(Channel("s", "2", "New", "", "http://h/2.ts"))
			field.setText("New")
			val second = show.invoke(a) as kotlinx.coroutines.Job
			val end = System.currentTimeMillis() + 5_000
			while (!second.isCompleted && System.currentTimeMillis() < end) {
				org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
				Thread.sleep(10)
			}
			assertTrue(second.isCompleted)
		} finally { release.countDown() }
		val end = System.currentTimeMillis() + 5_000
		while (!first.isCompleted && System.currentTimeMillis() < end) {
			org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
			Thread.sleep(10)
		}
		try {
			@Suppress("UNCHECKED_CAST")
			val adapter = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.channels).adapter as RowAdapter<Channel>
			assertEquals(listOf("New"), adapter.items.map { it.name })
		} finally { controller.pause().stop().destroy() }
	}

	@Test fun importedSourcesDoNotReuseThePreviousLoginCache() {
		val src = Source("s", Source.TYPE_M3U, "List", "${server.url}/old")
		app.prefs.sources = listOf(src)
		server.text("/old", "#EXTM3U\n#EXTINF:-1,Old\nhttp://h/1.ts")
		server.text("/new", "#EXTM3U\n#EXTINF:-1,New\nhttp://h/2.ts")
		runBlocking { app.load(App.LIVE, true) }
		val backup = app.prefs.export()
		backup.getJSONObject("settings").getJSONObject("sources").put("s", org.json.JSONArray(listOf(src.copy(url = "${server.url}/new").toJson())).toString())
		val uri = android.net.Uri.parse("content://backup/settings.json")
		org.robolectric.Shadows.shadowOf(app.contentResolver).registerInputStream(uri, backup.toString().byteInputStream())
		val controller = org.robolectric.Robolectric.buildActivity(SettingsActivity::class.java).setup()
		try {
			val a = controller.get()
			a.findViewById<android.view.View>(R.id.btnImport).performClick()
			val request = org.robolectric.Shadows.shadowOf(a).nextStartedActivityForResult
			a.activityResultRegistry.dispatchResult(request.requestCode, android.app.Activity.RESULT_OK, android.content.Intent().setData(uri))
			runBlocking { app.load(App.LIVE, false) }
			assertEquals(listOf("New"), app.channels.map { it.name })
		} finally { controller.pause().stop().destroy() }
	}
}
