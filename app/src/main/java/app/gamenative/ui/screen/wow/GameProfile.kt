package app.gamenative.ui.screen.wow

import app.gamenative.BuildConfig
import java.io.File

/**
 * Which standalone PC game this build is for (BuildConfig.GAME_PROFILE), for the parts that differ per
 * game: settings, saves and how the game starts. The WoW flavors have no profile.
 */
object GameProfile {
    const val SKYRIM = "skyrim"
    const val NMS = "nms"

    val isSkyrim get() = BuildConfig.GAME_PROFILE == SKYRIM
    val isNms get() = BuildConfig.GAME_PROFILE == NMS

    /** A standalone x64 PC game rather than a WoW client. */
    val isPlainGame get() = !BuildConfig.BLIZZARD_CLIENT && !BuildConfig.MPQ_CLIENT

    /** The game folder's data directory, used to tell an install apart from an empty folder. */
    val dataDir get() = if (isNms) "GAMEDATA" else "Data"

    /** This game's saves, or null when the launcher doesn't manage them. */
    val saves: SaveStore? get() = when {
        isSkyrim -> SkyrimSaveStore
        isNms -> NmsSaves
        else -> null
    }
}

/** A save as the launcher shows it: on the Continue card and in the Saves list. */
data class SaveSummary(
    val title: String,
    val subtitle: String,
    val detail: String,
    val savedAt: Long,
    /** ARGB pixels of the save's screenshot, if it has one. */
    val shot: IntArray? = null,
    val shotWidth: Int = 0,
    val shotHeight: Int = 0,
)

/** Saves kept in the game folder's Saves/, with backups in SaveBackups/. */
interface SaveStore {
    /** What one backup holds, for the Saves screen. */
    val backupContents: String

    /** How the player picks a save up again, shown after a restore. */
    val loadHint: String

    fun recent(gameRoot: File, limit: Int): List<SaveSummary>
    fun latest(gameRoot: File): SaveSummary? = recent(gameRoot, 1).firstOrNull()
    fun backUp(gameRoot: File, force: Boolean = false): File?
    fun backups(gameRoot: File): List<SkyrimSaves.Backup>
    fun restore(gameRoot: File, backup: File)
}

object SkyrimSaveStore : SaveStore {
    override val backupContents = "your ${SkyrimSaves.SAVES_PER_BACKUP} newest saves"
    override val loadHint = "Load it from the main menu."

    override fun recent(gameRoot: File, limit: Int) = SkyrimSaves.list(gameRoot, limit).map { save ->
        SaveSummary(
            title = "${save.name} · Level ${save.level}",
            subtitle = save.location,
            detail = "${save.kind} · ${save.gameDate}",
            savedAt = save.savedAt,
            shot = save.argb(),
            shotWidth = save.shotWidth,
            shotHeight = save.shotHeight,
        )
    }

    override fun backUp(gameRoot: File, force: Boolean) = SkyrimSaves.backUp(gameRoot, force)
    override fun backups(gameRoot: File) = SkyrimSaves.backups(gameRoot)
    override fun restore(gameRoot: File, backup: File) = SkyrimSaves.restore(gameRoot, backup)
}
