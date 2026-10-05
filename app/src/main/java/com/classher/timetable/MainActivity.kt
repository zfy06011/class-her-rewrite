package com.classher.timetable

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement as LayoutArrangement
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import com.classher.timetable.domain.ThemePreference
import com.classher.timetable.platform.WebViewSchoolGateway
import com.classher.timetable.ui.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model: TimetableViewModel by viewModels()

    @SuppressLint("SetJavaScriptEnabled") // 学校登录依赖脚本；仅官方 HTTPS，禁止原生桥和调试。
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WebView.setWebContentsDebuggingEnabled(false)
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val dark = when (state.theme) { ThemePreference.SYSTEM -> isSystemInDarkTheme(); ThemePreference.LIGHT -> false; ThemePreference.DARK -> true }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            ProbeTheme(state.theme) {
                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current
                var showingSchool by remember { mutableStateOf(false) }
                var browserMessage by remember { mutableStateOf<String?>(null) }
                val webView = remember {
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val blocked = !WebViewSchoolGateway.isSchoolUrl(request.url.toString())
                                if (blocked) browserMessage = "请停留在学校官方 HTTPS 页面。"
                                return blocked
                            }
                            override fun onPageFinished(view: WebView, url: String?) { CookieManager.getInstance().flush() }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) browserMessage = "学校页面加载失败，请检查网络后重试。"
                            }
                        }
                        loadUrl(WebViewSchoolGateway.LOGIN_URL)
                    }
                }
                val gateway = remember(webView) {
                    WebViewSchoolGateway(webView, context.assets.open("sdwu-fetch.js").bufferedReader().use { it.readText() })
                }
                DisposableEffect(webView, lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> {
                                webView.onResume()
                                val manager = context.getSystemService(ConnectivityManager::class.java)
                                model.onForeground(gateway, manager.getNetworkCapabilities(manager.activeNetwork)
                                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
                            }
                            Lifecycle.Event.ON_PAUSE -> { model.onBackground(); webView.onPause(); CookieManager.getInstance().flush() }
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        val manager = context.getSystemService(ConnectivityManager::class.java)
                        model.onForeground(gateway, manager.getNetworkCapabilities(manager.activeNetwork)
                            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
                    }
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        model.onBackground()
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView.stopLoading(); webView.destroy()
                    }
                }
                BackHandler(showingSchool) {
                    if (!state.busy && webView.canGoBack()) webView.goBack() else showingSchool = false
                }
                if (showingSchool) {
                    Scaffold { insets ->
                        Column(Modifier.fillMaxSize().padding(insets)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("山东女子学院", style = MaterialTheme.typography.titleLarge)
                                Text("由你本人输入账号、密码和验证码", style = MaterialTheme.typography.bodySmall)
                                browserMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                Row(horizontalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { showingSchool = false }, modifier = Modifier.weight(1f)) { Text("返回课表") }
                                    Button(onClick = { model.check(gateway); showingSchool = false }, enabled = !state.busy && !state.loading && !state.loadFailed,
                                        modifier = Modifier.weight(1f)) { Text("获取个人课表") }
                                }
                                if (browserMessage != null) TextButton(onClick = {
                                    browserMessage = null; webView.loadUrl(WebViewSchoolGateway.LOGIN_URL)
                                }) { Text("重试") }
                            }
                            AndroidView(factory = {
                                (webView.parent as? ViewGroup)?.removeView(webView); webView
                            }, modifier = Modifier.fillMaxWidth().weight(1f))
                        }
                    }
                } else {
                    TimetableApp(state = state, model = model, openSchool = { showingSchool = true },
                        fetch = { model.check(gateway) }, checkUpdates = { model.check(gateway, useStoredConfig = true) }, logout = {
                            CookieManager.getInstance().removeAllCookies {
                                CookieManager.getInstance().flush(); webView.loadUrl(WebViewSchoolGateway.LOGIN_URL)
                            }
                            WebStorage.getInstance().deleteAllData(); webView.clearCache(true)
                            model.schoolSessionCleared()
                        })
                }
            }
        }
    }
}
