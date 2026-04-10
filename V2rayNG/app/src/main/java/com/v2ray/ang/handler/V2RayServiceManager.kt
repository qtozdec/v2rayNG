package com.v2ray.ang.handler

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.OlcrtcManager
import com.v2ray.ang.service.V2RayProxyOnlyService
import com.v2ray.ang.service.V2RayVpnService
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import java.lang.ref.SoftReference

object V2RayServiceManager {

    private val coreController: CoreController = V2RayNativeManager.newCoreController(CoreCallback())
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            V2RayNativeManager.initCoreEnv(value?.get()?.getService())
        }

    /**
     * Starts the V2Ray service from a toggle action.
     * @param context The context from which the service is started.
     * @return True if the service was started successfully, false otherwise.
     */
    fun startVServiceFromToggle(context: Context): Boolean {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            context.toast(R.string.app_tile_first_use)
            return false
        }
        startContextService(context)
        return true
    }

    /**
     * Starts the V2Ray service.
     * @param context The context from which the service is started.
     * @param guid The GUID of the server configuration to use (optional).
     */
    fun startVService(context: Context, guid: String? = null) {
        Log.i(AppConfig.TAG, "StartCore-Manager: startVService from ${context::class.java.simpleName}")

        if (guid != null) {
            MmkvManager.setSelectServer(guid)
        }

        startContextService(context)
    }

    /**
     * Stops the V2Ray service.
     * @param context The context from which the service is stopped.
     */
    fun stopVService(context: Context) {
        //context.toast(R.string.toast_services_stop)
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = coreController.isRunning || OlcrtcManager.isRunning()

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Starts the context service for V2Ray.
     * Chooses between VPN service or Proxy-only service based on user settings.
     * @param context The context from which the service is started.
     */
    private fun startContextService(context: Context) {
        if (coreController.isRunning) {
            Log.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return
        }

        val guid = MmkvManager.getSelectServer()
        if (guid == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: No server selected")
            return
        }

        val config = MmkvManager.decodeServerConfig(guid)
        if (config == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to decode server config")
            return
        }

        if (config.configType != EConfigType.CUSTOM
            && config.configType != EConfigType.POLICYGROUP
            && config.configType != EConfigType.OLCRTC
            && !Utils.isValidUrl(config.server)
            && !Utils.isPureIpAddress(config.server.orEmpty())
        ) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Invalid server configuration")
            return
        }
//        val result = V2rayConfigUtil.getV2rayConfig(context, guid)
//        if (!result.status) return

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
            context.toast(R.string.toast_warning_pref_proxysharing_short)
        } else {
            context.toast(R.string.toast_services_start)
        }

        val isVpnMode = SettingsManager.isVpnMode()
        val intent = if (isVpnMode) {
            Log.i(AppConfig.TAG, "StartCore-Manager: Starting VPN service")
            Intent(context.applicationContext, V2RayVpnService::class.java)
        } else {
            Log.i(AppConfig.TAG, "StartCore-Manager: Starting Proxy service")
            Intent(context.applicationContext, V2RayProxyOnlyService::class.java)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to start service", e)
        }
    }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (coreController.isRunning) {
            Log.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return false
        }

        val service = getService()
        if (service == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }

        val guid = MmkvManager.getSelectServer()
        if (guid == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: No server selected")
            return false
        }

        val config = MmkvManager.decodeServerConfig(guid)
        if (config == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to decode server config")
            return false
        }

        Log.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for ${config.remarks}")

        try {
            val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
            mFilter.addAction(Intent.ACTION_SCREEN_ON)
            mFilter.addAction(Intent.ACTION_SCREEN_OFF)
            mFilter.addAction(Intent.ACTION_USER_PRESENT)
            ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to register receiver", e)
            return false
        }

        currentConfig = config

        if (config.configType == EConfigType.OLCRTC) {
            return startOlcrtcLoop(service, config)
        }

        val result = V2rayConfigManager.getV2rayConfig(service, guid)
        if (!result.status) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to get V2Ray config")
            return false
        }

        var tunFd = vpnInterface?.fd ?: 0
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }

        try {
            NotificationManager.showNotification(currentConfig)
            coreController.startLoop(result.content, tunFd)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to start core loop", e)
            return false
        }

        if (coreController.isRunning == false) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Core failed to start")
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            NotificationManager.cancelNotification()
            return false
        }

        try {
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
            NotificationManager.startSpeedNotification(currentConfig)
            Log.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to complete startup", e)
            return false
        }
        return true
    }

    /**
     * Starts olcRTC as an upstream SOCKS5 hop and Xray as the local router.
     * This keeps Android TUN generic while domain split routing stays in Xray.
     */
    private fun startOlcrtcLoop(service: Service, config: ProfileItem): Boolean {
        val guid = MmkvManager.getSelectServer() ?: return false
        val upstreamSocksPort = SettingsManager.getOlcrtcSocksPort()
        val protectSocket: ((Int) -> Boolean)? = serviceControl?.get()?.let { sc ->
            { fd: Int -> sc.vpnProtect(fd) }
        }

        try {
            NotificationManager.showNotification(currentConfig)
            if (!OlcrtcManager.start(config, upstreamSocksPort, protectSocket)) {
                Log.e(AppConfig.TAG, "StartCore-Manager: olcRTC failed to start")
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
                NotificationManager.cancelNotification()
                return false
            }

            val result = V2rayConfigManager.getOlcrtcGatewayConfig(service, guid, upstreamSocksPort)
            if (!result.status) {
                Log.e(AppConfig.TAG, "StartCore-Manager: failed to get olcRTC gateway config")
                OlcrtcManager.stop()
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
                NotificationManager.cancelNotification()
                return false
            }

            coreController.startLoop(result.content, 0)
            if (coreController.isRunning == false) {
                Log.e(AppConfig.TAG, "StartCore-Manager: olcRTC gateway core failed to start")
                OlcrtcManager.stop()
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
                NotificationManager.cancelNotification()
                return false
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: olcRTC exception", e)
            OlcrtcManager.stop()
            return false
        }

        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
        NotificationManager.startSpeedNotification(currentConfig)
        Log.i(AppConfig.TAG, "StartCore-Manager: olcRTC gateway started successfully")
        return true
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    fun stopCoreLoop(): Boolean {
        val service = getService() ?: return false

        // Stop olcRTC if running
        if (OlcrtcManager.isRunning()) {
            CoroutineScope(Dispatchers.IO).launch {
                OlcrtcManager.stop()
            }
        }

        if (coreController.isRunning) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    coreController.stopLoop()
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
                }
            }
        }

        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        NotificationManager.cancelNotification()

        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
        }

        return true
    }

    /**
     * Queries the statistics for a given tag and link.
     * @param tag The tag to query.
     * @param link The link to query.
     * @return The statistics value.
     */
    fun queryStats(tag: String, link: String): Long {
        return coreController.queryStats(tag, link)
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     */
    private fun measureV2rayDelay() {
        val isOlcrtc = OlcrtcManager.isRunning()
        if (!coreController.isRunning && !isOlcrtc) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val service = getService() ?: return@launch
            var time = -1L
            var errorStr = ""

            if (isOlcrtc) {
                time = measureOlcrtcDelay(SettingsManager.getDelayTestUrl())
                if (time == -1L) {
                    time = measureOlcrtcDelay(SettingsManager.getDelayTestUrl(true))
                }
                if (time == -1L) errorStr = "olcRTC: connection test failed"
            } else {
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                    errorStr = e.message?.substringAfter("\":") ?: "empty message"
                }
                if (time == -1L) {
                    try {
                        time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                    } catch (e: Exception) {
                        Log.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                        errorStr = e.message?.substringAfter("\":") ?: "empty message"
                    }
                }
            }

            val result = if (time >= 0) {
                service.getString(R.string.connection_test_available, time)
            } else {
                service.getString(R.string.connection_test_error, errorStr)
            }
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, result)

            // Only fetch IP info if the delay test was successful
            if (time >= 0) {
                SpeedtestManager.getRemoteIPInfo()?.let { ip ->
                    MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, "$result\n$ip")
                }
            }
        }
    }

    /**
     * Measures delay through the local SOCKS5 proxy (used for olcRTC mode).
     * Does the SOCKS5 handshake manually so we can pass the USER/PASS that
     * OlcrtcManager generates — java.net.Proxy can't carry credentials.
     */
    private fun measureOlcrtcDelay(url: String): Long {
        return try {
            val socksPort = SettingsManager.getOlcrtcSocksPort()
            val parsed = java.net.URL(url)
            val host = parsed.host
            val port = if (parsed.port != -1) parsed.port else (if (parsed.protocol == "https") 443 else 80)
            val path = if (parsed.file.isNullOrEmpty()) "/" else parsed.file

            val start = System.currentTimeMillis()
            val sock = java.net.Socket()
            sock.connect(java.net.InetSocketAddress("127.0.0.1", socksPort), 12000)
            sock.soTimeout = 12000
            val out = sock.getOutputStream()
            val `in` = sock.getInputStream()

            // SOCKS5 greeting with USER/PASS method
            out.write(byteArrayOf(0x05, 0x01, 0x02.toByte()))
            val greet = ByteArray(2)
            if (`in`.read(greet) != 2 || greet[0] != 0x05.toByte() || greet[1] != 0x02.toByte()) {
                sock.close(); return -1L
            }

            // RFC 1929 auth
            val user = OlcrtcManager.socksUser.toByteArray()
            val pass = OlcrtcManager.socksPass.toByteArray()
            val auth = java.io.ByteArrayOutputStream().apply {
                write(0x01)
                write(user.size)
                write(user)
                write(pass.size)
                write(pass)
            }.toByteArray()
            out.write(auth)
            val authResp = ByteArray(2)
            if (`in`.read(authResp) != 2 || authResp[1] != 0x00.toByte()) {
                sock.close(); return -1L
            }

            // CONNECT to host:port via domain ATYP
            val hostBytes = host.toByteArray()
            val req = java.io.ByteArrayOutputStream().apply {
                write(0x05); write(0x01); write(0x00); write(0x03)
                write(hostBytes.size)
                write(hostBytes)
                write((port shr 8) and 0xff)
                write(port and 0xff)
            }.toByteArray()
            out.write(req)

            val reply = ByteArray(4)
            if (`in`.read(reply) != 4 || reply[1] != 0x00.toByte()) {
                sock.close(); return -1L
            }
            // Drain bound address
            val skip = when (reply[3].toInt() and 0xff) {
                0x01 -> 4 + 2
                0x03 -> (`in`.read() and 0xff) + 2
                0x04 -> 16 + 2
                else -> { sock.close(); return -1L }
            }
            val bound = ByteArray(skip)
            `in`.read(bound)

            // Wrap TLS if https and send a minimal HTTP HEAD
            val ioSock: java.net.Socket = if (parsed.protocol == "https") {
                val ssl = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
                val tls = ssl.createSocket(sock, host, port, true) as javax.net.ssl.SSLSocket
                tls.startHandshake()
                tls
            } else sock

            val writer = ioSock.getOutputStream()
            val reader = ioSock.getInputStream()
            val httpReq = "HEAD $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: v2rayNG\r\nConnection: close\r\n\r\n"
            writer.write(httpReq.toByteArray())
            writer.flush()

            val headBuf = ByteArray(64)
            val read = reader.read(headBuf)
            ioSock.close()
            if (read <= 0) return -1L
            val statusLine = String(headBuf, 0, read)
            val ok = statusLine.startsWith("HTTP/1.1 2") || statusLine.startsWith("HTTP/1.0 2")
            if (ok) System.currentTimeMillis() - start else -1L
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: SOCKS delay test failed", e)
            -1L
        }
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            val serviceControl = serviceControl?.get() ?: return -1
            return try {
                serviceControl.stopService()
                0
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "StartCore-Manager: Failed to stop service", e)
                -1
            }
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            return 0
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (coreController.isRunning || OlcrtcManager.isRunning()) {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    serviceControl.stopService()
                    Thread.sleep(500L)
                    startVService(serviceControl.getService())
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification(currentConfig)
                }

                Intent.ACTION_SCREEN_ON -> {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    NotificationManager.startSpeedNotification(currentConfig)
                }
            }
        }
    }
}
