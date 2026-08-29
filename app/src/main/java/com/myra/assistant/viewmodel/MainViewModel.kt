package com.myra.assistant.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.CommandParser
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.model.AppCommand
import com.myra.assistant.model.PrimeContact
import com.myra.assistant.service.AccessibilityHelperService
import com.myra.assistant.ui.main.ChatMessage
import com.myra.assistant.ui.main.OrbAnimationView
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    private val context: Context get() = getApplication()

    private val geminiClient = GeminiLiveClient(context)
    private val audioEngine = AudioEngine(context)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _orbState = MutableStateFlow(OrbAnimationView.OrbState.IDLE)
    val orbState: StateFlow<OrbAnimationView.OrbState> = _orbState.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _statusText = MutableStateFlow("Tap karke bolo 💬")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    private val _newChatMessage = MutableSharedFlow<ChatMessage>()
    val newChatMessage: SharedFlow<ChatMessage> = _newChatMessage.asSharedFlow()

    private var isTorchOn = false
    private var isConnectedOnce = false

    init {
        setupAudioEngine()
        setupGeminiClient()
    }

    private fun setupAudioEngine() {
        audioEngine.callback = object : AudioEngine.Callback {
            override fun onMicAudioChunk(chunk: ByteArray) {
                geminiClient.sendAudioChunk(chunk)
            }

            override fun onAmplitudeChanged(rmsNormalized: Float) {
                _amplitude.value = rmsNormalized
            }

            override fun onSpeakingStarted() {
                _orbState.value = OrbAnimationView.OrbState.SPEAKING
                _statusText.value = "MYRA is speaking... ❤️"
            }

            override fun onSpeakingStopped() {
                _orbState.value = OrbAnimationView.OrbState.LISTENING
                _statusText.value = "Listening to you... 💬"
            }

            override fun onError(message: String) {
                Log.e(TAG, "AudioEngine error: $message")
            }
        }
    }

    private fun setupGeminiClient() {
        geminiClient.callback = object : GeminiLiveClient.Callback {
            override fun onConnected() {
                _connectionState.value = ConnectionState.CONNECTING
                _statusText.value = "Initializing MYRA neural link..."
            }

            override fun onSetupComplete() {
                _connectionState.value = ConnectionState.CONNECTED
                _orbState.value = OrbAnimationView.OrbState.LISTENING
                _statusText.value = "Listening to you... 💬"
                isConnectedOnce = true

                // Start recording and playback
                audioEngine.startRecording()
                audioEngine.startPlayback()

                // Welcome message if first connect
                val prefs = context.getSharedPreferences(GeminiLiveClient.PREFS_NAME, Context.MODE_PRIVATE)
                val userName = prefs.getString("user_name", "Boss")?.ifEmpty { "Boss" } ?: "Boss"
                postMyraMessage("MYRA is active. Main yahan hoon $userName!")
            }

            override fun onDisconnected(reason: String) {
                _connectionState.value = ConnectionState.DISCONNECTED
                _orbState.value = OrbAnimationView.OrbState.IDLE
                _statusText.value = "Tap to reconnect 💬"
                audioEngine.stopRecording()
                audioEngine.stopPlayback()
            }

            override fun onError(error: String) {
                _connectionState.value = ConnectionState.ERROR
                _orbState.value = OrbAnimationView.OrbState.IDLE
                _statusText.value = "Connection issue. Tap mic to retry."
                viewModelScope.launch {
                    _toastEvent.emit(error)
                }
            }

            override fun onAudioReceived(pcmData: ByteArray) {
                audioEngine.queueAudio(pcmData)
            }

            override fun onInputTranscript(text: String) {
                postUserMessage(text)

                // Intercept commands from user voice transcript
                val command = CommandParser.parse(text)
                if (command != null) {
                    executeCommand(command)
                }
            }

            override fun onOutputTranscript(text: String) {
                postMyraMessage(text)
            }

            override fun onTurnComplete() {
                // Turn ended
            }

            override fun onInterrupted() {
                audioEngine.clearPlaybackQueue()
                _orbState.value = OrbAnimationView.OrbState.LISTENING
                _statusText.value = "Listening..."
            }
        }
    }

    fun startSession() {
        _statusText.value = "Connecting to MYRA..."
        _connectionState.value = ConnectionState.CONNECTING
        _orbState.value = OrbAnimationView.OrbState.THINKING
        geminiClient.connect()
    }

    fun disconnect() {
        audioEngine.stopRecording()
        audioEngine.stopPlayback()
        geminiClient.disconnect()
        _orbState.value = OrbAnimationView.OrbState.IDLE
        _connectionState.value = ConnectionState.DISCONNECTED
        _statusText.value = "Tap karke bolo 💬"
    }

    fun toggleMute() {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            startSession()
            return
        }

        val nextMuted = !_isMuted.value
        _isMuted.value = nextMuted
        audioEngine.setMuted(nextMuted)

        if (nextMuted) {
            _statusText.value = "Microphone Muted 🔇"
            _orbState.value = OrbAnimationView.OrbState.IDLE
        } else {
            _statusText.value = "Listening to you... 💬"
            _orbState.value = OrbAnimationView.OrbState.LISTENING
        }
    }

    fun interrupt() {
        audioEngine.clearPlaybackQueue()
        geminiClient.sendInterrupt()
        _orbState.value = OrbAnimationView.OrbState.LISTENING
        _statusText.value = "Listening..."
        viewModelScope.launch {
            _toastEvent.emit("Interrupted MYRA")
        }
    }

    fun sendTextMessage(text: String) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return

        postUserMessage(cleanText)

        val cmd = CommandParser.parse(cleanText)
        if (cmd != null) {
            executeCommand(cmd)
        }

        if (_connectionState.value == ConnectionState.CONNECTED) {
            _orbState.value = OrbAnimationView.OrbState.THINKING
            _statusText.value = "MYRA is thinking... ✨"
            geminiClient.sendText(cleanText)
        } else {
            startSession()
            viewModelScope.launch {
                _toastEvent.emit("Connecting to MYRA...")
            }
        }
    }

    fun handleIncomingCall(callerName: String, callerNumber: String) {
        val announcement = "$callerName is calling you right now. Phone number is $callerNumber."
        postMyraMessage("Incoming call from: $callerName")
        if (_connectionState.value == ConnectionState.CONNECTED) {
            geminiClient.sendText("Incoming phone call alert: $announcement. Announce this to the user naturally.")
        }
    }

    private fun postUserMessage(text: String) {
        viewModelScope.launch {
            _newChatMessage.emit(ChatMessage(text = text, isUser = true))
        }
    }

    private fun postMyraMessage(text: String) {
        viewModelScope.launch {
            _newChatMessage.emit(ChatMessage(text = text, isUser = false))
        }
    }

    fun executeCommand(command: AppCommand) {
        Log.d(TAG, "Executing command: ${command.type} with params ${command.params}")
        when (command.type) {
            AppCommand.OPEN_APP -> {
                val appName = command.params["app_name"] ?: ""
                openApp(appName)
            }
            AppCommand.CLOSE_APP -> {
                closeApp()
            }
            AppCommand.CALL -> {
                val target = command.params["target"] ?: ""
                makeCall(target)
            }
            AppCommand.SMS -> {
                val target = command.params["target"] ?: ""
                val msg = command.params["message"] ?: ""
                sendSms(target, msg)
            }
            AppCommand.WHATSAPP_MSG -> {
                val target = command.params["target"] ?: ""
                val msg = command.params["message"] ?: ""
                sendWhatsApp(target, msg)
            }
            AppCommand.PRIME_CALL -> {
                val idx = command.params["index"]?.toIntOrNull() ?: 0
                callPrimeContact(idx)
            }
            AppCommand.PRIME_MSG -> {
                val idx = command.params["index"]?.toIntOrNull() ?: 0
                val msg = command.params["message"] ?: "Hey, MYRA sent this message."
                messagePrimeContact(idx, msg)
            }
            AppCommand.VOLUME_UP -> changeVolume(increase = true)
            AppCommand.VOLUME_DOWN -> changeVolume(increase = false)
            AppCommand.FLASHLIGHT_ON -> toggleTorch(true)
            AppCommand.FLASHLIGHT_OFF -> toggleTorch(false)
            AppCommand.WIFI_ON, AppCommand.WIFI_OFF -> openSettingsScreen(Settings.ACTION_WIFI_SETTINGS, "WiFi Settings")
            AppCommand.BLUETOOTH_ON, AppCommand.BLUETOOTH_OFF -> openSettingsScreen(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth Settings")
        }
    }

    private fun openApp(query: String) {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(0)
        val cleanQuery = query.lowercase(Locale.getDefault())

        var targetPackage: String? = null

        // Known mapping
        val map = mapOf(
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "camera" to "com.google.android.GoogleCamera",
            "chrome" to "com.android.chrome",
            "spotify" to "com.spotify.music",
            "settings" to "com.android.settings",
            "maps" to "com.google.android.apps.maps"
        )

        for ((k, v) in map) {
            if (cleanQuery.contains(k)) {
                targetPackage = v
                break
            }
        }

        if (targetPackage == null) {
            for (app in packages) {
                val label = pm.getApplicationLabel(app).toString().lowercase(Locale.getDefault())
                if (label.contains(cleanQuery) || cleanQuery.contains(label)) {
                    targetPackage = app.packageName
                    break
                }
            }
        }

        if (targetPackage != null) {
            val intent = pm.getLaunchIntentForPackage(targetPackage)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                emitToast("Opening $query...")
                return
            }
        }
        emitToast("Could not find app '$query'")
    }

    private fun closeApp() {
        val service = AccessibilityHelperService.instance
        if (service != null) {
            service.closeCurrentApp()
            emitToast("Closing app...")
        } else {
            emitToast("Accessibility service needed to close apps")
        }
    }

    private fun makeCall(target: String) {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$target")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$target")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
        }
    }

    private fun sendSms(target: String, message: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$target")
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            emitToast("Failed to open SMS")
        }
    }

    private fun sendWhatsApp(target: String, message: String) {
        val cleanNumber = target.replace(Regex("[^0-9]"), "")
        val uri = if (cleanNumber.isNotEmpty()) {
            Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(message)}")
        } else {
            Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(message)}")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            emitToast("WhatsApp not installed")
        }
    }

    private fun callPrimeContact(index: Int) {
        val contacts = getPrimeContacts()
        if (contacts.isNotEmpty()) {
            val c = contacts.getOrElse(index) { contacts[0] }
            makeCall(c.number)
            emitToast("Calling ${c.name}...")
        } else {
            emitToast("No Prime Contacts found in Settings")
        }
    }

    private fun messagePrimeContact(index: Int, msg: String) {
        val contacts = getPrimeContacts()
        if (contacts.isNotEmpty()) {
            val c = contacts.getOrElse(index) { contacts[0] }
            sendWhatsApp(c.number, msg)
            emitToast("Messaging ${c.name}...")
        } else {
            emitToast("No Prime Contacts found in Settings")
        }
    }

    private fun getPrimeContacts(): List<PrimeContact> {
        val prefs = context.getSharedPreferences(GeminiLiveClient.PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString("prime_contacts", "[]") ?: "[]"
        val list = mutableListOf<PrimeContact>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(PrimeContact(obj.optString("name"), obj.optString("number")))
            }
        } catch (e: Exception) {
            // ignore
        }
        return list
    }

    private fun changeVolume(increase: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager?.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
        emitToast(if (increase) "Volume increased 🔊" else "Volume decreased 🔉")
    }

    private fun toggleTorch(enable: Boolean) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        try {
            val cameraId = cameraManager?.cameraIdList?.firstOrNull()
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, enable)
                isTorchOn = enable
                emitToast(if (enable) "Torch Turned ON 🔦" else "Torch Turned OFF")
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error toggling torch", e)
        } catch (e: Exception) {
            Log.e(TAG, "Torch not supported", e)
        }
    }

    private fun openSettingsScreen(action: String, name: String) {
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            emitToast("Opening $name...")
        } catch (e: Exception) {
            emitToast("Could not open $name")
        }
    }

    private fun emitToast(msg: String) {
        viewModelScope.launch {
            _toastEvent.emit(msg)
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioEngine.release()
        geminiClient.disconnect()
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}
