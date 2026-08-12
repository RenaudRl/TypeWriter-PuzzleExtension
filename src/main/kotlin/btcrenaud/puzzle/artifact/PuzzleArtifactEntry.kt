package btcrenaud.puzzle.artifact

import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.engine.paper.entry.entries.ArtifactEntry
import com.typewritermc.engine.paper.entry.entries.stringData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Entry(
    "puzzle_artifact",
    "Persists puzzle states across server restarts",
    "#2ECC71",
    "mdi:database",
)
@Tags("puzzle", "artifact", "storage")
class PuzzleArtifactEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("Generated Typewriter artifact identifier. Keep it stable after publication.")
    override val artifactId: String = UUID.randomUUID().toString(),
) : ArtifactEntry {
    companion object {
        const val CURRENT_SCHEMA = 2
        const val LEGACY_ARTIFACT_ID = "puzzle-states"

        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }
    }

    val usesLegacyIdentity: Boolean
        get() = artifactId == LEGACY_ARTIFACT_ID

    suspend fun loadData(): PuzzleArtifactData {
        val data = stringData() ?: return PuzzleArtifactData()
        if (data.isBlank()) return PuzzleArtifactData()

        return try {
            val decoded = json.decodeFromString<PuzzleArtifactData>(data)
            require(decoded.schemaVersion in 1..CURRENT_SCHEMA) {
                "Unsupported Puzzle artifact schema ${decoded.schemaVersion}"
            }
            decoded.migrateToCurrent()
        } catch (error: Exception) {
            PuzzleArtifactRuntime.corrupt(artifactId)
            PuzzleArtifactRuntime.logger.error(
                "Puzzle artifact $artifactId could not be decoded; refusing to overwrite it",
                error,
            )
            throw IllegalStateException("Puzzle artifact payload is corrupt", error)
        }
    }

    suspend fun saveData(data: PuzzleArtifactData) {
        check(!PuzzleArtifactRuntime.isCorrupt(artifactId)) {
            "Puzzle artifact $artifactId is marked corrupt; recovery is required before saving"
        }
        stringData(json.encodeToString(data.copy(schemaVersion = CURRENT_SCHEMA)))
    }
}

private object PuzzleArtifactRuntime {
    val logger = LoggerFactory.getLogger(PuzzleArtifactEntry::class.java)
    private val corruptArtifacts = java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean>()

    fun corrupt(artifactId: String) {
        corruptArtifacts.computeIfAbsent(artifactId) { AtomicBoolean() }.set(true)
    }

    fun isCorrupt(artifactId: String): Boolean = corruptArtifacts[artifactId]?.get() == true
}

@Serializable
data class PuzzleArtifactData(
    val schemaVersion: Int = 1,
    val states: Map<String, Map<String, SerializedState>> = emptyMap(),
    val stats: Map<String, Map<String, SerializedStats>> = emptyMap(),
) {
    @Serializable
    data class SerializedState(
        val progress: Int = 0,
        val solved: Boolean = false,
        val wrongAttempts: Int = 0,
        val lastInteractionTime: Long = 0L,
        val startedAtTime: Long = 0L,
        val lastSolveTime: Long = 0L,
        val lastResetTime: Long = 0L,
        val cooldownUntil: Long = 0L,
    )

    @Serializable
    data class SerializedStats(
        val attempts: Int = 0,
        val solves: Int = 0,
        val bestTimeMs: Long = 0L,
    )

    fun migrateToCurrent(): PuzzleArtifactData = copy(
        schemaVersion = PuzzleArtifactEntry.CURRENT_SCHEMA,
        states = states.mapNotNull { (playerId, puzzleStates) ->
            if (playerId.isBlank()) return@mapNotNull null
            playerId to puzzleStates.filterKeys(String::isNotBlank)
        }.toMap(),
        stats = stats.mapNotNull { (playerId, puzzleStats) ->
            if (playerId.isBlank()) return@mapNotNull null
            playerId to puzzleStats.filterKeys(String::isNotBlank)
        }.toMap(),
    )
}




