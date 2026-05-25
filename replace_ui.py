import sys

with open("c:/Users/MURAD/AndroidStudioProjects/SAFE_KIDS2/app/src/main/java/com/example/safe_kids/ui/parent/ParentDashboardActivity.kt", "r", encoding="utf-8") as f:
    lines = f.readlines()

start_idx = -1
end_idx = -1

for i, line in enumerate(lines):
    if line.startswith("@Composable") and "fun DeviceManagementTab(" in lines[i+1]:
        start_idx = i
    if line.startswith("@Composable") and "fun ReportsTab(" in lines[i+1]:
        end_idx = i
        break

if start_idx != -1 and end_idx != -1:
    content_before = "".join(lines[:start_idx])
    content_after = "".join(lines[end_idx:])
    
    new_ui = """@Composable
fun DeviceManagementTab(
    child: ChildProfile?,
    authManager: AuthManagerImpl,
    appBlockingService: AppBlockingServiceImpl,
    notificationService: NotificationServiceImpl,
    parentActionsManager: ParentActionsManager,
    isAuthenticated: Boolean,
    onActionBlocked: () -> Unit = {}
) {
    if (child == null) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            EmptyStateCard(
                title = "No Children Added",
                description = "Add your first child to start managing their device",
                icon = Icons.Default.PersonAdd
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF4F6F9)),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            RecommendedRulesBanner()
        }
        item {
            QuickActionGrid()
        }
        item {
            NewAppBanner()
        }
        item {
            SettingsCategoryCard("Screen Time & Apps", listOf(
                SettingsMenuAction("Instant Block", Icons.Default.LockClock, hasSwitch = true),
                SettingsMenuAction("Downtime", Icons.Default.Bedtime, hasArrow = true),
                SettingsMenuAction("App Management", Icons.Default.Apps, hasArrow = true)
            ))
        }
        item {
            SettingsCategoryCard("Content Safety", listOf(
                SettingsMenuAction("Notification Monitoring", Icons.Default.NotificationsActive, hasArrow = true),
                SettingsMenuAction("Social Content Detection", Icons.Default.Visibility, hasArrow = true),
                SettingsMenuAction("Inappropriate Image Detection", Icons.Default.Image, hasArrow = true),
                SettingsMenuAction("Calls & SMS Monitoring", Icons.Default.Message, hasArrow = true)
            ))
        }
        item {
            SettingsCategoryCard("Safe Browsing", listOf(
                SettingsMenuAction("Website Restrictions", Icons.Default.Language, hasArrow = true),
                SettingsMenuAction("Browsing History", Icons.Default.History, hasArrow = true)
            ))
        }
        item {
            SettingsCategoryCard("Devices & Support", listOf(
                SettingsMenuAction("Check the Required Permissions", Icons.Default.CheckCircle, badgeCount = 5, hasArrow = true),
                SettingsMenuAction("SOS Settings", Icons.Default.Sos, hasArrow = true),
                SettingsMenuAction("Check Updates", Icons.Default.SystemUpdateAlt, hasArrow = true),
                SettingsMenuAction("Enable Augmented Supervision Mode", Icons.Default.Security, badgeDot = true, hasArrow = true),
                SettingsMenuAction("Open the Hidden AirDroid Kids?", Icons.Default.QuestionMark, hasArrow = true)
            ))
        }
        
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
fun RecommendedRulesBanner() {
    Card(
        modifier = Modifier.fillMaxWidth().height(100.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("Start with Recommended Rules!", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
            }
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5C6BC0)), shape = RoundedCornerShape(20.dp)) {
                Text("Start")
            }
        }
    }
}

@Composable
fun QuickActionGrid() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        QuickActionButton("Remote Camera", Icons.Default.CameraAlt, Color(0xFF3F51B5))
        QuickActionButton("Screen Mirroring", Icons.Default.Screenshot, Color(0xFFFF9800))
        QuickActionButton("One-Way Audio", Icons.Default.HeadsetMic, Color(0xFF4CAF50))
    }
}

@Composable
fun QuickActionButton(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(90.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.size(64.dp), shadowElevation = 2.dp) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.bodySmall, color = Color(0xFF1E293B), textAlign = TextAlign.Center, lineHeight = 14.sp)
    }
}

@Composable
fun NewAppBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = Color(0xFFFF9800))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("New App Installed", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text("Motorola moto g stylus 5G installed a new app: Parental Control", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = Color.Red, modifier = Modifier.size(24.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("4", color = Color.White, style = MaterialTheme.typography.labelSmall) }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
        }
    }
}

data class SettingsMenuAction(
    val title: String, 
    val icon: androidx.compose.ui.graphics.vector.ImageVector, 
    val hasSwitch: Boolean = false, 
    val hasArrow: Boolean = false,
    val badgeCount: Int = 0,
    val badgeDot: Boolean = false
)

@Composable
fun SettingsCategoryCard(title: String, items: List<SettingsMenuAction>) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp, start = 8.dp)) {
            Box(modifier = Modifier.size(4.dp, 16.dp).background(Color(0xFF5C6BC0), RoundedCornerShape(2.dp)))
            Spacer(modifier = Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
        }
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 1.dp
        ) {
            Column {
                items.forEachIndexed { index, item ->
                    var switchState by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(item.icon, contentDescription = null, tint = Color(0xFF64748B))
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(item.title, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF1E293B), modifier = Modifier.weight(1f))
                        
                        if (item.hasSwitch) {
                            Switch(checked = switchState, onCheckedChange = { switchState = it }, modifier = Modifier.size(40.dp, 20.dp))
                        } else if (item.badgeCount > 0) {
                            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = Color.Red, modifier = Modifier.size(24.dp)) {
                                Box(contentAlignment = Alignment.Center) { Text(item.badgeCount.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall) }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        } else if (item.badgeDot) {
                            Box(modifier = Modifier.size(8.dp).background(Color.Red, androidx.compose.foundation.shape.CircleShape))
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        
                        if (item.hasArrow) {
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.LightGray)
                        }
                    }
                    if (index < items.size - 1) {
                        HorizontalDivider(color = Color(0xFFF1F5F9), modifier = Modifier.padding(start = 56.dp))
                    }
                }
            }
        }
    }
}

"""
    
    with open("c:/Users/MURAD/AndroidStudioProjects/SAFE_KIDS2/app/src/main/java/com/example/safe_kids/ui/parent/ParentDashboardActivity.kt", "w", encoding="utf-8") as f:
        f.write(content_before + new_ui + content_after)
    print("UI Overhauled successfully")
else:
    print("Could not find start/end bounds")
