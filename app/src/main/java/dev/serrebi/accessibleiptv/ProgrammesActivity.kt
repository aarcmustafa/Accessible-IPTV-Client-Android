package dev.serrebi.accessibleiptv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What's on now, guide search across all channels, or one channel's guide with catch-up. */
class ProgrammesActivity : ListScreen() {
	private lateinit var adapter: RowAdapter<Pair<Programme, Channel>>
	private var all: List<Pair<Programme, Channel>> = emptyList()
	private var oneChannel = false
	private var searchJob: Job? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val key = intent.getStringExtra("key")
		val searchMode = intent.getBooleanExtra("search", false)
		oneChannel = key != null
		title = when {
			key != null -> app.channel(key)?.let { getString(R.string.guide_for, it.name) }.orEmpty()
			searchMode -> getString(R.string.search_guide)
			else -> getString(R.string.whats_on_now)
		}
		adapter = RowAdapter { (p, c), row, l1, l2 -> bindProgramme(p, c, row, l1, l2) }
		list.adapter = adapter

		if (searchMode) {
			// Guide search queries the database as you type, debounced.
			enableFilter { q ->
				searchJob?.cancel()
				searchJob = lifecycleScope.launch {
					delay(400)
					if (!app.isLoaded(App.LIVE)) app.load(App.LIVE, force = false)
					all = if (q.length < 2) emptyList() else withContext(Dispatchers.IO) { app.epg.search(q, app.channels) }
					show("")
					status.status(if (q.length >= 2 && all.isEmpty()) getString(R.string.no_matches) else "")
				}
			}
			heading.text = resources.getQuantityString(R.plurals.programme_count, 0, 0)
			return
		}
		if (!oneChannel) enableFilter { q -> show(q) }

		status.status(getString(R.string.loading))
		lifecycleScope.launch {
			// After Android killed the app, the restored screen has to load the channel list again.
			if (!app.isLoaded(App.LIVE)) app.load(App.LIVE, force = false)
			val ch = key?.let { app.channel(it) }
			if (key != null && ch == null) return@launch finish()
			ch?.let { title = getString(R.string.guide_for, it.name) }
			all = withContext(Dispatchers.IO) {
				if (ch != null) {
					val now = System.currentTimeMillis()
					val back = if (ch.hasCatchup) maxOf(1, ch.catchupDays) else 0
					app.epg.schedule(ch, now - back * 86_400_000L, now + app.prefs.epgDaysAhead * 86_400_000L).map { it to ch }
				} else app.epg.onNow(app.channels)
			}
			show("")
			status.status(if (all.isEmpty()) if (ch != null) getString(R.string.no_guide_for, ch.name) else getString(R.string.no_guide) else "")
			// Start at the programme on now, not at the top of the archive.
			if (ch != null) {
				val i = all.indexOfFirst { it.first.stop > System.currentTimeMillis() }
				if (i > 0) list.scrollToPosition(i)
			}
		}
	}

	private fun show(q: String) {
		val shown = if (q.isEmpty()) all else all.filter { (p, c) -> p.title.contains(q, true) || c.name.contains(q, true) }
		adapter.submit(shown)
		heading.text = resources.getQuantityString(R.plurals.programme_count, shown.size, shown.size)
	}

	private fun catchupState(p: Programme, ch: Channel, now: Long) = p.stop <= now && ch.hasCatchup && Catchup.withinWindow(ch, p.start)

	private fun bindProgramme(p: Programme, ch: Channel, row: android.view.View, l1: android.widget.TextView, l2: android.widget.TextView) {
		val now = System.currentTimeMillis()
		val state = when {
			p.stop <= now -> getString(if (catchupState(p, ch, now)) R.string.catchup_available else R.string.ended)
			p.start <= now -> getString(R.string.on_now)
			else -> Format.date(p.start).takeIf { it != Format.date(now) }.orEmpty()
		}
		l1.text = Format.programme(p)
		l2.text = listOf(if (oneChannel) "" else ch.name, state).filter { it.isNotEmpty() }.joinToString(", ")
		val speech = listOf(Format.programme(p), if (oneChannel) "" else ch.name, state).filter { it.isNotEmpty() }.joinToString(", ")
		val actions = ArrayList<RowAction>()
		if (p.description.isNotBlank()) actions += RowAction(getString(R.string.programme_details)) { alert(p.title, "${Format.programme(p)}\n\n${p.description}") }
		if (p.stop > now) actions += RowAction(getString(R.string.schedule_recording)) { schedule(p, ch) }
		if (catchupState(p, ch, now)) actions += RowAction(getString(R.string.download)) { withCatchup(p, ch) { url -> Recorder.download(this, url, ch.name, p.title, expectMs = p.stop - p.start); status.status(getString(R.string.downloading, p.title)) } }
		if (!oneChannel) actions += RowAction(getString(R.string.channel_guide)) { startActivity(forChannel(this, ch)) }
		bindRow(row, speech, { open(p, ch) }, actions)
	}

	private fun open(p: Programme, ch: Channel) {
		if (p.stop > System.currentTimeMillis()) return Player.open(this, ch, status)
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
