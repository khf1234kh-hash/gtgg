package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

  companion object {
    const val CHANNEL_ID = "prayer_times_channel"
    const val CHANNEL_NAME = "مواقيت الصلاة والتنبيهات"
  }

  private val requestPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { _: Boolean ->
    // Handled
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    createNotificationChannel()
    checkNotificationPermission()

    // Dark background for system bars
    window.statusBarColor = Color.parseColor("#0b130e")
    window.navigationBarColor = Color.parseColor("#0b130e")

    setContent {
      MyApplicationTheme {
        IslamicAppScreen(
          onShowNotification = { title, message ->
            sendNativeNotification(title, message)
          },
          onRequestPermission = {
            checkNotificationPermission()
          }
        )
      }
    }
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        CHANNEL_NAME,
        NotificationManager.IMPORTANCE_HIGH
      ).apply {
        description = "تنبيهات الأذان وحلول مواقيت الصلاة"
        enableLights(true)
        lightColor = Color.parseColor("#DFB168")
        enableVibration(true)
      }
      val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      notificationManager.createNotificationChannel(channel)
    }
  }

  private fun checkNotificationPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (ContextCompat.checkSelfPermission(
          this,
          Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
      ) {
        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }
  }

  fun sendNativeNotification(title: String, message: String) {
    val intent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    val pendingIntent = PendingIntent.getActivity(
      this,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(title)
      .setContentText(message)
      .setStyle(NotificationCompat.BigTextStyle().bigText(message))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setContentIntent(pendingIntent)
      .setAutoCancel(true)
      .setDefaults(NotificationCompat.DEFAULT_ALL)
      .build()

    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notificationId = (System.currentTimeMillis() % 100000).toInt()
    notificationManager.notify(notificationId, notification)
  }
}

class PrayerNotificationBridge(
  private val onShowNotification: (String, String) -> Unit,
  private val onRequestPermission: () -> Unit
) {
  @JavascriptInterface
  fun sendNotification(title: String, message: String) {
    onShowNotification(title, message)
  }

  @JavascriptInterface
  fun requestPermission() {
    onRequestPermission()
  }
}

class AndroidOtaBridge(
  private val context: Context,
  private val getWebView: () -> WebView?
) {
  @JavascriptInterface
  fun getInstalledVersion(): String {
    return OtaUpdateManager.getCurrentVersion(context)
  }

  @JavascriptInterface
  fun checkAndUpdate(serverUrl: String) {
    OtaUpdateManager.checkForUpdates(context, serverUrl) { updated, newVersion, _ ->
      if (updated) {
        val webView = getWebView()
        webView?.post {
          val newEntryUrl = OtaUpdateManager.getAppEntryUrl(context)
          webView.loadUrl(newEntryUrl)
        }
      }
    }
  }

  @JavascriptInterface
  fun reload() {
    val webView = getWebView()
    webView?.post {
      val newEntryUrl = OtaUpdateManager.getAppEntryUrl(context)
      webView.loadUrl(newEntryUrl)
    }
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun IslamicAppScreen(
  onShowNotification: (String, String) -> Unit,
  onRequestPermission: () -> Unit
) {
  var webViewRef by remember { mutableStateOf<WebView?>(null) }

  BackHandler(enabled = true) {
    if (webViewRef?.canGoBack() == true) {
      webViewRef?.goBack()
    } else {
      webViewRef?.evaluateJavascript(
        """
        if (typeof closeQuranReader === 'function' && (document.getElementById('quranReaderModal')?.classList.contains('active') || document.getElementById('quranReaderModal')?.style.display === 'flex')) {
          closeQuranReader();
        } else if (typeof switchNavTab === 'function' && document.getElementById('screen-home')?.classList.contains('active') === false) {
          switchNavTab('home', document.querySelector('.nav-dock-item'));
        } else {
          'exit';
        }
        """.trimIndent()
      ) { result ->
        if (result == "\"exit\"" || result == null) {
          // Normal exit
        }
      }
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(androidx.compose.ui.graphics.Color(0xFF0B130E))
      .systemBarsPadding()
  ) {
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { context ->
        WebView(context).apply {
          layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
          setBackgroundColor(Color.parseColor("#0b130e"))
          setLayerType(View.LAYER_TYPE_HARDWARE, null)
          isLongClickable = false
          setOnLongClickListener { true }

          settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            displayZoomControls = false
            builtInZoomControls = false
            @Suppress("DEPRECATION")
            setRenderPriority(WebSettings.RenderPriority.HIGH)
          }

          addJavascriptInterface(
            PrayerNotificationBridge(onShowNotification, onRequestPermission),
            "AndroidNotification"
          )

          addJavascriptInterface(
            AndroidOtaBridge(context) { webViewRef },
            "AndroidOTA"
          )

          webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
              view?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
              }
              return true
            }
          }
          webChromeClient = object : WebChromeClient() {}

          val initialUrl = OtaUpdateManager.getAppEntryUrl(context)
          loadUrl(initialUrl)
          webViewRef = this
        }
      },
      update = {
        webViewRef = it
      }
    )
  }
}
