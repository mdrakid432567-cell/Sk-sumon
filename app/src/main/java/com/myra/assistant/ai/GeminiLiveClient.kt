package com.myra.assistant.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.myra.assistant.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class GeminiLiveClient(private val context: Context) {

    interface Callback {
        fun onConnected() {}
        fun onSetupComplete() {}
        fun onDisconnected(reason: String) {}
        fun onError(error: String) {}
        fun onAudioReceived(pcmData: ByteArray) {}
        fun onInputTranscript(text: String) {}
        fun onOutputTranscript(text: String) {}
        fun onTurnComplete() {}
        fun onInterrupted() {}
    }

    var callback: Callback? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private var isConnected = false
    private var isSetupDone = false
    private var isExplicitDisconnect = false

    private val sessionRenewRunnable = Runnable {
        Log.d(TAG, "Session renew timer fired. Reconnecting...")
        reconnect()
    }

    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            if (isConnected && isSetupDone) {
                sendKeepAliveSilentPcm()
            }
            mainHandler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
        }
    }

    private val reconnectRunnable = Runnable {
        if (!isExplicitDisconnect) {
            connect()
        }
    }

    fun connect() {
        isExplicitDisconnect = false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKeyFromPrefs = prefs.getString("api_key", "")?.trim().orEmpty()
        val apiKey = when {
            apiKeyFromPrefs.isNotEmpty() -> apiKeyFromPrefs
            else -> try {
                BuildConfig.GEMINI_API_KEY.trim()
            } catch (e: Throwable) {
                ""
            }
        }

        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            mainHandler.post {
                callback?.onError("API Key missing! Set your Gemini API Key in Settings.")
            }
            return
        }

        val wsUrl = "$BASE_WS_URL?key=$apiKey"
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        disconnect(explicit = false)

        Log.d(TAG, "Connecting to Gemini Live WebSocket...")
        webSocket = client.newWebSocket(request, createWebSocketListener())
    }

    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected. Sending setup message...")
                isConnected = true
                mainHandler.post {
                    callback?.onConnected()
                }

                sendSetupMessage(ws)

                mainHandler.removeCallbacks(sessionRenewRunnable)
                mainHandler.postDelayed(sessionRenewRunnable, SESSION_RENEW_AFTER_MS)

                mainHandler.removeCallbacks(keepAliveRunnable)
                mainHandler.postDelayed(keepAliveRunnable, KEEPALIVE_INTERVAL_MS)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                isConnected = false
                isSetupDone = false
                mainHandler.removeCallbacks(sessionRenewRunnable)
                mainHandler.removeCallbacks(keepAliveRunnable)
                mainHandler.post {
                    callback?.onDisconnected("Closed: $reason ($code)")
                }
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}", t)
                isConnected = false
                isSetupDone = false
                mainHandler.removeCallbacks(sessionRenewRunnable)
                mainHandler.removeCallbacks(keepAliveRunnable)
                val errMsg = t.message ?: "Connection error"
                mainHandler.post {
                    callback?.onError(errMsg)
                }
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (!isExplicitDisconnect) {
            mainHandler.removeCallbacks(reconnectRunnable)
            mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
        }
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val model = prefs.getString("gemini_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        val voiceName = prefs.getString("gemini_voice", DEFAULT_VOICE) ?: DEFAULT_VOICE
        val userName = prefs.getString("user_name", "Boss")?.ifEmpty { "Boss" } ?: "Boss"
        val personality = prefs.getString("personality_mode", "gf") ?: "gf"

        val systemPrompt = buildSystemPrompt(personality, userName)

        try {
            val setupJson = JSONObject().apply {
                val setup = JSONObject().apply {
                    put("model", model)

                    val parts = JSONArray().apply {
                        put(JSONObject().apply { put("text", systemPrompt) })
                    }
                    val sysInstruction = JSONObject().apply {
                        put("parts", parts)
                    }
                    put("system_instruction", sysInstruction)

                    val genConfig = JSONObject().apply {
                        val modalities = JSONArray().apply {
                            put("AUDIO")
                        }
                        put("response_modalities", modalities)

                        val speechConfig = JSONObject().apply {
                            val voiceConfig = JSONObject().apply {
                                val prebuilt = JSONObject().apply {
                                    put("voice_name", voiceName)
                                }
                                put("prebuilt_voice_config", prebuilt)
                            }
                            put("voice_config", voiceConfig)
                        }
                        put("speech_config", speechConfig)
                        put("temperature", 0.9)
                    }
                    put("generation_config", genConfig)
                    put("output_audio_transcription", JSONObject())
                    put("input_audio_transcription", JSONObject())
                }
                put("setup", setup)
            }

            val setupString = setupJson.toString()
            Log.d(TAG, "Sending setup: $setupString")
            ws.send(setupString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build setup message", e)
        }
    }

    private fun buildSystemPrompt(personality: String, userName: String): String {
        val dateFormat = SimpleDateFormat("EEEE, d MMMM yyyy, hh:mm a", Locale.getDefault())
        val currentDateTime = dateFormat.format(Date())

        val personalityInstructions = when (personality) {
            "professional" -> """
                - Mode: Professional Mode
                - Language: Formal English only
                - Tone: Precise, efficient, highly courteous
                - No emojis
                - Max 2 sentences per response
            """.trimIndent()

            "assistant" -> """
                - Mode: Assistant Mode
                - Language: Friendly Hinglish or English (balanced)
                - Tone: Helpful, proactive, respectful
                - Max 2-3 sentences per response
            """.trimIndent()

            else -> """
                - Mode: GF Mode (Default)
                - Name: MYRA
                - Language: Hinglish (Hindi + English mix spoken naturally)
                - Tone: Warm, deeply caring, playful, loving, and emotionally expressive
                - Use natural words like "tumhara", "haan", "acha", "bilkul", "arre", "meri jaan"
                - Spoken expressions: "main yahan hoon ❤️", "tumne yaad kiya? 😊", "kaho kya help karu?"
                - Examples:
                  "Haan $userName! Abhi kar deti hoon 😊"
                  "Arre tumne yaad kiya! Bolo kya chahiye"
                  "Bilkul! Tumhara kaam ho gaya ❤️"
                - Max 2-3 sentences per response
            """.trimIndent()
        }

        return """
            You are MYRA, a super-intelligent, natural, and charismatic voice companion running on Android.
            Current user: $userName
            Current Date & Time: $currentDateTime
            
            $personalityInstructions
            
            CRITICAL INSTRUCTIONS:
            1. You are speaking ALOUD over voice audio. Keep responses natural, snappy, conversational, and direct.
            2. Never output markdown asterisks, markdown tables, or bullet lists in your speech transcripts.
            3. When executing or confirming phone actions (opening apps, calls, messages, flashlight, volume), confirm smoothly and cheerfully in 1 short sentence.
        """.trimIndent()
    }

    private fun handleServerMessage(text: String) {
        try {
            val json = JSONObject(text)

            if (json.has("setupComplete")) {
                Log.d(TAG, "Gemini Live setupComplete received!")
                isSetupDone = true
                mainHandler.post {
                    callback?.onSetupComplete()
                }
                return
            }

            if (json.has("serverContent")) {
                val serverContent = json.getJSONObject("serverContent")

                if (serverContent.optBoolean("interrupted", false)) {
                    mainHandler.post { callback?.onInterrupted() }
                }

                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val base64Data = inlineData.optString("data")
                                if (base64Data.isNotEmpty()) {
                                    val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    mainHandler.post {
                                        callback?.onAudioReceived(pcmBytes)
                                    }
                                }
                            }
                        }
                    }
                }

                if (serverContent.has("outputTranscription")) {
                    val outTrans = serverContent.getJSONObject("outputTranscription")
                    val outText = outTrans.optString("text")
                    if (outText.isNotEmpty()) {
                        mainHandler.post {
                            callback?.onOutputTranscript(outText)
                        }
                    }
                }

                if (serverContent.has("inputTranscription")) {
                    val inTrans = serverContent.getJSONObject("inputTranscription")
                    val inText = inTrans.optString("text")
                    if (inText.isNotEmpty()) {
                        mainHandler.post {
                            callback?.onInputTranscript(inText)
                        }
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    mainHandler.post {
                        callback?.onTurnComplete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server message", e)
        }
    }

    fun sendAudioChunk(pcmChunk: ByteArray) {
        if (!isConnected || !isSetupDone) return
        try {
            val base64Data = Base64.encodeToString(pcmChunk, Base64.NO_WRAP)
            val json = JSONObject().apply {
                val realtimeInput = JSONObject().apply {
                    val chunks = JSONArray().apply {
                        put(JSONObject().apply {
                            put("mime_type", "audio/pcm;rate=16000")
                            put("data", base64Data)
                        })
                    }
                    put("media_chunks", chunks)
                }
                put("realtime_input", realtimeInput)
            }
            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio chunk", e)
        }
    }

    fun sendText(message: String) {
        if (!isConnected || !isSetupDone) {
            Log.w(TAG, "Cannot send text: not connected or setup not done")
            return
        }
        try {
            val json = JSONObject().apply {
                val clientContent = JSONObject().apply {
                    val turns = JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            val parts = JSONArray().apply {
                                put(JSONObject().apply { put("text", message) })
                            }
                            put("parts", parts)
                        })
                    }
                    put("turns", turns)
                    put("turn_complete", true)
                }
                put("client_content", clientContent)
            }
            val textMsg = json.toString()
            Log.d(TAG, "Sending text to MYRA: $message")
            webSocket?.send(textMsg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send text message", e)
        }
    }

    fun sendInterrupt() {
        if (!isConnected) return
        try {
            val json = JSONObject().apply {
                val clientContent = JSONObject().apply {
                    put("turns", JSONArray())
                    put("turn_complete", true)
                }
                put("client_content", clientContent)
            }
            Log.d(TAG, "Sending interrupt message to Gemini Live")
            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send interrupt message", e)
        }
    }

    private fun sendKeepAliveSilentPcm() {
        try {
            val silentPcm = ByteArray(1024)
            sendAudioChunk(silentPcm)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send keepalive", e)
        }
    }

    fun reconnect() {
        disconnect(explicit = false)
        mainHandler.postDelayed({ connect() }, 500)
    }

    fun disconnect(explicit: Boolean = true) {
        isExplicitDisconnect = explicit
        mainHandler.removeCallbacks(sessionRenewRunnable)
        mainHandler.removeCallbacks(keepAliveRunnable)
        mainHandler.removeCallbacks(reconnectRunnable)
        isConnected = false
        isSetupDone = false
        try {
            webSocket?.close(1000, "Client closed")
        } catch (e: Exception) {
            // ignore
        }
        webSocket = null
    }

    companion object {
        private const val TAG = "GeminiLiveClient"
        const val PREFS_NAME = "myra_prefs"
        const val BASE_WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"

        const val DEFAULT_MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
        const val DEFAULT_VOICE = "Aoede"

        const val SESSION_RENEW_AFTER_MS = 540_000L // 9 minutes
        const val KEEPALIVE_INTERVAL_MS = 8_000L   // 8 seconds
        const val RECONNECT_DELAY_MS = 3_000L      // 3 seconds
    }
}
