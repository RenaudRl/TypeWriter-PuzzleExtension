package btcrenaud.puzzle.entries.fact

import btcrenaud.puzzle.PuzzleService
import btcrenaud.puzzle.objective.audienceRef
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.engine.paper.entry.AudienceManager
import com.typewritermc.engine.paper.entry.entries.GroupEntry
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.facts.FactData
import org.bukkit.entity.Player
import org.koin.java.KoinJavaComponent.get

/**
 * Readable per-player fact set to 1 after a puzzle has been completed.
 *
 * The fact is derived from PuzzleService so Typewriter criteria, modifiers,
 * triggers and placeholders all observe the same completion state.
 */
@Entry("puzzle_solved_fact", "Puzzle Solved Fact", "#F5B642", "mdi:puzzle-check")
@Tags("puzzle", "fact")
class PuzzleSolvedFactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "1 when the configured player has solved the puzzle, otherwise 0.",
    override val group: Ref<GroupEntry> = emptyRef(),
    @Help("Puzzle objective ID whose per-player completion state is read.")
    val puzzleId: String = "",
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        val solved = puzzleId.isNotBlank() &&
            get<PuzzleService>(PuzzleService::class.java).isSolved(player.uniqueId, puzzleId)
        return FactData(if (solved) 1 else 0)
    }
}

/**
 * Readable per-player fact set to 1 while the player is in a puzzle audience.
 * It is transient and therefore intentionally not cached or persisted.
 */
@Entry("puzzle_active_fact", "Puzzle Active Fact", "#00BCD4", "mdi:puzzle-star")
@Tags("puzzle", "fact")
class PuzzleActiveFactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "1 while the player is subscribed to the puzzle audience, otherwise 0.",
    override val group: Ref<GroupEntry> = emptyRef(),
    @Help("Puzzle objective ID whose audience membership is read.")
    val puzzleId: String = "",
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        val puzzle = get<PuzzleService>(PuzzleService::class.java).getPuzzleEntry(puzzleId)
        val active = puzzle != null &&
            get<AudienceManager>(AudienceManager::class.java)[puzzle.audienceRef()]?.contains(player) == true
        return FactData(if (active) 1 else 0)
    }
}
