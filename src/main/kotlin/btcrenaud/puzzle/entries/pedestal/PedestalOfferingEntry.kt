package btcrenaud.puzzle.entries.pedestal

import btcrenaud.puzzle.PuzzleResult
import btcrenaud.puzzle.PuzzleService
import btcrenaud.puzzle.PuzzleType
import btcrenaud.puzzle.objective.BasePuzzleObjectiveEntry
import btcrenaud.puzzle.objective.DEFAULT_PUZZLE_COMPLETION_MESSAGE
import btcrenaud.puzzle.objective.PuzzleObjectiveDisplay
import btcrenaud.puzzle.objective.handlePuzzleResult
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
import com.typewritermc.engine.paper.utils.item.Item
import com.typewritermc.engine.paper.utils.toBukkitLocation
import org.bukkit.entity.Player
import org.bukkit.entity.Item as DroppedItem
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Pedestal Offering puzzle entry.
 *
 * The player must right-click a specific block (the pedestal) while holding
 * a specific item. The puzzle completes when the correct item is offered.
 */
@Entry(
    "puzzle_pedestal",
    "Offer an item on a pedestal to solve the puzzle",
    "#3498DB",
    "mdi:hand"
)
@Tags("puzzle", "pedestal_offering")
class PedestalOfferingEntry(
    override val id: String = "",
    override val name: String = "",

    // === Base Puzzle Objective ===
    override val puzzleType: PuzzleType = PuzzleType.PEDESTAL_OFFERING,
    override val fact: Ref<CachableFactEntry> = emptyRef(),
    override val amount: Var<Int> = ConstVar(1),
    override val timeLimit: Var<Int> = ConstVar(0),
    override val cooldownOnFail: Var<Int> = ConstVar(0),
    override val maxAttempts: Var<Int> = ConstVar(0),
    override val resetOnWrong: Var<Boolean> = ConstVar(false),
    override val showHints: Var<Boolean> = ConstVar(true),
    override val hintMessage: Var<String> = ConstVar("<yellow>Place the correct item on the pedestal!"),
    override val completionMessage: Var<String> = ConstVar(DEFAULT_PUZZLE_COMPLETION_MESSAGE),
    override val onStart: Ref<TriggerableEntry> = emptyRef(),
    override val onComplete: Ref<TriggerableEntry> = emptyRef(),
    override val onFail: Ref<TriggerableEntry> = emptyRef(),
    override val onTimeout: Ref<TriggerableEntry> = emptyRef(),
    override val onLifespanExpire: Ref<TriggerableEntry> = emptyRef(),
    override val lifespan: Var<Int> = ConstVar(0),
    override val isShared: Var<Boolean> = ConstVar(false),

    // === Pedestal Offering Specific ===
    @Help("The block position that serves as the pedestal.")
    val targetPosition: Var<Position> = ConstVar(Position.ORIGIN),

    @Help("The item that must be offered to complete the puzzle.")
    val requiredItem: Var<Item> = ConstVar(Item.Empty),

    @Help("If true, the offered item is consumed from the player's hand on success.")
    val consumeItem: Var<Boolean> = ConstVar(false),

    @Help("Radius around the pedestal that accepts a dropped matching item.")
    val pickupRadius: Var<Double> = ConstVar(1.5),

    @Help("If true, a matching dropped item is removed when it solves the puzzle.")
    val consumeDroppedItem: Var<Boolean> = ConstVar(true),

    @Help("Sound played on puzzle completion.")
    val solveSound: Sound = Sound(soundId = DefaultSoundId("minecraft:ui.toast.challenge_complete")),

    override val criteria: List<Criteria> = emptyList(),

) : AudienceEntry, BasePuzzleObjectiveEntry {

    override suspend fun display(): AudienceDisplay =
        PedestalOfferingDisplay(
            this,
            targetPosition,
            requiredItem,
            consumeItem,
            pickupRadius,
            consumeDroppedItem,
            solveSound,
        )
}

/**
 * Display for the Pedestal Offering puzzle.
 */
class PedestalOfferingDisplay(
    private val entry: PedestalOfferingEntry,
    private val targetPosition: Var<Position>,
    private val requiredItem: Var<Item>,
    private val consumeItem: Var<Boolean>,
    private val pickupRadius: Var<Double>,
    private val consumeDroppedItem: Var<Boolean>,
    private val solveSound: Sound,
) : PuzzleObjectiveDisplay<PedestalOfferingEntry>(entry, entry.id), TickableDisplay {

    private val pendingDroppedOffers = ConcurrentHashMap.newKeySet<UUID>()

    override fun onPlayerRemove(player: Player) {
        pendingDroppedOffers.remove(player.uniqueId)
        super.onPlayerRemove(player)
    }

    override fun tick() {
        players.forEach { player ->
            if (player.isDead || player.uniqueId in pendingDroppedOffers) return@forEach
            val target = targetPosition.get(player).toBukkitLocation()
            val world = target.world ?: return@forEach
            val radius = pickupRadius.get(player).coerceAtLeast(0.25)

            PuzzleScheduler.runAtLocation(target) {
                if (player !in this) return@runAtLocation
                val candidates = world.getNearbyEntities(target.toCenterLocation(), radius, radius, radius)
                    .asSequence()
                    .filterIsInstance<DroppedItem>()
                    .toList()
                if (candidates.isEmpty()) return@runAtLocation

                if (!pendingDroppedOffers.add(player.uniqueId)) return@runAtLocation
                PuzzleScheduler.runAtEntity(player) {
                    try {
                        if (player !in this || player.isDead || service.getState(player.uniqueId, puzzleId).solved) return@runAtEntity
                        val required = requiredItem.get(player)
                        val dropped = candidates.firstOrNull { required.isSameAs(player, it.itemStack) }
                            ?: return@runAtEntity
                        completeOffering(player, dropped)
                    } finally {
                        pendingDroppedOffers.remove(player.uniqueId)
                    }
                }
            }
        }
    }

    // Claim the configured pedestal before RPG Core's enchanting-table menu.
    // The handler still validates the position and item before cancelling, so
    // unrelated block interactions remain untouched.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (player !in this) return
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (player.isDead) return
        if (service.isOnCooldown(player.uniqueId, puzzleId)) return

        val clickedBlock = event.clickedBlock ?: return
        val targetPos = targetPosition.get(player).toBukkitLocation()

        if (clickedBlock.x != targetPos.blockX ||
            clickedBlock.y != targetPos.blockY ||
            clickedBlock.z != targetPos.blockZ ||
            clickedBlock.world.name != targetPos.world?.name
        ) return

        if (service.getState(player.uniqueId, puzzleId).solved) return

        if (hasExceededMaxAttempts(player)) {
            handlePuzzleResult(PuzzleResult.MAX_ATTEMPTS_REACHED, player, entry, service, puzzleId)
            return
        }

        val heldItem = player.inventory.itemInMainHand
        val required = requiredItem.get(player)
        if (!required.isSameAs(player, heldItem)) return

        event.isCancelled = true

        if (consumeItem.get(player)) {
            PuzzleScheduler.runAtEntity(player) {
                val handItem = player.inventory.itemInMainHand
                if (required.isSameAs(player, handItem)) {
                    if (handItem.amount <= 1) {
                        player.inventory.setItemInMainHand(org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR))
                    } else {
                        handItem.amount--
                    }
                }
            }
        }

        completeOffering(player, null)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onEntityPickupItem(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (player !in this || player.isDead) return
        if (!isWithinPickupRadius(player, event.item.location)) return

        val required = requiredItem.get(player)
        if (!required.isSameAs(player, event.item.itemStack)) return

        event.isCancelled = true
        completeOffering(player, event.item)
    }

    private fun isWithinPickupRadius(player: Player, itemLocation: org.bukkit.Location): Boolean {
        val target = targetPosition.get(player).toBukkitLocation()
        if (target.world?.uid != itemLocation.world?.uid) return false
        val radius = pickupRadius.get(player).coerceAtLeast(0.25)
        return target.toCenterLocation().distanceSquared(itemLocation) <= radius * radius
    }

    private fun completeOffering(player: Player, droppedItem: DroppedItem?) {
        if (service.getState(player.uniqueId, puzzleId).solved) return
        if (droppedItem != null && consumeDroppedItem.get(player)) {
            PuzzleScheduler.runAtEntity(droppedItem) {
                if (droppedItem.isValid) droppedItem.remove()
            }
        }
        solveSound.play(player, null)
        handlePuzzleResult(PuzzleResult.COMPLETED, player, entry, service, puzzleId)
        removePlayer(player)
    }

}

