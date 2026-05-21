package com.example.rokidbluetoothlauncher

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class BluetoothSearchActivity : Activity() {
    companion object {
        private const val TAG = "BluetoothSearchActivity"
        private const val DISCOVERABLE_SECONDS = 300
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 42
        private const val SEARCH_PROMPT_UTTERANCE_ID = "rokid_bluetooth_search_prompt"
    }

    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var searchPromptRequested = false
    private var searchPromptSpoken = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        initSearchPromptTts()
        buildUi()
        ensurePermissionsThenStart()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            requestBluetoothDiscoverable()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(40, 40, 40, 40)
        }

        statusText = TextView(this).apply {
            text = getString(R.string.finding_glasses)
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
        }

        hintText = TextView(this).apply {
            text = getString(R.string.watch_for_blue_light)
            setTextColor(Color.rgb(100, 181, 246))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }

        root.addView(statusText)
        root.addView(hintText)
        setContentView(root)
    }

    private fun ensurePermissionsThenStart() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            requestBluetoothDiscoverable()
            return
        }

        val missingPermissions = listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ).filter { permission ->
            checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            requestBluetoothDiscoverable()
        } else {
            statusText.text = getString(R.string.bluetooth_permission_needed)
            hintText.text = getString(R.string.grant_permission_hint)
            requestPermissions(missingPermissions.toTypedArray(), REQUEST_BLUETOOTH_PERMISSIONS)
        }
    }

    private fun initSearchPromptTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                configureSearchPromptTts()
                if (searchPromptRequested) {
                    speakSearchPrompt()
                }
            } else {
                Log.w(TAG, "Bluetooth search TTS unavailable: status=$status")
            }
        }
    }

    private fun configureSearchPromptTts() {
        val engine = tts ?: return
        val langResult = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Simplified Chinese TTS unavailable; using default voice")
        }
        engine.setSpeechRate(1.0f)
        engine.setPitch(1.0f)
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .build()
        )
    }

    private fun requestSearchPrompt() {
        searchPromptRequested = true
        speakSearchPrompt()
    }

    private fun speakSearchPrompt() {
        val engine = tts
        if (!ttsReady || engine == null || searchPromptSpoken) return

        searchPromptSpoken = true
        engine.speak(
            getString(R.string.bluetooth_voice_prompt_zh),
            TextToSpeech.QUEUE_FLUSH,
            null,
            SEARCH_PROMPT_UTTERANCE_ID
        )
    }

    private fun requestBluetoothDiscoverable() {
        try {
            requestSearchPrompt()
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
            }
            startActivity(intent)
            statusText.text = getString(R.string.finding_glasses)
            hintText.text = getString(R.string.scan_from_pixel)
            Log.d(TAG, "Requested Bluetooth discoverable mode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Bluetooth discoverable mode", e)
            statusText.text = getString(R.string.bluetooth_search_unavailable)
            hintText.text = getString(R.string.hardware_gesture_may_be_required)
        }
    }
}
