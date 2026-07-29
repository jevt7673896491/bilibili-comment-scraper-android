package com.biliscraper.android.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.TreeMap
import java.util.concurrent.TimeUnit

object WbiSigner {

    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var cachedImgKey: String? = null
    private var cachedSubKey: String? = null
    private var lastFetchTime = 0L

    @Synchronized
    private fun ensureWbiKeys(cookie: String?) {
        val now = System.currentTimeMillis()
        if (cachedImgKey != null && cachedSubKey != null && (now - lastFetchTime) < 12 * 3600 * 1000L) {
            return
        }

        try {
            val url = "https://api.bilibili.com/x/web-interface/nav"
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://www.bilibili.com/")
            if (!cookie.isNullOrBlank()) {
                builder.header("Cookie", cookie)
            }

            client.newCall(builder.build()).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                val json = gson.fromJson(bodyStr, JsonObject::class.java)
                val wbiImg = json.getAsJsonObject("data")?.getAsJsonObject("wbi_img")
                if (wbiImg != null) {
                    val imgUrl = wbiImg.get("img_url")?.asString ?: ""
                    val subUrl = wbiImg.get("sub_url")?.asString ?: ""
                    cachedImgKey = extractKey(imgUrl)
                    cachedSubKey = extractKey(subUrl)
                    lastFetchTime = now
                    return
                }
            }
        } catch (e: Exception) {
            // Ignore network exception, use fallback
        }

        if (cachedImgKey == null || cachedSubKey == null) {
            // Official stable fallback WBI keys
            cachedImgKey = "7cd084941338484aae1ad9425b84077c"
            cachedSubKey = "4932caff0ff746eab6f01bf08b70ac45"
            lastFetchTime = now
        }
    }

    private fun extractKey(url: String): String {
        return url.substringAfterLast("/").substringBefore(".")
    }

    private fun getMixinKey(orig: String): String {
        val builder = StringBuilder()
        for (i in 0 until 32) {
            if (i < MIXIN_KEY_ENC_TAB.size) {
                val index = MIXIN_KEY_ENC_TAB[i]
                if (index < orig.length) {
                    builder.append(orig[index])
                }
            }
        }
        return builder.toString()
    }

    fun signParams(params: Map<String, Any>, cookie: String? = null): Map<String, String> {
        ensureWbiKeys(cookie)
        val origKey = (cachedImgKey ?: "") + (cachedSubKey ?: "")
        val mixinKey = getMixinKey(origKey)
        val currTime = System.currentTimeMillis() / 1000L

        val sortedMap = TreeMap<String, String>()
        for ((k, v) in params) {
            sortedMap[k] = v.toString()
        }
        sortedMap["wts"] = currTime.toString()

        val queryPairs = ArrayList<String>()
        for ((k, v) in sortedMap) {
            val sanitizedVal = v.replace(Regex("[!'()*]"), "")
            queryPairs.add("${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(sanitizedVal, "UTF-8")}")
        }
        val queryStr = queryPairs.joinToString("&")
        val md5Sign = md5(queryStr + mixinKey)

        val resultMap = LinkedHashMap<String, String>(sortedMap)
        resultMap["w_rid"] = md5Sign
        return resultMap
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
