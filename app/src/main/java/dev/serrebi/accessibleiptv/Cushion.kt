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
 * The desktop client's VLC cache, ported: playback starts, and resumes after a stall,
 * only once [startSeconds] are buffered, so that much cushion is always in hand. Each
 * stall adds one second, up to [maxSeconds] (desktop: base_buffer_seconds + 1 per stall,
 * capped at max_buffer_seconds). A new player starts again from [startSeconds].
 *
 * Every PlayerId overload is forwarded by hand: Kotlin's `by` delegation skips Java default
 * methods, and LoadControl's defaults throw "not implemented" (crashed the player on open).
 */
class Cushion(startSeconds: Double, maxSeconds: Double) : LoadControl {
	// Keep loading until the maximum is buffered, like VLC filling its cache.
	private val inner = DefaultLoadControl.Builder()
		.setBufferDurationsMs(msOf(maxSeconds), msOf(maxSeconds) + 2_000, msOf(startSeconds), msOf(startSeconds))
		.build()

	private val maxUs = (maxSeconds.coerceAtLeast(startSeconds) * 1_000_000).toLong()

	@Volatile var targetUs = (startSeconds * 1_000_000).toLong()
		private set

	/** A stall happened: raise the cushion by one second, as the desktop does. */
	fun onStall() {
		targetUs = minOf(maxUs, targetUs + 1_000_000)
	}

	override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
		parameters.bufferedDurationUs >= targetUs

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

	private companion object {
		fun msOf(seconds: Double) = (seconds * 1000).toInt().coerceAtLeast(100)
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
