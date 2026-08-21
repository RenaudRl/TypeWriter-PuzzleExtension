package btcrenaud.puzzle.entries.lever_order

import btcrenaud.puzzle.PuzzleResult
import btcrenaud.puzzle.PuzzleType
import btcrenaud.puzzle.isPuzzleBlockPosition
import btcrenaud.puzzle.objective.BasePuzzleObjectiveEntry
import btcrenaud.puzzle.objective.DEFAULT_PUZZLE_ACTIVATION_RADIUS
import btcrenaud.puzzle.objective.DEFAULT_PUZZLE_COMPLETION_MESSAGE
import btcrenaud.puzzle.objective.withoutUnsetAnchors
import btcrenaud.puzzle.objective.PuzzleObjectiveDisplay
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
import com.typewritermc.engine.paper.utils.Sound
import com.typewritermc.engine.paper.utils.DefaultSoundId
import com.typewritermc.engine.paper.utils.toBukkitLocation
import org.bukkit.Material
import org.bukkit.block.data.type.Switch
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Entry(
    "puzzle_lever_order",
    "Puzzle where levers must be activated in the correct order",
    "#2ECC71",
    "mdi:order-numeric-ascending"
)
@Tags("puzzle", "lever_order")
class LeverOrderEntry(
    override val id: String = "",
    override val name: String = "",

    override val puzzleType: PuzzleType = PuzzleType.LEVER_ORDER,
    override val fact: Ref<CachableFactEntry> = emptyRef(),
    override val amount: Var<Int> = ConstVar(1),
    override val timeLimit: Var<Int> = ConstVar(0),
    override val cooldownOnFail: Var<Int> = ConstVar(0),
    override val maxAttempts: Var<Int> = ConstVar(0),
    override val resetOnWrong: Var<Boolean> = ConstVar(true),
    override val showHints: Var<Boolean> = ConstVar(true),
    override val hintMessage: Var<String> = ConstVar("<yellow>Flip the levers in the correct order! {progress}/{total}"),
    override val completionMessage: Var<String> = ConstVar(DEFAULT_PUZZLE_COMPLETION_MESSAGE),
    override val onStart: Ref<TriggerableEntry> = emptyRef(),
    override val onComplete: Ref<TriggerableEntry> = emptyRef(),
    override val onFail: Ref<TriggerableEntry> = emptyRef(),
    override val onTimeout: Ref<TriggerableEntry> = emptyRef(),
    override val onLifespanExpire: Ref<TriggerableEntry> = emptyRef(),
    override val lifespan: Var<Int> = ConstVar(0),
    override val isShared: Var<Boolean> = ConstVar(false),

    @Help("Detection radius in blocks around the levers. The board is only rendered for players standing in their world, within this radius. 0 = no distance limit (the world is still enforced).")
    override val activationRadius: Var<Double> = ConstVar(DEFAULT_PUZZLE_ACTIVATION_RADIUS),

    @Help("Levers in this puzzle, each with a position and activation order index.")
    val levers: List<LeverDef> = emptyList(),
    @Help("Sound played on correct lever activation.")
    val correctSound: Sound = Sound(soundId = DefaultSoundId("minecraft:block.note_block.chime")),
    @Help("Sound played on wrong lever activation.")
    val wrongSound: Sound = Sound(soundId = DefaultSoundId("minecraft:block.note_block.bass")),
    @Help("Sound played when puzzle is solved.")
    val solveSound: Sound = Sound(soundId = DefaultSoundId("minecraft:ui.toast.challenge_complete")),

    override val criteria: List<Criteria> = emptyList(),

) : AudienceEntry, BasePuzzleObjectiveEntry {

    override suspend fun display(): AudienceDisplay =
        LeverOrderDisplay(
            this,
            levers, correctSound, wrongSound, solveSound,
        )
}

data class LeverDef(
    @Help("World position of the lever block.")
    val position: Var<Position> = ConstVar(Position.ORIGIN),
    @Help("Activation order index (0 = first, 1 = second, ...).")
    val orderIndex: Var<Int> = ConstVar(0),
)

class LeverOrderDisplay(
    private val entry: LeverOrderEntry,
    private val leverDefs: List<LeverDef>,
    private val correctSound: Sound,
    private val wrongSound: Sound,
    private val solveSound: Sound,
) : PuzzleObjectiveDisplay<LeverOrderEntry>(entry, entry.id) {

    private val nextExpected = ConcurrentHashMap<UUID, Int>()
    private val activatedLevers = ConcurrentHashMap<UUID, MutableSet<String>>()
    private val sortedOrder = ConcurrentHashMap<UUID, List<Pair<Int, LeverDef>>>()
    private val baseLeverStates = ConcurrentHashMap<UUID, MutableMap<String, Switch>>()
    private val renderer = PuzzleRenderers.blocks

    override fun puzzleAnchors(player: Player): List<org.bukkit.Location> =
        leverDefs.map { it.position.get(player).toBukkitLocation() }.withoutUnsetAnchors()

    override fun onPuzzleActivate(player: Player) {
        if (player !in this || leverDefs.isEmpty()) return
        nextExpected[player.uniqueId] = 0
        activatedLevers[player.uniqueId] = mutableSetOf()

        val sorted = leverDefs.mapIndexed { index, def ->
            index to def
        }.sortedBy { (_, def) -> def.orderIndex.get(player) }
        sortedOrder[player.uniqueId] = sorted
        val states = ConcurrentHashMap<String, Switch>()
        baseLeverStates[player.uniqueId] = states

        leverDefs.forEach { definition ->
            val location = definition.position.get(player).toBukkitLocation()
            val state = baseLeverState(location)
            states[leverKey(location)] = state
            PuzzleScheduler.runAtEntity(player) {
                renderer.showData(player, location, state.clone() as Switch, puzzleId)
            }
        }

    }

    override fun onPuzzleDeactivate(player: Player) {
        PuzzleScheduler.runAtEntity(player) { renderer.clearOwner(player, puzzleId) }
        nextExpected.remove(player.uniqueId)
        activatedLevers.remove(player.uniqueId)
        sortedOrder.remove(player.uniqueId)
        baseLeverStates.remove(player.uniqueId)
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (player !in this) return
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (player.isDead) return
        if (service.isOnCooldown(player.uniqueId, puzzleId)) return

        val clickedBlock = event.clickedBlock ?: return

        if (processInteraction(player, clickedBlock.location)) {
            event.isCancelled = true
        }
    }

    private fun processInteraction(player: Player, clickedLocation: org.bukkit.Location): Boolean {
        val sorted = sortedOrder[player.uniqueId] ?: return false
        val leverEntry = sorted.firstOrNull { (_, def) ->
            val pos = def.position.get(player).toBukkitLocation()
            isPuzzleBlockPosition(pos, clickedLocation)
        } ?: return false

        if (service.isOnCooldown(player.uniqueId, puzzleId)) {
            notifyCooldown(player, service, puzzleId)
            return true
        }

        val nextIdx = nextExpected[player.uniqueId] ?: return true
        val activated = activatedLevers[player.uniqueId] ?: return true

        val (leverIndex, leverDef) = leverEntry
        val leverId = "${leverIndex}_${leverDef.orderIndex.get(player)}"

        if (leverId in activated) return true

        if (hasExceededMaxAttempts(player)) {
            handlePuzzleResult(PuzzleResult.MAX_ATTEMPTS_REACHED, player, entry, service, puzzleId)
            return true
        }

        val expectedEntry = sorted.getOrNull(nextIdx)
        val expectedIndex = expectedEntry?.first

        if (leverIndex == expectedIndex) {
            activated.add(leverId)
            setVisualPower(player, clickedLocation, true)
            correctSound.play(player, null)

            val newStep = nextIdx + 1
            if (newStep >= sorted.size) {
                solveSound.play(player, null)
                nextExpected.remove(player.uniqueId)
                handlePuzzleResult(PuzzleResult.COMPLETED, player, entry, service, puzzleId)
                removePlayer(player)
            } else {
                nextExpected[player.uniqueId] = newStep
                handlePuzzleResult(PuzzleResult.STEP_CORRECT, player, entry, service, puzzleId)
            }
        } else {
            wrongSound.play(player, null)
            handlePuzzleResult(PuzzleResult.WRONG_RESET, player, entry, service, puzzleId)

            notifyProgress(player, "<red>Mauvais ordre — les leviers sont réinitialisés.")
            nextExpected[player.uniqueId] = 0
            activated.clear()
            leverDefs.forEach { definition ->
                setVisualPower(player, definition.position.get(player).toBukkitLocation(), false)
            }

        }
        return true
    }

    private fun setVisualPower(player: Player, location: org.bukkit.Location, powered: Boolean) {
        val base = baseLeverStates[player.uniqueId]?.get(leverKey(location)) ?: baseLeverState(location)
        val data = base.clone() as? Switch ?: return
        data.isPowered = powered
        PuzzleScheduler.runAtEntity(player) { renderer.showData(player, location, data, puzzleId) }
    }

    private fun leverKey(location: org.bukkit.Location): String =
        "${location.world?.uid}:${location.blockX}:${location.blockY}:${location.blockZ}"

    /** Preserve the configured/world attachment instead of recreating a wall lever. */
    private fun baseLeverState(location: org.bukkit.Location): Switch {
        val worldData = location.block.blockData
        return if (worldData is Switch && worldData.material == Material.LEVER) {
            worldData.clone() as Switch
        } else {
            Material.LEVER.createBlockData() as Switch
        }
    }
}





