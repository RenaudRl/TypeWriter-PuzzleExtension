package btcrenaud.puzzle.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PuzzleCoreTest {
    private val definition = PuzzleDefinition(
        amount = 2,
        cooldownSeconds = 10,
        maxAttempts = 2,
        resetOnWrong = true,
    )

    @Test
    fun `completed transition is idempotent`() {
        val started = PuzzleSessionReducer.start(PuzzleSessionState(), definition, 1_000L)
        val completed = PuzzleSessionReducer.apply(started.state, PuzzleTransitionOutcome.COMPLETED, definition, 2_000L)
        val repeated = PuzzleSessionReducer.apply(completed.state, PuzzleTransitionOutcome.COMPLETED, definition, 3_000L)

        assertTrue(completed.accepted)
        assertEquals(definition.amount, completed.state.progress)
        assertEquals(PuzzleTransitionOutcome.ALREADY_SOLVED, repeated.outcome)
        assertFalse(repeated.accepted)
        assertEquals(2_000L, repeated.state.completedAtMillis)
    }

    @Test
    fun `wrong attempts reset progress and apply cooldown at the limit`() {
        val started = PuzzleSessionReducer.start(PuzzleSessionState(), definition, 1_000L)
        val progress = PuzzleSessionReducer.apply(started.state, PuzzleTransitionOutcome.STEP_CORRECT, definition, 1_100L)
        val firstWrong = PuzzleSessionReducer.apply(progress.state, PuzzleTransitionOutcome.WRONG, definition, 1_200L)
        val secondWrong = PuzzleSessionReducer.apply(firstWrong.state, PuzzleTransitionOutcome.WRONG, definition, 1_300L)

        assertEquals(0, firstWrong.state.progress)
        assertEquals(PuzzleTransitionOutcome.MAX_ATTEMPTS_REACHED, secondWrong.outcome)
        assertEquals(PuzzleSessionStatus.FAILED, secondWrong.state.status)
        assertTrue(secondWrong.state.isOnCooldown(1_301L))
    }

    @Test
    fun `invalid definition is rejected`() {
        assertTrue(runCatching { PuzzleDefinition(amount = 0) }.exceptionOrNull() is IllegalArgumentException)
    }
}
