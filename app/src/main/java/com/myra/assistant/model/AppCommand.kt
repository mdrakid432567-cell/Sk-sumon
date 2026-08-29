package com.myra.assistant.model

data class AppCommand(
    val type: String,
    val params: Map<String, String> = emptyMap()
) {
    companion object {
        const val OPEN_APP = "OPEN_APP"
        const val CLOSE_APP = "CLOSE_APP"
        const val CALL = "CALL"
        const val SMS = "SMS"
        const val WHATSAPP_MSG = "WHATSAPP_MSG"
        const val WHATSAPP_CALL = "WHATSAPP_CALL"
        const val PRIME_CALL = "PRIME_CALL"
        const val PRIME_MSG = "PRIME_MSG"
        const val VOLUME_UP = "VOLUME_UP"
        const val VOLUME_DOWN = "VOLUME_DOWN"
        const val FLASHLIGHT_ON = "FLASHLIGHT_ON"
        const val FLASHLIGHT_OFF = "FLASHLIGHT_OFF"
        const val WIFI_ON = "WIFI_ON"
        const val WIFI_OFF = "WIFI_OFF"
        const val BLUETOOTH_ON = "BLUETOOTH_ON"
        const val BLUETOOTH_OFF = "BLUETOOTH_OFF"
    }
}
