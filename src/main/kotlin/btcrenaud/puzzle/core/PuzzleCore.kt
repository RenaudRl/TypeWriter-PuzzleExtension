package btcrenaud.puzzle.core

/** Scope used by a puzzle session and its visual state. */
enum class PuzzleScope {
    PLAYER,
    SHARED,
    COOPERATIVE,
}

/** Lifecycle of a puzzle session. */
enum class PuzzleSessionStatus {
    NOT_STARTED,
    ACTIVE,
    COMPLETED,
    FAILED,
}

/** Runtime-independent configuration validated before a session can start. */
data class PuzzleDefinition(
    val amount: Int = 1,
    val timeLimitSeconds: Int = 0,
    val cooldownSeconds: Int = 0,
    val maxAttempts: Int = 0,
    val resetOnWrong: Boolean = false,
    val lifespanSeconds: Int = 0,
    val scope: PuzzleScope = PuzzleScope.PLAYER,
) {
    init {
        require(amount > 0) { "Puzzle amount must be greater than zero" }
        require(timeLimitSeconds >= 0) { "Puzzle time limit cannot be negative" }
        require(cooldownSeconds >= 0) { "Puzzle cooldown cannot be negative" }
        require(maxAttempts >= 0) { "Puzzle maximum attempts cannot be negative" }
        require(lifespanSeconds >= 0) { "Puzzle lifespan cannot be negative" }
    }
}

/** Immutable state that can safely cross thread boundaries. */
data class PuzzleSessionState(
    val status: PuzzleSessionStatus = PuzzleSessionStatus.NOT_STARTED,
    val progress: Int = 0,
    val attempts: Int = 0,
    val startedAtMillis: Long = 0L,
    val completedAtMillis: Long = 0L,
    val cooldownUntilMillis: Long = 0L,
) {
    fun isOnCooldown(nowMillis: Long): Boolean = nowMillis < cooldownUntilMillis

    fun isExpired(definition: PuzzleDefinition, nowMillis: Long): Boolean {
        if (status != PuzzleSessionStatus.ACTIVE || definition.timeLimitSeconds <= 0) return false
        return nowMillis - startedAtMillis >= definition.timeLimitSeconds * 1_000L
    }

    fun isLifespanExpired(definition: PuzzleDefinition, nowMillis: Long): Boolean {
        if (status != PuzzleSessionStatus.ACTIVE || definition.lifespanSeconds <= 0) return false
        return nowMillis - startedAtMillis >= definition.lifespanSeconds * 1_000L
    }
}

/**
 * Ticks to wait before replaying a puzzle sequence after a wrong answer.
 *
 * A failed attempt arms the failure cooldown, so an immediate replay is
 * swallowed and leaves the board frozen. The replay must therefore land strictly
 * after the cooldown window.
 */
fun puzzleReplayDelayTicks(remainingCooldownMillis: Long, graceTicks: Long): Long =
    (remainingCooldownMillis.coerceAtLeast(0L) / MILLIS_PER_TICK) + graceTicks.coerceAtLeast(1L)

private const val MILLIS_PER_TICK = 50L

enum class PuzzleTransitionOutcome {
    STARTED,
    STEP_CORRECT,
    COMPLETED,
    WRONG,
    WRONG_RESET,
    TIMEOUT,
    LIFESPAN_EXPIRED,
    MAX_ATTEMPTS_REACHED,
    ALREADY_SOLVED,
    ON_COOLDOWN,
    INVALID,
}

data class PuzzleTransition(
    val state: PuzzleSessionState,
    val outcome: PuzzleTransitionOutcome,
    val accepted: Boolean,
)

/**
 * Pure state reducer for puzzle sessions. It deliberately knows nothing about
 * Bukkit or Typewriter so both the custom and public adapters can consume the
 * same behaviour.
 */
object PuzzleSessionReducer {

    fun start(
        current: PuzzleSessionState,
        definition: PuzzleDefinition,
        nowMillis: Long,
    ): PuzzleTransition {
        if (current.isOnCooldown(nowMillis)) {
            return PuzzleTransition(current, PuzzleTransitionOutcome.ON_COOLDOWN, accepted = false)
        }
        if (current.status == PuzzleSessionStatus.COMPLETED) {
            return PuzzleTransition(current, PuzzleTransitionOutcome.ALREADY_SOLVED, accepted = false)
        }
        if (current.status == PuzzleSessionStatus.ACTIVE) {
            return PuzzleTransition(current, PuzzleTransitionOutcome.STARTED, accepted = false)
        }

        return PuzzleTransition(
            state = PuzzleSessionState(
                status = PuzzleSessionStatus.ACTIVE,
                startedAtMillis = nowMillis,
            ),
            outcome = PuzzleTransitionOutcome.STARTED,
            accepted = true,
        )
    }

    fun apply(
        current: PuzzleSessionState,
        outcome: PuzzleTransitionOutcome,
        definition: PuzzleDefinition,
        nowMillis: Long,
    ): PuzzleTransition {
        if (current.status == PuzzleSessionStatus.COMPLETED) {
            return PuzzleTransition(current, PuzzleTransitionOutcome.ALREADY_SOLVED, accepted = false)
        }
        if (current.status != PuzzleSessionStatus.ACTIVE) {
            return PuzzleTransition(current, PuzzleTransitionOutcome.INVALID, accepted = false)
        }
        if (current.isExpired(definition, nowMillis)) {
            return terminal(current, PuzzleTransitionOutcome.TIMEOUT, definition, nowMillis)
        }
        if (current.isLifespanExpired(definition, nowMillis)) {
            return terminal(current, PuzzleTransitionOutcome.LIFESPAN_EXPIRED, definition, nowMillis)
        }

        return when (outcome) {
            PuzzleTransitionOutcome.STEP_CORRECT -> PuzzleTransition(
                state = current.copy(progress = (current.progress + 1).coerceAtMost(definition.amount)),
                outcome = PuzzleTransitionOutcome.STEP_CORRECT,
                accepted = true,
            )

            PuzzleTransitionOutcome.COMPLETED -> terminal(current, PuzzleTransitionOutcome.COMPLETED, definition, nowMillis)

            PuzzleTransitionOutcome.WRONG,
            PuzzleTransitionOutcome.WRONG_RESET,
            -> {
                val attempts = current.attempts + 1
                if (definition.maxAttempts > 0 && attempts >= definition.maxAttempts) {
                    terminal(
                        current.copy(attempts = attempts),
                        PuzzleTransitionOutcome.MAX_ATTEMPTS_REACHED,
                        definition,
                        nowMillis,
                    )
                } else {
                    PuzzleTransition(
                        state = current.copy(
                            progress = if (definition.resetOnWrong || outcome == PuzzleTransitionOutcome.WRONG_RESET) 0 else current.progress,
                            attempts = attempts,
                        ),
                        outcome = if (definition.resetOnWrong || outcome == PuzzleTransitionOutcome.WRONG_RESET) {
                            PuzzleTransitionOutcome.WRONG_RESET
                        } else {
                            PuzzleTransitionOutcome.WRONG
                        },
                        accepted = true,
                    )
                }
            }

            PuzzleTransitionOutcome.TIMEOUT,
            PuzzleTransitionOutcome.LIFESPAN_EXPIRED,
            -> terminal(current, outcome, definition, nowMillis)

            else -> PuzzleTransition(current, PuzzleTransitionOutcome.INVALID, accepted = false)
        }
    }

    fun reset(nowMillis: Long = 0L): PuzzleSessionState = PuzzleSessionState(
        status = PuzzleSessionStatus.NOT_STARTED,
        startedAtMillis = nowMillis,
    )

    private fun terminal(
        current: PuzzleSessionState,
        outcome: PuzzleTransitionOutcome,
        definition: PuzzleDefinition,
        nowMillis: Long,
    ): PuzzleTransition {
        val completed = outcome == PuzzleTransitionOutcome.COMPLETED
        return PuzzleTransition(
            state = current.copy(
                progress = if (completed) definition.amount else current.progress,
                status = if (completed) PuzzleSessionStatus.COMPLETED else PuzzleSessionStatus.FAILED,
                completedAtMillis = if (completed) nowMillis else current.completedAtMillis,
                cooldownUntilMillis = if (completed || definition.cooldownSeconds <= 0) {
                    current.cooldownUntilMillis
                } else {
                    nowMillis + definition.cooldownSeconds * 1_000L
                },
            ),
            outcome = outcome,
            accepted = true,
        )
    }
}


