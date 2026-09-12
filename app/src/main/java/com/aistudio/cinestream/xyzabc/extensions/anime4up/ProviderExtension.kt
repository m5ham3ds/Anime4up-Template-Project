package com.aistudio.cinestream.xyzabc.extensions.anime4up

/**
 * الواجهة الأساسية لأي إضافة في التطبيق.
 * كل إضافة جديدة يجب أن تُنفّذ هذه الواجهة.
 */
interface ProviderExtension {

    /** معرّف فريد للإضافة (بالإنجليزية، بدون مسافات) */
    val id: String

    /** اسم الإضافة المعروض للمستخدم */
    val name: String

    /** الرابط الأساسي للموقع */
    val baseUrl: String

    /** هل تدعم الأنمي؟ */
    val isAnime: Boolean

    /** هل تدعم الأفلام؟ */
    val isMovie: Boolean

    /** هل تدعم المسلسلات؟ */
    val isSeries: Boolean

    /** لغة الإضافة (ar, en, ...) */
    val lang: String

    /** رابط أيقونة الإضافة */
    val iconUrl: String

    /**
     * بناء رابط البحث.
     * @param titleOriginal العنوان الأصلي (إنجليزي عادةً)
     * @param titleClean    العنوان بعد التنظيف
     */
    fun getSearchUrl(titleOriginal: String, titleClean: String): String

    /**
     * كود JavaScript الذي سيُحقن داخل WebView لاستخراج السيرفرات.
     * يجب أن يستدعي:
     *   AndroidBridge.sendServersV2(jsonString, currentUrl)
     * أو:
     *   AndroidBridge.sendFailed()
     */
    fun getExtractionScript(isMovie: Boolean, episode: Int, title: String): String
}
