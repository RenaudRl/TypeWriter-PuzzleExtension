package btcrenaud.puzzle.render

import btcrenaud.puzzle.runtime.PuzzleScheduler
import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent

/**
 * Keeps the client-side puzzle board alive.
 *
 * A puzzle display renders its board as soon as the player joins the audience,
 * which is usually before the client owns the chunk. The client silently drops
 * a block change for an unknown chunk, and the chunk packet that arrives later
 * carries the real (empty) world state. The board therefore stayed invisible
 * until an unrelated block update refreshed the section.
 *
 * Replaying the tracked blocks right after the chunk packet closes that hole
 * for every puzzle type at once.
 */
class PuzzleVisualRefreshListener : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onChunkSentToPlayer(event: PlayerChunkLoadEvent) {
        val player = event.player
        val chunkX = event.chunk.x
        val chunkZ = event.chunk.z
        // One tick later: the chunk packet is already queued, so the replay is
        // guaranteed to be applied on top of it instead of before it.
        PuzzleScheduler.runAtEntityLater(player, 1L) {
            if (player.isOnline) PuzzleRenderers.blocks.resendChunk(player, chunkX, chunkZ)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onRespawn(event: PlayerRespawnEvent) = scheduleFullReplay(event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onWorldChange(event: PlayerChangedWorldEvent) = scheduleFullReplay(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        PuzzleRenderers.blocks.forget(event.player)
    }

    private fun scheduleFullReplay(player: Player) {
        PuzzleScheduler.runAtEntityLater(player, RESPAWN_REPLAY_DELAY_TICKS) {
            if (player.isOnline) PuzzleRenderers.blocks.resendAll(player)
        }
    }

    private companion object {
        /** Leaves the client time to receive the chunks of the new location. */
        const val RESPAWN_REPLAY_DELAY_TICKS = 20L
    }
}
