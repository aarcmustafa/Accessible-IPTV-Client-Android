package dev.serrebi.accessibleiptv

import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * How much programme an MPEG-TS stream holds, read from PES timestamps as the bytes go by.
 * The desktop reads ffmpeg's "time=" line for the same check; there is no ffmpeg here.
 * Only the first stream that carries a PTS is followed. Not TS (an MP4) = [durationMs] null.
 */
class TsClock {
	private val packet = ByteArray(188)
	private var fill = 0
	private var pid = -1
	private var first = -1L
	private var last = -1L

	/** Milliseconds between the first and last timestamp seen, or null when none were. */
	val durationMs: Long? get() = if (first < 0) null else (last - first) / 90

	fun feed(b: ByteArray, off: Int, len: Int) {
		var i = off
		val end = off + len
		while (i < end) {
			if (fill == 0 && b[i] != SYNC) { i++; continue }
			val n = minOf(188 - fill, end - i)
			System.arraycopy(b, i, packet, fill, n)
			fill += n
			i += n
			if (fill == 188) { packet(); fill = 0 }
		}
	}

	fun watching(out: OutputStream): OutputStream = object : FilterOutputStream(out) {
		override fun write(b: ByteArray, off: Int, len: Int) {
			out.write(b, off, len)
			feed(b, off, len)
		}
	}

	private fun packet() {
		if (packet[1].toInt() and 0x40 == 0) return // not the start of a PES packet
		val p = ((packet[1].toInt() and 0x1F) shl 8) or (packet[2].toInt() and 0xFF)
		val afc = (packet[3].toInt() shr 4) and 3
		var o = 4 + if (afc and 2 != 0) 1 + (packet[4].toInt() and 0xFF) else 0
		if (o + 14 > 188) return
		if (packet[o] != 0.toByte() || packet[o + 1] != 0.toByte() || packet[o + 2] != 1.toByte()) return
		if (packet[o + 7].toInt() and 0x80 == 0) return // no PTS
		if (pid >= 0 && p != pid) return
		o += 9
		// ponytail: no 33-bit wrap handling; a wrap (every 26.5 h) mid-download reads as short and costs one retry.
		val pts = ((packet[o].toLong() shr 1) and 7 shl 30) or
			((packet[o + 1].toLong() and 0xFF) shl 22) or
			((packet[o + 2].toLong() and 0xFF) shr 1 shl 15) or
			((packet[o + 3].toLong() and 0xFF) shl 7) or
			((packet[o + 4].toLong() and 0xFF) shr 1)
		pid = p
		if (first < 0) first = pts
		if (pts > last) last = pts
	}

	private companion object {
		const val SYNC = 0x47.toByte()
	}
}
