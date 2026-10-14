package dev.serrebi.accessibleiptv

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

/** A provider on 127.0.0.1 for tests: playlists, player_api, guides, portals. JDK only. */
class FakeServer : AutoCloseable {
	private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
	private val routes = ConcurrentHashMap<String, (HttpExchange) -> Unit>()

	/** Every request as path?query, in arrival order. */
	val requests = CopyOnWriteArrayList<String>()
	val headers = CopyOnWriteArrayList<Map<String, String>>()

	init {
		server.executor = Executors.newCachedThreadPool()
		server.createContext("/") { ex ->
			requests += ex.requestURI.toString()
			headers += ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
			try {
				(routes[ex.requestURI.path] ?: { it.reply(404, "missing") })(ex)
			} finally {
				ex.close()
			}
		}
		server.start()
	}

	val url get() = "http://127.0.0.1:${server.address.port}"

	fun on(path: String, handler: (HttpExchange) -> Unit) { routes[path] = handler }
	fun text(path: String, body: String, status: Int = 200) = on(path) { it.reply(status, body) }
	fun bytes(path: String, body: ByteArray) = on(path) { it.reply(200, body) }

	override fun close() = server.stop(0)

	companion object {
		fun HttpExchange.reply(status: Int, body: String) = reply(status, body.toByteArray())
		fun HttpExchange.reply(status: Int, body: ByteArray) {
			sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
			if (body.isNotEmpty()) responseBody.use { it.write(body) }
		}

		fun HttpExchange.redirect(to: String, status: Int = 302) {
			responseHeaders.add("Location", to)
			sendResponseHeaders(status, -1)
		}

		fun HttpExchange.query(): Map<String, String> = requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }
			.associate { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }

		fun gzip(s: String): ByteArray = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(s.toByteArray()) } }.toByteArray()
	}
}
