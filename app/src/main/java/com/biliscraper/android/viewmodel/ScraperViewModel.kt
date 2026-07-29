package com.biliscraper.android.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.biliscraper.android.api.BiliVideoApiService
import com.biliscraper.android.api.CommentItem
import com.biliscraper.android.api.ScrapedComment
import com.biliscraper.android.utils.CsvWriter
import com.biliscraper.android.utils.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.random.Random

class ScraperViewModel(application: Application) : AndroidViewModel(application) {

    private val apiService = BiliVideoApiService()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _statusText = MutableStateFlow("准备就绪 - 等待执行任务")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(listOf("> 爬取引擎初始化已就绪..."))
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _csvFile = MutableStateFlow<File?>(null)
    val csvFile: StateFlow<File?> = _csvFile.asStateFlow()

    private val _saveFolder = MutableStateFlow<File>(FileUtil.getDefaultSaveFolder(application))
    val saveFolder: StateFlow<File> = _saveFolder.asStateFlow()

    private var customSafUri: Uri? = null
    private var currentJob: Job? = null
    @Volatile private var isStopRequested = false

    fun setSafUriFolder(uri: Uri, displayPath: String) {
        customSafUri = uri
        addLog("> 保存目录切换为: $displayPath")
    }

    fun stopScraping() {
        if (_isBusy.value) {
            isStopRequested = true
            addLog("> WARN: 正在请求终止当前爬取任务，等待收尾导出...")
            _statusText.value = "正在中止..."
        }
    }

    fun startScraping(bvidInput: String, cookieInput: String?, scrapeSubComments: Boolean, dualOrder: Boolean) {
        val bvid = bvidInput.trim()
        if (bvid.isEmpty() || !bvid.startsWith("BV", ignoreCase = true)) {
            addLog("> ERROR: BV号不合法！请检查输入的值。")
            _statusText.value = "BV号错误 ❌"
            return
        }

        isStopRequested = false
        _csvFile.value = null

        currentJob = viewModelScope.launch {
            _isBusy.value = true
            addLog("==============================================")
            addLog("> 启动爬取引擎，目标视频: $bvid")
            _statusText.value = "正在连接服务器..."

            val allScrapedComments = ArrayList<ScrapedComment>()
            val seenRpids = HashSet<Long>()

            try {
                // 1. Get video info
                addLog("> LOG: 正在请求视频信息...")
                val videoInfo = withContext(Dispatchers.IO) {
                    apiService.getVideoInfo(bvid, cookieInput)
                }
                val aid = videoInfo.aid
                val totalReply = videoInfo.stat?.reply ?: 0L
                addLog("> LOG: 视频锁定位: 《${videoInfo.title ?: bvid}》")
                addLog("> LOG: 估计总评论数: $totalReply 条, AID: $aid")

                // 2. Dual order strategy (TIME=2, LIKE/HOT=3)
                val orderModes = if (dualOrder) listOf(2 to "时间", 3 to "热度") else listOf(2 to "时间")

                for ((mode, modeName) in orderModes) {
                    if (isStopRequested) break

                    addLog("> LOG: ====== 开始按【$modeName】排序爬取主评论 ======")
                    _statusText.value = "正在按【$modeName】抓取评论..."

                    var nextOffset = 0L
                    var pageNum = 1
                    var isEnd = false

                    while (!isEnd && !isStopRequested) {
                        val resp = withContext(Dispatchers.IO) {
                            apiService.getMainComments(aid, mode, nextOffset, cookieInput)
                        }

                        if (resp.code != 0) {
                            addLog("> WARN: 分页 $pageNum 请求异常 code=${resp.code} (${resp.message})")
                            break
                        }

                        val replies = resp.data?.replies ?: emptyList()
                        if (replies.isEmpty()) {
                            break
                        }

                        var newCountInPage = 0
                        val currentChunkMainComments = ArrayList<CommentItem>()

                        for (item in replies) {
                            if (seenRpids.add(item.rpid)) {
                                allScrapedComments.add(item.toScrapedComment(parentRpid = 0))
                                newCountInPage++
                                currentChunkMainComments.add(item)
                            }
                        }

                        addLog("> LOG: 【$modeName】第 $pageNum 页抓取成功，本页新增主评论 $newCountInPage 条 (当前累积: ${allScrapedComments.size})")
                        _statusText.value = "已获取 ${allScrapedComments.size} 条评论..."

                        // 3. Process sub-comments ("楼中楼") if enabled
                        if (scrapeSubComments && currentChunkMainComments.isNotEmpty()) {
                            processSubComments(aid, currentChunkMainComments, allScrapedComments, seenRpids, cookieInput)
                        }

                        // Check end condition
                        isEnd = resp.data?.cursor?.isEnd ?: true
                        val newOffset = resp.data?.cursor?.next ?: 0L
                        if (newOffset == 0L || newOffset == nextOffset) {
                            break
                        }
                        nextOffset = newOffset
                        pageNum++

                        // Random human-like sleep to avoid rate limiting
                        delay(Random.nextLong(300L, 700L))
                    }
                }

                if (isStopRequested) {
                    addLog("> WARN: 任务已成功被用户主动终止！")
                    _statusText.value = "任务中止，已部分保存"
                } else {
                    addLog("> LOG: 评论爬取全流程完毕！去重后共获取 ${allScrapedComments.size} 条独立评论。")
                    _statusText.value = "数据表生成中..."
                }

                // 4. Save to CSV
                val folder = _saveFolder.value
                val fileName = "comments_${bvid}_${UUID.randomUUID().toString().substring(0, 8)}.csv"
                val localFile = File(folder, fileName)

                addLog("> LOG: 正在保存 UTF-8 (BOM) CSV 表格...")
                withContext(Dispatchers.IO) {
                    CsvWriter.writeToCsvFile(allScrapedComments, localFile)
                }

                customSafUri?.let { uri ->
                    withContext(Dispatchers.IO) {
                        FileUtil.copyFileToSafFolder(
                            getApplication(),
                            uri,
                            fileName,
                            "text/csv",
                            localFile
                        )
                    }
                }

                addLog("> SUCCESS: CSV表格已成功导出至: ${localFile.absolutePath}")
                _csvFile.value = localFile
                _statusText.value = "任务完成 ✅ (共 ${allScrapedComments.size} 条)"

            } catch (e: Exception) {
                addLog("> ERROR: 执行期间发生异常: ${e.message}")
                e.printStackTrace()
                _statusText.value = "爬取错误 ❌"
            } finally {
                _isBusy.value = false
            }
        }
    }

    private suspend fun processSubComments(
        aid: Long,
        mainComments: List<CommentItem>,
        allScraped: MutableList<ScrapedComment>,
        seenRpids: HashSet<Long>,
        cookieInput: String?
    ) {
        val targets = mainComments.filter { (it.rcount ?: 0) > 0 }
        if (targets.isEmpty()) return

        addLog("> LOG: 发现本块 ${targets.size} 条主评论包含楼中楼回复，开始深度抽取...")

        for (mainCmt in targets) {
            if (isStopRequested) break

            var subPage = 1
            var subEnd = false
            while (!subEnd && !isStopRequested) {
                try {
                    val subResp = withContext(Dispatchers.IO) {
                        apiService.getSubComments(aid, mainCmt.rpid, subPage, cookieInput)
                    }
                    if (subResp.code != 0) {
                        break
                    }
                    val subReplies = subResp.data?.replies ?: emptyList()
                    if (subReplies.isEmpty()) {
                        break
                    }

                    for (sub in subReplies) {
                        if (seenRpids.add(sub.rpid)) {
                            allScraped.add(sub.toScrapedComment(parentRpid = mainCmt.rpid))
                        }
                    }

                    val pageInfo = subResp.data?.page
                    val currentCount = subPage * (pageInfo?.size ?: 20)
                    if (pageInfo == null || currentCount >= pageInfo.count) {
                        subEnd = true
                    } else {
                        subPage++
                        delay(Random.nextLong(250L, 500L))
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    private fun addLog(msg: String) {
        val list = _logs.value.toMutableList()
        list.add(msg)
        _logs.value = list
    }

    fun getExportedFiles(): List<File> {
        val file = _csvFile.value ?: return emptyList()
        return listOf(file)
    }
}
