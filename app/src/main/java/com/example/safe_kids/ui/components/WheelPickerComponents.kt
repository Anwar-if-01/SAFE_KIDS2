package com.example.safe_kids.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// Convert 24h to 12h components: returns Triple(hour12, minute, amPmIndex) where amPmIndex 0=AM, 1=PM
private fun to12h(hour24: Int): Pair<Int, Int> {
    val amPm = if (hour24 < 12) 0 else 1
    val h = when {
        hour24 == 0 -> 12
        hour24 > 12 -> hour24 - 12
        else -> hour24
    }
    return Pair(h, amPm)
}

// Convert 12h back to 24h
private fun to24h(hour12: Int, amPmIndex: Int): Int {
    return when {
        amPmIndex == 0 && hour12 == 12 -> 0       // 12 AM = 0
        amPmIndex == 1 && hour12 == 12 -> 12       // 12 PM = 12
        amPmIndex == 1 -> hour12 + 12              // PM
        else -> hour12                              // AM
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeRangeWheelPickerSheet(
    initialStartHour: Int,
    initialStartMinute: Int,
    initialEndHour: Int,
    initialEndMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int, Int, Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val (initStartH12, initStartAmPm) = to12h(initialStartHour)
    val (initEndH12, initEndAmPm) = to12h(initialEndHour)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.LightGray.copy(alpha = 0.5f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Text("Start", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("End", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }

            Spacer(Modifier.height(24.dp))

            var startH12 by remember { mutableStateOf(initStartH12) }
            var startM by remember { mutableStateOf(initialStartMinute) }
            var startAmPm by remember { mutableStateOf(initStartAmPm) }
            var endH12 by remember { mutableStateOf(initEndH12) }
            var endM by remember { mutableStateOf(initialEndMinute) }
            var endAmPm by remember { mutableStateOf(initEndAmPm) }

            Box(contentAlignment = Alignment.Center) {
                Column {
                    Divider(modifier = Modifier.padding(horizontal = 8.dp), thickness = 1.dp, color = Color.Gray.copy(alpha = 0.5f))
                    Spacer(Modifier.height(40.dp))
                    Divider(modifier = Modifier.padding(horizontal = 8.dp), thickness = 1.dp, color = Color.Gray.copy(alpha = 0.5f))
                }

                Row(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Start: Hour (1–12)
                    WheelColumn(items = (1..12).toList(), initialIndex = initStartH12 - 1) { startH12 = it }
                    Text(":", fontWeight = FontWeight.Bold)
                    // Start: Minute
                    WheelColumn(items = (0..59).toList(), initialIndex = initialStartMinute) { startM = it }
                    // Start: AM/PM
                    WheelColumnText(items = listOf("AM", "PM"), initialIndex = initStartAmPm) { startAmPm = it }

                    Text("-", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))

                    // End: Hour (1–12)
                    WheelColumn(items = (1..12).toList(), initialIndex = initEndH12 - 1) { endH12 = it }
                    Text(":", fontWeight = FontWeight.Bold)
                    // End: Minute
                    WheelColumn(items = (0..59).toList(), initialIndex = initialEndMinute) { endM = it }
                    // End: AM/PM
                    WheelColumnText(items = listOf("AM", "PM"), initialIndex = initEndAmPm) { endAmPm = it }
                }
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = {
                    onConfirm(
                        to24h(startH12, startAmPm), startM,
                        to24h(endH12, endAmPm), endM
                    )
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4C66EA)),
                shape = RoundedCornerShape(27.dp)
            ) {
                Text("OK", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 8.dp)) {
                Text("Cancel", color = Color.Gray, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WheelColumnText(
    items: List<String>,
    initialIndex: Int,
    onItemSelected: (Int) -> Unit
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val snappingBehavior = rememberSnapFlingBehavior(lazyListState = state)

    LaunchedEffect(state.firstVisibleItemIndex) {
        onItemSelected(state.firstVisibleItemIndex % items.size)
    }

    Box(modifier = Modifier.width(44.dp).height(120.dp), contentAlignment = Alignment.Center) {
        LazyColumn(
            state = state,
            flingBehavior = snappingBehavior,
            contentPadding = PaddingValues(vertical = 40.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(items.size * 100) { index ->
                val item = items[index % items.size]
                Box(modifier = Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                    val isSelected = (index % items.size) == (state.firstVisibleItemIndex % items.size)
                    Text(
                        text = item,
                        fontSize = if (isSelected) 18.sp else 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) Color.Black else Color.LightGray
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WheelColumn(
    items: List<Int>,
    initialIndex: Int,
    onItemSelected: (Int) -> Unit
) {
    // We use a large multiplier to simulate infinite scrolling if needed, but for time 24/60 is fine.
    // To match the image's "3 rows visible" look, we use a fixed height.
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    
    // Snap behavior ensures selection
    val snappingBehavior = rememberSnapFlingBehavior(lazyListState = state)
    
    // Track selection
    LaunchedEffect(state.firstVisibleItemIndex) {
        onItemSelected(items[state.firstVisibleItemIndex % items.size])
    }
    
    Box(modifier = Modifier.width(50.dp).height(120.dp), contentAlignment = Alignment.Center) {
        LazyColumn(
            state = state,
            flingBehavior = snappingBehavior,
            contentPadding = PaddingValues(vertical = 40.dp), // Center the items
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(items.size * 100) { index ->
                val item = items[index % items.size]
                Box(
                    modifier = Modifier.height(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val isSelected = (index % items.size) == (state.firstVisibleItemIndex % items.size)
                    Text(
                        text = String.format("%02d", item),
                        fontSize = if (isSelected) 22.sp else 18.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) Color.Black else Color.LightGray
                    )
                }
            }
        }
    }
}
