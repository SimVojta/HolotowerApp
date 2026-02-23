package com.holotower.app.data.model

import com.google.gson.annotations.SerializedName
import java.text.SimpleDateFormat
import java.util.*

data class CatalogPage(
    @SerializedName("page")    val page: Int,
    @SerializedName("threads") val threads: List<CatalogThread>
)

data class CatalogThread(
    @SerializedName("no")             val no: Long,
    @SerializedName("sub")            val sub: String?,
    @SerializedName("com")            val com: String?,
    @SerializedName("name")           val name: String?,
    @SerializedName("capcode")        val capcode: String?,   // "Mod", "Admin", etc.
    @SerializedName("time")           val time: Long,         // Unix timestamp
    @SerializedName("omitted_posts")  val omittedPosts: Int,
    @SerializedName("omitted_images") val omittedImages: Int,
    @SerializedName("replies")        val replies: Int,
    @SerializedName("images")         val images: Int,
    @SerializedName("sticky")         val sticky: Int,
    @SerializedName("locked")         val locked: Int,
    @SerializedName("cyclical")       val cyclical: Int,
    @SerializedName("last_modified")  val lastModified: Long,
    @SerializedName("tn_h")           val tnH: Int?,
    @SerializedName("tn_w")           val tnW: Int?,
    @SerializedName("h")              val h: Int?,
    @SerializedName("w")              val w: Int?,
    @SerializedName("fsize")          val fsize: Long?,
    @SerializedName("ext")            val ext: String?,
    @SerializedName("tim")            val tim: String?,       // STRING e.g. "1759201016928"
    @SerializedName("filename")       val filename: String?,
    @SerializedName("md5")            val md5: String?,
    @SerializedName("resto")          val resto: Long,
    @SerializedName("spoiler")        val spoiler: Int?
) {
    val isSticky get() = sticky == 1
    val isLocked get() = locked == 1
    val isMod    get() = capcode != null

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

    fun formattedTime(): String {
        val sdf = SimpleDateFormat("MM/dd/yy HH:mm", Locale.getDefault())
        return sdf.format(Date(time * 1000L))
    }
}
