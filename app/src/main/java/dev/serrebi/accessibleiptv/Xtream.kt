package dev.serrebi.accessibleiptv

import org.json.JSONArray
import org.json.JSONObject

/** Xtream Codes player_api client: live channels and account info. */
object Xtream {
	fun normalizeServer(s: String): String {
		val t = s.trim().trimEnd('/')
		return if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "http://$t"
	}

	private fun api(src: Source, action: String = "") =
		"${src.url}/player_api.php?username=${Source.enc(src.username)}&password=${Source.enc(src.password)}" +
			if (action.isEmpty()) "" else "&action=$action"

	fun liveChannels(src: Source): List<Channel> {
		val cats = categories(src, "get_live_categories")
		val user = Source.enc(src.username)
		val pass = Source.enc(src.password)
		val out = ArrayList<Channel>()
		Net.forEachObject(api(src, "get_live_streams")) { o ->
			val id = o["stream_id"].orEmpty()
			val archive = o["tv_archive"] == "1"
			out += Channel(
				sourceId = src.id,
				id = id,
				name = o["name"].orEmpty(),
				group = cats[o["category_id"]] ?: "Ungrouped",
				// TS, not m3u8: some servers (Dispatcharr) answer m3u8 with HTTP 500.
				url = "${src.url}/live/$user/$pass/$id.ts",
				tvgId = o["epg_channel_id"]?.takeUnless { it == "null" }.orEmpty(),
				attrs = if (archive) mapOf("catchup" to "xc", "catchup-days" to o["tv_archive_duration"].orEmpty()) else emptyMap(),
			)
		}
		return out
	}

	/** category_id -> name. The returned strings are shared by every item in the group. */
	private fun categories(src: Source, action: String): Map<String, String> {
		val out = HashMap<String, String>()
		Net.forEachObject(api(src, action)) { out[it["category_id"].orEmpty()] = it["category_name"].orEmpty() }
		return out
	}

	fun movies(src: Source): List<Channel> {
		val cats = categories(src, "get_vod_categories")
		val user = Source.enc(src.username)
		val pass = Source.enc(src.password)
		val out = ArrayList<Channel>()
		Net.forEachObject(api(src, "get_vod_streams")) { o ->
			val id = o["stream_id"].orEmpty()
			val ext = o["container_extension"].orEmpty().ifEmpty { "mp4" }
			out += Channel(src.id, "m$id", o["name"].orEmpty(), cats[o["category_id"]] ?: "Ungrouped",
				"${src.url}/movie/$user/$pass/$id.$ext", kind = Channel.KIND_MOVIE)
		}
		return out
	}

	fun series(src: Source): List<Channel> {
		val cats = categories(src, "get_series_categories")
		val out = ArrayList<Channel>()
		Net.forEachObject(api(src, "get_series")) { o ->
			// url holds the series id; episodes are fetched when the show is opened.
			val plot = o["plot"].orEmpty()
			out += Channel(src.id, "s${o["series_id"]}", o["name"].orEmpty(), cats[o["category_id"]] ?: "Ungrouped",
				o["series_id"].orEmpty(), attrs = if (plot.isBlank()) emptyMap() else mapOf("plot" to plot), kind = Channel.KIND_SERIES)
		}
		return out
	}

	/**
	 * Providers often title episodes "Show Name - S01E02"; after the season and episode
	 * are spoken, that is pure repetition. Keeps only a real episode title, else "".
	 */
	// Compiled once: each Regex holds native ICU memory until a GC finalizes it.
	private val yearSuffix = Regex("\\s*\\(\\d{4}\\)\\s*$")
	private val seasonEpisode = Regex("(?i)\\bS\\d{1,3}\\s*E\\d{1,4}\\b")

	fun episodeTitle(raw: String, show: String): String {
		var t = raw.trim()
		val showBase = show.replace(yearSuffix, "")
		for (prefix in listOf(show, showBase)) if (prefix.isNotEmpty() && t.startsWith(prefix, true)) t = t.substring(prefix.length)
		t = t.replace(seasonEpisode, "")
		return t.trim(' ', '-', ':', '.', '|', '_')
	}

	/** Episodes of a show, in season order, named "Season 1, Episode 2: Title". */
	fun episodes(src: Source, show: Channel): List<Channel> {
		val eps = JSONObject(Net.text(api(src, "get_series_info") + "&series_id=${show.url}")).optJSONObject("episodes") ?: return emptyList()
		val user = Source.enc(src.username)
		val pass = Source.enc(src.password)
		val out = ArrayList<Channel>()
		for (season in eps.keys().asSequence().sortedBy { it.toIntOrNull() ?: 0 }) {
			val list = eps.getJSONArray(season)
			for (i in 0 until list.length()) {
				val e = list.getJSONObject(i)
				val num = e.optString("episode_num")
				val title = episodeTitle(e.optString("title"), show.name)
				val ext = e.optString("container_extension").ifEmpty { "mp4" }
				val label = if (title.isEmpty()) "Season $season, Episode $num" else "Season $season, Episode $num: $title"
				out += Channel(src.id, "e${e.optString("id")}", label, show.name,
					"${src.url}/series/$user/$pass/${e.optString("id")}.$ext",
					attrs = mapOf("plot" to (e.optJSONObject("info")?.optString("plot").orEmpty())), kind = Channel.KIND_EPISODE)
			}
		}
		return out
	}

	/** user_info from the server, as label/value lines ready to read out. */
	fun accountInfo(src: Source): List<Pair<String, String>> {
		val u = JSONObject(Net.text(api(src))).optJSONObject("user_info") ?: return emptyList()
		val out = ArrayList<Pair<String, String>>()
		out += "Status" to u.optString("status")
		val exp = u.optString("exp_date").toLongOrNull()
		if (exp != null) {
			val days = (exp * 1000 - System.currentTimeMillis()) / 86_400_000
			out += "Expires" to "${Format.date(exp * 1000)}, $days days left"
		} else out += "Expires" to "Never"
		out += "Trial" to if (u.optString("is_trial") == "1") "Yes" else "No"
		out += "Connections in use" to "${u.optString("active_cons")} of ${u.optString("max_connections")}"
		u.optString("created_at").toLongOrNull()?.let { out += "Created" to Format.date(it * 1000) }
		return out
	}
}
