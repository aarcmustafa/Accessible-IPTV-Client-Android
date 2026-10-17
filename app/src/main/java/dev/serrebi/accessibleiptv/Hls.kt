package dev.serrebi.accessibleiptv

import java.io.InputStream
import java.io.OutputStream

/**
 * HLS (m3u8) downloads, the desktop's ffmpeg role: the playlist is read, then its segments
 * are copied in order — one request at a time, so a download is one provider connection —
 * and a live playlist is followed until the recording's end time.
 * ponytail: no EXT-X-BYTERANGE support; IPTV providers serve whole segments.
 */
object Hls {
	/** Copies [url] into [out]: the playlist, then every segment in order, each fetched once. */
	fun download(
		open: (String) -> InputStream,
		url: String,
		out: OutputStream,
		done: () -> Boolean,
		sleep: (Long) -> Unit = { Thread.sleep(it) },
	): Boolean {
		var wrote = false
		var media = url
		var variant = false
		var next = 0L
		var init = false
		while (!done()) {
			val body = text(open(media))
			if (!variant && isMaster(body)) {
				// A master playlist names its renditions; the first one carries the segments.
				media = firstVariant(body, media) ?: return wrote
				variant = true
				continue
			}
			if (!init) {
				// A fragmented MP4 stream needs its initialisation segment before its media.
				mapUri(body, media)?.let { wrote = copy(open(it), out) > 0 || wrote }
				init = true
			}
			val segments = segments(body, media)
			val sequence = mediaSequence(body)
			var added = false
			for (i in (next - sequence).coerceAtLeast(0).toInt() until segments.size) {
				if (done()) break
				wrote = copy(open(segments[i]), out) > 0 || wrote
				next = sequence + i + 1
				added = true
			}
			if (hasEndList(body)) break
			// A live playlist: wait for it to have something new before asking again.
			if (!added) sleep(reloadMs(body))
		}
		return wrote
	}

	/** A master playlist lists renditions (EXT-X-STREAM-INF) instead of segments. */
	fun isMaster(body: String) = body.lineSequence().any { it.trim().startsWith("#EXT-X-STREAM-INF", true) }

	/** A finished playlist: a live one keeps growing until the stream ends. */
	fun hasEndList(body: String) = body.lineSequence().any { it.trim().startsWith("#EXT-X-ENDLIST", true) }

	/** The first rendition URI of a master playlist, resolved against [base]. */
	fun firstVariant(body: String, base: String): String? {
		var wanted = false
		for (raw in body.lineSequence()) {
			val line = raw.trim()
			if (line.startsWith("#EXT-X-STREAM-INF", true)) { wanted = true; continue }
			if (wanted && line.isNotEmpty() && !line.startsWith("#")) return resolve(base, line)
		}
		return null
	}

	/** The segment URIs of a media playlist, in order, resolved against [base]. */
	fun segments(body: String, base: String): List<String> = body.lineSequence().map { it.trim() }
		.filter { it.isNotEmpty() && !it.startsWith("#") }
		.map { resolve(base, it) }
		.toList()

	/** The media sequence of the first listed segment; a live playlist slides, so it is the index. */
	fun mediaSequence(body: String) = tag(body, "#EXT-X-MEDIA-SEQUENCE:")?.trim()?.toLongOrNull() ?: 0L

	/** The initialisation segment named by EXT-X-MAP (fragmented MP4), when there is one. */
	fun mapUri(body: String, base: String): String? {
		val line = body.lineSequence().firstOrNull { it.trim().startsWith("#EXT-X-MAP", true) } ?: return null
		val uri = Regex("""URI\s*=\s*"([^"]+)"""").find(line)?.groupValues?.get(1) ?: return null
		return resolve(base, uri)
	}

	/** How long to wait before asking a live playlist for more: half its target duration. */
	private fun reloadMs(body: String) = ((tag(body, "#EXT-X-TARGETDURATION:")?.trim()?.toDoubleOrNull() ?: 4.0) * 500).coerceAtLeast(1_000.0).toLong()

	private fun tag(body: String, name: String) =
		body.lineSequence().firstOrNull { it.trim().startsWith(name, true) }?.substringAfter(name)

	private fun resolve(base: String, uri: String) =
		if (uri.startsWith("http://", true) || uri.startsWith("https://", true)) uri
		else runCatching { java.net.URI(base).resolve(uri).toString() }.getOrDefault(uri)

	private fun text(input: InputStream) = input.use { it.readBytes().toString(Charsets.UTF_8) }

	private fun copy(input: InputStream, out: OutputStream): Long = input.use { it.copyTo(out) }
}
