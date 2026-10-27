package app.gamenative.ui.screen.wow

import app.gamenative.utils.Net
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import okhttp3.Request
import timber.log.Timber

/**
 * Keeps an OctoWoW client in step with OctoWoW's own launcher, which tracks the client as a torrent.
 * The torrent's web seed serves every file over HTTPS, so changed files are fetched directly and checked
 * against the torrent's SHA-1 piece hashes; no BitTorrent client is needed.
 *
 * The torrent last applied is kept in the client folder, so the next check only has to compare piece
 * hashes to know which files changed.
 */
object OctoClientUpdater {
    private const val TORRENT_URL = "https://dl.octowow.st/download/client.torrent"
    private const val STATE_DIR = ".octowow-android"
    private const val BASELINE = "client.torrent"
    private const val BASELINE_DATE = "client.date"
    private const val UNKNOWN_VERSION = "unknown"

    // Archives older OctoWoW clients shipped and later dropped. A stale one would still be loaded, and
    // patch letters override earlier ones, so the launcher deletes them when their size matches exactly.
    private val LEGACY_ARCHIVES = mapOf(
        "patch-6.mpq" to 451195806L,
        "patch-7.mpq" to 175256564L,
        "patch-8.mpq" to 484649870L,
        "patch-9.mpq" to 506808141L,
        "patch-a.mpq" to 241751337L,
    )

    data class TorrentFile(val path: String, val length: Long, val offset: Long)

    class Torrent(
        val name: String,
        val pieceLength: Long,
        val pieces: ByteArray,
        val files: List<TorrentFile>,
        val webSeeds: List<String>,
        val infoHash: String,
    ) {
        val totalLength get() = files.lastOrNull()?.let { it.offset + it.length } ?: 0L

        fun pieceRange(file: TorrentFile): IntRange =
            if (file.length == 0L) IntRange.EMPTY
            else (file.offset / pieceLength).toInt()..((file.offset + file.length - 1) / pieceLength).toInt()

        fun pieceStart(index: Int) = index * pieceLength

        fun pieceSize(index: Int) = minOf(pieceLength, totalLength - pieceStart(index)).toInt()

        fun pieceHash(index: Int): ByteArray = pieces.copyOfRange(index * 20, index * 20 + 20)

        /** BEP 19: a web seed ending in "/" serves a multi-file torrent at <seed><name>/<path>. */
        fun fileUrl(file: TorrentFile): String {
            val seed = webSeeds.first()
            val base = if (seed.endsWith("/")) seed + encode(name) + "/" else "$seed/"
            return base + file.path.split('/').joinToString("/") { encode(it) }
        }

        private fun encode(segment: String) = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }

    // ---- Checking ----

    /** Compares the installed client with OctoWoW's current torrent. Null when the server can't be reached. */
    fun check(gameRoot: File): WowClientDownloader.VersionCheckResult? {
        val (bytes, date) = runCatching { fetchTorrent() }.getOrElse {
            Timber.w(it, "OctoWoW update check failed")
            return null
        }
        val remote = parseTorrent(bytes)
        val baseline = readBaseline(gameRoot)
        val sizesMatch = remote.files.all { File(gameRoot, it.path).let { f -> f.isFile && f.length() == it.length } }
        val outdated = when {
            !sizesMatch -> true
            baseline == null -> {
                // First check with a complete client: take it as current, so later updates only diff hashes.
                saveBaseline(gameRoot, bytes, date)
                false
            }
            else -> baseline.infoHash != remote.infoHash
        }
        val local = if (outdated) baselineDate(gameRoot) ?: UNKNOWN_VERSION else date
        Timber.i("OctoWoW client check: local=$local remote=$date outdated=$outdated")
        return WowClientDownloader.VersionCheckResult(localVersion = local, remoteVersion = date, isOutdated = outdated)
    }

    // ---- Updating ----

    /**
     * Brings the client in line with the current torrent. With [verifyAll], every file is hashed instead of
     * trusting the files the last applied torrent says are unchanged.
     */
    fun update(gameRoot: File, verifyAll: Boolean = false, onStatus: (String) -> Unit) {
        onStatus("Fetching OctoWoW client manifest...")
        val (bytes, date) = fetchTorrent()
        val remote = parseTorrent(bytes)
        check(remote.webSeeds.isNotEmpty()) { "The OctoWoW client torrent lists no download mirror" }
        val baseline = if (verifyAll) null else readBaseline(gameRoot)

        val download = linkedSetOf<TorrentFile>()
        val verify = linkedSetOf<TorrentFile>()
        for (file in remote.files) {
            val local = File(gameRoot, file.path)
            when {
                !local.isFile || local.length() != file.length -> download += file
                baseline != null && unchangedSince(baseline, remote, file) -> Unit
                else -> verify += file
            }
        }
        if (verify.isNotEmpty()) download += verifyLocal(gameRoot, remote, verify, download, onStatus)

        val totalBytes = download.sumOf { it.length }
        var doneBytes = 0L
        download.forEachIndexed { i, file ->
            downloadFile(gameRoot, remote, file) { got ->
                onStatus("Downloading ${file.path} (${i + 1}/${download.size}, ${mb(doneBytes + got)} of ${mb(totalBytes)} MB)...")
            }
            doneBytes += file.length
        }

        if (download.isNotEmpty()) {
            onStatus("Checking downloaded files...")
            val bad = remote.files.filter { it in download }.flatMap { remote.pieceRange(it) }.distinct()
                .filterNot { pieceMatches(gameRoot, remote, it) }
            check(bad.isEmpty()) { "Downloaded files failed verification (pieces ${bad.take(5)}); try again" }
        }

        pruneLegacyArchives(gameRoot, remote)
        saveBaseline(gameRoot, bytes, date)
        onStatus(if (download.isEmpty()) "Game files are up to date" else "Updated ${download.size} game files")
    }

    /** A file is unchanged when it sits at the same offset in both torrents and all its piece hashes match. */
    internal fun unchangedSince(old: Torrent, new: Torrent, file: TorrentFile): Boolean {
        val previous = old.files.firstOrNull { it.path == file.path } ?: return false
        if (previous.length != file.length || previous.offset != file.offset || old.pieceLength != new.pieceLength) return false
        return new.pieceRange(file).all { it < old.pieces.size / 20 && old.pieceHash(it).contentEquals(new.pieceHash(it)) }
    }

    /** Hashes the files in [verify]; returns the ones that don't match. */
    private fun verifyLocal(
        gameRoot: File,
        torrent: Torrent,
        verify: Set<TorrentFile>,
        download: Set<TorrentFile>,
        onStatus: (String) -> Unit,
    ): List<TorrentFile> {
        val failed = linkedSetOf<TorrentFile>()
        val pieces = verify.flatMap { torrent.pieceRange(it) }.distinct().sorted()
        pieces.forEachIndexed { i, piece ->
            if (i % 64 == 0) onStatus("Checking game files (${i * 100 / pieces.size}%)...")
            val touching = filesInPiece(torrent, piece)
            // A piece that shares bytes with a file being re-downloaded anyway can't tell us anything.
            if (touching.any { it in download }) return@forEachIndexed
            if (!pieceMatches(gameRoot, torrent, piece)) failed += touching.filter { it in verify }
        }
        return failed.toList()
    }

    private fun filesInPiece(torrent: Torrent, piece: Int): List<TorrentFile> {
        val start = torrent.pieceStart(piece)
        val end = start + torrent.pieceSize(piece)
        return torrent.files.filter { it.length > 0 && it.offset < end && it.offset + it.length > start }
    }

    internal fun pieceMatches(gameRoot: File, torrent: Torrent, piece: Int): Boolean {
        val start = torrent.pieceStart(piece)
        val size = torrent.pieceSize(piece)
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(64 * 1024)
        for (file in filesInPiece(torrent, piece)) {
            val from = maxOf(start, file.offset) - file.offset
            val to = minOf(start + size, file.offset + file.length) - file.offset
            val local = File(gameRoot, file.path)
            if (!local.isFile || local.length() != file.length) return false
            RandomAccessFile(local, "r").use { raf ->
                raf.seek(from)
                var left = to - from
                while (left > 0) {
                    val n = raf.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                    if (n < 0) return false
                    digest.update(buffer, 0, n)
                    left -= n
                }
            }
        }
        return digest.digest().contentEquals(torrent.pieceHash(piece))
    }

    private fun downloadFile(gameRoot: File, torrent: Torrent, file: TorrentFile, onBytes: (Long) -> Unit) {
        val dest = File(gameRoot, file.path)
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".octodl")
        if (file.length == 0L) {
            part.writeBytes(ByteArray(0))
        } else {
            if (part.length() > file.length) part.delete()
            val request = Request.Builder().url(torrent.fileUrl(file))
                .apply { if (part.length() > 0) header("Range", "bytes=${part.length()}-") }
                .build()
            Net.http.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
                .newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${file.path}")
                    // A server that ignores Range sends the whole file again.
                    val append = response.code == 206
                    if (!append) part.delete()
                    val body = response.body ?: throw IOException("Empty response for ${file.path}")
                    java.io.FileOutputStream(part, append).use { out ->
                        val input = body.byteStream()
                        val buffer = ByteArray(256 * 1024)
                        var written = part.length()
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            written += n
                            if (written - lastReport > 4 * 1024 * 1024) {
                                onBytes(written)
                                lastReport = written
                            }
                        }
                    }
                }
            if (part.length() != file.length) throw IOException("Incomplete download of ${file.path}; try again to resume")
        }
        if (dest.exists() && !dest.delete()) throw IOException("Could not replace ${file.path}")
        if (!part.renameTo(dest)) throw IOException("Could not write ${file.path}")
    }

    private fun pruneLegacyArchives(gameRoot: File, torrent: Torrent) {
        val shipped = torrent.files.map { it.path.lowercase() }.toSet()
        File(gameRoot, "Data").listFiles()?.forEach { archive ->
            val size = LEGACY_ARCHIVES[archive.name.lowercase()] ?: return@forEach
            if ("data/${archive.name.lowercase()}" !in shipped && archive.length() == size) {
                Timber.i("Removing retired OctoWoW archive ${archive.name}")
                archive.delete()
            }
        }
    }

    private fun mb(bytes: Long) = bytes / (1024 * 1024)

    // ---- Torrent and baseline I/O ----

    /** The torrent bytes and its publish date, used as the client "version" shown to the player. */
    private fun fetchTorrent(): Pair<ByteArray, String> =
        Net.http.newCall(Request.Builder().url(TORRENT_URL).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from the OctoWoW update server")
            val bytes = response.body?.bytes() ?: throw IOException("Empty OctoWoW client manifest")
            val date = response.headers.getDate("Last-Modified")?.let(::formatDate) ?: formatDate(Date())
            bytes to date
        }

    private fun formatDate(date: Date) =
        SimpleDateFormat("yyyy.MM.dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)

    private fun stateDir(gameRoot: File) = File(gameRoot, STATE_DIR)

    private fun readBaseline(gameRoot: File): Torrent? =
        File(stateDir(gameRoot), BASELINE).takeIf { it.isFile }?.let { runCatching { parseTorrent(it.readBytes()) }.getOrNull() }

    private fun baselineDate(gameRoot: File): String? =
        File(stateDir(gameRoot), BASELINE_DATE).takeIf { it.isFile }?.readText()?.trim()

    private fun saveBaseline(gameRoot: File, bytes: ByteArray, date: String) {
        runCatching {
            stateDir(gameRoot).mkdirs()
            File(stateDir(gameRoot), BASELINE).writeBytes(bytes)
            File(stateDir(gameRoot), BASELINE_DATE).writeText(date)
        }.onFailure { Timber.w(it, "Could not save the OctoWoW client baseline") }
    }

    // ---- Bencode ----

    internal fun parseTorrent(bytes: ByteArray): Torrent {
        val reader = BencodeReader(bytes)
        @Suppress("UNCHECKED_CAST")
        val root = reader.read() as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val info = root["info"] as? Map<String, Any> ?: throw IOException("Torrent has no info")
        val name = (info["name"] as? ByteArray)?.let { String(it, Charsets.UTF_8) } ?: throw IOException("Torrent has no name")
        val pieceLength = info["piece length"] as? Long ?: throw IOException("Torrent has no piece length")
        val pieces = info["pieces"] as? ByteArray ?: throw IOException("Torrent has no pieces")
        var offset = 0L
        @Suppress("UNCHECKED_CAST")
        val files = (info["files"] as? List<Map<String, Any>>)?.map { entry ->
            val path = (entry["path"] as List<ByteArray>).joinToString("/") { String(it, Charsets.UTF_8) }
            val length = entry["length"] as Long
            TorrentFile(path, length, offset).also { offset += length }
        } ?: listOf(TorrentFile(name, info["length"] as Long, 0L))
        val webSeeds = when (val seeds = root["url-list"]) {
            is ByteArray -> listOf(String(seeds, Charsets.UTF_8))
            is List<*> -> seeds.filterIsInstance<ByteArray>().map { String(it, Charsets.UTF_8) }
            else -> emptyList()
        }
        val infoHash = MessageDigest.getInstance("SHA-1")
            .digest(bytes.copyOfRange(reader.infoStart, reader.infoEnd))
            .joinToString("") { "%02x".format(it) }
        return Torrent(name, pieceLength, pieces, files, webSeeds, infoHash)
    }

    private class BencodeReader(private val bytes: ByteArray) {
        private var pos = 0
        var infoStart = 0
        var infoEnd = 0

        fun read(): Any = when (bytes[pos].toInt().toChar()) {
            'i' -> {
                val end = indexOf('e', pos)
                String(bytes, pos + 1, end - pos - 1).toLong().also { pos = end + 1 }
            }
            'l' -> {
                pos++
                val list = mutableListOf<Any>()
                while (bytes[pos] != 'e'.code.toByte()) list += read()
                pos++
                list
            }
            'd' -> {
                pos++
                val map = LinkedHashMap<String, Any>()
                while (bytes[pos] != 'e'.code.toByte()) {
                    val key = String(read() as ByteArray, Charsets.UTF_8)
                    if (key == "info") infoStart = pos
                    map[key] = read()
                    if (key == "info") infoEnd = pos
                }
                pos++
                map
            }
            else -> {
                val colon = indexOf(':', pos)
                val length = String(bytes, pos, colon - pos).toInt()
                bytes.copyOfRange(colon + 1, colon + 1 + length).also { pos = colon + 1 + length }
            }
        }

        private fun indexOf(c: Char, from: Int): Int {
            for (i in from until bytes.size) if (bytes[i] == c.code.toByte()) return i
            throw IOException("Malformed torrent")
        }
    }
}
