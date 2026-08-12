package btcrenaud.puzzle

import btcrenaud.puzzle.chain.PuzzleChainService
import btcrenaud.puzzle.objective.audienceRef
import com.typewritermc.engine.paper.entry.AudienceManager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.koin.java.KoinJavaComponent.get
import org.slf4j.LoggerFactory

/**
 * Global listener for the Puzzle extension.
 *
 * Handles player death and quit cleanup to prevent dead-player ticking.
 * Puzzle-specific interactions are handled by individual displays.
 */
class PuzzleListener : Listener {

    private val logger = LoggerFactory.getLogger(PuzzleListener::class.java)
    private val service = get<PuzzleService>(PuzzleService::class.java)
    private val chainService = get<PuzzleChainService>(PuzzleChainService::class.java)
    private val audienceManager = get<AudienceManager>(AudienceManager::class.java)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity
        service.getAllPuzzleEntries().forEach { entry ->
            audienceManager.removePlayerFor(player, entry.audienceRef())
        }
        service.clearPlayerState(player.uniqueId)
        chainService.clearPlayerChains(player.uniqueId)
        logger.trace("Cleared puzzle state for dead player ${player.name}")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        service.clearPlayerState(event.player.uniqueId)
        chainService.clearPlayerChains(event.player.uniqueId)
    }
}

