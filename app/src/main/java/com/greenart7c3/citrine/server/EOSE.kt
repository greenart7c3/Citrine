package com.greenart7c3.citrine.server

data class EOSE(val subscriptionId: String) {
    fun toJson(): String {
        return """["EOSE","$subscriptionId"]"""
    }
}
