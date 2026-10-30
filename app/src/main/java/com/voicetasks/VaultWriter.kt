package com.voicetasks

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.time.LocalDate

/** Writes tasks into the vault folder the user picked: appended to one file, or one note each. */
class VaultWriter(private val context: Context) {

    /** [pathTemplate] is relative to the vault; "{date}" becomes today's date, e.g. "Daily/{date}.md". */
    /** Returns the file's URI. */
    fun append(vault: Uri, pathTemplate: String, lines: List<String>, today: LocalDate = LocalDate.now()): Uri {
        val parts = splitPath(resolvePath(pathTemplate, today))
        if (parts.isEmpty()) throw IOException("The inbox file path is empty")

        val dir = folder(vault, parts.dropLast(1))
        val name = parts.last()
        // octet-stream so the provider keeps the name exactly instead of adding ".txt".
        val file = dir.findFile(name)
            ?: dir.createFile("application/octet-stream", name)
            ?: throw IOException("Can't create \"$name\"")

        // "rw" never truncates, unlike "w" on some providers.
        val pfd = context.contentResolver.openFileDescriptor(file.uri, "rw")
            ?: throw IOException("Can't open \"$name\"")
        pfd.use {
            val size = it.statSize
            val needsNewline = size > 0 && run {
                val last = ByteBuffer.allocate(1)
                FileInputStream(it.fileDescriptor).channel.read(last, size - 1)
                last.get(0) != '\n'.code.toByte()
            }
            val text = (if (needsNewline) "\n" else "") + lines.joinToString("\n", postfix = "\n")
            FileOutputStream(it.fileDescriptor).channel.write(ByteBuffer.wrap(text.toByteArray()), size)
        }
        return file.uri
    }

    /**
     * Creates one note per (title, content) pair in [folderTemplate] (relative to the vault,
     * "{date}" allowed), each named after its task. Returns the new notes' URIs, in order.
     */
    fun createNotes(
        vault: Uri,
        folderTemplate: String,
        notes: List<Pair<String, String>>,
        today: LocalDate = LocalDate.now(),
    ): List<Uri> {
        val dir = folder(vault, splitPath(resolvePath(folderTemplate, today)))
        val taken = dir.listFiles().mapNotNull { it.name?.lowercase() }.toMutableSet()
        return notes.map { (title, content) ->
            val name = noteFileName(title, taken)
            taken += name.lowercase()
            val file = dir.createFile("application/octet-stream", name)
                ?: throw IOException("Can't create \"$name\"")
            context.contentResolver.openOutputStream(file.uri, "w")?.use { it.write(content.toByteArray()) }
                ?: throw IOException("Can't open \"$name\"")
            file.uri
        }
    }

    /** Reads a file the app wrote earlier, or null if it's gone. */
    fun read(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
    }.getOrNull()

    /** Walks down from the vault into [parts], creating folders that don't exist yet. */
    private fun folder(vault: Uri, parts: List<String>): DocumentFile {
        var dir = DocumentFile.fromTreeUri(context, vault) ?: throw IOException("Can't open the vault folder")
        for (name in parts) {
            dir = dir.findFile(name)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(name)
                ?: throw IOException("Can't create folder \"$name\"")
        }
        return dir
    }

    companion object {
        private val ILLEGAL_NAME_CHARS = Regex("""[\\/:*?"<>|#^\[\]]""")
        private const val MAX_NAME_LENGTH = 100

        fun resolvePath(pathTemplate: String, today: LocalDate): String =
            pathTemplate.trim().replace("{date}", today.toString())

        fun splitPath(path: String): List<String> = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }

        /**
         * "Call mom" → "Call mom.md", or "Call mom 2.md" if that's [taken] (lowercase names).
         * Drops characters Obsidian can't link to or that aren't allowed in file names.
         */
        fun noteFileName(title: String, taken: Set<String>): String {
            val base = title.replace(ILLEGAL_NAME_CHARS, " ").replace(Regex("""\s+"""), " ")
                .trim(' ', '.').take(MAX_NAME_LENGTH).trim(' ', '.')
                .ifEmpty { "Task" }
            var name = "$base.md"
            var n = 2
            while (name.lowercase() in taken) name = "$base ${n++}.md"
            return name
        }

        fun vaultName(context: Context, vault: Uri?): String? =
            vault?.let { DocumentFile.fromTreeUri(context, it)?.name }
    }
}
