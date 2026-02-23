package com.holotower.app.data.model

import com.google.gson.annotations.SerializedName
import java.text.SimpleDateFormat
import java.util.*

data class ThreadResponse(
    @SerializedName("posts") val posts: List<Post>
)

data class Post(
    @SerializedName("no")             val no: Long,
    @SerializedName("name")           val name: String?,
    @SerializedName("capcode")        val capcode: String?,
    @SerializedName("com")            val com: String?,
    @SerializedName("time")           val time: Long,        // Unix timestamp
    @SerializedName("filename")       val filename: String?,
    @SerializedName("ext")            val ext: String?,
    @SerializedName("tim")            val tim: String?,      // STRING
    @SerializedName("fsize")          val fsize: Long?,
    @SerializedName("w")              val w: Int?,
    @SerializedName("h")              val h: Int?,
    @SerializedName("tn_w")           val tnW: Int?,
    @SerializedName("tn_h")           val tnH: Int?,
    @SerializedName("md5")            val md5: String?,
    @SerializedName("resto")          val resto: Long,       // 0 = OP
    @SerializedName("replies")        val replies: Int?,
    @SerializedName("images")         val images: Int?,
    @SerializedName("omitted_posts")  val omittedPosts: Int?,
    @SerializedName("omitted_images") val omittedImages: Int?,
    @SerializedName("sticky")         val sticky: Int?,
    @SerializedName("locked")         val locked: Int?,
    @SerializedName("spoiler")        val spoiler: Int?,
    @SerializedName("sub")            val sub: String? = null
) {
    val isOp  get() = resto == 0L
    val isMod get() = capcode != null

    fun thumbnailUrls(board: String): List<String> {
        val t = tim ?: return emptyList()
        val preferred = when (ext?.lowercase()) {
            ".png" -> listOf(".png", ".jpg", ".webp")
            else -> listOf(".jpg", ".png", ".webp")
        }
        val primary = preferred.first()
        val rest = preferred.drop(1)
        return buildList {
            add("https://holotower.org/$board/thumb/$t$primary")
            add("https://holotower.org/$board/thumb/${t}s$primary")
            rest.forEach { suffix ->
                add("https://holotower.org/$board/thumb/$t$suffix")
                add("https://holotower.org/$board/thumb/${t}s$suffix")
            }
        }.distinct()
    }

    fun thumbnailUrl(board: String): String? = thumbnailUrls(board).firstOrNull()

    fun fullImageUrl(board: String) =
        tim?.let { "https://holotower.org/$board/src/$it$ext" }

    fun fileSizeFormatted(): String {
        if (fsize == null) return ""
        return when {
            fsize >= 1_000_000 -> "${"%.1f".format(fsize / 1_000_000.0)}MB"
            fsize >= 1_000     -> "${fsize / 1000}KB"
            else               -> "${fsize}B"
        }
    }

    fun formattedTime(): String {
        val sdf = SimpleDateFormat("MM/dd/yy(EEE)HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(time * 1000L))
    }
}
