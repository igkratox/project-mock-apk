package com.igkratox.projectmock

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadBar: ProgressBar
    private lateinit var permGateLayout: LinearLayout
    private var fileUploadCb: ValueCallback<Array<Uri>>? = null
    private var webLoaded = false
    private var pausedAtMs = 0L
    private var kioskActive = false
    private val baseUrl = "https://igkratox.github.io/mock-options-without-socials/"

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val uris = if (res.resultCode == Activity.RESULT_OK && res.data != null) {
                WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
            } else null
            fileUploadCb?.onReceiveValue(uris)
            fileUploadCb = null
        }

    inner class ExamBridge {
        @JavascriptInterface
        fun setKiosk(enabled: Boolean) {
            runOnUiThread {
                if (enabled && !kioskActive) {
                    try {
                        startLockTask()
                        kioskActive = true
                    } catch (_: Exception) { }
                } else if (!enabled && kioskActive) {
                    try {
                        stopLockTask()
                        kioskActive = false
                    } catch (_: Exception) { }
                }
            }
        }

        @JavascriptInterface
        fun isExamApp(): Boolean = true
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Block screenshots & screen recording + keep screen awake during exam
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        loadBar = findViewById(R.id.loadBar)
        permGateLayout = findViewById(R.id.permGateLayout)

        findViewById<MaterialButton>(R.id.grantPermBtn).setOnClickListener {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
        }
        findViewById<MaterialButton>(R.id.recheckPermBtn).setOnClickListener {
            checkPermissionAndBoot()
        }

        setupWebView()
        checkPermissionAndBoot()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val ws = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.databaseEnabled = true
        ws.allowFileAccess = true
        ws.mediaPlaybackRequiresUserGesture = false
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        ws.userAgentString = ws.userAgentString + " ProjectMockApp/1.0"

        webView.addJavascriptInterface(ExamBridge(), "ExamAppBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("https://igkratox.github.io") ||
                    url.startsWith("https://drive.google.com") ||
                    url.startsWith("https://accounts.google.com") ||
                    url.startsWith("https://www.youtube.com") ||
                    url.startsWith("https://youtu.be")
                ) {
                    return false
                }
                return false
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                loadBar.progress = newProgress
                loadBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCb?.onReceiveValue(null)
                fileUploadCb = filePathCallback
                return try {
                    val i = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "application/pdf"
                    }
                    fileChooserLauncher.launch(i)
                    true
                } catch (_: Exception) {
                    fileUploadCb = null
                    false
                }
            }
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun checkPermissionAndBoot() {
        if (hasUsageStatsPermission()) {
            permGateLayout.visibility = View.GONE
            webView.visibility = View.VISIBLE
            if (!webLoaded) {
                webLoaded = true
                val target = intent?.dataString?.takeIf { it.startsWith("https://igkratox.github.io/mock-options-without-socials") }
                    ?: baseUrl
                webView.loadUrl(target)
            }
        } else {
            permGateLayout.visibility = View.VISIBLE
            webView.visibility = View.GONE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val u = intent.dataString
        if (hasUsageStatsPermission() && u != null && u.startsWith("https://igkratox.github.io/mock-options-without-socials")) {
            webView.loadUrl(u)
        }
    }

    override fun onPause() {
        super.onPause()
        pausedAtMs = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionAndBoot()
        if (webLoaded && pausedAtMs > 0L && hasUsageStatsPermission()) {
            val awaySec = ((System.currentTimeMillis() - pausedAtMs) / 1000L).toInt().coerceAtLeast(1)
            val pausedStart = pausedAtMs
            pausedAtMs = 0L
            detectAndReportSwitchedApp(pausedStart, awaySec)
        }
    }

    private fun detectAndReportSwitchedApp(sinceMs: Long, awaySec: Int) {
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, sinceMs - 5000L, now)
            if (stats.isNullOrEmpty()) return
            val recent = stats
                .filter {
                    it.packageName != packageName &&
                        it.lastTimeUsed >= sinceMs - 1500L &&
                        !it.packageName.contains("launcher", ignoreCase = true) &&
                        it.packageName != "com.android.systemui"
                }
                .maxByOrNull { it.lastTimeUsed } ?: return

            val pkg = recent.packageName
            val label = try {
                val ai = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
                pkg
            }
            val safePkg = pkg.replace("'", "\\'")
            val safeLbl = label.replace("'", "\\'")
            webView.evaluateJavascript(
                "window.onNativeAppSwitched && window.onNativeAppSwitched('$safePkg','$safeLbl',$awaySec);",
                null
            )
        } catch (_: Exception) { }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
        } else if (!kioskActive) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
