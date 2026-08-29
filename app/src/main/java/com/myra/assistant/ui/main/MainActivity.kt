package com.myra.assistant.ui.main

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.R
import com.myra.assistant.service.CallMonitorService
import com.myra.assistant.ui.settings.SettingsActivity
import com.myra.assistant.viewmodel.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var redOverlay: View
    private lateinit var batteryText: TextView
    private lateinit var ramText: TextView
    private lateinit var timeText: TextView
    private lateinit var settingsBtn: ImageButton
    private lateinit var orbView: OrbAnimationView
    private lateinit var waveformView: WaveformView
    private lateinit var statusText: TextView
    private lateinit var chatRecycler: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var textInput: EditText
    private lateinit var sendBtn: ImageButton
    private lateinit var micButton: ImageButton
    private lateinit var hintText: TextView

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

    private val permissionsToRequest = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val micGranted = results[Manifest.permission.RECORD_AUDIO] == true
            if (micGranted) {
                viewModel.startSession()
            }
            CallMonitorService.start(this)
        }

    private val systemInfoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> updateBatteryInfo(intent)
                Intent.ACTION_TIME_TICK -> updateTimeAndRam()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupChatRecycler()
        observeViewModel()
        setupListeners()
        checkAndRequestPermissions()
        handleIncomingIntent(intent)

        updateTimeAndRam()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
        }
        registerReceiver(systemInfoReceiver, filter)
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(systemInfoReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun initViews() {
        redOverlay = findViewById(R.id.redOverlay)
        batteryText = findViewById(R.id.batteryText)
        ramText = findViewById(R.id.ramText)
        timeText = findViewById(R.id.timeText)
        settingsBtn = findViewById(R.id.settingsBtn)
        orbView = findViewById(R.id.orbView)
        waveformView = findViewById(R.id.waveformView)
        statusText = findViewById(R.id.statusText)
        chatRecycler = findViewById(R.id.chatRecycler)
        textInput = findViewById(R.id.textInput)
        sendBtn = findViewById(R.id.sendBtn)
        micButton = findViewById(R.id.micButton)
        hintText = findViewById(R.id.hintText)
    }

    private fun setupChatRecycler() {
        chatAdapter = ChatAdapter()
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        chatRecycler.layoutManager = layoutManager
        chatRecycler.adapter = chatAdapter
    }

    private fun setupListeners() {
        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Tap Mic Button -> Toggle Mute / Start Session
        micButton.setOnClickListener {
            viewModel.toggleMute()
        }

        // Long Press Mic Button -> Interrupt MYRA
        micButton.setOnLongClickListener {
            viewModel.interrupt()
            true
        }

        // Send Text
        sendBtn.setOnClickListener {
            sendTypedMessage()
        }

        textInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTypedMessage()
                true
            } else {
                false
            }
        }
    }

    private fun sendTypedMessage() {
        val text = textInput.text.toString().trim()
        if (text.isNotEmpty()) {
            viewModel.sendTextMessage(text)
            textInput.setText("")
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.orbState.collectLatest { state ->
                        orbView.setState(state)
                        animateScreenTint(state)
                    }
                }

                launch {
                    viewModel.amplitude.collectLatest { amp ->
                        orbView.setAmplitude(amp)
                        waveformView.setAmplitude(amp)
                    }
                }

                launch {
                    viewModel.statusText.collectLatest { text ->
                        statusText.text = text
                    }
                }

                launch {
                    viewModel.isMuted.collectLatest { muted ->
                        if (muted) {
                            micButton.setImageResource(R.drawable.ic_mic_off)
                        } else {
                            micButton.setImageResource(R.drawable.ic_mic_on)
                        }
                    }
                }

                launch {
                    viewModel.toastEvent.collect { msg ->
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }

                launch {
                    viewModel.newChatMessage.collect { chatMsg ->
                        chatAdapter.addMessage(chatMsg)
                        chatRecycler.smoothScrollToPosition(chatAdapter.itemCount - 1)
                    }
                }
            }
        }
    }

    private fun animateScreenTint(state: OrbAnimationView.OrbState) {
        val targetAlpha = when (state) {
            OrbAnimationView.OrbState.SPEAKING -> 0.12f
            OrbAnimationView.OrbState.LISTENING, OrbAnimationView.OrbState.ACTIVE -> 0.06f
            OrbAnimationView.OrbState.THINKING -> 0.04f
            OrbAnimationView.OrbState.IDLE -> 0.0f
        }
        redOverlay.animate().alpha(targetAlpha).setDuration(400).start()
    }

    private fun checkAndRequestPermissions() {
        val missing = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        } else {
            CallMonitorService.start(this)
            viewModel.startSession()
        }

        if (!Settings.canDrawOverlays(this)) {
            // Suggest overlay permission
            try {
                val overlayIntent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(overlayIntent)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val isCall = intent.getBooleanExtra(CallMonitorService.EXTRA_INCOMING_CALL, false)
        if (isCall) {
            val callerName = intent.getStringExtra(CallMonitorService.EXTRA_CALLER_NAME) ?: "Unknown Caller"
            val callerNumber = intent.getStringExtra(CallMonitorService.EXTRA_CALLER_NUMBER) ?: ""
            viewModel.handleIncomingCall(callerName, callerNumber)
        }
    }

    private fun updateBatteryInfo(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
            val batteryPct = (level * 100) / scale
            batteryText.text = "$batteryPct% BAT"
        }
    }

    private fun updateTimeAndRam() {
        timeText.text = timeFormat.format(Date()).uppercase()

        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager != null) {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val usedGb = (memInfo.totalMem - memInfo.availMem).toDouble() / (1024.0 * 1024.0 * 1024.0)
            ramText.text = String.format(Locale.US, "%.1fGB RAM", usedGb)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.disconnect()
    }
}
