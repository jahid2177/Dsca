package com.docscan.ui.components.textedit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.docscan.util.AiOrchestrator
import kotlinx.coroutines.launch

/**
 * Modern CamScanner-Style In-Place Text Editor Dialog.
 * Allows seamless in-place editing and replacement of OCR detected document text
 * with real-time typography preview, AI smart editing, precise font styling,
 * ink color matching, and background inpainting.
 */
@Composable
fun InPlaceTextEditorDialog(
    initialText: String,
    initialTextColor: Color,
    initialFontSizeSp: Float,
    initialIsBold: Boolean,
    initialIsItalic: Boolean,
    initialIsUnderline: Boolean,
    initialFontFamily: String,
    initialAlignment: String = "LEFT",
    isPreviouslyEdited: Boolean = false,
    onDismiss: () -> Unit,
    onApplyEdit: (
        newText: String,
        textColor: Color,
        fontSizeSp: Float,
        isBold: Boolean,
        isItalic: Boolean,
        isUnderline: Boolean,
        fontFamily: String,
        alignment: String
    ) -> Unit,
    onEraseFromDocument: () -> Unit,
    onResetToOriginal: (() -> Unit)? = null
) {
    var editedText by remember { mutableStateOf(initialText) }
    var selectedColor by remember { mutableStateOf(initialTextColor) }
    var fontSize by remember { mutableFloatStateOf(initialFontSizeSp.coerceIn(10f, 48f)) }
    var isBold by remember { mutableStateOf(initialIsBold) }
    var isItalic by remember { mutableStateOf(initialIsItalic) }
    var isUnderline by remember { mutableStateOf(initialIsUnderline) }
    var fontFamilyType by remember { mutableStateOf(initialFontFamily) }
    var alignment by remember { mutableStateOf(initialAlignment) }

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val presetColors = listOf(
        initialTextColor, // Auto detected ink
        Color(0xFF111827), // Deep Black
        Color(0xFF374151), // Charcoal Gray
        Color(0xFF1E3A8A), // Navy Blue
        Color(0xFF2563EB), // Royal Blue
        Color(0xFFDC2626), // Crimson Red
        Color(0xFF059669), // Emerald Green
        Color(0xFFD97706), // Amber
        Color(0xFF7C3AED), // Purple
        Color(0xFFF8FAFC)  // White/Light
    ).distinct()

    val composeFontFamily = when (fontFamilyType) {
        "SERIF" -> FontFamily.Serif
        "MONO" -> FontFamily.Monospace
        else -> FontFamily.Default
    }

    val composeTextAlign = when (alignment) {
        "CENTER" -> TextAlign.Center
        "RIGHT" -> TextAlign.Right
        else -> TextAlign.Left
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF18181B),
            border = BorderStroke(1.dp, Color(0xFF27272A)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .testTag("dialog_inplace_text_editor")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header: Title & Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Edit Document Text",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF00C48C).copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "In-Place",
                                    color = Color(0xFF00C48C),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = "CamScanner replacement & auto inpaint",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (isPreviouslyEdited && onResetToOriginal != null) {
                            IconButton(
                                onClick = onResetToOriginal,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.RestartAlt,
                                    contentDescription = "Restore Original",
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(initialText))
                                android.widget.Toast.makeText(context, "Copied original text", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy Text",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Live Preview Card (Shows exact look on document paper)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFF8FAFC))
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "LIVE DOCUMENT PREVIEW",
                        color = Color(0xFF94A3B8),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (editedText.isNotBlank()) editedText else "(Empty text)",
                        color = selectedColor,
                        fontSize = fontSize.coerceIn(12f, 26f).sp,
                        fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
                        textDecoration = if (isUnderline) TextDecoration.Underline else TextDecoration.None,
                        fontFamily = composeFontFamily,
                        textAlign = composeTextAlign,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // AI Assist Action Chips
                val coroutineScope = rememberCoroutineScope()
                var isAiProcessing by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFF00C48C), modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("AI:", color = Color(0xFF00C48C), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    if (isAiProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = Color(0xFF00C48C),
                            strokeWidth = 2.dp
                        )
                        Text("Enhancing...", color = Color(0xFF94A3B8), fontSize = 11.sp)
                    } else {
                        val aiTools = listOf(
                            "Fix Typos" to "fix all spelling and OCR typos precisely",
                            "Format" to "clean up formatting and spacing",
                            "বাংলায় অনুবাদ" to "translate to Bengali",
                            "To English" to "translate to English"
                        )
                        aiTools.forEach { (label, action) ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF27272A),
                                border = BorderStroke(1.dp, Color(0xFF3F3F46)),
                                modifier = Modifier.clickable {
                                    if (editedText.isNotBlank()) {
                                        isAiProcessing = true
                                        coroutineScope.launch {
                                            val improved = AiOrchestrator.editTextAi(editedText, action, context)
                                            if (improved.isNotBlank()) {
                                                editedText = improved
                                            }
                                            isAiProcessing = false
                                        }
                                    }
                                }
                            ) {
                                Text(
                                    label,
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 10.5.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Text Edit Field
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    label = { Text("Text Content", color = Color(0xFF94A3B8), fontSize = 12.sp) },
                    trailingIcon = {
                        if (editedText.isNotEmpty()) {
                            IconButton(onClick = { editedText = "" }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00C48C),
                        unfocusedBorderColor = Color(0xFF3F3F46),
                        focusedContainerColor = Color(0xFF27272A),
                        unfocusedContainerColor = Color(0xFF27272A)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 150.dp)
                        .testTag("input_edit_text_content"),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Font Size Controls & Stepper
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Size", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium)

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF27272A),
                        border = BorderStroke(1.dp, Color(0xFF3F3F46)),
                        modifier = Modifier.clickable { fontSize = (fontSize - 1f).coerceAtLeast(10f) }
                    ) {
                        Text("-", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
                    }

                    Slider(
                        value = fontSize,
                        onValueChange = { fontSize = it },
                        valueRange = 10f..44f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00C48C),
                            activeTrackColor = Color(0xFF00C48C),
                            inactiveTrackColor = Color(0xFF334155)
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF27272A),
                        border = BorderStroke(1.dp, Color(0xFF3F3F46)),
                        modifier = Modifier.clickable { fontSize = (fontSize + 1f).coerceAtMost(44f) }
                    ) {
                        Text("+", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp))
                    }

                    Text(
                        "${fontSize.toInt()}sp",
                        color = Color(0xFF00C48C),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Font Family Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Font:", color = Color(0xFF94A3B8), fontSize = 11.sp)

                    listOf(
                        "DEFAULT" to "Sans",
                        "SERIF" to "Serif",
                        "MONO" to "Mono"
                    ).forEach { (key, label) ->
                        val isSel = fontFamilyType == key
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) Color(0xFF00C48C).copy(alpha = 0.2f) else Color(0xFF27272A),
                            border = BorderStroke(1.dp, if (isSel) Color(0xFF00C48C) else Color(0xFF3F3F46)),
                            modifier = Modifier.clickable { fontFamilyType = key }
                        ) {
                            Text(
                                text = label,
                                color = if (isSel) Color(0xFF00C48C) else Color(0xFF94A3B8),
                                fontSize = 11.5.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Styling & Alignment Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Style Chips (B / I / U)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            Triple("B", isBold, { isBold = !isBold }),
                            Triple("I", isItalic, { isItalic = !isItalic }),
                            Triple("U", isUnderline, { isUnderline = !isUnderline })
                        ).forEach { (lbl, active, toggle) ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (active) Color(0xFF00C48C).copy(alpha = 0.25f) else Color(0xFF27272A),
                                border = BorderStroke(1.dp, if (active) Color(0xFF00C48C) else Color(0xFF3F3F46)),
                                modifier = Modifier.clickable { toggle() }
                            ) {
                                Text(
                                    text = lbl,
                                    fontWeight = if (lbl == "B") FontWeight.Bold else FontWeight.Normal,
                                    fontStyle = if (lbl == "I") FontStyle.Italic else FontStyle.Normal,
                                    textDecoration = if (lbl == "U") TextDecoration.Underline else TextDecoration.None,
                                    color = if (active) Color(0xFF00C48C) else Color(0xFFE2E8F0),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    // Alignment Icons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .background(Color(0xFF27272A), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF3F3F46), RoundedCornerShape(8.dp))
                            .padding(2.dp)
                    ) {
                        listOf(
                            Triple("LEFT", Icons.Default.FormatAlignLeft, "Left"),
                            Triple("CENTER", Icons.Default.FormatAlignCenter, "Center"),
                            Triple("RIGHT", Icons.Default.FormatAlignRight, "Right")
                        ).forEach { (alignKey, icon, desc) ->
                            val isSel = alignment == alignKey
                            IconButton(
                                onClick = { alignment = alignKey },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    icon,
                                    contentDescription = desc,
                                    tint = if (isSel) Color(0xFF00C48C) else Color(0xFF94A3B8),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Ink Color Palette
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Ink Color", color = Color(0xFF94A3B8), fontSize = 11.5.sp)
                    if (selectedColor == initialTextColor) {
                        Text("🎯 Auto-Detected Ink", color = Color(0xFF00C48C), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    presetColors.forEach { c ->
                        val isSelected = selectedColor == c
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF00C48C) else Color(0xFF52525B),
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = c }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Erase text button
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFEF4444).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.3f)),
                        modifier = Modifier
                            .clickable { onEraseFromDocument() }
                            .testTag("btn_erase_text_from_doc")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.CleaningServices,
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Erase Text", color = Color(0xFFEF4444), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF27272A),
                            border = BorderStroke(1.dp, Color(0xFF3F3F46)),
                            modifier = Modifier.clickable { onDismiss() }
                        ) {
                            Text("Cancel", color = Color(0xFFE2E8F0), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF00C48C),
                            modifier = Modifier
                                .clickable {
                                    if (editedText.isNotBlank()) {
                                        onApplyEdit(
                                            editedText,
                                            selectedColor,
                                            fontSize,
                                            isBold,
                                            isItalic,
                                            isUnderline,
                                            fontFamilyType,
                                            alignment
                                        )
                                    }
                                }
                                .testTag("btn_apply_edit_text")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "Replace In-Place",
                                    color = Color.Black,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
