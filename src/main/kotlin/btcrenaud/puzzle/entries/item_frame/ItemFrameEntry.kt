package btcrenaud.puzzle.entries.item_frame

import btcrenaud.puzzle.PuzzleResult
import btcrenaud.puzzle.PuzzleType
import btcrenaud.puzzle.objective.BasePuzzleObjectiveEntry
import btcrenaud.puzzle.objective.DEFAULT_PUZZLE_ACTIVATION_RADIUS
import btcrenaud.puzzle.objective.DEFAULT_PUZZLE_COMPLETION_MESSAGE
import btcrenaud.puzzle.objective.withoutUnsetAnchors
import btcrenaud.puzzle.objective.PuzzleObjectiveDisplay
import btcrenaud.puzzle.objective.handlePuzzleResult
import btcrenaud.puzzle.objective.notifyCooldown
import btcrenaud.puzzle.objective.notifyProgress
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
import btcrenaud.puzzle.runtime.PuzzleScheduler
import org.bukkit.Material
import org.bukkit.Rotation
import org.bukkit.GameMode
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Entry(
    "puzzle_item_frame",
    "Rotate item frames to the correct angles",
    "#9B59B6",
    "mdi:rotate-3d"
)
@Tags("puzzle", "item_frame")
class ItemFrameEntry(
    override val id: String = "",
    override val name: String = "",

    override val puzzleType: PuzzleType = PuzzleType.ITEM_FRAME,
    override val fact: Ref<CachableFactEntry> = emptyRef(),
    override val amount: Var<Int> = ConstVar(1),
    override val timeLimit: Var<Int> = ConstVar(0),
    override val cooldownOnFail: Var<Int> = ConstVar(0),
    override val maxAttempts: Var<Int> = ConstVar(0),
    override val resetOnWrong: Var<Boolean> = ConstVar(false),
    override val showHints: Var<Boolean> = ConstVar(true),
    override val hintMessage: Var<String> = ConstVar("<yellow>Rotate the item frames to the correct angles! {progress}/{total}"),
    override val completionMessage: Var<String> = ConstVar(DEFAULT_PUZZLE_COMPLETION_MESSAGE),
    override val onStart: Ref<TriggerableEntry> = emptyRef(),
    override val onComplete: Ref<TriggerableEntry> = emptyRef(),
    override val onFail: Ref<TriggerableEntry> = emptyRef(),
    override val onTimeout: Ref<TriggerableEntry> = emptyRef(),
    override val onLifespanExpire: Ref<TriggerableEntry> = emptyRef(),
    override val lifespan: Var<Int> = ConstVar(0),
    override val isShared: Var<Boolean> = ConstVar(false),

    @Help("Detection radius in blocks around the frames. The puzzle only runs for players standing in their world, within this radius. 0 = no distance limit (the world is still enforced).")
    override val activationRadius: Var<Double> = ConstVar(DEFAULT_PUZZLE_ACTIVATION_RADIUS),

    @Help("Frame targets with position, correct rotation (0-7), and optional required item.")
    val frameTargets: List<FrameTargetDef> = emptyList(),
    @Help("If true, the item inside the frame must also match the required item.")
    val checkItem: Var<Boolean> = ConstVar(true),
    @Help("Sound played when a frame is rotated.")
    val rotateSound: Sound = Sound(soundId = DefaultSoundId("minecraft:entity.item_frame.rotate_item")),
    @Help("Sound played when a frame is set to the correct rotation.")
    val correctSound: Sound = Sound(soundId = DefaultSoundId("minecraft:block.note_block.chime")),
    @Help("Sound played on puzzle completion.")
    val solveSound: Sound = Sound(soundId = DefaultSoundId("minecraft:ui.toast.challenge_complete")),

    override val criteria: List<Criteria> = emptyList(),

) : AudienceEntry, BasePuzzleObjectiveEntry {

    override suspend fun display(): AudienceDisplay =
        ItemFrameDisplay(
            this,
            frameTargets, checkItem,
            rotateSound, correctSound, solveSound,
        )
}

data class FrameTargetDef(
    @Help("World position of the item frame.")
    val position: Var<Position> = ConstVar(Position.ORIGIN),
    @Help("Correct rotation index (0-7).")
    val correctRotation: Var<Int> = ConstVar(0),
    @Help("Optional required item inside the frame. AIR = any item accepted.")
    val requiredItem: Var<Material> = ConstVar(Material.AIR),
)

class ItemFrameDisplay(
    private val entry: ItemFrameEntry,
    private val frameTargets: List<FrameTargetDef>,
    private val checkItem: Var<Boolean>,
    private val rotateSound: Sound,
    private val correctSound: Sound,
    private val solveSound: Sound,
) : PuzzleObjectiveDisplay<ItemFrameEntry>(entry, entry.id) {

    private val solvedFrames = ConcurrentHashMap<UUID, MutableMap<Int, Boolean>>()

    /**
     * Frames whose insertion has been scheduled but not yet applied.
     *
     * The item frame is a shared world entity, so the latch is keyed by block
     * position rather than by player.
     */
    private val pendingInserts = ConcurrentHashMap.newKeySet<String>()

    private val logger = LoggerFactory.getLogger(ItemFrameDisplay::class.java)

    override fun puzzleAnchors(player: Player): List<org.bukkit.Location> =
        frameTargets.map { it.position.get(player).toBukkitLocation() }.withoutUnsetAnchors()

    override fun onPuzzleActivate(player: Player) {
        if (player !in this || frameTargets.isEmpty()) return
        val state = mutableMapOf<Int, Boolean>()
        frameTargets.indices.forEach { state[it] = false }
        solvedFrames[player.uniqueId] = state
        reportMissingFrames(player)
    }

    /**
     * The puzzle drives an item frame that already exists in the world; it never
     * spawns one. When the configured position holds no frame the puzzle is
     * simply inert, which is indistinguishable from a broken puzzle, so say so.
     */
    private fun reportMissingFrames(player: Player) {
        frameTargets.forEachIndexed { index, definition ->
            val target = definition.position.get(player).toBukkitLocation()
            PuzzleScheduler.runAtLocation(target) {
                val world = target.world ?: return@runAtLocation
                val present = world
                    .getNearbyEntities(target.toCenterLocation(), 0.6, 0.6, 0.6)
                    .any { it is ItemFrame }
                if (present) return@runAtLocation
                logger.warn(
                    "Puzzle {} target #{} has no item frame at {} {} {} in world {}",
                    puzzleId,
                    index,
                    target.blockX,
                    target.blockY,
                    target.blockZ,
                    world.name,
                )
                PuzzleScheduler.runAtEntity(player) {
                    notifyProgress(
                        player,
                        "<red>Aucun cadre en <white>${target.blockX} ${target.blockY} ${target.blockZ}",
                    )
                }
            }
        }
    }

    override fun onPuzzleDeactivate(player: Player) {
        solvedFrames.remove(player.uniqueId)
    }

    /**
     * The event is consumed at normal priority and the next rotation is applied
     * explicitly when it matches the configured target.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        val player = event.player
        if (player !in this) return
        if (event.hand != EquipmentSlot.HAND) return
        if (player.isDead) return

        val entity = event.rightClicked
        if (entity !is ItemFrame) return

        val frameLocation = entity.location
        val playerFrameState = solvedFrames[player.uniqueId] ?: return

        val frameIndex = frameTargets.indices.firstOrNull { index ->
            val targetPos = frameTargets[index].position.get(player).toBukkitLocation()
            targetPos.blockX == frameLocation.blockX &&
                targetPos.blockY == frameLocation.blockY &&
                targetPos.blockZ == frameLocation.blockZ &&
                targetPos.world?.uid == frameLocation.world?.uid
        } ?: return

        // The cooldown is only evaluated once the frame is known to belong to
        // this puzzle, so unrelated item frames stay fully vanilla.
        if (service.isOnCooldown(player.uniqueId, puzzleId)) {
            event.isCancelled = true
            notifyCooldown(player, service, puzzleId)
            return
        }

        if (hasExceededMaxAttempts(player)) {
            handlePuzzleResult(PuzzleResult.MAX_ATTEMPTS_REACHED, player, entry, service, puzzleId)
            event.isCancelled = true
            return
        }

        // The puzzle fully owns the interaction: vanilla insertion and vanilla
        // rotation are both suppressed so the two states never overlap.
        event.isCancelled = true

        val target = frameTargets[frameIndex]
        val requiredMaterial = target.requiredItem.get(player)
        val itemRequired = checkItem.get(player) && requiredMaterial != Material.AIR
        val frameKey = frameKey(frameLocation)

        if (itemRequired && entity.item.type == Material.AIR) {
            insertRequiredItem(player, entity, frameKey, requiredMaterial)
            return
        }

        // From here the frame holds an item: this is the rotation branch and it
        // must never ask for the item again.
        pendingInserts.remove(frameKey)

        if (itemRequired && entity.item.type != requiredMaterial) {
            notifyProgress(player, "<red>Ce n'est pas le bon objet dans le cadre.")
            handlePuzzleResult(PuzzleResult.WRONG_RESET, player, entry, service, puzzleId)
            return
        }

        rotate(player, entity, frameIndex, playerFrameState, target.correctRotation.get(player).coerceIn(0, 7))
    }

    /** Empty frame: the only accepted action is inserting the required item. */
    private fun insertRequiredItem(
        player: Player,
        entity: ItemFrame,
        frameKey: String,
        requiredMaterial: Material,
    ) {
        if (!pendingInserts.add(frameKey)) {
            // An insertion is already scheduled for this frame. Reading a stale
            // AIR here must not make the puzzle ask for a second item.
            notifyProgress(player, "<gray>Insertion en cours…")
            return
        }

        if (player.inventory.itemInMainHand.type != requiredMaterial) {
            pendingInserts.remove(frameKey)
            notifyProgress(player, "<yellow>Place d'abord l'objet demandé dans le cadre.")
            return
        }

        PuzzleScheduler.runAtEntity(entity) {
            if (entity.isValid && entity.item.type == Material.AIR) {
                entity.setItem(ItemStack(requiredMaterial, 1))
            }
            pendingInserts.remove(frameKey)
        }
        if (player.gameMode != GameMode.CREATIVE) {
            PuzzleScheduler.runAtEntity(player) {
                val held = player.inventory.itemInMainHand
                if (held.type == requiredMaterial) {
                    if (held.amount <= 1) {
                        player.inventory.setItemInMainHand(ItemStack(Material.AIR))
                    } else {
                        held.amount--
                    }
                }
            }
        }
        rotateSound.play(player, null)
        notifyProgress(player, "<green>Objet placé — fais tourner le cadre.")
    }

    /**
     * Filled frame: every click advances the rotation by one step.
     *
     * The previous version only applied the rotation when it already matched the
     * target, which froze the frame on its initial angle and made any target
     * other than `initial + 1` unreachable.
     */
    private fun rotate(
        player: Player,
        entity: ItemFrame,
        frameIndex: Int,
        playerFrameState: MutableMap<Int, Boolean>,
        targetRotation: Int,
    ) {
        val rotations = Rotation.entries
        val nextRotation = (entity.rotation.ordinal + 1) % rotations.size
        rotateSound.play(player, null)
        PuzzleScheduler.runAtEntity(entity) {
            if (entity.isValid) entity.rotation = rotations[nextRotation]
        }

        if (nextRotation != targetRotation) {
            playerFrameState[frameIndex] = false
            notifyProgress(player, "<gray>Rotation ${nextRotation + 1}/${rotations.size}")
            return
        }

        playerFrameState[frameIndex] = true
        correctSound.play(player, null)

        if (playerFrameState.values.all { it }) {
            solveSound.play(player, null)
            handlePuzzleResult(PuzzleResult.COMPLETED, player, entry, service, puzzleId)
            removePlayer(player)
        }
    }

    private fun frameKey(location: org.bukkit.Location): String =
        "${location.world?.uid}:${location.blockX}:${location.blockY}:${location.blockZ}"
}

