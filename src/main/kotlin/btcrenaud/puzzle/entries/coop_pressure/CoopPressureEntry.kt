package btcrenaud.puzzle.entries.coop_pressure

import btcrenaud.puzzle.PuzzleResult
import btcrenaud.puzzle.PuzzleService
import btcrenaud.puzzle.PuzzleType
import btcrenaud.puzzle.isStandingOnPuzzlePlate
import btcrenaud.puzzle.toPuzzleBlockCoordinate
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
import com.typewritermc.engine.paper.utils.Sound
import com.typewritermc.engine.paper.utils.DefaultSoundId
import com.typewritermc.engine.paper.utils.toBukkitLocation
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Entry(
    "puzzle_coop_pressure",
    "Cooperative pressure plate puzzle -",
    "#2ECC71",
    "mdi:account-group"
)
@Tags("puzzle", "coop_pressure")
class CoopPressureEntry(
    override val id: String = "",
    override val name: String = "",

    override val puzzleType: PuzzleType = PuzzleType.COOP_PRESSURE,
    override val fact: Ref<CachableFactEntry> = emptyRef(),
    override val amount: Var<Int> = ConstVar(1),
    override val timeLimit: Var<Int> = ConstVar(0),
    override val cooldownOnFail: Var<Int> = ConstVar(0),
    override val maxAttempts: Var<Int> = ConstVar(0),
    override val resetOnWrong: Var<Boolean> = ConstVar(true),
    override val showHints: Var<Boolean> = ConstVar(true),
    override val hintMessage: Var<String> = ConstVar("<yellow>Stand on the pressure plates and hold! {progress}/{total}"),
    override val completionMessage: Var<String> = ConstVar(DEFAULT_PUZZLE_COMPLETION_MESSAGE),
    override val onStart: Ref<TriggerableEntry> = emptyRef(),
    override val onComplete: Ref<TriggerableEntry> = emptyRef(),
    override val onFail: Ref<TriggerableEntry> = emptyRef(),
    override val onTimeout: Ref<TriggerableEntry> = emptyRef(),
    override val onLifespanExpire: Ref<TriggerableEntry> = emptyRef(),
    override val lifespan: Var<Int> = ConstVar(0),
    override val isShared: Var<Boolean> = ConstVar(true), // Coop puzzles are shared by default

    @Help("Plate positions where players must stand.")
    val plates: List<Var<Position>> = emptyList(),
    @Help("Number of distinct players required on plates simultaneously.")
    val requiredPlayers: Var<Int> = ConstVar(2),
    @Help("Duration in seconds that plates must remain activated.")
    val holdDuration: Var<Double> = ConstVar(3.0),
    @Help("Sound played when all plates are active.")
    val activateSound: Sound = Sound(soundId = DefaultSoundId("minecraft:block.note_block.pling")),
    @Help("Sound played on puzzle completion.")
    val solveSound: Sound = Sound(soundId = DefaultSoundId("minecraft:ui.toast.challenge_complete")),
    @Help("Sound played when a player leaves early.")
    val failSound: Sound = Sound(soundId = DefaultSoundId("minecraft:block.note_block.bass")),

    override val criteria: List<Criteria> = emptyList(),

) : AudienceEntry, BasePuzzleObjectiveEntry {

    override suspend fun display(): AudienceDisplay =
        CoopPressureDisplay(
            this,
            plates, requiredPlayers, holdDuration,
            activateSound, solveSound, failSound,
        )
}

class CoopPressureDisplay(
    private val entry: CoopPressureEntry,
    private val plates: List<Var<Position>>,
    private val requiredPlayers: Var<Int>,
    private val holdDuration: Var<Double>,
    private val activateSound: Sound,
    private val solveSound: Sound,
    private val failSound: Sound,
) : PuzzleObjectiveDisplay<CoopPressureEntry>(entry, entry.id), TickableDisplay {

    /** Per-player hold start time. Null = condition not met. */
    private val holdStartTimes = ConcurrentHashMap<UUID, Long>()
    private val activateSoundPlayed = ConcurrentHashMap.newKeySet<UUID>()
    private val positionSnapshots = ConcurrentHashMap<UUID, PlayerSnapshot>()

    private data class PlayerSnapshot(val player: Player, val location: Location, val alive: Boolean)

    private var tickCounter = 0

    override fun tick() {
        tickCounter++
        if (tickCounter % 5 != 0) return

        val allPlayers = this.players
        if (allPlayers.isEmpty()) {
            resetAllHolds()
            return
        }

        // Evaluate each player individually with their own context
        for (player in allPlayers) {
            btcrenaud.puzzle.runtime.PuzzleScheduler.runAtEntity(player) {
                if (player !in this) {
                    resetHold(player)
                    return@runAtEntity
                }
                val alive = !player.isDead
                positionSnapshots[player.uniqueId] = PlayerSnapshot(player, player.location.clone(), alive)
                if (!alive) {
                    resetHold(player)
                    return@runAtEntity
                }
                if (service.isOnCooldown(player.uniqueId, puzzleId)) return@runAtEntity
                evaluatePlayer(player, positionSnapshots.values.toList())
            }
        }
    }

    private fun evaluatePlayer(player: Player, allPlayers: Collection<PlayerSnapshot>) {
        val required = requiredPlayers.get(player)
        val platePositions = plates.map { it.get(player).toBukkitLocation() }
        if (required <= 0 || platePositions.isEmpty() || required > platePositions.size) return

        // Assign at most one player to each plate. Counting players that merely
        // share the same plate previously allowed one plate to satisfy the coop
        // requirement several times.
        val assignedPlates = mutableSetOf<Int>()
        val playersOnPlates = allPlayers
            .filter { it.alive }
            .filter { snapshot ->
                val playerLoc = snapshot.location
                val plateIndex = platePositions.indexOfFirst { plateLoc ->
                    isStandingOnPuzzlePlate(playerLoc.toPuzzleBlockCoordinate(), plateLoc.toPuzzleBlockCoordinate())
                }
                plateIndex >= 0 && assignedPlates.add(plateIndex)
            }

        val distinctCount = playersOnPlates.size

        if (distinctCount >= required) {
            val now = System.currentTimeMillis()
            if (!holdStartTimes.containsKey(player.uniqueId)) {
                holdStartTimes[player.uniqueId] = now
                activateSoundPlayed.remove(player.uniqueId)
            }

            if (player.uniqueId !in activateSoundPlayed) {
                activateSound.play(player, null)
                activateSoundPlayed.add(player.uniqueId)
            }

            val holdDurationMs = (holdDuration.get(player).coerceAtLeast(0.0) * 1000).toLong()
            val elapsed = now - holdStartTimes[player.uniqueId]!!

            val completingPlayer = playersOnPlates.firstOrNull()?.player?.uniqueId
            if (elapsed >= holdDurationMs && player.uniqueId == completingPlayer) {
                // Coop completion is shared: every participant receives the
                // per-player fact/trigger, then every participant leaves the
                // audience. Each player is scheduled on its own Folia region.
                playersOnPlates.map(PlayerSnapshot::player).forEach { participant ->
                    btcrenaud.puzzle.runtime.PuzzleScheduler.runAtEntity(participant) {
                        solveSound.play(participant, null)
                        handlePuzzleResult(PuzzleResult.COMPLETED, participant, entry, service, puzzleId)
                        removePlayer(participant)
                    }
                }
                resetAllHolds()
            }
        } else {
            if (holdStartTimes.containsKey(player.uniqueId)) {
                failSound.play(player, null)
                handlePuzzleResult(PuzzleResult.WRONG_RESET, player, entry, service, puzzleId)
                resetHold(player)
            }
        }
    }

    override fun onPlayerRemove(player: Player) {
        super.onPlayerRemove(player)
        positionSnapshots.remove(player.uniqueId)
        resetHold(player)
    }

    private fun resetHold(player: Player) {
        holdStartTimes.remove(player.uniqueId)
        activateSoundPlayed.remove(player.uniqueId)
    }

    private fun resetAllHolds() {
        holdStartTimes.clear()
        activateSoundPlayed.clear()
    }

}





