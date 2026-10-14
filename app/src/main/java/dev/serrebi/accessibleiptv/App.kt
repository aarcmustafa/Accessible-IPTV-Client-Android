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
			all += try {
				// Providers add and drop channels: a cache older than 12 hours is fetched again.
				val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < 12 * 3_600_000L
				if (!force && fresh) readCache(cache) else fetch(src, section)
			} catch (e: Exception) {
				errors += "${src.name}: ${e.message ?: e.javaClass.simpleName}"
				if (cache.exists()) runCatching { readCache(cache) }.getOrDefault(emptyList()) else emptyList()
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
		}.also { writeCache(cacheFile(src, section), it) }
		src.isStalker -> (if (section == LIVE) stalker(src).channels() else emptyList()).also { writeCache(cacheFile(src, section), it) }
		else -> {
			val parsed = M3uParser.parse(src.id, readSource(src.url))
			val bySection = parsed.groupBy { if (it.kind == Channel.KIND_LIVE) LIVE else if (it.kind == Channel.KIND_MOVIE) MOVIES else SERIES }
			for (s in SECTIONS) writeCache(cacheFile(src, s), bySection[s].orEmpty())
			bySection[section].orEmpty()
		}
	}

	/** Playlist text from a URL or from a file the user picked (content://). */
	private fun readSource(url: String): String =
		if (url.startsWith("content://")) contentResolver.openInputStream(Uri.parse(url))!!.use { it.readBytes().toString(Charsets.UTF_8) }
		else Net.text(url)

	fun forgetCache(src: Source) = SECTIONS.forEach { cacheFile(src, it).delete() }

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

	private fun writeCache(f: File, list: List<Channel>) {
		// ponytail: one JSON file per section; 250k movies is ~50 MB. Move to SQLite if memory bites.
		f.bufferedWriter().use { w ->
			w.write("[")
			list.forEachIndexed { i, c -> if (i > 0) w.write(","); w.write(c.toJson().toString()) }
			w.write("]")
		}
	}

	/** Group names in playlist order, with "Favorites" and "All" on top like the desktop app. */
	fun groups(section: String): List<String> = listOf(ALL, FAVORITES) + list(section).mapTo(LinkedHashSet()) { it.group }

	fun channelsIn(section: String, group: String): List<Channel> = when (group) {
		ALL -> list(section)
		FAVORITES -> prefs.favorites.let { f -> list(section).filter { it.key in f } }
		else -> list(section).filter { it.group == group }
	}

	fun channel(key: String) = lists.values.firstNotNullOfOrNull { l -> l.firstOrNull { it.key == key } }

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

	fun accountInfo(src: Source): List<Pair<String, String>> = if (src.isStalker) stalker(src).accountInfo() else Xtream.accountInfo(src)

	suspend fun importGuide(progress: (Int) -> Unit): Int = withContext(Dispatchers.IO) {
		val urls = prefs.sources.map { it.effectiveEpgUrl }.filter { it.isNotBlank() }.distinct()
		val now = System.currentTimeMillis()
		val back = maxOf(1, channels.maxOfOrNull { it.catchupDays } ?: 0).coerceAtMost(7)
		val n = epg.import(urls, channels, now - back * 86_400_000L, now + prefs.epgDaysAhead * 86_400_000L, progress)
		prefs.lastEpgImport = now
		prefs.guideFingerprint = guideFingerprint()
		n
	}

	/**
	 * The import keeps only guide channels the playlist can match, so a playlist that
	 * gained channels since the last import has channels with no guide at all.
	 * The fingerprint is the set of match keys; any change means re-import now.
	 */
	private fun guideFingerprint(): Int = channels.mapTo(HashSet()) { it.tvgId.lowercase().ifEmpty { Xmltv.nameKey(it.name) } }.hashCode()

	fun guideStale(): Boolean =
		channels.isNotEmpty() && (System.currentTimeMillis() - prefs.lastEpgImport > 86_400_000L || guideFingerprint() != prefs.guideFingerprint)

	companion object {
		const val LIVE = "live"
		const val MOVIES = "movies"
		const val SERIES = "series"
		val SECTIONS = listOf(LIVE, MOVIES, SERIES)
		const val ALL = "All"
		const val FAVORITES = "Favorites"
		fun of(c: Context) = c.applicationContext as App
	}
}
