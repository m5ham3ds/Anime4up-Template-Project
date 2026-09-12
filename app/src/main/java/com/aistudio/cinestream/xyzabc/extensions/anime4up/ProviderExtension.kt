package com.aistudio.cinestream.xyzabc.extensions.anime4up

/**
 * هذه هي الواجهة (Interface) الأساسية.
 * لا تقم بتعديل هذا الملف أبداً.
 * كل إضافة جديدة يجب أن "تَرِث" (Implement) هذه الواجهة وتوفر القيم الخاصة بها.
 */
interface ProviderExtension {
    // معلومات أساسية عن الإضافة يقرأها التطبيق لعرضها للمستخدم
    val id: String
    val name: String
    val baseUrl: String
    
    // نوع المحتوى الذي يدعمه الموقع
    val isAnime: Boolean
    val isMovie: Boolean
    val isSeries: Boolean
    
    // لغة الإضافة وأيقونتها (إن وجدت)
    val lang: String
    val iconUrl: String

    // الدالة المسؤولة عن بناء رابط البحث الخاص بالموقع
    fun getSearchUrl(titleOriginal: String, titleClean: String): String
    
    // الدالة المسؤولة عن إرسال كود الجافا سكريبت الذي سيعمل داخل المتصفح المخفي
    fun getExtractionScript(isMovie: Boolean, episode: Int, title: String): String
}