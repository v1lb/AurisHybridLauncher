package com.aurishybrid.launcher.data.obd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Локальный «виртуальный ELM327» на 127.0.0.1:<port>.
 * Hybrid Assistant подключается сюда вместо адаптера; все команды идут через ElmArbiter,
 * поэтому у адаптера всегда один TCP/BT-клиент — лаунчер.
 * Доступен только с этого же устройства (loopback), из сети не виден.
 */
class ObdProxyServer(
    private val port: Int,
    private val arbiter: () -> ElmArbiter?,
    private val onClientChange: (connected: Boolean) -> Unit,
) {
    suspend fun run() = coroutineScope {
        val server = withContext(Dispatchers.IO) {
            ServerSocket().apply {
                reuseAddress = true
                soTimeout = 1000
                bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
            }
        }
        var current: Job? = null
        var currentSocket: Socket? = null
        try {
            while (isActive) {
                val s = withContext(Dispatchers.IO) {
                    try {
                        server.accept()
                    } catch (e: SocketTimeoutException) {
                        null
                    }
                } ?: continue
                // Один активный клиент: новое подключение заменяет старое.
                currentSocket?.let { runCatching { it.close() } }
                current?.cancel()
                currentSocket = s
                current = launch(Dispatchers.IO) { handle(s) }
            }
        } finally {
            runCatching { server.close() }
            currentSocket?.let { runCatching { it.close() } }
        }
    }

    private suspend fun handle(s: Socket) {
        onClientChange(true)
        try {
            s.soTimeout = 1000
            s.tcpNoDelay = true
            val inp = s.getInputStream()
            val out = s.getOutputStream()
            val buf = StringBuilder()
            var lastCmd: String? = null
            var prevCr = false
            while (currentCoroutineContext().isActive) {
                val b = try {
                    inp.read()
                } catch (e: SocketTimeoutException) {
                    continue
                }
                if (b < 0) break
                val c = b.toChar()
                if (c != '\r' && c != '\n') {
                    buf.append(c); prevCr = false; continue
                }
                if (c == '\n' && prevCr) {
                    prevCr = false; continue
                }
                prevCr = c == '\r'
                var cmd = buf.toString().trim()
                buf.setLength(0)
                if (cmd.isEmpty()) {
                    // Как у ELM327: пустая строка повторяет последнюю команду.
                    cmd = lastCmd ?: run { out.write(">".toByteArray()); out.flush(); null } ?: continue
                }
                lastCmd = cmd
                val arb = arbiter()
                val reply = if (arb == null) {
                    "UNABLE TO CONNECT\r\r>"
                } else try {
                    arb.client(cmd)
                } catch (e: ElmTimeoutException) {
                    "NO DATA\r\r>"
                } catch (e: IOException) {
                    "UNABLE TO CONNECT\r\r>"
                }
                out.write(reply.toByteArray(Charsets.US_ASCII))
                out.flush()
            }
        } catch (e: IOException) {
            // клиент отключился
        } finally {
            onClientChange(false)
            runCatching { s.close() }
        }
    }
}
