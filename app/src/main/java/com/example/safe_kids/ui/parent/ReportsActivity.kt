package com.example.safe_kids.ui.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign

class ReportsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val childId = intent.getStringExtra("CHILD_ID") ?: ""
        val childName = intent.getStringExtra("CHILD_NAME") ?: "Child"

        setContent {
            SAFE_KIDSTheme {
                ReportsScreen(childId, childName, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    childId: String,
    childName: String,
    viewModel: ActivityReportViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    
    LaunchedEffect(childId) {
        if (childId.isNotEmpty()) {
            viewModel.fetchReports(childId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$childName's Activity") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.weeklyData.isEmpty()) {
                EmptyReportsState()
            } else {
                val selectedDay = state.weeklyData[state.selectedDayIndex]
                
                Column {
                    // Bar Chart Section
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .padding(16.dp),
                        elevation = CardDefaults.cardElevation(4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        UsageBarChart(
                            data = state.weeklyData,
                            selectedIndex = state.selectedDayIndex,
                            onDaySelected = { viewModel.selectDay(it) }
                        )
                    }

                    // Selected Day Details
                    Text(
                        text = "Usage for ${selectedDay.dayName} (${selectedDay.date})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    
                    Text(
                        text = "Total Screen Time: ${viewModel.formatDuration(selectedDay.totalMinutes)}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    )

                    // App Usage List
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(selectedDay.apps) { app ->
                            AppUsageItem(app, viewModel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UsageBarChart(
    data: List<DaySummary>,
    selectedIndex: Int,
    onDaySelected: (Int) -> Unit
) {
    AndroidView(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        factory = { context ->
            BarChart(context).apply {
                description.isEnabled = false
                setDrawGridBackground(false)
                setDrawBarShadow(false)
                legend.isEnabled = false
                
                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    setDrawGridLines(false)
                    granularity = 1f
                    labelCount = 7
                }
                
                axisLeft.apply {
                    setDrawGridLines(true)
                    axisMinimum = 0f
                }
                axisRight.isEnabled = false
                
                setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                    override fun onValueSelected(e: Entry?, h: Highlight?) {
                        e?.let { onDaySelected(it.x.toInt()) }
                    }
                    override fun onNothingSelected() {}
                })
            }
        },
        update = { chart ->
            val entries = data.mapIndexed { index, summary ->
                BarEntry(index.toFloat(), summary.totalMinutes.toFloat())
            }
            
            val dataSet = BarDataSet(entries, "Screen Time").apply {
                color = android.graphics.Color.parseColor("#6200EE")
                valueTextColor = android.graphics.Color.BLACK
                valueTextSize = 10f
                setDrawValues(true)
                // Highlight the selected day
                highLightAlpha = 120
                highLightColor = android.graphics.Color.parseColor("#03DAC6")
            }
            
            chart.data = BarData(dataSet)
            chart.xAxis.valueFormatter = IndexAxisValueFormatter(data.map { it.dayName })
            chart.highlightValue(selectedIndex.toFloat(), 0)
            chart.invalidate()
        }
    )
}

@Composable
fun AppUsageItem(app: AppUsageData, viewModel: ActivityReportViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(app.appName.take(1), fontWeight = FontWeight.Bold)
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(app.appName, fontWeight = FontWeight.Bold)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            
            Text(
                text = viewModel.formatDuration(app.totalMinutes),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun EmptyReportsState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📊", fontSize = 64.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text("No activity data yet", fontWeight = FontWeight.Bold)
            Text("Usage reports will appear here once the device is active.", textAlign = TextAlign.Center)
        }
    }
}
