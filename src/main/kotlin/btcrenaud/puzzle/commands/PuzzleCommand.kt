@file:Suppress("UnstableApiUsage")

package btcrenaud.puzzle.commands

import btcrenaud.puzzle.PuzzleService
import btcrenaud.puzzle.chain.PuzzleChainEntry
import btcrenaud.puzzle.chain.PuzzleChainService
import btcrenaud.puzzle.gui.PuzzleMenuEntry
import btcrenaud.puzzle.objective.audienceRef
import com.typewritermc.core.entries.Query
import com.typewritermc.core.extension.annotations.TypewriterCommand
import com.typewritermc.engine.paper.command.dsl.CommandTree
import com.typewritermc.engine.paper.command.dsl.executePlayer
import com.typewritermc.engine.paper.command.dsl.sender
import com.typewritermc.engine.paper.command.dsl.withPermission
import com.typewritermc.engine.paper.command.dsl.word
import com.typewritermc.engine.paper.entry.AudienceManager
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.koin.java.KoinJavaComponent.get

/**
 * Public Typewriter command tree for the Puzzle extension.
 *
 * All user-facing commands intentionally live below `/typewriter puzzle`
 * (and Typewriter's `/tw` alias). This keeps registration, permissions,
 * suggestions and lifecycle ownership inside Typewriter's Brigadier dispatcher.
 */
@TypewriterCommand
fun CommandTree.puzzleCommand() = literal("puzzle") {
    executePlayer { player ->
        val menuEntry = Query.find<PuzzleMenuEntry>().firstOrNull()
        if (menuEntry != null) {
            menuEntry.openForPlayer(player)
        } else {
            player.sendMini("<red>No puzzle menu configured.")
        }
    }

    literal("list") {
        executes {
            val entries = get<PuzzleService>(PuzzleService::class.java)
                .getAllPuzzleEntries()
                .sortedBy { it.id }

            if (entries.isEmpty()) {
                sender.sendMini("<red>No puzzles configured.")
                return@executes
            }

            sender.sendMini("<gold>=== Available Puzzles ===")
            entries.forEach { entry ->
                sender.sendMini("<green>${entry.id} <gray>- ${entry.puzzleType.displayName}")
            }
        }
    }

    literal("start") {
        word("puzzleId") { puzzleId ->
            executePlayer { player -> startPuzzleForPlayer(player, puzzleId()) }
        }
    }

    literal("join") {
        word("puzzleId") { puzzleId ->
            executePlayer { player -> joinPuzzleForPlayer(player, puzzleId()) }
        }
    }

    literal("chain") {
        word("chainId") { chainId ->
            executePlayer { player ->
                val chain = Query.find<PuzzleChainEntry>().firstOrNull {
                    it.id == chainId() || it.name.equals(chainId(), ignoreCase = true)
                }
                if (chain == null) {
                    player.sendMini("<red>Chain not found: <white>${chainId()}")
                } else {
                    startChain(player, chain)
                }
            }
        }
    }

    literal("leaderboard") {
        word("puzzleId") { puzzleId ->
            executePlayer { player -> showLeaderboard(player, puzzleId()) }
        }
    }

    literal("close") {
        executePlayer { player -> player.closeInventory() }
    }

    literal("stop") {
        executePlayer { player -> stopAllPuzzlesForPlayer(player) }
        word("puzzleId") { puzzleId ->
            executePlayer { player -> stopPuzzleForPlayer(player, puzzleId()) }
        }
    }

    literal("status") {
        executePlayer { player -> showPuzzleStatus(player) }
    }

    literal("admin") {
        withPermission("puzzle.admin")

        literal("reset") {
            word("player") {
                word("puzzleId") {
                    executes {
                        val targetName = getArgument("player", String::class)
                        val puzzleId = getArgument("puzzleId", String::class)
                        resetPuzzle(sender, targetName, puzzleId)
                    }
                }
            }
        }

        literal("reload") {
            executes {
                Bukkit.getOnlinePlayers().forEach { player -> detachAllPuzzleAudiences(player) }
                get<PuzzleChainService>(PuzzleChainService::class.java).clearAllChains()
                get<PuzzleService>(PuzzleService::class.java).clearAllRuntimeStates()
                sender.sendMini("<green>Puzzle runtime states cleared.")
            }
        }
    }
}

/** Starts one objective for one player through Typewriter's audience manager. */
fun startPuzzleForPlayer(player: org.bukkit.entity.Player, puzzleId: String): Boolean {
    val service = get<PuzzleService>(PuzzleService::class.java)
    val puzzleEntry = service.getPuzzleEntry(puzzleId)
    if (puzzleEntry == null) {
        player.sendMini("<red>Puzzle not found: <white>$puzzleId")
        return false
    }
    if (service.isSolved(player.uniqueId, puzzleId)) {
        player.sendMini("<yellow>Puzzle already completed. Use <white>/typewriter puzzle admin reset ${player.name} $puzzleId<yellow> to replay it.")
        return false
    }

    get<AudienceManager>(AudienceManager::class.java).addPlayerFor(player, puzzleEntry.audienceRef())
    player.closeInventory()
    player.sendMini("<green>Starting puzzle: <white>${puzzleEntry.puzzleType.displayName}")
    return true
}

/** Adds a player to an already-started shared objective, notably Coop Pressure. */
fun joinPuzzleForPlayer(player: org.bukkit.entity.Player, puzzleId: String): Boolean {
    val service = get<PuzzleService>(PuzzleService::class.java)
    val puzzleEntry = service.getPuzzleEntry(puzzleId)
    if (puzzleEntry == null) {
        player.sendMini("<red>Puzzle not found: <white>$puzzleId")
        return false
    }
    if (service.isSolved(player.uniqueId, puzzleId)) {
        player.sendMini("<yellow>Puzzle already completed for you: <white>$puzzleId")
        return false
    }

    get<AudienceManager>(AudienceManager::class.java).addPlayerFor(player, puzzleEntry.audienceRef())
    player.sendMini("<green>Joined puzzle: <white>${puzzleEntry.puzzleType.displayName}")
    return true
}

/** Stops a single puzzle, resetting only its transient progress and timers. */
fun stopPuzzleForPlayer(player: org.bukkit.entity.Player, puzzleId: String): Boolean {
    val service = get<PuzzleService>(PuzzleService::class.java)
    val puzzleEntry = service.getPuzzleEntry(puzzleId)
    if (puzzleEntry == null) {
        player.sendMini("<red>Puzzle not found: <white>$puzzleId")
        return false
    }

    val active = isPuzzleActive(player, puzzleEntry)
    val state = service.getExistingState(player.uniqueId, puzzleId)
    if (!active && state == null) {
        player.sendMini("<gray>Puzzle is not active: <white>$puzzleId")
        return false
    }

    detachPuzzle(player, puzzleEntry)
    get<PuzzleChainService>(PuzzleChainService::class.java).clearPlayerChains(player.uniqueId)
    player.sendMini("<yellow>Stopped puzzle: <white>$puzzleId")
    return true
}

/** Stops every Puzzle audience owned by a player without touching their statistics. */
fun stopAllPuzzlesForPlayer(player: org.bukkit.entity.Player) {
    val stopped = detachAllPuzzleAudiences(player)
    get<PuzzleChainService>(PuzzleChainService::class.java).clearPlayerChains(player.uniqueId)
    player.sendMini(if (stopped == 0) "<gray>No active puzzle." else "<yellow>Stopped <white>$stopped <yellow>puzzle(s).")
}

private fun showPuzzleStatus(player: org.bukkit.entity.Player) {
    val service = get<PuzzleService>(PuzzleService::class.java)
    player.sendMini("<gold>=== Puzzle Status ===")
    service.getAllPuzzleEntries().sortedBy { it.id }.forEach { entry ->
        val state = service.getExistingState(player.uniqueId, entry.id)
        val active = isPuzzleActive(player, entry)
        val status = when {
            active -> "<green>ACTIVE"
            state?.solved == true -> "<aqua>COMPLETED"
            state != null -> "<yellow>STOPPED"
            else -> "<gray>INACTIVE"
        }
        val progress = state?.progress ?: 0
        player.sendMini("<gray>${entry.id}: $status <dark_gray>progress=$progress")
    }
}

private fun isPuzzleActive(
    player: org.bukkit.entity.Player,
    puzzleEntry: btcrenaud.puzzle.objective.BasePuzzleObjectiveEntry,
): Boolean = get<AudienceManager>(AudienceManager::class.java)[puzzleEntry.audienceRef()]?.contains(player) == true

private fun detachPuzzle(
    player: org.bukkit.entity.Player,
    puzzleEntry: btcrenaud.puzzle.objective.BasePuzzleObjectiveEntry,
): Boolean {
    val service = get<PuzzleService>(PuzzleService::class.java)
    val active = isPuzzleActive(player, puzzleEntry)
    get<AudienceManager>(AudienceManager::class.java).removePlayerFor(player, puzzleEntry.audienceRef())
    if (!service.isSolved(player.uniqueId, puzzleEntry.id)) {
        service.resetProgress(player.uniqueId, puzzleEntry.id)
    }
    return active
}

private fun detachAllPuzzleAudiences(player: org.bukkit.entity.Player): Int {
    val service = get<PuzzleService>(PuzzleService::class.java)
    var stopped = 0
    service.getAllPuzzleEntries().forEach { entry ->
        if (detachPuzzle(player, entry)) stopped++
    }
    return stopped
}

private fun startChain(player: org.bukkit.entity.Player, chain: PuzzleChainEntry) {
    val chainService = get<PuzzleChainService>(PuzzleChainService::class.java)
    if (!chainService.startChain(player, chain)) {
        player.sendMini("<red>Chain is invalid or contains unresolved puzzles: <white>${chain.id}")
        return
    }

    val firstPuzzle = chain.resolvePuzzles().firstOrNull()
    if (firstPuzzle == null) {
        player.sendMini("<red>Chain has no resolvable puzzle entries.")
        return
    }

    get<AudienceManager>(AudienceManager::class.java).addPlayerFor(player, firstPuzzle.audienceRef())
    player.closeInventory()
    player.sendMini("<green>Starting puzzle chain: <white>${chain.name.ifBlank { chain.id }}")
}

private fun showLeaderboard(player: org.bukkit.entity.Player, puzzleId: String) {
    val service = get<PuzzleService>(PuzzleService::class.java)
    if (service.getPuzzleEntry(puzzleId) == null) {
        player.sendMini("<red>Puzzle not found: <white>$puzzleId")
        return
    }

    val stats = service.getStats(player.uniqueId, puzzleId)
    player.sendMini("<gold>=== Puzzle Stats: <white>$puzzleId <gold>===")
    player.sendMini("<gray>Attempts: <white>${stats.attempts}")
    player.sendMini("<gray>Solves: <white>${stats.solves}")
    if (stats.bestTimeMs > 0) {
        player.sendMini("<gray>Best time: <white>${stats.bestTimeMs / 1000.0}s")
    }
    player.sendMini("<gold>--- Leaderboard ---")
    service.leaderboard(puzzleId).forEachIndexed { index, row ->
        val name = Bukkit.getOfflinePlayer(row.playerId).name ?: row.playerId.toString().take(8)
        val best = if (row.bestTimeMs > 0L) " ${row.bestTimeMs / 1000.0}s" else ""
        player.sendMini("<gray>#${index + 1} <white>$name <gray>- <white>${row.solves} solves<gray>$best")
    }
}

private fun resetPuzzle(sender: org.bukkit.command.CommandSender, targetName: String, puzzleId: String) {
    val target = Bukkit.getPlayerExact(targetName)
    if (target == null) {
        sender.sendMini("<red>Player not found: <white>$targetName")
        return
    }

    val service = get<PuzzleService>(PuzzleService::class.java)
    val puzzle = service.getPuzzleEntry(puzzleId)
    if (puzzle == null) {
        sender.sendMini("<red>Puzzle not found: <white>$puzzleId")
        return
    }

    service.resetState(target.uniqueId, puzzleId)
    val manager = get<AudienceManager>(AudienceManager::class.java)
    manager.removePlayerFor(target, puzzle.audienceRef())
    manager.addPlayerFor(target, puzzle.audienceRef())
    sender.sendMini("<green>Reset puzzle <white>$puzzleId <green>for <white>${target.name}")
}

private fun org.bukkit.command.CommandSender.sendMini(message: String) {
    sendMessage(MiniMessage.miniMessage().deserialize(message))
}
