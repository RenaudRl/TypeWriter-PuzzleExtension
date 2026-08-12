package btcrenaud.puzzle

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PuzzleServiceCooldownTest {

    private val puzzleId = "puzzle_test_memory"

    @Test
    fun `wrong reset arms a cooldown that is reported to the player`() {
        val service = PuzzleService()
        val player = UUID.randomUUID()
        val now = 1_000_000L

        val accepted = service.applyResult(
            playerId = player,
            puzzleId = puzzleId,
            result = PuzzleResult.WRONG_RESET,
            resetOnWrong = true,
            cooldownSeconds = 3,
            progressLimit = 3,
            nowMillis = now,
        )

        assertTrue(accepted)
        assertTrue(service.isOnCooldown(player, puzzleId, now))
        assertEquals(3_000L, service.remainingCooldownMillis(player, puzzleId, now))
        assertEquals(1_000L, service.remainingCooldownMillis(player, puzzleId, now + 2_000L))
    }

    @Test
    fun `remaining cooldown reaches zero once the window elapsed`() {
        val service = PuzzleService()
        val player = UUID.randomUUID()
        val now = 500_000L

        service.applyResult(
            playerId = player,
            puzzleId = puzzleId,
            result = PuzzleResult.WRONG_RESET,
            resetOnWrong = true,
            cooldownSeconds = 2,
            progressLimit = 3,
            nowMillis = now,
        )

        assertEquals(0L, service.remainingCooldownMillis(player, puzzleId, now + 2_000L))
        assertFalse(service.isOnCooldown(player, puzzleId, now + 2_000L))
    }

    @Test
    fun `starting a session clears the exhausted attempt budget and the cooldown`() {
        val service = PuzzleService()
        val player = UUID.randomUUID()
        val now = 10_000L

        // Burn the whole attempt budget, exactly like a previous session would.
        repeat(5) { attempt ->
            service.applyResult(player, puzzleId, PuzzleResult.WRONG, false, 3, 3, now + attempt)
        }
        service.applyResult(player, puzzleId, PuzzleResult.MAX_ATTEMPTS_REACHED, false, 3, 3, now + 10)
        assertEquals(5, service.getState(player, puzzleId).wrongAttempts)
        assertTrue(service.isOnCooldown(player, puzzleId, now + 11))

        assertTrue(service.beginSession(player, puzzleId, now + 12))

        assertEquals(0, service.getState(player, puzzleId).wrongAttempts)
        assertEquals(0, service.getState(player, puzzleId).progress)
        assertFalse(service.isOnCooldown(player, puzzleId, now + 12))
    }

    @Test
    fun `starting a session never reopens a solved puzzle`() {
        val service = PuzzleService()
        val player = UUID.randomUUID()

        service.applyResult(player, puzzleId, PuzzleResult.COMPLETED, false, 0, 3, 1_000L)

        assertFalse(service.beginSession(player, puzzleId, 2_000L))
        assertTrue(service.isSolved(player, puzzleId))
    }

    @Test
    fun `unknown puzzle has no cooldown`() {
        val service = PuzzleService()
        assertEquals(0L, service.remainingCooldownMillis(UUID.randomUUID(), "unknown"))
    }

    @Test
    fun `wrong reset clears progress so a replay starts from zero`() {
        val service = PuzzleService()
        val player = UUID.randomUUID()

        service.applyResult(player, puzzleId, PuzzleResult.STEP_CORRECT, false, 0, 3, 1_000L)
        service.applyResult(player, puzzleId, PuzzleResult.STEP_CORRECT, false, 0, 3, 1_100L)
        assertEquals(2, service.getState(player, puzzleId).progress)

        service.applyResult(player, puzzleId, PuzzleResult.WRONG_RESET, true, 3, 3, 1_200L)
        assertEquals(0, service.getState(player, puzzleId).progress)
        assertFalse(service.getState(player, puzzleId).solved)
    }

    @Test
    fun `completion is applied once so the completion message cannot be duplicated`() {
        val service = PuzzleService()
        val player = UUID.randomUUID()

        val first = service.applyResult(player, puzzleId, PuzzleResult.COMPLETED, false, 0, 3, 2_000L)
        val second = service.applyResult(player, puzzleId, PuzzleResult.COMPLETED, false, 0, 3, 2_100L)

        assertTrue(first)
        assertFalse(second, "a duplicated interaction must not re-emit completion side effects")
        assertTrue(service.isSolved(player, puzzleId))
    }
}
