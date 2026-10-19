package dev.serrebi.accessibleiptv

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reopen half of the desktop's buffering rules: a stream reopened after a stall
 * waits with the whole cushion (its first start is not a quick start), loading runs to
 * the current cushion target rather than always to the maximum, and a fresh start is
 * quick with the saved buffer again.
 */
class CushionParityTest {
	private fun params(bufferedSec: Double, rebuffering: Boolean = true) = LoadControl.Parameters(
		PlayerId.UNSET, Timeline.EMPTY, MediaSource.MediaPeriodId(Any()), 0,
		(bufferedSec * 1_000_000).toLong(), 1f, true, rebuffering, C.TIME_UNSET, C.TIME_UNSET,
	)

	@Test fun aReopenAfterAStallWaitsForTheWholeCushionFromTheFirstStart() {
		val c = Cushion(25.0, 30.0)
		c.onStall() // cushion now 26 s, and this session is a reopened one
		assertFalse("a reopen is not a quick start", c.shouldStartPlayback(params(0.5, rebuffering = false)))
		assertFalse(c.shouldStartPlayback(params(25.0, rebuffering = false)))
		assertTrue(c.shouldStartPlayback(params(26.0, rebuffering = false)))
	}

	@Test fun aReopenWithoutGrowthStillWaitsForTheSavedCushion() {
		val c = Cushion(25.0, 30.0)
		c.onReopen()
		assertEquals(25_000_000L, c.targetUs)
		assertFalse(c.shouldStartPlayback(params(0.5, rebuffering = false)))
		assertTrue(c.shouldStartPlayback(params(25.0, rebuffering = false)))
	}

	@Test fun aDispatcharrReopenWaitsForTheSavedBuffer() {
		val c = Cushion(6.0, 30.0, dispatcharr = true)
		c.onStall()
		assertTrue(c.reopened)
		assertFalse(c.shouldStartPlayback(params(0.5, rebuffering = false)))
		assertTrue(c.shouldStartPlayback(params(6.0, rebuffering = false)))
	}

	@Test fun aFreshStartIsQuickWithTheSavedBufferAgain() {
		val c = Cushion(25.0, 30.0)
		c.onStall()
		c.onFreshStart()
		assertEquals(25_000_000L, c.targetUs)
		assertFalse(c.reopened)
		assertTrue("the first start waits only half a second", c.shouldStartPlayback(params(0.5, rebuffering = false)))
	}

	@Test fun loadingStopsAtTheCurrentTargetNotTheMaximum() {
		val c = Cushion(25.0, 30.0)
		assertTrue(c.shouldContinueLoading(params(26.9)))
		assertFalse("27 s is the 25 s target plus headroom", c.shouldContinueLoading(params(27.1)))
		c.onStall()
		assertTrue("a grown cushion loads to its new target", c.shouldContinueLoading(params(27.1)))
		assertFalse(c.shouldContinueLoading(params(28.1)))
	}
}
