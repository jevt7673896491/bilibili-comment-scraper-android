package com.biliscraper.android.api

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class BiliVideoApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    @Throws(IOException::class)
    fun getVideoInfo(bvid: String, cookie: String? = null): VideoInfo {
        val url = "https://api.bilibili.com/x/web-interface/view?bvid=$bvid"
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://www.bilibili.com/video/$bvid")
        if (!cookie.isNullOrBlank()) {
            builder.header("Cookie", cookie)
        }

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("获取视频信息失败，HTTP 状态码: ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("视频数据包为空")
            val resp = gson.fromJson(body, BiliViewResponse::class.java)
            if (resp.code != 0 || resp.data == null) {
                throw IOException("API 错误 [code=${resp.code}]: ${resp.message ?: "未知错误"}")
            }
            return resp.data
        }
    }

    /**
     * Engine 1: Standard Cursor API (/x/v2/reply/main)
     */
    @Throws(IOException::class)
    fun getMainComments(aid: Long, mode: Int, nextOffset: Long, cookie: String? = null): BiliReplyMainResponse {
        val url = "https://api.bilibili.com/x/v2/reply/main?oid=$aid&type=1&mode=$mode&next=$nextOffset&ps=20"
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://www.bilibili.com/")
        if (!cookie.isNullOrBlank()) {
            builder.header("Cookie", cookie)
        }

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("评论查询请求 HTTP 异常: ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("返回内容为空")
            return gson.fromJson(body, BiliReplyMainResponse::class.java)
        }
    }

    /**
     * Engine 2: WBI Signed Modern API (/x/v2/reply/wbi/main)
     * Automatically signs parameters with WBI w_rid and wts to bypass -403 / -352 rate limits.
     */
    @Throws(IOException::class)
    fun getWbiMainComments(aid: Long, mode: Int, nextOffset: Long, cookie: String? = null): BiliReplyMainResponse {
        val baseParams = mapOf(
            "oid" to aid,
            "type" to 1,
            "mode" to mode,
            "next" to nextOffset,
            "ps" to 20
        )
        val signed = WbiSigner.signParams(baseParams, cookie)
        val queryStr = signed.entries.joinToString("&") { "${it.key}=${it.value}" }
        val url = "https://api.bilibili.com/x/v2/reply/wbi/main?$queryStr"

        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://www.bilibili.com/")
        if (!cookie.isNullOrBlank()) {
            builder.header("Cookie", cookie)
        }

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("WBI接口 HTTP 异常: ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("WBI数据包为空")
            return gson.fromJson(body, BiliReplyMainResponse::class.java)
        }
    }

    /**
     * Sub-comment Engine: (/x/v2/reply/reply)
     */
    @Throws(IOException::class)
    fun getSubComments(aid: Long, rootRpid: Long, page: Int, cookie: String? = null): BiliSubReplyResponse {
        val url = "https://api.bilibili.com/x/v2/reply/reply?oid=$aid&type=1&root=$rootRpid&pn=$page&ps=20"
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://www.bilibili.com/")
        if (!cookie.isNullOrBlank()) {
            builder.header("Cookie", cookie)
        }

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("楼中楼查询异常 HTTP 状态码: ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("空数据包")
            return gson.fromJson(body, BiliSubReplyResponse::class.java)
        }
    }
}
