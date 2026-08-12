package btcrenaud.puzzle.gui

import btcrenaud.puzzle.PuzzleService
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ActionEntry
import com.typewritermc.engine.paper.entry.entries.ActionTrigger
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import org.koin.java.KoinJavaComponent.get

/**
 * Public-build menu action. It intentionally uses chat components instead of
 * the BTC GUI editor, keeping the public artifact free of private GUI APIs.
 */
@Entry("puzzle_menu", "Puzzle Menu", "#00BCD4", "mdi:puzzle")
@Tags("puzzle", "action")
class PuzzleMenuEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Puzzle entry IDs shown in the chat menu.")
    val puzzleEntryIds: List<String> = emptyList(),
) : ActionEntry {

    fun openForPlayer(player: Player) {
        val service = get<PuzzleService>(PuzzleService::class.java)
        val mm = MiniMessage.miniMessage()
        player.sendMessage(mm.deserialize("<gold><bold>Puzzles</bold> <gray>-"))
        puzzleEntryIds.mapNotNull(service::getPuzzleEntry).forEach { entry ->
            player.sendMessage(
                mm.deserialize("<green>${entry.id} <gray>- <aqua>click to start")
                    .clickEvent(ClickEvent.runCommand("/typewriter puzzle start ${entry.id}"))
            )
        }
        player.sendMessage(mm.deserialize("<gray>Use <white>/typewriter puzzle stop<gray> to stop all active puzzles."))
    }

    override fun ActionTrigger.execute() = openForPlayer(player)
}



