package com.novelreader.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** 內建瀏覽器的主頁清單，可在右上角「首頁」圖示切換 */
val BROWSER_HOME_SITES = listOf(
    "小說之家 (xszj.org)" to "https://m.xszj.org/",
    "CZBooks" to "https://czbooks.net"
)

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebBrowserScreen(
    initialUrl: String = "",
    onBack: () -> Unit,
    onImportHtml: (url: String, html: String) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE) }
    val startUrl = remember {
        initialUrl.ifBlank { prefs.getString("home_url", null) ?: BROWSER_HOME_SITES.first().second }
    }
    var currentUrl by remember { mutableStateOf(startUrl) }
    var homeMenuOpen by remember { mutableStateOf(false) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // 返回鍵優先回到瀏覽器上一頁，無上一頁才離開
    BackHandler {
        val web = webViewInstance
        if (web != null && web.canGoBack()) web.goBack() else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("瀏覽並匯入小說", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { homeMenuOpen = true }) {
                            Icon(Icons.Default.Home, contentDescription = "切換主頁")
                        }
                        DropdownMenu(expanded = homeMenuOpen, onDismissRequest = { homeMenuOpen = false }) {
                            BROWSER_HOME_SITES.forEach { (name, url) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        homeMenuOpen = false
                                        prefs.edit().putString("home_url", url).apply()
                                        webViewInstance?.loadUrl(url)
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { webViewInstance?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新整理")
                    }
                    Button(
                        onClick = {
                            // 呼叫 JavaScript 取得當前頁面的完整 DOM 並透過原生介面回傳
                            webViewInstance?.loadUrl(
                                "javascript:window.HTMLOUT.processHTML(document.documentElement.outerHTML);"
                            )
                        }
                    ) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("加入書架")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun processHTML(html: String) {
                                if (html.isNotBlank()) {
                                    post {
                                        onImportHtml(currentUrl, html)
                                    }
                                }
                            }
                        }, "HTMLOUT")

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                url?.let { currentUrl = it }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                url?.let { currentUrl = it }
                                CookieManager.getInstance().flush()
                            }
                        }
                        loadUrl(startUrl)
                        webViewInstance = this
                    }
                }
            )
        }
    }
}

private fun unescapeJsonString(raw: String): String {
    var s = raw.trim()
    if (s.startsWith("\"") && s.endsWith("\"")) {
        s = s.substring(1, s.length - 1)
    }
    return s.replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\\", "\\")
}
