package ru.translate.overlay.models

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Хранилище моделей.
 *
 * Модели не кладутся в APK: только один Whisper small — почти 500 МБ, а нужны
 * ещё VAD, MT и, возможно, денойз. Скачиваются при первом использовании в
 * приватный каталог приложения.
 *
 * Источник — релиз моделей этого же репозитория. Файлы там уже распакованные:
 * sherpa-onnx раздаёт модели архивами tar.bz2, а в Android нет ни tar, ни
 * bzip2 из коробки — распаковка вынесена в CI, чтобы не тащить на устройство
 * ещё одну библиотеку.
 */
class ModelStore(context: Context) {

    private val root = File(context.filesDir, "models").apply { mkdirs() }
    private val manifestFile = File(root, "manifest.json")

    /** Описание одного файла модели из манифеста. */
    data class Entry(val key: String, val file: String, val size: Long, val sha256: String)

    class MissingModelException(key: String) :
        IllegalStateException("В манифесте моделей нет ключа «$key»")

    fun localPath(entry: Entry): String = File(root, entry.file).absolutePath

    fun isDownloaded(entry: Entry): Boolean {
        val f = File(root, entry.file)
        return f.isFile && f.length() == entry.size
    }

    /**
     * Читает манифест: сначала из кеша, при отсутствии — по сети.
     * Манифест маленький, поэтому кешируем его навсегда и обновляем только
     * если запрошенного ключа в нём нет.
     */
    private suspend fun manifest(refresh: Boolean = false): Map<String, Entry> =
        withContext(Dispatchers.IO) {
            if (refresh || !manifestFile.isFile) {
                runCatching { download(MANIFEST_URL, manifestFile) }
                    .onFailure { Log.w(TAG, "Манифест моделей не скачался", it) }
            }
            if (!manifestFile.isFile) return@withContext emptyMap()
            parseManifest(manifestFile.readText())
        }

    private fun parseManifest(text: String): Map<String, Entry> {
        val json = JSONObject(text)
        val files = json.optJSONObject("files") ?: return emptyMap()
        val result = LinkedHashMap<String, Entry>()
        for (key in files.keys()) {
            val obj = files.getJSONObject(key)
            result[key] = Entry(
                key = key,
                file = obj.getString("file"),
                size = obj.getLong("size"),
                sha256 = obj.optString("sha256", ""),
            )
        }
        return result
    }

    suspend fun resolve(keys: List<String>): List<Entry> {
        var map = manifest()
        if (keys.any { it !in map }) map = manifest(refresh = true)
        return keys.map { map[it] ?: throw MissingModelException(it) }
    }

    /**
     * Догружает отсутствующие файлы. [onProgress] получает человекочитаемое
     * описание и процент 0..100 по всему набору.
     */
    suspend fun ensure(
        entries: List<Entry>,
        onProgress: (String, Int) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val missing = entries.filterNot { isDownloaded(it) }
        if (missing.isEmpty()) return@withContext
        val totalBytes = missing.sumOf { it.size }.coerceAtLeast(1)
        var done = 0L
        for (entry in missing) {
            val target = File(root, entry.file)
            target.parentFile?.mkdirs()
            val url = DOWNLOAD_BASE + entry.file
            val mb = entry.size / 1_000_000
            onProgress("${entry.file} (${mb} МБ)", (done * 100 / totalBytes).toInt())
            download(url, target) { bytes ->
                onProgress(
                    "${entry.file} (${mb} МБ)",
                    ((done + bytes) * 100 / totalBytes).toInt(),
                )
            }
            if (entry.sha256.isNotEmpty()) {
                val actual = sha256(target)
                if (!actual.equals(entry.sha256, ignoreCase = true)) {
                    target.delete()
                    error("Контрольная сумма ${entry.file} не совпала")
                }
            }
            done += entry.size
        }
        onProgress("Готово", 100)
    }

    /**
     * Распаковывает ZIP в подкаталог и возвращает путь к нему.
     *
     * Нужно для каталога espeak-ng-data, без которого не работают голоса Piper:
     * это несколько сотен мелких файлов, а sherpa-onnx требует путь к каталогу
     * на файловой системе. ZIP выбран потому, что его Android распаковывает
     * штатным java.util.zip, а tar.bz2, в котором модели раздаёт sherpa-onnx, —
     * не умеет.
     */
    suspend fun unpackZip(
        entry: Entry,
        dirName: String,
        onProgress: (String, Int) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        val target = File(root, dirName)
        val marker = File(target, ".unpacked")
        if (marker.isFile) return@withContext target.absolutePath

        val archive = File(root, entry.file)
        require(archive.isFile) { "Архив ${entry.file} не скачан" }

        onProgress("Распаковка $dirName", 0)
        target.deleteRecursively()
        target.mkdirs()

        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val item = zip.nextEntry ?: break
                // Защита от выхода за пределы каталога через «..» в имени.
                val out = File(target, item.name).canonicalFile
                if (!out.path.startsWith(target.canonicalFile.path)) {
                    zip.closeEntry()
                    continue
                }
                if (item.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
            }
        }
        marker.writeText("ok")
        onProgress("Распаковка $dirName", 100)

        // Часть архивов кладёт всё в один корневой каталог — тогда нужный путь
        // на уровень глубже.
        val children = target.listFiles()?.filter { it.name != ".unpacked" } ?: emptyList()
        val single = children.singleOrNull()
        if (single != null && single.isDirectory) {
            single.absolutePath
        } else {
            target.absolutePath
        }
    }

    /** Освобождает место: удаляет все скачанные модели. */
    fun deleteAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
        root.mkdirs()
    }

    fun usedBytes(): Long =
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun download(
        url: String,
        target: File,
        onBytes: (Long) -> Unit = {},
    ) {
        val part = File(target.parentFile, target.name + ".part")
        var current = URL(url)
        var redirects = 0
        while (true) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 120_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "*/*")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location == null || ++redirects > 5) {
                    error("Слишком много перенаправлений при загрузке $url")
                }
                current = URL(current, location)
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                error("HTTP $code при загрузке $url")
            }
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onBytes(written)
                    }
                }
            }
            conn.disconnect()
            break
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) error("Не удалось переименовать ${part.name}")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "ModelStore"

        /** Релиз моделей в этом же репозитории. */
        const val RELEASE_TAG = "models-v1"
        private const val REPO = "lemonmen72-svg/Translate-"
        const val DOWNLOAD_BASE =
            "https://github.com/$REPO/releases/download/$RELEASE_TAG/"
        const val MANIFEST_URL = DOWNLOAD_BASE + "manifest.json"
    }
}

/** Логические ключи файлов в манифесте моделей. */
object ModelKeys {
    const val SILERO_VAD = "silero_vad"
    const val GTCRN = "gtcrn"
    const val PUNCT_MODEL = "punct_model"

    /** Модель эмбеддингов голоса: обучена сразу на китайском и английском. */
    const val SPEAKER_MODEL = "speaker_campplus_zh_en"

    fun whisperEncoder(model: String) = "$model.encoder"
    fun whisperDecoder(model: String) = "$model.decoder"
    fun whisperTokens(model: String) = "$model.tokens"

    fun streamEncoder(model: String) = "$model.encoder"
    fun streamDecoder(model: String) = "$model.decoder"
    fun streamJoiner(model: String) = "$model.joiner"
    fun streamTokens(model: String) = "$model.tokens"

    fun voiceModel(voice: String) = "$voice.model"
    fun voiceTokens(voice: String) = "$voice.tokens"

    const val ESPEAK_DATA = "espeak-ng-data"

    fun mtEncoder(pair: String) = "opus-mt-$pair.encoder"
    fun mtDecoder(pair: String) = "opus-mt-$pair.decoder"
    fun mtSource(pair: String) = "opus-mt-$pair.source_spm"
    fun mtTarget(pair: String) = "opus-mt-$pair.target_vocab"
    fun mtMeta(pair: String) = "opus-mt-$pair.meta"
}
