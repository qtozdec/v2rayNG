package com.v2ray.ang.enums

enum class RoutingType(val fileName: String) {
    WHITE_RUSSIA("custom_routing_white_russia"),
    GLOBAL("custom_routing_global");

    companion object {
        fun fromIndex(index: Int): RoutingType {
            return when (index) {
                0 -> WHITE_RUSSIA
                1 -> GLOBAL
                else -> WHITE_RUSSIA
            }
        }
    }
}
