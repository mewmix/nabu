package com.example.nabu.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.jsoup.Jsoup
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream
import kotlin.math.min

object TextExtractor {

    data class DocMeta(val displayName: String, val pageCount: Int? = null)

    fun extract(
        ctx: Context,
        uri: Uri,
        chunkSize: Int = 1600
    ): Pair<Sequence<String>, DocMeta> {
        val name = safeName(ctx, uri)
        val mime = ctx.contentResolver.getType(uri) ?: ""
        val seq = when {
            mime.equals("application/pdf", true) || uri.toString().endsWith(".pdf", true) ->
                extractPdf(ctx, uri).chunkedByChars(chunkSize)
            mime.equals("application/epub+zip", true) || uri.toString().endsWith(".epub", true) ->
                extractEpub(ctx, uri).chunkedByChars(chunkSize)
            mime.startsWith("text/") || uri.toString().endsWith(".txt", true) ->
                extractTxt(ctx, uri).chunkedByChars(chunkSize)
            else -> extractTxt(ctx, uri).chunkedByChars(chunkSize)
        }
        return seq to DocMeta(displayName = name)
    }

    private fun extractPdf(ctx: Context, uri: Uri): Sequence<String> = sequence {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(input)
            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
            for (p in 1..doc.numberOfPages) {
                stripper.startPage = p; stripper.endPage = p
                val t = stripper.getText(doc).trim()
                if (t.isNotEmpty()) yield(t)
            }
            doc.close()
        } ?: yield("")
    }

    private fun extractEpub(ctx: Context, uri: Uri): Sequence<String> = sequence {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zis ->
                val items = mutableListOf<Pair<String, String>>()
                var e = zis.nextEntry
                while (e != null) {
                    val n = e.name.lowercase()
                    if (!e.isDirectory && (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm"))) {
                        val html = zis.readBytes().toString(Charsets.UTF_8)
                        val txt = Jsoup.parse(html).text()
                        if (txt.isNotBlank()) items.add(n to txt)
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
                items.sortedBy { it.first }.forEach { (_, txt) -> yield(txt) }
            }
        } ?: yield("")
    }

    private fun extractTxt(ctx: Context, uri: Uri): Sequence<String> = sequence {
        ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { lines ->
            val sb = StringBuilder()
            lines.forEach { line ->
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
                if (sb.length > 32768) { yield(sb.toString()); sb.clear() }
            }
            if (sb.isNotEmpty()) yield(sb.toString())
        } ?: yield("")
    }

    private fun Sequence<String>.chunkedByChars(maxLen: Int): Sequence<String> = sequence {
        val buf = StringBuilder()
        for (block in this@chunkedByChars) {
            var i = 0
            while (i < block.length) {
                val take = min(maxLen - buf.length, block.length - i)
                buf.append(block, i, i + take); i += take
                if (buf.length >= maxLen) { yield(buf.toString()); buf.clear() }
            }
        }
        if (buf.isNotEmpty()) yield(buf.toString())
    }

    private fun safeName(ctx: Context, uri: Uri): String =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else uri.lastPathSegment ?: "document" } ?: "document"
}

