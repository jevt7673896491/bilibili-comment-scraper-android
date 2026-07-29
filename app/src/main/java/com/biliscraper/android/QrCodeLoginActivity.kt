package com.biliscraper.android

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.biliscraper.android.api.BiliLoginApiService
import com.biliscraper.android.databinding.ActivityQrcodeLoginBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QrCodeLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrcodeLoginBinding
    private val loginApi = BiliLoginApiService()
    private var currentQrcodeKey: String? = null
    private var pollingJob: Job? = null

    private val webViewLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val cookieStr = result.data?.getStringExtra("COOKIE_RESULT")
            if (!cookieStr.isNullOrBlank()) {
                val intent = Intent()
                intent.putExtra("COOKIE_RESULT", cookieStr)
                setResult(Activity.RESULT_OK, intent)
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrcodeLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        loadNewQrCode()
    }

    private fun setupButtons() {
        binding.btnClose.setOnClickListener {
            finish()
        }

        binding.btnRefreshQr.setOnClickListener {
            loadNewQrCode()
        }

        binding.btnSwitchWebview.setOnClickListener {
            val intent = Intent(this, LoginWebViewActivity::class.java)
            webViewLoginLauncher.launch(intent)
        }
    }

    private fun loadNewQrCode() {
        pollingJob?.cancel()
        binding.tvStatus.text = "正在请求生成官方登录二维码..."
        binding.btnRefreshQr.isEnabled = false

        lifecycleScope.launch {
            try {
                val qrData = withContext(Dispatchers.IO) {
                    loginApi.generateQrCode()
                }
                currentQrcodeKey = qrData.qrcodeKey
                val bitmap = withContext(Dispatchers.Default) {
                    generateQrBitmap(qrData.url)
                }
                binding.imgQrcode.setImageBitmap(bitmap)
                binding.tvStatus.text = "请打开哔哩哔哩App扫一扫 (等待扫描...)"
                binding.btnRefreshQr.isEnabled = true

                startPolling(qrData.qrcodeKey)
            } catch (e: Exception) {
                binding.tvStatus.text = "获取二维码失败: ${e.message}"
                binding.btnRefreshQr.isEnabled = true
                Toast.makeText(this@QrCodeLoginActivity, "网络异常，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startPolling(qrcodeKey: String) {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch {
            while (true) {
                delay(3000L)
                val res = withContext(Dispatchers.IO) {
                    loginApi.pollQrCode(qrcodeKey)
                }

                when (res.status) {
                    86101 -> {
                        binding.tvStatus.text = "等待扫描中... (请使用B站App扫描二维码)"
                    }
                    86090 -> {
                        binding.tvStatus.text = "扫码成功！请在手机上点击确认登录 ✨"
                    }
                    86038 -> {
                        binding.tvStatus.text = "当前二维码已失效，请点击刷新重新生成"
                        break
                    }
                    0 -> {
                        binding.tvStatus.text = "登录确认成功！保存 Cookie... ✅"
                        val cookie = res.cookieString
                        if (!cookie.isNullOrBlank() && cookie.contains("SESSDATA=") && cookie.contains("bili_jct=")) {
                            val intent = Intent()
                            intent.putExtra("COOKIE_RESULT", cookie)
                            setResult(Activity.RESULT_OK, intent)
                            Toast.makeText(this@QrCodeLoginActivity, "B站官方认证登录成功！", Toast.LENGTH_SHORT).show()
                            finish()
                        } else {
                            binding.tvStatus.text = "未能正确提取Cookie，建议使用备用网页端登录"
                        }
                        break
                    }
                    else -> {
                        binding.tvStatus.text = "查询状态异常: ${res.message}"
                    }
                }
            }
        }
    }

    private fun generateQrBitmap(content: String): Bitmap {
        val size = 600
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
    }
}
