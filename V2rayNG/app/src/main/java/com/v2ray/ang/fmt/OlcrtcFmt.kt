package com.v2ray.ang.fmt

import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils
import java.net.URI

object OlcrtcFmt {
    /**
     * Parses an olcRTC URI string into a ProfileItem.
     * Format: olcrtc://ROOM_ID?key=HEX_KEY&duo=true#remarks
     *
     * Fields mapping:
     * - path → roomID (stored in ProfileItem.server)
     * - password → encryption key (hex)
     * - headerType → "duo" if dual channel mode
     */
    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.OLCRTC)

        val uri = URI(Utils.fixIllegalUrl(str))
        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).ifEmpty { "olcRTC" }

        // Room ID is the host part
        config.server = uri.host ?: return null
        config.serverPort = "0" // not used

        if (!uri.rawQuery.isNullOrEmpty()) {
            val queryParam = uri.rawQuery.split("&")
                .associate {
                    val parts = it.split("=", limit = 2)
                    parts[0] to Utils.decodeURIComponent(parts.getOrElse(1) { "" })
                }

            config.password = queryParam["key"]
            config.headerType = if (queryParam["duo"] == "true") "duo" else ""
        }

        return config
    }

    /**
     * Converts a ProfileItem to an olcRTC URI string.
     * Format: olcrtc://ROOM_ID?key=HEX_KEY&duo=true#remarks
     */
    fun toUri(config: ProfileItem): String {
        val sb = StringBuilder()
        sb.append(config.server ?: "")

        val params = mutableListOf<String>()
        config.password?.let { params.add("key=${Utils.encodeURIComponent(it)}") }
        if (config.headerType == "duo") {
            params.add("duo=true")
        }
        if (params.isNotEmpty()) {
            sb.append("?")
            sb.append(params.joinToString("&"))
        }

        sb.append("#")
        sb.append(Utils.encodeURIComponent(config.remarks))

        return sb.toString()
    }
}
