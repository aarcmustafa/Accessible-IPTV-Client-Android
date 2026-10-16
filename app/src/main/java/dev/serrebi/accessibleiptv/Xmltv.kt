package dev.serrebi.accessibleiptv

import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * XMLTV helpers that need no Android classes.
 *
 * Both functions run hundreds of thousands of times per guide import, so neither uses
 * Regex or DateTimeFormatter: on Android each compiled Regex holds native ICU memory until
 * a GC finalizes it, and per-call patterns pushed native heap past 190 MB on a 2 GB TV.
 */
object Xmltv {
	private val stripTags = setOf("hd", "fhd", "uhd", "sd", "4k", "hevc", "h265", "1080p", "720p", "backup", "raw")

	/** "20261013143000 +0200" -> epoch ms UTC. Missing offset = UTC. Bad input = null. */
	fun parseTime(value: String?): Long? {
		val v = value?.trim() ?: return null
		if (v.length < 14) return null
		fun num(from: Int, len: Int): Int {
			var n = 0
			for (i in from until from + len) {
				val d = v[i] - '0'
				if (d !in 0..9) return -1
				n = n * 10 + d
			}
			return n
		}
		val y = num(0, 4); val mo = num(4, 2); val d = num(6, 2)
		val h = num(8, 2); val mi = num(10, 2); val s = num(12, 2)
		if (y < 0 || mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59 || s !in 0..60) return null
		val dt = runCatching { LocalDateTime.of(y, mo, d, h, mi, minOf(s, 59)) }.getOrNull() ?: return null
		// "+0200", "-0530" and the occasional "+02:00" all name an offset. Any other tail (a
		// zone name, junk) still counts as UTC: a guide is not thrown away over it.
		val off = v.substring(14).trim()
		var offsetSec = 0
		val sign = off.firstOrNull()?.takeIf { it == '+' || it == '-' }
		if (sign != null) {
			val digits = off.substring(1).replace(":", "")
			if (digits.length == 4 && digits.all { it.isDigit() }) {
				val oh = (digits[0] - '0') * 10 + (digits[1] - '0')
				val om = (digits[2] - '0') * 10 + (digits[3] - '0')
				if (oh in 0..18 && om in 0..59) offsetSec = (oh * 3600 + om * 60) * if (sign == '-') -1 else 1
			}
		}
		return dt.toEpochSecond(ZoneOffset.UTC) * 1000 - offsetSec * 1000L
	}

	/**
	 * Name key for matching channels to guide entries when tvg-id is missing:
	 * drops a "UK:" style prefix, quality tags and punctuation.
	 */
	fun nameKey(name: String): String {
		// A 2-3 letter country prefix followed by ":" or "|".
		var start = 0
		var i = 0
		while (i < name.length && i < 3 && name[i].isLetter() && name[i].code < 128) i++
		if (i >= 2) {
			var j = i
			while (j < name.length && name[j].isWhitespace()) j++
			if (j < name.length && (name[j] == ':' || name[j] == '|')) {
				j++
				while (j < name.length && name[j].isWhitespace()) j++
				start = j
			}
		}
		val out = StringBuilder(name.length)
		val word = StringBuilder()
		fun flush() {
			if (word.isNotEmpty()) {
				val w = word.toString()
				if (w !in stripTags) out.append(w)
				word.setLength(0)
			}
		}
		for (k in start until name.length) {
			val c = name[k]
			if (c.isLetterOrDigit()) word.append(c.lowercaseChar()) else flush()
		}
		flush()
		return out.toString()
	}
}
