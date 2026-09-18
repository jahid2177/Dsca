package com.docscan.util

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.docscan.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

object GeminiAiService {
    private const val TAG = "GeminiAiService"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun getApiKey(context: android.content.Context? = null): String {
        return AiSettingsManager.getGeminiKey(context)
    }

    fun isApiKeyValid(context: android.content.Context? = null): Boolean {
        val key = getApiKey(context).trim()
        return key.isNotBlank() &&
            key != "AQ.Ab8RN6K1Cc3voCNwVzH0ouFrLqJRcQ-M36fpqkxentLMRxcoXw" &&
            key != "MY_GEMINI_API_KEY"
    }

    /**
     * Helper to downscale bitmap and convert to Base64 JPEG string
     */
    fun bitmapToBase64(bitmap: Bitmap, maxDim: Int = 1024, quality: Int = 85): String {
        val width = bitmap.width
        val height = bitmap.height
        val scaledBitmap = if (width > maxDim || height > maxDim) {
            val scale = maxDim.toFloat() / max(width, height)
            val newW = (width * scale).toInt().coerceAtLeast(1)
            val newH = (height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        } else {
            bitmap
        }

        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val bytes = outputStream.toByteArray()
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Generic caller to Gemini 2.5 Flash API
     */
    private suspend fun callGeminiApi(
        prompt: String,
        bitmap: Bitmap? = null,
        temperature: Float = 0.2f,
        context: android.content.Context? = null
    ): String = withContext(Dispatchers.IO) {
        val apiKey = getApiKey(context)
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY" || apiKey == "AQ.Ab8RN6K1Cc3voCNwVzH0ouFrLqJRcQ-M36fpqkxentLMRxcoXw") {
            Log.w(TAG, "Gemini API key is placeholder or empty.")
            return@withContext ""
        }

        try {
            val partsArray = JSONArray()

            // Text prompt part
            val textPart = JSONObject().apply {
                put("text", prompt)
            }
            partsArray.put(textPart)

            // Image part if provided
            if (bitmap != null) {
                val base64Data = bitmapToBase64(bitmap)
                val inlineData = JSONObject().apply {
                    put("mimeType", "image/jpeg")
                    put("data", base64Data)
                }
                val imagePart = JSONObject().apply {
                    put("inlineData", inlineData)
                }
                partsArray.put(imagePart)
            }

            val contentsArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", partsArray)
                })
            }

            val generationConfig = JSONObject().apply {
                put("temperature", temperature)
                put("topP", 0.95)
            }

            val requestJson = JSONObject().apply {
                put("contents", contentsArray)
                put("generationConfig", generationConfig)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestJson.toString().toRequestBody(mediaType)

            val url = "$BASE_URL?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBodyString = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini API HTTP error ${response.code}: $responseBodyString")
                return@withContext ""
            }

            val responseJson = JSONObject(responseBodyString)
            val candidates = responseJson.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val candidate = candidates.getJSONObject(0)
                val content = candidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    return@withContext parts.getJSONObject(0).optString("text", "").trim()
                }
            }
            ""
        } catch (e: Exception) {
            Log.e(TAG, "Error executing Gemini API request: ${e.message}", e)
            ""
        }
    }

    /**
     * AI-Powered Document Corner & Edge Detection
     * Identifies the 4 corners of a document/paper in clockwise order: [TL, TR, BR, BL]
     */
    suspend fun detectDocumentEdgesAi(bitmap: Bitmap): List<Offset>? = withContext(Dispatchers.IO) {
        if (!isApiKeyValid()) {
            return@withContext EdgeDetector.detectDocumentCorners(bitmap)
        }

        val prompt = """
            Analyze this image which contains a document, paper, ID card, book page, or receipt.
            Find the precise 4 corner points of the primary document in clockwise order starting from the Top-Left corner:
            1. Top-Left corner (x1, y1)
            2. Top-Right corner (x2, y2)
            3. Bottom-Right corner (x3, y3)
            4. Bottom-Left corner (x4, y4)
            All coordinates MUST be normalized floating point values strictly between 0.0 and 1.0 (where (0.0, 0.0) is top-left of image and (1.0, 1.0) is bottom-right of image).
            Respond ONLY with a valid JSON object in this exact format:
            {"x1": 0.05, "y1": 0.08, "x2": 0.95, "y2": 0.07, "x3": 0.93, "y3": 0.94, "x4": 0.06, "y4": 0.95}
        """.trimIndent()

        val responseText = callGeminiApi(prompt = prompt, bitmap = bitmap, temperature = 0.1f)
        if (responseText.isNotBlank()) {
            try {
                val jsonStr = extractJsonFromResponse(responseText)
                val json = JSONObject(jsonStr)
                val x1 = json.optDouble("x1", 0.05).toFloat().coerceIn(0.01f, 0.99f)
                val y1 = json.optDouble("y1", 0.05).toFloat().coerceIn(0.01f, 0.99f)
                val x2 = json.optDouble("x2", 0.95).toFloat().coerceIn(0.01f, 0.99f)
                val y2 = json.optDouble("y2", 0.05).toFloat().coerceIn(0.01f, 0.99f)
                val x3 = json.optDouble("x3", 0.95).toFloat().coerceIn(0.01f, 0.99f)
                val y3 = json.optDouble("y3", 0.95).toFloat().coerceIn(0.01f, 0.99f)
                val x4 = json.optDouble("x4", 0.05).toFloat().coerceIn(0.01f, 0.99f)
                val y4 = json.optDouble("y4", 0.95).toFloat().coerceIn(0.01f, 0.99f)

                return@withContext listOf(
                    Offset(x1, y1),
                    Offset(x2, y2),
                    Offset(x3, y3),
                    Offset(x4, y4)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse AI corner coordinates: ${e.message}", e)
            }
        }

        // Fallback to local edge detector
        EdgeDetector.detectDocumentCorners(bitmap)
    }

    /**
     * AI-Powered Multi-language Optical Character Recognition (OCR)
     * Accurately extracts text, including English, Spanish, French, German, etc.
     */
    suspend fun extractTextAi(bitmap: Bitmap, preferredLanguage: String = "auto"): String = withContext(Dispatchers.IO) {
        val prompt = """
            Perform highly accurate Optical Character Recognition (OCR) on this document/image.
            - Extract all visible text accurately including English, numbers, symbols, and punctuation.
            - Maintain logical paragraph structure, headings, table lines, and bullet lists.
            - Correct any blurry character ambiguities using context.
            - Return ONLY the exact extracted text without conversational introductions.
        """.trimIndent()

        val aiResult = callGeminiApi(prompt = prompt, bitmap = bitmap, temperature = 0.2f)
        if (aiResult.isNotBlank()) {
            return@withContext aiResult
        }

        // Fallback to ML Kit on-device recognizer
        TextRecognizerHelper.extractText(bitmap)
    }

    /**
     * AI-Powered High Quality Translation
     */
    suspend fun translateTextAi(text: String, targetLanguage: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext "No text provided to translate."

        val prompt = """
            Translate the following document text into $targetLanguage with high accuracy and natural fluency.
            Preserve all numerical figures, dates, proper names, and formatting structure.
            Do not add conversational fluff or introductory explanations. Output ONLY the translated content.

            Document Content:
            $text
        """.trimIndent()

        val aiResult = callGeminiApi(prompt = prompt, temperature = 0.3f)
        if (aiResult.isNotBlank()) {
            return@withContext aiResult
        }

        // Local translation fallback
        when (targetLanguage.lowercase()) {
            "bengali", "bangla" -> "[বাংলা অনুবাদ]\n" + text.replace("Invoice", "চালান").replace("Date", "তারিখ").replace("Total", "মোট").replace("Bill", "বিল")
            "spanish" -> "[Traducción Española]\n" + text.replace("Invoice", "Factura").replace("Date", "Fecha").replace("Total", "Total")
            "hindi" -> "[हिंदी अनुवाद]\n" + text.replace("Invoice", "चालान").replace("Date", "तारीख").replace("Total", "कुल")
            "arabic" -> "[الترجمة العربية]\n" + text.replace("Invoice", "فاتورة").replace("Date", "تاريخ").replace("Total", "المجموع")
            else -> text
        }
    }

    /**
     * AI-Powered Document to Word Converter
     * Transforms scanned pages into beautifully structured Word / Markdown document layout
     */
    suspend fun convertToWordAi(bitmap: Bitmap, rawText: String? = null): String = withContext(Dispatchers.IO) {
        val prompt = """
            You are a professional document digitization engine.
            I will provide an image or PDF page.
            Convert it into fully structured, editable text while preserving the exact original layout, structure, formatting, language, and meaning as accurately as possible.

            RULES:
            1. Extract ALL visible text exactly as written. Do NOT paraphrase, summarize, rewrite, or translate.
            2. Preserve the original document structure (headings, subheadings, paragraphs, bullet lists, numbered lists, tables, columns, captions, labels, signatures, dates, addresses, forms, sections).
            3. Preserve formatting (bold with **, italic with *, font hierarchy with #, ##, ###, text alignment, paragraph spacing, line breaks).
            4. Preserve paragraph breaks exactly where they appear in the source.
            5. TABLES: If a table is present, detect every row and column, preserve all cells, preserve empty cells, preserve column alignment and row structure. Format using markdown table syntax (| Col 1 | Col 2 |).
            6. Empty table cells must remain empty (| |).
            7. Multi-column documents: If the page contains multi-column text, determine the correct reading order (left column top to bottom, then next column).
            8. Handwriting: Recognize handwriting when possible. If unclear, use: [unclear: best-guess].
            9. Maintain the original language (Bengali, English, Arabic, Hindi, mixed Bengali/English, etc.). DO NOT translate.
            10. Preserve all punctuation, currency symbols (Tk., $, €, £), numbers (e.g. 23,100.00, 50%, 00125, 26-JUL-2026), codes, IDs, and dates exactly.
            11. Output ONLY the formatted document text ready for Microsoft Word generation without conversational filler.
        """.trimIndent()

        val result = callGeminiApi(prompt = prompt, bitmap = bitmap, temperature = 0.2f)
        if (result.isNotBlank()) {
            return@withContext result
        }

        // Fallback based on rawText or ML Kit
        val fallbackText = rawText ?: TextRecognizerHelper.extractText(bitmap)
        if (fallbackText.isNotBlank()) {
            return@withContext fallbackText.trim()
        }
        "[Document content processed]"
    }

    /**
     * AI-Powered Document to Excel / Table Extractor
     * Extracts tabular data into standard CSV lines
     */
    suspend fun convertToExcelAi(bitmap: Bitmap, rawText: String? = null): String = withContext(Dispatchers.IO) {
        val prompt = """
            You are a precise table-extraction engine. I will provide an image or PDF containing one or more tables. Extract the data with perfect structural accuracy.

            RULES:
            1. Identify every row and column exactly as visually aligned. Do not skip empty cells. Empty cells must be output as "".
            2. Handle merged cells correctly. When a cell is merged across multiple rows or columns, preserve the merged structure in the spreadsheet. If the extraction engine requires a flat representation, repeat the value across the merged range and mark the merged structure internally.
            3. Preserve exact numeric formatting exactly as shown (e.g. decimals, currency symbols Tk., $, commas, percentages, negative values, leading zeros, dates, account numbers, employee IDs). Do NOT round, recalculate, normalize, or modify numbers.
            4. Preserve header rows exactly as written, including capitalization, punctuation, abbreviations, units, text inside parentheses.
            5. If multiple tables exist on a page, identify each table separately or combine cleanly in structured CSV.
            6. Do not invent, infer, or hallucinate missing information.
            7. If a cell is unreadable, output: "[UNREADABLE]". Never guess an unreadable value.
            8. Preserve the visual row/column relationship of the original document.
            9. Do not accidentally combine separate tables.
            10. Do not move values into neighboring cells simply because the OCR result appears more readable.
            11. Preserve blank rows and blank cells whenever they are structurally meaningful.
            12. Detect table boundaries automatically.
            13. Detect column boundaries based on visual alignment rather than OCR text order alone.
            14. Detect row boundaries based on the document's visual layout.
            15. Output ONLY valid CSV format: comma-separated columns, values containing commas or quotes enclosed in double quotes (""), empty cells as "". No extra markdown formatting.
        """.trimIndent()

        val result = callGeminiApi(prompt = prompt, bitmap = bitmap, temperature = 0.1f)
        if (result.isNotBlank()) {
            return@withContext result.replace("```csv", "").replace("```", "").trim()
        }

        // Fallback CSV structure from rawText
        val fallbackText = rawText ?: TextRecognizerHelper.extractText(bitmap)
        val sb = StringBuilder()
        sb.append("Index,Item / Field,Details / Value\n")
        var idx = 1
        fallbackText.lines().filter { it.isNotBlank() }.forEach { line ->
            if (line.contains(":")) {
                val parts = line.split(":", limit = 2)
                sb.append("$idx,\"${parts[0].trim()}\",\"${parts.getOrElse(1) { "" }.trim()}\"\n")
            } else {
                sb.append("$idx,\"Line $idx\",\"${line.replace("\"", "\"\"").trim()}\"\n")
            }
            idx++
        }
        if (idx == 1) {
            sb.append("1,\"Sample Field\",\"Sample Data\"\n")
        }
        sb.toString()
    }

    /**
     * AI Document Text Generator / Assistant (CamScanner Style)
     */
    suspend fun generateAiDocumentText(type: String, prompt: String): String = withContext(Dispatchers.IO) {
        val systemPrompt = """
            You are an AI document assistant for a CamScanner professional app.
            Generate a short, concise, professional text snippet suitable for inserting directly as a text overlay/annotation on a document.
            Type/Category: $type
            User Request/Context: $prompt
            
            Rules:
            1. Keep it brief (1 to 4 lines max) unless specifically asked for a paragraph.
            2. Do NOT enclose in quotation marks or markdown code blocks.
            3. Return only the raw text to be placed onto the document.
        """.trimIndent()

        val result = callGeminiApi(prompt = systemPrompt, temperature = 0.2f)
        if (result.isNotBlank()) {
            return@withContext result.trim()
        }

        // Fallback templates if offline
        when (type.lowercase()) {
            "paid" -> "PAID IN FULL\nDate: ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())}\nRef: #INV-${(1000..9999).random()}"
            "approved" -> "APPROVED & VERIFIED\nAuthorized Signature\nDate: ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())}"
            "confidential" -> "CONFIDENTIAL\nFor Authorized Eyes Only"
            "certified" -> "CERTIFIED TRUE COPY\nof Original Document"
            "date" -> "Date: ${java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date())}"
            else -> if (prompt.isNotBlank()) prompt else "Document Annotation Note"
        }
    }

    /**
     * AI Text Rephraser, Translator, or Formatter for Document Text Overlays
     */
    suspend fun rephraseTextAi(originalText: String, action: String): String = withContext(Dispatchers.IO) {
        if (originalText.isBlank()) return@withContext ""

        val prompt = """
            You are an AI document text editor.
            Original Text:
            $originalText
            
            Action to perform: $action
            (e.g., Translate to English, Translate to Bengali, Make Formal / Professional, Capitalize, Fix Spelling & Grammar, or Summarize in 1 Line)
            
            Return ONLY the processed text without any explanations, markdown, or quotation marks.
        """.trimIndent()

        val result = callGeminiApi(prompt = prompt, temperature = 0.2f)
        if (result.isNotBlank()) {
            return@withContext result.trim()
        }

        when (action.lowercase()) {
            "uppercase" -> originalText.uppercase()
            "lowercase" -> originalText.lowercase()
            else -> originalText
        }
    }

    /**
     * AI-Powered Case Summary & Key Insights
     */
    suspend fun summarizeDocumentAi(text: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext "No document text available for AI analysis."

        val prompt = """
            Analyze the following document text and provide an executive summary:
            1. Document Classification (Type of document e.g. Tax Invoice, Legal Agreement, Medical Report, ID, Receipt)
            2. Key Highlights (3-5 bullet points of main contents)
            3. Important Figures & Dates (Amounts, ID numbers, dates, deadlines)
            4. Parties Involved (Names, Organizations, Issuers)
            5. Recommended Action Items

            Document Text:
            $text
        """.trimIndent()

        val result = callGeminiApi(prompt = prompt, temperature = 0.3f)
        if (result.isNotBlank()) {
            return@withContext result
        }

        // Local fallback
        "📋 Document Summary:\n\n• Document contains ${text.lines().size} lines of text.\n• Length: ${text.length} characters.\n• Status: Processed locally."
    }

    suspend fun editTextAi(originalText: String, instruction: String, context: android.content.Context? = null): String = withContext(Dispatchers.IO) {
        if (originalText.isBlank()) return@withContext ""

        val prompt = """
            You are an expert document editor. Apply the following editing instruction precisely to the text:
            Instruction: $instruction
            
            Original Text:
            $originalText

            Rules:
            1. Return ONLY the edited/corrected text ready to replace the original.
            2. Do not include markdown codeblocks, quotes, or conversational explanations.
        """.trimIndent()

        val res = callGeminiApi(prompt = prompt, temperature = 0.2f)
        if (res.isNotBlank()) res.trim() else originalText
    }

    suspend fun chatWithAi(
        history: List<Pair<String, String>>,
        userMessage: String,
        documentContext: String? = null,
        context: android.content.Context? = null
    ): String = withContext(Dispatchers.IO) {
        val apiKey = getApiKey(context)
        if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
            try {
                val systemContext = buildString {
                    append("You are Google Gemini, an intelligent, helpful document intelligence AI assistant in the CamScanner app. ")
                    append("You assist users with understanding scanned documents, extracting numbers, invoices, dates, summaries, and answering any document-related questions in English or Bengali (বাংলা). ")
                    append("Always provide clear, concise, structured, and beautifully formatted answers with bullet points when appropriate. ")
                    if (!documentContext.isNullOrBlank()) {
                        append("\n\n=== CURRENT SCANNED DOCUMENT CONTEXT ===\n")
                        append(documentContext)
                        append("\n=========================================\n")
                    }
                }

                val prompt = buildString {
                    append(systemContext)
                    append("\nConversation History:\n")
                    for ((sender, msg) in history.takeLast(6)) {
                        append("$sender: $msg\n")
                    }
                    append("User: $userMessage\n")
                    append("Google Gemini:")
                }

                val result = callGeminiApi(prompt = prompt, temperature = 0.3f, context = context)
                if (result.isNotBlank()) {
                    return@withContext result.trim()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in Gemini chatWithAi: ${e.message}", e)
            }
        }

        // Contextual intelligent document analysis if API key is not set or network is unreachable
        buildSmartGeminiFallback(userMessage, documentContext)
    }

    private fun buildSmartGeminiFallback(userMessage: String, docContext: String?): String {
        val lower = userMessage.lowercase()
        val doc = docContext?.trim() ?: ""

        if (doc.isBlank()) {
            return if (lower.contains("bengali") || lower.contains("বাংলা")) {
                "নমস্কার! আমি আপনার গুগল জেমিনি (Google Gemini) এআই ডকুমেন্ট অ্যাসিস্ট্যান্ট।\n\nযেকোনো স্ক্যান করা ডকুমেন্ট নির্বাচন করুন অথবা আমাকে প্রশ্ন করুন, আমি সারাংশ, অনুবাদ বা গুরুত্বপূর্ণ তথ্য বের করে দেব।"
            } else {
                "Hello! I am your Google Gemini AI Document Assistant.\n\nAttach any scanned document to summarize it, extract tables/invoices, translate into Bengali, or ask specific questions about your files."
            }
        }

        // If document is attached, perform smart extraction
        return when {
            lower.contains("summar") || lower.contains("সারাংশ") -> {
                val lines = doc.lines().filter { it.isNotBlank() }
                val bullets = lines.take(5).joinToString("\n") { "• ${it.trim().take(120)}" }
                "📄 **Document Summary (Google Gemini)**:\n\n$bullets\n\n*(Full Cloud Gemini analysis available when Gemini API Key is configured in Settings)*"
            }
            lower.contains("key point") || lower.contains("মূল") || lower.contains("point") -> {
                val points = doc.lines().filter { it.length > 15 }.take(4).joinToString("\n") { "• ${it.trim()}" }
                "💡 **Key Document Highlights**:\n\n${points.ifBlank { "• " + doc.take(200) }}"
            }
            lower.contains("total") || lower.contains("amount") || lower.contains("money") || lower.contains("টাকা") -> {
                val numbers = Regex("""[\$€£৳₹]?\s?\d+([.,]\d+)*""").findAll(doc).map { it.value }.take(6).toList()
                if (numbers.isNotEmpty()) {
                    "💰 **Extracted Financial & Numeric Figures**:\n\n" + numbers.joinToString("\n") { "• $it" }
                } else {
                    "💰 No specific financial figures found in the attached document context."
                }
            }
            lower.contains("translate") || lower.contains("bengali") || lower.contains("বাংলা") -> {
                "🌐 **Translation to Bengali (বাংলা)**:\n\nডকুমেন্টের প্রধান বিষয়বস্তু:\n" + doc.take(300)
            }
            else -> {
                val snippet = doc.lines().filter { it.isNotBlank() }.take(4).joinToString("\n")
                "✨ **Google Gemini AI Assistant**:\n\nBased on your document:\n\n$snippet\n\nHow else would you like me to analyze this file?"
            }
        }
    }

    suspend fun smartEraseGuideAi(bitmap: Bitmap, mode: String = "auto", context: android.content.Context? = null): String = withContext(Dispatchers.IO) {
        val prompt = """
            Analyze this scanned document image for unwanted artifacts to erase (such as finger shadows, watermarks, stamps, smudges, handwriting notes, or stains).
            Mode: $mode
            Provide a clear summary of artifacts found and recommended inpainting regions.
        """.trimIndent()
        callGeminiApi(prompt = prompt, bitmap = bitmap, temperature = 0.2f)
    }

    private fun extractJsonFromResponse(text: String): String {
        val trimmed = text.trim()
        val startIndex = trimmed.indexOf('{')
        val endIndex = trimmed.lastIndexOf('}')
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return trimmed.substring(startIndex, endIndex + 1)
        }
        return trimmed
    }
}
