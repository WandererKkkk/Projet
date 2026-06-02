package cn.a10miaomiao.bilidown.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import cn.a10miaomiao.bilidown.common.datastore.DataStoreKeys
import cn.a10miaomiao.bilidown.common.datastore.dataStore
import cn.a10miaomiao.bilidown.common.file.MiaoDocumentFile
import cn.a10miaomiao.bilidown.common.file.MiaoFile
import cn.a10miaomiao.bilidown.common.file.MiaoJavaFile
import cn.a10miaomiao.bilidown.entity.BiliDownloadEntryAndPathInfo
import cn.a10miaomiao.bilidown.entity.BiliDownloadEntryInfo
import cn.a10miaomiao.bilidown.shizuku.util.RemoteServiceUtil
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.jvm.Throws


class BiliDownFile(
    val context: Context,
    val packageName: String,
    val enabledShizuku: Boolean,
) {

    private val TAG = "BiliDownFile"
    private val externalStorage = Environment.getExternalStorageDirectory()
    private val DIR_DOWNLOAD = "download"
    var path = ""
    var list = emptyList<String>()

    suspend fun canRead(): Boolean {
        if (enabledShizuku) {
            return true
        }
        if (packageName == "com.mmstudyonline.mmso") {
            val manualUri = getSelectedMMSOFolderUri()
            if (!manualUri.isNullOrBlank()) {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(manualUri))
                if (root != null && root.exists() && root.isDirectory && root.canRead()) {
                    return true
                }
            }
        }
        val downloadDir = createMiaoFile(DIR_DOWNLOAD)
        return downloadDir.canRead()
    }

    suspend fun getSelectedMMSOFolderUri(): String? {
        return context.dataStore.data.first()[DataStoreKeys.mmsoFolderUri]
    }

    @Throws(TimeoutCancellationException::class)
    suspend fun readDownloadList(): List<BiliDownloadEntryAndPathInfo> {
        // Special handling for MMSO
        if (packageName == "com.mmstudyonline.mmso") {
            return readMMSOVideoList()
        }
        
        val downloadDir = createMiaoFile(DIR_DOWNLOAD)
        val list = mutableListOf<BiliDownloadEntryAndPathInfo>()
        MiaoLog.debug { enabledShizuku.toString() }
        if (enabledShizuku) {
            MiaoLog.debug { downloadDir.path }
            val userService = RemoteServiceUtil.getUserService()
            list.addAll(userService.readDownloadList(downloadDir.path))
        } else {
            downloadDir.listFiles()
                .filter { it.isDirectory }
                .forEach {
                    Log.d(TAG, it.path)
                    list.addAll(readDownloadDirectory(it))
                }
        }
        return list.reversed()
    }

    suspend fun readDownloadDirectory(dir: MiaoFile): List<BiliDownloadEntryAndPathInfo> {
        if (enabledShizuku) {
            val userService = RemoteServiceUtil.getUserService()
            return userService.readDownloadDirectory(dir.path)
        }
        if (!dir.exists() || !dir.isDirectory) {
            return emptyList()
        }
        return dir.listFiles()
            .filter { pageDir -> pageDir.isDirectory }
            .map {
                val entryFile = if (it is MiaoDocumentFile) {
                    MiaoDocumentFile(context, it.documentFile, "/entry.json")
                } else {
                    MiaoJavaFile(it.path + "/entry.json")
                }
                Pair(it, entryFile)
            }
            .filter { it.second.exists() }
            .map {
                val (entryDir, entryFile) = it
                val entryJson = entryFile.readText()
                val json = Json { ignoreUnknownKeys = true }
                val entry = json.decodeFromString<BiliDownloadEntryInfo>(entryJson)
                BiliDownloadEntryAndPathInfo(
                    entry = entry,
                    entryDirPath = entryDir.path,
                    pageDirPath = dir.path
//                    entryDirPath = it.parent,
//                    pageDirPath = it.parentFile.parent
                )
            }
    }

    //获取指定目录的权限
    fun startFor(REQUEST_CODE_FOR_DIR: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageName == "com.mmstudyonline.mmso") {
                MiaoDocumentFile.requestFolderPermission(
                    context as Activity,
                    REQUEST_CODE_FOR_DIR,
                    null
                )
            } else {
                MiaoDocumentFile.requestFolderPermission(
                    context as Activity,
                    REQUEST_CODE_FOR_DIR,
                    getDocumentFileId()
                )
            }
        }
    }

    private fun createMiaoFile(
        dirName: String,
    ): MiaoFile {
        if (!enabledShizuku && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {  // 10以上
            return MiaoDocumentFile(
                context,
                getDocumentFileId(),
                File.separator + dirName
            )
        }
        var file = File(getExternalDir(), dirName)
        if (!enabledShizuku && !file.exists()) {
            file.mkdir()
        }
        return MiaoJavaFile(file)
    }

    private fun getExternalDir(): String {
        var externalStorage = Environment.getExternalStorageDirectory()
        var path = externalStorage.absolutePath + "/Android/data/" + packageName
        return path
    }

    private fun getDocumentFileId(): String {
        var path = "primary:Android/data/$packageName"
        return path
    }

    /**
     * Read MMSO video files from custom storage path
     */
    private suspend fun readMMSOVideoList(): List<BiliDownloadEntryAndPathInfo> {
        val manualUri = getSelectedMMSOFolderUri()
        if (!manualUri.isNullOrBlank()) {
            val docRoot = DocumentFile.fromTreeUri(context, Uri.parse(manualUri))
            if (docRoot != null && docRoot.exists() && docRoot.isDirectory) {
                val metadata = readMMSODBMetadata(docRoot)
                return readMMSOVideoListFromDocumentFolder(docRoot, metadata)
            }
        }

        val list = mutableListOf<BiliDownloadEntryAndPathInfo>()
        val externalStorage = Environment.getExternalStorageDirectory()

        val possiblePaths = listOf(
            "/storage/1768-3511/data/files/playable_cache",
            externalStorage.absolutePath + "/Android/data/com.mmstudyonline.mmso/files/playable_cache",
            externalStorage.absolutePath + "/Android/data/com.mmstudyonline.mmso/cache/playable_cache"
        )

        for (cachePath in possiblePaths) {
            val cacheDir = File(cachePath)
            if (cacheDir.exists() && cacheDir.isDirectory) {
                cacheDir.listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") }?.forEach { mp4File ->
                    val entry = BiliDownloadEntryInfo(
                        media_type = 0,
                        is_completed = true,
                        total_bytes = mp4File.length(),
                        downloaded_bytes = mp4File.length(),
                        title = mp4File.nameWithoutExtension,
                        cover = "",
                        prefered_video_quality = 0,
                        guessed_total_bytes = mp4File.length().toInt(),
                        total_time_milli = 0L,
                        danmaku_count = 0
                    )
                    list.add(
                        BiliDownloadEntryAndPathInfo(
                            entry = entry,
                            entryDirPath = mp4File.absolutePath,
                            pageDirPath = cachePath
                        )
                    )
                }
                break
            }
        }
        return list.reversed()
    }

    private fun isDatabaseFile(name: String?): Boolean {
        return name?.let {
            it.endsWith(".db", true) || it.endsWith(".sqlite", true) || it.endsWith(".db3", true)
        } ?: false
    }

    private fun collectAllDocumentFiles(root: DocumentFile): List<DocumentFile> {
        val results = mutableListOf<DocumentFile>()
        if (root.isFile) {
            results.add(root)
        } else {
            root.listFiles().forEach { child ->
                if (child.isFile) {
                    results.add(child)
                } else if (child.isDirectory) {
                    results.addAll(collectAllDocumentFiles(child))
                }
            }
        }
        return results
    }

    private suspend fun readMMSOVideoListFromDocumentFolder(
        root: DocumentFile,
        metadata: Map<String, String>
    ): List<BiliDownloadEntryAndPathInfo> {
        val mp4Files = collectAllDocumentFiles(root).filter { it.isFile && it.name?.endsWith(".mp4", true) == true }
        return mp4Files.mapNotNull { videoFile ->
            val name = videoFile.name ?: return@mapNotNull null
            val title = metadata[name] ?: name.substringBeforeLast('.')
            val entry = BiliDownloadEntryInfo(
                media_type = 0,
                is_completed = true,
                total_bytes = videoFile.length(),
                downloaded_bytes = videoFile.length(),
                title = title,
                cover = "",
                prefered_video_quality = 0,
                guessed_total_bytes = videoFile.length().toInt(),
                total_time_milli = 0L,
                danmaku_count = 0
            )
            BiliDownloadEntryAndPathInfo(
                entry = entry,
                entryDirPath = videoFile.uri.toString(),
                pageDirPath = root.uri.toString()
            )
        }.reversed()
    }

    private suspend fun readMMSODBMetadata(root: DocumentFile): Map<String, String> {
        val dbFile = findDatabaseFile(root) ?: return emptyMap()
        val tempDbFile = File.createTempFile("mmso_db", ".db", context.cacheDir)
        return try {
            MiaoDocumentFile(context, dbFile).copyToTemp(tempDbFile)
            readMMSODBMetadata(tempDbFile)
        } finally {
            tempDbFile.delete()
        }
    }

    private fun findDatabaseFile(root: DocumentFile): DocumentFile? {
        root.listFiles().forEach { child ->
            if (child.isFile && isDatabaseFile(child.name)) {
                return child
            }
        }
        root.listFiles().filter { it.isDirectory }.forEach { child ->
            val found = findDatabaseFile(child)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun readMMSODBMetadata(dbFile: File): Map<String, String> {
        val metadata = mutableMapOf<String, String>()
        val database = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val tableCursor = database.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
            tableCursor.use { tc ->
                while (tc.moveToNext()) {
                    val tableName = tc.getString(0)
                    if (tableName.startsWith("sqlite_", true)) continue
                    val dataCursor = database.rawQuery("SELECT * FROM `$tableName` LIMIT 200", null)
                    dataCursor.use { dc ->
                        while (dc.moveToNext()) {
                            val row = mutableMapOf<String, String>()
                            for (index in 0 until dc.columnCount) {
                                val columnName = dc.getColumnName(index)
                                val value = dc.getString(index)
                                if (!value.isNullOrBlank()) {
                                    row[columnName] = value
                                }
                            }
                            val fileName = row.entries.firstNotNullOfOrNull { entry ->
                                val keyLower = entry.key.lowercase()
                                val value = entry.value
                                when {
                                    value.contains(".mp4", true) -> File(value).name
                                    keyLower.contains("file") && value.endsWith(".mp4", true) -> File(value).name
                                    keyLower.contains("path") && value.contains(".mp4", true) -> File(value).name
                                    else -> null
                                }
                            }
                            if (fileName != null) {
                                val title = row["title"] ?: row["name"] ?: row["download_title"] ?: row["video_title"] ?: row["title_name"] ?: fileName.substringBeforeLast('.')
                                metadata[fileName] = title
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            database.close()
        }
        return metadata
    }

}