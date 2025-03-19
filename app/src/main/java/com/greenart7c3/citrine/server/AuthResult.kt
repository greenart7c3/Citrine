package com.greenart7c3.citrine.server

data class AuthResult(val challenge: String) {
    fun toJson(): String {
        return """["AUTH","$challenge"]"""
    }

    companion object {
        fun challenge(challenge: String) = AuthResult(challenge)
    }
}
