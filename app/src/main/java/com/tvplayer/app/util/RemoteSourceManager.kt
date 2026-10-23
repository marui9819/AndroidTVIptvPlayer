package com.tvplayer.app.util

import android.content.Context
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.tvplayer.app.data.db.AppDatabase
import com.tvplayer.app.data.model.Playlist
import com.tvplayer.app.data.repository.PlaylistRepository
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * Mirrors the editable remote playlist addresses to the small LAN service in
 * scripts/diagnostics_server.py.  The QR contains a per-install token so a
 * different device on the LAN cannot casually edit this television's sources.
 */
class RemoteSourceManager(
    context: Context,
    private val database: AppDatabase,
    private val playlistRepository: PlaylistRepository,
    private val preferences: PreferencesHelper = PreferencesHelper(context)
) {

    companion object {
        private const val LOCAL_SERVER_PORT = 8765
        private const val PENDING_SOURCES_KEY = "embedded_remote_sources_pending"
    }

    data class SourceSpec(
        val localId: Long,
        val name: String,
        val url: String,
        val isActive: Boolean
    )

    data class SyncSummary(
        val changedCount: Int,
        val refreshedCount: Int,
        val createdCount: Int
    ) {
        val message: String
            get() = if (changedCount == 0) {
                "手机端没有新的节目源修改"
            } else {
                buildString {
                    append("已更新 $changedCount 个节目源，刷新 $refreshedCount 个")
                    if (createdCount > 0) append("，其中新增 $createdCount 个")
                }
            }
    }

    private data class SourcePayload(
        val device: String,
        val token: String,
        val sources: List<SourceSpec>
    )

    private data class SourceResponse(
        val ok: Boolean = false,
        val device: String? = null,
        val sources: List<SourceSpec> = emptyList(),
        val error: String? = null
    )

    private data class SourceIdMapping(
        val temporaryId: Long,
        val localId: Long
    )

    private data class SourceAckPayload(
        val device: String,
        val token: String,
        val mappings: List<SourceIdMapping>
    )

    private val appContext = context.applicationContext
    private val gson = Gson()
    private val localPreferences = appContext.getSharedPreferences(
        "embedded_remote_source_server",
        Context.MODE_PRIVATE
    )
    @Volatile
    private var localServer: LocalSourceServer? = null

    fun managementPageUrl(): String {
        ensureLocalServer()
        val ipAddress = localIpv4Address()
            ?: throw IllegalStateException("电视尚未获取局域网地址，请先连接 Wi-Fi")
        return "http://$ipAddress:$LOCAL_SERVER_PORT/sources?device=${encode(preferences.remoteSourceDeviceId)}" +
            "&token=${encode(preferences.remoteSourceAccessToken)}"
    }

    fun startLocalServer(): Result<String> = runCatching { managementPageUrl() }

    fun stopLocalServer() {
        synchronized(this) {
            localServer?.stop()
            localServer = null
        }
    }

    fun createQrBitmap(sizePx: Int = 480): Bitmap {
        val matrix = QRCodeWriter().encode(managementPageUrl(), BarcodeFormat.QR_CODE, sizePx, sizePx)
        return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { bitmap ->
            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                for (x in 0 until sizePx) {
                    pixels[y * sizePx + x] =
                        if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
            }
            bitmap.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    }

    suspend fun registerCurrentSources(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // The television is the endpoint now. The QR code points at this
            // embedded LAN server, so a computer-side Python service is not
            // required for phone editing.
            managementPageUrl()
            Unit
        }
    }

    suspend fun syncFromServer(): Result<SyncSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = localPreferences.getString(PENDING_SOURCES_KEY, null)
                ?: return@runCatching SyncSummary(0, 0, 0)
            val response = gson.fromJson(payload, SourceResponse::class.java)
            check(response.ok) { response.error ?: "手机提交的节目源格式无效" }
            val summary = applySourceSpecs(response.sources)
            localPreferences.edit().remove(PENDING_SOURCES_KEY).apply()
            summary
        }
    }

    private suspend fun applySourceSpecs(remoteSources: List<SourceSpec>): SyncSummary {
        var changed = 0
        var refreshed = 0
        var created = 0
        remoteSources.forEach { remote ->
            if (!remote.url.isHttpUrl()) return@forEach
            val existing = database.playlistDao().getPlaylistById(remote.localId)
            if (existing == null && remote.localId <= 0L) {
                val sortOrder = database.playlistDao().getAllPlaylistsList().size
                val playlist = Playlist.createRemote(
                    name = remote.name.ifBlank { "手机新增节目源" },
                    url = remote.url.trim(),
                    description = "手机扫码新增"
                ).copy(isActive = remote.isActive, sortOrder = sortOrder)
                val newId = database.playlistDao().insertPlaylist(playlist)
                val saved = database.playlistDao().getPlaylistById(newId) ?: return@forEach
                changed += 1
                created += 1
                if (saved.isActive) {
                    playlistRepository.refreshPlaylist(saved)
                    refreshed += 1
                }
            } else if (existing != null && !existing.isLocal()) {
                val updated = existing.copy(
                    name = remote.name.ifBlank { existing.name },
                    url = remote.url.trim(),
                    isActive = remote.isActive,
                    channelCount = if (remote.isActive) existing.channelCount else 0
                )
                if (updated == existing) return@forEach
                database.playlistDao().update(updated)
                if (!updated.isActive) {
                    database.channelDao().deleteChannelsByPlaylist(existing.id)
                }
                changed += 1
                if (updated.isActive) {
                    playlistRepository.refreshPlaylist(updated)
                    refreshed += 1
                }
            }
        }
        return SyncSummary(changed, refreshed, created)
    }

    private fun ensureLocalServer() {
        synchronized(this) {
            if (localServer?.isRunning == true) return
            LocalSourceServer().also {
                it.start()
                localServer = it
            }
        }
    }

    private fun localIpv4Address(): String? {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiIp = wifiManager?.connectionInfo?.ipAddress ?: 0
        if (wifiIp != 0) {
            return listOf(
                wifiIp and 0xff,
                wifiIp shr 8 and 0xff,
                wifiIp shr 16 and 0xff,
                wifiIp shr 24 and 0xff
            ).joinToString(".")
        }
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()
    }

    private fun Playlist.toSourceSpecOrNull(): SourceSpec? {
        val sourceUrl = url?.trim().orEmpty()
        return if (isLocal() || !sourceUrl.isHttpUrl()) {
            null
        } else {
            SourceSpec(id, name, sourceUrl, isActive)
        }
    }

    private fun String.isHttpUrl(): Boolean =
        startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun currentPageSources(): List<SourceSpec> {
        val pending = localPreferences.getString(PENDING_SOURCES_KEY, null)
            ?.let { json -> runCatching { gson.fromJson(json, SourceResponse::class.java).sources }.getOrNull() }
        return pending ?: runBlocking(Dispatchers.IO) {
            database.playlistDao().getAllPlaylistsList().mapNotNull { it.toSourceSpecOrNull() }
        }
    }

    private fun savePendingSources(device: String, sources: List<SourceSpec>) {
        localPreferences.edit().putString(
            PENDING_SOURCES_KEY,
            gson.toJson(SourceResponse(ok = true, device = device, sources = sources))
        ).apply()
    }

    private fun htmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun parseUrlEncoded(raw: String?): Map<String, List<String>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&')
            .mapNotNull { item ->
                val parts = item.split('=', limit = 2)
                if (parts.isEmpty() || parts[0].isBlank()) return@mapNotNull null
                val key = URLDecoder.decode(parts[0], "UTF-8")
                val value = URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                key to value
            }
            .groupBy({ it.first }, { it.second })
    }

    private fun managementPageHtml(device: String, token: String): String {
        val sources = currentPageSources()
        val action = "/sources?device=${encode(device)}&token=${encode(token)}"
        val rows = sources.mapIndexed { index, source ->
            """
            <section class="source">
              <input type="hidden" name="localId_$index" value="${source.localId}">
              <label>名称<input name="name_$index" value="${htmlEscape(source.name)}"></label>
              <label>节目源地址<input type="url" name="url_$index" required value="${htmlEscape(source.url)}"></label>
              <label class="enabled"><input type="checkbox" name="active_$index"${if (source.isActive) " checked" else ""}> 启用此节目源</label>
            </section>
            """.trimIndent()
        }.joinToString("\n")
        return """
        <!doctype html><html lang="zh-CN"><head>
        <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>5xingTV 节目源</title>
        <style>
        body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;margin:0;background:#101820;color:#eef7fb}
        main{max-width:720px;margin:auto;padding:22px}h1{font-size:26px}
        p{color:#a9c4d4;line-height:1.6}.source{background:#1f303b;border:1px solid #3c657a;border-radius:12px;padding:14px;margin:14px 0}
        label{display:block;font-weight:600;margin:8px 0}input[type=url],input:not([type]){box-sizing:border-box;width:100%;margin-top:6px;padding:12px;border:1px solid #638396;border-radius:8px;font-size:16px}
        .enabled{font-weight:400}button{width:100%;padding:15px;border:0;border-radius:10px;background:#15bcd4;color:#06222b;font-size:17px;font-weight:700;margin-top:12px}.secondary{background:#334d5d;color:#e8f8ff}
        </style></head><body><main><h1>5xingTV 节目源编辑</h1>
        <p>手机直接连接电视编辑节目源。保存后回到电视，按“从手机同步修改”应用变更。</p>
        <form method="post" action="${htmlEscape(action)}"><div id="sources">$rows</div>
        <input id="sourceCount" type="hidden" name="sourceCount" value="${sources.size}">
        <button class="secondary" type="button" onclick="addSource()">＋ 新增节目源</button>
        <button type="submit">保存到电视</button></form></main>
        <script>function addSource(){var c=document.getElementById('sourceCount');var i=Number(c.value);var id=-(Date.now()*10+i);var s=document.createElement('section');s.className='source';s.innerHTML='<input type="hidden" name="localId_'+i+'" value="'+id+'"><label>名称<input name="name_'+i+'" placeholder="例如：我的 IPTV"></label><label>节目源地址<input type="url" name="url_'+i+'" required placeholder="https://example.com/list.m3u"></label><label class="enabled"><input type="checkbox" name="active_'+i+'" checked> 启用此节目源</label>';document.getElementById('sources').appendChild(s);c.value=i+1;s.scrollIntoView({behavior:'smooth',block:'center'});}</script>
        </body></html>
        """.trimIndent()
    }

    private inner class LocalSourceServer {
        @Volatile
        var isRunning: Boolean = false
            private set
        private var serverSocket: ServerSocket? = null

        fun start() {
            if (isRunning) return
            val socket = ServerSocket(LOCAL_SERVER_PORT)
            socket.soTimeout = 1_000
            serverSocket = socket
            isRunning = true
            thread(isDaemon = true, name = "tv-source-http") {
                while (isRunning) {
                    try {
                        socket.accept().use { handleClient(it) }
                    } catch (_: SocketTimeoutException) {
                        // Allows stop() to be observed without a blocking accept.
                    } catch (_: Exception) {
                        if (isRunning) continue
                    }
                }
            }
        }

        fun stop() {
            isRunning = false
            runCatching { serverSocket?.close() }
            serverSocket = null
        }

        private fun handleClient(socket: Socket) {
            socket.soTimeout = 8_000
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val requestLine = reader.readLine() ?: return
            val requestParts = requestLine.split(' ')
            if (requestParts.size < 2) return
            val method = requestParts[0]
            val uri = runCatching { URI(requestParts[1]) }.getOrNull() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 1_000_000) ?: 0
            val body = if (contentLength > 0) {
                CharArray(contentLength).also { chars ->
                    var offset = 0
                    while (offset < chars.size) {
                        val count = reader.read(chars, offset, chars.size - offset)
                        if (count <= 0) break
                        offset += count
                    }
                }.concatToString()
            } else {
                ""
            }
            val query = parseUrlEncoded(uri.rawQuery)
            val device = query["device"]?.firstOrNull()
            val token = query["token"]?.firstOrNull()
            if (device != preferences.remoteSourceDeviceId || token != preferences.remoteSourceAccessToken) {
                send(socket, "403 Forbidden", "text/plain; charset=utf-8", "二维码令牌无效")
                return
            }
            when {
                method == "GET" && uri.path == "/sources" -> {
                    send(socket, "200 OK", "text/html; charset=utf-8", managementPageHtml(device, token))
                }
                method == "GET" && uri.path == "/api/sources" -> {
                    val response = SourceResponse(ok = true, device = device, sources = currentPageSources())
                    send(socket, "200 OK", "application/json; charset=utf-8", gson.toJson(response))
                }
                method == "POST" && uri.path == "/sources" -> {
                    runCatching { saveForm(device, parseUrlEncoded(body)) }
                        .onSuccess { sendRedirect(socket, "/sources?device=${encode(device)}&token=${encode(token)}") }
                        .onFailure { send(socket, "400 Bad Request", "text/plain; charset=utf-8", it.message ?: "节目源格式无效") }
                }
                method == "POST" && uri.path == "/api/sources/register" -> {
                    val response = SourceResponse(ok = true, device = device, sources = currentPageSources())
                    send(socket, "200 OK", "application/json; charset=utf-8", gson.toJson(response))
                }
                else -> send(socket, "404 Not Found", "text/plain; charset=utf-8", "Not Found")
            }
        }

        private fun saveForm(device: String, form: Map<String, List<String>>) {
            val existing = currentPageSources()
            val count = form["sourceCount"]?.firstOrNull()?.toIntOrNull()?.coerceIn(0, 200) ?: existing.size
            val sources = buildList {
                repeat(count) { index ->
                    val old = existing.getOrNull(index)
                    val id = form["localId_$index"]?.firstOrNull()?.toLongOrNull() ?: old?.localId ?: return@repeat
                    val url = form["url_$index"]?.firstOrNull().orEmpty().trim()
                    if (url.isBlank() && index >= existing.size) return@repeat
                    require(url.isHttpUrl()) { "第 ${index + 1} 个节目源地址无效" }
                    add(
                        SourceSpec(
                            localId = id,
                            name = form["name_$index"]?.firstOrNull().orEmpty().trim()
                                .ifBlank { old?.name ?: "手机新增节目源" },
                            url = url,
                            isActive = form.containsKey("active_$index")
                        )
                    )
                }
            }
            savePendingSources(device, sources)
        }

        private fun sendRedirect(socket: Socket, location: String) {
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 303 See Other\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
        }

        private fun send(socket: Socket, status: String, contentType: String, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val output = socket.getOutputStream()
            output.write(
                "HTTP/1.1 $status\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    .toByteArray()
            )
            output.write(bytes)
            output.flush()
        }
    }
}
