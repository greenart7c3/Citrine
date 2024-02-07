data class EOSE(val subscriptionId: String) {
    fun toJson(): String {
        return """["EOSE","$subscriptionId"]"""
    }
}