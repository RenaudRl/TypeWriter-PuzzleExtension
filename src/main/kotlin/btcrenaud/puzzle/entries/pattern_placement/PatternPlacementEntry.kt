package btcrenaud.puzzle.entries.pattern_placement

import btcrenaud.puzzle.PuzzleResult
import btcrenaud.puzzle.PuzzleType
import btcrenaud.puzzle.isPuzzleBlockPosition
import btcrenaud.puzzle.objective.BasePuzzleObjectiveEntry
import btcrenaud.puzzle.objective.DEFAULT_PUZZLE_COMPLETION_MESSAGE
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
import org.bukkit.Particle
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Entry(
    "puzzle_pattern",
    "Pattern placement puzzle -",
    "#F1C40F",
    "mdi:grid"
)
@Tags("puzzle", "pattern_placement")
class PatternPlacementEntry(
    override val id: String = "",
    override val name: String = "",

    override val puzzleType: PuzzleType = PuzzleType.PATTERN_PLACEMENT,
    override val fact: Ref<CachableFactEntry> = emptyRef(),
    override val amount: Var<Int> = ConstVar(1),
    override val timeLimit: Var<Int> = ConstVar(0),
    override val cooldownOnFail: Var<Int> = ConstVar(0),
    override val maxAttempts: Var<Int> = ConstVar(0),
    override val resetOnWrong: Var<Boolean> = ConstVar(false),
    override val showHints: Var<Boolean> = ConstVar(true),
    override val hintMessage: Var<String> = ConstVar("<yellow>Place blocks in the correct pattern! {progress}/{total}"),
    override val completionMessage: Var<String> = ConstVar(DEFAULT_PUZZLE_COMPLETION_MESSAGE),
    override val onStart: Ref<TriggerableEntry> = emptyRef(),
    override val onComplete: Ref<TriggerableEntry> = emptyRef(),
    override val onFail: Ref<TriggerableEntry> = emptyRef(),
    override val onTimeout: Ref<TriggerableEntry> = emptyRef(),
    override val onLifespanExpire: Ref<TriggerableEntry> = emptyRef(),
    override val lifespan: Var<Int> = ConstVar(0),
    override val isShared: Var<Boolean> = ConstVar(false),

    @Help("Expected materials for the grid pattern, in row-major order.")
    val pattern: List<PatternSlotDef> = emptyList(),
    @Help("World origin of the grid (the block at row 0, column 0).")
    val gridOrigin: Var<Position> = ConstVar(Position.ORIGIN),
    @Help("Number of columns in the grid pattern.")
    val columns: Var<Int> = ConstVar(3),
    @Help("Vertical offset for the client-side preview. The placement grid itself stays empty.")
    val previewOffsetY: Var<Int> = ConstVar(2),
    @Help("Render transparent client-side ghost blocks directly on the placement grid.")
    val ghostBlocks: Var<Boolean> = ConstVar(false),
    @Help("Tolerance: maximum number of mismatched blocks before reset. Default 0 = exact match only.")
    val tolerance: Var<Int> = ConstVar(0),
    @Help("Sound played on correct block placement.")
    val correctSound: Sound = Sound(soundId = DefaultSoundId("minecraft:block.note_block.chime")),
    @Help("Sound played on wrong block placement.")
    val wrongSound: Sound = Sound(soundId = DefaultSoundId("minecraft:block.note_block.bass")),
    @Help("Sound played when the pattern is fully matched.")
    val solveSound: Sound = Sound(soundId = DefaultSoundId("minecraft:ui.toast.challenge_complete")),

    override val criteria: List<Criteria> = emptyList(),

) : AudienceEntry, BasePuzzleObjectiveEntry {

    override suspend fun display(): AudienceDisplay =
        PatternPlacementDisplay(
            this,
            pattern, gridOrigin, columns, previewOffsetY, ghostBlocks, tolerance,
            correctSound, wrongSound, solveSound,
        )
}

data class PatternSlotDef(
    @Help("Material expected at this grid position.")
    val material: Var<Material> = ConstVar(Material.AIR),
)

class PatternPlacementDisplay(
    private val entry: PatternPlacementEntry,
    private val patternDefs: List<PatternSlotDef>,
    private val gridOrigin: Var<Position>,
    private val columns: Var<Int>,
    private val previewOffsetY: Var<Int>,
    private val ghostBlocks: Var<Boolean>,
    private val tolerance: Var<Int>,
    private val correctSound: Sound,
    private val wrongSound: Sound,
    private val solveSound: Sound,
) : PuzzleObjectiveDisplay<PatternPlacementEntry>(entry, entry.id) {

    private val correctlyPlaced = ConcurrentHashMap<UUID, MutableSet<Int>>()
    private val misplacedCount = ConcurrentHashMap<UUID, Int>()
    private val renderer = PuzzleRenderers.blocks

    override fun onPlayerAdd(player: Player) {
        super.onPlayerAdd(player)
        if (player !in this) return
        correctlyPlaced[player.uniqueId] = mutableSetOf()
        misplacedCount[player.uniqueId] = 0

        val origin = gridOrigin.get(player).toBukkitLocation()
        val cols = columns.get(player)
        if (cols <= 0) return
        patternDefs.forEachIndexed { index, definition ->
            val row = index / cols
            val column = index % cols
            val isGhostMode = ghostBlocks.get(player)
            val location = origin.clone().add(
                column.toDouble(),
                if (isGhostMode) 0.0 else previewOffsetY.get(player).toDouble(),
                row.toDouble(),
            )
            val material = definition.material.get(player)
            PuzzleScheduler.runAtEntity(player) {
                renderer.show(player, location, if (isGhostMode) ghostMaterial(material) else material, puzzleId)
            }
        }
    }

    override fun onPlayerRemove(player: Player) {
        super.onPlayerRemove(player)
        PuzzleScheduler.runAtEntity(player) { renderer.clearOwner(player, puzzleId) }
        correctlyPlaced.remove(player.uniqueId)
        misplacedCount.remove(player.uniqueId)
    }

    private fun getGridIndex(
        blockX: Int, blockY: Int, blockZ: Int,
        originX: Int, originY: Int, originZ: Int,
        cols: Int, rows: Int,
    ): Int? {
        if (blockY != originY) return null
        val col = blockX - originX
        val row = blockZ - originZ
        if (col < 0 || col >= cols) return null
        if (row < 0 || row >= rows) return null
        return row * cols + col
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        if (player !in this) return
        if (player.isDead) return
        val decision = processPlacement(player, event.block.location, event.block.type)
        if (decision == PlacementDecision.REJECTED) {
            event.isCancelled = true
        }
        if (decision == PlacementDecision.ACCEPTED && ghostBlocks.get(player)) {
            event.isCancelled = true
            renderPlacedVisual(player, event.block.location, event.block.type)
            consumeMainHandItem(player, event.block.type)
        }
    }

    private enum class PlacementDecision { OUTSIDE, ACCEPTED, REJECTED }

    private data class PlacementTarget(val index: Int, val expectedMaterial: Material)

    private fun resolveTarget(player: Player, placedLocation: org.bukkit.Location): PlacementTarget? {
        val origin = gridOrigin.get(player).toBukkitLocation()
        if (origin.world?.uid != placedLocation.world?.uid) return null

        val cols = columns.get(player)
        val patternMaterials = patternDefs.map { it.material.get(player) }
        val totalSlots = patternMaterials.size
        if (totalSlots == 0 || cols <= 0 || totalSlots % cols != 0) return null

        val rows = totalSlots / cols
        val gridIndex = getGridIndex(
            placedLocation.blockX,
            placedLocation.blockY,
            placedLocation.blockZ,
            origin.blockX,
            origin.blockY,
            origin.blockZ,
            cols,
            rows,
        ) ?: return null

        return PlacementTarget(gridIndex, patternMaterials[gridIndex])
    }

    private fun processPlacement(
        player: Player,
        placedLocation: org.bukkit.Location,
        placedMaterial: Material,
    ): PlacementDecision {
        val target = resolveTarget(player, placedLocation) ?: return PlacementDecision.OUTSIDE
        val gridIndex = target.index
        val totalSlots = patternDefs.size

        if (service.isOnCooldown(player.uniqueId, puzzleId)) {
            notifyCooldown(player, service, puzzleId)
            return PlacementDecision.REJECTED
        }

        if (hasExceededMaxAttempts(player)) {
            handlePuzzleResult(PuzzleResult.MAX_ATTEMPTS_REACHED, player, entry, service, puzzleId)
            return PlacementDecision.REJECTED
        }

        val placed = correctlyPlaced[player.uniqueId] ?: return PlacementDecision.REJECTED
        val misplaced = misplacedCount[player.uniqueId] ?: return PlacementDecision.REJECTED

        val expectedMaterial = target.expectedMaterial

        if (placed.contains(gridIndex)) return PlacementDecision.REJECTED

        if (placedMaterial == expectedMaterial) {
            placed.add(gridIndex)
            correctSound.play(player, null)
            handlePuzzleResult(PuzzleResult.STEP_CORRECT, player, entry, service, puzzleId)
            notifyProgress(player, "<green>Motif <white>${placed.size}/$totalSlots")

            player.spawnParticle(
                Particle.HAPPY_VILLAGER,
                placedLocation.toCenterLocation(),
                5, 0.3, 0.3, 0.3, 0.0
            )
        } else {
            misplacedCount[player.uniqueId] = misplaced + 1
            val tol = tolerance.get(player)

            if (misplaced + 1 > tol) {
                wrongSound.play(player, null)
                handlePuzzleResult(PuzzleResult.WRONG_RESET, player, entry, service, puzzleId)
                placed.clear()
                misplacedCount[player.uniqueId] = 0

            } else {
                wrongSound.play(player, null)
                handlePuzzleResult(PuzzleResult.WRONG, player, entry, service, puzzleId)
            }
            return PlacementDecision.REJECTED
        }

        if (placed.size >= totalSlots) {
            solveSound.play(player, null)
            handlePuzzleResult(PuzzleResult.COMPLETED, player, entry, service, puzzleId)
            removePlayer(player)
        }
        return PlacementDecision.ACCEPTED
    }

    private fun renderPlacedVisual(player: Player, location: org.bukkit.Location, material: Material) {
        if (player !in this) return
        val blockLocation = location.clone().apply {
            x = blockX.toDouble()
            y = blockY.toDouble()
            z = blockZ.toDouble()
        }
        PuzzleScheduler.runAtEntity(player) {
            renderer.show(player, blockLocation, material, puzzleId)
        }
    }

    private fun consumeMainHandItem(player: Player, expectedMaterial: Material) {
        if (player.gameMode == GameMode.CREATIVE) return
        PuzzleScheduler.runAtEntity(player) {
            val held = player.inventory.itemInMainHand
            if (held.type != expectedMaterial) return@runAtEntity
            if (held.amount <= 1) {
                player.inventory.setItemInMainHand(ItemStack(Material.AIR))
            } else {
                held.amount--
            }
        }
    }

    private fun ghostMaterial(material: Material): Material {
        val baseName = material.name.removeSuffix("_WOOL")
        return Material.matchMaterial("${baseName}_STAINED_GLASS")
            ?: Material.LIGHT_GRAY_STAINED_GLASS
    }
}







