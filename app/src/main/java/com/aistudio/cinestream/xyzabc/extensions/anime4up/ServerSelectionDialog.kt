package com.aistudio.cinestream.xyzabc.extensions.anime4up

// ✅ الاستيرادات تبقى كما هي
import com.aistudio.cinestream.xyzabc.extensions.anime4up.models.ServerItem
import com.aistudio.cinestream.xyzabc.extensions.anime4up.models.VideoQuality

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ============================================================
//  حالة الواجهة
//  ملاحظة: ServerItem و VideoQuality مُستوردان من models/ — لا نُعرِّف هنا
// ============================================================

sealed class DialogState {
    object LoadingServers : DialogState()
    data class ServersReady(val servers: List<ServerItem>) : DialogState()
    data class Extracting(val server: ServerItem, val progress: String) : DialogState()
    data class QualitiesReady(
        val server: ServerItem,
        val qualities: List<VideoQuality>
    ) : DialogState()
    data class Error(val message: String, val server: ServerItem? = null) : DialogState()
}

// ============================================================
//  Parser: من JSON القادم من AndroidBridge إلى ServerItem
// ============================================================

object ServerJsonParser {

    /**
     * يحلّل مصفوفة JSON قادمة من JavaScript.
     * البنية المتوقعة:
     * [
     *   { "name": "...", "url": "...", "quality": "...", "featured": true }
     * ]
     */
    fun parse(json: String): List<ServerItem> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<ServerItem>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                val url = obj.optString("url", "").trim()
                if (url.isEmpty()) continue
                out.add(
                    ServerItem(
                        name = name.ifEmpty { "سيرفر ${i + 1}" },
                        url = url,
                        quality = obj.optString("quality", "").ifBlank { null },
                        isFeatured = obj.optBoolean("featured", false)
                    )
                )
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }
}

// ============================================================
//  الواجهة الرئيسية
// ============================================================

@Composable
fun ServerSelectionDialog(
    serversJson: String,
    onDismiss: () -> Unit,
    onPlayDirect: (url: String, headers: Map<String, String>) -> Unit,
    onPlayIframe: (url: String) -> Unit
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<DialogState>(DialogState.LoadingServers) }
    val extractor = remember { VideoExtractor(context) }

    // ✅ مربوط بـ serversJson — يُعاد التحليل عند تغيّر السيرفرات
    LaunchedEffect(serversJson) {
        state = DialogState.LoadingServers
        val parsed = withContext(Dispatchers.Default) {
            ServerJsonParser.parse(serversJson)
        }
        state = if (parsed.isEmpty()) {
            DialogState.Error("لم يتم العثور على أي سيرفر مشاهدة.")
        } else {
            DialogState.ServersReady(parsed)
        }
    }

    Dialog(
        onDismissRequest = {
            extractor.destroy()
            onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(20.dp)),
            color = Color(0xFF131722),
            tonalElevation = 8.dp
        ) {
            Column(Modifier.fillMaxSize()) {

                // ---------- الشريط العلوي ----------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF181D2B))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "اختيار السيرفر",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            extractor.destroy()
                            onDismiss()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "إغلاق",
                            tint = Color.White
                        )
                    }
                }

                // ---------- المحتوى ----------
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when (val s = state) {
                        is DialogState.LoadingServers -> LoadingView("جاري تحميل السيرفرات...")

                        is DialogState.ServersReady -> ServersList(
                            servers = s.servers,
                            onSelect = { server ->
                                state = DialogState.Extracting(server, "جاري فحص السيرفر...")
                                extractor.extract(
                                    server.url,
                                    object : VideoExtractor.ExtractionCallback {
                                        override fun onDirectVideo(
                                            videoUrl: String,
                                            headers: Map<String, String>
                                        ) {
                                            val q = VideoQuality(
                                                label = server.quality ?: "جودة تلقائية",
                                                url = videoUrl,
                                                isDirect = true,
                                                headers = headers
                                            )
                                            state = DialogState.QualitiesReady(
                                                server = server,
                                                qualities = listOf(q)
                                            )
                                        }

                                        override fun onIframeFallback(iframeUrl: String) {
                                            val q = VideoQuality(
                                                label = server.quality ?: "iframe",
                                                url = iframeUrl,
                                                isDirect = false
                                            )
                                            state = DialogState.QualitiesReady(
                                                server = server,
                                                qualities = listOf(q)
                                            )
                                        }

                                        override fun onFailure(reason: String) {
                                            state = DialogState.Error(reason, server)
                                        }

                                        override fun onProgress(message: String) {
                                            state = DialogState.Extracting(server, message)
                                        }
                                    }
                                )
                            }
                        )

                        is DialogState.Extracting -> ExtractingView(s.server, s.progress)

                        is DialogState.QualitiesReady -> QualitiesList(
                            server = s.server,
                            qualities = s.qualities,
                            onPlay = { quality ->
                                extractor.destroy()
                                if (quality.isDirect) {
                                    onPlayDirect(quality.url, quality.headers)
                                } else {
                                    onPlayIframe(quality.url)
                                }
                            }
                        )

                        is DialogState.Error -> ErrorView(
                            message = s.message,
                            onRetry = if (s.server != null) {
                                { state = DialogState.ServersReady(emptyList()) }
                            } else null
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
//  مكونات فرعية
// ============================================================

@Composable
private fun LoadingView(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = Color(0xFF05D3E7))
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            color = Color(0xFFB0B8C4),
            fontSize = 14.sp
        )
    }
}

@Composable
private fun ServersList(
    servers: List<ServerItem>,
    onSelect: (ServerItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(servers, key = { it.url }) { server ->
            ServerRow(server = server, onClick = { onSelect(server) })
        }
    }
}

@Composable
private fun ServerRow(server: ServerItem, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = Color(0xFF0C111B)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (server.isFeatured) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "مميز",
                    tint = Color(0xFFFBBF24),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!server.quality.isNullOrEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = server.quality,
                        color = Color(0xFF8FA0B8),
                        fontSize = 12.sp
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "تشغيل",
                tint = Color(0xFF05D3E7)
            )
        }
    }
}

@Composable
private fun ExtractingView(server: ServerItem, progress: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = Color(0xFF05D3E7))
        Spacer(Modifier.height(20.dp))
        Text(
            text = "جاري استخراج الفيديو...",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = server.name,
            color = Color(0xFF05D3E7),
            fontSize = 14.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = progress,
            color = Color(0xFF8FA0B8),
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun QualitiesList(
    server: ServerItem,
    qualities: List<VideoQuality>,
    onPlay: (VideoQuality) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            text = "السيرفر: ${server.name}",
            color = Color(0xFF05D3E7),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(qualities) { q ->
                QualityRow(q, onClick = { onPlay(q) })
            }
        }
    }
}

@Composable
private fun QualityRow(quality: VideoQuality, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = Color(0xFF0C111B)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color(0xFF05D3E7)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = quality.label,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (quality.isDirect) "مباشر" else "iframe",
                color = if (quality.isDirect) Color(0xFF22C55E) else Color(0xFFFBBF24),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = Color(0xFFEF4444),
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            color = Color(0xFFE5E7EB),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF05D3E7),
                modifier = Modifier.clickable(onClick = onRetry)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = Color.Black
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "إعادة المحاولة",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
