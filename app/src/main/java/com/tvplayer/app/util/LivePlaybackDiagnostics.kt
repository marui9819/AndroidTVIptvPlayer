package com.tvplayer.app.util

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.InetAddress
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import kotlin.concurrent.thread

internal data class LivePlaybackLogEntry(
    val id: Long,
    val json: String,
    val sizeBytes: Int
)

/** A FIFO buffer bounded by both event count and the UTF-8 size of serialized events. */
internal class BoundedPlaybackLogBuffer(
    private val maxEntries: Int,
    private val maxBytes: Int
) {
    init {
        require(maxEntries > 0 && maxBytes > 0)
    }

    private val entries = ArrayDeque<LivePlaybackLogEntry>()
    private var currentBytes = 0

    @Synchronized
    fun append(id: Long, json: String): Boolean {
        val size = json.toByteArray(Charsets.UTF_8).size
        if (size <= 0 || size > maxBytes) return false
        while (entries.isNotEmpty() && (entries.size >= maxEntries || currentBytes + size > maxBytes)) {
            currentBytes -= entries.removeFirst().sizeBytes
        }
        entries.addLast(LivePlaybackLogEntry(id, json, size))
        currentBytes += size
        return true
    }

    @Synchronized
    fun readAfter(afterId: Long, limit: Int): List<LivePlaybackLogEntry> =
        entries.filter { it.id > afterId }.take(limit.coerceIn(1, MAX_RESPONSE_EVENTS))

    @Synchronized
    fun sizeBytes(): Int = currentBytes

    companion object {
        const val MAX_RESPONSE_EVENTS = 200
    }
}

/**
 * Read-only LAN endpoint for the player's sanitized, size-bounded in-memory log.
 * It binds only to the current LAN IPv4 address and starts with the playback process.
 */
object LivePlaybackLogServer {
    private const val PORT = 8766
    private const val MAX_HEADER_BYTES = 8 * 1024
    private const val MAX_HEADER_LINES = 32
    private const val SOCKET_TIMEOUT_MS = 2_000
    private const val TAG = "LivePlaybackLogServer"
    private val lock = Any()

    @Volatile
    private var running = false

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var currentSession: Session? = null

    data class Session(val address: String)

    fun isRunning(): Boolean = running

    fun start(context: Context): Result<Session> = runCatching {
        synchronized(lock) {
            currentSession?.takeIf { running }?.let { return@runCatching it }
            val ip = localIpv4Address(context.applicationContext)
                ?: error("电视尚未获取局域网 IPv4 地址")
            val address = InetAddress.getByName(ip)
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(address, PORT), 4)
                soTimeout = 1_000
            }
            val session = Session("http://$ip:$PORT/logs")
            serverSocket = socket
            currentSession = session
            running = true
            thread(isDaemon = true, name = "tv-live-playback-log-http") {
                while (running) {
                    try {
                        socket.accept().use { client -> handleClient(client) }
                    } catch (_: SocketTimeoutException) {
                        // Periodically observe stop().
                    } catch (_: Exception) {
                        if (!running) break
                        Log.w(TAG, "Live log server accept failed")
                    }
                }
            }
            session
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            runCatching { serverSocket?.close() }
            serverSocket = null
            currentSession = null
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val requestLine = readBoundedLine(reader, 1_024) ?: return
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size != 3) {
            respond(socket, 400, "Bad Request", "application/json", "{\"ok\":false}")
            return
        }
        val method = parts[0]
        val uri = runCatching { URI(parts[1]) }.getOrNull()
        if (uri == null) {
            respond(socket, 400, "Bad Request", "application/json", "{\"ok\":false}")
            return
        }

        var headerBytes = requestLine.toByteArray(Charsets.US_ASCII).size
        var headersComplete = false
        for (headerIndex in 0 until MAX_HEADER_LINES) {
            val line = readBoundedLine(reader, MAX_HEADER_BYTES - headerBytes)
            if (line == null) {
                respond(socket, 400, "Bad Request", "application/json", "{\"ok\":false}")
                return
            }
            headerBytes += line.toByteArray(Charsets.US_ASCII).size + 2
            if (headerBytes > MAX_HEADER_BYTES) {
                respond(socket, 431, "Request Header Fields Too Large", "application/json", "{\"ok\":false}")
                return
            }
            if (line.isEmpty()) {
                headersComplete = true
                break
            }
        }
        if (!headersComplete) {
            respond(socket, 431, "Request Header Fields Too Large", "application/json", "{\"ok\":false}")
            return
        }
        if (method != "GET") {
            respond(socket, 405, "Method Not Allowed", "application/json", "{\"ok\":false}")
            return
        }
        if (uri.path == "/health") {
            respond(socket, 200, "OK", "application/json; charset=utf-8", "{\"ok\":true}")
            return
        }
        if (uri.path != "/logs") {
            respond(socket, 404, "Not Found", "application/json", "{\"ok\":false}")
            return
        }

        val query = parseQuery(uri.rawQuery)
        val afterId = query["after"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val limit = query["limit"]?.toIntOrNull()?.coerceIn(1, BoundedPlaybackLogBuffer.MAX_RESPONSE_EVENTS)
            ?: BoundedPlaybackLogBuffer.MAX_RESPONSE_EVENTS
        val entries = DebugReporter.readLivePlaybackEvents(afterId, limit)
        val eventJson = entries.joinToString(",") { it.json }
        val body = "{\"ok\":true,\"latestId\":${DebugReporter.latestLivePlaybackEventId()},\"events\":[$eventJson]}"
        respond(socket, 200, "OK", "application/json; charset=utf-8", body)
    }

    private fun readBoundedLine(reader: java.io.BufferedReader, maxChars: Int): String? {
        val result = StringBuilder()
        while (result.length <= maxChars.coerceAtLeast(0)) {
            val char = reader.read()
            if (char < 0) return if (result.isEmpty()) null else result.toString()
            if (char == '\n'.code) return result.toString().trimEnd('\r')
            result.append(char.toChar())
        }
        return null
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { item ->
            val parts = item.split('=', limit = 2)
            if (parts.isEmpty()) return@mapNotNull null
            val key = URLDecoder.decode(parts[0], "UTF-8")
            val value = URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            key to value
        }.toMap()
    }

    private fun respond(socket: Socket, code: Int, reason: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    @Suppress("DEPRECATION")
    private fun localIpv4Address(context: Context): String? {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
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
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

}
