package com.igkratox.projectmock

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadBar: ProgressBar
    private lateinit var permGateLayout: LinearLayout
    private var fileUploadCb: ValueCallback<Array<Uri>>? = null
    private var webLoaded = false
    private var pausedAtMs = 0L
    private var kioskActive = false
    private val currentVersion = "v1.1.0"
    private val baseUrl = "https://igkratox.github.io/mock-options-without-socials/"

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val uris = if (res.resultCode == Activity.RESULT_OK && res.data != null) {
                WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
            } else null
            fileUploadCb?.onReceiveValue(uris)
            fileUploadCb = null
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

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

        @JavascriptInterface
        fun forceReload() {
            runOnUiThread {
                try {
                    webView.clearCache(true)
                    webView.reload()
                } catch (_: Exception) { }
            }
        }

        @JavascriptInterface
        fun notifyInvite(examName: String, senseiName: String, rid: String) {
            runOnUiThread {
                vibrateNotice()
                postInviteNotification(examName, senseiName, rid)
            }
        }
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
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.recheckPermBtn).setOnClickListener {
            checkPermissionAndBoot()
        }

        setupWebView()
        checkPermissionAndBoot()
        checkApkUpdate()
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
        ws.userAgentString = ws.userAgentString + " ProjectMockApp/1.1"

        webView.addJavascriptInterface(ExamBridge(), "ExamAppBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
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

    private fun vibrateNotice() {
        try {
            @Suppress("DEPRECATION")
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(350)
            }
        } catch (_: Exception) { }
    }

    private fun postInviteNotification(examName: String, senseiName: String, rid: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val chId = "exam_invites"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(chId, "Live Exam Invites", NotificationManager.IMPORTANCE_HIGH)
                ch.enableVibration(true)
                nm.createNotificationChannel(ch)
            }
            val openUrl = "${baseUrl}#live=$rid"
            val intent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(openUrl)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                this,
                rid.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this, chId)
                .setSmallIcon(R.drawable.ic_app_logo)
                .setContentTitle("📨 Live Exam Invite: $examName")
                .setContentText("$senseiName tomake invite korechen! Tap kore 5 min er moddhe Join dao.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(rid.hashCode(), notif)
        } catch (_: Exception) { }
    }

    private fun checkApkUpdate() {
        Thread {
            try {
                val conn = URL("https://api.github.com/repos/igkratox/project-mock-apk/releases/latest").openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(body)
                    val latestTag = json.optString("tag_name", "")
                    val htmlUrl = json.optString("html_url", "https://github.com/igkratox/project-mock-apk/releases/latest")
                    if (latestTag.isNotEmpty() && latestTag != currentVersion) {
                        runOnUiThread {
                            AlertDialog.Builder(this)
                                .setTitle("New App Update ($latestTag)")
                                .setMessage("Project Mock Exam App er notun version ($latestTag) ashche! Update korle notun features paba.")
                                .setPositiveButton("Download Update") { _, _ ->
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl)))
                                }
                                .setNegativeButton("Later", null)
                                .show()
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) { }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val u = intent.dataString
        if (hasUsageStatsPermission() && u != null && u.startsWith("https://igkratox.github.io/mock-options-without-socials")) {
            webView.loadUrl(u)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && webLoaded && !kioskActive && hasUsageStatsPermission()) {
            webView.evaluateJavascript("window.onNativeOverlayDetected && window.onNativeOverlayDetected();", null)
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
