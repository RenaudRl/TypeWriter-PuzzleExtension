package btcrenaud.puzzle

/**
 * Enum identifying the type of puzzle for entry discovery and display grouping.
 */
enum class PuzzleType(val displayName: String, val icon: String) {
    BLOCK_PUSH("Block Push", "mdi:arrow-expand"),
    LASER_GRID("Laser Grid", "mdi:laser"),
    MEMORY_SEQUENCE("Memory Sequence", "mdi:brain"),
    LEVER_ORDER("Lever Order", "mdi:order-numeric-ascending"),
    PATTERN_PLACEMENT("Pattern Placement", "mdi:grid"),
    COOP_PRESSURE("Coop Pressure", "mdi:account-group"),
    ITEM_FRAME("Item Frame", "mdi:rotate-3d"),
    COMBINATION_LOCK("Combination Lock", "mdi:lock"),
    PEDESTAL_OFFERING("Pedestal Offering", "mdi:hand"),
}

/**
 * Result of a puzzle interaction attempt.
 */
enum class PuzzleResult {
    /** The puzzle was just solved on this attempt. */
    COMPLETED,
    /** A single step was completed successfully. */
    STEP_CORRECT,
    /** The attempt was wrong but no reset occurred. */
    WRONG,
    /** Wrong attempt triggered a full puzzle reset. */
    WRONG_RESET,
    /** The time limit expired. */
    TIMEOUT,
    /** The puzzle was already solved (solve-once mode). */
    ALREADY_SOLVED,
    /** Player is on cooldown. */
    ON_COOLDOWN,
    /** Maximum attempts reached. */
    MAX_ATTEMPTS_REACHED,
    /** Puzzle lifespan expired. */
    LIFESPAN_EXPIRED,
    /** Invalid state or missing configuration. */
    INVALID,
}

/**
 * Runtime state for a per-player puzzle instance.
 */
data class PuzzlePlayerState(
    /** Current progress value (e.g. steps completed, blocks correct). */
    @Volatile
    var progress: Int = 0,
    /** Whether the puzzle is currently solved. */
    @Volatile
    var solved: Boolean = false,
    /** Number of wrong attempts. */
    @Volatile
    var wrongAttempts: Int = 0,
    /** Timestamp of last interaction. */
    @Volatile
    var lastInteractionTime: Long = 0L,
    /** Timestamp at which the current runtime session started. */
    @Volatile
    var startedAtTime: Long = 0L,
    /** Timestamp of last solve. */
    @Volatile
    var lastSolveTime: Long = 0L,
    /** Timestamp of last reset. */
    @Volatile
    var lastResetTime: Long = 0L,
    /** Cooldown end timestamp. */
    @Volatile
    var cooldownUntil: Long = 0L,
)

/**
 * Timer state for puzzle lifespan management.
 */
data class PuzzleTimerState(
    /** When the puzzle was started (epoch ms). */
    var startTime: Long = 0L,
    /** Lifespan in seconds (0 = unlimited). */
    var lifespanSeconds: Int = 0,
    /** Whether the timer has expired. */
    @Volatile
    var expired: Boolean = false,
) {
    fun isExpired(): Boolean {
        if (lifespanSeconds <= 0) return false
        if (expired) return true
        val elapsed = (System.currentTimeMillis() - startTime) / 1000
        return elapsed >= lifespanSeconds
    }

    fun remainingSeconds(): Int {
        if (lifespanSeconds <= 0) return -1
        val elapsed = (System.currentTimeMillis() - startTime) / 1000
        return (lifespanSeconds - elapsed.toInt()).coerceAtLeast(0)
    }
}


