package com.biliscraper.android.api

import com.google.gson.annotations.SerializedName

data class BiliViewResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String?,
    @SerializedName("data") val data: VideoInfo?
)

data class VideoInfo(
    @SerializedName("aid") val aid: Long,
    @SerializedName("title") val title: String?,
    @SerializedName("bvid") val bvid: String?,
    @SerializedName("stat") val stat: StatInfo?
)

data class StatInfo(
    @SerializedName("reply") val reply: Long
)

data class BiliReplyMainResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String?,
    @SerializedName("data") val data: ReplyMainData?
)

data class ReplyMainData(
    @SerializedName("cursor") val cursor: CursorInfo?,
    @SerializedName("replies") val replies: List<CommentItem>?
)

data class CursorInfo(
    @SerializedName("next") val next: Long,
    @SerializedName("is_end") val isEnd: Boolean,
    @SerializedName("all_count") val allCount: Long
)

data class BiliSubReplyResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String?,
    @SerializedName("data") val data: SubReplyData?
)

data class SubReplyData(
    @SerializedName("page") val page: SubPageInfo?,
    @SerializedName("replies") val replies: List<CommentItem>?
)

data class SubPageInfo(
    @SerializedName("num") val num: Int,
    @SerializedName("size") val size: Int,
    @SerializedName("count") val count: Long
)

data class CommentItem(
    @SerializedName("rpid") val rpid: Long,
    @SerializedName("oid") val oid: Long,
    @SerializedName("root") val root: Long,
    @SerializedName("parent") val parent: Long,
    @SerializedName("rcount") val rcount: Int?,
    @SerializedName("ctime") val ctime: Long,
    @SerializedName("like") val like: Int?,
    @SerializedName("member") val member: MemberInfo?,
    @SerializedName("content") val content: ContentInfo?,
    @SerializedName("reply_control") val replyControl: ReplyControlInfo?
) {
    fun toScrapedComment(parentRpid: Long = 0): ScrapedComment {
        return ScrapedComment(
            rpid = rpid,
            parentRpid = if (parent != 0L) parent else parentRpid,
            username = member?.uname ?: "匿名网友",
            content = content?.message ?: "",
            likeCount = like ?: 0,
            timestamp = ctime,
            ipLocation = replyControl?.location ?: "未知"
        )
    }
}

data class MemberInfo(
    @SerializedName("uname") val uname: String?
)

data class ContentInfo(
    @SerializedName("message") val message: String?
)

data class ReplyControlInfo(
    @SerializedName("location") val location: String?
)

data class ScrapedComment(
    val rpid: Long,
    val parentRpid: Long,
    val username: String,
    val content: String,
    val likeCount: Int,
    val timestamp: Long,
    val ipLocation: String
) {
    fun toCsvRow(): List<String> {
        return listOf(
            rpid.toString(),
            parentRpid.toString(),
            username,
            content,
            likeCount.toString(),
            timestamp.toString(),
            ipLocation
        )
    }
}
