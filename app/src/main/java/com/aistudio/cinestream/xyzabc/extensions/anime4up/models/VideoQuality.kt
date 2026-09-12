package com.aistudio.cinestream.xyzabc.extensions.anime4up.models

/**
 * جودة فيديو نهائية بعد الاستخراج.
 * @property label     اسم الجودة المعروض للمستخدم
 * @property url       الرابط (m3u8 / mp4 / iframe)
 * @property isDirect  true = رابط مباشر يُشغل بـ ExoPlayer، false = iframe
 * @property headers   ترويسات إضافية (Referer/User-Agent)
 */
data class VideoQuality(
    val label: String,
    val url: String,
    val isDirect: Boolean,
    val headers: Map<String, String> = emptyMap()
)
