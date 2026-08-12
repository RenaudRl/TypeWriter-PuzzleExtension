package btcrenaud.puzzle.entries.laser_grid

import btcrenaud.puzzle.PuzzleResult
import btcrenaud.puzzle.PuzzleType
import btcrenaud.puzzle.objective.BasePuzzleObjectiveEntry
import btcrenaud.puzzle.objective.DEFAULT_PUZZLE_COMPLETION_MESSAGE
import btcrenaud.puzzle.objective.PuzzleObjectiveDisplay
import btcrenaud.puzzle.objective.handlePuzzleResult
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
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Laser Grid puzzle -
 *
 * Uses particle beams for visualization. Mirrors redirect beams at 90-degree
 * angles based on which face is hit. Receivers activate when hit by their
 * required color. Puzzle solved when all receivers are active.
 *
 * This is a UNIFIED entry -
 * directly here instead of as separate @Entry classes.
 */

data class LaserEmitterDef(
    @Help("World position of the emitter.")
    val position: Var<Position> = ConstVar(Position.ORIGIN),
    @Help("Beam color.")
    val color: Var<BeamColor> = ConstVar(BeamColor.RED),
    @Help("Initial beam direction.")
    val direction: Var<BlockFace> = ConstVar(BlockFace.NORTH),
)

data class LaserMirrorDef(
    @Help("World position of the mirror.")
    val position: Var<Position> = ConstVar(Position.ORIGIN),
    @Help("Can players rotate this mirror by right-clicking?")
    val rotatable: Var<Boolean> = ConstVar(true),
    @Help("Initial horizontal mirror direction. The test board uses this to require several rotations.")
    val initialDirection: Var<BlockFace> = ConstVar(BlockFace.NORTH),
    @Help("Material used for mirror visual.")
    val mirrorMaterial: Var<Material> = ConstVar(Material.WHITE_STAINED_GLASS),
)

data class LaserReceiverDef(
    @Help("World position of the receiver.")
    val position: Var<Position> = ConstVar(Position.ORIGIN),
    @Help("Required beam color.")
    val requiredColor: Var<BeamColor> = ConstVar(BeamColor.RED),
)

enum class BeamColor(val displayName: String, val red: Float, val green: Float, val blue: Float) {
    RED("Red", 1f, 0f, 0f),
    GREEN("Green", 0f, 1f, 0f),
    BLUE("Blue", 0f, 0f, 1f),
    YELLOW("Yellow", 1f, 1f, 0f),
    PURPLE("Purple", 1f, 0f, 1f),
    CYAN("Cyan", 0f, 1f, 1f),
    WHITE("White", 1f, 1f, 1f),
}

@Entry(
    "puzzle_laser_grid",
    "Laser grid puzzle -",
    "#E67E22",
    "mdi:laser"
)
@Tags("puzzle", "laser_grid")
class LaserGridEntry(
    override val id: String = "",
    override val name: String = "",

    override val puzzleType: PuzzleType = PuzzleType.LASER_GRID,
    override val fact: Ref<CachableFactEntry> = emptyRef(),
    override val amount: Var<Int> = ConstVar(1),
    override val timeLimit: Var<Int> = ConstVar(0),
    override val cooldownOnFail: Var<Int> = ConstVar(0),
    override val maxAttempts: Var<Int> = ConstVar(0),
    override val resetOnWrong: Var<Boolean> = ConstVar(true),
    override val showHints: Var<Boolean> = ConstVar(true),
    override val hintMessage: Var<String> = ConstVar("<yellow>Redirect the beams to the receivers!"),
    override val completionMessage: Var<String> = ConstVar(DEFAULT_PUZZLE_COMPLETION_MESSAGE),
    override val onStart: Ref<TriggerableEntry> = emptyRef(),
    override val onComplete: Ref<TriggerableEntry> = emptyRef(),
    override val onFail: Ref<TriggerableEntry> = emptyRef(),
    override val onTimeout: Ref<TriggerableEntry> = emptyRef(),
    override val onLifespanExpire: Ref<TriggerableEntry> = emptyRef(),
    override val lifespan: Var<Int> = ConstVar(0),
    override val isShared: Var<Boolean> = ConstVar(false),

    @Help("Laser emitters in this puzzle.")
    val emitters: List<LaserEmitterDef> = emptyList(),
    @Help("Laser mirrors in this puzzle.")
    val mirrors: List<LaserMirrorDef> = emptyList(),
    @Help("Laser receivers in this puzzle.")
    val receivers: List<LaserReceiverDef> = emptyList(),
    @Help("Maximum beam reflections before stopping.")
    val maxReflections: Var<Int> = ConstVar(16),
    @Help("Beam particle density per block.")
    val beamDensity: Var<Double> = ConstVar(0.5),
    @Help("Sound on solve.")
    val solveSound: Sound = Sound(soundId = DefaultSoundId("minecraft:ui.toast.challenge_complete")),

    override val criteria: List<Criteria> = emptyList(),
) : AudienceEntry, BasePuzzleObjectiveEntry {

    override suspend fun display(): AudienceDisplay =
        LaserGridDisplay(this, emitters, mirrors, receivers, maxReflections, beamDensity, solveSound)
}

class LaserGridDisplay(
    private val entry: LaserGridEntry,
    private val emitterDefs: List<LaserEmitterDef>,
    private val mirrorDefs: List<LaserMirrorDef>,
    private val receiverDefs: List<LaserReceiverDef>,
    private val maxReflections: Var<Int>,
    private val beamDensity: Var<Double>,
    private val solveSound: Sound,
) : PuzzleObjectiveDisplay<LaserGridEntry>(entry, entry.id), TickableDisplay {

    private var tickCounter = 0
    private val mirrorDirections = ConcurrentHashMap<UUID, ConcurrentHashMap<String, BlockFace>>()
    private val renderer = PuzzleRenderers.blocks

    override fun onPlayerAdd(player: Player) {
        super.onPlayerAdd(player)
        if (player !in this) return
        val directions = ConcurrentHashMap<String, BlockFace>()
        mirrorDirections[player.uniqueId] = directions
        mirrorDefs.forEach { mirror ->
            val location = mirror.position.get(player).toBukkitLocation()
            directions[mirrorId(mirror, player)] = normalizeDirection(mirror.initialDirection.get(player))
            PuzzleScheduler.runAtEntity(player) {
                renderer.show(player, location, mirror.mirrorMaterial.get(player), puzzleId)
            }
        }
    }

    override fun onPlayerRemove(player: Player) {
        mirrorDirections.remove(player.uniqueId)
        PuzzleScheduler.runAtEntity(player) { renderer.clearOwner(player, puzzleId) }
        super.onPlayerRemove(player)
    }

    override fun tick() {
        tickCounter++
        if (tickCounter % 5 != 0) return
        val players = this.players
        if (players.isEmpty()) return

        players.forEach { player ->
            PuzzleScheduler.runAtEntity(player) {
                if (player.isDead || player !in this) return@runAtEntity
                traceAndCheck(player)
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (player !in this) return
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (player.isDead) return

        val clickedBlock = event.clickedBlock ?: return
        if (!rotateMirror(player, clickedBlock.location, event.blockFace)) return
        event.isCancelled = true
    }

    private fun rotateMirror(
        player: Player,
        clickedLocation: org.bukkit.Location,
        clickedFace: BlockFace? = null,
    ): Boolean {

        val mirror = mirrorDefs.firstOrNull { m ->
            val pos = m.position.get(player).toBukkitLocation()
            pos.blockX == clickedLocation.blockX && pos.blockY == clickedLocation.blockY &&
                pos.blockZ == clickedLocation.blockZ && pos.world?.uid == clickedLocation.world?.uid
        } ?: return false

        if (!mirror.rotatable.get(player)) return false

        val mirrorId = mirrorId(mirror, player)
        val directions = mirrorDirections.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }
        val currentDir = directions[mirrorId] ?: BlockFace.NORTH
        val faceDirection = clickedFace?.oppositeFace?.takeIf { isHorizontal(it) }
        val newDir = faceDirection ?: when (currentDir) {
            BlockFace.NORTH -> BlockFace.EAST
            BlockFace.EAST -> BlockFace.SOUTH
            BlockFace.SOUTH -> BlockFace.WEST
            BlockFace.WEST -> BlockFace.NORTH
            else -> BlockFace.NORTH
        }
        directions[mirrorId] = newDir

        return true
    }

    private fun mirrorId(mirror: LaserMirrorDef, player: Player): String {
        val location = mirror.position.get(player).toBukkitLocation()
        return "${location.world?.uid}:${location.blockX}:${location.blockY}:${location.blockZ}"
    }

    private fun normalizeDirection(direction: BlockFace): BlockFace = when (direction) {
        BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST -> direction
        else -> BlockFace.NORTH
    }

    private fun isHorizontal(face: BlockFace): Boolean = when (face) {
        BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST -> true
        else -> false
    }

    private fun traceAndCheck(player: Player) {
        val maxRefl = maxReflections.get(player)
        if (receiverDefs.isEmpty() || emitterDefs.isEmpty()) return
        val receiverStates = mutableMapOf<Int, Boolean>()
        receiverDefs.indices.forEach { receiverStates[it] = false }

        for (emitter in emitterDefs) {
            val startPos = emitter.position.get(player).toBukkitLocation()
            val dir = emitter.direction.get(player)
            val color = emitter.color.get(player)
            val initPos = startPos.clone().add(dir.modX.toDouble(), dir.modY.toDouble(), dir.modZ.toDouble())

            traceRay(initPos, dir, color, maxRefl, receiverStates, player)
        }

        val allActive = receiverDefs.indices.all { receiverStates[it] == true }
        if (allActive && !service.getState(player.uniqueId, puzzleId).solved) {
            solveSound.play(player, null)
            handlePuzzleResult(PuzzleResult.COMPLETED, player, entry, service, puzzleId)
            removePlayer(player)
        }
    }

    private fun traceRay(
        startPos: org.bukkit.Location,
        startDir: BlockFace,
        color: BeamColor,
        maxReflections: Int,
        receiverStates: MutableMap<Int, Boolean>,
        player: Player,
    ) {
        var pos = startPos.clone()
        var dir = startDir
        var reflectionsLeft = maxReflections
        val density = beamDensity.get(player)
        var lastSpawn = 0.0

        while (reflectionsLeft >= 0) {
            if (pos.y < -64 || pos.y > 320) break

            if (density > 0 && pos.distanceSquared(startPos) - lastSpawn > (1.0 / density)) {
                player.spawnParticle(
                    Particle.DUST,
                    pos.clone().add(0.5, 0.5, 0.5),
                    1,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    Particle.DustOptions(
                        Color.fromRGB(
                            (color.red * 255).toInt(),
                            (color.green * 255).toInt(),
                            (color.blue * 255).toInt(),
                        ),
                        0.8f,
                    ),
                )
                lastSpawn = pos.distanceSquared(startPos)
            }

            // Check receivers
            for (i in receiverDefs.indices) {
                val receiver = receiverDefs[i]
                val recPos = receiver.position.get(player).toBukkitLocation()
                if (pos.world?.uid == recPos.world?.uid &&
                    pos.blockX == recPos.blockX && pos.blockY == recPos.blockY && pos.blockZ == recPos.blockZ
                ) {
                    if (receiver.requiredColor.get(player) == color) {
                        receiverStates[i] = true
                    }
                    return
                }
            }

            // Check mirrors
            val hitMirror = mirrorDefs.firstOrNull { mirror ->
                val mirrorPos = mirror.position.get(player).toBukkitLocation()
                mirrorPos.world?.uid == pos.world?.uid &&
                    mirrorPos.blockX == pos.blockX && mirrorPos.blockY == pos.blockY && mirrorPos.blockZ == pos.blockZ
            }

            if (hitMirror != null && reflectionsLeft > 0) {
                val mirrorId = mirrorId(hitMirror, player)
                val mirrorDir = mirrorDirections[player.uniqueId]?.get(mirrorId) ?: BlockFace.NORTH
                dir = mirrorDir
                reflectionsLeft--
                pos.add(dir.modX.toDouble(), dir.modY.toDouble(), dir.modZ.toDouble())
                continue
            }

            val block = pos.block
            if (!block.isPassable && hitMirror == null) break

            pos.add(dir.modX.toDouble(), dir.modY.toDouble(), dir.modZ.toDouble())
        }
    }

}






