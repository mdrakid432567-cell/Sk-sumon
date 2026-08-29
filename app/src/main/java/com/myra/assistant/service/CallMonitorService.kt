package com.myra.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.myra.assistant.R
import com.myra.assistant.ui.main.MainActivity

class CallMonitorService : Service() {

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var lastState = TelephonyManager.CALL_STATE_IDLE

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground()
        registerPhoneStateListener()
        Log.d(TAG, "CallMonitorService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        unregisterPhoneStateListener()
        Log.d(TAG, "CallMonitorService destroyed")
    }

    private fun startAsForeground() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MYRA Call Monitor")
            .setContentText("Monitoring incoming calls for voice announcements")
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MYRA Call Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors phone calls for MYRA assistant"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun registerPhoneStateListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallState(state, null)
                }
            }
            telephonyCallback = callback
            try {
                telephonyManager?.registerTelephonyCallback(mainExecutor, callback)
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing permission to register telephony callback", e)
            }
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state, phoneNumber)
                }
            }
            @Suppress("DEPRECATION")
            try {
                telephonyManager?.listen(
                    phoneStateListener,
                    PhoneStateListener.LISTEN_CALL_STATE
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing permission for phone state listener", e)
            }
        }
    }

    private fun unregisterPhoneStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let {
                try {
                    telephonyManager?.unregisterTelephonyCallback(it)
                } catch (e: Exception) {
                    // ignore
                }
            }
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let {
                telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
            }
        }
    }

    private fun handleCallState(state: Int, number: String?) {
        if (state == lastState) return
        lastState = state

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val callerName = resolveCallerName(number)
                Log.d(TAG, "Incoming call ringing from $callerName ($number)")

                val intent = Intent(this, MainActivity::class.java).apply {
                    action = ACTION_INCOMING_CALL
                    putExtra(EXTRA_INCOMING_CALL, true)
                    putExtra(EXTRA_CALLER_NAME, callerName)
                    putExtra(EXTRA_CALLER_NUMBER, number ?: "")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                Log.d(TAG, "Call ended or idle")
                val broadcastIntent = Intent(ACTION_CALL_ENDED)
                broadcastIntent.setPackage(packageName)
                sendBroadcast(broadcastIntent)
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.d(TAG, "Call answered / off-hook")
            }
        }
    }

    private fun resolveCallerName(phoneNumber: String?): String {
        if (phoneNumber.isNullOrEmpty()) return "Unknown Caller"

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

        try {
            val cursor: Cursor? = contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        return it.getString(nameIndex) ?: phoneNumber
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while reading contacts", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving caller name", e)
        }

        return phoneNumber
    }

    companion object {
        private const val TAG = "CallMonitorService"
        const val CHANNEL_ID = "myra_call_monitor_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_INCOMING_CALL = "com.myra.assistant.INCOMING_CALL"
        const val ACTION_CALL_ENDED = "com.myra.CALL_ENDED"
        const val EXTRA_INCOMING_CALL = "INCOMING_CALL"
        const val EXTRA_CALLER_NAME = "CALLER_NAME"
        const val EXTRA_CALLER_NUMBER = "CALLER_NUMBER"

        fun start(context: Context) {
            try {
                val intent = Intent(context, CallMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start CallMonitorService", e)
            }
        }
    }
}
