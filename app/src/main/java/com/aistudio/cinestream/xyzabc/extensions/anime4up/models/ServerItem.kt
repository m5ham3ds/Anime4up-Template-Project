package com.aistudio.cinestream.xyzabc.extensions.anime4up.models

/**
 * سيرفر مشاهدة واحد قادم من موقع Anime4up.
 * @property name       اسم السيرفر (megamax, voe.sx, ...)
 * @property url        رابط iframe الفعلي (data-watch)
 * @property quality    وصف الجودة (FHD, SD, متعدد الجودات)
 * @property isFeatured هل السيرفر مميز (★)؟
 */
data class ServerItem(
    val name: String,
    val url: String,
    val quality: String? = null,
    val isFeatured: Boolean = false
)
