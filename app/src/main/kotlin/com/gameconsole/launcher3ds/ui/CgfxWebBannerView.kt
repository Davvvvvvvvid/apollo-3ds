package com.gameconsole.launcher3ds.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.AttributeSet
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.InputStream

/**
 * 复刻 3DSLauncher-0.1.20 架构的 WebView + WASM + Three.js 3DS 横幅渲染视图：
 * 使用 Rust 编译的 decoder.wasm 实时将 CGFX 转为带 EXT_cgfx_pica 的 GLB，
 * 并通过 Three.js + 自定义 PICA ShaderMaterial 播放原机动画与材质通道。
 */
@SuppressLint("SetJavaScriptEnabled")
class CgfxWebBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val webView = WebView(context)
    private var pageLoaded = false
    private var pendingBase64: String? = null
    private var lastLoadedKey: String? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.setLayerType(LAYER_TYPE_HARDWARE, null)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.isClickable = false
        webView.isFocusable = false
        webView.setOnTouchListener { _, _ -> true }

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.cacheMode = WebSettings.LOAD_DEFAULT

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                if (url.host != "appassets.androidplatform.net") return null
                val path = url.path ?: return null
                if (!path.startsWith("/ctr-banner/")) return null
                val assetPath = path.removePrefix("/")
                return openAssetResponse(assetPath)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                val b64 = pendingBase64
                if (b64 != null) {
                    pendingBase64 = null
                    dispatchLoadBase64(b64)
                }
            }
        }

        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        webView.loadUrl("https://appassets.androidplatform.net/ctr-banner/index.html")
    }

    private fun openAssetResponse(assetPath: String): WebResourceResponse? {
        val stream: InputStream = try {
            context.assets.open(assetPath)
        } catch (_: Throwable) {
            return null
        }
        val mime = when {
            assetPath.endsWith(".html") -> "text/html"
            assetPath.endsWith(".js") -> "application/javascript"
            assetPath.endsWith(".wasm") -> "application/wasm"
            assetPath.endsWith(".json") -> "application/json"
            else -> "application/octet-stream"
        }
        val headers = mapOf("Access-Control-Allow-Origin" to "*")
        return WebResourceResponse(mime, "UTF-8", 200, "OK", headers, stream)
    }

    fun setCgfxBytes(key: String, bytes: ByteArray?) {
        if (bytes == null || bytes.size < 16) {
            clearBanner()
            return
        }
        if (lastLoadedKey == key) return
        lastLoadedKey = key
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        if (pageLoaded) {
            dispatchLoadBase64(b64)
        } else {
            pendingBase64 = b64
        }
    }

    fun clearBanner() {
        lastLoadedKey = null
        pendingBase64 = null
        if (pageLoaded) {
            webView.post {
                webView.evaluateJavascript("window.clearBanner && window.clearBanner();", null)
            }
        }
    }

    private fun dispatchLoadBase64(b64: String) {
        webView.post {
            webView.evaluateJavascript("window.loadCgfxBase64 && window.loadCgfxBase64('$b64');", null)
        }
    }

    fun destroyView() {
        webView.stopLoading()
        webView.destroy()
    }
}
