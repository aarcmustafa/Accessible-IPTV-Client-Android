package dev.serrebi.accessibleiptv

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Process-wide state: the three sections (live, movies, series) and one guide. */
class App : Application() {
	lateinit var prefs: Prefs
	lateinit var epg: Epg

	private val lists = ConcurrentHashMap<String, List<Channel>>()
	private val stalkers = ConcurrentHashMap<String, Stalker>()

	/** Live channels: what the guide, recordings and catch-up work with. */
	val channels: List<Channel> get() = lists[LIVE].orEmpty()

	fun list(section: String): List<Channel> = lists[section].orEmpty()
	fun isLoaded(section: String) = lists.containsKey(section)

	override fun onCreate() {
		super.onCreate()
		prefs = Prefs(this)
		epg = Epg(this)
		Recorder.createChannel(this)
		keepContentClearOfSystemBars()
		// 0.1.0 cached as channels_<id>.json, before sections existed.
		filesDir.listFiles { f -> f.name.matches(Regex("channels_[^_]+\\.json")) }?.forEach { it.delete() }
	}

	/**
	 * Android 15+ draws every app edge to edge. Without this, the status bar covered the top
	 * of each screen and the navigation bar / Samsung taskbar covered the player's buttons.
	 * One place for every activity: pad the content view by the bars, cutout and keyboard.
	 */
	private fun keepContentClearOfSystemBars() = registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
		override fun onActivityPostCreated(a: android.app.Activity, b: android.os.Bundle?) {
			val content = a.findViewById<android.view.View>(android.R.id.content) ?: return
			androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
				val i = insets.getInsets(
					WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
				)
				v.setPadding(i.left, i.top, i.right, i.bottom)
				insets
			}
		}
		override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) = Unit
		override fun onActivityStarted(a: android.app.Activity) = Unit
		override fun onActivityResumed(a: android.app.Activity) = Unit
		override fun onActivityPaused(a: android.app.Activity) = Unit
		override fun onActivityStopped(a: android.app.Activity) = Unit
		override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) = Unit
		override fun onActivityDestroyed(a: android.app.Activity) = Unit
	})

	private fun cacheFile(src: Source, section: String) = File(filesDir, "channels_${src.id}_$section.json")

	/** Loads one section from cache, downloading sources with no cache, or all when [force]. */
	suspend fun load(section: String, force: Boolean): List<String> = withContext(Dispatchers.IO) {
		val errors = ArrayList<String>()
		val all = ArrayList<Channel>()
		for (src in prefs.sources) {
			val cache = cacheFile(src, section)
			// Providers add and drop channels: a cache older than 12 hours is fetched again.
			val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < 12 * 3_600_000L
			all += if (!force && fresh) {
				try {
					readCache(cache)
				} catch (e: Exception) {
					// A damaged cache must not empty the section: fetch again, drop it only if that fails too.
					runCatching { fetch(src, section) }.getOrElse {
						cache.delete()
						errors += "${src.name}: ${e.message ?: e.javaClass.simpleName}"
						emptyList()
					}
				}
			} else {
				try {
					fetch(src, section)
				} catch (e: Exception) {
					errors += "${src.name}: ${e.message ?: e.javaClass.simpleName}"
					// A failed download still shows the stale cache, unless that is damaged too.
					runCatching { readCache(cache) }.getOrElse { cache.delete(); emptyList() }
				}
			}
		}
		lists[section] = all
		if (section == LIVE && !epg.hasGuide) runCatching { epg.loadIndex() }
		errors
	}

	/** Downloads [section] of [src] and caches it. An M3U file fills all three caches at once. */
	private fun fetch(src: Source, section: String): List<Channel> = when {
		src.isXtream -> when (section) {
			MOVIES -> Xtream.movies(src)
			SERIES -> Xtream.series(src)
			else -> Xtream.liveChannels(src)
		}.also { cacheOrFail(cacheFile(src, section), it) }
		src.isStalker -> (if (section == LIVE) stalker(src).channels() else emptyList()).also { cacheOrFail(cacheFile(src, section), it) }
		else -> {
			val text = readSource(src.url)
			if (text.removePrefix("\uFEFF").lineSequence().none { it.trim().startsWith("#EXTM3U", true) || it.trim().startsWith("#EXTINF:", true) }) {
				throw java.io.IOException("Not an M3U playlist")
			}
			val parsed = M3uParser.parse(src.id, text)
			// A playlist that parses to nothing is a broken download, not a channel list.
			if (parsed.isEmpty() && SECTIONS.any { cacheFile(src, it).length() > 2 }) throw java.io.IOException("Playlist has no channels")
			val bySection = parsed.groupBy { if (it.kind == Channel.KIND_LIVE) LIVE else if (it.kind == Channel.KIND_MOVIE) MOVIES else SERIES }
			migrateKeys(cacheFile(src, LIVE), bySection[LIVE].orEmpty())
			for (s in SECTIONS) writeCache(cacheFile(src, s), bySection[s].orEmpty())
			// A playlist that names its own guide (x-tvg-url) needs no guide URL typed in.
			if (src.epgUrl.isBlank()) M3uParser.headerGuides(text).firstOrNull()?.let { g -> prefs.setSourceEpgUrl(src.id, g) }
			bySection[section].orEmpty()
		}
	}

	/**
	 * Channel ids can change under the user (0.2.5 and older used list position; a provider
	 * may renumber its tvg-ids). Favourites, remembered tracks, recent and scheduled
	 * recordings follow their channel by stream URL, so nobody has to set them again.
	 */
	private fun migrateKeys(oldCache: File, fresh: List<Channel>) {
		if (fresh.isEmpty() || !oldCache.exists()) return
		val old = runCatching { readCache(oldCache) }.getOrDefault(emptyList())
		// First occurrence wins: two entries sharing a URL must not steal the move.
		val byUrl = HashMap<String, Channel>()
		fresh.forEach { byUrl.putIfAbsent(it.url, it) }
		val moved = old.mapNotNull { o -> byUrl[o.url]?.let { o.key to it.key } }.toMap().filter { (a, b) -> a != b }
		if (moved.isEmpty()) return
		prefs.remapChannelKeys(moved)
		Recorder.remap(this, moved)
	}

	/** Playlist text from a URL or from a file the user picked (content://). */
	private fun readSource(url: String): String =
		if (url.startsWith("content://")) (contentResolver.openInputStream(Uri.parse(url))
			?: throw java.io.IOException("Cannot open the playlist file")).use { it.readBytes().toString(Charsets.UTF_8) }
		else Net.text(url)

	fun forgetCache(src: Source) {
		SECTIONS.forEach { cacheFile(src, it).delete() }
		lists.replaceAll { _, channels -> channels.filterNot { it.sourceId == src.id } }
		stalkers.remove(src.id)
	}

	/** Streams the cache back in; group and source strings are shared, not copied per item. */
	private fun readCache(f: File): List<Channel> {
		val out = ArrayList<Channel>()
		val pool = HashMap<String, String>()
		fun shared(s: String) = pool.getOrPut(s) { s }
		android.util.JsonReader(f.bufferedReader()).use { r ->
			r.beginArray()
			while (r.hasNext()) {
				var s = ""; var i = ""; var n = ""; var g = ""; var u = ""; var t = ""; var l = ""; var k = Channel.KIND_LIVE
				var attrs: Map<String, String> = emptyMap()
				r.beginObject()
				while (r.hasNext()) when (r.nextName()) {
					"s" -> s = shared(r.nextString()); "i" -> i = r.nextString(); "n" -> n = r.nextString()
					"g" -> g = shared(r.nextString()); "u" -> u = r.nextString(); "t" -> t = r.nextString()
					"l" -> l = r.nextString(); "k" -> k = shared(r.nextString())
					"a" -> {
						val m = HashMap<String, String>()
						r.beginObject()
						while (r.hasNext()) m[shared(r.nextName())] = r.nextString()
						r.endObject()
						if (m.isNotEmpty()) attrs = m
					}
					else -> r.skipValue()
				}
				r.endObject()
				out += Channel(s, i, n, g, u, t, l, attrs, k)
			}
			r.endArray()
		}
		return out
	}

	/** Caches a fetch, unless an empty result would replace real channels with nothing. */
	private fun cacheOrFail(f: File, list: List<Channel>) {
		// "[]" is 2 bytes, so a longer file held at least one channel once.
		if (list.isEmpty() && f.length() > 2) throw java.io.IOException("Provider returned no channels")
		writeCache(f, list)
	}

	private fun writeCache(f: File, list: List<Channel>) {
		// ponytail: one JSON file per section; 250k movies is ~50 MB. Move to SQLite if memory bites.
		// Written beside and renamed in: a process killed mid-write must not leave a torn cache.
		val tmp = File(f.parentFile, f.name + ".tmp")
		tmp.bufferedWriter().use { w ->
			w.write("[")
			list.forEachIndexed { i, c -> if (i > 0) w.write(","); w.write(c.toJson().toString()) }
			w.write("]")
		}
		try {
			java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
		} catch (e: Exception) {
			tmp.delete()
			throw e
		}
	}

	/** Group names in playlist order, with "Favorites" and "All" on top like the desktop app. */
	fun groups(section: String): List<String> = listOf(ALL, FAVORITES) +
		// A playlist with its own group named "All" or "Favorites" must not add a second row.
		list(section).mapTo(LinkedHashSet()) { it.group }.filterNot { it == ALL || it == FAVORITES }

	fun channelsIn(section: String, group: String): List<Channel> = when (group) {
		ALL -> list(section)
		FAVORITES -> prefs.favorites.let { f -> list(section).filter { it.key in f } }
		else -> list(section).filter { it.group == group }
	}

	fun channel(key: String) = lists.values.firstNotNullOfOrNull { l -> l.firstOrNull { it.key == key } }

	/**
	 * [channel], loading [section] from its cache first when the process is new. Android
	 * restores a guide or episode screen after killing the app; the lists are empty then.
	 */
	suspend fun findChannel(key: String, section: String): Channel? {
		channel(key)?.let { return it }
		if (!isLoaded(section)) load(section, force = false)
		return channel(key)
	}
	fun source(id: String) = prefs.sources.firstOrNull { it.id == id }

	private fun stalker(src: Source) = stalkers.getOrPut(src.id) { Stalker(src) }

	/** The URL to play or record. Stalker portals issue a fresh link each time. Blocking. */
	fun resolveUrl(ch: Channel): String {
		if (!ch.needsResolve) return ch.url
		val src = source(ch.sourceId) ?: error("Playlist removed")
		return stalker(src).link(ch.url.removePrefix(Channel.STALKER_SCHEME))
	}

	/** Archive URL for a past programme, or null when there is none. Blocking for Stalker. */
	fun catchupUrl(ch: Channel, start: Long, stop: Long): String? {
		if (!ch.hasCatchup || !Catchup.withinWindow(ch, start)) return null
		if (ch.needsResolve) {
			val src = source(ch.sourceId) ?: return null
			val token = java.time.Instant.ofEpochMilli(start).atZone(java.time.ZoneId.systemDefault())
				.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm"))
			return stalker(src).link(ch.url.removePrefix(Channel.STALKER_SCHEME), token, maxOf(1, (stop - start) / 60_000))
		}
		return Catchup.url(ch, start, stop)
	}

	fun episodes(show: Channel): List<Channel> = source(show.sourceId)?.let { Xtream.episodes(it, show) }.orEmpty()

	fun accountInfo(src: Source): List<Pair<String, String>> = when {
		src.isStalker -> stalker(src).accountInfo()
		src.isXtream -> Xtream.accountInfo(src)
		else -> xtreamBehind(src)?.let { Xtream.accountInfo(it) }.orEmpty()
	}

	companion object {
		const val LIVE = "live"
		const val MOVIES = "movies"
		const val SERIES = "series"
		val SECTIONS = listOf(LIVE, MOVIES, SERIES)
		const val ALL = "All"
		const val FAVORITES = "Favorites"
		fun of(c: Context) = c.applicationContext as App

		/** An M3U link from an Xtream panel (get.php?username=...) has an account behind it. */
		fun xtreamBehind(src: Source): Source? = if (src.isXtream || src.isStalker) null
		else Xtream.credentialsFromUrl(src.url)?.let { (server, u, p) -> src.copy(type = Source.TYPE_XTREAM, url = server, username = u, password = p) }

		/** Port of the desktop's search_rank; sortedBy is stable, so ties keep playlist order. */
		fun searchRanked(list: List<Channel>, q: String): List<Channel> {
			val query = q.trim().lowercase()
			if (query.isEmpty()) return list
			return list.filter { it.name.contains(query, ignoreCase = true) }.sortedWith(compareBy({ rank(it.name, query) }, { it.name.trim().length }))
		}

		private fun rank(name: String, query: String): Int {
			val n = name.trim().lowercase()
			if (n == query) return 0
			var i = n.indexOf(query)
			while (i >= 0) {
				if (i == 0 || !n[i - 1].isLetterOrDigit()) return 1
				i = n.indexOf(query, i + 1)
			}
			return 2
		}
	}

	suspend fun importGuide(progress: (Int) -> Unit): Int = withContext(Dispatchers.IO) {
		val sources = prefs.sources
		val importedChannels = channels
		val daysAhead = prefs.epgDaysAhead
		val fingerprint = guideFingerprint(importedChannels)
		val urls = sources.map { it.effectiveEpgUrl }.filter { it.isNotBlank() }.distinct()
		// No guide URL anywhere, or no channels to match against: the import would swap in
		// empty tables and throw away the guide the user already has for nothing.
		if (urls.isEmpty() || importedChannels.isEmpty()) return@withContext 0
		val now = System.currentTimeMillis()
		val back = maxOf(1, importedChannels.maxOfOrNull { it.catchupDays } ?: 0).coerceAtMost(7)
		val n = epg.import(urls, importedChannels, now - back * 86_400_000L, now + daysAhead * 86_400_000L, progress)
		prefs.lastEpgImport = if (sources == prefs.sources && daysAhead == prefs.epgDaysAhead) now else 0
		prefs.guideFingerprint = fingerprint
		n
	}

	/**
	 * The import keeps only guide channels the playlist can match, so a playlist that
	 * gained channels since the last import has channels with no guide at all.
	 * The fingerprint is the set of match keys; any change means re-import now.
	 */
	private fun guideFingerprint(list: List<Channel> = channels): Int = list.mapTo(HashSet()) { it.tvgId.lowercase().ifEmpty { Xmltv.nameKey(it.name) } }.hashCode()

	/**
	 * A guide the playlist or the user named is there, no import has happened yet today, or the
	 * playlist changed since the last one. A finished import counts as fresh even when nothing
	 * in it matched: [Epg.hasGuide] is false then, and going by that re-downloaded the whole
	 * guide (100 MB and up) on every single load, forever.
	 */
	fun guideStale(): Boolean = channels.isNotEmpty() && prefs.sources.any { it.effectiveEpgUrl.isNotBlank() } &&
		(prefs.lastEpgImport == 0L || System.currentTimeMillis() - prefs.lastEpgImport > 86_400_000L || guideFingerprint() != prefs.guideFingerprint)

}
