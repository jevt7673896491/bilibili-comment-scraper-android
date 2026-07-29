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
import kotlinx.coroutines.CancellationException
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

    // Maintain in-memory reference to scraped comments for emergency exit hook
    private val currentScrapedComments = ArrayList<ScrapedComment>()
    private var currentBvid: String = ""

    fun setSafUriFolder(uri: Uri, displayPath: String) {
        customSafUri = uri
        addLog("> 保存目录切换为: $displayPath")
    }

    fun stopScraping() {
        if (_isBusy.value) {
            isStopRequested = true
            addLog("> WARN: 正在请求终止当前多引擎任务，准备收尾保存已采集数据...")
            _statusText.value = "正在中止并保存..."
        }
    }

    /**
     * Emergency save hook called from Activity.onStop() / onDestroy() when OS kills or user exits.
     */
    fun triggerEmergencySaveOnExit() {
        if (_isBusy.value && currentScrapedComments.isNotEmpty() && currentBvid.isNotEmpty()) {
            addLog("> EMERGENCY: 监测到页面退出或进入后台，触发断点容灾紧急保存 ${currentScrapedComments.size} 条评论...")
            saveCommentsToDiskSync(currentScrapedComments, currentBvid, "emergency_exit")
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
        currentBvid = bvid
        currentScrapedComments.clear()

        currentJob = viewModelScope.launch {
            _isBusy.value = true
            addLog("==============================================")
            addLog("> 启动多引擎集成 B站评论联合爬取，目标视频: $bvid")
            addLog("> 容灾机制: [开启实时增量快照 + 异常中断自动持久化保存]")
            _statusText.value = "正在连接B站服务器..."

            val allScrapedComments = ArrayList<ScrapedComment>()
            val seenRpids = HashSet<Long>()
            val allMainCommentItems = ArrayList<CommentItem>()
            var isSavedAtEnd = false
            var lastCheckpointSize = 0

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
                            addLog("> WARN: [Cursor] 第 $pageNum 页响应 code=${resp.code} (${resp.message})，保存当前断点并切换备用引擎...")
                            break
                        }

                        val replies = resp.data?.replies ?: emptyList()
                        if (replies.isEmpty()) break

                        var newCountInPage = 0
                        for (item in replies) {
                            if (seenRpids.add(item.rpid)) {
                                val sc = item.toScrapedComment(parentRpid = 0)
                                allScrapedComments.add(sc)
                                currentScrapedComments.add(sc)
                                allMainCommentItems.add(item)
                                newCountInPage++
                            }
                        }

                        addLog("> LOG: [Cursor-$modeName] 第 $pageNum 页成功，本页新增 $newCountInPage 条 (当前累计: ${allScrapedComments.size})")
                        _statusText.value = "已获取 ${allScrapedComments.size} 条评论..."

                        // Incremental Auto-Checkpointing every 200 comments
                        if (allScrapedComments.size - lastCheckpointSize >= 200) {
                            lastCheckpointSize = allScrapedComments.size
                            saveCommentsToDiskSync(allScrapedComments, bvid, "checkpoint", silent = true)
                        }

                        isEnd = resp.data?.cursor?.isEnd ?: true
                        val newOffset = resp.data?.cursor?.next ?: 0L
                        if (newOffset == 0L || newOffset == nextOffset) break
                        nextOffset = newOffset
                        pageNum++

                        delay(Random.nextLong(280L, 550L))
                    }
                }
                addLog("> LOG: [引擎1: Cursor模式] 执行完成，累计获取有效主评论: ${allScrapedComments.size} 条。")

                // =========================================================================
                // ENGINE 2: WBI Signed API (/x/v2/reply/wbi/main) - Anti-ban / Gap Filling
                // =========================================================================
                if (!isStopRequested) {
                    addLog("> ====== [引擎2/2] 启动 WBI 动态加密签名接口补漏 (/x/v2/reply/wbi/main) ======")
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
                                        val sc = item.toScrapedComment(parentRpid = 0)
                                        allScrapedComments.add(sc)
                                        currentScrapedComments.add(sc)
                                        allMainCommentItems.add(item)
                                        newCountInPage++
                                        wbiNewCountTotal++
                                    }
                                }

                                if (newCountInPage > 0) {
                                    addLog("> LOG: [WBI-$modeName] 补缺第 $pageNum 页发现未抓取评论 +$newCountInPage 条 (总计: ${allScrapedComments.size})")
                                }

                                // Incremental Auto-Checkpointing every 200 comments
                                if (allScrapedComments.size - lastCheckpointSize >= 200) {
                                    lastCheckpointSize = allScrapedComments.size
                                    saveCommentsToDiskSync(allScrapedComments, bvid, "checkpoint", silent = true)
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
                    addLog("> LOG: [引擎2: WBI补漏] 完毕！通过加密签名补充获取了 $wbiNewCountTotal 条被隐藏的主评论！")
                }

                // =========================================================================
                // ENGINE 3: Sub-Comment Deep Scraper (/x/v2/reply/reply)
                // =========================================================================
                if (scrapeSubComments && allMainCommentItems.isNotEmpty() && !isStopRequested) {
                    addLog("> ====== [深度引擎] 联合抽取主引擎发现的所有二级楼中楼回复 ======")
                    processSubComments(aid, allMainCommentItems, allScrapedComments, seenRpids, cookieInput)
                }

                // Final save based on completion state
                val tag = if (isStopRequested) "stopped" else "completed"
                val savedFile = saveCommentsToDiskSync(allScrapedComments, bvid, tag)
                isSavedAtEnd = true

                if (isStopRequested) {
                    addLog("> WARN: 任务由用户主动中止！已完整保存截至目前的 ${allScrapedComments.size} 条有效评论 -> ${savedFile?.name}")
                    _statusText.value = "任务已中止并完整保存 ✅"
                } else {
                    addLog("> SUCCESS: 多引擎联合抓取全部成功完成！去重后共获得 ${allScrapedComments.size} 条独立有效评论 -> ${savedFile?.name}")
                    _statusText.value = "任务完成 ✅ (共 ${allScrapedComments.size} 条)"
                }

            } catch (e: CancellationException) {
                // Handle coroutine cancellation unexpectedly
                if (!isSavedAtEnd && allScrapedComments.isNotEmpty()) {
                    val savedFile = saveCommentsToDiskSync(allScrapedComments, bvid, "interrupted")
                    addLog("> WARN: 任务被取消或页面切出！已触发断点保护，保存 ${allScrapedComments.size} 条有效数据 -> ${savedFile?.name}")
                    _statusText.value = "已自动容灾保存 ${allScrapedComments.size} 条"
                }
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                // Handle any network error or unexpected runtime exception
                if (!isSavedAtEnd && allScrapedComments.isNotEmpty()) {
                    val savedFile = saveCommentsToDiskSync(allScrapedComments, bvid, "interrupted_error")
                    addLog("> ERROR: 意外网络异常 (${e.message})！自动容灾机制生效，成功保存此前采集的 ${allScrapedComments.size} 条有效评论 -> ${savedFile?.name}")
                    _statusText.value = "意外中断，已自动保存数据 ✅"
                    isSavedAtEnd = true
                } else {
                    addLog("> ERROR: 执行期间发生错误: ${e.message}")
                    _statusText.value = "爬取错误 ❌"
                }
            } finally {
                // Defensive safeguard: if still not saved and comments exist, save now
                if (!isSavedAtEnd && allScrapedComments.isNotEmpty()) {
                    val savedFile = saveCommentsToDiskSync(allScrapedComments, bvid, "emergency_finally")
                    addLog("> EMERGENCY: 流程退出容灾检查：自动兜底保存 ${allScrapedComments.size} 条数据 -> ${savedFile?.name}")
                }
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
                            val sc = sub.toScrapedComment(parentRpid = mainCmt.rpid)
                            allScraped.add(sc)
                            currentScrapedComments.add(sc)
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
                    // Even if subcomment fetching fails, break and keep already scraped comments
                    break
                }
            }
        }
    }

    /**
     * Synchronous / thread-safe helper to write CSV file and copy to SAF folder.
     * Prevents data loss during any interruption, error, stop, or periodic checkpoint.
     */
    private fun saveCommentsToDiskSync(
        comments: List<ScrapedComment>,
        bvid: String,
        tag: String,
        silent: Boolean = false
    ): File? {
        if (comments.isEmpty()) return null
        try {
            val folder = _saveFolder.value
            val fileName = "comments_${bvid}_${tag}_${UUID.randomUUID().toString().substring(0, 6)}.csv"
            val localFile = File(folder, fileName)

            CsvWriter.writeToCsvFile(comments, localFile)

            customSafUri?.let { uri ->
                FileUtil.copyFileToSafFolder(
                    getApplication(),
                    uri,
                    fileName,
                    "text/csv",
                    localFile
                )
            }

            if (!silent) {
                _csvFile.value = localFile
            }
            return localFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
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
