package dev.serrebi.accessibleiptv

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The desktop's playback-health rules (StallTracker): prolonged buffering, choppy
 * playback, frozen progress, the no-media restart and the first-play deadline.
 */
class StallTrackerTest {
	private var now = 0L
	private fun tracker() = StallTracker { now }
	private fun at(seconds: Long) { now = seconds * 1000 }

	@Test fun noMediaRestartsAt25SecondsAndNeverPlayedGivesUpAt30() {
		val t = tracker()
		at(24)
		assertEquals(StallTracker.Verdict.NONE, t.tick(buffering = true, frozenMs = 0))
		at(25)
		assertEquals(StallTracker.Verdict.RESTART, t.tick(buffering = true, frozenMs = 0))
		t.onPrepared()
		at(29)
		assertEquals(StallTracker.Verdict.NONE, t.tick(buffering = true, frozenMs = 0))
		at(30)
		assertEquals(StallTracker.Verdict.GIVE_UP, t.tick(buffering = true, frozenMs = 0))
		at(90)
		assertEquals(StallTracker.Verdict.NONE, t.tick(buffering = true, frozenMs = 0))
		t.reset()
		at(91)
		assertEquals(StallTracker.Verdict.NONE, t.tick(buffering = true, frozenMs = 0))
	}

	@Test fun playingBeforeTheDeadlineDisarmsIt() {
		val t = tracker()
		at(20)
		t.onPlaying()
		at(45)
		assertEquals(StallTracker.Verdict.NONE, t.tick(buffering = false, frozenMs = 0))
	}

	@Test fun prolongedBufferingRestartsWithABiggerCushion() {
		val t = tracker()
		at(1)
		t.onPlaying()
		at(5)
		assertEquals(StallTracker.Verdict.NONE, t.tick(buffering = true, frozenMs = 0))
		at(14)
		assertEquals(StallTracker.Verdict.NONE, t.tick(buffering = true, frozenMs = 0))
		at(15)
		assertEquals(StallTracker.Verdict.RESTART_GROWN, t.tick(buffering = true, frozenMs = 0))
	}

	@Test fun fillingTheRecoveryCushionDoesNotRestartEveryTenSeconds() {
		val t = tracker()
		t.onPlaying()
		t.onPrepared()
		for (second in 1L..30L) {
			at(second)
			assertEquals(StallTracker.Verdict.NONE, t.tick(buffering = true, frozenMs = 0, bufferedMs = second * 1000))
		}
		at(39)
		assertEquals(StallTracker.Verdict.NONE, t.tick(buffering = true, frozenMs = 0, bufferedMs = 30_000))
		at(40)
		assertEquals(StallTracker.Verdict.RESTART_GROWN, t.tick(buffering = true, frozenMs = 0, bufferedMs = 30_000))
	}

	@Test fun initialBufferProgressPreventsTheNoMediaRestart() {
		val t = tracker()
		for (second in 1L..29L) {
			at(second)
			assertEquals(StallTracker.Verdict.NONE, t.tick(buffering = true, frozenMs = 0, bufferedMs = second * 1000))
		}
		at(30)
		assertEquals("the first-play deadline still applies", StallTracker.Verdict.GIVE_UP,
			t.tick(buffering = true, frozenMs = 0, bufferedMs = 30_000))
	}

	@Test fun shortBlipsDoNotCountButFourLongOnesAreChoppy() {
		val t = tracker()
		at(1)
		t.onPlaying()
		// Four 1 s blips: under the 1.25 s event floor, nothing happens.
		for (start in 2L..14L step 4) {
			at(start)
			t.tick(buffering = true, frozenMs = 0)
			at(start + 1)
			assertEquals(StallTracker.Verdict.NONE, t.tick(buffering = false, frozenMs = 0))
		}
		// Four 2 s episodes inside a minute: the fourth asks for a bigger cushion.
		var verdict = StallTracker.Verdict.NONE
		for (start in 20L..32L step 4) {
			at(start)
			t.tick(buffering = true, frozenMs = 0)
			at(start + 2)
			verdict = t.tick(buffering = false, frozenMs = 0)
		}
		assertEquals("choppy playback that resumed needs a cushion, not a disconnect", StallTracker.Verdict.GROW, verdict)
		// The 20 s cooldown holds the next choppy verdict back.
		for (start in 36L..48L step 4) {
			at(start)
			t.tick(buffering = true, frozenMs = 0)
			at(start + 2)
			verdict = t.tick(buffering = false, frozenMs = 0)
		}
		assertEquals(StallTracker.Verdict.NONE, verdict)
	}

	@Test fun frozenProgressRestartsWithABiggerCushion() {
		val t = tracker()
		at(1)
		t.onPlaying()
		// Frozen solid, but still inside the 12 s startup grace: no verdict.
		at(11)
		assertEquals(StallTracker.Verdict.NONE, t.tick(buffering = false, frozenMs = 10_000))
		at(15)
		assertEquals(StallTracker.Verdict.NONE, t.tick(buffering = false, frozenMs = 9_000))
		at(16)
		assertEquals(StallTracker.Verdict.RESTART_GROWN, t.tick(buffering = false, frozenMs = 10_000))
	}
}
