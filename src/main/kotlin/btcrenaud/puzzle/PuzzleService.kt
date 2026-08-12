package btcrenaud.puzzle

import btcrenaud.puzzle.artifact.PuzzleArtifactData
import btcrenaud.puzzle.artifact.PuzzleArtifactEntry
import btcrenaud.puzzle.objective.BasePuzzleObjectiveEntry
import com.typewritermc.core.entries.Query
import com.typewritermc.core.extension.annotations.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe runtime and persistence boundary for Puzzle.
 *
 * The maps are concurrent, but each player's mutable state is additionally
 * serialized through a per-player lock. This is intentional: a concurrent map
 * alone does not make a read-modify-write transition atomic.
 */
@Singleton
class PuzzleService {

    private val logger = LoggerFactory.getLogger(PuzzleService::class.java)

    private val playerStates = ConcurrentHashMap<UUID, ConcurrentHashMap<String, PuzzlePlayerState>>()
    private val playerStats = ConcurrentHashMap<UUID, ConcurrentHashMap<String, PuzzleStats>>()
    private val playerLocks = ConcurrentHashMap<UUID, Any>()
    private val puzzleEntries = ConcurrentHashMap<String, BasePuzzleObjectiveEntry>()

    fun registerPuzzleEntry(entry: BasePuzzleObjectiveEntry) {
        require(entry.id.isNotBlank()) { "Puzzle entry id cannot be blank" }
        puzzleEntries[entry.id] = entry
        logger.debug("Registered puzzle entry: ${entry.id} (${entry.puzzleType.displayName})")
    }

    fun unregisterPuzzleEntry(id: String) {
        puzzleEntries.remove(id)
    }

    fun getPuzzleEntry(id: String): BasePuzzleObjectiveEntry? = puzzleEntries[id]

    fun getAllPuzzleEntries(): Collection<BasePuzzleObjectiveEntry> = puzzleEntries.values.toList()

    /** Returns the live state object for legacy puzzle adapters. */
    fun getState(playerId: UUID, puzzleId: String): PuzzlePlayerState =
        playerStates
            .computeIfAbsent(playerId) { ConcurrentHashMap() }
            .computeIfAbsent(puzzleId) { PuzzlePlayerState() }

    /** Returns the existing state without creating a new runtime session. */
    fun getExistingState(playerId: UUID, puzzleId: String): PuzzlePlayerState? =
        playerStates[playerId]?.get(puzzleId)

    /** Returns whether this player has already validated the puzzle. */
    fun isSolved(playerId: UUID, puzzleId: String): Boolean =
        getExistingState(playerId, puzzleId)?.solved == true

    fun resetState(playerId: UUID, puzzleId: String) {
        synchronized(lockFor(playerId)) {
            playerStates[playerId]?.remove(puzzleId)
        }
    }

    /** Resets progress while preserving the player's attempt history. */
    fun resetProgress(playerId: UUID, puzzleId: String, nowMillis: Long = System.currentTimeMillis()) {
        synchronized(lockFor(playerId)) {
            val state = getState(playerId, puzzleId)
            state.progress = 0
            state.solved = false
            state.wrongAttempts = 0
            state.startedAtTime = 0L
            state.cooldownUntil = 0L
            state.lastResetTime = nowMillis
        }
    }

    /**
     * Opens a fresh attempt window for an unsolved puzzle.
     *
     * `maxAttempts` and the failure cooldown are session scoped. Because
     * `wrongAttempts` is persisted in the artifact and was never cleared, a
     * puzzle that had already burned its attempts stayed locked forever: each
     * interaction re-armed the cooldown through `MAX_ATTEMPTS_REACHED` and the
     * player only ever saw a wait message.
     *
     * A solved puzzle is left untouched so completion stays idempotent.
     */
    fun beginSession(playerId: UUID, puzzleId: String, nowMillis: Long = System.currentTimeMillis()): Boolean =
        synchronized(lockFor(playerId)) {
            val state = getState(playerId, puzzleId)
            if (state.solved) return@synchronized false
            state.progress = 0
            state.wrongAttempts = 0
            state.cooldownUntil = 0L
            state.startedAtTime = nowMillis
            true
        }

    fun markSessionStarted(playerId: UUID, puzzleId: String, nowMillis: Long = System.currentTimeMillis()) {
        synchronized(lockFor(playerId)) {
            val state = getState(playerId, puzzleId)
            if (!state.solved && state.startedAtTime == 0L) {
                state.startedAtTime = nowMillis
            }
        }
    }

    /**
     * Clears ephemeral session state. Persistent statistics are deliberately
     * retained so a disconnect cannot erase the leaderboard.
     */
    fun clearPlayerState(playerId: UUID) {
        synchronized(lockFor(playerId)) {
            playerStates.remove(playerId)
        }
    }

    fun isOnCooldown(playerId: UUID, puzzleId: String? = null, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val states = playerStates[playerId] ?: return false
        val onCooldown = if (puzzleId == null) {
            states.values.any { it.cooldownUntil > nowMillis }
        } else {
            states[puzzleId]?.cooldownUntil?.let { it > nowMillis } == true
        }
        if (!onCooldown && puzzleId != null) {
            states[puzzleId]?.let { state ->
                if (state.cooldownUntil != 0L && state.cooldownUntil <= nowMillis) {
                    state.cooldownUntil = 0L
                }
            }
        }
        return onCooldown
    }

    /** Remaining cooldown in milliseconds, or 0 when the player can act again. */
    fun remainingCooldownMillis(
        playerId: UUID,
        puzzleId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long {
        val until = playerStates[playerId]?.get(puzzleId)?.cooldownUntil ?: return 0L
        return (until - nowMillis).coerceAtLeast(0L)
    }

    fun setCooldown(
        playerId: UUID,
        durationSeconds: Int,
        puzzleId: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (durationSeconds <= 0) return
        require(!puzzleId.isNullOrBlank()) { "Puzzle-specific cooldowns require a puzzle id" }
        synchronized(lockFor(playerId)) {
            getState(playerId, puzzleId).cooldownUntil = nowMillis + durationSeconds * 1_000L
        }
    }

    /**
     * Applies a common result exactly once and returns whether side effects such
     * as facts or Typewriter triggers should be emitted by the adapter.
     */
    fun applyResult(
        playerId: UUID,
        puzzleId: String,
        result: PuzzleResult,
        resetOnWrong: Boolean,
        cooldownSeconds: Int,
        progressLimit: Int = Int.MAX_VALUE,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lockFor(playerId)) {
        val state = getState(playerId, puzzleId)

        if (result == PuzzleResult.COMPLETED && state.solved) return@synchronized false
        if (result != PuzzleResult.COMPLETED && state.solved) return@synchronized false

        when (result) {
            PuzzleResult.COMPLETED -> {
                state.progress = progressLimit.coerceAtLeast(1)
                state.solved = true
                state.lastSolveTime = nowMillis
                recordAttemptLocked(playerId, puzzleId)
                val solveTime = if (state.startedAtTime > 0L) {
                    (nowMillis - state.startedAtTime).coerceAtLeast(0L)
                } else {
                    0L
                }
                recordSolveLocked(playerId, puzzleId, solveTime)
            }

            PuzzleResult.STEP_CORRECT -> {
                state.progress = (state.progress + 1).coerceAtMost(progressLimit.coerceAtLeast(1))
                state.lastInteractionTime = nowMillis
                recordAttemptLocked(playerId, puzzleId)
            }

            PuzzleResult.WRONG -> {
                state.wrongAttempts++
                state.lastInteractionTime = nowMillis
                recordAttemptLocked(playerId, puzzleId)
            }

            PuzzleResult.WRONG_RESET -> {
                state.wrongAttempts++
                state.lastResetTime = nowMillis
                state.lastInteractionTime = nowMillis
                if (resetOnWrong || result == PuzzleResult.WRONG_RESET) {
                    state.progress = 0
                }
                recordAttemptLocked(playerId, puzzleId)
                setCooldownLocked(state, cooldownSeconds, nowMillis)
            }

            PuzzleResult.TIMEOUT,
            PuzzleResult.LIFESPAN_EXPIRED,
            -> {
                state.progress = 0
                state.solved = false
                state.lastResetTime = nowMillis
                setCooldownLocked(state, cooldownSeconds, nowMillis)
            }

            PuzzleResult.MAX_ATTEMPTS_REACHED -> {
                setCooldownLocked(state, cooldownSeconds, nowMillis)
            }

            else -> return@synchronized false
        }
        true
    }

    fun getStats(playerId: UUID, puzzleId: String): PuzzleStats =
        playerStats
            .computeIfAbsent(playerId) { ConcurrentHashMap() }
            .computeIfAbsent(puzzleId) { PuzzleStats() }

    fun recordSolve(playerId: UUID, puzzleId: String, solveTimeMs: Long) {
        synchronized(lockFor(playerId)) {
            recordSolveLocked(playerId, puzzleId, solveTimeMs)
        }
    }

    fun recordAttempt(playerId: UUID, puzzleId: String) {
        synchronized(lockFor(playerId)) {
            recordAttemptLocked(playerId, puzzleId)
        }
    }

    fun leaderboard(puzzleId: String, limit: Int = 10): List<PuzzleLeaderboardRow> =
        playerStats.entries
            .mapNotNull { (playerId, statsByPuzzle) ->
                val stats = statsByPuzzle[puzzleId] ?: return@mapNotNull null
                PuzzleLeaderboardRow(
                    playerId = playerId,
                    solves = stats.solves,
                    bestTimeMs = stats.bestTimeMs,
                    attempts = stats.attempts,
                )
            }
            .sortedWith(
                compareByDescending<PuzzleLeaderboardRow> { it.solves }
                    .thenBy { if (it.bestTimeMs > 0L) it.bestTimeMs else Long.MAX_VALUE }
                    .thenBy { it.attempts },
            )
            .take(limit.coerceIn(1, 100))

    private fun recordSolveLocked(playerId: UUID, puzzleId: String, solveTimeMs: Long) {
        val stats = getStats(playerId, puzzleId)
        stats.solves++
        if (solveTimeMs > 0L && (stats.bestTimeMs <= 0L || solveTimeMs < stats.bestTimeMs)) {
            stats.bestTimeMs = solveTimeMs
        }
    }

    private fun recordAttemptLocked(playerId: UUID, puzzleId: String) {
        getStats(playerId, puzzleId).attempts++
    }

    private fun setCooldownLocked(state: PuzzlePlayerState, durationSeconds: Int, nowMillis: Long) {
        if (durationSeconds > 0) {
            state.cooldownUntil = nowMillis + durationSeconds * 1_000L
        }
    }

    private fun lockFor(playerId: UUID): Any = playerLocks.computeIfAbsent(playerId) { Any() }

    private fun findArtifact(): PuzzleArtifactEntry? {
        val artifacts = Query.find<PuzzleArtifactEntry>().toList()
        val current = artifacts.firstOrNull { !it.usesLegacyIdentity }
        if (current != null) return current

        val legacy = artifacts.firstOrNull()
        if (legacy?.usesLegacyIdentity == true) {
            logger.warn(
                "Puzzle artifact ${PuzzleArtifactEntry.LEGACY_ARTIFACT_ID} uses a legacy identity; " +
                    "create a generated artifact page and migrate it before changing the existing page.",
            )
        }
        return legacy
    }

    /** Builds a defensive snapshot while each player's mutable state is locked. */
    private fun snapshot(): PuzzleArtifactData {
        val states = playerStates.entries.associate { (uuid, puzzleMap) ->
            val serialized = synchronized(lockFor(uuid)) {
                puzzleMap.mapValues { (_, state) ->
                    PuzzleArtifactData.SerializedState(
                        progress = state.progress,
                        solved = state.solved,
                        wrongAttempts = state.wrongAttempts,
                        lastInteractionTime = state.lastInteractionTime,
                        startedAtTime = state.startedAtTime,
                        lastSolveTime = state.lastSolveTime,
                        lastResetTime = state.lastResetTime,
                        cooldownUntil = state.cooldownUntil,
                    )
                }
            }
            uuid.toString() to serialized
        }
        val stats = playerStats.entries.associate { (uuid, puzzleMap) ->
            val serialized = synchronized(lockFor(uuid)) {
                puzzleMap.mapValues { (_, value) ->
                    PuzzleArtifactData.SerializedStats(
                        attempts = value.attempts,
                        solves = value.solves,
                        bestTimeMs = value.bestTimeMs,
                    )
                }
            }
            uuid.toString() to serialized
        }
        return PuzzleArtifactData(states = states, stats = stats)
    }

    suspend fun saveToArtifact() {
        val artifact = findArtifact() ?: run {
            logger.debug("No PuzzleArtifactEntry found - skipping save")
            return
        }
        try {
            withContext(Dispatchers.IO) {
                artifact.saveData(snapshot())
            }
            logger.debug("Saved puzzle states to artifact")
        } catch (e: Exception) {
            logger.error("Failed to save puzzle states", e)
        }
    }

    suspend fun loadFromArtifact() {
        val artifact = findArtifact() ?: run {
            logger.info("No PuzzleArtifactEntry found - starting fresh")
            return
        }

        try {
            val data = withContext(Dispatchers.IO) { artifact.loadData() }
            data.states.forEach { (playerIdStr, puzzleMap) ->
                val playerId = runCatching { UUID.fromString(playerIdStr) }.getOrNull() ?: return@forEach
                synchronized(lockFor(playerId)) {
                    val puzzleStates = playerStates.computeIfAbsent(playerId) { ConcurrentHashMap() }
                    puzzleMap.forEach { (puzzleId, serialized) ->
                        puzzleStates[puzzleId] = PuzzlePlayerState(
                            progress = serialized.progress.coerceAtLeast(0),
                            solved = serialized.solved,
                            wrongAttempts = serialized.wrongAttempts.coerceAtLeast(0),
                            lastInteractionTime = serialized.lastInteractionTime.coerceAtLeast(0L),
                            startedAtTime = serialized.startedAtTime.coerceAtLeast(0L),
                            lastSolveTime = serialized.lastSolveTime.coerceAtLeast(0L),
                            lastResetTime = serialized.lastResetTime.coerceAtLeast(0L),
                            cooldownUntil = serialized.cooldownUntil.coerceAtLeast(0L),
                        )
                    }
                }
            }
            data.stats.forEach { (playerIdStr, puzzleMap) ->
                val playerId = runCatching { UUID.fromString(playerIdStr) }.getOrNull() ?: return@forEach
                synchronized(lockFor(playerId)) {
                    val puzzleStats = playerStats.computeIfAbsent(playerId) { ConcurrentHashMap() }
                    puzzleMap.forEach { (puzzleId, serialized) ->
                        puzzleStats[puzzleId] = PuzzleStats(
                            attempts = serialized.attempts.coerceAtLeast(0),
                            solves = serialized.solves.coerceAtLeast(0),
                            bestTimeMs = serialized.bestTimeMs.coerceAtLeast(0L),
                        )
                    }
                }
            }
            logger.info("Loaded puzzle states from artifact")
        } catch (e: Exception) {
            logger.warn("Failed to load puzzle states from artifact; keeping current state", e)
        }
    }

    fun clearAllRuntimeStates() {
        playerStates.clear()
        playerLocks.clear()
    }
}

data class PuzzleStats(
    @Volatile var attempts: Int = 0,
    @Volatile var solves: Int = 0,
    @Volatile var bestTimeMs: Long = 0L,
)

data class PuzzleLeaderboardRow(
    val playerId: UUID,
    val solves: Int,
    val bestTimeMs: Long,
    val attempts: Int,
)
