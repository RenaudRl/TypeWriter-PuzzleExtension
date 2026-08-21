package btcrenaud.puzzle.entries.block_push

import btcrenaud.puzzle.PuzzleResult
import btcrenaud.puzzle.PuzzleService
import btcrenaud.puzzle.PuzzleType
import btcrenaud.puzzle.isPuzzleBlockPosition
import btcrenaud.puzzle.objective.BasePuzzleObjectiveEntry
import btcrenaud.puzzle.objective.DEFAULT_PUZZLE_ACTIVATION_RADIUS
import btcrenaud.puzzle.objective.DEFAULT_PUZZLE_COMPLETION_MESSAGE
import btcrenaud.puzzle.objective.withoutUnsetAnchors
import btcrenaud.puzzle.objective.PuzzleObjectiveDisplay
import btcrenaud.puzzle.objective.handlePuzzleResult
import btcrenaud.puzzle.objective.notifyCooldown
import btcrenaud.puzzle.render.PuzzleRenderers
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.core.interaction.context
import com.typewritermc.core.utils.point.Position
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.*
import com.typewritermc.engine.paper.entry.triggerFor
import com.typewritermc.engine.paper.utils.Sound
import com.typewritermc.engine.paper.utils.DefaultSoundId
import btcrenaud.puzzle.runtime.PuzzleScheduler
import com.typewritermc.engine.paper.utils.toBukkitLocation
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.Location
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Entry(
    "puzzle_block_push",
    "Push blocks into target holes -",
    "#3498DB",
    "mdi:arrow-expand"
)
@Tags("puzzle", "block_push")
class BlockPushEntry(
    override val id: String = "",
    override val name: String = "",

    // === Base Puzzle Objective ===
    override val puzzleType: PuzzleType = PuzzleType.BLOCK_PUSH,
    override val fact: Ref<CachableFactEntry> = emptyRef(),
    override val amount: Var<Int> = ConstVar(1),
    override val timeLimit: Var<Int> = ConstVar(0),
    override val cooldownOnFail: Var<Int> = ConstVar(0),
    override val maxAttempts: Var<Int> = ConstVar(0),
    override val resetOnWrong: Var<Boolean> = ConstVar(false),
    override val showHints: Var<Boolean> = ConstVar(true),
    override val hintMessage: Var<String> = ConstVar("<yellow>Push blocks into the holes! {progress}/{total}"),
    override val completionMessage: Var<String> = ConstVar(DEFAULT_PUZZLE_COMPLETION_MESSAGE),
    override val onStart: Ref<TriggerableEntry> = emptyRef(),
    override val onComplete: Ref<TriggerableEntry> = emptyRef(),
    override val onFail: Ref<TriggerableEntry> = emptyRef(),
    override val onTimeout: Ref<TriggerableEntry> = emptyRef(),
    override val onLifespanExpire: Ref<TriggerableEntry> = emptyRef(),
    override val lifespan: Var<Int> = ConstVar(0),
    override val isShared: Var<Boolean> = ConstVar(false),
    @Help("Detection radius in blocks. The board is only rendered for players standing in its world, within this radius. 0 = no distance limit (the world is still enforced).")
    override val activationRadius: Var<Double> = ConstVar(DEFAULT_PUZZLE_ACTIVATION_RADIUS),

    // === Block Push Specific ===
    @Help("Pushable blocks with their positions.")
    val pushBlocks: List<PushBlockDef> = emptyList(),
    @Help("Target holes with positions and accepted materials.")
    val targetHoles: List<TargetHoleDef> = emptyList(),
    @Help("If true, blocks can be pushed backward (pulled).")
    val allowPull: Var<Boolean> = ConstVar(false),
    @Help("Sound played when a block is pushed.")
    val pushSound: Sound = Sound(soundId = DefaultSoundId("minecraft:block.stone.step")),
    @Help("Sound played when all blocks are placed.")
    val solveSound: Sound = Sound(soundId = DefaultSoundId("minecraft:ui.toast.challenge_complete")),
    @Help("Material used for the pushable block visual.")
    val blockMaterial: Var<Material> = ConstVar(Material.STONE_BRICKS),
    @Help("Material used for the target hole visual.")
    val holeMaterial: Var<Material> = ConstVar(Material.MOSSY_STONE_BRICKS),

    override val criteria: List<Criteria> = emptyList(),

) : AudienceEntry, BasePuzzleObjectiveEntry {

    override suspend fun display(): AudienceDisplay =
        BlockPushDisplay(
            this,
            pushBlocks, targetHoles, allowPull,
            pushSound, solveSound, blockMaterial, holeMaterial,
        )
}

data class PushBlockDef(
    @Help("Starting position of the pushable block.")
    val position: Var<Position> = ConstVar(Position.ORIGIN),
)

data class TargetHoleDef(
    @Help("Position of the target hole.")
    val position: Var<Position> = ConstVar(Position.ORIGIN),
    @Help("Material accepted by this hole. Use AIR for any block.")
    val acceptedMaterial: Var<Material> = ConstVar(Material.AIR),
)

class BlockPushDisplay(
    private val entry: BlockPushEntry,
    private val pushBlocks: List<PushBlockDef>,
    private val targetHoles: List<TargetHoleDef>,
    private val allowPull: Var<Boolean>,
    private val pushSound: Sound,
    private val solveSound: Sound,
    private val blockMaterial: Var<Material>,
    private val holeMaterial: Var<Material>,
) : PuzzleObjectiveDisplay<BlockPushEntry>(entry, entry.id) {

    private val pushedBlocks = ConcurrentHashMap<UUID, MutableMap<Int, Location>>()
    private val renderer = PuzzleRenderers.blocks

    override fun puzzleAnchors(player: Player): List<Location> =
        (pushBlocks.map { it.position.get(player).toBukkitLocation() } +
            targetHoles.map { it.position.get(player).toBukkitLocation() })
            .withoutUnsetAnchors()

    override fun onPuzzleActivate(player: Player) {
        if (player !in this) return
        placeVisuals(player)
    }

    override fun onPuzzleDeactivate(player: Player) {
        pushedBlocks.remove(player.uniqueId)
        PuzzleScheduler.runAtEntity(player) { renderer.clearOwner(player, puzzleId) }
    }

    private fun placeVisuals(player: Player) {
        val mat = blockMaterial.get(player)
        val holeMat = holeMaterial.get(player)
        val initial = mutableMapOf<Int, Location>()
        pushBlocks.forEachIndexed { index, blockDef ->
            initial[index] = blockDef.position.get(player).toBukkitLocation()
        }
        pushedBlocks[player.uniqueId] = initial

        targetHoles.forEach { holeDef ->
            val location = holeDef.position.get(player).toBukkitLocation()
            PuzzleScheduler.runAtEntity(player) { renderer.show(player, location, holeMat, puzzleId) }
        }
        initial.values.forEach { location ->
            PuzzleScheduler.runAtEntity(player) { renderer.show(player, location, mat, puzzleId) }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (player !in this) return
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.LEFT_CLICK_BLOCK && event.action != Action.RIGHT_CLICK_BLOCK) return
        if (player.isDead) return
        val clickedLocation = event.clickedBlock?.location ?: return
        if (processInteraction(player, clickedLocation, player.facing)) {
            event.isCancelled = true
        }
    }

    private fun processInteraction(player: Player, clickedLocation: Location, blockFace: BlockFace): Boolean {
        val playerPositions = pushedBlocks[player.uniqueId] ?: return false
        val blockIndex = pushBlocks.indices.firstOrNull { index ->
            val loc = playerPositions[index] ?: return@firstOrNull false
            isPuzzleBlockPosition(loc, clickedLocation)
        } ?: return false

        if (service.isOnCooldown(player.uniqueId, puzzleId)) {
            notifyCooldown(player, service, puzzleId)
            return true
        }

        val mat = blockMaterial.get(player)

        if (hasExceededMaxAttempts(player)) {
            handlePuzzleResult(PuzzleResult.MAX_ATTEMPTS_REACHED, player, entry, service, puzzleId)
            return true
        }

        val currentLoc = playerPositions[blockIndex]!!
        // Move along the player's facing direction: this is the direction
        // from the player toward the block and therefore pushes it away.
        val pushDir = blockFace
        val newLoc = currentLoc.clone().add(
            pushDir.modX.toDouble(),
            pushDir.modY.toDouble(),
            pushDir.modZ.toDouble()
        )

        val occupiedByAnother = playerPositions.any { (index, location) ->
            index != blockIndex && location.blockX == newLoc.blockX &&
                location.blockY == newLoc.blockY && location.blockZ == newLoc.blockZ &&
                location.world?.uid == newLoc.world?.uid
        }
        val isTargetHole = targetHoles.any { holeDef ->
            val holeLoc = holeDef.position.get(player).toBukkitLocation()
            holeLoc.blockX == newLoc.blockX &&
                holeLoc.blockY == newLoc.blockY &&
                holeLoc.blockZ == newLoc.blockZ &&
                holeLoc.world?.uid == newLoc.world?.uid
        }

        // The custom renderer owns the visual board. Collision is therefore
        // computed from the puzzle state and never from a fake client block.
        val isValidMove = !occupiedByAnother && (isTargetHole || newLoc.block.type.isAir)

        if (!isValidMove) {
            handleInvalidPush(player, blockIndex)
            return true
        }

        PuzzleScheduler.runAtEntity(player) {
            // Keep the old cell hidden while the puzzle is active. Clearing
            // it immediately revealed a server-side block left in the world.
            renderer.show(player, currentLoc, Material.AIR, puzzleId)
            renderer.show(player, newLoc, mat, puzzleId)
        }
        playerPositions[blockIndex] = newLoc

        pushSound.play(player, null)

        checkCompletion(player)
        return true
    }

    private fun handleInvalidPush(player: Player, blockIndex: Int) {
        handlePuzzleResult(PuzzleResult.WRONG, player, entry, service, puzzleId)
    }

    private fun checkCompletion(player: Player) {
        val playerPositions = pushedBlocks[player.uniqueId] ?: return

        if (targetHoles.isEmpty() || pushBlocks.size != targetHoles.size) return

        val remainingBlocks = pushBlocks.indices.toMutableSet()
        val allInHoles = targetHoles.all { holeDef ->
            val holeLoc = holeDef.position.get(player).toBukkitLocation()
            val match = remainingBlocks.firstOrNull { blockIndex ->
                val blockLoc = playerPositions[blockIndex] ?: return@firstOrNull false
                val accepted = holeDef.acceptedMaterial.get(player)
                val materialMatches = accepted == Material.AIR || blockMaterial.get(player) == accepted
                materialMatches && blockLoc.blockX == holeLoc.blockX &&
                    blockLoc.blockY == holeLoc.blockY &&
                    blockLoc.blockZ == holeLoc.blockZ &&
                    blockLoc.world?.uid == holeLoc.world?.uid
            }
            if (match != null) remainingBlocks.remove(match)
            match != null
        }

        if (allInHoles) {
            solveSound.play(player, null)
            handlePuzzleResult(PuzzleResult.COMPLETED, player, entry, service, puzzleId)

            removePlayer(player)
        }
    }
}






