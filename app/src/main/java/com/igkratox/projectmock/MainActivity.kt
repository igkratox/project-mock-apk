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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadBar: ProgressBar
    private lateinit var permGateLayout: LinearLayout
    private lateinit var splashLayout: LinearLayout
    private lateinit var offlineLayout: LinearLayout
    private var fileUploadCb: ValueCallback<Array<Uri>>? = null
    private var webLoaded = false
    private var initialSplashDone = false
    private var hasMainFrameError = false
    private var pausedAtMs = 0L
    private var kioskActive = false
    private var audioFocusReq: AudioFocusRequest? = null
    private var audioVolumeCheckedForSession = false
    private val currentVersion = "v1.3.0"
    private val baseUrl = InviteCheckWorker.BASE_URL

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
                if (enabled) {
                    requestExamAudioFocus()
                    if (!audioVolumeCheckedForSession) {
                        audioVolumeCheckedForSession = true
                        checkMediaVolumeAndWarn()
                    }
                    if (!kioskActive) {
                        try {
                            startLockTask()
                            kioskActive = true
                        } catch (_: Exception) { }
                    }
                } else {
                    abandonExamAudioFocus()
                    audioVolumeCheckedForSession = false
                    if (kioskActive) {
                        try {
                            stopLockTask()
                            kioskActive = false
                        } catch (_: Exception) { }
                    }
                }
            }
        }

        @JavascriptInterface
        fun isExamApp(): Boolean = true

        @JavascriptInterface
        fun checkAudioReady(): Boolean {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (vol <= 0) {
                runOnUiThread { checkMediaVolumeAndWarn() }
                return false
            }
            return true
        }

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
        splashLayout = findViewById(R.id.splashLayout)
        offlineLayout = findViewById(R.id.offlineLayout)

        findViewById<MaterialButton>(R.id.grantPermBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.recheckPermBtn).setOnClickListener {
            checkPermissionAndBoot()
        }
        findViewById<MaterialButton>(R.id.retryNetBtn).setOnClickListener {
            hasMainFrameError = false
            offlineLayout.visibility = View.GONE
            splashLayout.visibility = View.VISIBLE
            webView.reload()
        }

        setupWebView()
        setupBackNavigation()
        scheduleInviteWorker()
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
        ws.setSupportZoom(true)
        ws.builtInZoomControls = true
        ws.displayZoomControls = false
        ws.userAgentString = ws.userAgentString + " ProjectMockApp/1.3"

        webView.addJavascriptInterface(ExamBridge(), "ExamAppBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!hasMainFrameError) {
                    offlineLayout.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                }
                if (!initialSplashDone) {
                    initialSplashDone = true
                    splashLayout.animate()
                        .alpha(0f)
                        .setDuration(250L)
                        .withEndAction {
                            splashLayout.visibility = View.GONE
                            splashLayout.alpha = 1f
                        }
                        .start()
                }
                syncStudentUsernameForWorker()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    hasMainFrameError = true
                    splashLayout.visibility = View.GONE
                    webView.visibility = View.GONE
                    offlineLayout.visibility = View.VISIBLE
                }
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

    private fun syncStudentUsernameForWorker() {
        webView.evaluateJavascript(
            "(function(){try{return (window.curStudent&&window.curStudent.username)||'';}catch(e){return '';}})();"
        ) { raw ->
            val username = raw?.trim('"')?.takeIf { it != "null" } ?: ""
            getSharedPreferences(InviteCheckWorker.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(InviteCheckWorker.KEY_STUDENT_USERNAME, username)
                .apply()
        }
    }

    private fun scheduleInviteWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = PeriodicWorkRequestBuilder<InviteCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "shiken_invite_poll",
            ExistingPeriodicWorkPolicy.KEEP,
            req
        )
    }

    private fun requestExamAudioFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                audioFocusReq = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        } catch (_: Exception) { }
    }

    private fun abandonExamAudioFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusReq?.let { am.abandonAudioFocusRequest(it) }
                audioFocusReq = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) { }
    }

    private fun checkMediaVolumeAndWarn() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (cur <= 0 && max > 0) {
                AlertDialog.Builder(this)
                    .setTitle("Audio Volume Muted! 🔇")
                    .setMessage("Tomar phone-er Media Volume 0 kora ache. Listening audio shunte hole volume barano lagbe.")
                    .setPositiveButton("Raise Volume") { _, _ ->
                        val target = (max * 0.6f).toInt().coerceAtLeast(1)
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                    }
                    .setNegativeButton("Ignore", null)
                    .show()
            }
        } catch (_: Exception) { }
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
            if (!hasMainFrameError) {
                webView.visibility = View.VISIBLE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!webLoaded) {
                webLoaded = true
                splashLayout.visibility = View.VISIBLE
                val target = intent?.dataString?.takeIf { it.startsWith("https://igkratox.github.io/mock-options-without-socials") }
                    ?: baseUrl
                webView.loadUrl(target)
            }
        } else {
            permGateLayout.visibility = View.VISIBLE
            splashLayout.visibility = View.GONE
            offlineLayout.visibility = View.GONE
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
                    var apkUrl = ""
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.optJSONObject(i) ?: continue
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                apkUrl = asset.optString("browser_download_url", "")
                                break
                            }
                        }
                    }
                    if (latestTag.isNotEmpty() && latestTag != currentVersion) {
                        runOnUiThread {
                            AlertDialog.Builder(this)
                                .setTitle("New App Update ($latestTag)")
                                .setMessage("ShiKen App er notun version ($latestTag) ashche! Update korle notun features paba.")
                                .setPositiveButton("Update Now") { _, _ ->
                                    if (apkUrl.isNotEmpty()) {
                                        downloadAndInstallApk(apkUrl, htmlUrl)
                                    } else {
                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl)))
                                    }
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

    private fun downloadAndInstallApk(apkUrl: String, fallbackUrl: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Install Permission Needed")
                .setMessage("Direct app update install korte 'Allow from this source' on koro, tarpor abar Update dao.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton("Browser e Open Koro") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
                }
                .show()
            return
        }

        val progressDlg = AlertDialog.Builder(this)
            .setTitle("Downloading Update...")
            .setMessage("ShiKen notun APK download hochhe, ektu wait koro...")
            .setCancelable(false)
            .create()
        progressDlg.show()

        Thread {
            try {
                val dlDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
                val outFile = File(dlDir, "ShiKen-Update.apk")
                if (outFile.exists()) outFile.delete()

                val conn = URL(apkUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                conn.disconnect()

                runOnUiThread {
                    progressDlg.dismiss()
                    val apkUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", outFile)
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(installIntent)
                }
            } catch (_: Exception) {
                runOnUiThread {
                    progressDlg.dismiss()
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
                }
            }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val u = intent.dataString
        if (hasUsageStatsPermission() && u != null && u.startsWith("https://igkratox.github.io/mock-options-without-socials")) {
            hasMainFrameError = false
            offlineLayout.visibility = View.GONE
            webView.visibility = View.VISIBLE
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
        if (webLoaded) syncStudentUsernameForWorker()
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

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })
    }

    private fun handleBackPress() {
        if (webView.visibility != View.VISIBLE) {
            showAppExitDialog()
            return
        }

        val stateJs = """
            (function() {
                var examEl = document.getElementById('exam');
                if (examEl && !examEl.classList.contains('hide')) return 'exam';
                var setupEl = document.getElementById('setup');
                var gateEl = document.getElementById('gateView');
                var onSetup = setupEl && !setupEl.classList.contains('hide');
                var onGate = gateEl && !gateEl.classList.contains('hide');
                if (onSetup || onGate) return 'home';
                return 'subpage';
            })();
        """.trimIndent()

        webView.evaluateJavascript(stateJs) { raw ->
            val state = raw?.trim('"') ?: "home"
            when {
                state == "exam" || kioskActive -> showExamExitWarning()
                webView.canGoBack() -> webView.goBack()
                state == "subpage" -> {
                    webView.evaluateJavascript("if(typeof goHome==='function')goHome();", null)
                }
                else -> showAppExitDialog()
            }
        }
    }

    private fun showExamExitWarning() {
        AlertDialog.Builder(this)
            .setTitle("Exam Cholche! ⚠️")
            .setMessage("Tumi ekhon exam page e acho. Home page e fire jete chao? Exam cholakalin ber hole progress lose hote pare.")
            .setPositiveButton("Home e Jao") { _, _ ->
                abandonExamAudioFocus()
                audioVolumeCheckedForSession = false
                if (kioskActive) {
                    try {
                        stopLockTask()
                    } catch (_: Exception) { }
                    kioskActive = false
                }
                webView.evaluateJavascript("if(typeof goHome==='function')goHome();", null)
            }
            .setNegativeButton("Na, Exam e Thako", null)
            .show()
    }

    private fun showAppExitDialog() {
        AlertDialog.Builder(this)
            .setTitle("Exit ShiKen?")
            .setMessage("Tumi ki ShiKen app theke exit dite chao?")
            .setPositiveButton("Exit") { _, _ ->
                finish()
            }
            .setNegativeButton("Na", null)
            .show()
    }
}
