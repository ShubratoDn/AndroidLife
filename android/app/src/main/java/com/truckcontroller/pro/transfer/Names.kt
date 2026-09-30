package com.truckcontroller.pro.transfer

import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** File names coming from browsers: cleaned, kept inside their folder, and never overwriting a file. */
internal object Names {

    private val ILLEGAL_CHARS = Regex("[:*?\"<>|\\x00-\\x1F]")
    private val reserved = ConcurrentHashMap.newKeySet<String>()

    /** One file or folder name, or null when it can't be used. */
    fun clean(name: String?): String? {
        val clean = name.orEmpty().trim().replace(ILLEGAL_CHARS, "_")
        if (clean.isEmpty() || clean == "." || clean == ".." || clean.contains('/') || clean.contains('\\')) return null
        if (clean.toByteArray().size > 255) return null
        return clean
    }

    /** "folder/sub/file.jpg" from a folder upload; every part must be a plain name. */
    fun cleanRelative(name: String?): String? {
        val parts = name.orEmpty().replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return parts.map { clean(it) ?: return null }.joinToString("/")
    }

    /** "photo.jpg" → "photo (1).jpg" when the name is taken (or being written right now). Call [release] after. */
    @Synchronized
    fun reserveUnique(file: File): File {
        val base = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
        var candidate = file
        var n = 1
        while (candidate.exists() || candidate.path in reserved) {
            candidate = File(file.parentFile, "$base ($n)$ext")
            n++
        }
        reserved += candidate.path
        return candidate
    }

    fun release(file: File) {
        reserved -= file.path
    }

    fun mime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "heic", "heif" -> "image/heic"
            "mkv" -> "video/x-matroska"
            "opus" -> "audio/ogg"
            else -> "application/octet-stream"
        }
    }
}
