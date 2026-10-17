package dev.serrebi.accessibleiptv

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowPopupMenu
import java.time.Duration

/** Screens as TalkBack and a remote meet them: one stop per row, headings, errors on the field, status lines. */
@RunWith(RobolectricTestRunner::class)
class ScreenTest {
	private val server = FakeServer()
	/** Players built by the tests, destroyed afterwards so the one stream is free again. */
	private val players = mutableListOf<ActivityController<PlayerActivity>>()
	private val app get() = RuntimeEnvironment.getApplication() as App

	@Before fun quiet() {
		app.prefs.lastUpdateCheck = System.currentTimeMillis() // no GitHub call from tests
	}

	@After fun stop() {
		// A player holds the one provider stream; one left undestroyed would refuse the next test.
		players.forEach { it.pause().stop().destroy() }
		players.clear()
		server.close()
	}

	private fun waitUntil(ms: Long = 5_000, cond: () -> Boolean) {
		val end = System.currentTimeMillis() + ms
		while (!cond()) {
			shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
			check(System.currentTimeMillis() < end) { "timed out" }
			Thread.sleep(10)
		}
	}

	private fun playlist(body: String) {
		server.text("/list.m3u", body)
		app.prefs.sources = listOf(Source("src", Source.TYPE_M3U, "List", "${server.url}/list.m3u"))
	}

	private fun isHeading(v: View) = ViewCompat.isAccessibilityHeading(v)

	// ---- Rows ----

	@Test fun aRowIsOneStopAndItsActionsDoNotPileUpOnRebind() {
		val host = Robolectric.buildActivity(HelpActivity::class.java).setup().get()
		val row = LayoutInflater.from(host).inflate(R.layout.item_row, null)
		bindRow(row, "BBC One", {}, listOf(RowAction("Play") {}, RowAction("Favorite") {}))
		bindRow(row, "ITV", {}, listOf(RowAction("Record") {}))
		val labels = row.createAccessibilityNodeInfo().actionList.mapNotNull { it.label?.toString() }
		assertEquals("a recycled row keeps only its new actions", listOf("Record"), labels)
		assertEquals("ITV", row.contentDescription)
		assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, row.findViewById<View>(R.id.line1).importantForAccessibility)
		assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, row.findViewById<View>(R.id.line2).importantForAccessibility)
	}

	@Test fun theMenuKeyOpensTheSameActions() {
		val host = Robolectric.buildActivity(HelpActivity::class.java).setup().get()
		val row = LayoutInflater.from(host).inflate(R.layout.item_row, null)
		host.setContentView(row) // a popup needs an anchor on screen
		shadowOf(Looper.getMainLooper()).idle()
		assertTrue(row.requestFocus()) // as when D-pad or TalkBack is on the row
		var ran = ""
		bindRow(row, "BBC One", {}, listOf(RowAction("Play") { ran = "play" }, RowAction("Favorite") { ran = "fav" }))
		assertTrue("the row takes the Menu key", row.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU)))
		val menu = ShadowPopupMenu.getLatestPopupMenu().menu
		assertEquals(listOf("Play", "Favorite"), (0 until menu.size()).map { menu.getItem(it).title.toString() })
		menu.performIdentifierAction(menu.getItem(1).itemId, 0)
		assertEquals("fav", ran)
	}

	// ---- Main screen ----

	@Test fun noPlaylistsSaysSoAndHeadingsAreHeadings() {
		val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
		assertEquals(a.getString(R.string.no_sources), a.findViewById<TextView>(R.id.status).text.toString())
		assertTrue(isHeading(a.findViewById(R.id.groupsHeading)))
		assertTrue(isHeading(a.findViewById(R.id.channelsHeading)))
	}

	@Test fun channelsLoadAndEachRowSpeaksItsName() {
		playlist("#EXTM3U\n#EXTINF:-1 group-title=\"News\",BBC One\nhttp://h/1.ts\n#EXTINF:-1 group-title=\"Sport\",Arena\nhttp://h/2.ts")
		val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
		val rv = a.findViewById<RecyclerView>(R.id.channels)
		waitUntil { rv.adapter!!.itemCount == 2 && rv.findViewHolderForAdapterPosition(0) != null }
		assertEquals("BBC One", rv.findViewHolderForAdapterPosition(0)!!.itemView.contentDescription.toString())
		assertTrue(a.findViewById<TextView>(R.id.status).text.contains("2"))
		val groups = a.findViewById<RecyclerView>(R.id.groups)
		waitUntil { groups.findViewHolderForAdapterPosition(0) != null }
		// "All, 2, selected": the reader says which group is showing.
		assertTrue(groups.findViewHolderForAdapterPosition(0)!!.itemView.contentDescription.endsWith(a.getString(R.string.selected)))
	}

	@Test fun searchRanksHitsAndBackClearsIt() {
		playlist("#EXTM3U\n#EXTINF:-1,PEACOCK 03 Cup Challenge\nhttp://h/1.ts\n#EXTINF:-1,The Challenge\nhttp://h/2.ts\n#EXTINF:-1,Challenge\nhttp://h/3.ts")
		val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
		val rv = a.findViewById<RecyclerView>(R.id.channels)
		@Suppress("UNCHECKED_CAST") val adapter = rv.adapter as RowAdapter<Channel>
		waitUntil { adapter.itemCount == 3 }
		a.findViewById<EditText>(R.id.filter).setText("challenge")
		waitUntil { adapter.items.firstOrNull()?.name == "Challenge" }
		assertEquals(listOf("Challenge", "The Challenge", "PEACOCK 03 Cup Challenge"), adapter.items.map { it.name })
		a.onBackPressedDispatcher.onBackPressed()
		assertEquals("", a.findViewById<EditText>(R.id.filter).text.toString())
		assertTrue("Back with search text does not close the app", !a.isFinishing)
	}

	@Test fun theSearchKeyClosesTheKeyboardAndSearchesNow() {
		playlist("#EXTM3U\n#EXTINF:-1,The Challenge\nhttp://h/1.ts\n#EXTINF:-1,Challenge\nhttp://h/2.ts\n#EXTINF:-1,BBC\nhttp://h/3.ts")
		val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
		@Suppress("UNCHECKED_CAST") val adapter = a.findViewById<RecyclerView>(R.id.channels).adapter as RowAdapter<Channel>
		waitUntil { adapter.itemCount == 3 }
		val field = a.findViewById<EditText>(R.id.filter)
		val imm = a.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
		field.requestFocus()
		imm.showSoftInput(field, 0)
		assertTrue(shadowOf(imm).isSoftInputVisible)
		field.setText("challenge")
		field.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
		assertTrue("keyboard put away", !shadowOf(imm).isSoftInputVisible)
		// No 300 ms typing pause: the Search key runs it at once.
		val end = System.currentTimeMillis() + 5_000
		while (adapter.items.firstOrNull()?.name != "Challenge" && System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
		assertEquals(listOf("Challenge", "The Challenge"), adapter.items.map { it.name })
		assertEquals("the text stays for Back to clear", "challenge", field.text.toString())
	}

	@Test fun theFilterSearchKeyClosesTheKeyboardToo() {
		val a = Robolectric.buildActivity(ProgrammesActivity::class.java).setup().get()
		val field = a.findViewById<EditText>(R.id.filter)
		val imm = a.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
		field.requestFocus()
		imm.showSoftInput(field, 0)
		field.setText("news")
		field.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
		assertTrue(!shadowOf(imm).isSoftInputVisible)
	}

	// ---- Settings ----

	@Test fun settingsKeepNumbersInRange() {
		val c = Robolectric.buildActivity(SettingsActivity::class.java).setup()
		val a = c.get()
		a.findViewById<EditText>(R.id.buffer).setText("500")
		a.findViewById<EditText>(R.id.maxBuffer).setText("999")
		a.findViewById<EditText>(R.id.days).setText("99")
		c.pause()
		assertEquals(300.0, app.prefs.maxBufferSeconds, 0.0)
		assertEquals(300.0, app.prefs.bufferSeconds, 0.0)
		assertEquals(14, app.prefs.epgDaysAhead)
		a.findViewById<EditText>(R.id.buffer).setText("-1")
		a.findViewById<EditText>(R.id.maxBuffer).setText("abc")
		a.findViewById<EditText>(R.id.days).setText("")
		c.resume().pause()
		assertEquals(25.0, app.prefs.bufferSeconds, 0.0)
		assertEquals(300.0, app.prefs.maxBufferSeconds, 0.0)
		assertEquals(2, app.prefs.epgDaysAhead)
	}

	@Test fun invalidBufferDefaultsStillFitTheChosenMaximum() {
		val c = Robolectric.buildActivity(SettingsActivity::class.java).setup()
		val a = c.get()
		a.findViewById<EditText>(R.id.buffer).setText("-1")
		a.findViewById<EditText>(R.id.maxBuffer).setText("12")
		c.pause()
		assertEquals(12.0, app.prefs.bufferSeconds, 0.0)
		assertEquals(12.0, app.prefs.maxBufferSeconds, 0.0)
		c.resume()
		a.findViewById<EditText>(R.id.buffer).setText("-1")
		a.findViewById<EditText>(R.id.maxBuffer).setText("-1")
		c.pause()
		assertEquals(25.0, app.prefs.bufferSeconds, 0.0)
		assertEquals(30.0, app.prefs.maxBufferSeconds, 0.0)
	}

	// ---- Playlists ----

	private fun openAdd(a: SourcesActivity, label: Int): AlertDialog {
		val bar = a.findViewById<LinearLayout>(R.id.buttons)
		(0 until bar.childCount).map { bar.getChildAt(it) as Button }.first { it.text == a.getString(label) }.performClick()
		shadowOf(Looper.getMainLooper()).idle() // runs the dialog's OnShowListener, which wires Save
		return ShadowDialog.getLatestDialog() as AlertDialog
	}

	@Test fun anEmptyXtreamFormPutsTheErrorOnTheField() {
		val a = Robolectric.buildActivity(SourcesActivity::class.java).setup().get()
		val d = openAdd(a, R.string.add_xtream)
		d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		assertEquals(a.getString(R.string.field_required), d.findViewById<EditText>(R.id.url)!!.error.toString())
		assertTrue("the form stays open", d.isShowing)
		assertEquals(emptyList<Source>(), app.prefs.sources)
	}

	@Test fun aPastedPanelLinkFillsTheXtreamLogin() {
		val a = Robolectric.buildActivity(SourcesActivity::class.java).setup().get()
		val d = openAdd(a, R.string.add_xtream)
		d.findViewById<EditText>(R.id.url)!!.setText("http://h:8080/get.php?username=me&password=pw&type=m3u_plus")
		d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		val s = app.prefs.sources.single()
		assertEquals("http://h:8080", s.url)
		assertEquals("me", s.username)
		assertEquals("pw", s.password)
		assertEquals("h", s.name)
	}

	@Test fun aStalkerMacIsChecked() {
		val a = Robolectric.buildActivity(SourcesActivity::class.java).setup().get()
		val d = openAdd(a, R.string.add_stalker)
		d.findViewById<EditText>(R.id.url)!!.setText("http://p.tv/stalker_portal/server/load.php")
		d.findViewById<EditText>(R.id.mac)!!.setText("00:1A:79:12:34")
		d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		assertEquals(a.getString(R.string.mac_invalid), d.findViewById<EditText>(R.id.mac)!!.error.toString())
		d.findViewById<EditText>(R.id.mac)!!.setText("00:1a:79:12:34:56")
		d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		val s = app.prefs.sources.single()
		assertEquals("00:1A:79:12:34:56", s.mac)
		assertEquals("http://p.tv/stalker_portal/server/load.php", s.url)
	}

	@Test fun anM3uAddressWithoutSchemeGetsOne() {
		val a = Robolectric.buildActivity(SourcesActivity::class.java).setup().get()
		val d = openAdd(a, R.string.add_m3u)
		d.findViewById<EditText>(R.id.url)!!.setText("example.com/list.m3u")
		d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		assertEquals("http://example.com/list.m3u", app.prefs.sources.single().url)
	}

	// ---- Guide, recordings, help ----

	@Test fun aRestoredGuideScreenShowsItsChannelNotOnNow() {
		playlist("#EXTM3U\n#EXTINF:-1 tvg-id=\"bbc\",BBC One\nhttp://h/1.ts")
		runBlocking { app.load(App.LIVE, false) }
		val key = app.channels[0].key
		@Suppress("UNCHECKED_CAST")
		(App::class.java.getDeclaredField("lists").apply { isAccessible = true }.get(app) as MutableMap<String, *>).clear()
		val a = Robolectric.buildActivity(ProgrammesActivity::class.java, Intent(app, ProgrammesActivity::class.java).putExtra("key", key)).setup().get()
		waitUntil { a.title.isNotEmpty() }
		assertEquals(a.getString(R.string.guide_for, "BBC One"), a.title.toString())
		assertTrue(isHeading(a.findViewById(R.id.listHeading)))
	}

	// ---- Guide (day headings, search, the picker) ----

	private val nowMinute = System.currentTimeMillis() / 60_000 * 60_000

	/** "20261013143000 +0000" for a UTC time, as XMLTV writes it. */
	private fun xmltv(ms: Long) =
		java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(java.time.Instant.ofEpochMilli(ms).atOffset(java.time.ZoneOffset.UTC)) + " +0000"

	private fun guideXml(vararg progs: Triple<Long, Long, String>) =
		"<tv><channel id=\"bbc1.uk\"><display-name>BBC One</display-name></channel>" +
			progs.joinToString("") { (s, e, t) ->
				"<programme start=\"${xmltv(s)}\" stop=\"${xmltv(e)}\" channel=\"bbc1.uk\"><title>$t</title><desc>$t details</desc></programme>"
			} + "</tv>"

	/** One live channel with a guide already imported, ready for a guide screen. */
	private fun channelGuide(vararg progs: Triple<Long, Long, String>) {
		server.text("/list.m3u", "#EXTM3U\n#EXTINF:-1 tvg-id=\"bbc1.uk\",BBC One\nhttp://h/1.ts")
		server.text("/guide.xml", guideXml(*progs))
		app.prefs.sources = listOf(Source("src", Source.TYPE_M3U, "List", "${server.url}/list.m3u", epgUrl = "${server.url}/guide.xml"))
		runBlocking { app.load(App.LIVE, false) }
		runBlocking { app.importGuide { } }
	}

	/** Today: one programme on now and one later. Tomorrow: one. Rows: Day, P1, P2, Day, P3. */
	private val threeProgs = arrayOf(
		Triple(nowMinute - 30 * 60_000L, nowMinute + 30 * 60_000L, "On Now"),
		Triple(nowMinute + 30 * 60_000L, nowMinute + 60 * 60_000L, "Later"),
		Triple(nowMinute + 24 * 3_600_000L, nowMinute + 25 * 3_600_000L, "Tomorrow Show"),
	)

	private fun channelGuideScreen(): Pair<ProgrammesActivity, RecyclerView> {
		channelGuide(*threeProgs)
		val a = Robolectric.buildActivity(ProgrammesActivity::class.java, ProgrammesActivity.forChannel(app, app.channels[0])).setup().get()
		val rv = a.findViewById<RecyclerView>(R.id.list)
		waitUntil { rv.adapter!!.itemCount == 5 } // Day, P1, P2, Day, P3
		return a to rv
	}

	private fun hasText(v: View, text: String): Boolean = when {
		v is TextView -> v.text?.toString()?.contains(text) == true
		v is android.view.ViewGroup -> (0 until v.childCount).any { hasText(v.getChildAt(it), text) }
		else -> false
	}

	@Test fun eachDayGetsAHeadingSoTalkBackJumpsADayAtATime() {
		val (a, rv) = channelGuideScreen()
		rv.scrollToPosition(0)
		waitUntil { rv.findViewHolderForAdapterPosition(0) != null }
		val today = Format.date(nowMinute - 30 * 60_000L)
		val tomorrow = Format.date(nowMinute + 24 * 3_600_000L)
		// One day's run of programmes, then the next day's, each under its own date.
		assertEquals(today, rv.findViewHolderForAdapterPosition(0)!!.itemView.contentDescription.toString())
		assertTrue("the day is a heading", isHeading(rv.findViewHolderForAdapterPosition(0)!!.itemView))
		assertEquals(
			"${Format.programme(Programme("k", nowMinute - 30 * 60_000L, nowMinute + 30 * 60_000L, "On Now"))}, ${a.getString(R.string.on_now)}",
			rv.findViewHolderForAdapterPosition(1)!!.itemView.contentDescription.toString(),
		)
		assertTrue("a programme is not a heading", !isHeading(rv.findViewHolderForAdapterPosition(1)!!.itemView))
		assertEquals(tomorrow, rv.findViewHolderForAdapterPosition(3)!!.itemView.contentDescription.toString())
		assertTrue(isHeading(rv.findViewHolderForAdapterPosition(3)!!.itemView))
		assertEquals("a guide with programmes offers no import button", View.GONE, a.findViewById<View>(R.id.buttons).visibility)
	}

	@Test fun aChannelGuideOpensAtTheProgrammeOnNow() {
		val (_, rv) = channelGuideScreen()
		// Not at the top of the archive: the first row on screen is the programme on now, as on iOS.
		assertEquals(1, rv.getChildAdapterPosition(rv.getChildAt(0)))
	}

	@Test fun aProgrammeThatHasNotStartedOpensItsDetails() {
		val (a, rv) = channelGuideScreen()
		rv.scrollToPosition(4)
		waitUntil { rv.findViewHolderForAdapterPosition(4) != null }
		rv.findViewHolderForAdapterPosition(4)!!.itemView.performClick()
		val dialog = ShadowDialog.getLatestDialog()
		assertNotNull("a future programme shows its details", dialog)
		assertTrue(dialog.isShowing)
		assertTrue("the details name the programme", hasText(dialog.window!!.decorView, "Tomorrow Show"))
		assertNull("and its row never starts the player", shadowOf(a).nextStartedActivity)
	}

	@Test fun guideSearchSaysWhatToTypeAndWhenTheListIsCut() {
		val progs = (1..Epg.SEARCH_LIMIT + 1).map { Triple(nowMinute + 3_600_000L + it * 60_000L, nowMinute + 3_660_000L + it * 60_000L, "News $it") }.toTypedArray()
		channelGuide(*progs)
		val a = Robolectric.buildActivity(ProgrammesActivity::class.java, ProgrammesActivity.search(app)).setup().get()
		val status = a.findViewById<TextView>(R.id.status)
		val filter = a.findViewById<EditText>(R.id.filter)
		filter.setText("n")
		waitUntil { status.text.toString() == a.getString(R.string.guide_search_hint) }
		filter.setText("news")
		waitUntil { status.text.toString() == a.getString(R.string.results_limited, Epg.SEARCH_LIMIT) }
		filter.setText("nowhere")
		waitUntil { status.text.toString() == a.getString(R.string.no_matches) }
	}

	@Test fun anEmptyGuideOffersToFetchIt() {
		// A channel with no guide at all: the screen offers the button instead of sitting empty.
		server.text("/list.m3u", "#EXTM3U\n#EXTINF:-1 tvg-id=\"bbc1.uk\",BBC One\nhttp://h/1.ts")
		app.prefs.sources = listOf(Source("src", Source.TYPE_M3U, "List", "${server.url}/list.m3u"))
		runBlocking { app.load(App.LIVE, false) }
		val a = Robolectric.buildActivity(ProgrammesActivity::class.java, ProgrammesActivity.forChannel(app, app.channels[0])).setup().get()
		waitUntil { a.findViewById<View>(R.id.buttons).visibility == View.VISIBLE }
		val bar = a.findViewById<LinearLayout>(R.id.buttons)
		val button = (0 until bar.childCount).map { bar.getChildAt(it) as Button }.first { it.text == a.getString(R.string.import_guide) }
		assertTrue(button.isFocusable)
		// No guide URL in this playlist: pressing it must not claim the guide is ready.
		button.performClick()
		waitUntil { a.findViewById<TextView>(R.id.status).text.toString() == a.getString(R.string.no_guide) }
	}

	@Test fun theChannelGuidePickerOpensAChannelGuide() {
		channelGuide(Triple(nowMinute - 30 * 60_000L, nowMinute + 30 * 60_000L, "On Now"))
		val a = Robolectric.buildActivity(ChannelGuideActivity::class.java).setup().get()
		val rv = a.findViewById<RecyclerView>(R.id.list)
		waitUntil { rv.findViewHolderForAdapterPosition(0) != null }
		val row = rv.findViewHolderForAdapterPosition(0)!!.itemView
		assertTrue("the row names the channel", row.contentDescription.toString().startsWith("BBC One"))
		assertTrue(
			"and what is on until when",
			row.contentDescription.toString().contains(a.getString(R.string.until, "On Now", Format.time(nowMinute + 30 * 60_000L))),
		)
		row.performClick()
		val started = shadowOf(a).nextStartedActivity
		assertEquals(ProgrammesActivity::class.java.name, started.component?.className)
		assertEquals(app.channels[0].key, started.getStringExtra("key"))
	}

	@Test fun recordingsHeadingCountsScheduled() {
		Recorder.schedule(app, Channel("s", "1", "BBC One", "", "http://h/1.ts"), "News", System.currentTimeMillis() + 3_600_000, System.currentTimeMillis() + 7_200_000)
		val a = Robolectric.buildActivity(RecordingsActivity::class.java).setup().get()
		assertEquals(a.getString(R.string.recordings_heading, 0, 1, 0), a.findViewById<TextView>(R.id.listHeading).text.toString())
	}

	@Test fun helpHasHeadingsToJumpBetween() {
		val a = Robolectric.buildActivity(HelpActivity::class.java).setup().get()
		val col = ((a.findViewById<View>(android.R.id.content) as android.view.ViewGroup).getChildAt(0) as android.widget.ScrollView).getChildAt(0) as LinearLayout
		val views = (0 until col.childCount).map { col.getChildAt(it) as TextView }
		assertTrue(views.count { isHeading(it) } >= 3)
		assertTrue("every paragraph reachable with a D-pad", views.all { it.isFocusable })
		assertTrue("no raw markdown read aloud", views.none { it.text.startsWith("#") })
	}

	// ---- Player ----

	private fun player(url: String, key: String, catchup: Boolean = false) = Robolectric.buildActivity(
		PlayerActivity::class.java,
		Intent(app, PlayerActivity::class.java).putExtra("url", url).putExtra("title", "BBC One").putExtra("key", key).putExtra("catchup", catchup),
	).also { players += it }

	@Test fun playerControlsAreRealLabelledButtons() {
		val a = player("${server.url}/live.ts", "").setup().get()
		shadowOf(Looper.getMainLooper()).idle()
		val ids = listOf(R.id.btnPause, R.id.btnSubs, R.id.btnAudio, R.id.btnRecord, R.id.btnCast, R.id.btnFull, R.id.btnClose)
		for (id in ids) {
			val b = a.findViewById<Button>(id)
			assertTrue(b.text.isNotBlank())
			assertTrue(b.isFocusable)
			assertEquals(View.VISIBLE, b.visibility)
		}
		assertTrue("OK on a remote lands on Pause, not on " + a.currentFocus?.let { runCatching { a.resources.getResourceEntryName(it.id) }.getOrDefault(it.javaClass.simpleName) }, a.findViewById<Button>(R.id.btnPause).isFocused)
		a.findViewById<Button>(R.id.btnRecord).performClick()
		assertEquals(a.getString(R.string.cannot_record_this), a.findViewById<TextView>(R.id.playerStatus).text.toString())
	}

	@Test fun fullScreenKeepsOneLabelledStopAndBackRestoresControls() {
		val a = player("${server.url}/live.ts", "").setup().get()
		a.findViewById<Button>(R.id.btnFull).performClick()
		val video = a.findViewById<View>(R.id.video)
		assertEquals(View.GONE, a.findViewById<View>(R.id.controls).visibility)
		assertTrue(video.isFocusable)
		assertNotNull(video.contentDescription)
		a.onBackPressedDispatcher.onBackPressed()
		assertEquals(View.VISIBLE, a.findViewById<View>(R.id.controls).visibility)
		assertNull(video.contentDescription)
		assertTrue(!a.isFinishing)
	}

	@Test fun aDroppedLiveStreamReconnects() {
		playlist("#EXTM3U\n#EXTINF:-1,BBC One\n${server.url}/live.ts")
		runBlocking { app.load(App.LIVE, false) }
		server.text("/live.ts", "gone", status = 404)
		val a = player("${server.url}/live.ts", app.channels[0].key).setup().get()
		val status = a.findViewById<TextView>(R.id.playerStatus)
		waitUntil(10_000) { status.text.toString() == a.getString(R.string.reconnecting) }
	}

	@Test fun aFailedCatchupIsNotRetriedForever() {
		playlist("#EXTM3U\n#EXTINF:-1,BBC One\n${server.url}/live.ts")
		runBlocking { app.load(App.LIVE, false) }
		server.text("/arch.ts", "gone", status = 404)
		val a = player("${server.url}/arch.ts", app.channels[0].key, catchup = true).setup().get()
		val pause = a.findViewById<Button>(R.id.btnPause)
		waitUntil(10_000) { pause.text.toString() == a.getString(R.string.retry) }
	}

	@Test fun aRecordingInProgressMakesTheRecordButtonStopIt() {
		playlist("#EXTM3U\n#EXTINF:-1,BBC One\n${server.url}/live.ts")
		runBlocking { app.load(App.LIVE, false) }
		val key = app.channels[0].key
		Recorder.active[key] = Recorder.Active(key, "BBC One", key, 0, 0)
		try {
			val a = player("${server.url}/live.ts", key).setup().get()
			val record = a.findViewById<Button>(R.id.btnRecord)
			assertEquals(a.getString(R.string.stop_recording_channel), record.text.toString())
			record.performClick()
			val sent = shadowOf(a).nextStartedService
			assertEquals(RecordService.ACTION_STOP_ONE, sent.action)
			assertEquals(key, sent.getStringExtra("key"))
		} finally { Recorder.active.clear() }
	}
}
