package com.biliscraper.android.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class QrGenerateResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String?,
    @SerializedName("data") val data: QrCodeData?
)

data class QrCodeData(
    @SerializedName("url") val url: String,
    @SerializedName("qrcode_key") val qrcodeKey: String
)

data class QrPollResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String?,
    @SerializedName("data") val data: QrPollData?
)

data class QrPollData(
    @SerializedName("url") val url: String?,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("timestamp") val timestamp: Long?,
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String?
)

data class PollResult(
    val status: Int, // 0: 登录成功, 86090: 已扫码待确认, 86101: 未扫码, 86038: 已失效
    val message: String,
    val cookieString: String? = null
)

class BiliLoginApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    @Throws(IOException::class)
    fun generateQrCode(): QrCodeData {
        val url = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", "https://www.bilibili.com/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("申请二维码失败: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("返回数据为空")
            val resp = gson.fromJson(body, QrGenerateResponse::class.java)
            if (resp.code != 0 || resp.data == null) {
                throw IOException("生成二维码返回码异常 [${resp.code}]: ${resp.message}")
            }
            return resp.data
        }
    }

    @Throws(IOException::class)
    fun pollQrCode(qrcodeKey: String): PollResult {
        val url = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=$qrcodeKey"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://www.bilibili.com/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return PollResult(-1, "查询 HTTP 状态错误 ${response.code}")
            }

            // Extract Set-Cookie headers
            val cookieHeaders = response.headers("Set-Cookie")
            val cookiePairs = ArrayList<String>()
            for (header in cookieHeaders) {
                val firstPart = header.split(";").firstOrNull()?.trim()
                if (!firstPart.isNullOrEmpty() && firstPart.contains("=")) {
                    cookiePairs.add(firstPart)
                }
            }
            val combinedCookie = cookiePairs.joinToString("; ")

            val body = response.body?.string() ?: return PollResult(-1, "无响应体")
            val resp = gson.fromJson(body, QrPollResponse::class.java)
            val pollData = resp.data ?: return PollResult(-1, "无数据内容")

            return PollResult(
                status = pollData.code,
                message = pollData.message ?: "",
                cookieString = if (pollData.code == 0) combinedCookie else null
            )
        }
    }
}
