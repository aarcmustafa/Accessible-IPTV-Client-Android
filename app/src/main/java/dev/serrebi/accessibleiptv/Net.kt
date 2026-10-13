package dev.serrebi.accessibleiptv

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object Net {
	const val USER_AGENT = "AccessibleIPTV-Android/0.1"

	/** Opens [url] following redirects; gzip bodies are unwrapped by sniffing the magic bytes. */
	fun open(url: String, headers: Map<String, String> = emptyMap()): InputStream {
		var current = url
		repeat(5) {
			val c = URL(current).openConnection() as HttpURLConnection
			c.connectTimeout = 30_000
			c.readTimeout = 60_000
			c.instanceFollowRedirects = true
			c.setRequestProperty("User-Agent", USER_AGENT)
			headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
			val code = c.responseCode
			if (code in 300..399) { // http <-> https hops are not followed automatically.
				current = URL(URL(current), c.getHeaderField("Location") ?: throw IOException("Redirect without location")).toString()
				c.disconnect()
				return@repeat
			}
			if (code !in 200..299) { c.disconnect(); throw IOException("Server answered $code") }
			val input = BufferedInputStream(c.inputStream, 64 * 1024)
			input.mark(2)
			val gz = input.read() == 0x1f && input.read() == 0x8b
			input.reset()
			return if (gz) BufferedInputStream(GZIPInputStream(input, 64 * 1024), 64 * 1024) else input
		}
		throw IOException("Too many redirects")
	}

	fun text(url: String): String = open(url).use { it.readBytes().toString(Charsets.UTF_8) }

	/**
	 * Streams a JSON array of objects, handing each object's flat fields to [each] as strings.
	 * Xtream VOD lists run past 100 MB; reading them whole runs a phone out of memory.
	 * Nested values are skipped. A non-array body (an error object) yields nothing.
	 */
	fun forEachObject(url: String, each: (Map<String, String>) -> Unit) = open(url).use { input ->
		val r = android.util.JsonReader(java.io.InputStreamReader(input, Charsets.UTF_8))
		if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) return@use
		r.beginArray()
		val fields = HashMap<String, String>()
		while (r.hasNext()) {
			if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
			fields.clear()
			r.beginObject()
			while (r.hasNext()) {
				val name = r.nextName()
				when (r.peek()) {
					android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> fields[name] = r.nextString()
					android.util.JsonToken.BOOLEAN -> fields[name] = r.nextBoolean().toString()
					else -> r.skipValue()
				}
			}
			r.endObject()
			each(fields)
		}
		r.endArray()
	}
}
