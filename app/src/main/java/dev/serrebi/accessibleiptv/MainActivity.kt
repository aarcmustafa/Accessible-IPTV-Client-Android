package dev.serrebi.accessibleiptv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
	private val app by lazy { App.of(this) }
	private lateinit var status: TextView
	private lateinit var details: TextView
	private lateinit var search: EditText
	private lateinit var channelsHeading: TextView
	private lateinit var groupAdapter: RowAdapter<Pair<String, Int>>
	private lateinit var channelAdapter: RowAdapter<Channel>
	private var section = App.LIVE
	private var group = App.ALL
	private var searchJob: Job? = null

	// Back with search text clears the search first, like Escape on the desktop.
	// Voice search: the system recogniser's best guess goes into the search field.
	private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
		val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return@registerForActivityResult
		search.setText(heard)
		search.setSelection(heard.length)
	}

	private fun startVoice() {
		val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
			.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
			.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
		try { voice.launch(i) } catch (_: ActivityNotFoundException) { status.status(getString(R.string.voice_unavailable)) }
	}

	private val clearSearchOnBack = object : OnBackPressedCallback(false) {
		override fun handleOnBackPressed() = search.setText("")
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)
		status = findViewById(R.id.status)
		details = findViewById(R.id.details)
		search = findViewById(R.id.filter)
		channelsHeading = findViewById(R.id.channelsHeading)
		findViewById<View>(R.id.groupsHeading).heading()
		channelsHeading.heading()

		groupAdapter = RowAdapter { (name, count), row, l1, l2 ->
			l1.text = groupLabel(name)
			l2.text = count.toString()
			val sel = if (name == group) ", " + getString(R.string.selected) else ""
			bindRow(row, "${l1.text}, $count$sel", { selectGroup(name) }, emptyList())
		}
		channelAdapter = RowAdapter { ch, row, l1, l2 -> bindChannel(ch, row, l1, l2) }
		setupList(R.id.groups, groupAdapter)
		setupList(R.id.channels, channelAdapter)

		search.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
			override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
			override fun afterTextChanged(s: Editable?) {
				clearSearchOnBack.isEnabled = !s.isNullOrEmpty()
				searchJob?.cancel()
				// Debounced so typing stays responsive and results are not re-announced per key.
				searchJob = lifecycleScope.launch { delay(300); showChannels() }
			}
		})
		onBackPressedDispatcher.addCallback(this, clearSearchOnBack)

		findViewById<RadioGroup>(R.id.sections).setOnCheckedChangeListener { _, id ->
			selectSection(when (id) { R.id.secMovies -> App.MOVIES; R.id.secSeries -> App.SERIES; else -> App.LIVE })
		}
		findViewById<View>(R.id.btnSources).setOnClickListener { startActivity(Intent(this, SourcesActivity::class.java)) }
		findViewById<View>(R.id.btnOnNow).setOnClickListener { startActivity(ProgrammesActivity.onNow(this)) }
		findViewById<View>(R.id.btnGuideSearch).setOnClickListener { startActivity(ProgrammesActivity.search(this)) }
		findViewById<View>(R.id.btnRecordings).setOnClickListener { startActivity(Intent(this, RecordingsActivity::class.java)) }
		findViewById<View>(R.id.btnMore).setOnClickListener { showMore(it) }
		findViewById<View>(R.id.btnVoice).setOnClickListener { startVoice() }

		group = app.prefs.lastGroup.ifEmpty { App.ALL }
		load(force = false)
		// Once a day, quietly: only speaks up when there is something to install.
		if (System.currentTimeMillis() - app.prefs.lastUpdateCheck > 86_400_000L) checkUpdates(quiet = true)
	}

	override fun onResume() {
		super.onResume()
		if (SourcesActivity.changed) {
			SourcesActivity.changed = false
			load(force = false, all = true)
		} else channelAdapter.notifyDataSetChanged() // favourites or guide may have changed
	}

	private fun setupList(id: Int, adapter: RecyclerView.Adapter<*>) = findViewById<RecyclerView>(id).apply {
		layoutManager = LinearLayoutManager(this@MainActivity)
		this.adapter = adapter
	}

	private fun groupLabel(name: String) = when (name) {
		App.ALL -> getString(R.string.all_in_section)
		App.FAVORITES -> getString(R.string.favorites)
		else -> name
	}

	private fun countText(n: Int) = resources.getQuantityString(
		when (section) { App.MOVIES -> R.plurals.movie_count; App.SERIES -> R.plurals.series_count; else -> R.plurals.channel_count }, n, n,
	)

	private fun sectionName() = getString(when (section) { App.MOVIES -> R.string.movies; App.SERIES -> R.string.series; else -> R.string.channels })

	private fun bindChannel(ch: Channel, row: View, l1: TextView, l2: TextView) {
		val fav = ch.key in app.prefs.favorites
		val nn = if (ch.isLive) app.epg.nowNext(ch) else emptyList()
		l1.text = if (fav) getString(R.string.favorite_name, ch.name) else ch.name
		// Just what is on and until when; next and the description are under Details.
		val now = nn.firstOrNull()?.let { getString(R.string.until, it.title, Format.time(it.stop)) }
		// While searching, results come from every group: say which when there is no guide.
		l2.text = now ?: if (search.text.isNotEmpty()) ch.group else ""
		val speech = Format.sentences(listOfNotNull(
			if (fav) "${ch.name}, ${getString(R.string.favorite)}" else ch.name,
			l2.text.toString().ifEmpty { null },
		))
		bindRow(row, speech, { Player.open(this, ch, status) }, channelActions(ch, fav))
		row.setOnFocusChangeListener { _, has -> if (has) details.text = detailsText(ch) }
	}

	private fun channelActions(ch: Channel, fav: Boolean): List<RowAction> {
		val out = ArrayList<RowAction>()
		if (ch.kind != Channel.KIND_SERIES) out += RowAction(getString(R.string.play_external)) { Player.open(this, ch, status, external = true) }
		out += RowAction(getString(if (fav) R.string.remove_favorite else R.string.add_favorite)) {
			val now = app.prefs.toggleFavorite(ch.key)
			status.status(getString(if (now) R.string.added_favorite else R.string.removed_favorite, ch.name))
			if (group == App.FAVORITES) showChannels() else channelAdapter.notifyDataSetChanged()
			refreshGroupCounts()
		}
		out += RowAction(getString(R.string.programme_details)) { alert(ch.name, detailsText(ch)) }
		if (ch.isLive) {
			out += RowAction(getString(if (ch.hasCatchup) R.string.guide_and_catchup else R.string.channel_guide)) { startActivity(ProgrammesActivity.forChannel(this, ch)) }
			out += RowAction(getString(R.string.record_now)) {
				Recorder.start(this, ch, app.epg.nowNext(ch).firstOrNull()?.title.orEmpty())
				status.status(getString(R.string.recording_channel, ch.name))
			}
		} else if (ch.kind == Channel.KIND_MOVIE || ch.kind == Channel.KIND_EPISODE) {
			out += RowAction(getString(R.string.download)) {
				Recorder.download(this, ch.url, ch.name, "")
				status.status(getString(R.string.downloading, ch.name))
			}
		}
		return out
	}

	private fun detailsText(ch: Channel): String {
		val lines = ArrayList<String>()
		if (ch.isLive) {
			val nn = app.epg.nowNext(ch)
			if (nn.isEmpty()) lines += getString(R.string.no_guide_for, ch.name)
			nn.getOrNull(0)?.let { lines += getString(R.string.now_programme, Format.programme(it)); if (it.description.isNotBlank()) lines += it.description }
			nn.getOrNull(1)?.let { lines += getString(R.string.next_programme, Format.programme(it)) }
		} else {
			lines += ch.group
			ch.attrs["plot"]?.takeIf { it.isNotBlank() }?.let { lines += it }
		}
		if (app.prefs.showStreamUrl && ch.kind != Channel.KIND_SERIES) lines += getString(R.string.stream_url_is, ch.url)
		return lines.joinToString("\n")
	}

	private fun load(force: Boolean, all: Boolean = false) {
		if (app.prefs.sources.isEmpty()) {
			status.status(getString(R.string.no_sources))
			groupAdapter.submit(emptyList())
			channelAdapter.submit(emptyList())
			return
		}
		status.status(getString(if (force) R.string.refreshing else R.string.loading))
		lifecycleScope.launch {
			// Live always loads (the guide needs it); the other sections only once they are opened.
			val sections = if (all) App.SECTIONS.filter { it == App.LIVE || it == section || app.isLoaded(it) } else listOf(section)
			val errors = sections.flatMap { app.load(it, force) }
			refreshGroupCounts()
			showChannels()
			status.status(
				if (errors.isEmpty()) countText(app.list(section).size)
				else getString(R.string.load_errors, errors.joinToString("; ")),
			)
			
			// Guide older than a day, or built for a different channel list: fetch it now.
			if (section == App.LIVE && app.guideStale()) importGuide()
		}
	}

	private fun selectSection(s: String) {
		section = s
		group = App.ALL
		if (app.isLoaded(s)) { refreshGroupCounts(); showChannels(); status.status("") }
		else load(force = false)
	}

	private fun refreshGroupCounts() {
		val items = app.list(section)
		val counts = items.groupingBy { it.group }.eachCount()
		if (group !in counts && group != App.ALL && group != App.FAVORITES) group = App.ALL
		groupAdapter.submit(app.groups(section).map { g ->
			g to when (g) {
				App.ALL -> items.size
				App.FAVORITES -> app.channelsIn(section, App.FAVORITES).size
				else -> counts[g] ?: 0
			}
		})
	}

	private fun selectGroup(name: String) {
		group = name
		if (section == App.LIVE) app.prefs.lastGroup = name
		groupAdapter.notifyDataSetChanged()
		showChannels()
		// Move the reader to the list heading so the result is heard straight away.
		channelsHeading.post { channelsHeading.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
	}

	private fun showChannels() {
		val q = search.text.toString().trim()
		lifecycleScope.launch {
			val list = withContext(Dispatchers.Default) {
				// Search covers every group in the section; otherwise show the chosen group.
				if (q.isEmpty()) app.channelsIn(section, group) else app.list(section).filter { it.name.contains(q, ignoreCase = true) }
			}
			channelAdapter.submit(list)
			findViewById<RecyclerView>(R.id.channels).contentDescription = sectionName()
			channelsHeading.text = if (q.isEmpty()) getString(R.string.list_heading, groupLabel(group), list.size)
			else resources.getQuantityString(R.plurals.result_count, list.size, list.size)
		}
	}

	private fun importGuide() {
		status.status(getString(R.string.importing_guide))
		lifecycleScope.launch {
			try {
				val n = app.importGuide { count -> runOnUiThread { status.text = getString(R.string.importing_guide_count, count) } }
				channelAdapter.notifyDataSetChanged()
				status.status(getString(R.string.guide_imported))
			} catch (e: Exception) {
				status.status(getString(R.string.guide_failed, e.message ?: e.javaClass.simpleName))
			}
		}
	}

	private fun showMore(anchor: View) {
		val items = listOf(
			getString(R.string.refresh_playlists) to { load(force = true, all = true) },
			getString(R.string.import_guide) to { importGuide() },
			getString(R.string.account_info) to { showAccountInfo() },
			getString(R.string.settings) to { startActivity(Intent(this, SettingsActivity::class.java)) },
			getString(R.string.check_updates) to { checkUpdates(quiet = false) },
			getString(R.string.help) to { startActivity(Intent(this, HelpActivity::class.java)) },
		)
		PopupMenu(this, anchor).apply {
			items.forEachIndexed { i, (label, _) -> menu.add(0, i, i, label) }
			setOnMenuItemClickListener { items[it.itemId].second(); true }
		}.show()
	}

	private fun checkUpdates(quiet: Boolean) {
		app.prefs.lastUpdateCheck = System.currentTimeMillis()
		if (!quiet) status.status(getString(R.string.checking_updates))
		lifecycleScope.launch {
			val result = withContext(Dispatchers.IO) { runCatching { Updater.check(BuildConfig.VERSION_NAME) } }
			val r = result.getOrNull()
			when {
				r != null -> AlertDialog.Builder(this@MainActivity)
					.setTitle(getString(R.string.update_available, r.version))
					.setMessage(r.notes.take(1500))
					.setPositiveButton(R.string.install) { _, _ -> installUpdate(r) }
					.setNegativeButton(R.string.later, null).show()
				quiet -> Unit
				result.isFailure -> status.status(getString(R.string.update_failed, result.exceptionOrNull()?.message.orEmpty()))
				else -> status.status(getString(R.string.up_to_date))
			}
		}
	}

	private fun installUpdate(r: Updater.Release) {
		// First time only: Android sends the user to allow installs from this app.
		if (!Updater.ensureInstallAllowed(this)) { status.status(getString(R.string.allow_installs)); return }
		status.status(getString(R.string.downloading_update))
		lifecycleScope.launch {
			val apk = withContext(Dispatchers.IO) { runCatching { Updater.download(this@MainActivity, r) } }
			apk.onSuccess { Updater.install(this@MainActivity, it); status.status(getString(R.string.installing_update)) }
				.onFailure { status.status(getString(R.string.update_failed, it.message.orEmpty())) }
		}
	}

	private fun showAccountInfo() {
		val sources = app.prefs.sources.filter { it.isXtream || it.isStalker }
		if (sources.isEmpty()) { alert(getString(R.string.account_info), getString(R.string.account_info_none)); return }
		status.status(getString(R.string.loading))
		lifecycleScope.launch {
			val text = withContext(Dispatchers.IO) {
				sources.joinToString("\n\n") { src ->
					val lines = runCatching { app.accountInfo(src) }.fold(
						{ info -> info.joinToString("\n") { "${it.first}: ${it.second}" } },
						{ getString(R.string.account_info_failed, it.message ?: "") },
					)
					"${src.name}\n$lines"
				}
			}
			status.status("")
			AlertDialog.Builder(this@MainActivity).setTitle(R.string.account_info).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
		}
	}
}
