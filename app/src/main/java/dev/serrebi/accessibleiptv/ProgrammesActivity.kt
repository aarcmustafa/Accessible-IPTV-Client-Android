package dev.serrebi.accessibleiptv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What's on now, guide search across all channels, or one channel's guide with catch-up. */
class ProgrammesActivity : ListScreen() {
	/** One guide row: a day heading, or a programme under it. The iOS guide groups the same way. */
	private sealed interface Row {
		data class Day(val label: String) : Row
		data class Prog(val p: Programme, val ch: Channel) : Row
	}

	private lateinit var adapter: RowAdapter<Row>
	private var all: List<Row.Prog> = emptyList()
	/** [all] with a day heading in front of each day's run of programmes. */
	private var shown: List<Row> = emptyList()
	private var key: String? = null
	private var oneChannel = false
	private var searchMode = false
	private var guideButton: Button? = null
	private var searchJob: Job? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val asked = intent.getStringExtra("key")
		key = asked
		searchMode = intent.getBooleanExtra("search", false)
		oneChannel = asked != null
		title = when {
			asked != null -> app.channel(asked)?.let { getString(R.string.guide_for, it.name) }.orEmpty()
			searchMode -> getString(R.string.search_guide)
			else -> getString(R.string.whats_on_now)
		}
		adapter = RowAdapter { item, row, l1, l2 ->
			when (item) {
				is Row.Day -> {
					// A day heading: one stop, read once, so heading navigation jumps a day at a time.
					l1.setTextAppearance(android.R.style.TextAppearance_Large)
					l2.text = ""
					bindRow(row, item.label, {}, emptyList())
					row.heading()
				}
				is Row.Prog -> {
					l1.setTextAppearance(android.R.style.TextAppearance_Medium)
					row.heading(false) // a recycled row must not stay a heading
					bindProgramme(item.p, item.ch, row, l1, l2)
				}
			}
		}
		list.adapter = adapter

		if (searchMode) {
			// Guide search queries the database as you type, debounced.
			enableFilter { q ->
				searchJob?.cancel()
				searchJob = lifecycleScope.launch {
					delay(400)
					if (!app.isLoaded(App.LIVE)) app.load(App.LIVE, force = false)
					all = if (q.length < 2) emptyList() else withContext(Dispatchers.IO) {
						app.epg.search(q, app.channels).map { (p, c) -> Row.Prog(p, c) }
					}
					show("")
					status.status(when {
						q.isEmpty() -> ""
						q.length < 2 -> getString(R.string.guide_search_hint)
						all.size >= Epg.SEARCH_LIMIT -> getString(R.string.results_limited)
						all.isEmpty() -> getString(R.string.no_matches)
						else -> resources.getQuantityString(R.plurals.programme_count, all.size, all.size)
					})
				}
			}
			heading.text = resources.getQuantityString(R.plurals.programme_count, 0, 0)
			return
		}
		if (!oneChannel) enableFilter { q -> show(q) }

		lifecycleScope.launch {
			loadGuide()
			startAtNow()
		}
	}

	/** Reads this screen's guide rows and shows them. Call from the main thread. */
	private suspend fun loadGuide() {
		status.status(getString(R.string.loading))
		// After Android killed the app, the restored screen has to load the channel list again.
		if (!app.isLoaded(App.LIVE)) app.load(App.LIVE, force = false)
		val ch = key?.let { app.channel(it) }
		if (key != null && ch == null) return finish()
		ch?.let { title = getString(R.string.guide_for, it.name) }
		all = withContext(Dispatchers.IO) {
			if (ch != null) {
				val now = System.currentTimeMillis()
				val back = if (ch.hasCatchup) maxOf(1, ch.catchupDays) else 0
				app.epg.schedule(ch, now - back * 86_400_000L, now + app.prefs.epgDaysAhead * 86_400_000L).map { Row.Prog(it, ch) }
			} else app.epg.onNow(app.channels).map { (p, c) -> Row.Prog(p, c) }
		}
		show("")
		status.status(if (all.isEmpty()) if (ch != null) getString(R.string.no_guide_for, ch.name) else getString(R.string.no_guide) else "")
	}

	/** Opens a channel's guide at the programme on now, with the reader there, as on iOS. */
	private fun startAtNow() {
		if (key == null) return
		val i = shown.indexOfFirst { ((it as? Row.Prog)?.p?.stop ?: 0L) > System.currentTimeMillis() }
		if (i <= 0) return
		list.scrollToPosition(i)
		list.post {
			list.findViewHolderForAdapterPosition(i)?.itemView
				?.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
		}
	}

	private fun show(q: String) {
		val kept = if (q.isEmpty()) all else all.filter { it.p.title.contains(q, true) || it.ch.name.contains(q, true) }
		shown = withDayHeadings(kept)
		adapter.submit(shown)
		heading.text = resources.getQuantityString(R.plurals.programme_count, kept.size, kept.size)
		showGuideButton(all.isEmpty() && !searchMode)
	}

	/** [progs] in order, each day's run under its date, so TalkBack can jump a day at a time. */
	private fun withDayHeadings(progs: List<Row.Prog>): List<Row> {
		val out = ArrayList<Row>(progs.size + 4)
		var day = ""
		for (p in progs) {
			val date = Format.date(p.p.start)
			if (date != day) { day = date; out += Row.Day(date) }
			out += p
		}
		return out
	}

	/** An empty guide offers to fetch it, as the iOS guide does. */
	private fun showGuideButton(on: Boolean) {
		val bar = findViewById<LinearLayout>(R.id.buttons)
		if (!on && guideButton == null) return
		val b = guideButton ?: addButton(getString(R.string.import_guide)) { importGuide() }.also { guideButton = it }
		b.visibility = if (on) View.VISIBLE else View.GONE
		bar.visibility = if (on) View.VISIBLE else View.GONE
	}

	private fun importGuide() {
		status.status(getString(R.string.importing_guide))
		lifecycleScope.launch {
			try {
				val count = app.importGuide { }
				loadGuide()
				status.status(getString(if (count > 0) R.string.guide_imported else R.string.no_guide))
			} catch (e: Exception) {
				status.status(getString(R.string.guide_failed, e.message ?: e.javaClass.simpleName))
			}
		}
	}

	private fun catchupState(p: Programme, ch: Channel, now: Long) = p.stop <= now && ch.hasCatchup && Catchup.withinWindow(ch, p.start)

	private fun bindProgramme(p: Programme, ch: Channel, row: View, l1: TextView, l2: TextView) {
		val now = System.currentTimeMillis()
		val state = ArrayList<String>(2)
		// Rows on another day name their day, then what the programme is doing.
		if (Format.date(p.start) != Format.date(now)) state += Format.date(p.start)
		when {
			p.stop <= now -> state += getString(if (catchupState(p, ch, now)) R.string.catchup_available else R.string.ended)
			p.start <= now -> state += getString(R.string.on_now)
		}
		val stateText = state.joinToString(", ")
		l1.text = Format.programme(p)
		l2.text = listOf(if (oneChannel) "" else ch.name, stateText).filter { it.isNotEmpty() }.joinToString(", ")
		val speech = listOf(Format.programme(p), if (oneChannel) "" else ch.name, stateText).filter { it.isNotEmpty() }.joinToString(", ")
		val actions = ArrayList<RowAction>()
		if (p.description.isNotBlank()) actions += RowAction(getString(R.string.programme_details)) { alert(p.title, detailsText(p)) }
		if (p.stop > now) actions += RowAction(getString(R.string.schedule_recording)) { schedule(p, ch) }
		if (catchupState(p, ch, now)) actions += RowAction(getString(R.string.download)) {
			withCatchup(p, ch) { url ->
				Recorder.download(this, url, ch.name, p.title, expectMs = p.stop - p.start)
				status.status(getString(R.string.downloading, p.title))
			}
		}
		if (!oneChannel) actions += RowAction(getString(R.string.channel_guide)) { startActivity(forChannel(this, ch)) }
		bindRow(row, speech, { open(p, ch) }, actions)
	}

	/** Title, day, times and the description, as the iOS guide reads a programme out. */
	private fun detailsText(p: Programme) = listOf("${Format.date(p.start)}, ${Format.programme(p)}", p.description.trim())
		.filter { it.isNotEmpty() }.joinToString("\n\n")

	private fun open(p: Programme, ch: Channel) {
		val now = System.currentTimeMillis()
		// A programme that has not started yet shows its details; recording it is a row action.
		if (p.start > now) { alert(p.title, detailsText(p)); return }
		if (p.stop > now) return Player.open(this, ch, status)
		withCatchup(p, ch) { url -> Player.play(this, url, "${ch.name}: ${p.title}", ch.key, catchup = true) }
	}

	/** Builds the archive URL off the main thread (Stalker asks the portal) and hands it on. */
	private fun withCatchup(p: Programme, ch: Channel, then: (String) -> Unit) {
		lifecycleScope.launch {
			val url = withContext(Dispatchers.IO) { runCatching { app.catchupUrl(ch, p.start, p.stop) }.getOrNull() }
			if (url == null) status.status(getString(R.string.no_catchup)) else then(url)
		}
	}

	private fun schedule(p: Programme, ch: Channel) {
		if (!Recorder.canScheduleExact(this)) {
			// Exact alarms need the user's say-so on Android 12+; without it the start may drift.
			status.status(getString(R.string.exact_alarm_needed))
			runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:$packageName"))) }
		}
		Recorder.schedule(this, ch, p.title, p.start, p.stop)
		status.status(getString(R.string.recording_scheduled, p.title, Format.dateTime(p.start)))
	}

	companion object {
		fun onNow(c: Context) = Intent(c, ProgrammesActivity::class.java)
		fun search(c: Context) = Intent(c, ProgrammesActivity::class.java).putExtra("search", true)
		fun forChannel(c: Context, ch: Channel) = Intent(c, ProgrammesActivity::class.java).putExtra("key", ch.key)
	}
}
