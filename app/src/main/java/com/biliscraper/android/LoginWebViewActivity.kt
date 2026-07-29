package com.biliscraper.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.biliscraper.android.databinding.ActivityLoginWebviewBinding

class LoginWebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginWebviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()

        binding.btnFinishLogin.setOnClickListener {
            val cookieManager = CookieManager.getInstance()
            val cookieStr1 = cookieManager.getCookie("https://bilibili.com") ?: ""
            val cookieStr2 = cookieManager.getCookie("https://www.bilibili.com") ?: ""
            val cookieStr3 = cookieManager.getCookie("https://passport.bilibili.com") ?: ""
            
            val combined = (cookieStr1 + "; " + cookieStr2 + "; " + cookieStr3).trim()
            if (combined.contains("SESSDATA=") && combined.contains("bili_jct=")) {
                val intent = Intent()
                intent.putExtra("COOKIE_RESULT", combined)
                setResult(Activity.RESULT_OK, intent)
                Toast.makeText(this, "成功提取B站 Cookie！", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "未检测到已登录的 SESSDATA/bili_jct，请在网页完成登录后再点击保存", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupWebView() {
        val webView = binding.webview
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.webviewTitle.text = view?.title ?: "登录 Bilibili 账号获取 Cookie"
            }
        }

        webView.loadUrl("https://passport.bilibili.com/login")
    }
}
