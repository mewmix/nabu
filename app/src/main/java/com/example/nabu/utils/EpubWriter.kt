package com.example.nabu.utils

import android.content.Context
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object EpubWriter {
    const val MIME_TYPE = "application/epub+zip"

    fun save(context: Context, uri: Uri, title: String, paragraphs: List<String>): Boolean {
        if (paragraphs.isEmpty()) return false
        val resolver = context.contentResolver
        return try {
            resolver.openOutputStream(uri, "wt")?.use { outputStream ->
                writeEpub(outputStream, title.ifBlank { "Edited Book" }, paragraphs)
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun writeEpub(outputStream: OutputStream, title: String, paragraphs: List<String>) {
        ZipOutputStream(BufferedOutputStream(outputStream)).use { zos ->
            writeMimetypeEntry(zos)
            writeTextEntry(
                zos,
                "META-INF/container.xml",
                containerXml
            )
            val bookId = UUID.randomUUID().toString()
            val opfContent = createOpfContent(title, bookId)
            writeTextEntry(zos, "OEBPS/content.opf", opfContent)
            val xhtmlContent = createXhtmlContent(title, paragraphs)
            writeTextEntry(zos, "OEBPS/content.xhtml", xhtmlContent)
        }
    }

    private fun writeMimetypeEntry(zos: ZipOutputStream) {
        val data = MIME_TYPE.toByteArray(StandardCharsets.US_ASCII)
        val entry = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = size
            crc = CRC32().apply { update(data) }.value
        }
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun writeTextEntry(zos: ZipOutputStream, name: String, content: String) {
        val data = content.toByteArray(StandardCharsets.UTF_8)
        zos.putNextEntry(ZipEntry(name))
        zos.write(data)
        zos.closeEntry()
    }

    private fun createOpfContent(title: String, bookId: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="2.0">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>${escapeXml(title)}</dc:title>
                <dc:language>en</dc:language>
                <dc:identifier id="BookId">$bookId</dc:identifier>
                <dc:creator>Nabu Editor</dc:creator>
            </metadata>
            <manifest>
                <item id="content" href="content.xhtml" media-type="application/xhtml+xml"/>
            </manifest>
            <spine>
                <itemref idref="content"/>
            </spine>
        </package>
    """.trimIndent()

    private fun createXhtmlContent(title: String, paragraphs: List<String>): String {
        val paragraphHtml = paragraphs.flatMap { paragraph ->
            paragraph
                .split(Regex("\\n{2,}"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf(paragraph.trim()) }
        }.joinToString(separator = "\n") { para ->
            val formatted = para.replace("\n", "<br/>")
            "<p>${escapeXml(formatted)}</p>"
        }
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                    <title>${escapeXml(title)}</title>
                </head>
                <body>
                    $paragraphHtml
                </body>
            </html>
        """.trimIndent()
    }

    private fun escapeXml(text: String): String {
        val builder = StringBuilder(text.length)
        text.forEach { ch ->
            builder.append(
                when (ch) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> ch
                }
            )
        }
        return builder.toString()
    }

    private val containerXml: String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
            </rootfiles>
        </container>
    """.trimIndent()
}
