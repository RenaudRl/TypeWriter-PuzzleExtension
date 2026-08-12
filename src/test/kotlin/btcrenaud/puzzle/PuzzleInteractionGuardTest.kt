package btcrenaud.puzzle

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.UUID

class PuzzleInteractionGuardTest {

    private enum class InteractionType {
        BLOCK_BREAK,
        BLOCK_PLACE,
        PLAYER_INTERACT,
    }

    private val palier1 = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val puzzleTarget = PuzzleBlockCoordinate(palier1, 100, 65, 100)
    private val outsidePuzzle = PuzzleBlockCoordinate(palier1, 101, 65, 100)

    @Test
    fun `block break outside puzzle is never cancelled`() {
        assertOutsideIsNotCancelled(InteractionType.BLOCK_BREAK)
    }

    @Test
    fun `block place outside puzzle is never cancelled`() {
        assertOutsideIsNotCancelled(InteractionType.BLOCK_PLACE)
    }

    @Test
    fun `player interact outside puzzle is never cancelled`() {
        assertOutsideIsNotCancelled(InteractionType.PLAYER_INTERACT)
    }

    @Test
    fun `inside puzzle interaction remains owned in creative and survival`() {
        for (gameMode in GameMode.entries) {
            assertTrue(
                shouldCancelPuzzleEvent(puzzleTarget, listOf(puzzleTarget)),
                "${gameMode.name} interaction inside palier1 must remain handled",
            )
        }
    }

    @Test
    fun `empty criteria do not turn every coordinate into a puzzle coordinate`() {
        assertFalse(shouldCancelPuzzleEvent(outsidePuzzle, listOf(puzzleTarget)))
    }

    @Test
    fun `same xyz in another world is outside the puzzle`() {
        val anotherWorld = PuzzleBlockCoordinate(UUID.fromString("00000000-0000-0000-0000-000000000002"), 100, 65, 100)
        assertFalse(shouldCancelPuzzleEvent(anotherWorld, listOf(puzzleTarget)))
    }

    @Test
    fun `coop accepts player feet on the configured plate or one block above`() {
        val plate = PuzzleBlockCoordinate(palier1, 150, 80, 0)
        assertTrue(isStandingOnPuzzlePlate(PuzzleBlockCoordinate(palier1, 150, 80, 0), plate))
        assertTrue(isStandingOnPuzzlePlate(PuzzleBlockCoordinate(palier1, 150, 81, 0), plate))
        assertFalse(isStandingOnPuzzlePlate(PuzzleBlockCoordinate(palier1, 151, 81, 0), plate))
    }

    private fun assertOutsideIsNotCancelled(interactionType: InteractionType) {
        for (gameMode in GameMode.entries) {
            assertFalse(
                shouldCancelPuzzleEvent(outsidePuzzle, listOf(puzzleTarget)),
                "${interactionType.name} in ${gameMode.name} outside palier1 must not be cancelled",
            )
        }
    }
}
    private enum class GameMode {
        CREATIVE,
        SURVIVAL,
    }
