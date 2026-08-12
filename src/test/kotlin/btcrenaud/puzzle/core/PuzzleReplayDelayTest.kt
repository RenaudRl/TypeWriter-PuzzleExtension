package btcrenaud.puzzle.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the memory-sequence replay window.
 *
 * A wrong answer arms the failure cooldown before the replay is scheduled. The
 * previous implementation replayed immediately, the cooldown swallowed it, and
 * the board stayed frozen with no usable control.
 */
class PuzzleReplayDelayTest {

    @Test
    fun `replay lands strictly after the remaining cooldown`() {
        val cooldownMillis = 3_000L
        val delayTicks = puzzleReplayDelayTicks(cooldownMillis, graceTicks = 10L)

        assertEquals(70L, delayTicks)
        assertTrue(delayTicks * 50L > cooldownMillis, "replay must not land inside the cooldown")
    }

    @Test
    fun `replay is still scheduled when no cooldown is configured`() {
        assertEquals(10L, puzzleReplayDelayTicks(0L, graceTicks = 10L))
    }

    @Test
    fun `negative remaining cooldown never produces a negative delay`() {
        assertEquals(1L, puzzleReplayDelayTicks(-5_000L, graceTicks = 0L))
    }
}
