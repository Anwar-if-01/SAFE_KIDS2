package com.example.safe_kids.ui.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.auth.AuthConfig
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class KeywordCategory(val name: String, val keywords: List<String>)

class KeywordManagementActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Removed enableEdgeToEdge() for Android 12 compatibility
        val childId = intent.getStringExtra("CHILD_ID") ?: ""
        setContent {
            SAFE_KIDSTheme { KeywordManagementScreen(childId = childId, onBack = { finish() }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeywordManagementScreen(childId: String, onBack: () -> Unit) {
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    var categories by remember { mutableStateOf<List<KeywordCategory>>(emptyList()) }
    var showAddCategoryScreen by remember { mutableStateOf(false) }

    LaunchedEffect(childId) {
        if (childId.isEmpty()) return@LaunchedEffect
        db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
            .addSnapshotListener { snap, _ ->
                @Suppress("UNCHECKED_CAST")
                val cats = snap?.get("keywordCategories") as? List<Map<String, Any>> ?: emptyList()
                categories = cats.map { c ->
                    KeywordCategory(
                        name = c["name"] as? String ?: "",
                        keywords = (c["keywords"] as? List<String>) ?: emptyList()
                    )
                }
            }
    }

    if (showAddCategoryScreen) {
        AddKeywordsScreen(
            childId = childId,
            onBack = { showAddCategoryScreen = false },
            onSave = { catName, keywords ->
                scope.launch {
                    val snap = db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId).get().await()
                    @Suppress("UNCHECKED_CAST")
                    val existing = snap.get("keywordCategories") as? List<Map<String, Any>> ?: emptyList()
                    val updated = existing.toMutableList()
                    updated.add(mapOf("name" to catName, "keywords" to keywords))
                    db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                        .set(mapOf("keywordCategories" to updated), com.google.firebase.firestore.SetOptions.merge()).await()
                    showAddCategoryScreen = false
                }
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Keyword Management", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Color(0xFFF4F6F9)
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Tab selector
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp)
                    .background(Color(0xFFEEEEEE), RoundedCornerShape(24.dp)).padding(4.dp)
            ) {
                listOf("Customize", "Subscription").forEachIndexed { index, label ->
                    Box(
                        modifier = Modifier.weight(1f)
                            .background(if (selectedTab == index) Color.White else Color.Transparent, RoundedCornerShape(20.dp))
                            .clickable { selectedTab = index }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == index) Color(0xFF4C66EA) else Color.Gray,
                            fontSize = 14.sp)
                    }
                }
            }

            if (selectedTab == 0) {
                if (categories.isEmpty()) {
                    // Empty state
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("📝", fontSize = 64.sp)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Add keywords to receive alerts when the words are detected",
                            textAlign = TextAlign.Center, color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(32.dp))
                        Button(
                            onClick = { showAddCategoryScreen = true },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4C66EA)),
                            shape = RoundedCornerShape(27.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("AI Keyword Assistant", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Create Custom Keywords",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4C66EA),
                            modifier = Modifier.clickable { showAddCategoryScreen = true }
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item { Spacer(Modifier.height(4.dp)) }
                        items(categories) { cat ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                shape = RoundedCornerShape(16.dp),
                                elevation = CardDefaults.cardElevation(1.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(cat.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Icon(Icons.Default.RemoveCircle, null, tint = Color.Red,
                                            modifier = Modifier.size(22.dp).clickable {
                                                scope.launch {
                                                    val snap = db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId).get().await()
                                                    @Suppress("UNCHECKED_CAST")
                                                    val existing = snap.get("keywordCategories") as? List<Map<String, Any>> ?: emptyList()
                                                    val updated = existing.filter { it["name"] != cat.name }
                                                    db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                                                        .set(mapOf("keywordCategories" to updated), com.google.firebase.firestore.SetOptions.merge()).await()
                                                }
                                            })
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    cat.keywords.forEach { kw ->
                                        Surface(color = Color(0xFFE8EAF6), shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(end = 6.dp, bottom = 6.dp)) {
                                            Text(kw, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 13.sp, color = Color(0xFF4C66EA))
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            Button(
                                onClick = { showAddCategoryScreen = true },
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4C66EA)),
                                shape = RoundedCornerShape(27.dp)
                            ) {
                                Icon(Icons.Default.Add, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Add Category", fontWeight = FontWeight.Bold)
                            }
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Subscription plans coming soon", color = Color.Gray)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddKeywordsScreen(childId: String, onBack: () -> Unit, onSave: (String, List<String>) -> Unit) {
    var categoryName by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf<List<String>>(emptyList()) }
    var showKeywordDialog by remember { mutableStateOf(false) }
    var keywordInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Keywords", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    TextButton(onClick = { if (categoryName.isNotBlank()) onSave(categoryName, keywords) }) {
                        Text("Save", color = Color(0xFF4C66EA), fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Color(0xFFF4F6F9)
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Category name
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(1.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Category Name", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    BasicTextField(
                        value = categoryName,
                        onValueChange = { categoryName = it },
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (categoryName.isEmpty()) Text("Enter category name", color = Color.LightGray)
                            inner()
                        }
                    )
                }
            }

            Text("Keyword Management", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(1.dp)) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Enter keyword", color = Color.Gray, fontWeight = FontWeight.Medium)
                        IconButton(onClick = { keywordInput = ""; showKeywordDialog = true }) {
                            Icon(Icons.Default.AddCircle, null, tint = Color(0xFF4C66EA), modifier = Modifier.size(28.dp))
                        }
                    }
                    if (keywords.isEmpty()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📁", fontSize = 48.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("No keywords added yet", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        keywords.forEach { kw ->
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(kw, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                Icon(Icons.Default.RemoveCircle, null, tint = Color.Red,
                                    modifier = Modifier.size(20.dp).clickable { keywords = keywords.filter { it != kw } })
                            }
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.3f))
                        }
                    }
                }
            }
        }
    }

    if (showKeywordDialog) {
        AlertDialog(
            onDismissRequest = { showKeywordDialog = false },
            title = { Text("Enter keyword", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
            text = {
                OutlinedTextField(
                    value = keywordInput, onValueChange = { keywordInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF4C66EA)),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = { if (keywordInput.isNotBlank()) { keywords = keywords + keywordInput.trim(); showKeywordDialog = false } },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4C66EA)),
                    shape = RoundedCornerShape(24.dp)
                ) { Text("OK", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showKeywordDialog = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color.White, shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun BasicTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier, decorationBox: @Composable (@Composable () -> Unit) -> Unit) {
    androidx.compose.foundation.text.BasicTextField(value = value, onValueChange = onValueChange, modifier = modifier, decorationBox = decorationBox)
}
