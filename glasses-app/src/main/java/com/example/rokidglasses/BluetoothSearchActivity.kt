package com.example.rokidglasses

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.rokidglasses.ui.theme.RokidGlassesTheme

class BluetoothSearchActivity : ComponentActivity() {
    companion object {
        private const val TAG = "BluetoothSearchActivity"
        private const val DISCOVERABLE_SECONDS = 300
    }

    private var statusText by mutableStateOf("Starting Bluetooth search mode...")
    private var hintText by mutableStateOf("Watch for the glasses blue light")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        requestBluetoothDiscoverable()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            RokidGlassesTheme {
                BluetoothSearchScreen(
                    status = statusText,
                    hint = hintText
                )
            }
        }

        ensurePermissionsThenStart()
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
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            requestBluetoothDiscoverable()
        } else {
            statusText = "Bluetooth permission needed"
            hintText = "Grant permission, then scan from Pixel 7"
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun requestBluetoothDiscoverable() {
        try {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
            }
            startActivity(intent)
            statusText = "Bluetooth search mode"
            hintText = "Scan from Pixel 7 now"
            Log.d(TAG, "Requested Bluetooth discoverable mode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Bluetooth discoverable mode", e)
            statusText = "Bluetooth search unavailable"
            hintText = "Rokid firmware may require the hardware gesture"
        }
    }
}

@androidx.compose.runtime.Composable
private fun BluetoothSearchScreen(
    status: String,
    hint: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = status,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = hint,
                color = Color(0xFF64B5F6),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
