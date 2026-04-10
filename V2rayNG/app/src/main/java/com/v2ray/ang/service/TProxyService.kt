package com.v2ray.ang.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.OlcrtcManager
import com.v2ray.ang.handler.SettingsManager
import java.io.File

/**
 * Manages the tun2socks process that handles VPN traffic
 */
class TProxyService(
    private val context: Context,
    private val vpnInterface: ParcelFileDescriptor,
    private val isRunningProvider: () -> Boolean,
    private val restartCallback: () -> Unit
) : Tun2SocksControl {
    companion object {
        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int)
        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService()
        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyGetStats(): LongArray?

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }

    /**
     * Starts the tun2socks process with the appropriate parameters.
     */
    override fun startTun2Socks() {
//        Log.i(AppConfig.TAG, "Starting HevSocks5Tunnel via JNI")

        val configContent = buildConfig()
        val configFile = File(context.filesDir, "hev-socks5-tunnel.yaml").apply {
            writeText(configContent)
        }
//        Log.i(AppConfig.TAG, "Config file created: ${configFile.absolutePath}")
        Log.d(AppConfig.TAG, "HevSocks5Tunnel Config content:\n$configContent")

        try {
//            Log.i(AppConfig.TAG, "TProxyStartService...")
            TProxyStartService(configFile.absolutePath, vpnInterface.fd)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "HevSocks5Tunnel exception: ${e.message}")
        }
    }

    private fun buildConfig(): String {
        val socksPort = SettingsManager.getSocksPort()
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        return buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${SettingsManager.getVpnMtu()}")
            appendLine("  ipv4: ${vpnConfig.ipv4Client}")

            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6)) {
                appendLine("  ipv6: '${vpnConfig.ipv6Client}'")
            }

            val olcrtc = isOlcrtcSelected()
            if (olcrtc) {
                // TProxyService runs before OlcrtcManager.start(); make sure the
                // credentials exist so the yaml and olcRTC agree on them.
                OlcrtcManager.ensureCredentials()
            }
            appendLine("socks5:")
            appendLine("  port: ${socksPort}")
            appendLine("  address: ${AppConfig.LOOPBACK}")
            appendLine("  udp: 'udp'")
            if (olcrtc && OlcrtcManager.socksUser.isNotEmpty()) {
                appendLine("  username: '${OlcrtcManager.socksUser}'")
                appendLine("  password: '${OlcrtcManager.socksPass}'")
            }

            // olcRTC's SOCKS5 only supports TCP CONNECT (no UDP ASSOCIATE).
            // mapdns intercepts DNS queries destined to a public-looking IP,
            // hands back fake IPs from 100.64.0.0/10, then on connect substitutes
            // the real domain into the SOCKS5 request so resolution happens server-side.
            // Using 1.1.1.1 here makes the VPN DNS look like a normal public resolver
            // to fingerprinting tools instead of a suspicious private address.
            if (olcrtc) {
                appendLine("mapdns:")
                appendLine("  address: ${AppConfig.OLCRTC_FAKE_DNS}")
                appendLine("  port: 53")
                appendLine("  network: 100.64.0.0")
                appendLine("  netmask: 255.192.0.0")
                appendLine("  cache-size: 10000")
            }

            // Read-write timeout settings
            val timeoutSetting = MmkvManager.decodeSettingsString(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT) ?: AppConfig.HEVTUN_RW_TIMEOUT
            val parts = timeoutSetting.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val tcpTimeout = parts.getOrNull(0)?.toIntOrNull() ?: 300
            val udpTimeout = parts.getOrNull(1)?.toIntOrNull() ?: 60

            appendLine("misc:")
            appendLine("  tcp-read-write-timeout: ${tcpTimeout * 1000}")
            appendLine("  udp-read-write-timeout: ${udpTimeout * 1000}")
            appendLine("  log-level: ${MmkvManager.decodeSettingsString(AppConfig.PREF_HEV_TUNNEL_LOGLEVEL) ?: "warn"}")
        }
    }

    private fun isOlcrtcSelected(): Boolean {
        val guid = MmkvManager.getSelectServer() ?: return false
        val config = MmkvManager.decodeServerConfig(guid) ?: return false
        return config.configType == EConfigType.OLCRTC
    }

    /**
     * Stops the tun2socks process
     */
    override fun stopTun2Socks() {
        try {
            Log.i(AppConfig.TAG, "TProxyStopService...")
            TProxyStopService()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to stop hev-socks5-tunnel", e)
        }
    }
}
