package com.docscan.ui.remover.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.docscan.ui.remover.model.SubjectCategory
import com.docscan.util.AiSettingsManager
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

data class AiSubjectGuide(
    val category: SubjectCategory,
    val normalizedBox: Rect, // 0..1000 normalized coordinates: left, top, right, bottom
    val hasHairOrFur: Boolean,
    val difficultBackground: Boolean,
    val confidence: Float
)

interface AiBackgroundProvider {
    val name: String
    suspend fun analyzeSubject(bitmap: Bitmap, context: Context): AiSubjectGuide?
}

/**
 * Google Gemini 2.5 Flash Vision Background Provider
 */
class GeminiBackgroundProvider : AiBackgroundProvider {
    override val name = "Gemini Vision"

    companion object {
        private const val TAG = "GeminiBgProvider"
        private const val URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun analyzeSubject(bitmap: Bitmap, context: Context): AiSubjectGuide? = withContext(Dispatchers.IO) {
        val apiKey = AiSettingsManager.getGeminiKey(context)
        if (apiKey.isBlank() || apiKey.startsWith("MY_")) {
            Log.w(TAG, "Gemini API key is not configured.")
            return@withContext null
        }

        try {
            val base64Image = encodeBitmapToBase64(bitmap)
            val prompt = """
                Analyze this photo for precision foreground subject extraction and background removal.
                Detect the primary foreground subject (Person, Pet, Product, Document, Vehicle, Furniture, or Object).
                Provide the tight normalized bounding box of the subject [ymin, xmin, ymax, xmax] on a scale of 0 to 1000.
                Return ONLY a valid JSON object strictly in this format:
                {
                  "category": "PERSON" | "PET_ANIMAL" | "PRODUCT" | "DOCUMENT" | "VEHICLE" | "FURNITURE" | "GENERAL_OBJECT",
                  "box_2d": [ymin, xmin, ymax, xmax],
                  "has_hair_or_fur": true,
                  "difficult_background": false
                }
            """.trimIndent()

            val requestJson = JSONObject().apply {
                val parts = JSONArray().apply {
                    put(JSONObject().put("text", prompt))
                    put(JSONObject().put("inlineData", JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", base64Image)
                    }))
                }
                put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("responseMimeType", "application/json")
                })
            }

            val request = Request.Builder()
                .url("$URL?key=$apiKey")
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Gemini API failed with code ${response.code}")
                return@withContext null
            }

            val respBody = response.body?.string() ?: return@withContext null
            val root = JSONObject(respBody)
            val text = root.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            parseJsonGuide(text)
        } catch (e: Exception) {
            Log.e(TAG, "Error in Gemini background analysis: ${e.message}")
            null
        }
    }
}

/**
 * Anthropic Claude 3.5 Sonnet Vision Background Provider
 */
class ClaudeBackgroundProvider : AiBackgroundProvider {
    override val name = "Claude Vision"

    companion object {
        private const val TAG = "ClaudeBgProvider"
        private const val URL = "https://api.anthropic.com/v1/messages"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun analyzeSubject(bitmap: Bitmap, context: Context): AiSubjectGuide? = withContext(Dispatchers.IO) {
        val apiKey = AiSettingsManager.getClaudeKey(context)
        if (apiKey.isBlank() || apiKey.startsWith("MY_")) {
            Log.w(TAG, "Claude API key is not configured.")
            return@withContext null
        }

        try {
            val base64Image = encodeBitmapToBase64(bitmap)
            val prompt = """
                Extract the main foreground subject for background removal.
                Return ONLY a JSON with:
                {
                  "category": "PERSON" | "PET_ANIMAL" | "PRODUCT" | "DOCUMENT" | "VEHICLE" | "FURNITURE" | "GENERAL_OBJECT",
                  "box_2d": [ymin, xmin, ymax, xmax],
                  "has_hair_or_fur": true,
                  "difficult_background": false
                }
                Scale: 0 to 1000. Do not wrap in markdown quotes.
            """.trimIndent()

            val contentArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "image")
                    put("source", JSONObject().apply {
                        put("type", "base64")
                        put("media_type", "image/jpeg")
                        put("data", base64Image)
                    })
                })
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", prompt)
                })
            }

            val requestJson = JSONObject().apply {
                put("model", "claude-3-5-sonnet-20241022")
                put("max_tokens", 500)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", contentArray)
                }))
            }

            val request = Request.Builder()
                .url(URL)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Claude API failed with code ${response.code}")
                return@withContext null
            }

            val respBody = response.body?.string() ?: return@withContext null
            val root = JSONObject(respBody)
            val text = root.getJSONArray("content")
                .getJSONObject(0)
                .getString("text")

            parseJsonGuide(text)
        } catch (e: Exception) {
            Log.e(TAG, "Error in Claude background analysis: ${e.message}")
            null
        }
    }
}

private fun parseJsonGuide(jsonStr: String): AiSubjectGuide? {
    return try {
        val clean = jsonStr.substringAfter("{").substringBeforeLast("}")
        val json = JSONObject("{$clean}")
        val catStr = json.optString("category", "PERSON")
        val category = try {
            SubjectCategory.valueOf(catStr)
        } catch (e: Exception) {
            SubjectCategory.PERSON
        }

        val box = json.optJSONArray("box_2d") ?: JSONArray("[50, 50, 950, 950]")
        val ymin = box.getInt(0)
        val xmin = box.getInt(1)
        val ymax = box.getInt(2)
        val xmax = box.getInt(3)

        val hasHair = json.optBoolean("has_hair_or_fur", category == SubjectCategory.PERSON || category == SubjectCategory.PET_ANIMAL)
        val difficult = json.optBoolean("difficult_background", false)

        AiSubjectGuide(
            category = category,
            normalizedBox = Rect(xmin, ymin, xmax, ymax),
            hasHairOrFur = hasHair,
            difficultBackground = difficult,
            confidence = 0.95f
        )
    } catch (e: Exception) {
        null
    }
}

private fun encodeBitmapToBase64(bitmap: Bitmap, maxDim: Int = 800): String {
    val w = bitmap.width
    val h = bitmap.height
    val scaled = if (w > maxDim || h > maxDim) {
        val factor = maxDim.toFloat() / max(w, h)
        Bitmap.createScaledBitmap(bitmap, (w * factor).toInt(), (h * factor).toInt(), true)
    } else {
        bitmap
    }
    val baos = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
    val bytes = baos.toByteArray()
    if (scaled != bitmap) scaled.recycle()
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}
