package btcrenaud.puzzle.chain

import btcrenaud.puzzle.PuzzleService
import btcrenaud.puzzle.objective.BasePuzzleObjectiveEntry
import btcrenaud.puzzle.objective.audienceRef
import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.AudienceManager
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.entry.triggerFor
import com.typewritermc.core.interaction.context
import org.bukkit.entity.Player
import org.koin.java.KoinJavaComponent.get
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Entry(
    "puzzle_chain",
    "Chain of puzzles -",
    "#F39C12",
    "mdi:link"
)
@Tags("puzzle", "chain")
class PuzzleChainEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    @Help("List of puzzle entry IDs to chain in order.")
    val puzzleEntryIds: List<String> = emptyList(),
    @Help("Trigger fired when the entire chain is completed.")
    val onChainComplete: Ref<TriggerableEntry> = emptyRef(),
    @Help("Trigger fired when a puzzle in the chain fails.")
    val onChainFail: Ref<TriggerableEntry> = emptyRef(),
    @Help("If true, failing any puzzle resets the entire chain.")
    val resetOnFail: Var<Boolean> = ConstVar(true),
) : TriggerableEntry {
    override val triggers: List<Ref<TriggerableEntry>> get() = emptyList()

    fun resolvePuzzles(): List<BasePuzzleObjectiveEntry> {
        val puzzleService = get<PuzzleService>(PuzzleService::class.java)
        return puzzleEntryIds.mapNotNull { puzzleService.getPuzzleEntry(it) }
    }
}

@Singleton
class PuzzleChainService {

    private val logger = LoggerFactory.getLogger(PuzzleChainService::class.java)
    private val chainProgress = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Int>>()

    fun startChain(player: Player, chainEntry: PuzzleChainEntry): Boolean {
        val puzzles = chainEntry.resolvePuzzles()
        if (puzzles.isEmpty() || puzzles.size != chainEntry.puzzleEntryIds.size) {
            logger.warn("Cannot start incomplete puzzle chain ${chainEntry.id}")
            return false
        }
        chainProgress.getOrPut(player.uniqueId) { ConcurrentHashMap() }[chainEntry.id] = 0
        logger.info("Starting puzzle chain ${chainEntry.id} for player ${player.name}")
        return true
    }

    fun startChain(player: Player, chainId: String): Boolean {
        val chain = Query.find<PuzzleChainEntry>().firstOrNull { it.id == chainId } ?: return false
        startChain(player, chain)
        return getChainProgress(player.uniqueId, chainId) == 0
    }

    /** Advances every active chain whose current objective is [puzzleId]. */
    fun onPuzzleCompleted(player: Player, puzzleId: String) {
        val active = chainProgress[player.uniqueId]?.keys?.toList() ?: return
        active.forEach { chainId ->
            val chain = Query.find<PuzzleChainEntry>().firstOrNull { it.id == chainId } ?: return@forEach
            val current = chainProgress[player.uniqueId]?.get(chainId) ?: return@forEach
            if (chain.resolvePuzzles().getOrNull(current)?.id == puzzleId) {
                onPuzzleCompleted(player, chain)
            }
        }
    }

    /** Fails every active chain currently waiting on [puzzleId]. */
    fun onPuzzleFailed(player: Player, puzzleId: String) {
        val active = chainProgress[player.uniqueId]?.keys?.toList() ?: return
        active.forEach { chainId ->
            val chain = Query.find<PuzzleChainEntry>().firstOrNull { it.id == chainId } ?: return@forEach
            val current = chainProgress[player.uniqueId]?.get(chainId) ?: return@forEach
            if (chain.resolvePuzzles().getOrNull(current)?.id == puzzleId) {
                onPuzzleFailed(player, chain)
            }
        }
    }

    fun onPuzzleCompleted(player: Player, chainEntry: PuzzleChainEntry) {
        val progress = chainProgress.getOrPut(player.uniqueId) { ConcurrentHashMap() }
        val currentIndex = progress[chainEntry.id] ?: return
        val puzzles = chainEntry.resolvePuzzles()
        val nextIndex = currentIndex + 1

        if (nextIndex >= puzzles.size) {
            progress.remove(chainEntry.id)
            chainEntry.onChainComplete.triggerFor(player, context())
            logger.info("Puzzle chain ${chainEntry.id} completed by ${player.name}")
        } else {
            progress[chainEntry.id] = nextIndex
            val nextPuzzle = puzzles[nextIndex]
            get<AudienceManager>(AudienceManager::class.java).addPlayerFor(player, nextPuzzle.audienceRef())
            logger.info("Advancing chain ${chainEntry.id} to puzzle $nextIndex for ${player.name}")
        }
    }

    fun onPuzzleFailed(player: Player, chainEntry: PuzzleChainEntry) {
        chainEntry.onChainFail.triggerFor(player, context())
        if (chainEntry.resetOnFail.get(player)) {
            chainProgress[player.uniqueId]?.remove(chainEntry.id)
            logger.info("Puzzle chain ${chainEntry.id} reset for ${player.name}")
        }
    }

    fun getChainProgress(playerId: UUID, chainId: String): Int {
        return chainProgress[playerId]?.get(chainId) ?: -1
    }

    fun clearPlayerChains(playerId: UUID) {
        chainProgress.remove(playerId)
    }

    /** Clears all transient chain sessions during a stop-all or reload. */
    fun clearAllChains() {
        chainProgress.clear()
    }
}





