package com.docscan.util

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs

/**
 * High-precision Table Geometry Reconstruction Engine.
 * Reconstructs the exact row/column structure of tables photographed inside documents
 * (salary slips, invoices, receipts, bills, schedules, price lists, forms, bank statements)
 * using OCR word-level and line-level 2D spatial bounding boxes.
 *
 * It groups words that share vertical alignment into aligned column bands,
 * groups horizontally aligned words into distinct rows, and emits cleanly structured
 * CSV / TSV data with full support for multilingual and Bengali Unicode text.
 */
object TableGeometryDetector {

    data class TableExtractionResult(
        val headers: List<String>,
        val rows: List<List<String>>,
        val isHighConfidence: Boolean
    )

    /**
     * Detects tables on [bitmap] and returns structured CSV text.
     * Returns null if the page does not contain tabular structure.
     */
    suspend fun detectTableText(bitmap: Bitmap): String? {
        val result = extractStructuredTable(bitmap) ?: return null
        val sb = StringBuilder()
        
        // Header
        sb.append(result.headers.joinToString(",") { escapeCsv(it) }).append("\n")
        
        // Rows
        for (row in result.rows) {
            sb.append(row.joinToString(",") { escapeCsv(it) }).append("\n")
        }
        
        return sb.toString().trim()
    }

    /**
     * Extracts structured headers and rows using 2D spatial clustering.
     */
    suspend fun extractStructuredTable(bitmap: Bitmap): TableExtractionResult? {
        val lines = TextRecognizerHelper.extractLinesWithWords(bitmap)
        if (lines.isEmpty()) return null

        val allWords = lines.flatMap { it.words }
        if (allWords.size < 3) return null

        // 1. Group lines into rows by vertical proximity
        val avgLineHeight = lines.map { it.rect.height() }.filter { it > 0 }.average().takeIf { it > 0 } ?: 20.0
        val sortedLines = lines.sortedBy { it.rect.centerY() }
        
        val rowGroups = mutableListOf<MutableList<TextRecognizerHelper.LineWithWordsItem>>()
        for (line in sortedLines) {
            val lastRow = rowGroups.lastOrNull()
            val lastCenter = lastRow?.let { r -> r.map { it.rect.centerY() }.average() }
            if (lastRow != null && lastCenter != null && abs(line.rect.centerY() - lastCenter) < avgLineHeight * 0.55) {
                lastRow.add(line)
            } else {
                rowGroups.add(mutableListOf(line))
            }
        }

        if (rowGroups.isEmpty()) return null

        // 2. Discover column bands using horizontal word spans and gaps
        val bitmapWidth = bitmap.width.takeIf { it > 0 } ?: 1000
        val gapThreshold = (bitmapWidth * 0.022f).coerceIn(10f, 40f)
        val wordsByLeft = allWords.sortedBy { it.rect.left }
        
        data class ColumnBand(var left: Int, var right: Int)
        val rawBands = mutableListOf<ColumnBand>()
        for (word in wordsByLeft) {
            val current = rawBands.lastOrNull()
            if (current != null && word.rect.left - current.right < gapThreshold) {
                current.right = maxOf(current.right, word.rect.right)
            } else {
                rawBands.add(ColumnBand(word.rect.left, word.rect.right))
            }
        }

        // Merge very close bands if too fragmented
        val bands = mutableListOf<ColumnBand>()
        for (band in rawBands) {
            val last = bands.lastOrNull()
            if (last != null && band.left - last.right < gapThreshold * 0.8f) {
                last.right = maxOf(last.right, band.right)
            } else {
                bands.add(band)
            }
        }

        if (bands.size < 2 && rowGroups.size < 2) return null

        fun bandIndexFor(rect: Rect): Int {
            if (bands.isEmpty()) return 0
            val cx = rect.centerX()
            var bestIdx = 0
            var bestDist = Int.MAX_VALUE
            bands.forEachIndexed { idx, band ->
                val dist = when {
                    cx < band.left -> band.left - cx
                    cx > band.right -> cx - band.right
                    else -> 0
                }
                if (dist < bestDist) {
                    bestDist = dist
                    bestIdx = idx
                }
            }
            return bestIdx
        }

        // 3. Build the 2D Table Grid
        val grid = mutableListOf<MutableList<String>>()
        val colCount = if (bands.isNotEmpty()) bands.size else 1

        for (row in rowGroups) {
            val cells = MutableList(colCount) { "" }
            val wordsInRow = row.flatMap { it.words }.sortedBy { it.rect.left }
            
            if (bands.isNotEmpty()) {
                for (word in wordsInRow) {
                    val col = bandIndexFor(word.rect)
                    cells[col] = if (cells[col].isEmpty()) word.text.trim() else "${cells[col]} ${word.text.trim()}"
                }
            } else {
                val fullText = wordsInRow.joinToString(" ") { it.text.trim() }
                cells[0] = fullText
            }
            
            // Only add rows that contain at least one non-empty cell
            if (cells.any { it.isNotBlank() }) {
                grid.add(cells)
            }
        }

        if (grid.isEmpty()) return null

        // 4. Identify Header vs Data Rows
        val firstRow = grid.first()
        val hasMultiCols = colCount > 1 && grid.any { r -> r.count { it.isNotBlank() } > 1 }
        
        val headers: List<String>
        val dataRows: List<List<String>>

        if (hasMultiCols && isLikelyHeaderRow(firstRow)) {
            headers = firstRow.mapIndexed { i, h -> h.ifBlank { "Column ${i + 1}" } }
            dataRows = if (grid.size > 1) grid.subList(1, grid.size) else emptyList()
        } else if (hasMultiCols) {
            headers = List(colCount) { "Column ${it + 1}" }
            dataRows = grid
        } else {
            // Key-Value or List layout
            val kvRows = mutableListOf<List<String>>()
            grid.forEachIndexed { idx, row ->
                val text = row.joinToString(" ").trim()
                if (text.contains(":") && !text.startsWith("http")) {
                    val parts = text.split(":", limit = 2)
                    kvRows.add(listOf((idx + 1).toString(), parts[0].trim(), parts.getOrElse(1) { "" }.trim()))
                } else {
                    kvRows.add(listOf((idx + 1).toString(), text))
                }
            }
            val maxLen = kvRows.maxOfOrNull { it.size } ?: 2
            headers = if (maxLen >= 3) listOf("No.", "Field / Label", "Value") else listOf("No.", "Extracted Content")
            dataRows = kvRows
        }

        return TableExtractionResult(
            headers = headers,
            rows = dataRows,
            isHighConfidence = hasMultiCols && grid.size >= 2
        )
    }

    private fun isLikelyHeaderRow(row: List<String>): Boolean {
        if (row.isEmpty()) return false
        val headerKeywords = setOf(
            "no", "sl", "id", "item", "description", "details", "particulars", "name", "date",
            "qty", "quantity", "unit", "rate", "price", "amount", "total", "subtotal", "tax", "vat",
            "debit", "credit", "balance", "status", "remarks", "code", "designation", "salary",
            "বেতন", "বিবরণ", "টাকা", "পরিমাণ", "মূল্য", "মোট", "তারিখ", "ক্রমিক", "পদবী", "নাম"
        )
        val matchCount = row.count { cell ->
            val lower = cell.lowercase().trim()
            headerKeywords.any { kw -> lower == kw || lower.startsWith(kw) || lower.endsWith(kw) }
        }
        return matchCount >= 1 || row.all { it.isNotBlank() && it.length < 35 && !it.any { c -> c.isDigit() } }
    }

    private fun escapeCsv(text: String): String {
        val clean = text.replace("\n", " ").trim()
        return if (clean.contains(",") || clean.contains("\"") || clean.contains(";")) {
            "\"${clean.replace("\"", "\"\"")}\""
        } else {
            clean
        }
    }
}
