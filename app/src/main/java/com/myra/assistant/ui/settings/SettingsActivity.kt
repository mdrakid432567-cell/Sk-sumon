package com.myra.assistant.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.BuildConfig
import com.myra.assistant.R
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.model.PrimeContact
import com.myra.assistant.service.AccessibilityHelperService
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    private lateinit var apiKeyInput: EditText
    private lateinit var userNameInput: EditText
    private lateinit var modelSpinner: Spinner
    private lateinit var voiceSpinner: Spinner
    private lateinit var personalityRadioGroup: RadioGroup
    private lateinit var radioGf: RadioButton
    private lateinit var radioProfessional: RadioButton
    private lateinit var radioAssistant: RadioButton
    private lateinit var accessibilityStatusText: TextView
    private lateinit var primeContactsRecycler: RecyclerView
    private lateinit var primeContactAdapter: PrimeContactAdapter

    private val modelOptions = listOf(
        Pair("Native Audio (Human Voice) — DEFAULT", "models/gemini-2.5-flash-native-audio-preview-12-2025"),
        Pair("Flash Live (Fast)", "models/gemini-2.0-flash-live-001"),
        Pair("Pro Audio Dialog", "models/gemini-2.5-flash-preview-native-audio-dialog")
    )

    private val voiceOptions = listOf(
        "Aoede", "Charon", "Kore", "Fenrir", "Puck", "Leda", "Orus", "Zephyr"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(GeminiLiveClient.PREFS_NAME, Context.MODE_PRIVATE)

        initViews()
        loadPreferences()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    private fun initViews() {
        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }

        apiKeyInput = findViewById(R.id.apiKeyInput)
        userNameInput = findViewById(R.id.userNameInput)
        modelSpinner = findViewById(R.id.modelSpinner)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        personalityRadioGroup = findViewById(R.id.personalityRadioGroup)
        radioGf = findViewById(R.id.radioGf)
        radioProfessional = findViewById(R.id.radioProfessional)
        radioAssistant = findViewById(R.id.radioAssistant)
        accessibilityStatusText = findViewById(R.id.accessibilityStatusText)

        // Setup Spinners
        val modelLabels = modelOptions.map { it.first }
        val modelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelLabels)
        modelSpinner.adapter = modelAdapter

        val voiceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceOptions)
        voiceSpinner.adapter = voiceAdapter

        // Prime Contacts Recycler
        primeContactsRecycler = findViewById(R.id.primeContactsRecycler)
        primeContactsRecycler.layoutManager = LinearLayoutManager(this)
        primeContactAdapter = PrimeContactAdapter { pos ->
            primeContactAdapter.removeContact(pos)
        }
        primeContactsRecycler.adapter = primeContactAdapter

        findViewById<Button>(R.id.addPrimeContactBtn).setOnClickListener {
            showAddPrimeContactDialog()
        }

        findViewById<android.view.View>(R.id.accessibilityCardLayout).setOnClickListener {
            AccessibilityHelperService.openSettings(this)
        }

        findViewById<Button>(R.id.saveBtn).setOnClickListener {
            savePreferences()
        }
    }

    private fun loadPreferences() {
        val savedKey = prefs.getString("api_key", "")
        if (savedKey.isNullOrEmpty()) {
            try {
                if (BuildConfig.GEMINI_API_KEY.isNotEmpty() && BuildConfig.GEMINI_API_KEY != "MY_GEMINI_API_KEY") {
                    apiKeyInput.setText(BuildConfig.GEMINI_API_KEY)
                }
            } catch (e: Throwable) {
                // ignore
            }
        } else {
            apiKeyInput.setText(savedKey)
        }

        val savedUser = prefs.getString("user_name", "Boss") ?: "Boss"
        userNameInput.setText(savedUser)

        val savedModel = prefs.getString("gemini_model", GeminiLiveClient.DEFAULT_MODEL)
        val modelIdx = modelOptions.indexOfFirst { it.second == savedModel }
        if (modelIdx != -1) modelSpinner.setSelection(modelIdx)

        val savedVoice = prefs.getString("gemini_voice", GeminiLiveClient.DEFAULT_VOICE)
        val voiceIdx = voiceOptions.indexOf(savedVoice)
        if (voiceIdx != -1) voiceSpinner.setSelection(voiceIdx)

        when (prefs.getString("personality_mode", "gf")) {
            "professional" -> radioProfessional.isChecked = true
            "assistant" -> radioAssistant.isChecked = true
            else -> radioGf.isChecked = true
        }

        // Prime Contacts
        val contactsJson = prefs.getString("prime_contacts", "[]") ?: "[]"
        val list = mutableListOf<PrimeContact>()
        try {
            val jsonArray = JSONArray(contactsJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(PrimeContact(obj.optString("name"), obj.optString("number")))
            }
        } catch (e: Exception) {
            // ignore
        }
        primeContactAdapter.setContacts(list)

        updateAccessibilityStatus()
    }

    private fun updateAccessibilityStatus() {
        val isEnabled = AccessibilityHelperService.isEnabled(this)
        if (isEnabled) {
            accessibilityStatusText.text = "✅ Enabled"
            accessibilityStatusText.setTextColor(getColor(R.color.color_success))
        } else {
            accessibilityStatusText.text = "❌ Disabled (Tap to Enable)"
            accessibilityStatusText.setTextColor(getColor(R.color.color_error))
        }
    }

    private fun showAddPrimeContactDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_prime_contact, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.dialogNameInput)
        val numInput = dialogView.findViewById<EditText>(R.id.dialogNumberInput)

        AlertDialog.Builder(this, R.style.Theme_Myra)
            .setView(dialogView)
            .setPositiveButton("ADD") { _, _ ->
                val name = nameInput.text.toString().trim()
                val number = numInput.text.toString().trim()
                if (name.isNotEmpty() && number.isNotEmpty()) {
                    primeContactAdapter.addContact(PrimeContact(name, number))
                } else {
                    Toast.makeText(this, "Please enter both name and number", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun savePreferences() {
        val key = apiKeyInput.text.toString().trim()
        val user = userNameInput.text.toString().trim().ifEmpty { "Boss" }
        val model = modelOptions[modelSpinner.selectedItemPosition].second
        val voice = voiceOptions[voiceSpinner.selectedItemPosition]

        val personality = when (personalityRadioGroup.checkedRadioButtonId) {
            R.id.radioProfessional -> "professional"
            R.id.radioAssistant -> "assistant"
            else -> "gf"
        }

        val contacts = primeContactAdapter.getContacts()
        val jsonArray = JSONArray()
        contacts.forEach { c ->
            val obj = JSONObject().apply {
                put("name", c.name)
                put("number", c.number)
            }
            jsonArray.put(obj)
        }

        prefs.edit().apply {
            putString("api_key", key)
            putString("user_name", user)
            putString("gemini_model", model)
            putString("gemini_voice", voice)
            putString("personality_mode", personality)
            putString("prime_contacts", jsonArray.toString())
            apply()
        }

        Toast.makeText(this, "Settings saved! Restart app or reconnect.", Toast.LENGTH_LONG).show()
        finish()
    }
}
