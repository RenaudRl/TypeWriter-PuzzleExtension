package btcrenaud.puzzle.render

import btcrenaud.puzzle.runtime.PuzzleScheduler
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Rendering boundary shared by all public puzzle displays. */
interface PuzzleRenderer {
    fun show(player: Player, location: Location, material: Material, owner: String)
    fun showData(player: Player, location: Location, data: BlockData, owner: String)
    fun clear(player: Player, location: Location)
    fun clearOwner(player: Player, owner: String)

    /**
     * Re-sends every tracked block of [player] that lives in the given chunk.
     *
     * The client discards a block change for a chunk it has not received yet,
     * and a freshly delivered chunk overwrites everything the puzzle already
     * pushed. Without this replay the initial board stays invisible until an
     * unrelated block update happens to refresh the section.
     */
    fun resendChunk(player: Player, chunkX: Int, chunkZ: Int)

    /** Re-sends every tracked block of [player], regardless of chunk. */
    fun resendAll(player: Player)

    /** Drops the tracked state of [player] without touching the client. */
    fun forget(player: Player)
}

/**
 * Pure client-side renderer using Paper's sendBlockChange API.
 * It never mutates the world and keeps enough metadata to restore the
 * underlying block when a display leaves the audience.
 */
class ClientSidePuzzleRenderer : PuzzleRenderer {
    private data class RenderedBlock(val location: Location, val data: BlockData)

    private val byPlayer = ConcurrentHashMap<UUID, ConcurrentHashMap<String, ConcurrentHashMap<String, RenderedBlock>>>()

    /** Players with a replay already scheduled, so a burst costs a single task. */
    private val pendingReasserts = ConcurrentHashMap.newKeySet<UUID>()

    override fun show(player: Player, location: Location, material: Material, owner: String) {
        showData(player, location, material.createBlockData(), owner)
    }

    override fun showData(player: Player, location: Location, data: BlockData, owner: String) {
        val key = key(location)
        val owners = byPlayer.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }
        val blocks = owners.computeIfAbsent(owner) { ConcurrentHashMap() }
        blocks[key] = RenderedBlock(location.clone(), data.clone())
        player.sendBlockChange(location, data)
        scheduleReassert(player)
    }

    override fun clear(player: Player, location: Location) {
        val owners = byPlayer[player.uniqueId] ?: return
        owners.values.forEach { it.remove(key(location)) }
        restore(player, location)
    }

    override fun clearOwner(player: Player, owner: String) {
        val owners = byPlayer[player.uniqueId] ?: return
        val removed = owners.remove(owner)?.values.orEmpty().toList()
        removed.forEach { restore(player, it.location) }
        if (owners.isEmpty()) byPlayer.remove(player.uniqueId, owners)
    }

    override fun resendChunk(player: Player, chunkX: Int, chunkZ: Int) {
        forEachTracked(player) { rendered ->
            if (rendered.location.blockX shr 4 != chunkX) return@forEachTracked
            if (rendered.location.blockZ shr 4 != chunkZ) return@forEachTracked
            if (rendered.location.world?.uid != player.world.uid) return@forEachTracked
            player.sendBlockChange(rendered.location, rendered.data)
        }
    }

    override fun resendAll(player: Player) {
        forEachTracked(player) { rendered ->
            if (rendered.location.world?.uid != player.world.uid) return@forEachTracked
            player.sendBlockChange(rendered.location, rendered.data)
        }
    }

    override fun forget(player: Player) {
        byPlayer.remove(player.uniqueId)
    }

    /**
     * Re-sends the tracked board shortly after a render burst.
     *
     * Restarting a puzzle removes the player from the audience and adds them
     * back in the same tick, so the "restore the real block" packet emitted by
     * [clearOwner] can land *after* the freshly rendered board and erase it.
     * A single debounced replay a few ticks later restores the authoritative
     * puzzle state without racing anything.
     */
    private fun scheduleReassert(player: Player) {
        if (!pendingReasserts.add(player.uniqueId)) return
        PuzzleScheduler.runAtEntityLater(player, REASSERT_DELAY_TICKS) {
            pendingReasserts.remove(player.uniqueId)
            if (player.isOnline) resendAll(player)
        }
    }

    private inline fun forEachTracked(player: Player, action: (RenderedBlock) -> Unit) {
        val owners = byPlayer[player.uniqueId] ?: return
        owners.values.forEach { blocks -> blocks.values.forEach(action) }
    }

    private fun restore(player: Player, location: Location) {
        player.sendBlockChange(location, location.block.blockData)
    }

    private fun key(location: Location): String =
        "${location.world?.uid}:${location.blockX}:${location.blockY}:${location.blockZ}"

    private companion object {
        /** Must outlast the restore packet dispatched by [clearOwner]. */
        const val REASSERT_DELAY_TICKS = 5L
    }
}

/**
 * Single renderer shared by every puzzle display.
 *
 * The owner id already scopes the tracked blocks per puzzle entry, and a single
 * instance is what lets [PuzzleVisualRefreshListener] replay the whole board of
 * a player from one place.
 */
object PuzzleRenderers {
    val blocks: PuzzleRenderer = ClientSidePuzzleRenderer()
}
