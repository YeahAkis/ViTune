package app.vitune.android.ui.screens.settings

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive

private const val LOGIN_URL = "https://discord.com/login"
private const val TOKEN_SCRIPT =
    "(function(){var f=document.createElement('iframe');document.body.appendChild(f);" +
        "var t=f.contentWindow.localStorage.token;f.remove();return t||null;})()"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DiscordLoginDialog(
    onDismiss: () -> Unit,
    onToken: (String) -> Unit
) {
    val currentOnToken by rememberUpdatedState(onToken)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    webViewClient = object : WebViewClient() {
                        private var done = false

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                            request.url.scheme?.startsWith("http") != true

                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                            if (done || url == null) return
                            if (!url.contains("/channels/") && !url.endsWith("/app")) return

                            view.evaluateJavascript(TOKEN_SCRIPT) { result ->
                                val token = parseToken(result) ?: return@evaluateJavascript
                                done = true
                                view.stopLoading()
                                clearDiscordWebData()
                                currentOnToken(token)
                            }
                        }
                    }

                    loadUrl(LOGIN_URL)
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun parseToken(result: String?): String? {
    val raw = runCatching {
        Json.parseToJsonElement(result ?: return null).takeUnless { it is JsonNull }?.jsonPrimitive?.content
    }.getOrNull() ?: return null
    val token = runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrNull() ?: raw
    return token.takeIf { it.isNotBlank() }
}

private fun clearDiscordWebData() {
    WebStorage.getInstance().deleteAllData()
    CookieManager.getInstance().removeAllCookies(null)
}
