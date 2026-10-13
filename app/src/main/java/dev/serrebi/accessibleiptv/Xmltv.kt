package dev.serrebi.accessibleiptv

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** XMLTV helpers that need no Android classes. */
object Xmltv {
	private val fmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
	private val stripTags = setOf("hd", "fhd", "uhd", "sd", "4k", "hevc", "h265", "1080p", "720p", "backup", "raw")

	/** "20261013143000 +0200" -> epoch ms UTC. Missing offset = UTC. Bad input = null. */
	fun parseTime(value: String?): Long? {
		val v = value?.trim() ?: return null
		if (v.length < 14) return null
		val dt = runCatching { LocalDateTime.parse(v.substring(0, 14), fmt) }.getOrNull() ?: return null
		val off = v.substring(14).trim()
		val offset = if (off.length == 5 && (off[0] == '+' || off[0] == '-'))
			runCatching { ZoneOffset.of("${off.substring(0, 3)}:${off.substring(3)}") }.getOrDefault(ZoneOffset.UTC)
		else ZoneOffset.UTC
		return dt.toInstant(offset).toEpochMilli()
	}

	/**
	 * Name key for matching channels to guide entries when tvg-id is missing:
	 * drops a "UK:" style prefix, quality tags and punctuation.
	 */
	fun nameKey(name: String): String {
		val noPrefix = name.replace(Regex("^[A-Za-z]{2,3}\\s*[:|]\\s*"), "")
		return noPrefix.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
			.filter { it.isNotEmpty() && it !in stripTags }.joinToString("")
	}
}
