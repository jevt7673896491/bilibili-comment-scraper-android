package com.biliscraper.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.biliscraper.android.databinding.ActivityMainBinding
import com.biliscraper.android.utils.FileUtil
import com.biliscraper.android.viewmodel.ScraperViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: ScraperViewModel by viewModels()

    private val selectFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val displayPath = uri.path ?: "自定义目录"
            binding.folderText.text = "保存位置: $displayPath"
            viewModel.setSafUriFolder(uri, displayPath)
        }
    }

    private val loginCookieLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val cookieStr = result.data?.getStringExtra("COOKIE_RESULT")
            if (!cookieStr.isNullOrBlank()) {
                binding.cookieEntry.setText(cookieStr)
                val prefs = getSharedPreferences("config_ini", Context.MODE_PRIVATE)
                prefs.edit().putString("saved_cookie", cookieStr).apply()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSavedConfig()
        setupListeners()
        observeViewModel()
    }

    private fun loadSavedConfig() {
        val prefs = getSharedPreferences("config_ini", Context.MODE_PRIVATE)
        val savedBvid = prefs.getString("saved_bvid", "BV1BK411L7DJ")
        val savedCookie = prefs.getString("saved_cookie", "")
        binding.bvidEntry.setText(savedBvid)
        binding.cookieEntry.setText(savedCookie)
    }

    private fun setupListeners() {
        binding.btnSelectFolder.setOnClickListener {
            selectFolderLauncher.launch(null)
        }

        binding.btnLoginCookie.setOnClickListener {
            val intent = Intent(this, LoginWebViewActivity::class.java)
            loginCookieLauncher.launch(intent)
        }

        binding.btnStart.setOnClickListener {
            val bvid = binding.bvidEntry.text.toString().trim()
            val cookie = binding.cookieEntry.text.toString().trim()
            val subComments = binding.checkSubComments.isChecked
            val dualOrder = binding.checkDualOrder.isChecked

            val prefs = getSharedPreferences("config_ini", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("saved_bvid", bvid)
                .putString("saved_cookie", cookie)
                .apply()

            viewModel.startScraping(bvid, cookie, subComments, dualOrder)
        }

        binding.btnStop.setOnClickListener {
            viewModel.stopScraping()
        }

        binding.btnOpenFolder.setOnClickListener {
            FileUtil.openFolder(this, viewModel.saveFolder.value)
        }

        binding.btnShare.setOnClickListener {
            FileUtil.shareFiles(this, viewModel.getExportedFiles())
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isBusy.collect { busy ->
                        binding.btnStart.isEnabled = !busy
                        binding.btnStop.isEnabled = busy
                        binding.bvidEntry.isEnabled = !busy
                        binding.cookieEntry.isEnabled = !busy
                        binding.checkSubComments.isEnabled = !busy
                        binding.checkDualOrder.isEnabled = !busy
                        binding.btnSelectFolder.isEnabled = !busy
                        if (busy) {
                            binding.actionsLayout.visibility = View.GONE
                        }
                    }
                }

                launch {
                    viewModel.statusText.collect { status ->
                        binding.statusLabel.text = status
                    }
                }

                launch {
                    viewModel.logs.collect { logs ->
                        binding.logText.text = logs.joinToString("\n")
                        binding.logScrollView.post {
                            binding.logScrollView.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }

                launch {
                    viewModel.csvFile.collect { file ->
                        if (file != null) {
                            binding.actionsLayout.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        val bvid = binding.bvidEntry.text.toString().trim()
        val cookie = binding.cookieEntry.text.toString().trim()
        if (bvid.isNotEmpty()) {
            val prefs = getSharedPreferences("config_ini", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("saved_bvid", bvid)
                .putString("saved_cookie", cookie)
                .apply()
        }
    }
}
