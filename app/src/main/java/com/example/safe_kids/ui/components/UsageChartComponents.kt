package com.example.safe_kids.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class DailyUsage(
    val dayName: String,
    val durationHours: Float
)

@Composable
fun WeeklyUsageChart(
    usageData: List<DailyUsage>,
    modifier: Modifier = Modifier
) {
    val maxUsage = usageData.maxOfOrNull { it.durationHours }?.coerceAtLeast(1f) ?: 1f
    
    Column(modifier = modifier.padding(16.dp)) {
        Text(
            text = "Weekly Activity",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            usageData.forEach { data ->
                UsageBar(
                    label = data.dayName,
                    value = data.durationHours,
                    maxValue = maxUsage
                )
            }
        }
    }
}

@Composable
fun UsageBar(
    label: String,
    value: Float,
    maxValue: Float
) {
    val heightScale by animateFloatAsState(
        targetValue = value / maxValue,
        animationSpec = tween(durationMillis = 1000),
        label = "bar_height"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxHeight()
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .fillMaxHeight(heightScale)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

data class AppUsageInfo(
    val appName: String,
    val packageName: String,
    val durationMillis: Long,
    val color: Color
)

@Composable
fun TopAppsChart(
    apps: List<AppUsageInfo>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(16.dp)) {
        Text(
            text = "Top Used Apps",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        apps.forEach { app ->
            AppUsageRow(app)
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun AppUsageRow(app: AppUsageInfo) {
    val durationText = formatDuration(app.durationMillis)
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(app.color)
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Text(
            text = durationText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun formatDuration(millis: Long): String {
    val minutes = (millis / (1000 * 60)) % 60
    val hours = (millis / (1000 * 60 * 60))
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
