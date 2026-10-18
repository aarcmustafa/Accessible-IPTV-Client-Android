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
 * [quickSeconds] are buffered, then plays the rest of the buffer as it arrives. Filling
 * must never slow playback down (2026-10-17, the desktop's rule): a speed under 1.0 was
 * the only way to grow a cushion locally, it made speech drag, and the buffer worth
 * having is the one the server hands over ahead of real time. After a stall it resumes
 * only with the full cushion in hand, and each stall adds one second, up to [maxSeconds]
 * (desktop: base_buffer_seconds + 1 per stall, capped at max_buffer_seconds).
 *
 * A Dispatcharr stream ([Channel.isDispatcharr]) keeps the saved buffer instead: it restreams
 * from its own server-side buffer, so a bigger client cache only adds delay.
 *
 * Every PlayerId overload is forwarded by hand: Kotlin's `by` delegation skips Java default
 * methods, and LoadControl's defaults throw "not implemented" (crashed the player on open).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Cushion(startSeconds: Double, maxSeconds: Double, quickSeconds: Double = QUICK_START_SECONDS, private val dispatcharr: Boolean = false) : LoadControl {
	// Keep loading until the maximum is buffered, like VLC filling its cache.
	private val inner = DefaultLoadControl.Builder()
		.setBufferDurationsMs(msOf(maxSeconds), msOf(maxSeconds) + 2_000, msOf(startSeconds), msOf(startSeconds))
		// A timestamp reset can fill the byte target with media that cannot yet play.
		.setPrioritizeTimeOverSizeThresholds(true)
		.build()

	private val maxUs = (maxSeconds.coerceAtLeast(startSeconds) * 1_000_000).toLong()

	@Volatile var targetUs = (startSeconds * 1_000_000).toLong()
		private set

	private val quickUs = (minOf(quickSeconds, startSeconds) * 1_000_000).toLong()

	/** A stall happened: raise the cushion by one second, as the desktop does. */
	fun onStall() {
		if (dispatcharr) return  // Dispatcharr's own buffer is the cushion
		targetUs = minOf(maxUs, targetUs + 1_000_000)
	}

	// First start (and a reconnect, which prepares afresh): quick. Back from a stall: the whole cushion.
	override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
		parameters.bufferedDurationUs >= if (parameters.rebuffering) targetUs else quickUs

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
		/** First-start buffer; the rest of the cushion is played as it arrives. */
		const val QUICK_START_SECONDS = 0.5

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
