package btcrenaud.puzzle.objective

import btcrenaud.puzzle.PuzzleResult
import btcrenaud.puzzle.PuzzleService
import btcrenaud.puzzle.PuzzleTimerState
import com.typewritermc.core.entries.Ref
import com.typewritermc.engine.paper.entry.entries.AudienceDisplay
import com.typewritermc.engine.paper.entry.matches
import com.typewritermc.engine.paper.entry.triggerFor
import btcrenaud.puzzle.runtime.PuzzleScheduler
import org.bukkit.entity.Player
import org.koin.java.KoinJavaComponent.get
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Abstract AudienceDisplay for puzzle objectives.
 *
 * Manages per-player puzzle state, cooldowns, timeouts, lifespan, and triggers.
 * Each puzzle type creates a subclass that implements the specific display logic.
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

    override fun onPlayerAdd(player: Player) {
        if (!puzzleEntry.criteria.matches(player)) {
            // AudienceDisplay adds the player before invoking this callback.
            // Explicitly remove rejected players or they remain eligible for
            // puzzle event handlers despite failing the configured criteria.
            removePlayer(player)
            return
        }

        // Fire onStart trigger
        puzzleEntry.onStart.triggerFor(player, com.typewritermc.core.interaction.context())

        val playerId = player.uniqueId
        val now = System.currentTimeMillis()
        // Joining the audience is what starts a puzzle session, so the attempt
        // budget and the failure cooldown are cleared here. Without it a puzzle
        // that exhausted maxAttempts in an earlier session could never be played
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

    override fun onPlayerRemove(player: Player) {
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




