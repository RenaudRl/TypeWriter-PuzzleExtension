package btcrenaud.puzzle

import org.bukkit.Location
import java.util.UUID

/**
 * Block coordinates used to decide whether a Bukkit interaction belongs to a
 * puzzle.  Audience membership alone is not enough: an empty criteria list
 * intentionally matches every player, so the block position must remain the
 * final ownership boundary.
 */
internal data class PuzzleBlockCoordinate(
    val worldId: UUID?,
    val x: Int,
    val y: Int,
    val z: Int,
)

internal fun Location.toPuzzleBlockCoordinate(): PuzzleBlockCoordinate =
    PuzzleBlockCoordinate(world?.uid, blockX, blockY, blockZ)

internal fun isPuzzleBlockPosition(first: Location, second: Location): Boolean =
    shouldCancelPuzzleEvent(first.toPuzzleBlockCoordinate(), listOf(second.toPuzzleBlockCoordinate()))

/**
 * Central cancellation policy shared by native and fake-block paths.
 * Cancellation is allowed only after a concrete puzzle coordinate matched.
 */
internal fun shouldCancelPuzzleEvent(
    clicked: PuzzleBlockCoordinate?,
    puzzleCoordinates: Collection<PuzzleBlockCoordinate>,
): Boolean = clicked != null && clicked in puzzleCoordinates

/**
 * Returns true when a player's feet occupy a configured pressure position.
 * Both the configured block coordinate and the block immediately above it
 * are accepted so pages can describe either a feet position or a real plate.
 */
internal fun isStandingOnPuzzlePlate(
    player: PuzzleBlockCoordinate,
    plate: PuzzleBlockCoordinate,
): Boolean = player.worldId == plate.worldId &&
    player.x == plate.x &&
    player.z == plate.z &&
    (player.y == plate.y || player.y == plate.y + 1)
