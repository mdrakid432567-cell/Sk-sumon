package com.myra.assistant.ai

import com.myra.assistant.model.AppCommand
import java.util.Locale

object CommandParser {

    fun parse(rawText: String): AppCommand? {
        val text = rawText.lowercase(Locale.getDefault())
            .replace(Regex("[.,!?;:]"), "")
            .trim()

        if (text.isEmpty()) return null

        // 1. PRIME CONTACT COMMANDS
        if (text.contains("close friend ko call") ||
            text.contains("mere close friend ko call") ||
            text.contains("call my close friend") ||
            text.contains("meri jaan ko call") ||
            text.contains("jaan ko call karo") ||
            text.contains("first contact ko call") ||
            text.contains("call my first contact")
        ) {
            return AppCommand(AppCommand.PRIME_CALL, mapOf("index" to "0"))
        }

        if (text.contains("second contact ko call") ||
            text.contains("call my second contact") ||
            text.contains("dusre contact ko call")
        ) {
            return AppCommand(AppCommand.PRIME_CALL, mapOf("index" to "1"))
        }

        if (text.contains("close friend ko message") ||
            text.contains("close friend ko msg") ||
            text.contains("meri jaan ko message") ||
            text.contains("meri jaan ko msg") ||
            text.contains("message my love") ||
            text.contains("message my close friend")
        ) {
            val msgBody = extractMessageBody(text)
            return AppCommand(AppCommand.PRIME_MSG, mapOf("index" to "0", "message" to msgBody))
        }

        // 2. VOLUME CONTROLS
        if (text.contains("volume badhao") ||
            text.contains("volume up") ||
            text.contains("awaaz badhao") ||
            text.contains("awaaz tez karo") ||
            text.contains("sound badhao") ||
            text.contains("increase volume")
        ) {
            return AppCommand(AppCommand.VOLUME_UP)
        }

        if (text.contains("volume kam karo") ||
            text.contains("volume down") ||
            text.contains("awaaz kam karo") ||
            text.contains("awaaz dheemi karo") ||
            text.contains("sound kam karo") ||
            text.contains("decrease volume")
        ) {
            return AppCommand(AppCommand.VOLUME_DOWN)
        }

        // 3. FLASHLIGHT / TORCH
        if (text.contains("torch on") ||
            text.contains("torch chalu") ||
            text.contains("torch jalao") ||
            text.contains("flashlight on") ||
            text.contains("flashlight chalu") ||
            text.contains("turn on flashlight") ||
            text.contains("turn on torch")
        ) {
            return AppCommand(AppCommand.FLASHLIGHT_ON)
        }

        if (text.contains("torch off") ||
            text.contains("torch band") ||
            text.contains("flashlight off") ||
            text.contains("flashlight band") ||
            text.contains("turn off flashlight") ||
            text.contains("turn off torch")
        ) {
            return AppCommand(AppCommand.FLASHLIGHT_OFF)
        }

        // 4. WIFI & BLUETOOTH
        if (text.contains("wifi on") || text.contains("wifi chalu") || text.contains("turn on wifi")) {
            return AppCommand(AppCommand.WIFI_ON)
        }
        if (text.contains("wifi off") || text.contains("wifi band") || text.contains("turn off wifi")) {
            return AppCommand(AppCommand.WIFI_OFF)
        }

        if (text.contains("bluetooth on") || text.contains("bluetooth chalu") || text.contains("turn on bluetooth")) {
            return AppCommand(AppCommand.BLUETOOTH_ON)
        }
        if (text.contains("bluetooth off") || text.contains("bluetooth band") || text.contains("turn off bluetooth")) {
            return AppCommand(AppCommand.BLUETOOTH_OFF)
        }

        // 5. CLOSE APP
        if (text.contains("band karo") ||
            text.contains("close app") ||
            text.contains("close current app") ||
            text.contains("back jao") ||
            text.contains("app close karo")
        ) {
            return AppCommand(AppCommand.CLOSE_APP)
        }

        // 6. OPEN APP
        val openAppRegexes = listOf(
            Regex("(?:open|kholo|chalao|launch|start)\\s+([a-zA-Z0-9\\s]+)"),
            Regex("([a-zA-Z0-9]+)\\s+(?:kholo|chalao|open karo|launch karo)")
        )

        for (regex in openAppRegexes) {
            val match = regex.find(text)
            if (match != null) {
                val appName = match.groupValues[1].trim()
                if (appName.isNotEmpty() && !isNonAppWord(appName)) {
                    return AppCommand(AppCommand.OPEN_APP, mapOf("app_name" to appName))
                }
            }
        }

        // 7. CALL CONTACT
        val callRegexes = listOf(
            Regex("(?:call|phone)\\s+(?:karo\\s+)?([a-zA-Z0-9\\s]+?)(?:\\s+ko)?(?:\\s+call\\s+karo|\\s+phone\\s+lagao)?$"),
            Regex("([a-zA-Z0-9\\s]+?)\\s+ko\\s+(?:call\\s+karo|phone\\s+lagao|call\\s+laga)")
        )

        if (text.contains("call") || text.contains("phone lagao") || text.contains("dial")) {
            for (regex in callRegexes) {
                val match = regex.find(text)
                if (match != null) {
                    val contactTarget = match.groupValues[1]
                        .replace("ko", "")
                        .replace("call", "")
                        .replace("phone", "")
                        .trim()
                    if (contactTarget.isNotEmpty() && !isNonContactWord(contactTarget)) {
                        return AppCommand(AppCommand.CALL, mapOf("target" to contactTarget))
                    }
                }
            }
        }

        // 8. WHATSAPP & SMS
        if (text.contains("whatsapp")) {
            val target = extractTargetName(text, "whatsapp")
            val msg = extractMessageBody(text)
            return AppCommand(AppCommand.WHATSAPP_MSG, mapOf("target" to target, "message" to msg))
        }

        if (text.contains("sms") || text.contains("message bhejo") || text.contains("msg bhejo")) {
            val target = extractTargetName(text, "sms")
            val msg = extractMessageBody(text)
            return AppCommand(AppCommand.SMS, mapOf("target" to target, "message" to msg))
        }

        return null
    }

    private fun extractTargetName(text: String, keyword: String): String {
        val parts = text.split(" ")
        val index = parts.indexOf(keyword)
        if (index != -1 && index + 1 < parts.size) {
            val candidate = parts[index + 1].replace("ko", "").trim()
            if (candidate.isNotEmpty()) return candidate
        }
        return ""
    }

    private fun extractMessageBody(text: String): String {
        val keywords = listOf("bolo", "likho", "message", "msg", "that", "saying")
        for (kw in keywords) {
            val idx = text.indexOf(kw)
            if (idx != -1 && idx + kw.length < text.length) {
                return text.substring(idx + kw.length).trim()
            }
        }
        return "Hey, MYRA sent this message."
    }

    private fun isNonAppWord(word: String): Boolean {
        val nonApps = listOf("door", "window", "gate", "close", "on", "off", "wifi", "bluetooth", "torch", "volume", "call")
        return nonApps.contains(word.lowercase())
    }

    private fun isNonContactWord(word: String): Boolean {
        val nonContacts = listOf("me", "us", "him", "her", "karo", "please", "now", "abhi")
        return nonContacts.contains(word.lowercase())
    }
}
