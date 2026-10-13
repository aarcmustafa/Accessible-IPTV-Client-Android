package dev.serrebi.accessibleiptv

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Catch-up (archive) URLs, ported from the desktop client's rules. */
object Catchup {
	private val tokens = listOf(
		"\${start}" to "start", "\${timestamp}" to "lutc", "\${duration}" to "duration", "\${end}" to "end",
		"{utc}" to "start", "{lutc}" to "lutc", "{utcstart}" to "start", "{utcend}" to "end",
		"{duration}" to "duration", "{S}" to "start", "{E}" to "end", "{L}" to "lutc", "{D}" to "duration",
		"{Y}" to "Y", "{m}" to "m", "{d}" to "d", "{H}" to "H", "{M}" to "M",
		"{y}" to "eY", "{e}" to "ed", "{h}" to "eH", "{i}" to "eM",
	)
	private val xtreamLive = Regex("""^/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.[A-Za-z0-9]+)?$""")
	private val tokenFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")

	/** True when [startMs] is inside the channel's archive window (no window = allowed). */
	fun withinWindow(ch: Channel, startMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
		val days = ch.catchupDays
		return days <= 0 || startMs >= nowMs - days * 86_400_000L
	}

	/** The archive URL for a past programme, or null when the channel cannot provide one. */
	fun url(ch: Channel, startMs: Long, endMs: Long, zone: ZoneId = ZoneId.systemDefault(), nowMs: Long = System.currentTimeMillis()): String? {
		val offsetMs = ((ch.attrs["catchup-offset"]?.toDoubleOrNull() ?: 0.0) * 3_600_000).toLong()
		val start = startMs - offsetMs
		val end = endMs - offsetMs
		val durationMin = maxOf(1L, (endMs - startMs) / 60_000)
		val source = ch.attrs["catchup-source"].orEmpty().trim()
		val (base, headerTail) = ch.url.indexOf('|').let { if (it < 0) ch.url to "" else ch.url.substring(0, it) to ch.url.substring(it) }

		if (source.isEmpty()) {
			if (!ch.attrs["catchup"].equals("xc", true)) return null
			val uri = runCatching { java.net.URI(base) }.getOrNull() ?: return null
			val m = xtreamLive.matchEntire(uri.rawPath ?: "") ?: return null
			val (user, pass, id) = m.destructured
			val token = wallClock(start, ch, zone).format(tokenFmt)
			val query = uri.rawQuery?.let { "?$it" }.orEmpty()
			return "${uri.scheme}://${uri.rawAuthority}/timeshift/$user/$pass/$durationMin/$token/$id.ts$query$headerTail"
		}

		if (tokens.any { it.first in source }) {
			var expanded = source
			for ((t, kind) in tokens) expanded = expanded.replace(t, value(kind, start, end, nowMs, zone))
			if ('?' in base && expanded.startsWith("?")) expanded = "&" + expanded.substring(1)
			return base + expanded + headerTail
		}

		// Path style: <source>/<stream id>/<start>/<duration>/.
		var src = source.trimEnd('/')
		if (!src.substringAfterLast('/').all(Char::isDigit)) {
			val id = base.trimEnd('/').substringAfterLast('/').substringBefore('.')
			if (id.isEmpty()) return null
			src = "$src/$id"
		}
		val startLocal = wallClock(start, ch, zone)
		val type = (ch.attrs["catchup-type"] ?: ch.attrs["catchup"] ?: "xc").lowercase()
		val url = if (type == "flussonic") "$src/${startLocal.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))}-$durationMin.m3u8"
		else "$src/${startLocal.format(tokenFmt)}/$durationMin/"
		return url + headerTail
	}

	/** Archive start on the provider's clock: "UTC" (Dispatcharr), an IANA zone, else local. */
	private fun wallClock(ms: Long, ch: Channel, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(
		when (val tz = ch.attrs["catchup-timezone"].orEmpty().trim()) {
			"" -> zone
			"UTC", "GMT", "Z", "Etc/UTC" -> ZoneOffset.UTC
			else -> runCatching { ZoneId.of(tz) }.getOrDefault(zone)
		},
	)

	private fun value(kind: String, start: Long, end: Long, now: Long, zone: ZoneId): String {
		val s = Instant.ofEpochMilli(start).atZone(zone)
		val e = Instant.ofEpochMilli(end).atZone(zone)
		return when (kind) {
			"start" -> (start / 1000).toString()
			"end" -> (end / 1000).toString()
			"lutc" -> (now / 1000).toString()
			"duration" -> maxOf(1, (end - start) / 1000).toString()
			"Y" -> "%04d".format(s.year); "m" -> "%02d".format(s.monthValue); "d" -> "%02d".format(s.dayOfMonth)
			"H" -> "%02d".format(s.hour); "M" -> "%02d".format(s.minute)
			"eY" -> "%04d".format(e.year); "ed" -> "%02d".format(e.dayOfMonth)
			"eH" -> "%02d".format(e.hour); "eM" -> "%02d".format(e.minute)
			else -> ""
		}
	}
}
