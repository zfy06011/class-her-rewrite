package com.classher.timetable

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.classher.timetable.platform.WebViewSchoolGateway
import com.classher.timetable.ui.ProbeTheme
import com.classher.timetable.ui.ProbeViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model: ProbeViewModel by viewModels()

    @SuppressLint("SetJavaScriptEnabled") // 学校登录依赖脚本；导航限定官方 HTTPS 域名，无 JS 原生桥。
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WebView.setWebContentsDebuggingEnabled(false)
        setContent {
            ProbeTheme {
                val state by model.state.collectAsStateWithLifecycle()
                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current
                var showingSchool by remember { mutableStateOf(false) }
                var browserMessage by remember { mutableStateOf<String?>(null) }
                var weeks by remember { mutableStateOf("19") }
                var periods by remember { mutableStateOf("12") }
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
                                if (blocked) browserMessage = "学校页面尝试打开不受支持的地址，请停留在官方 HTTPS 个人课表页。"
                                return blocked
                            }
                            override fun onPageFinished(view: WebView, url: String?) {
                                CookieManager.getInstance().flush()
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) browserMessage = "学校页面加载失败，请检查网络后重新打开学校。"
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
                                val online = manager.getNetworkCapabilities(manager.activeNetwork)
                                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                                model.onForeground(gateway, online)
                            }
                            Lifecycle.Event.ON_PAUSE -> {
                                model.cancel()
                                webView.onPause()
                                CookieManager.getInstance().flush()
                            }
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        model.cancel()
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView.stopLoading()
                        webView.destroy()
                    }
                }
                BackHandler(showingSchool) {
                    if (!state.running && webView.canGoBack()) webView.goBack() else showingSchool = false
                }
                Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
                    Column(Modifier.fillMaxSize().padding(insets)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("学校接入验证", style = MaterialTheme.typography.headlineSmall)
                            Text("山东女子学院 · 由你在学校页面输入账号和验证码", style = MaterialTheme.typography.bodyMedium)
                            Text(state.status, style = MaterialTheme.typography.bodyMedium)
                            browserMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    if (browserMessage != null && !state.running) {
                                        browserMessage = null
                                        webView.loadUrl(WebViewSchoolGateway.LOGIN_URL)
                                    }
                                    showingSchool = !showingSchool
                                }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(if (showingSchool) "返回预览" else "打开学校")
                                }
                                Button(
                                    onClick = {
                                        model.configure(weeks.toInt(), periods.toInt())
                                        model.check(gateway)
                                    },
                                    enabled = !state.running && weeks.toIntOrNull()?.let { it in 1..60 } == true &&
                                        periods.toIntOrNull()?.let { it in 1..48 } == true,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("立即获取") }
                            }
                            if (state.running) OutlinedButton(onClick = model::cancel) { Text("取消获取") }
                        }
                        if (showingSchool) {
                            AndroidView(
                                factory = {
                                    (webView.parent as? ViewGroup)?.removeView(webView)
                                    webView
                                },
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            )
                        } else {
                            LazyColumn(
                                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                item {
                                    Text("解析范围需与学校所选学期一致", style = MaterialTheme.typography.titleSmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        OutlinedTextField(
                                            value = weeks, onValueChange = { weeks = it.take(2) },
                                            label = { Text("总周数") }, enabled = !state.running,
                                            modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        )
                                        OutlinedTextField(
                                            value = periods, onValueChange = { periods = it.take(2) },
                                            label = { Text("每日节次") }, enabled = !state.running,
                                            modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        )
                                    }
                                }
                                val snapshot = state.snapshot
                                if (snapshot == null) item {
                                    Card {
                                        Text("暂无预览。登录后进入教学安排 → 个人课表，选中当前学期，再点立即获取。", Modifier.padding(16.dp))
                                    }
                                } else {
                                    item {
                                        Text("${snapshot.scope.year} 学年 · 第 ${snapshot.scope.semester.toIntOrNull()?.plus(1) ?: "?"} 学期")
                                        Text("${snapshot.meetings.size} 条安排 · ${snapshot.unscheduledNames.size} 门未排课课程")
                                        state.repeatedIdentically?.let {
                                            Text(if (it) "两次获取的来源、安排及核对项一致" else "两次获取内容有变化，请核对")
                                        }
                                    }
                                    itemsIndexed(snapshot.doubts) { _, doubt ->
                                        Text("待核对：${doubt.reason}", color = MaterialTheme.colorScheme.error)
                                    }
                                    itemsIndexed(snapshot.unscheduledNames) { _, name ->
                                        Text("未排课：$name")
                                    }
                                    itemsIndexed(snapshot.meetings) { index, meeting ->
                                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("${index + 1}. ${meeting.name}", style = MaterialTheme.typography.titleMedium)
                                                Text("${meeting.teacher} · ${meeting.room.ifBlank { "地点待核对" }}")
                                                Text("周${listOf("一", "二", "三", "四", "五", "六", "日")[meeting.weekday - 1]} · 第 ${meeting.periods.sorted().joinToString(",")} 节")
                                                Text("周次：${meeting.weeks.sorted().joinToString(",")}")
                                            }
                                        }
                                    }
                                }
                                item {
                                    OutlinedButton(onClick = model::clearPreview, enabled = !state.running) { Text("清除本次预览") }
                                    OutlinedButton(onClick = {
                                        CookieManager.getInstance().removeAllCookies {
                                            CookieManager.getInstance().flush()
                                            webView.loadUrl(WebViewSchoolGateway.LOGIN_URL)
                                        }
                                        WebStorage.getInstance().deleteAllData()
                                        webView.clearCache(true)
                                    }, enabled = !state.running) { Text("退出学校会话（保留预览）") }
                                    Text("此验证包仅保留本次进程预览，关闭后需重新获取。", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
