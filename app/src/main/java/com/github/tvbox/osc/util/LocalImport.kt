package com.github.tvbox.osc.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * 本地文件导入工具。
 *
 * SAF 返回的是 content:// Uri，部分机型（如小米文件管理器）的 DocumentProvider
 * 不支持持久化授权，App 重启后该 Uri 即失效。因此导入时直接把文件内容复制到
 * App 内部私有目录 files/<subDir>/ 下，并以 file:// 绝对路径存储：
 *  - 内部私有目录读写不需要任何存储权限（自然也无需 MANAGE_EXTERNAL_STORAGE）；
 *  - 重启后依然可读，彻底避免 content:// 失效导致的"暂无数据/无法加载"。
 */
object LocalImport {

    fun copyToPrivate(context: Context, uri: Uri, subDir: String, displayName: String): String? {
        return try {
            val safe = sanitizeName(displayName)
            val dir = File(context.filesDir, subDir)
            if (!dir.exists()) dir.mkdirs()
            var target = File(dir, safe)
            var index = 1
            while (target.exists()) {
                val dot = safe.lastIndexOf('.')
                val prefix = if (dot > 0) safe.substring(0, dot) else safe
                val suffix = if (dot > 0) safe.substring(dot) else ""
                target = File(dir, "$prefix($index)$suffix")
                index++
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            } ?: return null
            Uri.fromFile(target).toString()
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    private fun sanitizeName(name: String): String {
        val raw = name.trim().substringAfterLast('/').substringAfterLast('\\')
        val safe = if (raw.isEmpty()) "import" else raw.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return if (safe.endsWith(".json", true) || safe.endsWith(".txt", true)
            || safe.endsWith(".m3u", true) || safe.endsWith(".m3u8", true)
        ) safe else "$safe.json"
    }
}
