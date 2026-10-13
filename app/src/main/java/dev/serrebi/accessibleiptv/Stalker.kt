package dev.serrebi.accessibleiptv

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Stalker / Ministra portal client: the MAG-style handshake, ported from the desktop app. */
class Stalker(private val src: Source) {
	private var token = ""
	private var issued = 0L
	private val endpoint = src.url.trim().let {
		if (it.endsWith("portal.php", true) || it.endsWith("load.php", true)) it else baseUrl(it) + "/portal.php"
	}
	private val base = baseUrl(src.url)

	private fun call(params: Map<String, String>, withToken: Boolean = true): JSONObject {
		val q = (params + ("JsHttpRequest" to "1-xml")).entries.joinToString("&") { "${it.key}=${Source.enc(it.value)}" }
		val c = URL("$endpoint?$q").openConnection() as HttpURLConnection
		c.connectTimeout = 30_000
		c.readTimeout = 60_000
		c.setRequestProperty("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/535.21 (KHTML, like Gecko) MAG250 stbapp Safari/535.21")
		c.setRequestProperty("X-User-Agent", "Model: MAG420; Link: WiFi")
		c.setRequestProperty("Referer", "$base/c/")
		c.setRequestProperty("Cookie", "stb_lang=en; timezone=UTC; mac=${src.mac}")
		if (withToken && token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
		try {
			if (c.responseCode !in 200..299) throw IOException("Portal answered ${c.responseCode}")
			return JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
		} finally {
			c.disconnect()
		}
	}

	@Synchronized private fun ensureToken() {
		if (token.isNotEmpty() && System.currentTimeMillis() - issued < 25 * 60_000) return
		val hs = call(mapOf("type" to "stb", "action" to "handshake", "token" to "", "prehash" to "0"), withToken = false)
		token = hs.optJSONObject("js")?.optString("token").orEmpty().ifEmpty { hs.optString("token") }
		if (token.isEmpty()) throw IOException("Portal handshake failed")
		issued = System.currentTimeMillis()
		// Many portals authenticate by MAC alone; log in only when credentials were given.
		if (src.username.isNotEmpty() || src.password.isNotEmpty()) {
			val auth = call(mapOf("type" to "stb", "action" to "login", "login" to src.username, "password" to src.password))
			auth.optJSONObject("js")?.optString("token")?.takeIf { it.isNotEmpty() }?.let { token = it }
		}
	}

	fun channels(): List<Channel> {
		ensureToken()
		val genres = HashMap<String, String>()
		runCatching {
			val g = call(mapOf("type" to "itv", "action" to "get_genres")).optJSONArray("js")
			for (i in 0 until (g?.length() ?: 0)) g!!.getJSONObject(i).let { genres[it.optString("id")] = it.optString("title") }
		}
		val data = call(mapOf("type" to "itv", "action" to "get_all_channels", "force_ch_link_check" to "1"))
			.optJSONObject("js")?.optJSONArray("data") ?: return emptyList()
		val out = ArrayList<Channel>()
		for (i in 0 until data.length()) {
			val r = data.getJSONObject(i)
			val name = r.optString("name")
			val cmd = r.optString("cmd")
			if (name.isEmpty() || cmd.isEmpty()) continue
			val archive = r.optString("allow_timeshift") == "1" || r.optString("archive") == "1"
			out += Channel(
				sourceId = src.id,
				id = r.optString("id").ifEmpty { out.size.toString() },
				name = name,
				group = genres[r.optString("tv_genre_id")] ?: r.optString("tv_genre_title").ifEmpty { "Stalker" },
				url = Channel.STALKER_SCHEME + cmd,
				tvgId = r.optString("xmltv_id").ifEmpty { r.optString("epg_id") },
				logo = r.optString("logo"),
				attrs = if (archive) mapOf("catchup" to "stalker", "catchup-days" to r.optString("tv_archive_duration").ifEmpty { "0" }) else emptyMap(),
			)
		}
		return out
	}

	/** A playable link for a channel's cmd; [startToken]/[minutes] ask for catch-up. */
	fun link(cmd: String, startToken: String? = null, minutes: Long = 0): String {
		ensureToken()
		val p = mutableMapOf("type" to "itv", "action" to "create_link", "cmd" to cmd)
		if (startToken != null) p += mapOf("download" to "0", "save" to "0", "series" to "1", "forced_storage" to "0", "start" to startToken, "duration" to minutes.toString())
		val res = call(p)
		val link = res.optJSONObject("js")?.optString("cmd").orEmpty().ifEmpty { res.optString("cmd") }
		if (link.isEmpty()) throw IOException("Portal did not return a stream link")
		return link.removePrefix("ffmpeg ").removePrefix("auto ").trim()
	}

	fun accountInfo(): List<Pair<String, String>> {
		ensureToken()
		val js = call(mapOf("type" to "account_info", "action" to "get_main_info")).optJSONObject("js") ?: return emptyList()
		val labels = mapOf("tariff_plan" to "Plan", "end_date" to "Expires", "phone" to "Expires", "account_balance" to "Balance", "fname" to "Name")
		return labels.mapNotNull { (k, label) -> js.optString(k).takeIf { it.isNotBlank() && it != "null" }?.let { label to it } }.distinctBy { it.first }
	}

	companion object {
		/** Portal base without a trailing /c, /portal.php or /server/load.php. */
		fun baseUrl(url: String): String {
			var u = Xtream.normalizeServer(url)
			for (tail in listOf("/portal.php", "/server/load.php", "/load.php", "/c")) if (u.endsWith(tail, true)) u = u.dropLast(tail.length)
			return u.trimEnd('/')
		}
	}
}
