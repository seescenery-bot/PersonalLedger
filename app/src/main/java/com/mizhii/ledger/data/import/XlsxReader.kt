package com.mizhii.ledger.data.import

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 零依赖的 xlsx 读取器（需求文档 §7.9 / §8.6）。
 *
 * 为什么自己写而不引库：
 *  - Apache POI 在 Android 上要 20 MB+，且要开一堆 desugar；
 *  - 我们只需要「把第一个工作表读成字符串二维表」，账单导出的 xlsx 结构极简单
 *    （sharedStrings + 一个 sheet，无公式、无合并单元格、无图表）。
 *
 * 实现用 `java.util.zip` + `javax.xml.parsers`（DOM）——**两者在 Android 与 JVM 单测里都可用**，
 * 因此这个类可以被单元测试直接拿真账单文件跑，不需要 Robolectric。
 *
 * 刻意只做「读文本」这一件事，数据解释（哪列是日期、哪列是金额）全部交给 [BillFile]：
 *  - 字符串共享表（`t="s"`）→ 还原成文本
 *  - 内联字符串（`t="inlineStr"`）/ 公式结果（`t="str"`）→ 取文本
 *  - 数字与日期 → **原样返回十进制文本**（日期是 Excel 序列号，如 `46324.720034722224`，
 *    由调用方按列解释，见 [BillFile.parseTime]）
 */
object XlsxReader {

    /** 防御：账单不会超过这个规模，超了说明不是账单文件 */
    private const val MAX_ROWS = 20_000

    /** 防御：单个压缩包解压后的总上限 */
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    fun readRows(input: InputStream): List<List<String>> {
        var sharedBytes: ByteArray? = null
        var sheetBytes: ByteArray? = null
        var total = 0L

        ZipInputStream(BufferedInputStream(input)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name
                    val wanted = name == "xl/sharedStrings.xml" || name == SHEET1
                    if (wanted) {
                        val bytes = zis.readBytes()
                        total += bytes.size
                        require(total <= MAX_TOTAL_BYTES) { "xlsx 解压体积异常（疑似非账单文件）" }
                        if (name == "xl/sharedStrings.xml") sharedBytes = bytes else sheetBytes = bytes
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
                if (sheetBytes != null && sharedBytes != null) break
            }
        }

        val sheet = sheetBytes ?: throw IllegalArgumentException("不是有效的 xlsx：找不到工作表 xl/worksheets/sheet1.xml")
        val shared = ArrayList<String>()
        sharedBytes?.let { parseSharedStrings(it, shared) }
        return parseSheet(sheet, shared)
    }

    private const val SHEET1 = "xl/worksheets/sheet1.xml"

    private fun newBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        // 用户提供的文件，禁止 DTD / 外部实体（XXE）
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        factory.isExpandEntityReferences = false
        return factory.newDocumentBuilder()
    }

    private fun parse(bytes: ByteArray): org.w3c.dom.Document =
        newBuilder().parse(InputSource(ByteArrayInputStream(bytes)))

    /** `<si><t>文本</t></si>`；富文本会拆成多个 `<r><t>`，全部拼起来 */
    private fun parseSharedStrings(bytes: ByteArray, out: MutableList<String>) {
        val doc = parse(bytes)
        val si = doc.getElementsByTagName("si")
        for (i in 0 until si.length) {
            val element = si.item(i) as? Element ?: continue
            out.add(collectText(element))
        }
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val doc = parse(bytes)
        val rowNodes = doc.getElementsByTagName("row")
        if (rowNodes.length == 0) return emptyList()

        // 先求全局最大列号：账单里有空单元格会被省略，必须补齐才能按下标取列
        var maxCol = -1
        for (i in 0 until rowNodes.length) {
            val row = rowNodes.item(i) as? Element ?: continue
            val cells = row.getElementsByTagName("c")
            for (j in 0 until cells.length) {
                val ref = (cells.item(j) as? Element)?.getAttribute("r").orEmpty()
                val idx = columnIndexOf(ref)
                if (idx > maxCol) maxCol = idx
            }
        }
        if (maxCol < 0) return emptyList()

        val result = ArrayList<List<String>>(minOf(rowNodes.length, MAX_ROWS))
        for (i in 0 until minOf(rowNodes.length, MAX_ROWS)) {
            val row = rowNodes.item(i) as? Element ?: continue
            val values = MutableList(maxCol + 1) { "" }
            val cells = row.getElementsByTagName("c")
            for (j in 0 until cells.length) {
                val cell = cells.item(j) as? Element ?: continue
                val idx = columnIndexOf(cell.getAttribute("r"))
                if (idx in 0..maxCol) values[idx] = cellText(cell, shared)
            }
            result.add(values)
        }
        return result
    }

    /** `A19` → 0，`K19` → 10，`AA3` → 26 */
    internal fun columnIndexOf(cellRef: String): Int {
        var col = 0
        var seen = false
        for (ch in cellRef) {
            if (ch in 'A'..'Z') {
                col = col * 26 + (ch - 'A' + 1)
                seen = true
            } else if (ch in 'a'..'z') {
                col = col * 26 + (ch - 'a' + 1)
                seen = true
            } else {
                break
            }
        }
        return if (seen) col - 1 else -1
    }

    private fun cellText(cell: Element, shared: List<String>): String {
        return when (cell.getAttribute("t")) {
            "s" -> {
                val v = firstText(cell, "v") ?: return ""
                shared.getOrNull(v.trim().toIntOrNull() ?: -1) ?: ""
            }
            "inlineStr" -> {
                val isElement = cell.getElementsByTagName("is").item(0) as? Element ?: return ""
                collectText(isElement)
            }
            "str" -> firstText(cell, "v") ?: ""
            "b" -> if (firstText(cell, "v") == "1") "TRUE" else "FALSE"
            "e" -> firstText(cell, "v") ?: ""
            // 数字 / 日期序列号：原样返回十进制文本
            else -> firstText(cell, "v") ?: ""
        }
    }

    private fun firstText(parent: Element, tag: String): String? {
        val nodes = parent.getElementsByTagName(tag)
        if (nodes.length == 0) return null
        return nodes.item(0)?.textContent ?: ""
    }

    private fun collectText(parent: Element): String {
        val nodes = parent.getElementsByTagName("t")
        if (nodes.length == 0) return ""
        val sb = StringBuilder()
        for (i in 0 until nodes.length) {
            sb.append(nodes.item(i)?.textContent.orEmpty())
        }
        return sb.toString()
    }
}
