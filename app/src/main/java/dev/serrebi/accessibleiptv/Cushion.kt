package dev.serrebi.accessibleiptv

import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * The desktop client's VLC cache, ported, with a quick start. Playback starts once
 * [quickSeconds] are buffered, then [speedFor] plays a little slow until the full
 * [startSeconds] cushion has built up: a live stream arrives at real time, so slower
 * playback is the only way the cushion can grow. After a stall it resumes only with the
 * full cushion in hand, and each stall adds one second, up to [maxSeconds] (desktop:
 * base_buffer_seconds + 1 per stall, capped at max_buffer_seconds).
 *
 * Every PlayerId overload is forwarded by hand: Kotlin's `by` delegation skips Java default
 * methods, and LoadControl's defaults throw "not implemented" (crashed the player on open).
 */
class Cushion(startSeconds: Double, maxSeconds: Double, quickSeconds: Double = QUICK_START_SECONDS) : LoadControl {
	// Keep loading until the maximum is buffered, like VLC filling its cache.
	private val inner = DefaultLoadControl.Builder()
		.setBufferDurationsMs(msOf(maxSeconds), msOf(maxSeconds) + 2_000, msOf(startSeconds), msOf(startSeconds))
		.build()

	private val maxUs = (maxSeconds.coerceAtLeast(startSeconds) * 1_000_000).toLong()

	@Volatile var targetUs = (startSeconds * 1_000_000).toLong()
		private set

	private val quickUs = (minOf(quickSeconds, startSeconds) * 1_000_000).toLong()

	/** A stall happened: raise the cushion by one second, as the desktop does. */
	fun onStall() {
		targetUs = minOf(maxUs, targetUs + 1_000_000)
	}

	// First start (and a reconnect, which prepares afresh): quick. Back from a stall: the whole cushion.
	override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
		parameters.bufferedDurationUs >= if (parameters.rebuffering) targetUs else quickUs

	/**
	 * Playback speed for a live stream holding [bufferedUs]: [SLOW] while the cushion is short
	 * of the target, normal again once it is full, and slow again only when it drops a second
	 * below (so the speed does not flip back and forth around the target).
	 */
	fun speedFor(bufferedUs: Long, current: Float): Float = when {
		bufferedUs >= targetUs -> 1f
		current < 1f || bufferedUs < targetUs - 1_000_000 -> SLOW
		else -> 1f
	}

	override fun onPrepared(playerId: PlayerId) = inner.onPrepared(playerId)
	override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
		inner.onTracksSelected(parameters, trackGroups, trackSelections)
	override fun onStopped(playerId: PlayerId) = inner.onStopped(playerId)
	override fun onReleased(playerId: PlayerId) = inner.onReleased(playerId)
	override fun getAllocator(playerId: PlayerId): Allocator = inner.getAllocator(playerId)
	override fun getBackBufferDurationUs(playerId: PlayerId): Long = inner.getBackBufferDurationUs(playerId)
	override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = inner.retainBackBufferFromKeyframe(playerId)
	override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = inner.shouldContinueLoading(parameters)
	override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean =
		inner.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

	companion object {
		/** Seconds buffered before the picture first starts. Dispatcharr sends about 3 s at once. */
		const val QUICK_START_SECONDS = 2.0

		// ponytail: fixed 5% slower (pitch kept); 4 s of cushion takes 80 s to build. Tune if it is heard.
		const val SLOW = 0.95f

		private fun msOf(seconds: Double) = (seconds * 1000).toInt().coerceAtLeast(100)
	}
}

/**
 * The desktop player's stream recovery (_schedule_restart): a dropped live stream is
 * reopened after 0.4, 2, 4, 8, 8, 8 s, then given up. A count that sat unused for two
 * minutes starts again, so one bad evening does not use up the whole budget.
 */
class Reconnect(private val clock: () -> Long) {
	private var attempts = 0
	private var last = 0L

	/** Delay before the next try in ms, or null when it is time to give up. */
	fun next(): Long? {
		val now = clock()
		if (last != 0L && now - last >= RESET_MS) attempts = 0
		if (attempts >= MAX) return null
		attempts++
		last = now
		return delayMs(attempts)
	}

	fun reset() { attempts = 0; last = 0L }

	companion object {
		const val MAX = 6
		const val RESET_MS = 120_000L
		fun delayMs(attempt: Int): Long = if (attempt <= 1) 400L else minOf(1000L shl (attempt - 1), 8000L)
	}
}
