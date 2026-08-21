package btcrenaud.puzzle.entries.combination_lock

import btcrenaud.puzzle.PuzzleResult
import btcrenaud.puzzle.PuzzleType
import btcrenaud.puzzle.objective.BasePuzzleObjectiveEntry
import btcrenaud.puzzle.objective.DEFAULT_PUZZLE_ACTIVATION_RADIUS
import btcrenaud.puzzle.objective.DEFAULT_PUZZLE_COMPLETION_MESSAGE
import btcrenaud.puzzle.objective.withoutUnsetAnchors
import btcrenaud.puzzle.objective.PuzzleObjectiveDisplay
import btcrenaud.puzzle.objective.handlePuzzleResult
import btcrenaud.puzzle.isPuzzleBlockPosition
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.core.utils.point.Position
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.*
import com.typewritermc.engine.paper.utils.Sound
import com.typewritermc.engine.paper.utils.DefaultSoundId
import com.typewritermc.engine.paper.utils.toBukkitLocation
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Entry(
    "puzzle_combination_lock",
    "Enter the correct code by pressing buttons/levers in sequence",
    "#E74C3C",
    "mdi:lock"
)
@Tags("puzzle", "combination_lock")
class CombinationLockEntry(
    override val id: String = "",
    override val name: String = "",

    override val puzzleType: PuzzleType = PuzzleType.COMBINATION_LOCK,
    override val fact: Ref<CachableFactEntry> = emptyRef(),
    override val amount: Var<Int> = ConstVar(1),
    override val timeLimit: Var<Int> = ConstVar(0),
    override val cooldownOnFail: Var<Int> = ConstVar(0),
    override val maxAttempts: Var<Int> = ConstVar(0),
    override val resetOnWrong: Var<Boolean> = ConstVar(true),
    override val showHints: Var<Boolean> = ConstVar(true),
    override val hintMessage: Var<String> = ConstVar("<yellow>Enter the correct code sequence! {progress}/{total}"),
    override val completionMessage: Var<String> = ConstVar(DEFAULT_PUZZLE_COMPLETION_MESSAGE),
    override val onStart: Ref<TriggerableEntry> = emptyRef(),
    override val onComplete: Ref<TriggerableEntry> = emptyRef(),
    override val onFail: Ref<TriggerableEntry> = emptyRef(),
    override val onTimeout: Ref<TriggerableEntry> = emptyRef(),
    override val onLifespanExpire: Ref<TriggerableEntry> = emptyRef(),
    override val lifespan: Var<Int> = ConstVar(0),
    override val isShared: Var<Boolean> = ConstVar(false),
    @Help("Detection radius in blocks. The lock only answers players standing in the world of the digits, within this radius. 0 = no distance limit (the world is still enforced).")
    override val activationRadius: Var<Double> = ConstVar(DEFAULT_PUZZLE_ACTIVATION_RADIUS),

    @Help("Digit button/lever positions. Index 0 maps to digit 0, index 1 to digit 1, etc.")
    val digits: List<Var<Position>> = emptyList(),
    @Help("The unlock code as a list of digit indices. E.g. [0, 2, 1, 3] means press digits at positions 0, 2, 1, 3 in that order.")
    val code: List<Var<Int>> = emptyList(),
    @Help("Sound played when a digit is pressed (correct step).")
    val digitPressSound: Sound = Sound(soundId = DefaultSoundId("minecraft:block.note_block.pling")),
    @Help("Sound played when a wrong digit is pressed.")
    val wrongDigitSound: Sound = Sound(soundId = DefaultSoundId("minecraft:block.note_block.bass")),
    @Help("Sound played on puzzle completion.")
    val solveSound: Sound = Sound(soundId = DefaultSoundId("minecraft:ui.toast.challenge_complete")),

    override val criteria: List<Criteria> = emptyList(),

) : AudienceEntry, BasePuzzleObjectiveEntry {

    override suspend fun display(): AudienceDisplay =
        CombinationLockDisplay(
            this,
            digits, code,
            digitPressSound, wrongDigitSound, solveSound,
        )
}

class CombinationLockDisplay(
    private val entry: CombinationLockEntry,
    private val digits: List<Var<Position>>,
    private val code: List<Var<Int>>,
    private val digitPressSound: Sound,
    private val wrongDigitSound: Sound,
    private val solveSound: Sound,
) : PuzzleObjectiveDisplay<CombinationLockEntry>(entry, entry.id) {

    private val currentInputIndex = ConcurrentHashMap<UUID, Int>()

    override fun puzzleAnchors(player: Player): List<org.bukkit.Location> =
        digits.map { it.get(player).toBukkitLocation() }.withoutUnsetAnchors()

    override fun onPuzzleActivate(player: Player) {
        if (player !in this || digits.isEmpty() || code.isEmpty()) return
        currentInputIndex[player.uniqueId] = 0
    }

    override fun onPuzzleDeactivate(player: Player) {
        currentInputIndex.remove(player.uniqueId)
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (player !in this) return
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_BLOCK) return
        if (player.isDead) return

        val clickedBlock = event.clickedBlock ?: return
        if (processInteraction(player, clickedBlock.location)) event.isCancelled = true
    }

    private fun processInteraction(player: Player, clickedLocation: org.bukkit.Location): Boolean {

        val digitPositions = digits.map { it.get(player).toBukkitLocation() }
        val unlockCode = code.map { it.get(player) }

        if (unlockCode.isEmpty() || unlockCode.any { it !in digitPositions.indices }) return false

        val clickedDigitIndex = digitPositions.indices.firstOrNull { index ->
            val pos = digitPositions[index]
            isPuzzleBlockPosition(pos, clickedLocation)
        } ?: return false

        if (service.isOnCooldown(player.uniqueId, puzzleId)) return true
        if (hasExceededMaxAttempts(player)) {
            handlePuzzleResult(PuzzleResult.MAX_ATTEMPTS_REACHED, player, entry, service, puzzleId)
            return true
        }

        val inputIndex = currentInputIndex[player.uniqueId] ?: 0
        val expectedDigit = unlockCode.getOrNull(inputIndex) ?: return true

        if (clickedDigitIndex == expectedDigit) {
            digitPressSound.play(player, null)

            clickedLocation.world?.spawnParticle(
                Particle.HAPPY_VILLAGER,
                clickedLocation.toCenterLocation(),
                5, 0.3, 0.3, 0.3, 0.0
            )

            val nextIndex = inputIndex + 1
            currentInputIndex[player.uniqueId] = nextIndex

            if (nextIndex >= unlockCode.size) {
                solveSound.play(player, null)
                handlePuzzleResult(PuzzleResult.COMPLETED, player, entry, service, puzzleId)
                removePlayer(player)
            } else {
                // Fire STEP_CORRECT trigger for intermediate correct digits
                handlePuzzleResult(PuzzleResult.STEP_CORRECT, player, entry, service, puzzleId)
            }
        } else {
            wrongDigitSound.play(player, null)

            clickedLocation.world?.spawnParticle(
                Particle.ANGRY_VILLAGER,
                clickedLocation.toCenterLocation(),
                3, 0.3, 0.3, 0.3, 0.0
            )

            currentInputIndex[player.uniqueId] = 0
            handlePuzzleResult(PuzzleResult.WRONG_RESET, player, entry, service, puzzleId)
        }
        return true
    }
}

