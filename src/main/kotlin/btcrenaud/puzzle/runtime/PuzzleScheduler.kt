package btcrenaud.puzzle.runtime

import com.typewritermc.engine.paper.plugin
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity

/**
 * Official Paper/Folia scheduler boundary used by the public build.
 *
 * Every call becomes a no-op once the plugin is disabled. Paper/Folia throws
 * `IllegalPluginAccessException` when a task is registered during shutdown, and
 * that exception used to abort audience disposal halfway through — leaving
 * client-side blocks behind and spamming the console once per display tick.
 */
object PuzzleScheduler {
    interface TaskHandle {
        fun cancel()
    }

    /** True while the engine plugin can still accept scheduled work. */
    val isActive: Boolean
        get() = runCatching { plugin.isEnabled }.getOrDefault(false)

    fun runTask(task: () -> Unit) {
        guarded { Bukkit.getGlobalRegionScheduler().run(plugin) { task() } }
    }

    fun runAtEntity(entity: Entity, task: () -> Unit) {
        guarded { entity.scheduler.run(plugin, { _ -> task() }, null) }
    }

    fun runAtLocation(location: Location, task: () -> Unit) {
        guarded { Bukkit.getRegionScheduler().run(plugin, location) { task() } }
    }

    fun runAtEntityLater(entity: Entity, delayTicks: Long, task: () -> Unit): TaskHandle {
        if (!isActive) return taskHandle {}
        val scheduled = runCatching {
            entity.scheduler.runDelayed(
                plugin,
                { _ -> task() },
                null,
                delayTicks.coerceAtLeast(1L),
            )
        }.getOrNull()
        return scheduled?.let { taskHandle { it.cancel() } } ?: taskHandle {}
    }

    fun runAsyncAtFixedRate(initialDelayTicks: Long, periodTicks: Long, task: () -> Unit): TaskHandle {
        if (!isActive) return taskHandle {}
        val scheduled = runCatching {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin,
                { task() },
                initialDelayTicks.coerceAtLeast(1L),
                periodTicks.coerceAtLeast(1L),
            )
        }.getOrNull() ?: return taskHandle {}
        return taskHandle { scheduled.cancel() }
    }

    private inline fun guarded(schedule: () -> Unit) {
        if (!isActive) return
        runCatching(schedule)
    }

    private fun taskHandle(cancelAction: () -> Unit): TaskHandle = object : TaskHandle {
        override fun cancel() = cancelAction()
    }
}
