package com.example.safe_kids.ui.parent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.tasks.await

data class AppUsageData(
    val appName: String,
    val packageName: String,
    val totalMinutes: Long,
    val date: String
)

data class DaySummary(
    val date: String,
    val dayName: String,
    val totalMinutes: Long,
    val apps: List<AppUsageData>
)

data class ReportsState(
    val isLoading: Boolean = false,
    val weeklyData: List<DaySummary> = emptyList(),
    val selectedDayIndex: Int = 6, // Default to today
    val error: String? = null
)

class ActivityReportViewModel : ViewModel() {
    private val firestore = FirebaseFirestore.getInstance()
    private val _state = MutableStateFlow(ReportsState())
    val state: StateFlow<ReportsState> = _state.asStateFlow()

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dayFormatter = SimpleDateFormat("EEE", Locale.getDefault())

    fun fetchReports(childId: String) {
        _state.update { it.copy(isLoading = true) }
        
        viewModelScope.launch {
            val calendar = Calendar.getInstance()
            val summaries = mutableListOf<DaySummary>()
            
            // Prepare 7 days of dates
            val days = (0 until 7).map { i ->
                val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -i) }
                dateFormatter.format(cal.time) to dayFormatter.format(cal.time)
            }.reversed()

            try {
                for ((date, dayName) in days) {
                    val snapshot = firestore.collection("app_usage")
                        .document(childId)
                        .collection("daily")
                        .document(date)
                        .collection("apps")
                        .get()
                        .await()
                    
                    val apps = snapshot.documents.mapNotNull { doc ->
                        AppUsageData(
                            appName = doc.getString("appName") ?: "Unknown",
                            packageName = doc.getString("packageName") ?: "",
                            totalMinutes = doc.getLong("totalMinutes") ?: 0L,
                            date = doc.getString("date") ?: date
                        )
                    }
                    
                    summaries.add(DaySummary(
                        date = date,
                        dayName = dayName,
                        totalMinutes = apps.sumOf { it.totalMinutes },
                        apps = apps.sortedByDescending { it.totalMinutes }
                    ))
                }
                
                _state.update { it.copy(
                    isLoading = false,
                    weeklyData = summaries,
                    selectedDayIndex = summaries.size - 1
                ) }

                // Save today's report to history if it contains data
                summaries.lastOrNull()?.let { todaySummary ->
                    if (todaySummary.totalMinutes > 0) {
                        saveReportToHistory(childId, todaySummary)
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun saveReportToHistory(childId: String, summary: DaySummary) {
        viewModelScope.launch {
            try {
                val report = mapOf(
                    "childId" to childId,
                    "date" to summary.date,
                    "totalMinutes" to summary.totalMinutes,
                    "appCount" to summary.apps.size,
                    "topApp" to summary.apps.firstOrNull()?.appName,
                    "generatedAt" to System.currentTimeMillis()
                )
                
                firestore.collection(com.example.safe_kids.auth.AuthConfig.ACTIVITY_REPORTS_COLLECTION)
                    .document("${childId}_${summary.date}")
                    .set(report)
                    .await()
            } catch (e: Exception) {
                // Silent fail for background save
            }
        }
    }

    fun selectDay(index: Int) {
        _state.update { it.copy(selectedDayIndex = index) }
    }

    fun formatDuration(minutes: Long): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
