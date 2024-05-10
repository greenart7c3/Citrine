package com.greenart7c3.citrine.server

import com.fasterxml.jackson.databind.ObjectMapper

data class CountResult(val count: Int) {
    fun toJson(): String {
        return ObjectMapper().writeValueAsString(this)
    }
}
