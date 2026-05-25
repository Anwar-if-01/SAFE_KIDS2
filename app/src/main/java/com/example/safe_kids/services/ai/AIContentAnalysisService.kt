package com.example.safe_kids.services.ai

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.tasks.await

data class ContentAnalysisResult(
    val id: String,
    val timestamp: Long,
    val contentType: ContentType,
    val analysisType: AnalysisType,
    val result: AnalysisResultData,
    val riskLevel: RiskLevel,
    val recommendations: List<String> = emptyList()
)

enum class ContentType {
    IMAGE,
    TEXT,
    APP_CONTENT,
    SCREEN_CAPTURE
}

enum class AnalysisType {
    IMAGE_LABELING,
    TEXT_RECOGNITION,
    INAPPROPRIATE_CONTENT_DETECTION,
    STUDY_CONTENT_ANALYSIS
}

enum class RiskLevel {
    SAFE,
    LOW_RISK,
    MEDIUM_RISK,
    HIGH_RISK,
    BLOCKED
}

sealed class AnalysisResultData {
    data class ImageLabels(
        val labels: List<DetectedLabel>,
        val confidence: Float
    ) : AnalysisResultData()
    
    data class RecognizedText(
        val text: String,
        val language: String?,
        val blocks: List<TextBlock>
    ) : AnalysisResultData()
    
    data class ContentRisk(
        val riskFactors: List<String>,
        val safetyScore: Float,
        val blockedKeywords: List<String>
    ) : AnalysisResultData()
}

data class DetectedLabel(
    val text: String,
    val confidence: Float,
    val index: Int
)

data class TextBlock(
    val text: String,
    val boundingBox: android.graphics.Rect?,
    val confidence: Float
)

data class StudyContentAnalysis(
    val isEducational: Boolean,
    val subject: String?,
    val difficulty: String?,
    val timeEstimate: Long? // in minutes
)

interface AIContentAnalysisService {
    // Image analysis
    suspend fun analyzeImage(bitmap: Bitmap): ContentAnalysisResult
    suspend fun detectInappropriateContent(bitmap: Bitmap): ContentAnalysisResult
    suspend fun analyzeScreenshot(bitmap: Bitmap): ContentAnalysisResult
    
    // Text analysis
    suspend fun recognizeText(bitmap: Bitmap): ContentAnalysisResult
    suspend fun analyzeTextContent(text: String): ContentAnalysisResult
    suspend fun detectInappropriateText(text: String): ContentAnalysisResult
    
    // Study content analysis
    suspend fun analyzeStudyContent(content: String): StudyContentAnalysis
    suspend fun isEducationalApp(packageName: String): Boolean
    suspend fun categorizeAppContent(packageName: String): String
    
    // Content monitoring
    fun startContentMonitoring()
    fun stopContentMonitoring()
    fun getAnalysisHistory(): Flow<List<ContentAnalysisResult>>
    
    // Settings and configuration
    fun setSensitivityLevel(level: Float) // 0.0 to 1.0
    fun addCustomBlockedKeywords(keywords: List<String>)
    fun removeCustomBlockedKeywords(keywords: List<String>)
    fun getBlockedKeywords(): List<String>
}

class AIContentAnalysisServiceImpl(private val context: Context) : AIContentAnalysisService {
    
    private val imageLabeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val workManager = WorkManager.getInstance(context)
    
    private val _analysisHistory = MutableStateFlow<List<ContentAnalysisResult>>(emptyList())
    private var sensitivityLevel = 0.7f
    private val customBlockedKeywords = mutableSetOf<String>()
    
    // Default inappropriate content keywords
    private val defaultBlockedKeywords = setOf(
        "violence", "drugs", "alcohol", "gambling", "adult", "explicit",
        "inappropriate", "harmful", "dangerous", "weapon"
    )

    override suspend fun analyzeImage(bitmap: Bitmap): ContentAnalysisResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        
        return try {
            val labels = imageLabeler.process(image).await()
            val detectedLabels = labels.map { label ->
                DetectedLabel(
                    text = label.text,
                    confidence = label.confidence,
                    index = label.index
                )
            }
            
            val riskLevel = assessImageRisk(detectedLabels)
            val averageConfidence = if (detectedLabels.isNotEmpty()) {
                detectedLabels.map { it.confidence }.average().toFloat()
            } else {
                0f
            }
            
            ContentAnalysisResult(
                id = "img_${System.currentTimeMillis()}",
                timestamp = System.currentTimeMillis(),
                contentType = ContentType.IMAGE,
                analysisType = AnalysisType.IMAGE_LABELING,
                result = AnalysisResultData.ImageLabels(detectedLabels, averageConfidence),
                riskLevel = riskLevel,
                recommendations = generateImageRecommendations(riskLevel, detectedLabels)
            ).also { addToHistory(it) }
            
        } catch (e: Exception) {
            ContentAnalysisResult(
                id = "img_error_${System.currentTimeMillis()}",
                timestamp = System.currentTimeMillis(),
                contentType = ContentType.IMAGE,
                analysisType = AnalysisType.IMAGE_LABELING,
                result = AnalysisResultData.ContentRisk(listOf("Analysis failed"), 0f, emptyList()),
                riskLevel = RiskLevel.SAFE
            )
        }
    }

    override suspend fun detectInappropriateContent(bitmap: Bitmap): ContentAnalysisResult {
        val analysisResult = analyzeImage(bitmap)
        
        // Enhanced inappropriate content detection
        val labels = (analysisResult.result as? AnalysisResultData.ImageLabels)?.labels ?: emptyList()
        val riskFactors = mutableListOf<String>()
        val blockedKeywords = mutableListOf<String>()
        
        labels.forEach { label ->
            val labelText = label.text.lowercase()
            if (isInappropriateLabel(labelText)) {
                riskFactors.add("Inappropriate visual content: ${label.text}")
                blockedKeywords.add(labelText)
            }
        }
        
        val riskLevel = when {
            blockedKeywords.isNotEmpty() -> RiskLevel.HIGH_RISK
            riskFactors.isNotEmpty() -> RiskLevel.MEDIUM_RISK
            else -> RiskLevel.SAFE
        }
        
        return ContentAnalysisResult(
            id = "inappropriate_${System.currentTimeMillis()}",
            timestamp = System.currentTimeMillis(),
            contentType = ContentType.IMAGE,
            analysisType = AnalysisType.INAPPROPRIATE_CONTENT_DETECTION,
            result = AnalysisResultData.ContentRisk(riskFactors, calculateSafetyScore(riskFactors), blockedKeywords),
            riskLevel = riskLevel,
            recommendations = generateSafetyRecommendations(riskLevel)
        ).also { 
            addToHistory(it)
            if (it.riskLevel >= RiskLevel.MEDIUM_RISK) {
                logAlertToFirestore(it)
            }
        }
    }

    override suspend fun analyzeScreenshot(bitmap: Bitmap): ContentAnalysisResult {
        // Combine image and text analysis for screenshots
        val imageAnalysis = analyzeImage(bitmap)
        val textAnalysis = recognizeText(bitmap)
        
        val combinedRiskLevel = maxOf(imageAnalysis.riskLevel.ordinal, textAnalysis.riskLevel.ordinal)
        
        // Calculate combined confidence from both analyses
        val imageConfidence = (imageAnalysis.result as? AnalysisResultData.ImageLabels)?.confidence ?: 0f
        val textConfidence = (textAnalysis.result as? AnalysisResultData.RecognizedText)?.let { textResult ->
            if (textResult.blocks.isNotEmpty()) {
                0.9f // Use fixed confidence for text
            } else 0f
        } ?: 0f
        
        val combinedConfidence = if (imageConfidence > 0f && textConfidence > 0f) {
            (imageConfidence + textConfidence) / 2f
        } else {
            maxOf(imageConfidence, textConfidence)
        }
        
        return ContentAnalysisResult(
            id = "screenshot_${System.currentTimeMillis()}",
            timestamp = System.currentTimeMillis(),
            contentType = ContentType.SCREEN_CAPTURE,
            analysisType = AnalysisType.INAPPROPRIATE_CONTENT_DETECTION,
            result = AnalysisResultData.ContentRisk(
                riskFactors = listOf("Screenshot analysis completed"),
                safetyScore = combinedConfidence,
                blockedKeywords = emptyList()
            ),
            riskLevel = RiskLevel.values()[combinedRiskLevel],
            recommendations = listOf("Screenshot monitored for safety")
        ).also { addToHistory(it) }
    }

    override suspend fun recognizeText(bitmap: Bitmap): ContentAnalysisResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        
        return try {
            val visionText = textRecognizer.process(image).await()
            
            val textBlocks = visionText.textBlocks.map { block ->
                TextBlock(
                    text = block.text,
                    boundingBox = block.boundingBox,
                    confidence = 0.9f // ML Kit doesn't provide confidence for text recognition
                )
            }
            
            val fullText = visionText.text
            val riskLevel = assessTextRisk(fullText)
            
            ContentAnalysisResult(
                id = "text_${System.currentTimeMillis()}",
                timestamp = System.currentTimeMillis(),
                contentType = ContentType.TEXT,
                analysisType = AnalysisType.TEXT_RECOGNITION,
                result = AnalysisResultData.RecognizedText(fullText, null, textBlocks),
                riskLevel = riskLevel,
                recommendations = generateTextRecommendations(riskLevel, fullText)
            ).also { addToHistory(it) }
            
        } catch (e: Exception) {
            ContentAnalysisResult(
                id = "text_error_${System.currentTimeMillis()}",
                timestamp = System.currentTimeMillis(),
                contentType = ContentType.TEXT,
                analysisType = AnalysisType.TEXT_RECOGNITION,
                result = AnalysisResultData.RecognizedText("", null, emptyList()),
                riskLevel = RiskLevel.SAFE
            )
        }
    }

    override suspend fun analyzeTextContent(text: String): ContentAnalysisResult {
        val riskLevel = assessTextRisk(text)
        val riskFactors = findRiskFactors(text)
        val blockedKeywords = findBlockedKeywords(text)
        
        return ContentAnalysisResult(
            id = "text_analysis_${System.currentTimeMillis()}",
            timestamp = System.currentTimeMillis(),
            contentType = ContentType.TEXT,
            analysisType = AnalysisType.INAPPROPRIATE_CONTENT_DETECTION,
            result = AnalysisResultData.ContentRisk(riskFactors, calculateSafetyScore(riskFactors), blockedKeywords),
            riskLevel = riskLevel,
            recommendations = generateTextRecommendations(riskLevel, text)
        ).also { addToHistory(it) }
    }

    override suspend fun detectInappropriateText(text: String): ContentAnalysisResult {
        return analyzeTextContent(text)
    }

    override suspend fun analyzeStudyContent(content: String): StudyContentAnalysis {
        // Simple heuristic-based analysis (can be enhanced with ML models)
        val educationalKeywords = setOf(
            "math", "science", "history", "literature", "biology", "chemistry", "physics",
            "geography", "language", "study", "learn", "education", "homework", "assignment"
        )
        
        val contentLower = content.lowercase()
        val isEducational = educationalKeywords.any { keyword -> contentLower.contains(keyword) }
        
        val subject = educationalKeywords.find { contentLower.contains(it) }
        val difficulty = when {
            contentLower.contains("advanced") || contentLower.contains("complex") -> "Advanced"
            contentLower.contains("basic") || contentLower.contains("introduction") -> "Beginner"
            else -> "Intermediate"
        }
        
        val timeEstimate = when (content.length) {
            in 0..500 -> 5L
            in 501..1500 -> 15L
            in 1501..3000 -> 30L
            else -> 60L
        }
        
        return StudyContentAnalysis(
            isEducational = isEducational,
            subject = subject,
            difficulty = difficulty,
            timeEstimate = timeEstimate
        )
    }

    override suspend fun isEducationalApp(packageName: String): Boolean {
        val educationalApps = setOf(
            "com.google.android.apps.docs",
            "com.microsoft.office.word",
            "com.adobe.reader",
            "org.khanacademy.android",
            "com.duolingo",
            "com.google.android.apps.classroom"
        )
        return educationalApps.contains(packageName)
    }

    override suspend fun categorizeAppContent(packageName: String): String {
        return when {
            packageName.contains("game") -> "Gaming"
            packageName.contains("social") || packageName.contains("chat") -> "Social"
            packageName.contains("video") || packageName.contains("youtube") -> "Entertainment"
            packageName.contains("education") || packageName.contains("learn") -> "Educational"
            packageName.contains("news") -> "News"
            packageName.contains("shopping") -> "Shopping"
            else -> "Other"
        }
    }

    override fun startContentMonitoring() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<ContentMonitoringWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .addTag("content_monitoring")
            .build()

        workManager.enqueueUniquePeriodicWork(
            "ContentMonitoring",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        android.util.Log.d("AI_MONITOR", "Content monitoring started via WorkManager")
    }

    override fun stopContentMonitoring() {
        workManager.cancelAllWorkByTag("content_monitoring")
        android.util.Log.d("AI_MONITOR", "Content monitoring stopped")
    }

    override fun getAnalysisHistory(): Flow<List<ContentAnalysisResult>> = _analysisHistory

    override fun setSensitivityLevel(level: Float) {
        sensitivityLevel = level.coerceIn(0f, 1f)
    }

    override fun addCustomBlockedKeywords(keywords: List<String>) {
        customBlockedKeywords.addAll(keywords.map { it.lowercase() })
    }

    override fun removeCustomBlockedKeywords(keywords: List<String>) {
        customBlockedKeywords.removeAll(keywords.map { it.lowercase() }.toSet())
    }

    override fun getBlockedKeywords(): List<String> {
        return (defaultBlockedKeywords + customBlockedKeywords).toList()
    }

    private fun logAlertToFirestore(result: ContentAnalysisResult) {
        val currentUser = auth.currentUser ?: return
        val riskData = result.result as? AnalysisResultData.ContentRisk ?: return
        
        val alert = mapOf(
            "childId" to currentUser.uid,
            "type" to "INAPPROPRIATE_CONTENT",
            "riskLevel" to result.riskLevel.name,
            "riskFactors" to riskData.riskFactors,
            "keywords" to riskData.blockedKeywords,
            "timestamp" to result.timestamp,
            "message" to "Potential inappropriate image detected on device",
            "isRead" to false
        )

        firestore.collection("activity_alerts")
            .add(alert)
            .addOnSuccessListener {
                android.util.Log.d("AI_MONITOR", "Alert logged to Firestore")
            }
    }

    private fun addToHistory(result: ContentAnalysisResult) {
        val currentHistory = _analysisHistory.value.toMutableList()
        currentHistory.add(0, result) // Add to beginning
        // Keep only last 100 results
        if (currentHistory.size > 100) {
            currentHistory.removeAt(currentHistory.size - 1)
        }
        _analysisHistory.value = currentHistory
    }

    private fun assessImageRisk(labels: List<DetectedLabel>): RiskLevel {
        val riskLabels = labels.filter { isInappropriateLabel(it.text.lowercase()) }
        return when {
            riskLabels.any { it.confidence > 0.8f } -> RiskLevel.HIGH_RISK
            riskLabels.any { it.confidence > 0.6f } -> RiskLevel.MEDIUM_RISK
            riskLabels.isNotEmpty() -> RiskLevel.LOW_RISK
            else -> RiskLevel.SAFE
        }
    }

    private fun assessTextRisk(text: String): RiskLevel {
        val textLower = text.lowercase()
        val blockedKeywords = getBlockedKeywords()
        val foundKeywords = blockedKeywords.filter { textLower.contains(it) }
        
        return when {
            foundKeywords.size >= 3 -> RiskLevel.HIGH_RISK
            foundKeywords.size >= 2 -> RiskLevel.MEDIUM_RISK
            foundKeywords.size >= 1 -> RiskLevel.LOW_RISK
            else -> RiskLevel.SAFE
        }
    }

    private fun isInappropriateLabel(label: String): Boolean {
        return getBlockedKeywords().any { keyword -> label.contains(keyword) }
    }

    private fun findRiskFactors(text: String): List<String> {
        val textLower = text.lowercase()
        return getBlockedKeywords().filter { textLower.contains(it) }
            .map { "Found inappropriate keyword: $it" }
    }

    private fun findBlockedKeywords(text: String): List<String> {
        val textLower = text.lowercase()
        return getBlockedKeywords().filter { textLower.contains(it) }
    }

    private fun calculateSafetyScore(riskFactors: List<String>): Float {
        return when (riskFactors.size) {
            0 -> 1.0f
            1 -> 0.7f
            2 -> 0.4f
            else -> 0.1f
        }
    }

    private fun generateImageRecommendations(riskLevel: RiskLevel, labels: List<DetectedLabel>): List<String> {
        return when (riskLevel) {
            RiskLevel.HIGH_RISK -> listOf("Block this content", "Notify parents immediately")
            RiskLevel.MEDIUM_RISK -> listOf("Review content", "Consider parental guidance")
            RiskLevel.LOW_RISK -> listOf("Monitor usage", "Discuss with child")
            RiskLevel.SAFE -> listOf("Content appears safe")
            RiskLevel.BLOCKED -> listOf("Content blocked by policy")
        }
    }

    private fun generateTextRecommendations(riskLevel: RiskLevel, text: String): List<String> {
        return when (riskLevel) {
            RiskLevel.HIGH_RISK -> listOf("Block text content", "Alert parents")
            RiskLevel.MEDIUM_RISK -> listOf("Review text content", "Parental guidance recommended")
            RiskLevel.LOW_RISK -> listOf("Monitor text content")
            RiskLevel.SAFE -> listOf("Text content is safe")
            RiskLevel.BLOCKED -> listOf("Text content blocked")
        }
    }

    private fun generateSafetyRecommendations(riskLevel: RiskLevel): List<String> {
        return when (riskLevel) {
            RiskLevel.HIGH_RISK -> listOf("Immediate action required", "Block access", "Contact parents")
            RiskLevel.MEDIUM_RISK -> listOf("Review required", "Consider restrictions")
            RiskLevel.LOW_RISK -> listOf("Monitor closely", "Educational discussion recommended")
            RiskLevel.SAFE -> listOf("No action needed")
            RiskLevel.BLOCKED -> listOf("Access denied by safety policy")
        }
    }
}
