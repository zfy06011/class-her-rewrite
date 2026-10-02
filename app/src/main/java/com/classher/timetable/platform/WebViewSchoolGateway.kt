package com.classher.timetable.platform

import android.webkit.WebView
import com.classher.timetable.domain.accountDigest
import com.classher.timetable.domain.RawSchoolResponse
import com.classher.timetable.domain.SchoolException
import com.classher.timetable.domain.SchoolFailure
import com.classher.timetable.domain.SchoolGateway
import com.classher.timetable.domain.SourceScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.util.UUID
import kotlin.coroutines.resume

class WebViewSchoolGateway(private val webView: WebView, private val script: String) : SchoolGateway {
    override suspend fun fetch(): RawSchoolResponse = withContext(Dispatchers.Main.immediate) {
        if (!isSchoolUrl(webView.url)) throw SchoolException(SchoolFailure.LOGIN_REQUIRED)
        val key = "__classHer_${UUID.randomUUID().toString().replace("-", "")}" // 每次获取独立结果槽。
        val quotedKey = JSONObject.quote(key)
        try {
            withTimeout(30_000) {
                evaluate(script.replace("__REQUEST_KEY__", quotedKey))
                var ready: RawSchoolResponse? = null
                while (ready == null) {
                    // 每次轮询前检查来源，导航后不在未知页面执行读取。
                    if (!isSchoolUrl(webView.url)) throw SchoolException(SchoolFailure.LOGIN_REQUIRED)
                    val result = evaluate("JSON.stringify(window[$quotedKey] || {state:'pending'})")
                    val encoded = JSONTokener(result).nextValue() as? String
                    if (encoded == null) throw SchoolException(SchoolFailure.INCOMPLETE)
                    val payload = JSONObject(encoded)
                    when (payload.optString("state")) {
                        "ready" -> {
                            val account = payload.getString("account")
                            ready = RawSchoolResponse(
                                payload.getString("html"),
                                SourceScope(accountDigest(account), payload.getString("year"), payload.getString("semester")),
                            )
                        }
                        "error" -> throw SchoolException(
                            SchoolFailure.entries.firstOrNull { it.name == payload.optString("code") }
                                ?: SchoolFailure.NETWORK,
                        )
                    }
                    if (ready == null) delay(200)
                }
                requireNotNull(ready)
            }
        } finally {
            // 取消网络并移除包含私人 HTML 的内存槽；不写磁盘。
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (isSchoolUrl(webView.url)) {
                    webView.evaluateJavascript(
                        "window[${JSONObject.quote(key + "_cancel")}]?.(); delete window[$quotedKey]; " +
                            "delete window[${JSONObject.quote(key + "_cancel")}];",
                        null,
                    )
                }
            }
        }
    }

    private suspend fun evaluate(script: String): String = suspendCancellableCoroutine { continuation ->
        webView.evaluateJavascript(script) { result ->
            if (continuation.isActive) continuation.resume(result)
        }
    }

    companion object {
        const val LOGIN_URL = "https://jwxt.sdwu.edu.cn/sdnzjw/cas/login.action"
        fun isSchoolUrl(url: String?): Boolean = try {
            val uri = URI(url ?: "")
            uri.scheme == "https" && uri.host == "jwxt.sdwu.edu.cn" && uri.port in listOf(-1, 443)
        } catch (_: Exception) { false }
    }
}
