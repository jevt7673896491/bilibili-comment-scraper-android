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
            addLog("> WARN: 正在请求终止当前多引擎任务，等待收尾导出...")
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
            addLog("> 启动多引擎集成 B站评论联合爬取，目标视频: $bvid")
            addLog("> 集成方案: [Cursor主页接口] + [WBI签名补漏接口] + [时间/热度双排去重]")
            _statusText.value = "正在连接B站服务器..."

            val allScrapedComments = ArrayList<ScrapedComment>()
            val seenRpids = HashSet<Long>()
            val allMainCommentItems = ArrayList<CommentItem>()

            try {
                // 1. Get video info
                addLog("> LOG: 正在请求视频基础元数据...")
                val videoInfo = withContext(Dispatchers.IO) {
                    apiService.getVideoInfo(bvid, cookieInput)
                }
                val aid = videoInfo.aid
                val totalReply = videoInfo.stat?.reply ?: 0L
                addLog("> LOG: 视频锁定位: 《${videoInfo.title ?: bvid}》")
                addLog("> LOG: 估计总评论数: $totalReply 条, AID: $aid")

                val orderModes = if (dualOrder) listOf(2 to "时间最新", 3 to "热度推荐") else listOf(2 to "时间最新")

                // =========================================================================
                // ENGINE 1: Standard Cursor API (/x/v2/reply/main)
                // =========================================================================
                addLog("> ====== [引擎1/2] 启动标准 Cursor 游标抓取 (/x/v2/reply/main) ======")
                for ((mode, modeName) in orderModes) {
                    if (isStopRequested) break

                    addLog("> LOG: -> [Cursor模式] 按【$modeName】排序爬取主评论...")
                    _statusText.value = "Cursor引擎-【$modeName】抓取中..."

                    var nextOffset = 0L
                    var pageNum = 1
                    var isEnd = false

                    while (!isEnd && !isStopRequested) {
                        val resp = withContext(Dispatchers.IO) {
                            apiService.getMainComments(aid, mode, nextOffset, cookieInput)
                        }

                        if (resp.code != 0) {
                            addLog("> WARN: [Cursor] 第 $pageNum 页请求响应 code=${resp.code} (${resp.message})，自动切换备用引擎...")
                            break
                        }

                        val replies = resp.data?.replies ?: emptyList()
                        if (replies.isEmpty()) break

                        var newCountInPage = 0
                        for (item in replies) {
                            if (seenRpids.add(item.rpid)) {
                                allScrapedComments.add(item.toScrapedComment(parentRpid = 0))
                                allMainCommentItems.add(item)
                                newCountInPage++
                            }
                        }

                        addLog("> LOG: [Cursor-$modeName] 第 $pageNum 页成功，本页新增 $newCountInPage 条 (当前累计: ${allScrapedComments.size})")
                        _statusText.value = "已获取 ${allScrapedComments.size} 条评论..."

                        isEnd = resp.data?.cursor?.isEnd ?: true
                        val newOffset = resp.data?.cursor?.next ?: 0L
                        if (newOffset == 0L || newOffset == nextOffset) break
                        nextOffset = newOffset
                        pageNum++

                        delay(Random.nextLong(280L, 550L))
                    }
                }
                addLog("> LOG: [引擎1: Cursor模式] 执行完成，累计获取独立有效主评论: ${allScrapedComments.size} 条。")

                // =========================================================================
                // ENGINE 2: WBI Signed API (/x/v2/reply/wbi/main) - Anti-ban / Gap Filling
                // =========================================================================
                if (!isStopRequested) {
                    addLog("> ====== [引擎2/2] 启动 WBI 动态加密接口补漏 (/x/v2/reply/wbi/main) ======")
                    var wbiNewCountTotal = 0
                    for ((mode, modeName) in orderModes) {
                        if (isStopRequested) break
                        addLog("> LOG: -> [WBI加密模式] 校验【$modeName】排序漏抓评论...")
                        _statusText.value = "WBI签名补缺中-【$modeName】..."

                        var nextOffset = 0L
                        var pageNum = 1
                        var isEnd = false

                        while (!isEnd && !isStopRequested) {
                            try {
                                val resp = withContext(Dispatchers.IO) {
                                    apiService.getWbiMainComments(aid, mode, nextOffset, cookieInput)
                                }

                                if (resp.code != 0) {
                                    addLog("> WARN: [WBI-$modeName] code=${resp.code} (${resp.message})")
                                    break
                                }

                                val replies = resp.data?.replies ?: emptyList()
                                if (replies.isEmpty()) break

                                var newCountInPage = 0
                                for (item in replies) {
                                    if (seenRpids.add(item.rpid)) {
                                        allScrapedComments.add(item.toScrapedComment(parentRpid = 0))
                                        allMainCommentItems.add(item)
                                        newCountInPage++
                                        wbiNewCountTotal++
                                    }
                                }

                                if (newCountInPage > 0) {
                                    addLog("> LOG: [WBI-$modeName] 补缺第 $pageNum 页发现未抓取评论 +$newCountInPage 条 (总计: ${allScrapedComments.size})")
                                }

                                isEnd = resp.data?.cursor?.isEnd ?: true
                                val newOffset = resp.data?.cursor?.next ?: 0L
                                if (newOffset == 0L || newOffset == nextOffset) break
                                nextOffset = newOffset
                                pageNum++

                                delay(Random.nextLong(300L, 600L))
                            } catch (e: Exception) {
                                break
                            }
                        }
                    }
                    addLog("> LOG: [引擎2: WBI补漏] 完毕！通过加密签名补充获取了 $wbiNewCountTotal 条此前被过滤/遗漏的主评论！")
                }

                // =========================================================================
                // ENGINE 3: Sub-Comment Deep Scraper (/x/v2/reply/reply)
                // =========================================================================
                if (scrapeSubComments && allMainCommentItems.isNotEmpty() && !isStopRequested) {
                    addLog("> ====== [深度引擎] 联合抽取两套主引擎发现的所有二级楼中楼回复 ======")
                    processSubComments(aid, allMainCommentItems, allScrapedComments, seenRpids, cookieInput)
                }

                if (isStopRequested) {
                    addLog("> WARN: 任务已成功被用户主动终止！")
                    _statusText.value = "任务中止，已部分保存"
                } else {
                    addLog("> LOG: 多引擎联合抓取全部完成！统一去重后最终共获得 ${allScrapedComments.size} 条独立评论。")
                    _statusText.value = "数据表生成中..."
                }

                // Save to CSV
                val folder = _saveFolder.value
                val fileName = "comments_${bvid}_${UUID.randomUUID().toString().substring(0, 8)}.csv"
                val localFile = File(folder, fileName)

                addLog("> LOG: 正在生成 UTF-8 (BOM) CSV 电子表格...")
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

        addLog("> LOG: 锁定本轮共 ${targets.size} 条主评论包含楼中楼回复，开始并发抽取...")

        for (mainCmt in targets) {
            if (isStopRequested) break

            var subPage = 1
            var subEnd = false
            while (!subEnd && !isStopRequested) {
                try {
                    val subResp = withContext(Dispatchers.IO) {
                        apiService.getSubComments(aid, mainCmt.rpid, subPage, cookieInput)
                    }
                    if (subResp.code != 0) break
                    val subReplies = subResp.data?.replies ?: emptyList()
                    if (subReplies.isEmpty()) break

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
