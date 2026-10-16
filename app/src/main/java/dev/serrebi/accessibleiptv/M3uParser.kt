package dev.serrebi.accessibleiptv

/** Parses M3U / M3U Plus text into channels. Pure Kotlin so it is unit-testable. */
object M3uParser {
	// Quoted, single-quoted or bare values, like the desktop's _M3U_ATTR_RE.
	private val attrRe = Regex("""([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^",\s]+))""")
	private val keptAttrs = setOf("catchup", "catchup-source", "catchup-days", "catchup-timezone", "catchup-offset", "catchup-type", "timeshift", Channel.CHNO)

	/** Player options a playlist sets per channel, as HTTP headers. Same names as the desktop. */
	private val headerOpts = mapOf(
		"http-user-agent" to "User-Agent", "http-referrer" to "Referer", "http-referer" to "Referer",
		"referer" to "Referer", "referrer" to "Referer", "http-origin" to "Origin", "origin" to "Origin",
		"http-cookie" to "Cookie", "cookie" to "Cookie",
	)

	/**
	 * Channel ids are tvg-id plus name, not list position: favourites, remembered audio
	 * tracks and scheduled recordings must still find the channel after the provider adds,
	 * drops or reorders entries. A repeated name gets "#2", "#3".
	 */
	fun parse(sourceId: String, text: String): List<Channel> {
		val out = ArrayList<Channel>()
		val seen = HashMap<String, Int>()
		var header: Map<String, String> = emptyMap()
		var entry: Map<String, String>? = null
		var opts = HashMap<String, String>()
		var name = ""
		var extGrp = ""
		for (raw in text.removePrefix("\uFEFF").lineSequence()) {
			val line = raw.trim()
			when {
				line.isEmpty() -> Unit
				line.startsWith("#EXTM3U", true) -> header = attributes(line)
				line.startsWith("#EXTINF", true) -> {
					val comma = titleComma(line)
					entry = attributes(if (comma >= 0) line.substring(0, comma) else line)
					name = if (comma >= 0) line.substring(comma + 1).trim() else ""
					opts = HashMap()
					entry.forEach { (k, v) -> headerOpts[k]?.let { opts[it] = v } }
				}
				line.startsWith("#EXTGRP:", true) -> extGrp = line.substringAfter(':').trim()
				line.startsWith("#EXTVLCOPT:", true) || line.startsWith("#KODIPROP:", true) -> {
					val kv = line.substringAfter(':')
					val k = kv.substringBefore('=').trim().lowercase()
					if ('=' in kv) headerOpts[k]?.let { opts[it] = kv.substringAfter('=').trim() }
				}
				line.startsWith("#") -> Unit
				entry != null -> {
					// Playlist-wide catch-up settings apply to channels without their own.
					val attrs = header.filterKeys { it in keptAttrs } + entry.filterKeys { it in keptAttrs }
					val title = name.ifEmpty { entry["tvg-name"].orEmpty() }.ifEmpty { line }
					val tvgId = entry["tvg-id"].orEmpty()
					val base = "$tvgId|$title"
					val n = seen.merge(base, 1, Int::plus)!!
					out += Channel(
						sourceId = sourceId,
						id = if (n == 1) base else "$base#$n",
						name = title,
						group = entry["group-title"].orEmpty().ifEmpty { extGrp }.ifEmpty { "Ungrouped" },
						url = withHeaders(line, opts),
						tvgId = tvgId,
						logo = entry["tvg-logo"].orEmpty(),
						attrs = attrs,
						kind = kindOf(line, entry["tvg-type"].orEmpty()),
					)
					entry = null
					extGrp = ""
				}
			}
		}
		return out
	}

	/** Guide URLs the #EXTM3U header names (x-tvg-url / url-tvg, comma separated). http(s) only. */
	fun headerGuides(text: String): List<String> {
		val first = text.removePrefix("\uFEFF").trimStart().lineSequence().firstOrNull()?.trim().orEmpty()
		if (!first.startsWith("#EXTM3U", true)) return emptyList()
		val attrs = attributes(first)
		// A bare (unquoted) list stops at the first comma in the regex; take it to the next space.
		val raw = listOf("x-tvg-url", "url-tvg").firstNotNullOfOrNull { k ->
			attrs[k]?.let { v -> Regex("""(?i)\b$k\s*=\s*([^"'\s]\S*)""").find(first)?.groupValues?.get(1) ?: v }
		} ?: return emptyList()
		return raw.split(',').map { it.trim() }
			.filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
			.map { it.substringBefore("://").lowercase() + "://" + it.substringAfter("://") }
			.distinct()
	}

	/** Xtream-style M3U files mark VOD by path: /movie/ and /series/. */
	fun kindOf(url: String, tvgType: String): String = when {
		tvgType.equals("movie", true) || "/movie/" in url -> Channel.KIND_MOVIE
		tvgType.equals("series", true) || "/series/" in url -> Channel.KIND_EPISODE
		else -> Channel.KIND_LIVE
	}

	fun attributes(line: String): Map<String, String> =
		attrRe.findAll(line).associate { m -> m.groupValues[1].lowercase() to (m.groups[2] ?: m.groups[3] ?: m.groups[4])!!.value }

	/** Index of the comma that starts the title: the first one outside quotes. */
	private fun titleComma(line: String): Int {
		var quote: Char? = null
		var previous: Char? = null
		for ((i, c) in line.withIndex()) {
			if (c == quote) quote = null
			else if (quote == null && previous == '=' && (c == '"' || c == '\'')) quote = c
			else if (c == ',' && quote == null) return i
			if (!c.isWhitespace()) previous = c
		}
		return -1
	}

	/** Adds playlist headers to the URL's "|Key=Value" tail; ones already in the tail win. */
	private fun withHeaders(url: String, opts: Map<String, String>): String {
		if (opts.isEmpty()) return url
		val (plain, existing) = splitHeaders(url)
		val merged = opts + existing
		return plain + "|" + merged.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
	}

	/**
	 * Splits Kodi-style "url|User-Agent=x&Referer=y" into the URL and its headers.
	 * Header names are canonicalised: a hand-written "user-agent" must be the same header
	 * as the "User-Agent" the player looks up, not a second one.
	 */
	fun splitHeaders(url: String): Pair<String, Map<String, String>> {
		val bar = url.indexOf('|')
		if (bar < 0) return url to emptyMap()
		val headers = url.substring(bar + 1).split('&').mapNotNull { part ->
			val eq = part.indexOf('=')
			if (eq <= 0) null else {
				val k = part.substring(0, eq).trim().let { canonicalHeaders[it.lowercase()] ?: it }
				k to runCatching { java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8") }.getOrDefault(part.substring(eq + 1))
			}
		}.toMap()
		return url.substring(0, bar) to headers
	}

	private val canonicalHeaders = mapOf(
		"ua" to "User-Agent", "user-agent" to "User-Agent", "referer" to "Referer", "referrer" to "Referer",
		"origin" to "Origin", "cookie" to "Cookie",
	)
}
