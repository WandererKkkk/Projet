package cn.a10miaomiao.bilidown.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import cn.a10miaomiao.bilidown.common.file.MiaoDocumentFile
import cn.a10miaomiao.bilidown.common.file.MiaoFile
import cn.a10miaomiao.bilidown.common.file.MiaoJavaFile
import cn.a10miaomiao.bilidown.entity.BiliDownloadEntryAndPathInfo
import cn.a10miaomiao.bilidown.entity.BiliDownloadEntryInfo
import cn.a10miaomiao.bilidown.shizuku.util.RemoteServiceUtil
import kotlinx.coroutines.TimeoutCancellationException
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

    fun canRead(): Boolean {
        if (enabledShizuku) {
            return true
        }
        val downloadDir = createMiaoFile(DIR_DOWNLOAD)
        return downloadDir.canRead()
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
            MiaoDocumentFile.requestFolderPermission(
                context as Activity,
                REQUEST_CODE_FOR_DIR,
                getDocumentFileId()
            )
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
        val list = mutableListOf<BiliDownloadEntryAndPathInfo>()
        
        // Try to find MMSO cache directory
        val externalStorage = Environment.getExternalStorageDirectory()
        
        // Check common MMSO storage paths
        val possiblePaths = listOf(
            "/storage/1768-3511/data/files/playable_cache",
            externalStorage.absolutePath + "/Android/data/com.mmstudyonline.mmso/files/playable_cache",
            externalStorage.absolutePath + "/Android/data/com.mmstudyonline.mmso/cache/playable_cache"
        )
        
        for (cachePath in possiblePaths) {
            val cacheDir = File(cachePath)
            if (cacheDir.exists() && cacheDir.isDirectory) {
                // Scan for MP4 files
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

}