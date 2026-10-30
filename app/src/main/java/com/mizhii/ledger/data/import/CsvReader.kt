package com.mizhii.ledger.data.import

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 零依赖 CSV 读取器（需求文档 §7.9）。
 *
 * 两个必须处理的现实问题：
 *  1. **编码**。微信导出的 CSV 是 UTF-8 with BOM；支付宝导出的通常是 **GBK**（且没有被严格文档化）。
 *     这里按 BOM → UTF-8 严格解码 → GB18030 的顺序探测，探测结果会写进导入批次记录。
 *  2. **引号**。账单里的「商品」「备注」列可能含逗号、换行、双引号，
 *     必须按 RFC4180 解析（`""` 是转义的双引号）。
 */
object CsvReader {

    data class Csv(
        val rows: List<List<String>>,
        val encoding: String,
        val hadBom: Boolean,
    )

    fun read(bytes: ByteArray): Csv {
        val (text, encoding, hadBom) = decode(bytes)
        return Csv(parseRows(text), encoding, hadBom)
    }

    // ── 编码探测 ─────────────────────────────────────────────────────

    private fun decode(bytes: ByteArray): Triple<String, String, Boolean> {
        // UTF-8 BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Triple(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), "UTF-8-BOM", true)
        }
        // UTF-16 BOM
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Triple(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), "UTF-16LE", true)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Triple(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), "UTF-16BE", true)
        }
        // 严格 UTF-8：中文 GBK 字节序列几乎必然包含非法 UTF-8 序列，所以这里失败即可判定不是 UTF-8
        strictDecode(bytes, Charsets.UTF_8)?.let { return Triple(it, "UTF-8", false) }
        // 兜底 GB18030（GBK 的超集）
        strictDecode(bytes, Charset.forName("GB18030"))?.let { return Triple(it, "GB18030", false) }
        // 实在不行按 UTF-8 替换非法字节，至少能读出来
        return Triple(String(bytes, Charsets.UTF_8), "UTF-8-LOSSY", false)
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    // ── RFC4180 行解析 ──────────────────────────────────────────────

    internal fun parseRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        var fieldStarted = false

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
            fieldStarted = false
        }

        fun endRow() {
            endField()
            // 跳过完全空的尾行
            if (row.size > 1 || row[0].isNotEmpty()) rows.add(row)
            row = ArrayList()
        }

        while (i < text.length) {
            val ch = text[i]
            when {
                inQuotes -> {
                    if (ch == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            field.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(ch)
                    }
                }
                ch == '"' && !fieldStarted -> {
                    inQuotes = true
                    fieldStarted = true
                }
                ch == ',' -> endField()
                ch == '\r' -> {
                    endRow()
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                }
                ch == '\n' -> endRow()
                else -> {
                    field.append(ch)
                    fieldStarted = true
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
