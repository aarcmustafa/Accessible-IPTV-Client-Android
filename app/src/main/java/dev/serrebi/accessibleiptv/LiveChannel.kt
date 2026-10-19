package dev.serrebi.accessibleiptv

import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One provider stream per device, the desktop's rule: every socket takes this gate, so a
 * second, different stream is refused until the first stops. Live channels share through
 * [LiveChannel]'s tap instead of taking a socket of their own.
 */
object Streams {
	private var holder: String? = null

	/** [id] takes the stream; false when a different stream already holds it. */
	@Synchronized fun acquire(id: String): Boolean {
		if (holder != null && holder != id) return false
		holder = id
		return true
	}

	/** True when [id] may take the stream now. */
	@Synchronized fun free(id: String) = holder == null || holder == id

	@Synchronized fun release(id: String) { if (holder == id) holder = null }

	/** The stream in use, for messages and checks. */
	@Synchronized fun current(): String? = holder

	fun liveId(channelKey: String) = "live:$channelKey"
	/** HLS lives cannot share, so the player's HLS socket is its own id: recording the same channel is refused. */
	fun hlsId(channelKey: String) = "hls:$channelKey"
	fun urlId(url: String) = "url:$url"
	fun downloadId(url: String) = "dl:$url"

	/** Tests: forget the holder between cases. */
	@Synchronized fun reset() { holder = null }
}

/**
 * The bytes of a live stream as they arrive, with one read position per consumer. A reader
 * that fell too far behind jumps to the oldest retained byte: for live TV that is the right
 * recovery, and it means no reader can ever stall the socket or the recording.
 */
class Tap(private val capacity: Int) {
	private val lock = ReentrantLock()
	private val ready = lock.newCondition()
	private val buf = ByteArray(capacity)
	/** Absolute position of the oldest retained byte and of the next byte to write. */
	private var base = 0L
	private var end = 0L
	@Volatile private var open = true

	/** Adds [len] bytes; a full ring drops its oldest bytes rather than blocking the socket. */
	fun write(b: ByteArray, off: Int, len: Int) = lock.withLock {
		var i = off
		var n = len
		while (n > 0) {
			val chunk = minOf(n, capacity)
			if (end - base + chunk > capacity) base = end + chunk - capacity
			put(b, i, chunk)
			end += chunk
			i += chunk
			n -= chunk
		}
		ready.signalAll()
	}

	/** A reader starting at the live edge, which is where a live stream must start. */
	fun reader(retained: Boolean = false): InputStream = lock.withLock { Reader(if (retained) base else end) }

	/** Drops everything unread: a reconnected stream is a fresh live edge, the old bytes a time jump. */
	fun clear() = lock.withLock { base = end; ready.signalAll() }

	fun close() = lock.withLock { open = false; ready.signalAll() }

	private fun put(b: ByteArray, off: Int, len: Int) {
		val at = (end % capacity).toInt()
		val first = minOf(len, capacity - at)
		System.arraycopy(b, off, buf, at, first)
		if (len > first) System.arraycopy(b, off + first, buf, 0, len - first)
	}

	private inner class Reader(private var pos: Long) : InputStream() {
		private var closed = false

		override fun close() = lock.withLock { closed = true; ready.signalAll() }

		override fun read(): Int {
			val one = ByteArray(1)
			return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
		}

		override fun read(b: ByteArray, off: Int, len: Int): Int {
			if (len == 0) return 0
			try {
				lock.withLock {
					while (true) {
						if (closed) return -1
						if (pos < base) pos = base
						val available = end - pos
						if (available > 0) {
							val n = minOf(len.toLong(), available).toInt()
							val at = (pos % capacity).toInt()
							val first = minOf(n, capacity - at)
							System.arraycopy(buf, at, b, off, first)
							if (n > first) System.arraycopy(buf, 0, b, off + first, n - first)
							pos += n
							return n
						}
						// Nothing new yet: a live stream has no end until it is closed or stopped.
						if (!open) return -1
						ready.await(200, TimeUnit.MILLISECONDS)
					}
				}
			} catch (e: InterruptedException) {
				// A stop pressed, or a player releasing: that reader's stream is over.
				Thread.currentThread().interrupt()
				return -1
			}
		}

		override fun available() = lock.withLock { if (closed) 0 else (end - maxOf(pos, base)).coerceAtLeast(0).toInt() }
	}
}

/**
 * The single socket of one live channel. [open] supplies a fresh connection; the channel keeps
 * reading it into [tap] and reconnects on drops, so every consumer sees one uninterrupted
 * stream. The socket lives here, not in the player: leaving the player does not end a
 * recording, and pressing Record does not open a second connection.
 */
class LiveChannel internal constructor(val key: String, private val cancel: () -> Unit, private val open: () -> InputStream) {
	private val tap = Tap(TAP_BYTES)
	private var consumers = 0
	private var firstReader = true
	private var thread: Thread? = null
	@Volatile private var input: InputStream? = null

	@Volatile var isClosed = false
		private set

	/** True while a dropped socket is being retried: the player says so while it waits. */
	@Volatile var reconnecting = false
		private set

	/** One more consumer; pair it with [LiveChannels.leave]. */
	internal fun join(): LiveChannel = synchronized(this) { consumers++; this }

	fun reader(): InputStream = synchronized(this) {
		// The pump can deliver PAT/PMT before the first consumer gets its reader.
		tap.reader(retained = firstReader).also { firstReader = false }
	}

	/** One consumer leaves: true when it was the last one, which closes the socket. */
	internal fun leave(): Boolean = synchronized(this) {
		consumers--
		if (consumers > 0) return false
		close()
		true
	}

	internal fun closeNow() = close()

	private fun close() {
		isClosed = true
		tap.close()
		thread?.interrupt()
		runCatching { cancel() }
		runCatching { input?.close() }
	}

	internal fun start() {
		thread = Thread(::pump, "live-$key").apply { isDaemon = true; start() }
	}

	/**
	 * Reads the socket into the tap, reconnecting after a drop with the player's own policy
	 * (0.4, 2, 4, 8, 8, 8 s; a quiet two minutes starts the count again). A stream that never
	 * plays gives up after a couple of quick tries — the player then retries it itself and the
	 * recording fails loudly instead of hanging. After the budget the channel closes, which its
	 * readers see as the end of the stream. A reconnect starts a new socket at the live edge,
	 * so the tap goes with it.
	 */
	private fun pump() {
		val reconnect = Reconnect(System::currentTimeMillis)
		val buf = ByteArray(64 * 1024)
		var played = false
		var tries = 0
		while (!isClosed) {
			var input: InputStream? = null
			try {
				if (played) tap.clear()
				input = open()
				this.input = input
				if (isClosed) return
				reconnecting = false
				while (!isClosed) {
					val n = input.read(buf)
					if (n < 0) break
					tap.write(buf, 0, n)
					played = true
				}
			} catch (e: Exception) {
				android.util.Log.w("AiptvLive", "live $key: ${e.message}")
			} finally {
				runCatching { input?.close() }
				this.input = null
			}
			if (isClosed) return
			if (!played && ++tries > FIRST_TRIES) return giveUp()
			reconnecting = true
			val delay = reconnect.next() ?: return giveUp()
			try {
				Thread.sleep(delay)
			} catch (_: InterruptedException) {
				return
			}
		}
	}

	/** The stream is over: readers see the end, and the next channel may take the one stream. */
	private fun giveUp() {
		close()
		LiveChannels.closed(this)
	}

	companion object {
		// ponytail: a paused or stalled reader can fall this far behind live before its bytes go; shrink it if a low-RAM device ever complains.
		const val TAP_BYTES = 8 shl 20

		/** Quick tries before a stream that has never sent a byte is left to the player. */
		const val FIRST_TRIES = 2
	}
}

/** The live channels that exist right now: at most one, because the provider allows one stream. */
object LiveChannels {
	private val lock = Any()
	private var current: LiveChannel? = null

	/** A place in [key]'s stream: the running channel, or a new one. Null = another stream is in use. */
	fun attach(key: String, cancel: () -> Unit = {}, open: () -> InputStream): LiveChannel? = synchronized(lock) {
		current?.let { return if (it.key == key && !it.isClosed) it.join() else null }
		if (!Streams.acquire(Streams.liveId(key))) return null
		val ch = LiveChannel(key, cancel, open)
		current = ch
		ch.start()
		ch.join()
	}

	fun attachUrl(key: String, url: String, headers: Map<String, String>): LiveChannel? {
		val call = Net.Call(url, headers)
		return attach(key, call::close, call::open)
	}

	/** One consumer leaves [ch]; the last one closes the socket and frees the stream. */
	fun leave(ch: LiveChannel) = synchronized(lock) {
		if (!ch.leave()) return
		if (current === ch) {
			current = null
			Streams.release(Streams.liveId(ch.key))
		}
	}

	/** The running channel for [key], when watching and recording already share it. */
	fun current(key: String): LiveChannel? = synchronized(lock) { current?.takeIf { !it.isClosed && it.key == key } }

	/** A pump that gave up: the channel is over, so the one stream is free for the next one. */
	internal fun closed(ch: LiveChannel) = synchronized(lock) {
		if (current === ch) {
			current = null
			Streams.release(Streams.liveId(ch.key))
		}
	}

	/** Tests: close whatever is running. */
	fun reset() = synchronized(lock) {
		current?.closeNow()
		current = null
		Streams.reset()
	}
}
