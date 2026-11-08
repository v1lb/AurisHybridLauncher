package com.aurishybrid.launcher.data.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

sealed interface TransportConfig {
    val label: String

    data class Wifi(val host: String, val port: Int) : TransportConfig {
        override val label: String get() = "Wi-Fi $host:$port"
    }

    data class Bluetooth(val address: String) : TransportConfig {
        override val label: String get() = "Bluetooth $address"
    }
}

interface ObdTransport : Closeable {
    val input: InputStream
    val output: OutputStream
    val label: String
}

private class SocketTransport(private val socket: Socket, override val label: String) : ObdTransport {
    override val input: InputStream = socket.getInputStream()
    override val output: OutputStream = socket.getOutputStream()
    override fun close() {
        runCatching { socket.close() }
    }
}

private class BluetoothTransport(private val socket: BluetoothSocket, override val label: String) : ObdTransport {
    override val input: InputStream = socket.inputStream
    override val output: OutputStream = socket.outputStream
    override fun close() {
        runCatching { socket.close() }
    }
}

object Transports {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    suspend fun open(context: Context, cfg: TransportConfig): ObdTransport = when (cfg) {
        is TransportConfig.Wifi -> openWifi(context, cfg)
        is TransportConfig.Bluetooth -> openBluetooth(context, cfg)
    }

    /**
     * Сеть адаптера (WIFI_OBDII) не имеет интернета, поэтому Android может не сделать её
     * сетью по умолчанию. Сокет явно привязывается к Wi-Fi сети, иначе трафик уйдёт
     * в мобильную сеть и 192.168.0.10 будет недоступен.
     */
    @Suppress("DEPRECATION")
    fun wifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private suspend fun openWifi(context: Context, cfg: TransportConfig.Wifi): ObdTransport =
        withContext(Dispatchers.IO) {
            val net = wifiNetwork(context)
                ?: throw IOException("Wi-Fi не подключён (нужна сеть адаптера, например WIFI_OBDII)")
            val socket = Socket()
            try {
                net.bindSocket(socket)
                socket.connect(InetSocketAddress(cfg.host, cfg.port), 5000)
                socket.tcpNoDelay = true
                socket.keepAlive = true
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw IOException("TCP ${cfg.host}:${cfg.port} — ${e.message}")
            }
            SocketTransport(socket, cfg.label)
        }

    @SuppressLint("MissingPermission")
    private suspend fun openBluetooth(context: Context, cfg: TransportConfig.Bluetooth): ObdTransport =
        withContext(Dispatchers.IO) {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw IOException("Bluetooth недоступен")
            if (!adapter.isEnabled) throw IOException("Bluetooth выключен")
            val device = adapter.getRemoteDevice(cfg.address)
            adapter.cancelDiscovery()
            var socket = device.createRfcommSocketToServiceRecord(SPP)
            try {
                socket.connect()
            } catch (e: IOException) {
                runCatching { socket.close() }
                socket = device.createInsecureRfcommSocketToServiceRecord(SPP)
                socket.connect()
            }
            BluetoothTransport(socket, cfg.label)
        }
}
