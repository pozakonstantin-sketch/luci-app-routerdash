package ru.routerdash.android

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.setPadding
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.FrameLayout
import android.text.InputType
import android.content.Intent
import android.net.Uri

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var addressLabel: TextView
    private val prefs by lazy { getSharedPreferences("routerdash", MODE_PRIVATE) }
    private val defaultAddress = "http://192.168.1.1"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(23, 27, 34)
        window.navigationBarColor = Color.rgb(23, 27, 34)
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 21, 28))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(34, 40, 50))
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        addressLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.LTGRAY)
            text = baseAddress()
        }
        titleBox.addView(addressLabel)
        toolbar.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(Button(this).apply {
            text = "⚙"
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Настройки адреса роутера"
            setOnClickListener { showAddressDialog() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(toolbar)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 138, 61))
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(11, 15, 20))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.allowFileAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url
                    val host = Uri.parse(baseAddress()).host
                    if (target.host == host) return false
                    Toast.makeText(this@MainActivity, "Внешняя ссылка открыта в браузере", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(Intent.ACTION_VIEW, target))
                    return true
                }

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        Toast.makeText(this@MainActivity, "Не удалось открыть дашборд. Проверь адрес роутера и подключение к сети.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        loadDashboard()
    }

    private fun baseAddress(): String =
        prefs.getString("url", defaultAddress)?.trim()?.trimEnd('/')?.ifBlank { defaultAddress } ?: defaultAddress

    private fun loadDashboard() {
        val base = baseAddress()
        addressLabel.text = base
        webView.loadUrl("$base/dashboard.html")
    }

    private fun showAddressDialog() {
        val input = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(baseAddress())
            hint = "http://192.168.1.1"
        }
        AlertDialog.Builder(this)
            .setTitle("Адрес OpenWrt")
            .setMessage("Укажи адрес роутера с установленным RouterDash.")
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val value = input.text.toString().trim().trimEnd('/')
                val uri = runCatching { Uri.parse(value) }.getOrNull()
                if (uri == null || uri.host.isNullOrBlank() || uri.scheme !in listOf("http", "https")) {
                    Toast.makeText(this, "Укажи корректный адрес, например http://192.168.1.1", Toast.LENGTH_LONG).show()
                } else {
                    prefs.edit().putString("url", value).apply()
                    webView.clearHistory()
                    loadDashboard()
                }
            }.show()
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
