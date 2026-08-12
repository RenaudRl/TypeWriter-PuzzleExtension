package btcrenaud.puzzle

import btcrenaud.puzzle.entries.block_push.BlockPushEntry
import btcrenaud.puzzle.entries.combination_lock.CombinationLockEntry
import btcrenaud.puzzle.entries.coop_pressure.CoopPressureEntry
import btcrenaud.puzzle.entries.item_frame.ItemFrameEntry
import btcrenaud.puzzle.entries.laser_grid.LaserGridEntry
import btcrenaud.puzzle.entries.lever_order.LeverOrderEntry
import btcrenaud.puzzle.entries.memory_sequence.MemorySequenceEntry
import btcrenaud.puzzle.entries.pattern_placement.PatternPlacementEntry
import btcrenaud.puzzle.entries.pedestal.PedestalOfferingEntry
import btcrenaud.puzzle.objective.BasePuzzleObjectiveEntry
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.plugin
import btcrenaud.puzzle.runtime.PuzzleScheduler
import com.typewritermc.core.entries.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent
import org.slf4j.LoggerFactory

@Singleton
class PuzzleInitializer : Initializable {

    private val logger = LoggerFactory.getLogger(PuzzleInitializer::class.java)
    private val service = KoinJavaComponent.get<PuzzleService>(PuzzleService::class.java)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var autoSaveTask: PuzzleScheduler.TaskHandle? = null

    override suspend fun initialize() {
        logger.info("Initializing Puzzle extension...")

        // Load persisted state
        service.loadFromArtifact()

        // Query is class-based. BasePuzzleObjectiveEntry is an interface and is
        // therefore not indexed as a discoverable Typewriter entry type; query
        // each concrete entry class explicitly.
        val entries = buildList<BasePuzzleObjectiveEntry> {
            Query.find<BlockPushEntry>().forEach(::add)
            Query.find<MemorySequenceEntry>().forEach(::add)
            Query.find<LaserGridEntry>().forEach(::add)
            Query.find<LeverOrderEntry>().forEach(::add)
            Query.find<PatternPlacementEntry>().forEach(::add)
            Query.find<CoopPressureEntry>().forEach(::add)
            Query.find<ItemFrameEntry>().forEach(::add)
            Query.find<CombinationLockEntry>().forEach(::add)
            Query.find<PedestalOfferingEntry>().forEach(::add)
        }.distinctBy { it.id }
        entries.forEach(service::registerPuzzleEntry)
        logger.info("Registered ${entries.size} puzzle entries")

        // Register death listener for cleanup
        PuzzleScheduler.runTask {
            // Replays the client-side board after a chunk is sent to the player.
            // Without it the initial visuals are dropped by the client.
            plugin.server.pluginManager.registerEvents(
                btcrenaud.puzzle.render.PuzzleVisualRefreshListener(),
                plugin,
            )
            plugin.server.pluginManager.registerEvents(PuzzleListener(), plugin)
            logger.info("PuzzleListener registered")
        }

        // Artifact I/O is async. The scheduler only wakes the persistence scope;
        // it never blocks the global region thread.
        autoSaveTask = PuzzleScheduler.runAsyncAtFixedRate(200L, 600L) {
            persistenceScope.launch {
                runCatching { service.saveToArtifact() }
                    .onFailure { logger.error("Auto-save failed", it) }
            }
        }


        logger.info("Puzzle extension initialized")
    }

    override suspend fun shutdown() {
        logger.info("Shutting down Puzzle extension...")
        autoSaveTask?.cancel()
        autoSaveTask = null
        withContext(Dispatchers.IO) {
            service.saveToArtifact()
        }
        persistenceScope.cancel()
        service.clearAllRuntimeStates()
        logger.info("Puzzle extension shut down")
    }
}




