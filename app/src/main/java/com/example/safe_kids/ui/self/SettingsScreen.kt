package com.example.safe_kids.ui.self

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.auth.UserRole
import com.example.safe_kids.auth.isParentLike
import com.example.safe_kids.data.ThemeMode
import com.example.safe_kids.auth.AuthManager
import com.example.safe_kids.ui.components.AdminCodeVerificationDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    authManager: AuthManager,
    onBack: () -> Unit
) {
    val userRole by viewModel.userRole.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val textSize by viewModel.textSize.collectAsState()
    val displaySize by viewModel.displaySize.collectAsState()
    val language by viewModel.language.collectAsState()
    
    var showAdminCodeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // General Settings Header
            SettingsHeader("App Preferences")

            // Language selection
            val languageDisplayName = remember(language) {
                when(language) {
                    "tr" -> "Turkish"
                    "es" -> "Spanish"
                    "fr" -> "French"
                    "de" -> "German"
                    "ru" -> "Russian"
                    else -> "English"
                }
            }
            
            SettingsItem(
                title = "Language",
                subtitle = languageDisplayName,
                icon = Icons.Default.Language,
                onClick = { showLanguageDialog = true }
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))
            SettingsHeader("Display & Theme")

            // Theme Selection Row (Simpler for demo)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ThemeOption("Light", themeMode == ThemeMode.LIGHT) { viewModel.setThemeMode(ThemeMode.LIGHT) }
                ThemeOption("Dark", themeMode == ThemeMode.DARK) { viewModel.setThemeMode(ThemeMode.DARK) }
                ThemeOption("System", themeMode == ThemeMode.SYSTEM) { viewModel.setThemeMode(ThemeMode.SYSTEM) }
            }

            SettingsHeader("Accessibility")

            TextSizeSlider(textSize) { viewModel.setTextSize(it) }
            DisplaySizeSlider(displaySize) { viewModel.setDisplaySize(it) }

            // Conditional Settings (Parent Mode Only)
            if (userRole.isParentLike) {
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                SettingsHeader("Account")

                SettingsItem(
                    title = "Switch Mode",
                    subtitle = "Switch between Parent and Child mode",
                    icon = Icons.Default.SwapHoriz,
                    onClick = { showAdminCodeDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
            
            // App Info
            Text(
                text = "Safe Kids v1.0.0",
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showAdminCodeDialog) {
        AdminCodeVerificationDialog(
            authManager = authManager,
            onDismiss = { showAdminCodeDialog = false },
            onSuccess = {
                showAdminCodeDialog = false
                viewModel.switchMode()
            }
        )
    }

    if (showLanguageDialog) {
        LanguagePickerDialog(
            currentLanguageTag = language,
            onDismiss = { showLanguageDialog = false },
            onSelect = { tag ->
                viewModel.setLanguage(tag)
                showLanguageDialog = false
            }
        )
    }
}

@Composable
fun LanguagePickerDialog(
    currentLanguageTag: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val languages = listOf(
        "en" to "English",
        "tr" to "Turkish",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "ru" to "Russian"
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Language", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                languages.forEach { (tag, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(tag) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = tag == currentLanguageTag,
                            onClick = { onSelect(tag) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SettingsHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }

    )
}

@Composable
fun ThemeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
        } else null
    )
}

@Composable
fun TextSizeSlider(value: Float, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.TextFields, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Text Size: ${(value * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0.8f..1.5f,
            steps = 6,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun DisplaySizeSlider(value: Float, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AspectRatio, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Display Size: ${(value * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0.8f..1.5f,
            steps = 6,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

private fun String.capitalizeWord() = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

