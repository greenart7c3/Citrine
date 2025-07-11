package com.greenart7c3.citrine.server

data class NoticeResult(val message: String) {
    fun toJson(): String = """["NOTICE","$message"]"""

    companion object {
        fun invalid(message: String) = NoticeResult("invalid: $message")
    }
}
