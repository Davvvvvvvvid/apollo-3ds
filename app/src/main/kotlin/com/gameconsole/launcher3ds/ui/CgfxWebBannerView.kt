package com.gameconsole.launcher3ds.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 🎮 深度复刻 3DSLauncher-0.1.20 架构的 3D 横幅渲染视图：
 * 采用原生 WebGL + WASM (decoder.wasm) + Three.js (runtime.js)，
 * 通过直接拦截虚拟资源域 `https://appassets.androidplatform.net/ctr-banner/`，
 * 以高效流传输提供 CGFX 数据并调用 `window.dsosBanner` API，
 * 支持原机动画循环、PICA 着色器、Matcap 反射以及水平手势触控旋转联动。
 */
@SuppressLint("SetJavaScriptEnabled")
class CgfxWebBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val TAG = "CgfxWebBannerView"

    private val webView = WebView(context)
    private var pageLoaded = false

    // 内存中缓存当前待加载或正在展示的 CGFX 数据流
    private val modelDataMap = ConcurrentHashMap<String, ByteArray>()
    private var activeModelId: String? = null
    private var activeTitle: String = ""
    private var pendingLoadId: String? = null

    // 手势触控旋转交互状态
    private var startTouchX: Float = 0f
    private var currentSpinAngle: Float = 0f
    private var isDragging: Boolean = false

    var onBannerReady: ((String) -> Unit)? = null
    var onBannerError: ((String, String) -> Unit)? = null

    inner class NativeBannerBridge {
        @JavascriptInterface
        fun report(id: String, type: String, message: String) {
            Log.i(TAG, "NativeBanner event: id=$id, type=$type, message=$message")
            when (type) {
                "boot" -> {
                    post { warmAndResize() }
                }
                "ready" -> {
                    post {
                        webView.alpha = 1.0f
                        onBannerReady?.invoke(id)
                    }
                }
                "error" -> {
                    post {
                        onBannerError?.invoke(id, message)
                    }
                }
            }
        }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.setLayerType(LAYER_TYPE_HARDWARE, null)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.isClickable = false
        webView.isFocusable = false

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.blockNetworkLoads = true
        s.mediaPlaybackRequiresUserGesture = true
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.setSupportZoom(false)

        webView.addJavascriptInterface(NativeBannerBridge(), "NativeBanner")
        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                if (url.host != "appassets.androidplatform.net") return null
                val path = url.path ?: return null

                // 1. 动态拦截模型请求: /ctr-banner/model/<id>.cgfx
                if (path.startsWith("/ctr-banner/model/")) {
                    val rawName = path.substringAfter("/ctr-banner/model/").removeSuffix(".cgfx")
                    val modelBytes = modelDataMap[rawName]
                    if (modelBytes != null) {
                        val headers = mapOf(
                            "Access-Control-Allow-Origin" to "https://appassets.androidplatform.net",
                            "Cache-Control" to "no-store"
                        )
                        return WebResourceResponse(
                            "application/octet-stream",
                            null,
                            200,
                            "OK",
                            headers,
                            ByteArrayInputStream(modelBytes)
                        )
                    }
                    return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, null)
                }

                // 2. 静态离线资产拦截: /ctr-banner/index.html, runtime.js, decoder.wasm
                if (path.startsWith("/ctr-banner/")) {
                    val assetPath = path.removePrefix("/")
                    return openAssetResponse(assetPath)
                }

                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                warmAndResize()
                val targetId = pendingLoadId
                if (targetId != null) {
                    pendingLoadId = null
                    dispatchLoad(targetId, activeTitle)
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
            assetPath.endsWith(".wav") -> "audio/wav"
            else -> "application/octet-stream"
        }
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "https://appassets.androidplatform.net",
            "Cache-Control" to "no-store"
        )
        val encoding = if (mime.startsWith("text/") || mime == "application/javascript" || mime == "application/json") "UTF-8" else null
        return WebResourceResponse(mime, encoding, 200, "OK", headers, stream)
    }

    private fun warmAndResize() {
        if (!pageLoaded) return
        val w = if (width > 0) width else 400
        val h = if (height > 0) height else 240
        webView.post {
            webView.evaluateJavascript(
                "window.dsosBanner && (window.dsosBanner.warm(), window.dsosBanner.viewport($w, $h));",
                null
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && pageLoaded) {
            webView.post {
                webView.evaluateJavascript("window.dsosBanner && window.dsosBanner.viewport($w, $h);", null)
            }
        }
    }

    fun setCgfxBytes(key: String, bytes: ByteArray?, title: String = "") {
        if (bytes == null || bytes.size < 16) {
            clearBanner()
            return
        }

        val safeKey = key.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        if (activeModelId == safeKey && modelDataMap.containsKey(safeKey)) {
            return
        }

        modelDataMap[safeKey] = bytes
        activeModelId = safeKey
        activeTitle = title
        webView.alpha = 0f

        if (pageLoaded) {
            dispatchLoad(safeKey, title)
        } else {
            pendingLoadId = safeKey
        }
    }

    private fun dispatchLoad(safeKey: String, title: String) {
        val localeTag = Locale.getDefault().toLanguageTag()
        val quotedId = JSONObject.quote(safeKey)
        val quotedLocale = JSONObject.quote(localeTag)
        val quotedTitle = JSONObject.quote(title.ifBlank { safeKey })
        val w = if (width > 0) width else 400
        val h = if (height > 0) height else 240

        webView.post {
            val js = "window.dsosBanner && (window.dsosBanner.viewport($w, $h), window.dsosBanner.configure(true, true), window.dsosBanner.load($quotedId, true, $quotedLocale, $quotedTitle));"
            webView.evaluateJavascript(js, null)
        }
    }

    fun clearBanner() {
        activeModelId = null
        pendingLoadId = null
        webView.alpha = 0f
        if (pageLoaded) {
            webView.post {
                webView.evaluateJavascript("window.dsosBanner && window.dsosBanner.retire(true);", null)
            }
        }
    }

    fun setRotationAngle(angleRad: Float) {
        currentSpinAngle = angleRad
        if (pageLoaded) {
            webView.post {
                webView.evaluateJavascript("window.dsosBanner && window.dsosBanner.spin($angleRad);", null)
            }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startTouchX = event.x
                isDragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val dx = event.x - startTouchX
                    val deltaAngle = (dx / (width.coerceAtLeast(1) * 0.5f)) * Math.PI.toFloat()
                    setRotationAngle(currentSpinAngle + deltaAngle)
                    startTouchX = event.x
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun destroyView() {
        if (pageLoaded) {
            webView.evaluateJavascript("window.dsosBanner && window.dsosBanner.dispose();", null)
        }
        webView.stopLoading()
        webView.destroy()
        modelDataMap.clear()
    }
}
