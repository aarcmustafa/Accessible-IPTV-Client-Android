package dev.serrebi.accessibleiptv

/** Parses M3U / M3U Plus text into channels. Pure Kotlin so it is unit-testable. */
object M3uParser {
	private val attrRe = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
	private val keptAttrs = setOf("catchup", "catchup-source", "catchup-days", "catchup-timezone", "catchup-offset", "catchup-type", "timeshift", "http-user-agent")

	fun parse(sourceId: String, text: String): List<Channel> {
		val out = ArrayList<Channel>()
		var header: Map<String, String> = emptyMap()
		var entry: Map<String, String>? = null
		var name = ""
		var extGrp = ""
		for (raw in text.lineSequence()) {
			val line = raw.trim()
			when {
				line.isEmpty() -> Unit
				line.startsWith("#EXTM3U") -> header = attributes(line)
				line.startsWith("#EXTINF") -> {
					val comma = titleComma(line)
					entry = attributes(if (comma >= 0) line.substring(0, comma) else line)
					name = if (comma >= 0) line.substring(comma + 1).trim() else ""
				}
				line.startsWith("#EXTGRP:") -> extGrp = line.substringAfter(':').trim()
				line.startsWith("#") -> Unit
				entry != null -> {
					// Playlist-wide catch-up settings apply to channels without their own.
					val attrs = header.filterKeys { it in keptAttrs } + entry.filterKeys { it in keptAttrs }
					out += Channel(
						sourceId = sourceId,
						id = out.size.toString(),
						name = name.ifEmpty { entry["tvg-name"].orEmpty() }.ifEmpty { line },
						group = entry["group-title"].orEmpty().ifEmpty { extGrp }.ifEmpty { "Ungrouped" },
						url = line,
						tvgId = entry["tvg-id"].orEmpty(),
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

	/** Xtream-style M3U files mark VOD by path: /movie/ and /series/. */
	fun kindOf(url: String, tvgType: String): String = when {
		tvgType.equals("movie", true) || "/movie/" in url -> Channel.KIND_MOVIE
		tvgType.equals("series", true) || "/series/" in url -> Channel.KIND_EPISODE
		else -> Channel.KIND_LIVE
	}

	fun attributes(line: String): Map<String, String> =
		attrRe.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

	/** Index of the comma that starts the title: the first one outside quotes. */
	private fun titleComma(line: String): Int {
		var inQuotes = false
		for ((i, c) in line.withIndex()) {
			if (c == '"') inQuotes = !inQuotes
			else if (c == ',' && !inQuotes) return i
		}
		return -1
	}

	/** Splits Kodi-style "url|User-Agent=x&Referer=y" into the URL and its headers. */
	fun splitHeaders(url: String): Pair<String, Map<String, String>> {
		val bar = url.indexOf('|')
		if (bar < 0) return url to emptyMap()
		val headers = url.substring(bar + 1).split('&').mapNotNull { part ->
			val eq = part.indexOf('=')
			if (eq <= 0) null else {
				val k = part.substring(0, eq).trim().let { if (it.equals("UA", true)) "User-Agent" else it }
				k to java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8")
			}
		}.toMap()
		return url.substring(0, bar) to headers
	}
}
