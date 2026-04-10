package com.v2ray.ang.enums

enum class Language(val code: String) {
    AUTO("auto"),
    ENGLISH("en"),
    VIETNAMESE("vi"),
    RUSSIAN("ru"),
    PERSIAN("fa"),
    ARABIC("ar"),
    BANGLA("bn"),
    BAKHTIARI("bqi-rIR");

    companion object {
        fun fromCode(code: String): Language {
            return entries.find { it.code == code } ?: AUTO
        }
    }
}
