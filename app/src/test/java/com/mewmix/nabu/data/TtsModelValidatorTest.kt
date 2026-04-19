package com.mewmix.nabu.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempDirectory

class TtsModelValidatorTest {
    @Test
    fun sopranoRequiresAllFiles() {
        val root = createTempDirectory(prefix = "soprano-validator-missing").toFile()
        createSizedFile(File(root, "soprano_backbone_kv.onnx"), 200_000_001L)
        assertFalse(TtsModelValidator.hasAllRequiredFiles("soprano-80m-onnx", root))
    }

    @Test
    fun sopranoRejectsTooSmallFiles() {
        val root = createTempDirectory(prefix = "soprano-validator-small").toFile()
        File(root, "soprano_backbone_kv.onnx").writeBytes(ByteArray(10))
        File(root, "soprano_decoder.onnx").writeBytes(ByteArray(10))
        File(root, "soprano_decoder.onnx.data").writeBytes(ByteArray(10))
        File(root, "tokenizer.json").writeBytes("{}".toByteArray(StandardCharsets.UTF_8))
        assertFalse(TtsModelValidator.hasAllRequiredFiles("soprano-80m-onnx", root))
    }

    @Test
    fun supertonicRequiresExpectedBundle() {
        val root = createTempDirectory(prefix = "tts-validator-supertonic").toFile()
        assertFalse(TtsModelValidator.hasAllRequiredFiles("supertonic-2-onnx", root))

        val required = TtsModelValidator.requiredFiles("supertonic-2-onnx")
        required.forEach { relativePath ->
            val target = File(root, relativePath)
            target.parentFile?.mkdirs()
            val minBytes = if (relativePath.startsWith("voice_styles/")) 100 else 1_000
            createSizedFile(target, minBytes.toLong())
        }
        assertTrue(TtsModelValidator.hasAllRequiredFiles("supertonic-2-onnx", root))
    }

    private fun createSizedFile(file: File, sizeBytes: Long) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(sizeBytes)
        }
    }
}
