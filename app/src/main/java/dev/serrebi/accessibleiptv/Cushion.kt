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
	// The inner control owns the allocator and the back buffer; how much to load and
	// when to start are decided here, against the current cushion target.
	private val inner = DefaultLoadControl.Builder()
		.setBufferDurationsMs(msOf(maxSeconds), msOf(maxSeconds) + 2_000, msOf(startSeconds), msOf(startSeconds))
		// A timestamp reset can fill the byte target with media that cannot yet play.
		.setPrioritizeTimeOverSizeThresholds(true)
		.build()

	private val startUs = (startSeconds * 1_000_000).toLong()
	private val maxUs = (maxSeconds.coerceAtLeast(startSeconds) * 1_000_000).toLong()

	@Volatile var targetUs = startUs
		private set

	private val quickUs = (minOf(quickSeconds, startSeconds) * 1_000_000).toLong()

	/**
	 * This session was reopened after a stall, so its starts wait for the whole cushion:
	 * the desktop creates the reopened media with the full caching value, where a fresh
	 * prepare used to slip back in at the quick-start gate and flap.
	 */
	@Volatile var reopened = false
		private set

	/** A stall is being recovered from: raise the cushion by one second, as the desktop does. */
	fun onStall() {
		reopened = true
		if (dispatcharr) return  // Dispatcharr's own buffer is the cushion
		targetUs = minOf(maxUs, targetUs + 1_000_000)
	}

	/** A reopen that earns no growth (a dropped stream rejoining the live edge). */
	fun onReopen() { reopened = true }

	/** A fresh start (a new channel, or the user pressing play again): quick start, saved buffer. */
	fun onFreshStart() {
		targetUs = startUs
		reopened = false
	}

	// First start: quick. A rebuffer, and any start after a recovery reopen: the whole cushion.
	override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
		parameters.bufferedDurationUs >= if (parameters.rebuffering || reopened) targetUs else quickUs

	override fun onPrepared(playerId: PlayerId) = inner.onPrepared(playerId)
	override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
		inner.onTracksSelected(parameters, trackGroups, trackSelections)
	override fun onStopped(playerId: PlayerId) = inner.onStopped(playerId)
	override fun onReleased(playerId: PlayerId) = inner.onReleased(playerId)
	override fun getAllocator(playerId: PlayerId): Allocator = inner.getAllocator(playerId)
	override fun getBackBufferDurationUs(playerId: PlayerId): Long = inner.getBackBufferDurationUs(playerId)
	override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = inner.retainBackBufferFromKeyframe(playerId)

	// Loading runs to the current cushion target (plus the inner control's headroom),
	// not always to the maximum: the desktop's steady cushion is its current target.
	override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
		parameters.bufferedDurationUs < targetUs + 2_000_000

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

/**
 * The desktop player's playback-health bookkeeping (internal_player.py), fed once a
 * second by the player screen: buffering with no growth for 10 s after playback is a
 * stall, four buffering events of 1.25 s or more inside a minute are choppy playback,
 * media progress frozen for 10 s (after a 12 s startup grace) is a stall, a reopened
 * stream with no media after 25 s restarts, and a channel that has not played at all
 * 30 s after it was chosen is given up on. Pure time arithmetic against an injected
 * clock, so the rules are testable without a player.
 */
class StallTracker(private val clock: () -> Long) {
	enum class Verdict { NONE, GROW, RESTART, RESTART_GROWN, GIVE_UP }

	private var openedAt = clock()
	private var preparedAt = openedAt
	private var played = false
	private var bufferingSince: Long? = null
	private var lastBufferedMs = 0L
	private var bufferProgressAt = openedAt
	private val events = ArrayDeque<Long>()
	private var lastAdjustAt = Long.MIN_VALUE / 2
	private var gaveUp = false

	/** A fresh request (a new channel, or the user pressing play again): every clock restarts. */
	fun reset() {
		openedAt = clock()
		preparedAt = openedAt
		played = false
		bufferingSince = null
		lastBufferedMs = 0
		bufferProgressAt = openedAt
		events.clear()
		lastAdjustAt = Long.MIN_VALUE / 2
		gaveUp = false
	}

	/** A (re)open of the same stream: the no-media clock restarts, the channel's does not. */
	fun onPrepared() {
		preparedAt = clock()
		bufferingSince = null
		lastBufferedMs = 0
		bufferProgressAt = preparedAt
	}

	/** Playback reached the screen: the first-play deadline is met for good. */
	fun onPlaying() {
		played = true
		closeEpisode()
	}

	/**
	 * One health tick. [frozenMs] is how long media progress has stood still while the
	 * player claims to be playing (0 while buffering or paused).
	 */
	fun tick(buffering: Boolean, frozenMs: Long, bufferedMs: Long = 0): Verdict {
		val t = clock()
		if (buffering) {
			if (bufferingSince == null) bufferingSince = t
			// Filling a saved cushion is progress, not a reason to discard it and reconnect.
			if (bufferedMs > lastBufferedMs) {
				bufferProgressAt = t
				preparedAt = t
			}
			lastBufferedMs = bufferedMs
		} else {
			closeEpisode()
			lastBufferedMs = 0
		}
		if (gaveUp) return Verdict.NONE
		if (!played) {
			if (t - openedAt >= FIRST_PLAY_DEADLINE_MS) { gaveUp = true; return Verdict.GIVE_UP }
			if (t - preparedAt >= NO_MEDIA_MS) return Verdict.RESTART
			return Verdict.NONE
		}
		val since = bufferingSince
		if (buffering && since != null && t - maxOf(since, bufferProgressAt) >= PROLONGED_MS) {
			bufferingSince = t
			lastAdjustAt = t
			return Verdict.RESTART_GROWN
		}
		if (frozenMs >= FROZEN_MS && t - openedAt >= STARTUP_GRACE_MS && t - lastAdjustAt >= COOLDOWN_MS) {
			lastAdjustAt = t
			return Verdict.RESTART_GROWN
		}
		while (events.isNotEmpty() && t - events.first() >= CHOPPY_WINDOW_MS) events.removeFirst()
		if (events.size >= CHOPPY_COUNT && t - lastAdjustAt >= COOLDOWN_MS) {
			lastAdjustAt = t
			events.clear()
			return Verdict.GROW
		}
		return Verdict.NONE
	}

	private fun closeEpisode() {
		val since = bufferingSince ?: return
		bufferingSince = null
		if (clock() - since >= EVENT_MIN_MS) events.addLast(clock())
	}

	companion object {
		const val PROLONGED_MS = 10_000L
		const val EVENT_MIN_MS = 1_250L
		const val CHOPPY_WINDOW_MS = 60_000L
		const val CHOPPY_COUNT = 4
		const val COOLDOWN_MS = 20_000L
		const val NO_MEDIA_MS = 25_000L
		const val FIRST_PLAY_DEADLINE_MS = 30_000L
		const val FROZEN_MS = 10_000L
		const val STARTUP_GRACE_MS = 12_000L
	}
}
