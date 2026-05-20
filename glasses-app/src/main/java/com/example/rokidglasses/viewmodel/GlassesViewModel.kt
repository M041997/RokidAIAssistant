package com.example.rokidglasses.viewmodel

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rokidcommon.Constants
import com.example.rokidcommon.protocol.ConnectionState
import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import com.example.rokidglasses.R
import com.example.rokidglasses.sdk.CameraMode
import com.example.rokidglasses.sdk.CxrServiceManager
import com.example.rokidglasses.sdk.UnifiedCameraManager
import com.example.rokidglasses.service.BluetoothClientState
import com.example.rokidglasses.service.BluetoothSppClient
import com.example.rokidglasses.service.photo.GlassesCameraManager
import com.example.rokidglasses.service.photo.ImageCompressor
import com.example.rokidglasses.service.photo.PhotoTransferProtocol
import com.example.rokidglasses.service.photo.createPhotoTransferProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream

data class GlassesUiState(
    val isConnected: Boolean = false,
    val isListening: Boolean = false,
    val isProcessing: Boolean = false,
    val displayText: String = "",
    val hintText: String = "",
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val userTranscript: String = "",
    val aiResponse: String = "",
    // Pagination for long text
    val currentPage: Int = 0,
    val totalPages: Int = 1,
    val isPaginated: Boolean = false,
    // Bluetooth related
    val bluetoothState: BluetoothClientState = BluetoothClientState.DISCONNECTED,
    val connectedDeviceName: String? = null,
    val availableDevices: List<BluetoothDevice> = emptyList(),
    // CXR connected phone name (to help identify correct SPP device)
    val cxrConnectedPhoneName: String? = null,
    // Photo capture state
    val isCapturingPhoto: Boolean = false,
    val photoTransferProgress: Float = 0f,
    // Gemini Live mode state
    val isLiveModeActive: Boolean = false,
    val liveTranscription: String = ""
)

/**
 * Glasses ViewModel
 * 
 * New Architecture:
 * 1. Glasses record -> Collect PCM data
 * 2. Recording ends -> Send via Bluetooth SPP to phone
 * 3. Phone processes AI -> Returns result
 * 4. Glasses display result
 */
class GlassesViewModel(
    private val context: Context
) : ViewModel() {
    
    companion object {
        private const val TAG = "GlassesViewModel"
        // Max characters per page for glasses display
        private const val MAX_CHARS_PER_PAGE = 120
        private const val MAX_LINES_PER_PAGE = 4
        private const val AUDIO_DIAGNOSTIC_INTERVAL_MS = 1000L
    }
    
    private val _uiState = MutableStateFlow(GlassesUiState(
        displayText = context.getString(R.string.say_hey_rokid),
        hintText = context.getString(R.string.tap_touchpad_record)
    ))
    val uiState: StateFlow<GlassesUiState> = _uiState.asStateFlow()
    
    // Store full AI response for pagination
    private var fullAiResponse: String = ""
    private var responsePages: List<String> = emptyList()
    
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    
    // Bluetooth SPP client - connects to phone
    private val bluetoothClient = BluetoothSppClient(context, viewModelScope)
    
    // Camera manager for photo capture
    // Glasses use Camera2 API to directly access local camera (no longer using CXR-M SDK)
    // CXR-M SDK is for phone side to remotely control glasses camera
    private var cameraManager: UnifiedCameraManager? = null
    
    // CXR-S SDK service manager (for communication with phone)
    private var cxrServiceManager: CxrServiceManager? = null
    
    // Photo transfer protocol
    private var photoTransferProtocol: PhotoTransferProtocol? = null
    
    // Audio buffer - collects recording data
    private val audioBuffer = ByteArrayOutputStream()

    private data class AudioRecordSetup(
        val audioRecord: AudioRecord,
        val source: Int,
        val sourceName: String
    )
    
    // ========== Gemini Live Mode Related ==========
    
    // Live mode activation status (notified by phone)
    private var isLiveModeActive = false

    // Continuous visual translation mode activation status (notified by phone)
    private var isVisualTranslationActive = false
    
    // Real-time video streaming job (~1fps camera frame capture and send to phone)
    private var videoStreamingJob: Job? = null
    
    // Video streaming frame rate control (milliseconds)
    private val videoFrameIntervalMs = 1000L  // ~1fps
    
    // Video streaming JPEG compression quality (0-100)
    private val videoFrameQuality = 50

    // Live translation should match the user's central field of view, not the full wide camera frame.
    private val visualTranslationFrameZoom = 2.0f

    // Rokid camera frames arrive in sensor orientation, which is sideways relative to the wearer view.
    private val visualTranslationFrameRotationDegrees = 180
    
    init {
        initializeBluetooth()
        initializeCamera()
        initializeCxrService()
    }
    
    /**
     * Initialize CXR-S SDK service (Glasses Side)
     * Used to receive messages and commands from phone side
     */
    private fun initializeCxrService() {
        if (CxrServiceManager.isSdkAvailable()) {
            cxrServiceManager = CxrServiceManager.getInstance()
            val initialized = cxrServiceManager?.initialize() == true
            Log.d(TAG, "CXR-S Service initialized: $initialized")
            
            if (initialized) {
                // Listen for connection state
                viewModelScope.launch {
                    cxrServiceManager?.connectionState?.collect { state ->
                        when (state) {
                            is CxrServiceManager.ConnectionState.Connected -> {
                                Log.d(TAG, "CXR connected to: ${state.deviceName}")
                                // Store CXR-connected phone name for SPP device selection
                                _uiState.update { it.copy(cxrConnectedPhoneName = state.deviceName) }
                            }
                            is CxrServiceManager.ConnectionState.Disconnected -> {
                                Log.d(TAG, "CXR disconnected")
                                _uiState.update { it.copy(cxrConnectedPhoneName = null) }
                            }
                        }
                    }
                }
            }
        } else {
            Log.w(TAG, "CXR-S SDK not available")
        }
    }
    
    private fun initializeCamera() {
        viewModelScope.launch {
            // Glasses directly use Camera2 API (no longer depends on CXR-M SDK)
            // CXR-M SDK camera functionality is for phone side to remotely control glasses camera
            cameraManager = UnifiedCameraManager(
                context = context,
                preferredMode = CameraMode.CAMERA2  // Use local Camera2 API directly
            )
            
            val result = cameraManager?.initialize()
            
            if (result?.isSuccess == true) {
                val cameraType = cameraManager?.getCameraTypeName() ?: "Unknown"
                Log.d(TAG, "Camera manager initialized: $cameraType")
            } else {
                Log.w(TAG, "Camera manager initialization failed: ${result?.exceptionOrNull()?.message}")
            }
        }
    }
    
    private fun initializeBluetooth() {
        // Listen to Bluetooth connection state
        viewModelScope.launch {
            bluetoothClient.connectionState.collect { state ->
                Log.d(TAG, "Bluetooth state changed: $state")
                val connectionState = when (state) {
                    BluetoothClientState.DISCONNECTED -> ConnectionState.DISCONNECTED
                    BluetoothClientState.CONNECTING -> ConnectionState.CONNECTING
                    BluetoothClientState.CONNECTED -> ConnectionState.CONNECTED
                }
                
                _uiState.update { it.copy(
                    bluetoothState = state,
                    connectionState = connectionState,
                    isConnected = state == BluetoothClientState.CONNECTED,
                    displayText = when (state) {
                        BluetoothClientState.DISCONNECTED -> context.getString(R.string.not_connected)
                        BluetoothClientState.CONNECTING -> context.getString(R.string.connecting_status)
                        BluetoothClientState.CONNECTED -> context.getString(R.string.connected_ready)
                    },
                    hintText = when (state) {
                        BluetoothClientState.DISCONNECTED -> context.getString(R.string.please_connect_phone)
                        BluetoothClientState.CONNECTING -> context.getString(R.string.please_wait)
                        BluetoothClientState.CONNECTED -> context.getString(R.string.tap_touchpad_start)
                    }
                ) }
            }
        }
        
        // Listen to connected device name
        viewModelScope.launch {
            bluetoothClient.connectedDeviceName.collect { name ->
                _uiState.update { it.copy(connectedDeviceName = name) }
            }
        }
        
        // Listen to messages from phone
        viewModelScope.launch {
            bluetoothClient.messageFlow.collect { message ->
                handlePhoneMessage(message)
            }
        }
        
        // Get paired devices
        refreshPairedDevices()
    }
    
    /**
     * Refresh paired devices list
     */
    fun refreshPairedDevices() {
        val devices = bluetoothClient.getPairedDevices()
        _uiState.update { it.copy(availableDevices = devices) }
        Log.d(TAG, "Found ${devices.size} paired devices")
    }
    
    /**
     * Connect to specified device
     */
    fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to device: ${device.name}")
        bluetoothClient.connect(device)
    }
    
    /**
     * Disconnect Bluetooth connection
     */
    fun disconnectBluetooth() {
        bluetoothClient.disconnect()
    }

    private fun createAudioRecord(bufferSizeBytes: Int): AudioRecordSetup? {
        val candidateSources = listOf(
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC
        )

        for (source in candidateSources) {
            val sourceName = audioSourceName(source)
            try {
                val candidate = AudioRecord(
                    source,
                    Constants.AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSizeBytes
                )

                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d(TAG, "AudioRecord initialized with source=$sourceName ($source), bufferSizeBytes=$bufferSizeBytes")
                    return AudioRecordSetup(candidate, source, sourceName)
                }

                Log.w(TAG, "AudioRecord source=$sourceName ($source) failed to initialize, state=${candidate.state}")
                candidate.release()
            } catch (e: SecurityException) {
                Log.e(TAG, "AudioRecord source=$sourceName ($source) blocked by microphone permission", e)
                throw e
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "AudioRecord source=$sourceName ($source) rejected by platform", e)
            } catch (e: UnsupportedOperationException) {
                Log.w(TAG, "AudioRecord source=$sourceName ($source) unsupported by platform", e)
            }
        }

        Log.e(TAG, "AudioRecord initialization failed for all candidate sources")
        return null
    }

    private fun audioSourceName(source: Int): String {
        return when (source) {
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.MIC -> "MIC"
            else -> "UNKNOWN"
        }
    }

    private fun calculatePcm16Peak(buffer: ByteArray, byteCount: Int): Int {
        var peak = 0
        var index = 0
        val evenByteCount = byteCount - (byteCount % 2)

        while (index < evenByteCount) {
            val sample = (buffer[index].toInt() and 0xFF) or (buffer[index + 1].toInt() shl 8)
            val amplitude = if (sample == Short.MIN_VALUE.toInt()) {
                Short.MAX_VALUE.toInt() + 1
            } else {
                kotlin.math.abs(sample)
            }
            peak = maxOf(peak, amplitude)
            index += 2
        }

        return peak
    }
    
    fun startRecording() {
        // Check if connected
        if (_uiState.value.bluetoothState != BluetoothClientState.CONNECTED) {
            _uiState.update { it.copy(
                displayText = context.getString(R.string.please_connect_phone),
                hintText = context.getString(R.string.select_paired_device)
            ) }
            return
        }
        
        if (_uiState.value.isListening) return
        
        // Clear audio buffer and reset pagination
        audioBuffer.reset()
        resetPagination()
        
        _uiState.update { it.copy(
            isListening = true,
            displayText = context.getString(R.string.listening),
            hintText = context.getString(R.string.tap_stop_recording),
            userTranscript = "",
            aiResponse = ""
        ) }
        
        Log.d(TAG, "Start recording")
        
        // Check RECORD_AUDIO permission before proceeding
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            _uiState.update { it.copy(
                displayText = context.getString(R.string.mic_permission_required),
                isListening = false
            ) }
            return
        }
        
        // Notify phone that recording started
        viewModelScope.launch {
            bluetoothClient.sendVoiceStart()
        }
        
        // Start recording
        recordingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(
                    Constants.AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "Invalid AudioRecord min buffer size: $bufferSize")
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(
                            displayText = "Failed to initialize microphone",
                            isListening = false
                        ) }
                    }
                    return@launch
                }
                
                // Verify permission again before AudioRecord initialization
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "RECORD_AUDIO permission lost during initialization")
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(
                            displayText = context.getString(R.string.mic_permission_required),
                            isListening = false
                        ) }
                    }
                    return@launch
                }
                
                val audioRecordSetup = createAudioRecord(bufferSize * 2)
                audioRecord = audioRecordSetup?.audioRecord
                
                // Verify AudioRecord was initialized successfully
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord initialization failed")
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(
                            displayText = "Failed to initialize microphone",
                            isListening = false
                        ) }
                    }
                    audioRecord?.release()
                    audioRecord = null
                    return@launch
                }
                
                audioRecord?.startRecording()
                Log.d(TAG, "AudioRecord started recording with source=${audioRecordSetup?.sourceName} (${audioRecordSetup?.source})")
                
                val buffer = ByteArray(Constants.AUDIO_BUFFER_SIZE)
                var totalBytesRead = 0L
                var readCalls = 0L
                var zeroReadCalls = 0L
                var errorReadCalls = 0L
                var peakSinceLastLog = 0
                var lastDiagnosticLogMs = System.currentTimeMillis()
                
                while (isActive && _uiState.value.isListening) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    readCalls++
                    if (readSize > 0) {
                        totalBytesRead += readSize
                        peakSinceLastLog = maxOf(peakSinceLastLog, calculatePcm16Peak(buffer, readSize))
                        // Collect audio data to buffer
                        synchronized(audioBuffer) {
                            audioBuffer.write(buffer, 0, readSize)
                        }
                    } else if (readSize == 0) {
                        zeroReadCalls++
                    } else {
                        errorReadCalls++
                        Log.w(TAG, "AudioRecord.read returned error code $readSize")
                    }

                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastDiagnosticLogMs >= AUDIO_DIAGNOSTIC_INTERVAL_MS) {
                        Log.d(
                            TAG,
                            "Audio diagnostic source=${audioRecordSetup?.sourceName} readCalls=$readCalls " +
                                "bytes=$totalBytesRead peak=$peakSinceLastLog zeroReads=$zeroReadCalls errorReads=$errorReadCalls"
                        )
                        peakSinceLastLog = 0
                        lastDiagnosticLogMs = nowMs
                    }
                }
                
                Log.d(
                    TAG,
                    "Recording ended, source=${audioRecordSetup?.sourceName}, collected ${audioBuffer.size()} bytes, " +
                        "readCalls=$readCalls, zeroReads=$zeroReadCalls, errorReads=$errorReadCalls"
                )
                
            } catch (e: SecurityException) {
                Log.e(TAG, "Microphone permission error", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(
                        displayText = context.getString(R.string.mic_permission_required),
                        isListening = false
                    ) }
                }
            } finally {
                // Safely stop and release AudioRecord
                try {
                    if (audioRecord?.recordingState == android.media.AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord?.stop()
                    }
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "AudioRecord stop failed (already stopped or invalid state)", e)
                }
                try {
                    audioRecord?.release()
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord release failed", e)
                }
                audioRecord = null
            }
        }
    }
    
    fun stopRecording() {
        _uiState.update { it.copy(
            isListening = false,
            isProcessing = true,
            displayText = context.getString(R.string.sending_audio),
            hintText = context.getString(R.string.please_wait)
        ) }
        
        val jobToStop = recordingJob
        recordingJob = null
        
        Log.d(TAG, "Stop recording, sending audio to phone")
        
        // Send audio via Bluetooth to phone for processing
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withTimeoutOrNull(1000) {
                    jobToStop?.join()
                }

                // Get recording data
                val audioData: ByteArray
                synchronized(audioBuffer) {
                    audioData = audioBuffer.toByteArray()
                }
                
                Log.d(TAG, "Audio data size: ${audioData.size} bytes")
                
                if (audioData.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(
                            isProcessing = false,
                            displayText = context.getString(R.string.no_voice_detected),
                            hintText = context.getString(R.string.please_try_again)
                        ) }
                    }
                    return@launch
                }
                
                // Send audio data to phone
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(displayText = context.getString(R.string.sending)) }
                }
                
                val success = bluetoothClient.sendVoiceEnd(audioData)
                
                if (!success) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(
                            isProcessing = false,
                            displayText = context.getString(R.string.send_failed),
                            hintText = context.getString(R.string.reconnect_try_again)
                        ) }
                    }
                    return@launch
                }
                
                Log.d(TAG, "Audio sent to phone, waiting for processing result")
                
                // Update UI state, waiting for phone response
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(
                        displayText = context.getString(R.string.waiting_phone),
                        hintText = context.getString(R.string.ai_thinking)
                    ) }
                }
                
                // Phone will update UI via handlePhoneMessage callback when done
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sending audio", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(
                        isProcessing = false,
                        displayText = context.getString(R.string.error_prefix, e.message ?: ""),
                        hintText = context.getString(R.string.please_try_again)
                    ) }
                }
            }
        }
    }
    
    fun toggleRecording() {
        if (_uiState.value.isListening) {
            stopRecording()
        } else {
            startRecording()
        }
    }
    
    /**
     * Handle message from phone
     */
    private fun handlePhoneMessage(message: Message) {
        Log.d(TAG, "Received from phone: ${message.type}, payload: ${message.payload}")
        
        when (message.type) {
            MessageType.AI_PROCESSING -> {
                _uiState.update { it.copy(
                    isProcessing = true,
                    displayText = message.payload ?: context.getString(R.string.processing)
                ) }
            }
            
            MessageType.USER_TRANSCRIPT -> {
                // User's speech recognized by phone
                _uiState.update { it.copy(
                    userTranscript = message.payload ?: "",
                    displayText = context.getString(R.string.you_said, message.payload ?: "")
                ) }
            }
            
            MessageType.AI_RESPONSE_TEXT -> {
                handleAiResponseText(message)
            }
            
            MessageType.AI_RESPONSE_TTS -> {
                // Play AI voice
                message.binaryData?.let { audioData ->
                    playAudio(audioData)
                }
            }
            
            MessageType.AI_ERROR -> {
                _uiState.update { it.copy(
                    isProcessing = false,
                    displayText = context.getString(R.string.error_prefix, message.payload ?: ""),
                    hintText = context.getString(R.string.please_try_again)
                ) }
            }
            
            MessageType.DISPLAY_TEXT -> {
                _uiState.update { it.copy(
                    displayText = message.payload ?: ""
                ) }
            }
            
            MessageType.DISPLAY_CLEAR -> {
                _uiState.update { it.copy(
                    displayText = "",
                    hintText = context.getString(R.string.tap_touchpad_start)
                ) }
            }
            
            MessageType.HEARTBEAT -> {
                viewModelScope.launch {
                    bluetoothClient.sendMessage(
                        Message(type = MessageType.HEARTBEAT_ACK)
                    )
                }
            }
            
            MessageType.CAPTURE_PHOTO -> {
                // Phone requested to capture photo
                Log.d(TAG, "Phone requested photo capture")
                captureAndSendPhoto()
            }
            
            // ========== Gemini Live Mode Message Handling ==========
            
            MessageType.LIVE_SESSION_START -> {
                // Phone started Live mode
                Log.d(TAG, "Live session started by phone")
                isLiveModeActive = true
                _uiState.update { it.copy(
                    isLiveModeActive = true,
                    displayText = "🎙️ Live mode activated",
                    hintText = "Real-time voice conversation...",
                    liveTranscription = ""
                ) }
                // Start real-time video streaming to phone
                startVideoStreaming()
            }
            
            MessageType.LIVE_SESSION_END -> {
                // Phone ended Live mode
                Log.d(TAG, "Live session ended by phone")
                isLiveModeActive = false
                if (!isVisualTranslationActive) {
                    stopVideoStreaming()
                }
                _uiState.update { it.copy(
                    isLiveModeActive = false,
                    displayText = "Live mode ended",
                    hintText = context.getString(R.string.tap_touchpad_start),
                    liveTranscription = ""
                ) }
            }

            MessageType.VISUAL_TRANSLATION_START -> {
                Log.d(TAG, "Visual translation started by phone")
                isVisualTranslationActive = true
                _uiState.update { it.copy(
                    displayText = "Visual translation active",
                    hintText = "Look at text to translate"
                ) }
                startVideoStreaming()
            }

            MessageType.VISUAL_TRANSLATION_END -> {
                Log.d(TAG, "Visual translation ended by phone")
                isVisualTranslationActive = false
                if (!isLiveModeActive) {
                    stopVideoStreaming()
                }
                _uiState.update { it.copy(
                    displayText = context.getString(R.string.connected_ready),
                    hintText = context.getString(R.string.tap_touchpad_start)
                ) }
            }
            
            MessageType.LIVE_TRANSCRIPTION -> {
                // Real-time transcription text (user voice or AI response)
                val text = message.payload ?: ""
                Log.d(TAG, "Live transcription: $text")
                _uiState.update { it.copy(
                    liveTranscription = text,
                    displayText = text
                ) }
            }
            
            MessageType.PHOTO_ANALYSIS_RESULT -> {
                // Received photo analysis result from phone
                Log.d(TAG, "Photo analysis result: ${message.payload}")
                val analysisText = message.payload ?: context.getString(R.string.photo_analysis_no_result)
                
                // Clear capturing state and show result
                _uiState.update { it.copy(
                    isCapturingPhoto = false,
                    photoTransferProgress = 0f,
                    isProcessing = false
                ) }
                
                // Display the analysis result (with pagination)
                fullAiResponse = analysisText
                responsePages = paginateText(analysisText)
                val isPaginated = responsePages.size > 1
                val pageIndicator = if (isPaginated) " (1/${responsePages.size})" else ""
                val hintText = if (isPaginated) 
                    context.getString(R.string.swipe_left_right_pages)
                else 
                    context.getString(R.string.tap_touchpad_start)
                
                _uiState.update { it.copy(
                    displayText = responsePages[0] + pageIndicator,
                    hintText = hintText,
                    currentPage = 0,
                    totalPages = responsePages.size,
                    isPaginated = isPaginated
                ) }
            }
            
            MessageType.REMOTE_RECORD_START -> {
                // Phone requested glasses to start recording
                Log.d(TAG, "Remote recording start command from phone")
                if (!_uiState.value.isListening) {
                    startRecording()
                } else {
                    Log.d(TAG, "Already recording, ignoring remote start command")
                }
            }
            
            MessageType.REMOTE_RECORD_STOP -> {
                // Phone requested glasses to stop recording
                Log.d(TAG, "Remote recording stop command from phone")
                if (_uiState.value.isListening) {
                    stopRecording()
                } else {
                    Log.d(TAG, "Not recording, ignoring remote stop command")
                }
            }
            
            // HEARTBEAT_ACK is handled internally by BluetoothSppClient
            MessageType.HEARTBEAT_ACK -> { /* no-op */ }
            
            else -> { 
                Log.d(TAG, "Unhandled message type: ${message.type}")
            }
        }
    }
    
    /**
     * Handle AI response text message with pagination support
     */
    private fun handleAiResponseText(message: Message) {
        val responseText = message.payload ?: ""
        fullAiResponse = responseText
        responsePages = paginateText(responseText)
        
        val isPaginated = responsePages.size > 1
        val displayText = if (responsePages.isNotEmpty()) responsePages[0] else responseText
        val hintText = if (isPaginated) {
            context.getString(R.string.swipe_for_more)
        } else {
            context.getString(R.string.tap_continue)
        }
        
        _uiState.update { it.copy(
            isProcessing = false,
            aiResponse = responseText,
            displayText = displayText,
            hintText = hintText,
            currentPage = 0,
            totalPages = responsePages.size,
            isPaginated = isPaginated
        ) }
    }
    
    private fun playAudio(audioData: ByteArray) {
        // TODO: Use AudioTrack to play audio
        Log.d(TAG, "Playing audio: ${audioData.size} bytes")
    }
    
    /**
     * Paginate long text for glasses display
     * Splits text into pages based on character limit and line count
     */
    private fun paginateText(text: String): List<String> {
        if (text.length <= MAX_CHARS_PER_PAGE) {
            return listOf(text)
        }
        
        val pages = mutableListOf<String>()
        val words = text.split(" ", "，", "。", "、", "！", "？")
        var currentPage = StringBuilder()
        var lineCount = 0
        var charCount = 0
        
        for (word in words) {
            val wordWithSpace = if (currentPage.isEmpty()) word else " $word"
            val newCharCount = charCount + wordWithSpace.length
            
            // Check if adding this word would exceed limits
            if (newCharCount > MAX_CHARS_PER_PAGE || lineCount >= MAX_LINES_PER_PAGE) {
                if (currentPage.isNotEmpty()) {
                    pages.add(currentPage.toString().trim())
                    currentPage = StringBuilder()
                    charCount = 0
                    lineCount = 0
                }
            }
            
            currentPage.append(wordWithSpace)
            charCount = currentPage.length
            
            // Count newlines for line tracking
            if (word.contains("\n")) {
                lineCount += word.count { it == '\n' }
            }
        }
        
        // Add remaining text
        if (currentPage.isNotEmpty()) {
            pages.add(currentPage.toString().trim())
        }
        
        // If simple word splitting didn't work well, use character-based splitting
        if (pages.isEmpty() || (pages.size == 1 && text.length > MAX_CHARS_PER_PAGE)) {
            pages.clear()
            var i = 0
            while (i < text.length) {
                val end = minOf(i + MAX_CHARS_PER_PAGE, text.length)
                // Try to break at natural boundaries
                var breakPoint = end
                if (end < text.length) {
                    val lastSpace = text.lastIndexOf(' ', end)
                    val lastPunctuation = maxOf(
                        text.lastIndexOf('。', end),
                        text.lastIndexOf('，', end),
                        text.lastIndexOf('.', end),
                        text.lastIndexOf(',', end)
                    )
                    val naturalBreak = maxOf(lastSpace, lastPunctuation)
                    if (naturalBreak > i) {
                        breakPoint = naturalBreak + 1
                    }
                }
                pages.add(text.substring(i, breakPoint).trim())
                i = breakPoint
            }
        }
        
        return pages
    }
    
    /**
     * Navigate to next page (swipe down)
     */
    fun nextPage() {
        val currentState = _uiState.value
        if (currentState.isPaginated && currentState.currentPage < currentState.totalPages - 1) {
            val newPage = currentState.currentPage + 1
            val isLastPage = newPage == currentState.totalPages - 1
            _uiState.update { it.copy(
                currentPage = newPage,
                displayText = responsePages.getOrElse(newPage) { "" },
                hintText = if (isLastPage) context.getString(R.string.tap_continue) 
                          else context.getString(R.string.swipe_for_more)
            ) }
        }
    }
    
    /**
     * Navigate to previous page (swipe up)
     */
    fun previousPage() {
        val currentState = _uiState.value
        if (currentState.isPaginated && currentState.currentPage > 0) {
            val newPage = currentState.currentPage - 1
            _uiState.update { it.copy(
                currentPage = newPage,
                displayText = responsePages.getOrElse(newPage) { "" },
                hintText = context.getString(R.string.swipe_for_more)
            ) }
        }
    }
    
    /**
     * Dismiss pagination and return to normal state
     */
    fun dismissPagination() {
        resetPagination()
        _uiState.update { it.copy(
            displayText = context.getString(R.string.tap_touchpad_start),
            hintText = context.getString(R.string.tap_touchpad_record)
        ) }
    }
    
    /**
     * Reset pagination state (when starting new conversation)
     */
    private fun resetPagination() {
        fullAiResponse = ""
        responsePages = emptyList()
        _uiState.update { it.copy(
            currentPage = 0,
            totalPages = 1,
            isPaginated = false
        ) }
    }

    override fun onCleared() {
        super.onCleared()
        recordingJob?.cancel()
        videoStreamingJob?.cancel()
        audioRecord?.release()
        bluetoothClient.disconnect()
        cameraManager?.release()
        cxrServiceManager?.release()
    }
    
    // ==================== Live Mode: Real-time Video Streaming ====================
    
    /**
     * Start real-time video streaming
     * Captures camera frames at ~1fps, compresses to JPEG and sends to phone via Bluetooth,
     * which then forwards to GeminiLiveService's WebSocket.
     *
     * Notes:
     * - Requires CAMERA permission
     * - Uses Camera2 API (UnifiedCameraManager)
     * - JPEG compression quality reduced to 50% to reduce Bluetooth bandwidth
     * - Errors won't interrupt streaming, only logged
     */
    private fun startVideoStreaming() {
        if (videoStreamingJob?.isActive == true) {
            Log.w(TAG, "Video streaming already active")
            return
        }
        
        if (cameraManager == null) {
            Log.w(TAG, "Camera not available for video streaming")
            return
        }
        
        Log.d(TAG, "Starting video frame streaming (~1fps, quality=$videoFrameQuality)")
        
        videoStreamingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && (isLiveModeActive || isVisualTranslationActive)) {
                try {
                    // Capture one camera frame
                    val rawImageData = cameraManager?.capturePhoto()
                    
                    if (rawImageData != null) {
                        // Compress to low-quality JPEG to reduce transfer size (video frames use smaller dimensions)
                        val compressedFrame = withContext(Dispatchers.Default) {
                            val shouldZoomFrame = isVisualTranslationActive
                            ImageCompressor.compressForTransfer(
                                rawImageData,
                                targetWidth = 640,
                                targetHeight = 480,
                                quality = videoFrameQuality,
                                centerCropToTargetAspect = shouldZoomFrame,
                                zoomFactor = if (shouldZoomFrame) visualTranslationFrameZoom else 1.0f,
                                rotationDegrees = if (shouldZoomFrame) visualTranslationFrameRotationDegrees else 0
                            )
                        }
                        
                        Log.d(TAG, "Video frame captured: ${compressedFrame.size} bytes")
                        
                        // Send VIDEO_FRAME message to phone via Bluetooth
                        bluetoothClient.sendMessage(
                            Message(
                                type = MessageType.VIDEO_FRAME,
                                binaryData = compressedFrame
                            )
                        )
                    } else {
                        Log.w(TAG, "Failed to capture video frame")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error capturing video frame", e)
                }
                
                // Wait until next frame (~1fps)
                delay(videoFrameIntervalMs)
            }
            
            Log.d(TAG, "Video streaming loop ended")
        }
    }
    
    /**
     * Stop real-time video streaming
     */
    private fun stopVideoStreaming() {
        videoStreamingJob?.cancel()
        videoStreamingJob = null
        Log.d(TAG, "Video streaming stopped")
    }
    
    // ==================== Photo Capture ====================
    
    /**
     * Capture photo and send to phone for AI analysis.
     * Triggered by camera key press or voice command.
     */
    fun captureAndSendPhoto() {
        if (_uiState.value.isCapturingPhoto) {
            Log.w(TAG, "Photo capture already in progress")
            return
        }
        
        if (_uiState.value.bluetoothState != BluetoothClientState.CONNECTED) {
            Log.w(TAG, "Not connected to phone")
            _uiState.update { it.copy(
                displayText = context.getString(R.string.bluetooth_not_connected),
                hintText = context.getString(R.string.connect_phone_first)
            ) }
            return
        }
        
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(
                    isCapturingPhoto = true,
                    isProcessing = true,
                    displayText = context.getString(R.string.capturing_photo),
                    hintText = context.getString(R.string.please_wait_short)
                ) }
                
                // Step 1: Capture photo
                val cameraType = cameraManager?.getCameraTypeName() ?: "Unknown"
                Log.d(TAG, "Capturing photo using: $cameraType")
                val rawImageData = cameraManager?.capturePhoto()
                
                if (rawImageData == null) {
                    val cameraState = cameraManager?.cameraState?.value
                    Log.e(TAG, "Failed to capture photo. Camera state: $cameraState")
                    _uiState.update { it.copy(
                        isCapturingPhoto = false,
                        isProcessing = false,
                        displayText = context.getString(R.string.capture_failed),
                        hintText = context.getString(R.string.capture_failed_hint)
                    ) }
                    return@launch
                }
                
                Log.d(TAG, "Photo captured: ${rawImageData.size} bytes using $cameraType")
                
                // Step 2: Compress photo
                _uiState.update { it.copy(displayText = context.getString(R.string.compressing_photo)) }
                val compressedData = withContext(Dispatchers.Default) {
                    ImageCompressor.compressForTransfer(rawImageData)
                }
                Log.d(TAG, "Compressed: ${rawImageData.size} -> ${compressedData.size} bytes")
                
                // Step 3: Send to phone
                _uiState.update { it.copy(
                    displayText = context.getString(R.string.transferring_photo),
                    photoTransferProgress = 0f
                ) }
                
                val socket = bluetoothClient.connectedSocket
                if (socket == null || !socket.isConnected) {
                    throw IllegalStateException("Bluetooth socket not connected")
                }
                
                photoTransferProtocol = socket.createPhotoTransferProtocol { current, total ->
                    val progress = current.toFloat() / total
                    _uiState.update { it.copy(photoTransferProgress = progress) }
                }
                
                val result = photoTransferProtocol?.sendPhoto(compressedData)
                
                result?.fold(
                    onSuccess = { stats ->
                        Log.d(TAG, "Photo transfer complete: $stats")
                        _uiState.update { it.copy(
                            isCapturingPhoto = false,
                            displayText = context.getString(R.string.photo_sent_waiting_ai),
                            hintText = context.getString(R.string.please_wait_short)
                        ) }
                        // Phone will send AI response via Bluetooth
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Photo transfer failed", error)
                        _uiState.update { it.copy(
                            isCapturingPhoto = false,
                            isProcessing = false,
                            displayText = context.getString(R.string.transfer_failed, error.message ?: ""),
                            hintText = context.getString(R.string.please_retry)
                        ) }
                    }
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Photo capture error", e)
                _uiState.update { it.copy(
                    isCapturingPhoto = false,
                    isProcessing = false,
                    displayText = context.getString(R.string.error_message, e.message ?: ""),
                    hintText = context.getString(R.string.please_retry)
                ) }
            }
        }
    }
    
    /**
     * ViewModel Factory
     */
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(GlassesViewModel::class.java)) {
                return GlassesViewModel(context.applicationContext) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
