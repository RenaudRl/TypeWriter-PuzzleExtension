package btcrenaud.puzzle.entries.memory_sequence

import btcrenaud.puzzle.PuzzleResult
import btcrenaud.puzzle.PuzzleType
import btcrenaud.puzzle.isPuzzleBlockPosition
import btcrenaud.puzzle.objective.BasePuzzleObjectiveEntry
import btcrenaud.puzzle.objective.DEFAULT_PUZZLE_COMPLETION_MESSAGE
import btcrenaud.puzzle.objective.PuzzleObjectiveDisplay
import btcrenaud.puzzle.core.puzzleReplayDelayTicks
import btcrenaud.puzzle.objective.handlePuzzleResult
import btcrenaud.puzzle.objective.notifyCooldown
import btcrenaud.puzzle.objective.notifyProgress
import btcrenaud.puzzle.render.PuzzleRenderers
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.core.utils.point.Position
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.*
import btcrenaud.puzzle.runtime.PuzzleScheduler
import com.typewritermc.engine.paper.utils.DefaultSoundId
import com.typewritermc.engine.paper.utils.Sound
import com.typewritermc.engine.paper.utils.toBukkitLocation
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

@Entry(
    "puzzle_memory",
    "Memory sequence puzzle -",
    "#9B59B6",
    "mdi:brain"
)
@Tags("puzzle", "memory_sequence")
class MemorySequenceEntry(
    override val id: String = "",
    override val name: String = "",

    override val puzzleType: PuzzleType = PuzzleType.MEMORY_SEQUENCE,
    override val fact: Ref<CachableFactEntry> = emptyRef(),
    override val amount: Var<Int> = ConstVar(1),
    override val timeLimit: Var<Int> = ConstVar(0),
    override val cooldownOnFail: Var<Int> = ConstVar(0),
    override val maxAttempts: Var<Int> = ConstVar(0),
    override val resetOnWrong: Var<Boolean> = ConstVar(true),
    override val showHints: Var<Boolean> = ConstVar(true),
    override val hintMessage: Var<String> = ConstVar("<yellow>Repeat the sequence! {progress}/{total}"),
    override val completionMessage: Var<String> = ConstVar(DEFAULT_PUZZLE_COMPLETION_MESSAGE),
    override val onStart: Ref<TriggerableEntry> = emptyRef(),
    override val onComplete: Ref<TriggerableEntry> = emptyRef(),
    override val onFail: Ref<TriggerableEntry> = emptyRef(),
    override val onTimeout: Ref<TriggerableEntry> = emptyRef(),
    override val onLifespanExpire: Ref<TriggerableEntry> = emptyRef(),
    override val lifespan: Var<Int> = ConstVar(0),
    override val isShared: Var<Boolean> = ConstVar(false),

    @Help("Buttons the player can click, with positions, materials, and sounds.")
    val buttons: List<MemoryButtonDef> = emptyList(),
    @Help("Number of steps in the sequence.")
    val sequenceLength: Var<Int> = ConstVar(4),
    @Help("Time each step is highlighted (ticks).")
    val highlightDuration: Var<Long> = ConstVar(20L),
    @Help("Delay between steps (ticks).")
    val stepGap: Var<Long> = ConstVar(10L),
    @Help("Delay before player can input after sequence display (ticks).")
    val inputDelay: Var<Long> = ConstVar(20L),
    @Help("Material used to highlight a button during sequence display.")
    val highlightMaterial: Var<Material> = ConstVar(Material.GLOWSTONE),
    @Help("Sound played when the puzzle is solved.")
    val solveSound: Sound = Sound(soundId = DefaultSoundId("minecraft:ui.toast.challenge_complete")),

    override val criteria: List<Criteria> = emptyList(),

) : AudienceEntry, BasePuzzleObjectiveEntry {

    override suspend fun display(): AudienceDisplay =
        MemorySequenceDisplay(
            this,
            buttons, sequenceLength, highlightDuration, stepGap,
            inputDelay, highlightMaterial, solveSound,
        )
}

data class MemoryButtonDef(
    @Help("World position of the button.")
    val position: Var<Position> = ConstVar(Position.ORIGIN),
    @Help("Material to display for this button.")
    val material: Var<Material> = ConstVar(Material.STONE_BUTTON),
    @Help("Sound played when this button is clicked.")
    val clickSound: Sound = Sound(soundId = DefaultSoundId("minecraft:block.note_block.harp")),
)

class MemorySequenceDisplay(
    private val entry: MemorySequenceEntry,
    private val buttonDefs: List<MemoryButtonDef>,
    private val sequenceLength: Var<Int>,
    private val highlightDuration: Var<Long>,
    private val stepGap: Var<Long>,
    private val inputDelay: Var<Long>,
    private val highlightMaterial: Var<Material>,
    private val solveSound: Sound,
) : PuzzleObjectiveDisplay<MemorySequenceEntry>(entry, entry.id) {

    private val sequences = ConcurrentHashMap<UUID, List<Int>>()
    private val playerProgress = ConcurrentHashMap<UUID, Int>()
    private val canInput = ConcurrentHashMap<UUID, Boolean>()
    private val scheduledTasks = ConcurrentHashMap<UUID, MutableSet<PuzzleScheduler.TaskHandle>>()
    private val renderer = PuzzleRenderers.blocks

    override fun onPlayerAdd(player: Player) {
        super.onPlayerAdd(player)
        if (player !in this) return
        val buttonCount = buttonDefs.size
        if (buttonCount < 2) return

        val length = sequenceLength.get(player).coerceAtLeast(1)
        val seq = List(length) { Random.nextInt(buttonCount) }
        sequences[player.uniqueId] = seq

        renderButtons(player)
        startRound(player, seq)
    }

    /** Draws every button in its idle state for [player]. */
    private fun renderButtons(player: Player) {
        buttonDefs.forEach { def ->
            val loc = def.position.get(player).toBukkitLocation()
            PuzzleScheduler.runAtEntity(player) {
                renderer.show(player, loc, def.material.get(player), puzzleId)
            }
        }
    }

    /**
     * Restarts a full round: pending animation tasks are dropped first so a
     * replay can never run concurrently with the sequence it replaces.
     */
    private fun startRound(player: Player, sequence: List<Int>) {
        cancelTasks(player)
        playerProgress[player.uniqueId] = 0
        canInput[player.uniqueId] = false
        renderButtons(player)
        displaySequence(player, sequence)
    }

    private fun cancelTasks(player: Player) {
        scheduledTasks.remove(player.uniqueId)?.forEach(PuzzleScheduler.TaskHandle::cancel)
    }

    override fun onPlayerRemove(player: Player) {
        scheduledTasks.remove(player.uniqueId)?.forEach(PuzzleScheduler.TaskHandle::cancel)
        PuzzleScheduler.runAtEntity(player) { renderer.clearOwner(player, puzzleId) }
        super.onPlayerRemove(player)
        sequences.remove(player.uniqueId)
        playerProgress.remove(player.uniqueId)
        canInput.remove(player.uniqueId)
    }

    private fun schedule(player: Player, delayTicks: Long, action: () -> Unit) {
        lateinit var handle: PuzzleScheduler.TaskHandle
        handle = PuzzleScheduler.runAtEntityLater(player, delayTicks.coerceAtLeast(1L)) {
            scheduledTasks[player.uniqueId]?.remove(handle)
            if (player.isDead || player !in this) return@runAtEntityLater
            action()
        }
        scheduledTasks.computeIfAbsent(player.uniqueId) { ConcurrentHashMap.newKeySet() }.add(handle)
    }

    private fun displaySequence(player: Player, sequence: List<Int>) {
        if (player.isDead) return

        notifyProgress(player, "<gold>Mémorise la séquence…")

        val highlight = highlightMaterial.get(player)
        val highlightTicks = highlightDuration.get(player).coerceAtLeast(1L)
        val gapTicks = stepGap.get(player).coerceAtLeast(0L)
        val inputDelayTicks = inputDelay.get(player).coerceAtLeast(0L)

        sequence.forEachIndexed { stepIndex, buttonIndex ->
            val delayStart = stepIndex * (highlightTicks + gapTicks)
            val delayEnd = delayStart + highlightTicks

            schedule(player, delayStart + 1) {
                highlightButton(player, buttonIndex, highlight)
                val sound = buttonDefs.getOrNull(buttonIndex)?.clickSound
                sound?.play(player, null)
            }

            schedule(player, delayEnd + 1) {
                restoreButton(player, buttonIndex)
            }
        }

        val totalDelay = sequence.size * (highlightTicks + gapTicks) + inputDelayTicks
        schedule(player, totalDelay + 1) {
            canInput[player.uniqueId] = true
            notifyProgress(player, "<green>À toi ! <white>0/${sequence.size}")
        }
    }

    private fun highlightButton(player: Player, buttonIndex: Int, material: Material) {
        val def = buttonDefs.getOrNull(buttonIndex) ?: return
        val loc = def.position.get(player).toBukkitLocation()
        PuzzleScheduler.runAtEntity(player) {
            renderer.show(player, loc, material, puzzleId)
        }
    }

    private fun restoreButton(player: Player, buttonIndex: Int) {
        val def = buttonDefs.getOrNull(buttonIndex) ?: return
        val loc = def.position.get(player).toBukkitLocation()
        PuzzleScheduler.runAtEntity(player) {
            // Restore the configured button instead of clearing the client
            // side block after its first highlight.
            renderer.show(player, loc, def.material.get(player), puzzleId)
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (player !in this) return
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_BLOCK) return
        if (player.isDead) return
        val clickedLocation = event.clickedBlock?.location ?: return
        if (processInteraction(player, clickedLocation)) {
            event.isCancelled = true
        }
    }

    private fun processInteraction(player: Player, clickedLocation: org.bukkit.Location): Boolean {
        val clickedIndex = buttonDefs.indices.firstOrNull { index ->
            val pos = buttonDefs[index].position.get(player).toBukkitLocation()
            isPuzzleBlockPosition(pos, clickedLocation)
        } ?: return false

        if (service.isOnCooldown(player.uniqueId, puzzleId)) {
            notifyCooldown(player, service, puzzleId)
            return true
        }
        if (canInput[player.uniqueId] != true) {
            notifyProgress(player, "<gold>Mémorise la séquence…")
            return true
        }

        val sequence = sequences[player.uniqueId] ?: return true
        val currentStep = playerProgress[player.uniqueId] ?: return true

        if (hasExceededMaxAttempts(player)) {
            handlePuzzleResult(PuzzleResult.MAX_ATTEMPTS_REACHED, player, entry, service, puzzleId)
            return true
        }

        buttonDefs.getOrNull(clickedIndex)?.clickSound?.play(player, null)

        val expectedIndex = sequence[currentStep]
        if (clickedIndex == expectedIndex) {
            val newStep = currentStep + 1
            if (newStep >= sequence.size) {
                solveSound.play(player, null)
                playerProgress.remove(player.uniqueId)
                canInput[player.uniqueId] = false
                handlePuzzleResult(PuzzleResult.COMPLETED, player, entry, service, puzzleId)
                removePlayer(player)
            } else {
                playerProgress[player.uniqueId] = newStep
                handlePuzzleResult(PuzzleResult.STEP_CORRECT, player, entry, service, puzzleId)
                notifyProgress(player, "<green>Bien joué ! <white>$newStep/${sequence.size}")
            }
        } else {
            handlePuzzleResult(PuzzleResult.WRONG_RESET, player, entry, service, puzzleId)
            playerProgress[player.uniqueId] = 0
            canInput[player.uniqueId] = false
            scheduleReplay(player, sequence)
        }
        return true
    }

    /**
     * Replays the sequence once the failure cooldown has elapsed.
     *
     * The previous implementation replayed immediately and was silently skipped
     * because [handlePuzzleResult] had just armed the cooldown: the board then
     * stayed frozen with `canInput = false` and no usable control until the
     * player left the audience.
     */
    private fun scheduleReplay(player: Player, sequence: List<Int>) {
        if (hasExceededMaxAttempts(player)) return
        val delayTicks = puzzleReplayDelayTicks(
            service.remainingCooldownMillis(player.uniqueId, puzzleId),
            REPLAY_GRACE_TICKS,
        )
        cancelTasks(player)
        notifyCooldown(player, service, puzzleId)
        schedule(player, delayTicks) {
            if (service.getState(player.uniqueId, puzzleId).solved) return@schedule
            startRound(player, sequence)
        }
    }

    private companion object {
        /** Small margin so the replay never lands inside the cooldown window. */
        const val REPLAY_GRACE_TICKS = 10L
    }
}








