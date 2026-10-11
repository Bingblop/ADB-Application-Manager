package com.bloatware.bingblop.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bloatware.bingblop.data.model.ShellMode
import com.bloatware.bingblop.data.repository.AppRepository
import com.bloatware.bingblop.data.repository.CleanerRepository
import com.bloatware.bingblop.data.repository.LogcatRepository
import com.bloatware.bingblop.data.repository.SettingsRepository
import com.bloatware.bingblop.data.repository.ShellRepository
import com.bloatware.bingblop.ui.theme.AccentCyan
import com.bloatware.bingblop.ui.theme.BgBase
import com.bloatware.bingblop.ui.theme.BgCard
import com.bloatware.bingblop.ui.theme.BgSurface
import com.bloatware.bingblop.ui.theme.BorderGlass
import com.bloatware.bingblop.ui.theme.CleanGreen
import com.bloatware.bingblop.ui.theme.TextDim
import com.bloatware.bingblop.ui.theme.TextMain
import com.bloatware.bingblop.ui.theme.TextMuted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ClassicWebViewScreen(
    appRepository: AppRepository,
    settingsRepository: SettingsRepository,
    shellRepository: ShellRepository,
    cleanerRepository: CleanerRepository,
    logcatRepository: LogcatRepository,
    onSwitchToModern: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var webViewRef: WebView? = null

    BackHandler {
        if (webViewRef?.canGoBack() == true) {
            webViewRef?.goBack()
        } else {
            onSwitchToModern()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgBase)
    ) {
        // Coexistence Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface)
                .border(width = 1.dp, color = BorderGlass)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onSwitchToModern,
                    modifier = Modifier.size(32.dp).testTag("back_to_compose_btn")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Return to Modern UI",
                        tint = AccentCyan
                    )
                }

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Classic Engine (WebView)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            color = TextMain
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(CleanGreen.copy(alpha = 0.2f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text("14 LANGUAGES", fontSize = 8.5.sp, color = CleanGreen, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        text = "Complete original web suite active",
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(
                    onClick = { webViewRef?.reload() },
                    modifier = Modifier.size(32.dp).testTag("reload_webview_btn")
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Reload Page",
                        tint = TextDim
                    )
                }

                Button(
                    onClick = onSwitchToModern,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BgBase),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("switch_to_modern_btn")
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Modern Compose", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Embedded Android WebView
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this
                        setBackgroundColor(0xFF080A0F.toInt())

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = true
                            allowContentAccess = true
                            databaseEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: ""
                                if (url.startsWith("file:///android_asset/")) {
                                    return false
                                }
                                return try {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, request?.url))
                                    true
                                } catch (_: Exception) {
                                    false
                                }
                            }
                        }

                        webChromeClient = WebChromeClient()

                        addJavascriptInterface(
                            ModernAndroidBridge(
                                context = ctx,
                                scope = coroutineScope,
                                appRepository = appRepository,
                                settingsRepository = settingsRepository,
                                shellRepository = shellRepository,
                                cleanerRepository = cleanerRepository,
                                logcatRepository = logcatRepository,
                                webView = this
                            ),
                            "AndroidBridge"
                        )

                        loadUrl("file:///android_asset/index.html")
                    }
                },
                modifier = Modifier.fillMaxSize().testTag("classic_webview")
            )
        }
    }
}

/**
 * JavaScript Bridge connecting the WebView application (index.html)
 * to the robust Kotlin architecture.
 */
class ModernAndroidBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val appRepository: AppRepository,
    private val settingsRepository: SettingsRepository,
    private val shellRepository: ShellRepository,
    private val cleanerRepository: CleanerRepository,
    private val logcatRepository: LogcatRepository,
    private val webView: WebView
) {

    @JavascriptInterface
    fun vibrate() {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(30)
        }
    }

    @JavascriptInterface
    fun showToast(msg: String?) {
        if (!msg.isNullOrBlank()) {
            scope.launch(Dispatchers.Main) {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun openDeveloperOptions() {
        scope.launch(Dispatchers.Main) {
            try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {
                try {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (_: Exception) {}
            }
        }
    }

    @JavascriptInterface
    fun openShizukuApp() {
        scope.launch(Dispatchers.Main) {
            val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                Toast.makeText(context, "Shizuku app is not installed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun checkShizukuStatus(): String {
        val json = JSONObject()
        json.put("installed", context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") != null)
        json.put("running", false)
        json.put("version", 13)
        return json.toString()
    }

    @JavascriptInterface
    fun getWorkingMode(): String {
        return "local"
    }

    @JavascriptInterface
    fun executeShell(cmd: String?): String {
        if (cmd.isNullOrBlank()) return ""
        return kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            shellRepository.executeCommand(cmd, ShellMode.LOCAL_SHELL).output
        }
    }

    @JavascriptInterface
    fun executeShellAsync(runId: String?, cmd: String?): String {
        if (cmd.isNullOrBlank() || runId.isNullOrBlank()) return ""
        scope.launch(Dispatchers.IO) {
            val result = shellRepository.executeCommand(cmd, ShellMode.LOCAL_SHELL)
            val escapedOut = JSONObject.quote(result.output)
            launch(Dispatchers.Main) {
                webView.evaluateJavascript("if (window.onShellOutput) { window.onShellOutput('$runId', $escapedOut, ${result.exitCode}); }", null)
            }
        }
        return "started"
    }

    @JavascriptInterface
    fun settingsList(req: Int, ns: String?): String {
        val json = JSONObject()
        json.put("req", req)
        json.put("ns", ns ?: "global")
        json.put("items", org.json.JSONArray())
        return json.toString()
    }

    @JavascriptInterface
    fun getDeviceIp(): String {
        return "127.0.0.1"
    }

    @JavascriptInterface
    fun rishStart(): String {
        return "{\"status\":\"ready\"}"
    }

    @JavascriptInterface
    fun rishStop() {}

    @JavascriptInterface
    fun rishClose() {}
}
