package com.example.safe_kids.ui.onboarding

import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Displays a legal document (ToS or Privacy Policy) in a WebView.
 *
 * @param title     TopAppBar title (e.g. "Terms of Service")
 * @param assetPath Relative path inside assets/  e.g. "terms_of_service.html"
 *                  Pass null to use [remoteUrl] instead.
 * @param remoteUrl Full https:// URL. Used only when [assetPath] is null.
 * @param onBack    Called when the user taps the back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalDocumentScreen(
    title: String,
    assetPath: String? = null,
    remoteUrl: String? = null,
    onBack: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        // Safety: disable JavaScript for local HTML; enable only if remote URL truly requires it
                        settings.javaScriptEnabled = (assetPath == null)
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = (assetPath != null)
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false

                        // Intercept navigation so links don't open another browser
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                            }

                            // Block external navigation — keep user inside the document
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                // Allow same-page anchors and asset files
                                if (url.startsWith("file://") || url.startsWith("about:")) return false
                                // Block everything else (external links)
                                return true
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadProgress = newProgress
                                if (newProgress == 100) isLoading = false
                            }
                        }

                        // Load from assets (local) OR from a remote URL
                        when {
                            assetPath != null ->
                                loadUrl("file:///android_asset/$assetPath")
                            remoteUrl != null ->
                                loadUrl(remoteUrl)
                            else ->
                                loadData(
                                    "<h2>No content available</h2>",
                                    "text/html",
                                    "UTF-8"
                                )
                        }
                    }
                }
            )

            // Loading progress bar at the top of the WebView area
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
