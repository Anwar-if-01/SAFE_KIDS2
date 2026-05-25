package com.example.safe_kids.ui.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.safe_kids.auth.AuthManagerImpl
import com.example.safe_kids.data.FamilyRepository
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme

class FamilyChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Removed enableEdgeToEdge() for Android 12 compatibility
        val authManager = AuthManagerImpl(this)
        val repository = FamilyRepository()

        setContent {
            SAFE_KIDSTheme {
                val factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return FamilyViewModel(authManager, repository) as T
                    }
                }
                
                val viewModel: FamilyViewModel = viewModel(factory = factory)
                
                FamilyTabScreen(
                    viewModel = viewModel,
                    onBack = { finish() }
                )
            }
        }
    }
}
