package com.example.rokidbluetoothlauncher

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class BluetoothSearchActivity : Activity() {
    companion object {
        private const val TAG = "BluetoothSearchActivity"
        private const val DISCOVERABLE_SECONDS = 300
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 42
    }

    private lateinit var statusText: TextView
    private lateinit var hintText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        ensurePermissionsThenStart()
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

    private fun requestBluetoothDiscoverable() {
        try {
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
