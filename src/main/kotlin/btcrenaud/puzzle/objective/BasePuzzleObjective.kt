package btcrenaud.puzzle.objective

import btcrenaud.puzzle.PuzzleResult
import btcrenaud.puzzle.PuzzleService
import btcrenaud.puzzle.PuzzleType
import btcrenaud.puzzle.chain.PuzzleChainService
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.interaction.context
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.AudienceEntry
import com.typewritermc.engine.paper.entry.entries.CachableFactEntry
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.entry.triggerFor
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import org.koin.java.KoinJavaComponent.get
import kotlin.math.ceil

/**
 * Base interface for all puzzle objective entries.
 */
interface BasePuzzleObjectiveEntry {
    val id: String
    val puzzleType: PuzzleType
    val fact: Ref<CachableFactEntry>
    val amount: Var<Int>
    val timeLimit: Var<Int>
    val cooldownOnFail: Var<Int>
    val maxAttempts: Var<Int>
    val resetOnWrong: Var<Boolean>
    val showHints: Var<Boolean>
    val hintMessage: Var<String>

    /**
     * Message sent to the player once the puzzle transitions to COMPLETED.
     *
     * Blank disables the message. It is only emitted for a transition that
     * [PuzzleService.applyResult] actually accepted, so a duplicated event or a
     * re-entered interaction never sends it twice.
     */
    val completionMessage: Var<String>

    val onStart: Ref<TriggerableEntry>
    val onComplete: Ref<TriggerableEntry>
    val onFail: Ref<TriggerableEntry>
    val onTimeout: Ref<TriggerableEntry>
    val onLifespanExpire: Ref<TriggerableEntry>
    val lifespan: Var<Int>
    val isShared: Var<Boolean>

    /**
     * Detection radius, in blocks, around the puzzle positions.
     *
     * The puzzle only renders and runs for a player standing in the world of its
     * own positions **and** within this radius of at least one of them. `0`
     * disables the distance check only — the world check always applies, since a
     * client-side board carries coordinates but no dimension.
     */
    val activationRadius: Var<Double>

    val criteria: List<Criteria>
}

/** Default detection radius applied to every puzzle entry. */
const val DEFAULT_PUZZLE_ACTIVATION_RADIUS: Double = 16.0

/** Default used by every entry so existing pages keep working without edits. */
const val DEFAULT_PUZZLE_COMPLETION_MESSAGE: String = "<green>Puzzle complété !"

/** Builds a runtime reference without requiring a concrete objective class at the call site. */
fun BasePuzzleObjectiveEntry.audienceRef(): Ref<AudienceEntry> =
    Ref(id, AudienceEntry::class, this as AudienceEntry)

/**
 * Handles common puzzle attempt flow: validation -
 */
fun handlePuzzleResult(
    result: PuzzleResult,
    player: Player,
    entry: BasePuzzleObjectiveEntry,
    service: PuzzleService,
    puzzleId: String,
) {
    val accepted = service.applyResult(
        playerId = player.uniqueId,
        puzzleId = puzzleId,
        result = result,
        resetOnWrong = entry.resetOnWrong.get(player),
        cooldownSeconds = entry.cooldownOnFail.get(player),
        progressLimit = entry.amount.get(player),
    )
    if (!accepted) return

    val chainService = get<PuzzleChainService>(PuzzleChainService::class.java)

    when (result) {
        PuzzleResult.COMPLETED -> {
            val factEntry = entry.fact.get()
            if (factEntry != null) {
                val current = factEntry.readForPlayersGroup(player).value
                val amount = entry.amount.get(player).coerceAtLeast(1)
                factEntry.write(player, current + amount)
            }

            sendCompletionMessage(player, entry)
            entry.onComplete.triggerFor(player, context())
            chainService.onPuzzleCompleted(player, puzzleId)
        }

        PuzzleResult.STEP_CORRECT -> {
            // State mutation and attempt accounting are centralized in PuzzleService.
            sendHintIfEnabled(player, entry, service, puzzleId)
        }

        PuzzleResult.WRONG -> {
            // State mutation and attempt accounting are centralized in PuzzleService.
            sendHintIfEnabled(player, entry, service, puzzleId)
        }

        PuzzleResult.WRONG_RESET -> {
            entry.onFail.triggerFor(player, context())
            chainService.onPuzzleFailed(player, puzzleId)
            sendHintIfEnabled(player, entry, service, puzzleId)
        }

        PuzzleResult.TIMEOUT -> {
            entry.onTimeout.triggerFor(player, context())
            chainService.onPuzzleFailed(player, puzzleId)
        }

        PuzzleResult.MAX_ATTEMPTS_REACHED -> {
            // This branch used to be silent, which is how a puzzle out of
            // attempts looked identical to a broken one: every interaction
            // re-armed the cooldown and the player only saw a wait message.
            player.sendMessage(
                MiniMessage.miniMessage().deserialize(
                    "<red>Trop d'erreurs — relance le puzzle pour réessayer.",
                ),
            )
            entry.onFail.triggerFor(player, context())
            chainService.onPuzzleFailed(player, puzzleId)
        }

        PuzzleResult.LIFESPAN_EXPIRED -> {
            entry.onLifespanExpire.triggerFor(player, context())
            chainService.onPuzzleFailed(player, puzzleId)
        }

        else -> {}
    }
}

/**
 * Tells the player why an interaction was swallowed.
 *
 * Every puzzle silently ignores interactions during the cooldown that follows a
 * wrong answer. Without this feedback the board looks broken.
 */
fun notifyCooldown(
    player: Player,
    service: PuzzleService,
    puzzleId: String,
) {
    val remaining = service.remainingCooldownMillis(player.uniqueId, puzzleId)
    if (remaining <= 0L) return
    val seconds = ceil(remaining / 1000.0).toInt().coerceAtLeast(1)
    player.sendActionBar(
        MiniMessage.miniMessage().deserialize("<red>Puzzle en attente — <white>${seconds}s"),
    )
}

/** Sends an already-formatted MiniMessage line on the action bar. */
fun notifyProgress(player: Player, message: String) {
    if (message.isBlank()) return
    player.sendActionBar(MiniMessage.miniMessage().deserialize(message))
}

private fun sendCompletionMessage(player: Player, entry: BasePuzzleObjectiveEntry) {
    val message = entry.completionMessage.get(player)
    if (message.isBlank()) return
    player.sendMessage(MiniMessage.miniMessage().deserialize(message))
}

private fun sendHintIfEnabled(
    player: Player,
    entry: BasePuzzleObjectiveEntry,
    service: PuzzleService,
    puzzleId: String,
) {
    if (!entry.showHints.get(player)) return
    val state = service.getState(player.uniqueId, puzzleId)
    val message = entry.hintMessage.get(player)
        .replace("{progress}", state.progress.toString())
        .replace("{total}", entry.amount.get(player).coerceAtLeast(1).toString())
    player.sendMessage(MiniMessage.miniMessage().deserialize(message))
}




