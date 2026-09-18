package com.docscan.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.docscan.data.db.AppDatabase
import com.docscan.data.model.DocumentEntity
import com.docscan.data.model.FilterType
import com.docscan.data.model.IdCardType
import com.docscan.data.model.MainScanMode
import com.docscan.data.model.PageEntity
import com.docscan.data.model.ParsedBarcode
import com.docscan.data.model.QrHistoryEntity
import com.docscan.data.model.QrScanSubMode
import com.docscan.data.model.ScanMode
import com.docscan.data.model.ScannerFeatureMode
import com.docscan.data.repository.DocumentRepository
import com.docscan.data.repository.QrHistoryRepository
import com.docscan.util.AiOrchestrator
import com.docscan.util.AutoOrientationHelper
import com.docscan.util.BarcodeAnalyzerHelper
import com.docscan.util.DocxExporter
import com.docscan.util.ExcelExporter
import com.docscan.util.EdgeDetector
import com.docscan.util.FileUtils
import com.docscan.util.GeminiAiService
import com.docscan.util.ImageProcessor
import com.docscan.util.MlKitDocumentScannerHelper
import com.docscan.util.NetworkStatus
import com.docscan.util.NetworkUtils
import com.docscan.util.NotificationHelper
import com.docscan.util.PdfExportConfig
import com.docscan.util.PdfExporter
import com.docscan.util.SampleDocGenerator
import com.docscan.util.TextRecognizerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TempScannedPage(
    val originalPath: String,
    var processedPath: String = "",
    var corners: List<Offset> = listOf(
        Offset(0.05f, 0.05f),
        Offset(0.95f, 0.05f),
        Offset(0.95f, 0.95f),
        Offset(0.05f, 0.95f)
    ),
    var filterType: FilterType = FilterType.MAGIC_COLOR,
    var brightness: Float = 0f,
    var contrast: Float = 1f,
    var rotationDegrees: Int = 0,
    var watermarkText: String? = null,
    var watermarkOpacity: Float = 0.35f,
    var watermarkColor: Long = 0xFF555555,
    var label: String = "Page"
)

class ScannerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DocumentRepository
    val context = application.applicationContext
    private var documentDetailsJob: Job? = null

    init {
        val db = AppDatabase.getInstance(application)
        repository = DocumentRepository(db)
    }

    val allDocuments: Flow<List<DocumentEntity>> = repository.allDocuments

    // Home Screen States
    val selectedFolder = MutableStateFlow("All")
    val searchQuery = MutableStateFlow("")
    val isGridView = MutableStateFlow(false)
    val selectedDocIds = MutableStateFlow<Set<Long>>(emptySet())
    val isSelectionMode = MutableStateFlow(false)

    // Page Selection States for DocumentDetailScreen
    val selectedPageIds = MutableStateFlow<Set<Long>>(emptySet())
    val isPageSelectionMode = MutableStateFlow(false)

    // Active Scan Session
    val sessionDocumentTitle = MutableStateFlow("ID Card " + SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.getDefault()).format(Date()))
    val selectedPreviewCardIndex = MutableStateFlow(0) // 0 = Front, 1 = Back


    val documentsList: StateFlow<List<DocumentEntity>> = combine(
        selectedFolder,
        searchQuery
    ) { folder, query ->
        Pair(folder, query)
    }.flatMapLatest { (folder, query) ->
        if (query.isNotBlank()) {
            if (folder != "All" && folder != "Starred") {
                repository.searchDocuments(query).map { list -> list.filter { it.folder == folder } }
            } else if (folder == "Starred") {
                repository.searchDocuments(query).map { list -> list.filter { it.isStarred } }
            } else {
                repository.searchDocuments(query)
            }
        } else {
            repository.getDocumentsByFolder(folder)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Current Scan Capture Session State
    val scanMode = MutableStateFlow(ScanMode.SINGLE)
    val activeFeatureMode = MutableStateFlow(ScannerFeatureMode.SCAN)
    val isAutoCaptureEnabled = MutableStateFlow(false)
    val isMagicEnhanceEnabled = MutableStateFlow(true)
    val isHdModeEnabled = MutableStateFlow(true)
    val isAutoCropEnabled = MutableStateFlow(true)
    val showGridGuidelines = MutableStateFlow(false)
    val capturedPages = MutableStateFlow<List<TempScannedPage>>(emptyList())
    val currentCropPageIndex = MutableStateFlow(0)

    // QR & Barcode Scanner System States
    val qrHistoryRepository = QrHistoryRepository(application)
    val mainScanMode = MutableStateFlow(MainScanMode.DOCUMENT)
    val isQrOnlyMode = MutableStateFlow(false)
    val qrScanSubMode = MutableStateFlow(QrScanSubMode.SINGLE)
    val isWideBarcodeMode = MutableStateFlow(false)
    val activeScannedBarcode = MutableStateFlow<ParsedBarcode?>(null)
    val continuousScannedList = MutableStateFlow<List<ParsedBarcode>>(emptyList())
    val showQrHistorySheet = MutableStateFlow(false)
    val showCreateQrSheet = MutableStateFlow(false)
    val galleryParsedCodes = MutableStateFlow<List<ParsedBarcode>>(emptyList())
    val showGalleryMultipleCodesDialog = MutableStateFlow(false)
    val isTorchActive = MutableStateFlow(false)

    val qrHistoryList: StateFlow<List<QrHistoryEntity>> = qrHistoryRepository.allHistory
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun onBarcodeDetected(parsed: ParsedBarcode) {
        if (qrScanSubMode.value == QrScanSubMode.SINGLE) {
            if (activeScannedBarcode.value == null) {
                activeScannedBarcode.value = parsed
                viewModelScope.launch {
                    qrHistoryRepository.saveScan(parsed)
                }
            }
        } else {
            // Continuous batch scan mode - debounce duplicates within 3s
            val currentList = continuousScannedList.value
            val isDuplicate = currentList.any { it.rawValue == parsed.rawValue }
            if (!isDuplicate) {
                continuousScannedList.value = currentList + parsed
                viewModelScope.launch {
                    qrHistoryRepository.saveScan(parsed)
                }
            }
        }
    }

    fun clearContinuousScannedList() {
        continuousScannedList.value = emptyList()
    }

    fun onScanAgain() {
        activeScannedBarcode.value = null
    }

    fun processGalleryBarcodeBitmap(bitmap: Bitmap) {
        BarcodeAnalyzerHelper.analyzeBitmap(
            bitmap = bitmap,
            onSuccess = { barcodes ->
                if (barcodes.isEmpty()) {
                    Toast.makeText(context, "No QR Code or Barcode found in this image", Toast.LENGTH_LONG).show()
                } else if (barcodes.size == 1) {
                    val code = barcodes.first()
                    activeScannedBarcode.value = code
                    viewModelScope.launch {
                        qrHistoryRepository.saveScan(code)
                    }
                } else {
                    galleryParsedCodes.value = barcodes
                    showGalleryMultipleCodesDialog.value = true
                }
            },
            onFailure = {
                Toast.makeText(context, "Failed to analyze image: ${it.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // ID Card mode temporary buffers & options
    val selectedIdCardType = MutableStateFlow(IdCardType.BANK_CARD)
    val showIdCardIntro = MutableStateFlow(true)
    val idCardFront = MutableStateFlow<Bitmap?>(null)
    val idCardBack = MutableStateFlow<Bitmap?>(null)
    val idCardStep = MutableStateFlow(1) // 1 = Front, 2 = Back

    // Active Document Detail State
    val activeDocument = MutableStateFlow<DocumentEntity?>(null)
    val activeDocumentPages = MutableStateFlow<List<PageEntity>>(emptyList())

    // UI Loading indicator
    val isProcessing = MutableStateFlow(false)

    // Real-time Internet Connectivity State
    val isOnline: StateFlow<Boolean> = NetworkUtils.observeNetwork(application)
        .map { it == NetworkStatus.Available }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = NetworkUtils.isOnline(application)
        )

    fun checkIsOnline(): Boolean = NetworkUtils.isOnline(context)

    fun setFolder(folder: String) {
        selectedFolder.value = folder
    }

    fun setSearch(query: String) {
        searchQuery.value = query
    }

    fun toggleGridView() {
        isGridView.value = !isGridView.value
    }

    fun toggleDocSelection(id: Long) {
        val current = selectedDocIds.value.toMutableSet()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        selectedDocIds.value = current
        isSelectionMode.value = current.isNotEmpty()
    }

    fun selectAllDocuments(docs: List<DocumentEntity>) {
        if (selectedDocIds.value.size == docs.size && docs.isNotEmpty()) {
            selectedDocIds.value = emptySet()
            isSelectionMode.value = false
        } else {
            selectedDocIds.value = docs.map { it.id }.toSet()
            isSelectionMode.value = true
        }
    }

    fun clearSelection() {
        selectedDocIds.value = emptySet()
        isSelectionMode.value = false
    }

    fun deleteSelectedDocuments() {
        val ids = selectedDocIds.value.toList()
        viewModelScope.launch {
            repository.deleteDocuments(ids)
            clearSelection()
        }
    }

    fun moveSelectedDocuments(folder: String) {
        val ids = selectedDocIds.value.toList()
        viewModelScope.launch {
            ids.forEach { id ->
                val doc = repository.getDocumentById(id)
                if (doc != null) {
                    repository.updateDocument(doc.copy(folder = folder))
                }
            }
            clearSelection()
            Toast.makeText(context, "Moved ${ids.size} document(s) to $folder", Toast.LENGTH_SHORT).show()
        }
    }

    fun mergeSelectedDocuments(mergedTitle: String, folder: String = "Business", onComplete: (Long) -> Unit) {
        val ids = selectedDocIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            try {
                val newDocId = repository.mergeDocumentsIntoNew(mergedTitle, folder, ids, context)
                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                    clearSelection()
                    Toast.makeText(context, "Merged into $mergedTitle", Toast.LENGTH_SHORT).show()
                    onComplete(newDocId)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                    Toast.makeText(context, "Merge failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // --- PAGE SELECTION & REORDER ACTIONS ---

    fun togglePageSelection(pageId: Long) {
        val current = selectedPageIds.value.toMutableSet()
        if (current.contains(pageId)) {
            current.remove(pageId)
        } else {
            current.add(pageId)
        }
        selectedPageIds.value = current
        isPageSelectionMode.value = current.isNotEmpty()
    }

    fun selectAllPages(pages: List<PageEntity>) {
        if (selectedPageIds.value.size == pages.size && pages.isNotEmpty()) {
            selectedPageIds.value = emptySet()
            isPageSelectionMode.value = false
        } else {
            selectedPageIds.value = pages.map { it.id }.toSet()
            isPageSelectionMode.value = true
        }
    }

    fun clearPageSelection() {
        selectedPageIds.value = emptySet()
        isPageSelectionMode.value = false
    }

    fun deleteSelectedPages(documentId: Long) {
        val ids = selectedPageIds.value
        val pagesToDelete = activeDocumentPages.value.filter { ids.contains(it.id) }
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            repository.deletePages(pagesToDelete)
            loadDocumentDetails(documentId)
            withContext(Dispatchers.Main) {
                isProcessing.value = false
                clearPageSelection()
                Toast.makeText(context, "Deleted ${pagesToDelete.size} page(s)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun extractSelectedPages(
        documentId: Long,
        extractedTitle: String,
        folder: String = "Business",
        onComplete: (Long) -> Unit
    ) {
        val ids = selectedPageIds.value
        val pagesToExtract = activeDocumentPages.value.filter { ids.contains(it.id) }
        if (pagesToExtract.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            try {
                val newDocId = repository.extractPagesToNewDocument(extractedTitle, folder, pagesToExtract, context)
                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                    clearPageSelection()
                    Toast.makeText(context, "Extracted ${pagesToExtract.size} page(s) to new document", Toast.LENGTH_SHORT).show()
                    onComplete(newDocId)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                    Toast.makeText(context, "Extraction failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Dedicated extraction method for PDF Extract / Extract PDF Pages tool
     */
    suspend fun extractPagesDirectly(
        sourceDoc: DocumentEntity,
        pagesToExtract: List<PageEntity>,
        customTitle: String? = null
    ): Long = withContext(Dispatchers.IO) {
        val title = customTitle?.takeIf { it.isNotBlank() } ?: "${sourceDoc.title} - Extracted"
        val folder = sourceDoc.folder
        val newDocId = repository.extractPagesToNewDocument(title, folder, pagesToExtract, context)
        newDocId
    }

    fun reorderPages(documentId: Long, reorderedPages: List<PageEntity>) {
        activeDocumentPages.value = reorderedPages
        viewModelScope.launch(Dispatchers.IO) {
            repository.reorderPages(documentId, reorderedPages)
            loadDocumentDetails(documentId)
        }
    }

    fun shareSelectedPagesPdf(docTitle: String, pages: List<PageEntity>) {
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            val title = "$docTitle (Selected)"
            val pdfFile = com.docscan.util.PdfExporter.generatePdf(context, title, pages, com.docscan.util.PdfExportConfig(title = title))
            isProcessing.value = false
            withContext(Dispatchers.Main) {
                if (pdfFile != null) {
                    com.docscan.util.PdfExporter.sharePdf(context, pdfFile)
                } else {
                    Toast.makeText(context, "Failed to generate PDF for selected pages", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun shareSelectedPagesImages(docTitle: String, pages: List<PageEntity>) {
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            val files = pages.mapNotNull { page ->
                val f = java.io.File(page.processedImagePath)
                if (f.exists()) f else null
            }
            isProcessing.value = false
            withContext(Dispatchers.Main) {
                if (files.isNotEmpty()) {
                    FileUtils.shareImageFiles(context, files, "$docTitle (Selected)")
                } else {
                    Toast.makeText(context, "No page images found to share", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun rotateSelectedPages(documentId: Long) {
        val ids = selectedPageIds.value
        val pagesToRotate = activeDocumentPages.value.filter { ids.contains(it.id) }
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            pagesToRotate.forEach { page ->
                val bmp = FileUtils.loadBitmap(page.processedImagePath)
                if (bmp != null) {
                    val rotatedBitmap = FileUtils.rotateBitmap(bmp, 90f)
                    val newPath = FileUtils.saveBitmapToDocStorage(context, rotatedBitmap, "PAGE_ROT")
                    repository.updatePage(page.copy(processedImagePath = newPath, originalImagePath = newPath))
                }
            }
            loadDocumentDetails(documentId)
            withContext(Dispatchers.Main) {
                isProcessing.value = false
                Toast.makeText(context, "Rotated ${pagesToRotate.size} page(s)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun toggleStar(doc: DocumentEntity) {
        viewModelScope.launch {
            repository.updateDocument(doc.copy(isStarred = !doc.isStarred))
        }
    }

    fun renameDocument(doc: DocumentEntity, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            repository.updateDocument(doc.copy(title = newTitle.trim()))
            if (activeDocument.value?.id == doc.id) {
                activeDocument.value = doc.copy(title = newTitle.trim())
            }
        }
    }

    fun moveDocumentToFolder(doc: DocumentEntity, newFolder: String) {
        viewModelScope.launch {
            repository.updateDocument(doc.copy(folder = newFolder))
            if (activeDocument.value?.id == doc.id) {
                activeDocument.value = doc.copy(folder = newFolder)
            }
        }
    }

    /**
     * Remembers the exported .docx path on the source document, so the app's own Word reader
     * (like CamScanner's) can reopen it later straight from the library — no re-conversion,
     * no external app needed.
     */
    fun attachWordExport(documentId: Long?, filePath: String) {
        if (documentId == null || documentId == 0L) return
        viewModelScope.launch {
            val doc = repository.getDocumentById(documentId) ?: return@launch
            val updated = doc.copy(wordFilePath = filePath)
            repository.updateDocument(updated)
            if (activeDocument.value?.id == documentId) {
                activeDocument.value = updated
            }
        }
    }

    /**
     * Remembers the exported .xlsx path on the source document, so the app's own Excel reader
     * can reopen it later straight from the library.
     */
    fun attachExcelExport(documentId: Long?, filePath: String) {
        if (documentId == null || documentId == 0L) return
        viewModelScope.launch {
            val doc = repository.getDocumentById(documentId) ?: return@launch
            val updated = doc.copy(excelFilePath = filePath)
            repository.updateDocument(updated)
            if (activeDocument.value?.id == documentId) {
                activeDocument.value = updated
            }
        }
    }

    fun deleteDocument(doc: DocumentEntity) {
        viewModelScope.launch {
            repository.deleteDocument(doc.id)
        }
    }

    // --- CAMERA CAPTURE FLOW ---

    fun onImageCaptured(
        bitmap: Bitmap,
        customCorners: List<Offset>? = null,
        onNavigateToCrop: () -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val detectedCorners = if (scanMode.value == ScanMode.ID_CARD) {
                customCorners ?: EdgeDetector.idCardFrameCorners()
            } else {
                customCorners ?: EdgeDetector.detectDocumentCorners(bitmap)
            }
            val initialFilter = if (isMagicEnhanceEnabled.value) FilterType.MAGIC_COLOR else FilterType.ORIGINAL
            val tempPath = FileUtils.saveBitmapToTemp(context, bitmap)
            val newPage = TempScannedPage(
                originalPath = tempPath,
                corners = detectedCorners,
                filterType = initialFilter
            )

            when (scanMode.value) {
                ScanMode.SINGLE -> {
                    capturedPages.value = listOf(newPage)
                    currentCropPageIndex.value = 0
                    withContext(Dispatchers.Main) {
                        onNavigateToCrop()
                    }
                }
                ScanMode.BATCH -> {
                    val list = capturedPages.value.toMutableList()
                    list.add(newPage)
                    capturedPages.value = list
                }
                ScanMode.ID_CARD -> {
                    val cardCorners = customCorners ?: EdgeDetector.idCardFrameCorners()
                    val cardType = selectedIdCardType.value

                    if (!cardType.isTwoSided) {
                        // Single-sided ID (e.g. Passport)
                        val passPath = FileUtils.saveBitmapToTemp(context, bitmap)
                        val passPage = TempScannedPage(
                            originalPath = passPath,
                            corners = cardCorners,
                            filterType = initialFilter,
                            watermarkText = null,
                            label = "${cardType.title} Page"
                        )
                        capturedPages.value = listOf(passPage)
                        currentCropPageIndex.value = 0
                        idCardStep.value = 1
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "${cardType.title} scan completed! Adjust crop and filters.", Toast.LENGTH_SHORT).show()
                            onNavigateToCrop()
                        }
                    } else if (idCardStep.value == 1) {
                        idCardFront.value = bitmap
                        val frontPath = FileUtils.saveBitmapToTemp(context, bitmap)
                        val frontPage = TempScannedPage(
                            originalPath = frontPath,
                            corners = cardCorners,
                            filterType = initialFilter,
                            watermarkText = null,
                            label = "Front Side (${cardType.title})"
                        )
                        capturedPages.value = listOf(frontPage)
                        idCardStep.value = 2
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Front side scanned! Now scan the back side.", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        idCardBack.value = bitmap
                        val backPath = FileUtils.saveBitmapToTemp(context, bitmap)
                        val backPage = TempScannedPage(
                            originalPath = backPath,
                            corners = cardCorners,
                            filterType = initialFilter,
                            watermarkText = null,
                            label = "Back Side (${cardType.title})"
                        )
                        val currentList = capturedPages.value.toMutableList()
                        if (currentList.isNotEmpty()) {
                            currentList.add(backPage)
                            capturedPages.value = currentList
                        } else {
                            val frontBmp = idCardFront.value ?: bitmap
                            val frontPath = FileUtils.saveBitmapToTemp(context, frontBmp)
                            capturedPages.value = listOf(
                                TempScannedPage(
                                    originalPath = frontPath,
                                    corners = cardCorners,
                                    filterType = initialFilter,
                                    watermarkText = null,
                                    label = "Front Side (${cardType.title})"
                                ),
                                backPage
                            )
                        }
                        currentCropPageIndex.value = 0
                        idCardStep.value = 1
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Both sides captured! Adjust crop and filters.", Toast.LENGTH_SHORT).show()
                            onNavigateToCrop()
                        }
                    }
                }
            }
        }
    }

    fun onMultipleImagesImported(bitmaps: List<Bitmap>, onNavigateToCrop: () -> Unit) {
        if (bitmaps.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val newPages = mutableListOf<TempScannedPage>()
            for (bitmap in bitmaps) {
                val detectedCorners = EdgeDetector.detectDocumentCorners(bitmap)
                val tempPath = FileUtils.saveBitmapToTemp(context, bitmap)
                newPages.add(
                    TempScannedPage(
                        originalPath = tempPath,
                        corners = detectedCorners
                    )
                )
            }

            val isIdMode = (scanMode.value == ScanMode.ID_CARD || activeFeatureMode.value == ScannerFeatureMode.ID_CARDS)

            if (isIdMode && newPages.size >= 2) {
                newPages[0] = newPages[0].copy(label = "Front Side")
                newPages[1] = newPages[1].copy(label = "Back Side")
                capturedPages.value = newPages.take(2)
                currentCropPageIndex.value = 0
                scanMode.value = ScanMode.ID_CARD
                activeFeatureMode.value = ScannerFeatureMode.ID_CARDS
                sessionDocumentTitle.value = "NID Card " + SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.getDefault()).format(Date())
                idCardStep.value = 1
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "2 card sides selected! Adjust crop and confirm.", Toast.LENGTH_SHORT).show()
                    onNavigateToCrop()
                }
            } else if (isIdMode && newPages.size == 1) {
                if (idCardStep.value == 1 && capturedPages.value.isEmpty()) {
                    val p0 = newPages[0].copy(label = "Front Side")
                    capturedPages.value = listOf(p0)
                    idCardStep.value = 2
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Front side imported! Now select or scan the back side.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    val p1 = newPages[0].copy(label = "Back Side")
                    val existing = capturedPages.value.toMutableList()
                    if (existing.isNotEmpty()) {
                        existing.add(p1)
                        capturedPages.value = existing.take(2)
                    } else {
                        capturedPages.value = listOf(p1)
                    }
                    idCardStep.value = 1
                    currentCropPageIndex.value = 0
                    scanMode.value = ScanMode.ID_CARD
                    activeFeatureMode.value = ScannerFeatureMode.ID_CARDS
                    sessionDocumentTitle.value = "NID Card " + SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.getDefault()).format(Date())
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Both sides ready! Adjust crop and confirm.", Toast.LENGTH_SHORT).show()
                        onNavigateToCrop()
                    }
                }
            } else if (newPages.size > 1) {
                scanMode.value = ScanMode.BATCH
                val list = capturedPages.value.toMutableList()
                list.addAll(newPages)
                capturedPages.value = list
                currentCropPageIndex.value = 0
                withContext(Dispatchers.Main) {
                    onNavigateToCrop()
                }
            } else if (scanMode.value == ScanMode.SINGLE) {
                capturedPages.value = newPages
                currentCropPageIndex.value = 0
                withContext(Dispatchers.Main) {
                    onNavigateToCrop()
                }
            } else {
                val list = capturedPages.value.toMutableList()
                list.addAll(newPages)
                capturedPages.value = list
                withContext(Dispatchers.Main) {
                    onNavigateToCrop()
                }
            }
        }
    }

    fun autoDetectCurrentPageCrop() {
        val index = currentCropPageIndex.value
        val list = capturedPages.value
        if (index in list.indices) {
            val page = list[index]
            viewModelScope.launch(Dispatchers.IO) {
                val bmp = FileUtils.loadBitmap(page.originalPath) ?: return@launch
                val detected = EdgeDetector.detectDocumentCorners(bmp)
                withContext(Dispatchers.Main) {
                    updateCurrentPageCrop(detected)
                    Toast.makeText(context, "Document edges auto-detected", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * AI-Powered high precision Document Edge & Perspective Detection using Gemini Vision
     */
    fun aiDetectCurrentPageCrop(onComplete: (() -> Unit)? = null) {
        val index = currentCropPageIndex.value
        val list = capturedPages.value
        if (index in list.indices) {
            val page = list[index]
            viewModelScope.launch(Dispatchers.IO) {
                isProcessing.value = true
                val bmp = FileUtils.loadBitmap(page.originalPath)
                if (bmp == null) {
                    isProcessing.value = false
                    return@launch
                }
                val detected = AiOrchestrator.detectDocumentEdgesAi(bmp, context)
                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                    if (detected != null) {
                        updateCurrentPageCrop(detected)
                        Toast.makeText(context, "✨ AI Document edge detection successful!", Toast.LENGTH_SHORT).show()
                    } else {
                        autoDetectCurrentPageCrop()
                    }
                    onComplete?.invoke()
                }
            }
        }
    }

    /**
     * AI-Powered Multi-language Text OCR Extraction (Bengali, English, etc.)
     */
    fun extractTextWithAi(page: PageEntity, preferredLanguage: String = "auto", onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            val bmp = FileUtils.loadBitmap(page.processedImagePath) ?: FileUtils.loadBitmap(page.originalImagePath)
            val extracted = if (bmp != null) {
                AiOrchestrator.extractTextAi(bmp, preferredLanguage)
            } else {
                page.extractedText ?: ""
            }
            if (extracted.isNotBlank() && extracted != page.extractedText) {
                repository.updatePage(page.copy(extractedText = extracted))
            }
            withContext(Dispatchers.Main) {
                isProcessing.value = false
                onResult(extracted)
            }
        }
    }

    /**
     * AI-Powered Document Translation
     */
    fun translateWithAi(text: String, targetLanguage: String, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            val translated = AiOrchestrator.translateTextAi(text, targetLanguage)
            withContext(Dispatchers.Main) {
                isProcessing.value = false
                onResult(translated)
            }
        }
    }

    /**
     * AI-Powered Document to Word Structure Extractor
     */
    fun convertToWordWithAi(page: PageEntity, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            val bmp = FileUtils.loadBitmap(page.processedImagePath) ?: FileUtils.loadBitmap(page.originalImagePath)
            val wordText = if (bmp != null) {
                AiOrchestrator.convertToWordAi(bmp, page.extractedText)
            } else {
                page.extractedText ?: ""
            }
            withContext(Dispatchers.Main) {
                isProcessing.value = false
                onResult(wordText)
            }
        }
    }

    /**
     * AI-Powered Document to Excel Table Extractor (CSV)
     */
    fun convertToExcelWithAi(page: PageEntity, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            val bmp = FileUtils.loadBitmap(page.processedImagePath) ?: FileUtils.loadBitmap(page.originalImagePath)
            val csvData = if (bmp != null) {
                AiOrchestrator.convertToExcelAi(bmp, page.extractedText)
            } else {
                val raw = page.extractedText ?: ""
                "Index,Field,Value\n1,Page Text,\"${raw.replace("\"", "\"\"")}\""
            }
            withContext(Dispatchers.Main) {
                isProcessing.value = false
                onResult(csvData)
            }
        }
    }

    /**
     * AI-Powered Document Summary
     */
    fun summarizeDocumentWithAi(text: String, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            val summary = AiOrchestrator.summarizeDocumentAi(text)
            withContext(Dispatchers.Main) {
                isProcessing.value = false
                onResult(summary)
            }
        }
    }


    /**
     * Processes images returned from the ML Kit Document Scanner API.
     * The ML Kit Document Scanner automatically detects edges, performs perspective correction,
     * cleans shadows, and applies enhancement.
     * We import these perspective-corrected high-res images, perform on-device OCR, and save/open them.
     */
    fun processMlKitScanResult(
        scanResult: MlKitDocumentScannerHelper.ScanResult,
        targetDocumentId: Long? = null,
        isIdCardMode: Boolean = (scanMode.value == ScanMode.ID_CARD),
        onNavigateToDetail: (Long) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            try {
                val tempPages = mutableListOf<TempScannedPage>()
                val pageEntities = mutableListOf<PageEntity>()

                for ((index, uri) in scanResult.imageUris.withIndex()) {
                    val tempPath = FileUtils.copyUriToTemp(context, uri) ?: continue
                    val bitmap = FileUtils.loadBitmap(tempPath) ?: continue

                    // Document corners are already perspective-rectified by ML Kit
                    val corners = listOf(
                        Offset(0f, 0f),
                        Offset(1f, 0f),
                        Offset(1f, 1f),
                        Offset(0f, 1f)
                    )

                    // Extract on-device ML Kit OCR
                    val ocrText = try {
                        TextRecognizerHelper.extractText(bitmap)
                    } catch (e: Exception) {
                        ""
                    }

                    val finalDocPath = FileUtils.saveBitmapToDocStorage(context, bitmap)
                    val pageLabel = if (isIdCardMode) {
                        if (index == 0) "Front Side" else if (index == 1) "Back Side" else "Page ${index + 1}"
                    } else {
                        "Page ${index + 1}"
                    }

                    tempPages.add(
                        TempScannedPage(
                            originalPath = tempPath,
                            processedPath = finalDocPath,
                            corners = corners,
                            filterType = FilterType.ORIGINAL,
                            label = pageLabel
                        )
                    )

                    pageEntities.add(
                        PageEntity(
                            documentId = targetDocumentId ?: 0,
                            pageNumber = index + 1,
                            originalImagePath = tempPath,
                            processedImagePath = finalDocPath,
                            filterType = FilterType.ORIGINAL.name,
                            brightness = 0f,
                            contrast = 1f,
                            rotationDegrees = 0,
                            watermarkText = null,
                            extractedText = ocrText.ifBlank { null },
                            notes = if (isIdCardMode) pageLabel else null
                        )
                    )
                }

                if (pageEntities.isNotEmpty()) {
                    if (targetDocumentId != null && targetDocumentId > 0) {
                        repository.addPagesToDocument(targetDocumentId, pageEntities)
                        loadDocumentDetails(targetDocumentId)
                        withContext(Dispatchers.Main) {
                            isProcessing.value = false
                            Toast.makeText(context, "Added ${pageEntities.size} page(s) with ML Kit", Toast.LENGTH_SHORT).show()
                            onNavigateToDetail(targetDocumentId)
                        }
                    } else {
                        val timeStamp = SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.getDefault()).format(Date())
                        val folder = if (isIdCardMode) {
                            "ID Cards"
                        } else if (selectedFolder.value != "All" && selectedFolder.value != "Starred") {
                            selectedFolder.value
                        } else "Business"

                        val docTitle = if (isIdCardMode) "ID Card $timeStamp" else "Scan $timeStamp"

                        val newDocId = repository.saveNewDocument(docTitle, folder, pageEntities)

                        capturedPages.value = tempPages
                        currentCropPageIndex.value = 0
                        sessionDocumentTitle.value = docTitle

                        withContext(Dispatchers.Main) {
                            isProcessing.value = false
                            val modeMsg = if (isIdCardMode) "Scanned ID Card successfully" else "Scanned ${pageEntities.size} page(s) with ML Kit"
                            Toast.makeText(context, modeMsg, Toast.LENGTH_SHORT).show()
                            onNavigateToDetail(newDocId)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isProcessing.value = false
                        Toast.makeText(context, "No pages scanned", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun removeCapturedPage(index: Int) {
        val list = capturedPages.value.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            capturedPages.value = list
            if (currentCropPageIndex.value >= list.size && list.isNotEmpty()) {
                currentCropPageIndex.value = list.size - 1
            }
        }
    }

    fun onBatchCaptureFinished(onNavigateToCrop: () -> Unit) {
        if (capturedPages.value.isNotEmpty()) {
            currentCropPageIndex.value = 0
            onNavigateToCrop()
        }
    }

    fun resetScanSession() {
        capturedPages.value = emptyList()
        currentCropPageIndex.value = 0
        idCardFront.value = null
        idCardBack.value = null
        idCardStep.value = 1
        showIdCardIntro.value = true
        // Also clear any leftover NID Card mode from a previous session, otherwise
        // it silently sticks around and the NID Card front/back interface pops up
        // again the next time the user scans in Extract Text, To Word, Sign, Scan,
        // or Smart Erase mode.
        scanMode.value = ScanMode.SINGLE
        activeFeatureMode.value = ScannerFeatureMode.SCAN
        mainScanMode.value = MainScanMode.DOCUMENT
        isQrOnlyMode.value = false
        viewModelScope.launch(Dispatchers.IO) {
            FileUtils.clearTempFiles(context)
        }
    }

    /**
     * Creates and saves a new document from a list of Bitmaps (e.g. from To Excel or image conversions)
     */
    fun createDocumentFromBitmaps(
        title: String,
        bitmaps: List<Bitmap>,
        folder: String = "Business",
        onComplete: ((Long) -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            try {
                val pageEntities = mutableListOf<PageEntity>()
                for ((idx, bmp) in bitmaps.withIndex()) {
                    val finalPath = FileUtils.saveBitmapToDocStorage(context, bmp)
                    val ocrText = try {
                        TextRecognizerHelper.extractText(bmp)
                    } catch (e: Exception) {
                        ""
                    }
                    pageEntities.add(
                        PageEntity(
                            documentId = 0,
                            pageNumber = idx + 1,
                            originalImagePath = finalPath,
                            processedImagePath = finalPath,
                            filterType = FilterType.ORIGINAL.name,
                            extractedText = ocrText.ifBlank { null }
                        )
                    )
                }
                if (pageEntities.isNotEmpty()) {
                    val targetFolder = if (selectedFolder.value != "All" && selectedFolder.value != "Starred") {
                        selectedFolder.value
                    } else folder
                    val newDocId = repository.saveNewDocument(title, targetFolder, pageEntities)
                    withContext(Dispatchers.Main) {
                        isProcessing.value = false
                        onComplete?.invoke(newDocId)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isProcessing.value = false
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                    Toast.makeText(context, "Save document failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // --- CROP & FILTER PROCESSING ---

    fun updateCurrentPageCrop(corners: List<Offset>) {
        val index = currentCropPageIndex.value
        val list = capturedPages.value.toMutableList()
        if (index in list.indices) {
            list[index] = list[index].copy(corners = corners)
            capturedPages.value = list
        }
    }

    fun updateCurrentPageFilter(filter: FilterType) {
        val index = currentCropPageIndex.value
        val list = capturedPages.value.toMutableList()
        if (index in list.indices) {
            list[index] = list[index].copy(filterType = filter)
            capturedPages.value = list
        }
    }

    fun applyFilterToAllPages(filter: FilterType) {
        val list = capturedPages.value.map { it.copy(filterType = filter) }
        capturedPages.value = list
    }

    fun initIdCardSessionWithSamples(onReady: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val front = SampleDocGenerator.createSampleIdCardFront()
            val back = SampleDocGenerator.createSampleIdCardBack()

            val frontPath = FileUtils.saveBitmapToTemp(context, front, "ID_FRONT")
            val backPath = FileUtils.saveBitmapToTemp(context, back, "ID_BACK")

            val frontCorners = EdgeDetector.detectDocumentCorners(front)
            val backCorners = EdgeDetector.detectDocumentCorners(back)

            val page1 = TempScannedPage(
                originalPath = frontPath,
                corners = frontCorners,
                label = "Front Side"
            )
            val page2 = TempScannedPage(
                originalPath = backPath,
                corners = backCorners,
                label = "Back Side"
            )

            capturedPages.value = listOf(page1, page2)
            currentCropPageIndex.value = 0
            sessionDocumentTitle.value = "ID Card " + SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.getDefault()).format(Date())
            scanMode.value = ScanMode.ID_CARD

            withContext(Dispatchers.Main) {
                onReady()
            }
        }
    }

    fun rotateCurrentPageLeft() {
        val index = currentCropPageIndex.value
        val list = capturedPages.value.toMutableList()
        if (index in list.indices) {
            val page = list[index]
            val nextDeg = (page.rotationDegrees - 90 + 360) % 360
            // Transform corners for 90° CCW rotation: (x, y) -> (y, 1 - x)
            val old = page.corners
            val rotatedCorners = if (old.size == 4) {
                listOf(
                    Offset(old[1].y.coerceIn(0f, 1f), (1f - old[1].x).coerceIn(0f, 1f)), // new TL from old TR
                    Offset(old[2].y.coerceIn(0f, 1f), (1f - old[2].x).coerceIn(0f, 1f)), // new TR from old BR
                    Offset(old[3].y.coerceIn(0f, 1f), (1f - old[3].x).coerceIn(0f, 1f)), // new BR from old BL
                    Offset(old[0].y.coerceIn(0f, 1f), (1f - old[0].x).coerceIn(0f, 1f))  // new BL from old TL
                )
            } else old
            list[index] = page.copy(rotationDegrees = nextDeg, corners = rotatedCorners)
            capturedPages.value = list
        }
    }

    fun rotateCurrentPageRight() {
        val index = currentCropPageIndex.value
        val list = capturedPages.value.toMutableList()
        if (index in list.indices) {
            val page = list[index]
            val nextDeg = (page.rotationDegrees + 90) % 360
            // Transform corners for 90° CW rotation: (x, y) -> (1 - y, x)
            val old = page.corners
            val rotatedCorners = if (old.size == 4) {
                listOf(
                    Offset((1f - old[3].y).coerceIn(0f, 1f), old[3].x.coerceIn(0f, 1f)), // new TL from old BL
                    Offset((1f - old[0].y).coerceIn(0f, 1f), old[0].x.coerceIn(0f, 1f)), // new TR from old TL
                    Offset((1f - old[1].y).coerceIn(0f, 1f), old[1].x.coerceIn(0f, 1f)), // new BR from old TR
                    Offset((1f - old[2].y).coerceIn(0f, 1f), old[2].x.coerceIn(0f, 1f))  // new BL from old BR
                )
            } else old
            list[index] = page.copy(rotationDegrees = nextDeg, corners = rotatedCorners)
            capturedPages.value = list
        }
    }

    fun selectAllCurrentPageCrop() {
        val index = currentCropPageIndex.value
        val list = capturedPages.value.toMutableList()
        if (index in list.indices) {
            val allCorners = listOf(
                Offset(0f, 0f),
                Offset(1f, 0f),
                Offset(1f, 1f),
                Offset(0f, 1f)
            )
            list[index] = list[index].copy(corners = allCorners)
            capturedPages.value = list
        }
    }

    fun applyFilterToSession(filter: FilterType, pageIndex: Int = -1) {
        val list = capturedPages.value.toMutableList()
        if (pageIndex in list.indices) {
            list[pageIndex] = list[pageIndex].copy(filterType = filter)
        } else {
            // Apply to all pages in session
            for (i in list.indices) {
                list[i] = list[i].copy(filterType = filter)
            }
        }
        capturedPages.value = list
    }

    fun applyWatermarkToSession(text: String, opacity: Float, colorLong: Long, pageIndex: Int = -1) {
        val list = capturedPages.value.toMutableList()
        if (pageIndex in list.indices) {
            list[pageIndex] = list[pageIndex].copy(
                watermarkText = text.ifBlank { null },
                watermarkOpacity = opacity,
                watermarkColor = colorLong
            )
        } else {
            for (i in list.indices) {
                list[i] = list[i].copy(
                    watermarkText = text.ifBlank { null },
                    watermarkOpacity = opacity,
                    watermarkColor = colorLong
                )
            }
        }
        capturedPages.value = list
    }

    fun replacePageImage(index: Int, newBitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = FileUtils.saveBitmapToTemp(context, newBitmap, "RETAKE")
            val detected = EdgeDetector.detectDocumentCorners(newBitmap)
            val list = capturedPages.value.toMutableList()
            if (index in list.indices) {
                list[index] = list[index].copy(
                    originalPath = path,
                    corners = detected,
                    rotationDegrees = 0
                )
                capturedPages.value = list
            }
        }
    }

    fun updateCurrentPageAdjustments(brightness: Float, contrast: Float) {
        val index = currentCropPageIndex.value
        val list = capturedPages.value.toMutableList()
        if (index in list.indices) {
            list[index] = list[index].copy(brightness = brightness, contrast = contrast)
            capturedPages.value = list
        }
    }

    fun saveScannedDocument(
        folder: String = "All",
        targetDocumentId: Long? = null,
        onComplete: (Long) -> Unit
    ) {
        isProcessing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val processedPageEntities = mutableListOf<PageEntity>()

                // Check if this is a 2-sided ID Card scan session
                val isIdCardMode = (scanMode.value == ScanMode.ID_CARD)
                val isIdCardComposite = isIdCardMode && (capturedPages.value.size >= 2)
                val isPassportSingle = isIdCardMode && (capturedPages.value.size == 1)

                if (isIdCardComposite) {
                    // 1. Process Front Page (Index 0)
                    val p0 = capturedPages.value[0]
                    val raw0 = FileUtils.loadBitmap(p0.originalPath)
                    val rotated0 = raw0?.let { ImageProcessor.rotate(it, p0.rotationDegrees) } ?: raw0
                    val cropped0 = rotated0?.let { ImageProcessor.perspectiveCrop(it, p0.corners) } ?: rotated0
                    val filtered0 = cropped0?.let {
                        ImageProcessor.applyFilter(it, p0.filterType, p0.brightness, p0.contrast)
                    } ?: rotated0

                    // 2. Process Back Page (Index 1)
                    val p1 = capturedPages.value[1]
                    val raw1 = FileUtils.loadBitmap(p1.originalPath)
                    val rotated1 = raw1?.let { ImageProcessor.rotate(it, p1.rotationDegrees) } ?: raw1
                    val cropped1 = rotated1?.let { ImageProcessor.perspectiveCrop(it, p1.corners) } ?: rotated1
                    val filtered1 = cropped1?.let {
                        ImageProcessor.applyFilter(it, p1.filterType, p1.brightness, p1.contrast)
                    } ?: rotated1

                    if (filtered0 != null && filtered1 != null) {
                        // 3. Merge Front and Back onto single A4 standard sheet (matching user's reference photo)
                        var mergedA4Bitmap = ImageProcessor.mergeIdCard(filtered0, filtered1)
                        p0.watermarkText?.takeIf { it.isNotBlank() }?.let { wmText ->
                            mergedA4Bitmap = ImageProcessor.applyWatermark(
                                mergedA4Bitmap,
                                wmText,
                                p0.watermarkOpacity,
                                p0.watermarkColor
                            )
                        }

                        // 4. OCR on composite document
                        val ocrText = try {
                            TextRecognizerHelper.extractText(mergedA4Bitmap)
                        } catch (e: Exception) {
                            ""
                        }

                        // 5. Save single composite page
                        val finalPath = FileUtils.saveBitmapToDocStorage(context, mergedA4Bitmap)

                        processedPageEntities.add(
                            PageEntity(
                                documentId = targetDocumentId ?: 0,
                                pageNumber = 1,
                                originalImagePath = p0.originalPath,
                                processedImagePath = finalPath,
                                filterType = p0.filterType.name,
                                brightness = p0.brightness,
                                contrast = p0.contrast,
                                rotationDegrees = 0,
                                watermarkText = p0.watermarkText,
                                watermarkOpacity = p0.watermarkOpacity,
                                watermarkColor = p0.watermarkColor,
                                extractedText = ocrText.ifBlank { null }
                            )
                        )
                    }
                } else if (isPassportSingle) {
                    val p0 = capturedPages.value[0]
                    val raw0 = FileUtils.loadBitmap(p0.originalPath)
                    val rotated0 = raw0?.let { ImageProcessor.rotate(it, p0.rotationDegrees) } ?: raw0
                    val cropped0 = rotated0?.let { ImageProcessor.perspectiveCrop(it, p0.corners) } ?: rotated0
                    val filtered0 = cropped0?.let {
                        ImageProcessor.applyFilter(it, p0.filterType, p0.brightness, p0.contrast)
                    } ?: rotated0

                    if (filtered0 != null) {
                        var mergedA4Bitmap = ImageProcessor.mergePassport(filtered0)
                        p0.watermarkText?.takeIf { it.isNotBlank() }?.let { wmText ->
                            mergedA4Bitmap = ImageProcessor.applyWatermark(
                                mergedA4Bitmap,
                                wmText,
                                p0.watermarkOpacity,
                                p0.watermarkColor
                            )
                        }

                        val ocrText = try {
                            TextRecognizerHelper.extractText(mergedA4Bitmap)
                        } catch (e: Exception) {
                            ""
                        }

                        val finalPath = FileUtils.saveBitmapToDocStorage(context, mergedA4Bitmap)

                        processedPageEntities.add(
                            PageEntity(
                                documentId = targetDocumentId ?: 0,
                                pageNumber = 1,
                                originalImagePath = p0.originalPath,
                                processedImagePath = finalPath,
                                filterType = p0.filterType.name,
                                brightness = p0.brightness,
                                contrast = p0.contrast,
                                rotationDegrees = 0,
                                watermarkText = p0.watermarkText,
                                watermarkOpacity = p0.watermarkOpacity,
                                watermarkColor = p0.watermarkColor,
                                extractedText = ocrText.ifBlank { null }
                            )
                        )
                    }
                } else {
                    // Standard multi-page processing
                    for (tempPage in capturedPages.value) {
                        val originalBitmap = FileUtils.loadBitmap(tempPage.originalPath) ?: continue

                        // 1. Rotate (matches the orientation the crop frame was adjusted against)
                        val rotatedBitmap = ImageProcessor.rotate(originalBitmap, tempPage.rotationDegrees)

                        // 2. Perspective Crop
                        val croppedBitmap = ImageProcessor.perspectiveCrop(rotatedBitmap, tempPage.corners)

                        // 3. Filter & Tone adjustments
                        val filteredBitmap = ImageProcessor.applyFilter(
                            source = croppedBitmap,
                            filterType = tempPage.filterType,
                            brightness = tempPage.brightness,
                            contrast = tempPage.contrast
                        )

                        // 4. Watermark if present
                        val watermarkedBitmap = tempPage.watermarkText?.takeIf { it.isNotBlank() }?.let { wmText ->
                            ImageProcessor.applyWatermark(
                                source = filteredBitmap,
                                text = wmText,
                                opacity = tempPage.watermarkOpacity,
                                colorLong = tempPage.watermarkColor
                            )
                        } ?: filteredBitmap

                        // 5. Extract text via ML Kit on-device Text Recognition
                        val ocrText = try {
                            TextRecognizerHelper.extractText(watermarkedBitmap)
                        } catch (e: Exception) {
                            ""
                        }

                        // 6. Save to permanent app storage
                        val finalPath = FileUtils.saveBitmapToDocStorage(context, watermarkedBitmap)

                        processedPageEntities.add(
                            PageEntity(
                                documentId = targetDocumentId ?: 0,
                                pageNumber = processedPageEntities.size + 1,
                                originalImagePath = tempPage.originalPath,
                                processedImagePath = finalPath,
                                filterType = tempPage.filterType.name,
                                brightness = tempPage.brightness,
                                contrast = tempPage.contrast,
                                rotationDegrees = tempPage.rotationDegrees,
                                watermarkText = tempPage.watermarkText,
                                watermarkOpacity = tempPage.watermarkOpacity,
                                watermarkColor = tempPage.watermarkColor,
                                extractedText = ocrText.ifBlank { null }
                            )
                        )
                    }
                }

                if (processedPageEntities.isNotEmpty()) {
                    if (targetDocumentId != null) {
                        // Append to existing document
                        repository.addPagesToDocument(targetDocumentId, processedPageEntities)
                        loadDocumentDetails(targetDocumentId)
                        withContext(Dispatchers.Main) {
                            isProcessing.value = false
                            onComplete(targetDocumentId)
                        }
                    } else {
                        // Create new document with session title
                        val title = sessionDocumentTitle.value.ifBlank {
                            val timeStamp = SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.getDefault()).format(Date())
                            if (isIdCardComposite) "ID Card $timeStamp" else "Doc_$timeStamp"
                        }
                        val docId = repository.saveNewDocument(title, folder, processedPageEntities)
                        withContext(Dispatchers.Main) {
                            isProcessing.value = false
                            onComplete(docId)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isProcessing.value = false
                        Toast.makeText(context, "Could not process pages. Please try capturing again.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                    Toast.makeText(context, "Error processing document: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                }
            }
        }
    }

    // --- DOCUMENT DETAILS & PAGE EDITING ---

    fun loadDocumentDetails(docId: Long) {
        documentDetailsJob?.cancel()
        documentDetailsJob = viewModelScope.launch {
            val doc = repository.getDocumentById(docId)
            activeDocument.value = doc
            repository.getPagesForDocument(docId).collect { pages ->
                activeDocumentPages.value = pages
            }
        }
    }

    fun applyWatermarkToPage(page: PageEntity, text: String, opacity: Float, colorLong: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val originalBmp = FileUtils.loadBitmap(page.processedImagePath) ?: return@launch
            val watermarkedBmp = ImageProcessor.applyWatermark(
                source = originalBmp,
                text = text,
                opacity = opacity,
                colorLong = colorLong
            )
            val newPath = FileUtils.saveBitmapToDocStorage(context, watermarkedBmp, "WM")
            val updated = page.copy(
                processedImagePath = newPath,
                watermarkText = text,
                watermarkOpacity = opacity,
                watermarkColor = colorLong
            )
            repository.updatePage(updated)
            loadDocumentDetails(page.documentId)
        }
    }

    /**
     * Apply or update watermark across all pages in a document (or remove watermark)
     */
    suspend fun applyWatermarkToDocument(
        documentId: Long,
        text: String,
        opacity: Float = 0.35f,
        colorLong: Long = 0xFF888888,
        isTileMode: Boolean = true,
        sizeScale: Float = 1.0f,
        rotationDegrees: Float = -43f,
        removeWatermark: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val pages = repository.getPagesDirect(documentId)
        if (pages.isEmpty()) return@withContext

        var firstPageThumb: String? = null

        pages.forEachIndexed { index, page ->
            // Load base image: prefer originalImagePath if available to avoid recompression degradation
            val baseFile = File(page.originalImagePath)
            val baseBmp = if (baseFile.exists()) {
                FileUtils.loadBitmap(page.originalImagePath)
            } else {
                FileUtils.loadBitmap(page.processedImagePath)
            }

            if (baseBmp != null) {
                // Apply existing page filter/crop/rotation
                val filter = try {
                    FilterType.valueOf(page.filterType)
                } catch (e: Exception) {
                    FilterType.MAGIC_COLOR
                }
                var processed = ImageProcessor.applyFilter(baseBmp, filter, page.brightness, page.contrast)
                if (page.rotationDegrees % 360 != 0) {
                    processed = ImageProcessor.rotate(processed, page.rotationDegrees)
                }

                // Apply watermark if not removing
                val finalBmp = if (!removeWatermark && text.isNotBlank()) {
                    ImageProcessor.applyWatermark(
                        source = processed,
                        text = text,
                        opacity = opacity,
                        colorLong = colorLong,
                        isTileMode = isTileMode,
                        sizeScale = sizeScale,
                        rotationDegrees = rotationDegrees
                    )
                } else {
                    processed
                }

                val newPath = FileUtils.saveBitmapToDocStorage(context, finalBmp, "WM")
                if (index == 0) {
                    firstPageThumb = newPath
                }

                val updatedPage = page.copy(
                    processedImagePath = newPath,
                    watermarkText = if (removeWatermark) null else text.ifBlank { null },
                    watermarkOpacity = opacity,
                    watermarkColor = colorLong
                )
                repository.updatePage(updatedPage)
            }
        }

        // Update document thumbnail if first page was modified
        val doc = repository.getDocumentById(documentId)
        if (doc != null && firstPageThumb != null) {
            repository.updateDocument(
                doc.copy(
                    thumbnailPath = firstPageThumb!!,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

        loadDocumentDetails(documentId)
    }

    fun applySignatureToPage(page: PageEntity, signatureBitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val baseBmp = FileUtils.loadBitmap(page.processedImagePath) ?: return@launch
            val signedBmp = ImageProcessor.applySignature(baseBmp, signatureBitmap)
            val newPath = FileUtils.saveBitmapToDocStorage(context, signedBmp, "SIGN")
            val updated = page.copy(processedImagePath = newPath)
            repository.updatePage(updated)
            loadDocumentDetails(page.documentId)
        }
    }

    fun reExtractOcrForPage(page: PageEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = FileUtils.loadBitmap(page.processedImagePath) ?: return@launch
            val text = TextRecognizerHelper.extractText(bmp)
            val updated = page.copy(extractedText = text.ifBlank { null })
            repository.updatePage(updated)
            loadDocumentDetails(page.documentId)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, if (text.isNotBlank()) "Text extracted successfully" else "No text found on page", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun updatePageExtractedText(page: PageEntity, newText: String) {
        viewModelScope.launch {
            val updated = page.copy(extractedText = newText.ifBlank { null })
            repository.updatePage(updated)
            loadDocumentDetails(page.documentId)
        }
    }

    fun deletePage(page: PageEntity) {
        viewModelScope.launch {
            repository.deletePage(page)
            loadDocumentDetails(page.documentId)
        }
    }

    fun duplicatePage(page: PageEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val newPage = page.copy(
                id = 0
            )
            repository.addPagesToDocument(page.documentId, listOf(newPage))
            loadDocumentDetails(page.documentId)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Page duplicated", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun rotatePageBy90(page: PageEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentBmp = FileUtils.loadBitmap(page.processedImagePath) ?: return@launch
            val rotated = FileUtils.rotateBitmap(currentBmp, 90f)
            val newPath = FileUtils.saveBitmapToDocStorage(context, rotated, "ROT")
            val newRotation = (page.rotationDegrees + 90) % 360
            val updated = page.copy(
                processedImagePath = newPath,
                rotationDegrees = newRotation
            )
            repository.updatePage(updated)
            loadDocumentDetails(page.documentId)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Rotated 90°", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun updateDocumentTags(doc: DocumentEntity, tags: String) {
        viewModelScope.launch {
            val updated = doc.copy(tags = tags.trim())
            repository.updateDocument(updated)
            activeDocument.value = updated
            Toast.makeText(context, "Tags updated", Toast.LENGTH_SHORT).show()
        }
    }

    fun mergeDocuments(targetDoc: DocumentEntity, sourceDocId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            val sourcePages = repository.getPagesDirect(sourceDocId)
            val copiedPages = sourcePages.map { sp ->
                sp.copy(id = 0)
            }
            repository.addPagesToDocument(targetDoc.id, copiedPages)
            loadDocumentDetails(targetDoc.id)
            withContext(Dispatchers.Main) {
                isProcessing.value = false
                Toast.makeText(context, "Merged ${copiedPages.size} page(s) into document", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun emailPdfToMyself(doc: DocumentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            val pages = repository.getPagesDirect(doc.id)
            val pdfFile = PdfExporter.generatePdf(context, doc.title, pages, PdfExportConfig(title = doc.title))
            isProcessing.value = false
            withContext(Dispatchers.Main) {
                if (pdfFile != null && pdfFile.exists()) {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.provider",
                        pdfFile
                    )
                    val emailIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Scanned Document: ${doc.title}")
                        putExtra(android.content.Intent.EXTRA_TEXT, "Please find attached the scanned document: ${doc.title}\n\nGenerated with DocScanner.")
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        val chooser = android.content.Intent.createChooser(emailIntent, "Send Email").apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(chooser)
                    } catch (e: Exception) {
                        Toast.makeText(context, "No email client found", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Failed to generate PDF for email", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun printDocumentPdf(context: android.content.Context, doc: DocumentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            val pages = repository.getPagesDirect(doc.id)
            val pdfFile = PdfExporter.generatePdf(context, doc.title, pages, PdfExportConfig(title = doc.title))
            isProcessing.value = false
            withContext(Dispatchers.Main) {
                if (pdfFile != null && pdfFile.exists()) {
                    val printManager = context.getSystemService(android.content.Context.PRINT_SERVICE) as? android.print.PrintManager
                    if (printManager != null) {
                        val printAdapter = object : android.print.PrintDocumentAdapter() {
                            override fun onLayout(
                                oldAttributes: android.print.PrintAttributes?,
                                newAttributes: android.print.PrintAttributes?,
                                cancellationSignal: android.os.CancellationSignal?,
                                callback: LayoutResultCallback?,
                                extras: android.os.Bundle?
                            ) {
                                if (cancellationSignal?.isCanceled == true) {
                                    callback?.onLayoutCancelled()
                                    return
                                }
                                val pdi = android.print.PrintDocumentInfo.Builder("${doc.title}.pdf")
                                    .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                                    .build()
                                callback?.onLayoutFinished(pdi, true)
                            }

                            override fun onWrite(
                                pages: Array<out android.print.PageRange>?,
                                destination: android.os.ParcelFileDescriptor?,
                                cancellationSignal: android.os.CancellationSignal?,
                                callback: WriteResultCallback?
                            ) {
                                try {
                                    val input = java.io.FileInputStream(pdfFile)
                                    val output = java.io.FileOutputStream(destination?.fileDescriptor)
                                    input.copyTo(output)
                                    input.close()
                                    output.close()
                                    callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                                } catch (e: Exception) {
                                    callback?.onWriteFailed(e.message)
                                }
                            }
                        }
                        printManager.print(doc.title, printAdapter, android.print.PrintAttributes.Builder().build())
                    } else {
                        Toast.makeText(context, "Print service is not available", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Failed to generate document for printing", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun exportAllPagesAsImages(doc: DocumentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            val pages = repository.getPagesDirect(doc.id)
            val files = pages.mapNotNull { page ->
                val f = java.io.File(page.processedImagePath)
                if (f.exists()) f else null
            }
            isProcessing.value = false
            withContext(Dispatchers.Main) {
                if (files.isNotEmpty()) {
                    FileUtils.shareImageFiles(context, files, doc.title)
                } else {
                    Toast.makeText(context, "No page images found to export", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // --- BATCH RENAMING ACTIONS ---

    fun batchRenamePages(
        documentId: Long,
        template: String = "Page {num}",
        startNumber: Int = 1,
        useDateSuffix: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val doc = repository.getDocumentById(documentId) ?: return@launch
            val pages = repository.getPagesDirect(documentId)
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

            val updatedPages = pages.mapIndexed { idx, page ->
                val num = startNumber + idx
                val formattedNum = String.format("%02d", num)
                var newLabel = template
                    .replace("{index}", formattedNum)
                    .replace("{num}", num.toString())
                    .replace("{doc_name}", doc.title)
                    .replace("{date}", dateStr)

                if (useDateSuffix && !newLabel.contains(dateStr)) {
                    newLabel = "${newLabel}_$dateStr"
                }

                page.copy(notes = newLabel)
            }

            repository.updatePages(updatedPages)
            loadDocumentDetails(documentId)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Renamed ${pages.size} page(s)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun batchRenameDocuments(
        docIds: List<Long>,
        template: String = "Doc {num}",
        startNumber: Int = 1,
        useDateSuffix: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            docIds.forEachIndexed { idx, id ->
                val doc = repository.getDocumentById(id) ?: return@forEachIndexed
                val num = startNumber + idx
                val formattedNum = String.format("%02d", num)
                var newTitle = template
                    .replace("{index}", formattedNum)
                    .replace("{num}", num.toString())
                    .replace("{date}", dateStr)
                    .replace("{title}", doc.title)

                if (useDateSuffix && !newTitle.contains(dateStr)) {
                    newTitle = "${newTitle}_$dateStr"
                }

                repository.updateDocument(doc.copy(title = newTitle))
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Renamed ${docIds.size} document(s)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun batchRenameSessionPages(
        template: String = "Page {num}",
        startNumber: Int = 1,
        useDateSuffix: Boolean = false
    ) {
        val list = capturedPages.value.toMutableList()
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        for (i in list.indices) {
            val num = startNumber + i
            val formattedNum = String.format("%02d", num)
            var newLabel = template
                .replace("{index}", formattedNum)
                .replace("{num}", num.toString())
                .replace("{date}", dateStr)

            if (useDateSuffix && !newLabel.contains(dateStr)) {
                newLabel = "${newLabel}_$dateStr"
            }
            list[i] = list[i].copy(label = newLabel)
        }
        capturedPages.value = list
        Toast.makeText(context, "Renamed ${list.size} page(s)", Toast.LENGTH_SHORT).show()
    }

    // --- AUTO ORIENTATION DETECTION ---

    fun autoRotatePageUpright(page: PageEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            val bmp = FileUtils.loadBitmap(page.processedImagePath)
            if (bmp == null) {
                isProcessing.value = false
                return@launch
            }
            val result = AutoOrientationHelper.detectAndCorrectOrientation(bmp)
            if (result.rotationAppliedDegrees != 0) {
                val newPath = FileUtils.saveBitmapToDocStorage(context, result.rotatedBitmap, "UPRIGHT")
                val updated = page.copy(
                    processedImagePath = newPath,
                    extractedText = result.detectedText.ifBlank { page.extractedText }
                )
                repository.updatePage(updated)
                loadDocumentDetails(page.documentId)
                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                    Toast.makeText(context, "Rotated ${result.rotationAppliedDegrees}° to upright position", Toast.LENGTH_SHORT).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                    Toast.makeText(context, "Page is already in upright orientation", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // --- PDF & DOCX EXPORT ACTIONS ---

    fun sharePdfDirect(doc: DocumentEntity) {
        exportAndSharePdf(doc, PdfExportConfig(title = doc.title))
    }

    fun exportAndSharePdf(doc: DocumentEntity, config: PdfExportConfig) {
        viewModelScope.launch {
            isProcessing.value = true
            val pages = repository.getPagesDirect(doc.id)
            val pdfFile = PdfExporter.generatePdf(context, config.title, pages, config)
            isProcessing.value = false
            if (pdfFile != null) {
                PdfExporter.sharePdf(context, pdfFile)
            } else {
                Toast.makeText(context, "Failed to generate PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun exportAndOpenPdf(doc: DocumentEntity, config: PdfExportConfig) {
        viewModelScope.launch {
            isProcessing.value = true
            val pages = repository.getPagesDirect(doc.id)
            val pdfFile = PdfExporter.generatePdf(context, config.title, pages, config)
            isProcessing.value = false
            if (pdfFile != null) {
                PdfExporter.openPdf(context, pdfFile)
            } else {
                Toast.makeText(context, "Failed to generate PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun exportAndShareDocx(doc: DocumentEntity, config: DocxExporter.DocxExportConfig = DocxExporter.DocxExportConfig(title = doc.title)) {
        viewModelScope.launch {
            isProcessing.value = true
            val pages = repository.getPagesDirect(doc.id)
            val docxFile = DocxExporter.generateDocx(context, doc.title, pages, config)
            isProcessing.value = false
            if (docxFile != null) {
                DocxExporter.shareDocx(context, docxFile)
            } else {
                Toast.makeText(context, "Failed to generate Word document", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun exportAndOpenDocx(doc: DocumentEntity, config: DocxExporter.DocxExportConfig = DocxExporter.DocxExportConfig(title = doc.title)) {
        viewModelScope.launch {
            isProcessing.value = true
            val pages = repository.getPagesDirect(doc.id)
            val docxFile = DocxExporter.generateDocx(context, doc.title, pages, config)
            isProcessing.value = false
            if (docxFile != null) {
                DocxExporter.openDocx(context, docxFile)
            } else {
                Toast.makeText(context, "Failed to generate Word document", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun exportAndShareXlsx(doc: DocumentEntity) {
        viewModelScope.launch {
            isProcessing.value = true
            val pages = repository.getPagesDirect(doc.id)
            val xlsxFile = ExcelExporter.generateXlsxFromPages(context, doc.title, pages)
            isProcessing.value = false
            if (xlsxFile != null) {
                ExcelExporter.shareXlsx(context, xlsxFile)
            } else {
                Toast.makeText(context, "Failed to generate Excel spreadsheet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun exportAndOpenXlsx(doc: DocumentEntity) {
        viewModelScope.launch {
            isProcessing.value = true
            val pages = repository.getPagesDirect(doc.id)
            val xlsxFile = ExcelExporter.generateXlsxFromPages(context, doc.title, pages)
            isProcessing.value = false
            if (xlsxFile != null) {
                ExcelExporter.openXlsx(context, xlsxFile)
            } else {
                Toast.makeText(context, "Failed to generate Excel spreadsheet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun exportAndShareXlsxFromCsv(title: String, csvText: String) {
        viewModelScope.launch {
            isProcessing.value = true
            val xlsxFile = ExcelExporter.generateXlsxFromCsv(context, title, csvText)
            isProcessing.value = false
            if (xlsxFile != null) {
                ExcelExporter.shareXlsx(context, xlsxFile)
            } else {
                Toast.makeText(context, "Failed to generate Excel spreadsheet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun exportAndOpenXlsxFromCsv(title: String, csvText: String) {
        viewModelScope.launch {
            isProcessing.value = true
            val xlsxFile = ExcelExporter.generateXlsxFromCsv(context, title, csvText)
            isProcessing.value = false
            if (xlsxFile != null) {
                ExcelExporter.openXlsx(context, xlsxFile)
            } else {
                Toast.makeText(context, "Failed to generate Excel spreadsheet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun prepareExistingDocForCrop(doc: DocumentEntity, pageIndex: Int, onReady: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val pages = repository.getPagesDirect(doc.id)
            if (pages.isEmpty()) return@launch
            val tempPages = pages.map { page ->
                val imagePath = if (File(page.originalImagePath).exists()) page.originalImagePath else page.processedImagePath
                TempScannedPage(
                    originalPath = imagePath,
                    corners = listOf(
                        Offset(0.05f, 0.05f),
                        Offset(0.95f, 0.05f),
                        Offset(0.95f, 0.95f),
                        Offset(0.05f, 0.95f)
                    ),
                    rotationDegrees = page.rotationDegrees,
                    filterType = try { FilterType.valueOf(page.filterType) } catch (e: Exception) { FilterType.MAGIC_COLOR },
                    label = "Page ${page.pageNumber}"
                )
            }
            capturedPages.value = tempPages
            currentCropPageIndex.value = pageIndex.coerceIn(0, (tempPages.size - 1).coerceAtLeast(0))
            sessionDocumentTitle.value = doc.title
            scanMode.value = if (tempPages.size > 1) ScanMode.BATCH else ScanMode.SINGLE
            withContext(Dispatchers.Main) {
                onReady()
            }
        }
    }

    suspend fun getPagesForDocumentDirect(documentId: Long): List<PageEntity> {
        return withContext(Dispatchers.IO) {
            repository.getPagesDirect(documentId)
        }
    }

    suspend fun getDocumentDirect(documentId: Long): DocumentEntity? {
        return withContext(Dispatchers.IO) {
            repository.getDocumentById(documentId)
        }
    }

    suspend fun saveNewDocument(title: String, folder: String = "All", pages: List<PageEntity>): Long {
        return withContext(Dispatchers.IO) {
            repository.saveNewDocument(title, folder, pages)
        }
    }

    suspend fun updateDocument(document: DocumentEntity) {
        withContext(Dispatchers.IO) {
            repository.updateDocument(document)
        }
    }

    suspend fun getPageByIdDirect(pageId: Long): PageEntity? {
        return withContext(Dispatchers.IO) {
            repository.getPageById(pageId)
        }
    }

    fun saveEditedSinglePage(
        page: PageEntity,
        newBitmap: Bitmap,
        newTitle: String? = null,
        newExtractedText: String? = null,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            try {
                val newPath = FileUtils.saveBitmapToDocStorage(context, newBitmap, "EDITED")
                val updatedPage = page.copy(
                    processedImagePath = newPath,
                    notes = if (!newTitle.isNullOrBlank()) newTitle else page.notes,
                    extractedText = newExtractedText ?: page.extractedText
                )
                repository.updatePage(updatedPage)
                loadDocumentDetails(page.documentId)
                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                    Toast.makeText(context, "Page changes saved successfully", Toast.LENGTH_SHORT).show()
                    onComplete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                    Toast.makeText(context, "Failed to save edits: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun rotateAllPagesDirect(doc: DocumentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val pages = repository.getPagesDirect(doc.id)
            pages.forEach { page ->
                val currentBmp = FileUtils.loadBitmap(page.processedImagePath)
                if (currentBmp != null) {
                    val rotated = FileUtils.rotateBitmap(currentBmp, 90f)
                    val newPath = FileUtils.saveBitmapToDocStorage(context, rotated, "ROT")
                    val newRotation = (page.rotationDegrees + 90) % 360
                    repository.updatePage(
                        page.copy(
                            processedImagePath = newPath,
                            rotationDegrees = newRotation
                        )
                    )
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "All ${pages.size} pages rotated 90°", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun autoUprightDocumentPages(doc: DocumentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val pages = repository.getPagesDirect(doc.id)
            pages.forEach { page ->
                val currentBmp = FileUtils.loadBitmap(page.processedImagePath)
                if (currentBmp != null) {
                    // Check if aspect ratio is landscape (width > height) and orient upright
                    if (currentBmp.width > currentBmp.height) {
                        val upright = FileUtils.rotateBitmap(currentBmp, 90f)
                        val newPath = FileUtils.saveBitmapToDocStorage(context, upright, "UPRIGHT")
                        val newRotation = (page.rotationDegrees + 90) % 360
                        repository.updatePage(
                            page.copy(
                                processedImagePath = newPath,
                                rotationDegrees = newRotation
                            )
                        )
                    }
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "AI Upright orientation applied to all pages", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun mergeDocuments(documents: List<DocumentEntity>, newTitle: String) {
        if (documents.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            try {
                val allIds = documents.map { it.id }
                val newDocId = repository.mergeDocumentsIntoNew(
                    newTitle = newTitle.ifBlank { "Merged_Document" },
                    folder = documents.firstOrNull()?.folder ?: "All Docs",
                    sourceDocIds = allIds,
                    context = context
                )
                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                    Toast.makeText(context, "Successfully merged into $newTitle", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                    Toast.makeText(context, "Merge failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Executes merging of multiple documents in exact custom order with support for:
     * - Preserving original source documents or deleting them after success
     * - Generating complete combined PDF file
     * - Background execution with progress notification
     */
    fun mergeDocumentsWithCustomOrder(
        orderedDocuments: List<DocumentEntity>,
        newTitle: String,
        keepOriginals: Boolean,
        onSuccess: (Long) -> Unit,
        onError: (String) -> Unit
    ) {
        if (orderedDocuments.isEmpty()) {
            onError("No documents selected to merge")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            try {
                val sourceDocIds = orderedDocuments.map { it.id }
                val defaultTitle = "Merged_${SimpleDateFormat("ddMMyyyy_HHmm", Locale.getDefault()).format(Date())}"
                val finalTitle = newTitle.ifBlank { defaultTitle }
                val targetFolder = orderedDocuments.firstOrNull()?.folder ?: "All Docs"

                val newDocId = repository.mergeDocumentsIntoNew(
                    newTitle = finalTitle,
                    folder = targetFolder,
                    sourceDocIds = sourceDocIds,
                    context = context
                )

                // Generate full exported PDF
                val pages = repository.getPagesDirect(newDocId)
                if (pages.isNotEmpty()) {
                    val pdfFile = PdfExporter.generatePdf(
                        context = context,
                        documentTitle = finalTitle,
                        pages = pages,
                        config = PdfExportConfig(title = finalTitle)
                    )
                    if (pdfFile != null && pdfFile.exists()) {
                        val doc = repository.getDocumentById(newDocId)
                        doc?.let {
                            repository.updateDocument(it.copy(pdfPath = pdfFile.absolutePath))
                        }
                    }
                }

                // If user unchecked "Keep originals", delete source documents
                if (!keepOriginals) {
                    repository.deleteDocuments(sourceDocIds)
                }

                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                    Toast.makeText(context, "Documents merged successfully", Toast.LENGTH_SHORT).show()
                    onSuccess(newDocId)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                    val msg = e.localizedMessage ?: "Failed to merge documents"
                    Toast.makeText(context, "Merge error: $msg", Toast.LENGTH_LONG).show()
                    onError(msg)
                }
            }
        }
    }

    /**
     * Imports files (PDF / images) from device storage directly into the app repository,
     * returning newly created document entities ready for merging.
     */
    suspend fun importFilesForMerge(uris: List<Uri>): List<DocumentEntity> = withContext(Dispatchers.IO) {
        val createdDocs = mutableListOf<DocumentEntity>()
        for (uri in uris) {
            try {
                val bitmaps = FileUtils.loadBitmapsFromUri(context, uri)
                if (bitmaps.isNotEmpty()) {
                    val fileName = FileUtils.getFileNameFromUri(context, uri).ifBlank { "Imported Document" }
                    val pageEntities = bitmaps.mapIndexed { index, bmp ->
                        val path = FileUtils.saveBitmapToDocStorage(context, bmp, "MERGE_IMPORT")
                        PageEntity(
                            documentId = 0,
                            pageNumber = index + 1,
                            originalImagePath = path,
                            processedImagePath = path
                        )
                    }
                    val newDocId = repository.saveNewDocument(fileName, "All Docs", pageEntities)
                    repository.getDocumentById(newDocId)?.let { createdDocs.add(it) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        createdDocs
    }

    /**
     * Imports a PDF or images from device storage specifically for the Extract PDF Pages tool,
     * persists it as a new document, and returns the DocumentEntity so the UI immediately
     * opens the page grid selection screen.
     */
    suspend fun importDocumentFromDeviceForExtract(uris: List<Uri>): DocumentEntity? = withContext(Dispatchers.IO) {
        importDocumentFromDevice(uris, "EXTRACT_SRC")
    }

    /**
     * General multi-page PDF & Image importer from device storage.
     * Renders pages, persists PageEntities, creates DocumentEntity in database, and returns it.
     */
    suspend fun importDocumentFromDevice(uris: List<Uri>, tag: String = "DEVICE_IMPORT"): DocumentEntity? = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext null
        try {
            val allBitmaps = mutableListOf<Bitmap>()
            var primaryTitle = ""
            for (uri in uris) {
                val loaded = FileUtils.loadBitmapsFromUri(context, uri)
                if (loaded.isNotEmpty()) {
                    if (primaryTitle.isBlank()) {
                        val originalName = FileUtils.getFileNameFromUri(context, uri)
                        primaryTitle = if (originalName.isNotBlank()) {
                            val clean = originalName.substringBeforeLast(".")
                            clean.ifBlank { "Imported PDF" }
                        } else {
                            "Imported PDF ${SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.getDefault()).format(Date())}"
                        }
                    }
                    allBitmaps.addAll(loaded)
                }
            }
            if (allBitmaps.isNotEmpty()) {
                val pageEntities = allBitmaps.mapIndexed { index, bmp ->
                    val path = FileUtils.saveBitmapToDocStorage(context, bmp, tag)
                    PageEntity(
                        documentId = 0,
                        pageNumber = index + 1,
                        originalImagePath = path,
                        processedImagePath = path
                    )
                }
                val finalTitle = primaryTitle.ifBlank {
                    "Imported PDF ${SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.getDefault()).format(Date())}"
                }
                val newDocId = repository.saveNewDocument(
                    title = finalTitle,
                    folder = "All Docs",
                    pages = pageEntities
                )
                return@withContext repository.getDocumentById(newDocId)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    /**
     * Applies rotation transformations to pages of a document permanently.
     * Rotates underlying bitmap files, updates PageEntities in DB, and re-generates document PDF.
     */
    suspend fun saveRotatedDocumentPages(
        documentId: Long,
        pageRotations: Map<Long, Int>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val pages = repository.getPagesDirect(documentId)
            var hasChanges = false
            for (page in pages) {
                val rot = (pageRotations[page.id] ?: 0) % 360
                val normalizedRot = if (rot < 0) rot + 360 else rot
                if (normalizedRot != 0) {
                    hasChanges = true
                    val currentBmp = FileUtils.loadBitmap(page.processedImagePath)
                        ?: FileUtils.loadBitmap(page.originalImagePath)
                    if (currentBmp != null) {
                        val rotated = FileUtils.rotateBitmap(currentBmp, normalizedRot.toFloat())
                        val newPath = FileUtils.saveBitmapToDocStorage(context, rotated, "ROTATED")
                        val newTotalRot = (page.rotationDegrees + normalizedRot) % 360
                        val updated = page.copy(
                            processedImagePath = newPath,
                            rotationDegrees = newTotalRot
                        )
                        repository.updatePage(updated)
                    }
                }
            }
            if (hasChanges) {
                val updatedPages = repository.getPagesDirect(documentId)
                val doc = repository.getDocumentById(documentId)
                if (doc != null && updatedPages.isNotEmpty()) {
                    val firstPage = updatedPages.firstOrNull()
                    if (firstPage != null) {
                        repository.updateDocument(doc.copy(thumbnailPath = firstPage.processedImagePath))
                    }
                    PdfExporter.generatePdf(context, doc.title, updatedPages)
                }
                loadDocumentDetails(documentId)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Explicit Save to Device Button: Exports the document as a PDF to the device public storage (Downloads/DocScanner)
     * and shows a file saved notification.
     */
    fun saveDocumentPdfToDevice(
        doc: DocumentEntity,
        config: PdfExportConfig? = null,
        onSaved: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pages = repository.getPagesDirect(doc.id)
                if (pages.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Document has no pages to save", Toast.LENGTH_SHORT).show()
                        onSaved(false)
                    }
                    return@launch
                }
                val pdfFile = PdfExporter.generatePdf(
                    context,
                    config?.title ?: doc.title,
                    pages,
                    config ?: com.docscan.util.PdfExportConfig(title = doc.title)
                )
                if (pdfFile != null && pdfFile.exists()) {
                    val savedUri = FileUtils.savePdfToPublicDownloads(
                        context = context,
                        pdfFile = pdfFile,
                        displayName = config?.title ?: doc.title,
                        showNotification = true
                    )
                    withContext(Dispatchers.Main) {
                        if (savedUri != null) {
                            Toast.makeText(context, "PDF saved to Downloads/DocScanner", Toast.LENGTH_LONG).show()
                            onSaved(true)
                        } else {
                            Toast.makeText(context, "Failed to save PDF to device storage", Toast.LENGTH_SHORT).show()
                            onSaved(false)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Could not generate PDF", Toast.LENGTH_SHORT).show()
                        onSaved(false)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error saving PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                    onSaved(false)
                }
            }
        }
    }

    /**
     * Explicit Save to Gallery Button: Saves all document pages to the device Pictures gallery
     * and shows a file saved notification.
     */
    fun saveDocumentToGallery(
        doc: DocumentEntity,
        onSaved: (Int) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pages = repository.getPagesDirect(doc.id)
                var savedCount = 0
                pages.forEachIndexed { index, page ->
                    val pageTitle = "${doc.title}_page_${index + 1}"
                    if (FileUtils.saveImageFileToGallery(context, page.processedImagePath, pageTitle)) {
                        savedCount++
                    }
                }
                withContext(Dispatchers.Main) {
                    if (savedCount > 0) {
                        Toast.makeText(context, "Saved $savedCount image(s) to Gallery (Pictures/DocScanner)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "No images could be saved", Toast.LENGTH_SHORT).show()
                    }
                    onSaved(savedCount)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error saving images: ${e.message}", Toast.LENGTH_SHORT).show()
                    onSaved(0)
                }
            }
        }
    }

    /**
     * Saves a specific single page image to the device Pictures gallery
     */
    fun saveSinglePageToGallery(
        page: PageEntity,
        docTitle: String = "Scanned_Page",
        pageIndex: Int = 1,
        onSaved: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cleanDocTitle = docTitle.ifBlank { "Scanned_Page" }
                val pageTitle = "${cleanDocTitle}_page_$pageIndex"
                val success = FileUtils.saveImageFileToGallery(context, page.processedImagePath, pageTitle)
                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(context, "Page $pageIndex saved to Gallery (Pictures/DocScanner)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Failed to save page to Gallery", Toast.LENGTH_SHORT).show()
                    }
                    onSaved(success)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error saving page: ${e.message}", Toast.LENGTH_SHORT).show()
                    onSaved(false)
                }
            }
        }
    }
}

