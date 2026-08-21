package btcrenaud.puzzle.objective

import btcrenaud.puzzle.PuzzleResult
import btcrenaud.puzzle.PuzzleService
import btcrenaud.puzzle.PuzzleTimerState
import btcrenaud.puzzle.runtime.PuzzleScheduler
import com.typewritermc.engine.paper.entry.entries.AudienceDisplay
import com.typewritermc.engine.paper.entry.matches
import com.typewritermc.engine.paper.entry.triggerFor
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.koin.java.KoinJavaComponent.get
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Abstract AudienceDisplay for puzzle objectives.
 *
 * Manages per-player puzzle state, cooldowns, timeouts, lifespan, and triggers.
 * Each puzzle type creates a subclass that implements the specific display logic.
 *
 * ## Spatial activation
 *
 * Joining the audience is **not** what runs a puzzle: a puzzle only runs for a
 * player standing in the world of its own positions, within
 * [BasePuzzleObjectiveEntry.activationRadius] blocks of them. Without that gate a
 * display rendered and ticked for every audience member wherever they were —
 * client-side blocks and particles carry raw coordinates and no world, so a laser
 * grid configured in one dimension was drawn in **every** dimension the player
 * visited, and a memory sequence started at any distance.
 *
 * Subclasses therefore declare their board through [puzzleAnchors] and do their
 * setup/teardown in [onPuzzleActivate] / [onPuzzleDeactivate] instead of
 * `onPlayerAdd` / `onPlayerRemove`.
 */
abstract class PuzzleObjectiveDisplay<T : BasePuzzleObjectiveEntry>(
    protected val puzzleEntry: T,
    protected val puzzleId: String,
) : AudienceDisplay() {

    protected val service = get<PuzzleService>(PuzzleService::class.java)

    private val startTimes = ConcurrentHashMap<UUID, Long>()
    private val timeoutTasks = ConcurrentHashMap<UUID, PuzzleScheduler.TaskHandle>()
    private val lifespanTimers = ConcurrentHashMap<UUID, PuzzleTimerState>()
    private val lifespanTasks = ConcurrentHashMap<UUID, PuzzleScheduler.TaskHandle>()

    /** Players for whom the puzzle currently runs (in world, in range). */
    private val activePlayers = ConcurrentHashMap.newKeySet<UUID>()

    /** Anchors resolved once per audience join; [Var] lookups are not free. */
    private val anchorCache = ConcurrentHashMap<UUID, List<Location>>()

    /**
     * World positions that anchor this puzzle for [player].
     *
     * Every configured board position should be returned: the gate keeps the
     * player as long as **one** anchor is close enough. An empty list disables
     * the spatial gate entirely, which is only correct for a puzzle that has no
     * position at all.
     */
    protected open fun puzzleAnchors(player: Player): List<Location> = emptyList()

    /** Called once the player enters the puzzle's world and radius. */
    protected open fun onPuzzleActivate(player: Player) {}

    /**
     * Called when the player leaves the radius, the world, or the audience.
     *
     * Must undo everything [onPuzzleActivate] did — client-side blocks above all,
     * or the board stays painted on a client that walked away.
     */
    protected open fun onPuzzleDeactivate(player: Player) {}

    /** True while the puzzle actually runs for [player]. */
    fun isPuzzleActive(player: Player): Boolean = player.uniqueId in activePlayers

    // Containment is what every display checks before reacting to an event or
    // rendering, so folding the spatial gate into it makes the whole extension
    // range-aware without touching a single handler.
    override fun contains(player: Player): Boolean = super.contains(player) && isPuzzleActive(player)

    override fun contains(uuid: UUID): Boolean = super.contains(uuid) && uuid in activePlayers

    /** Audience members considered by the gate, whether active or not. */
    protected val consideredPlayers: List<Player> get() = super.players

    final override fun onPlayerAdd(player: Player) {
        if (!puzzleEntry.criteria.matches(player)) {
            // AudienceDisplay adds the player before invoking this callback.
            // Explicitly remove rejected players or they remain eligible for
            // puzzle event handlers despite failing the configured criteria.
            removePlayer(player)
            return
        }

        anchorCache[player.uniqueId] = runCatching { puzzleAnchors(player) }.getOrDefault(emptyList())
        updateActivation(player)
    }

    final override fun onPlayerRemove(player: Player) {
        deactivate(player)
        anchorCache.remove(player.uniqueId)
    }

    // === Spatial gate ===

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPuzzleProximityMove(event: PlayerMoveEvent) {
        // Block granularity is enough for a radius expressed in blocks and keeps
        // the handler off the per-tick movement packets.
        if (!event.hasChangedBlock()) return
        updateActivation(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPuzzleProximityWorldChange(event: PlayerChangedWorldEvent) {
        updateActivation(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPuzzleProximityRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        // The player location is still the death location during the event.
        PuzzleScheduler.runAtEntityLater(player, 1L) { updateActivation(player) }
    }

    private fun updateActivation(player: Player) {
        if (!super.contains(player)) return
        if (player.isDead || !isWithinActivationRange(player)) {
            deactivate(player)
            return
        }
        activate(player)
    }

    private fun isWithinActivationRange(player: Player): Boolean {
        val anchors = anchorCache[player.uniqueId] ?: return true
        if (anchors.isEmpty()) return true

        val location = player.location
        val worldId = location.world?.uid ?: return false
        val sameWorld = anchors.filter { it.world?.uid == worldId }
        // Dimension gate: never negotiable, a board belongs to one world.
        if (sameWorld.isEmpty()) return false

        val radius = puzzleEntry.activationRadius.get(player)
        if (radius <= 0.0) return true
        val squaredRadius = radius * radius
        return sameWorld.any { it.distanceSquared(location) <= squaredRadius }
    }

    private fun activate(player: Player) {
        if (!activePlayers.add(player.uniqueId)) return
        beginPuzzleSession(player)
        onPuzzleActivate(player)
    }

    private fun deactivate(player: Player) {
        if (!activePlayers.remove(player.uniqueId)) return
        onPuzzleDeactivate(player)
        endPuzzleSession(player)
    }

    // === Session lifecycle ===

    private fun beginPuzzleSession(player: Player) {
        puzzleEntry.onStart.triggerFor(player, com.typewritermc.core.interaction.context())

        val playerId = player.uniqueId
        val now = System.currentTimeMillis()
        // Reaching the puzzle is what starts a session, so the attempt budget and
        // the failure cooldown are cleared here. Without it a puzzle that
        // exhausted maxAttempts in an earlier session could never be played
        // again: every interaction re-armed the cooldown silently.
        service.beginSession(playerId, puzzleId, now)
        startTimes[playerId] = now

        // Schedule timeout if time limited
        val timeLimit = puzzleEntry.timeLimit.get(player)
        if (timeLimit > 0) {
            val timeoutTask = PuzzleScheduler.runAtEntityLater(player, timeLimit * 20L) {
                if (!service.getState(playerId, puzzleId).solved) {
                    handlePuzzleResult(
                        PuzzleResult.TIMEOUT,
                        player, puzzleEntry, service, puzzleId
                    )
                    onPuzzleTimeout(player)
                }
                timeoutTasks.remove(playerId)
            }
            timeoutTasks[playerId] = timeoutTask
        }

        // Schedule lifespan timer if configured
        val lifespan = puzzleEntry.lifespan.get(player)
        if (lifespan > 0) {
            val timerState = PuzzleTimerState(
                startTime = System.currentTimeMillis(),
                lifespanSeconds = lifespan,
            )
            lifespanTimers[playerId] = timerState
            val lifespanTask = PuzzleScheduler.runAtEntityLater(player, lifespan * 20L) {
                val state = lifespanTimers[playerId]
                if (state != null && !state.expired && !service.getState(playerId, puzzleId).solved) {
                    state.expired = true
                    handlePuzzleResult(
                        PuzzleResult.LIFESPAN_EXPIRED,
                        player, puzzleEntry, service, puzzleId
                    )
                    onPuzzleLifespanExpired(player)
                }
                lifespanTasks.remove(playerId)
            }
            lifespanTasks[playerId] = lifespanTask
        }
    }

    private fun endPuzzleSession(player: Player) {
        val playerId = player.uniqueId
        timeoutTasks.remove(playerId)?.cancel()
        startTimes.remove(playerId)
        lifespanTasks.remove(playerId)?.cancel()
        lifespanTimers.remove(playerId)
    }

    /**
     * Called when the puzzle times out. Override to clean up visual state.
     */
    protected open fun onPuzzleTimeout(player: Player) {}

    /**
     * Called when the puzzle lifespan expires. Override to clean up visual state.
     */
    protected open fun onPuzzleLifespanExpired(player: Player) {}

    /**
     * Get remaining time in seconds, or -1 if no time limit.
     */
    protected fun getRemainingSeconds(player: Player): Int {
        val startTime = startTimes[player.uniqueId] ?: return -1
        val limit = puzzleEntry.timeLimit.get(player)
        if (limit <= 0) return -1
        val elapsed = (System.currentTimeMillis() - startTime) / 1000
        return (limit - elapsed.toInt()).coerceAtLeast(0)
    }

    /**
     * Get remaining lifespan in seconds, or -1 if no lifespan limit.
     */
    protected fun getRemainingLifespan(player: Player): Int {
        val timer = lifespanTimers[player.uniqueId] ?: return -1
        return timer.remainingSeconds()
    }

    /**
     * Check if the puzzle lifespan has expired.
     */
    protected fun isLifespanExpired(player: Player): Boolean {
        val timer = lifespanTimers[player.uniqueId] ?: return false
        return timer.isExpired()
    }

    /**
     * Check if the player has exceeded max attempts.
     */
    protected fun hasExceededMaxAttempts(player: Player): Boolean {
        val maxAttempts = puzzleEntry.maxAttempts.get(player)
        if (maxAttempts <= 0) return false
        val state = service.getState(player.uniqueId, puzzleId)
        return state.wrongAttempts >= maxAttempts
    }
}

/**
 * Resolves the anchors of a puzzle, dropping the ones that were never configured.
 *
 * A `Position` field left empty does not come back as `Position.ORIGIN`: the
 * world serializer resolves an unknown identifier to the first loaded world, so
 * an untouched field looks like a real position at 0/0/0. Keeping those would
 * anchor the puzzle to the spawn of an arbitrary world and lock the player out.
 */
internal fun List<Location>.withoutUnsetAnchors(): List<Location> =
    filterNot { it.blockX == 0 && it.blockY == 0 && it.blockZ == 0 }
