package com.example.photorecovery

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import java.io.File

data class Found(
    val key: String,
    val name: String,
    val source: String,
    val file: File? = null,
    val uri: Uri? = null,
    val trashed: Boolean = false,
    val sizeKb: Long = 0
)

object Scanner {
    // مجلدات سلة المحذوفات الشائعة في تطبيقات المعرض ومدراء الملفات وبطاقات SD
    private val trashNames = setOf(
        ".trash", ".trashbin", ".recycle", ".recyclebin", "recyclebin",
        "lost.dir", ".thumbnails", ".gallery_trash", "trash"
    )

    /** 1) الصور في سلة نظام أندرويد (MediaStore) - أندرويد 11+ ، تُحذف نهائياً بعد 30 يوماً */
    fun trash(ctx: Context): List<Found> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val out = mutableListOf<Found>()
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATA
        )
        runCatching {
            ctx.contentResolver.query(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                proj, args, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val path = c.getString(3)?.let { File(it) }?.takeIf { it.exists() }
                    out += Found(
                        key = uri.toString(), name = c.getString(1) ?: "image",
                        source = "سلة المحذوفات", file = path, uri = uri,
                        trashed = true, sizeKb = c.getLong(2) / 1024
                    )
                }
            }
        }
        return out
    }

    /** 2) فحص وحدات التخزين (الداخلية + SD) بحثاً عن صور داخل مجلدات السلة/المصغرات/LOST.DIR */
    fun files(ctx: Context): List<Found> {
        val roots = ctx.getExternalFilesDirs(null)
            .mapNotNull { it?.absolutePath?.substringBefore("/Android") }
            .distinct().map { File(it) }
        val out = mutableListOf<Found>()
        for (root in roots) {
            root.walkTopDown().onEnter { it.name != "Android" }.forEach { f ->
                if (f.isFile && f.length() > 2048 && !f.name.startsWith(".trashed-") && inTrash(f, root)) {
                    if (sniff(f) != null) {
                        out += Found(
                            key = f.absolutePath, name = f.name,
                            source = f.parentFile?.name ?: "", file = f, sizeKb = f.length() / 1024
                        )
                    }
                }
            }
        }
        return out
    }

    private fun inTrash(f: File, root: File): Boolean =
        f.relativeTo(root).path.lowercase().split('/').dropLast(1).any { it in trashNames }

    /** يتعرف على نوع الصورة من أول بايتات الملف (حتى لو بدون امتداد) */
    fun sniff(f: File): String? = runCatching {
        val b = ByteArray(12)
        f.inputStream().use { it.read(b) }
        when {
            b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
            b[0] == 0x89.toByte() && String(b, 1, 3) == "PNG" -> "image/png"
            String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WEBP" -> "image/webp"
            String(b, 0, 3) == "GIF" -> "image/gif"
            else -> null
        }
    }.getOrNull()

    /** ينسخ الصورة إلى Pictures/Recovered ويظهرها في المعرض */
    fun copyOut(ctx: Context, f: File): Boolean = runCatching {
        val mime = sniff(f) ?: return false
        val ext = mime.substringAfter('/').replace("jpeg", "jpg")
        val base = f.nameWithoutExtension.trimStart('.')
            .ifEmpty { "recovered_${System.currentTimeMillis()}" }
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$base.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Recovered")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { o ->
            f.inputStream().use { it.copyTo(o) }
        }
        true
    }.getOrDefault(false)
}
