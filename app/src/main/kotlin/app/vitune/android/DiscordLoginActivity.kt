package app.vitune.android

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import app.vitune.android.preferences.DiscordPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive

class DiscordLoginActivity : ComponentActivity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
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
                        onToken(token)
                    }
                }
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        setContentView(webView)
        if (savedInstanceState == null) webView.loadUrl(LOGIN_URL)
        else webView.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun onToken(token: String) {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)

        DiscordPreferences.token = token
        DiscordPreferences.enabled = true
        finish()
    }

    private fun parseToken(result: String?): String? {
        val raw = runCatching {
            Json.parseToJsonElement(result ?: return null).takeUnless { it is JsonNull }?.jsonPrimitive?.content
        }.getOrNull() ?: return null
        val token = runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrNull() ?: raw
        return token.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val LOGIN_URL = "https://discord.com/login"
        const val TOKEN_SCRIPT =
            "(function(){var f=document.createElement('iframe');document.body.appendChild(f);" +
                "var t=f.contentWindow.localStorage.token;f.remove();return t||null;})()"
    }
}
