package com.v2ray.ang.handler

import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ProfileItem
import mobile.Mobile
import mobile.LogWriter
import mobile.SocketProtector
import java.security.SecureRandom

/**
 * Manages the olcRTC transport lifecycle.
 * olcRTC tunnels traffic through Yandex Telemost WebRTC DataChannels.
 */
object OlcrtcManager {

    /** Randomly generated SOCKS5 credentials for the current session. */
    var socksUser: String = ""
        private set
    var socksPass: String = ""
        private set

    private fun randomToken(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    /** Generates SOCKS5 credentials if not already set for this session.
     *  Called from both the TUN setup (via TProxyService) and olcRTC start,
     *  whichever runs first — the second call reuses what the first produced. */
    fun ensureCredentials() {
        if (socksUser.isEmpty()) {
            socksUser = randomToken(8)
            socksPass = randomToken(16)
        }
    }

    /**
     * Starts the olcRTC client SOCKS5 proxy.
     *
     * @param config ProfileItem with olcRTC settings
     * @param socksPort local SOCKS5 port to listen on
     * @param protectSocket callback to protect sockets from VPN routing
     * @return true if started successfully
     */
    fun start(config: ProfileItem, socksPort: Int, protectSocket: ((Int) -> Boolean)?): Boolean {
        val roomID = config.server
        val keyHex = config.password
        val duo = config.headerType == "duo"

        if (roomID.isNullOrEmpty() || keyHex.isNullOrEmpty()) {
            Log.e(AppConfig.TAG, "olcRTC: roomID or key is empty")
            return false
        }

        try {
            // Set VPN socket protector
            if (protectSocket != null) {
                Mobile.setProtector(object : SocketProtector {
                    override fun protect(p0: Long): Boolean {
                        return protectSocket(p0.toInt())
                    }
                })
            }

            // Set log bridge
            Mobile.setLogWriter(object : LogWriter {
                override fun writeLog(msg: String?) {
                    Log.d(AppConfig.TAG, "olcRTC: ${msg?.trimEnd()}")
                }
            })

            ensureCredentials()

            Mobile.start(roomID, keyHex, socksPort.toLong(), duo, socksUser, socksPass)
            Log.i(AppConfig.TAG, "olcRTC started: room=$roomID port=$socksPort duo=$duo auth=on")
            return true
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "olcRTC start failed", e)
            return false
        }
    }

    /**
     * Stops the olcRTC client.
     */
    fun stop() {
        try {
            Mobile.stop()
            Log.i(AppConfig.TAG, "olcRTC stopped")
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "olcRTC stop failed", e)
        } finally {
            socksUser = ""
            socksPass = ""
        }
    }

    /**
     * Returns true if olcRTC is currently running.
     */
    fun isRunning(): Boolean {
        return try {
            Mobile.isRunning()
        } catch (e: Exception) {
            false
        }
    }
}
