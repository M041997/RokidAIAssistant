package com.example.rokidphone.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.rokidcommon.Constants
import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import com.example.rokidcommon.protocol.photo.PhotoTransferState
import com.example.rokidphone.BuildConfig
import com.example.rokidphone.MainActivity
import com.example.rokidphone.R
import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.data.ApiSettings
import com.example.rokidphone.data.AvailableModels
import com.example.rokidphone.data.SettingsRepository
import com.example.rokidphone.data.VisualTranslationLanguages
import com.example.rokidphone.data.db.ConversationRepository
import com.example.rokidphone.data.db.RecordingRepository
import com.example.rokidphone.service.ai.AiServiceFactory
import com.example.rokidphone.service.ai.AiServiceProvider
import com.example.rokidphone.service.ai.GeminiLiveSession
import com.example.rokidphone.service.cxr.CxrMobileManager
import com.example.rokidphone.service.stt.SttProvider
import com.example.rokidphone.service.stt.SttService
import com.example.rokidphone.service.stt.SttServiceFactory
import com.example.rokidphone.data.toSttCredentials
import com.example.rokidphone.service.ServiceBridge.notifyApiKeyMissing
import com.example.rokidphone.service.photo.PhotoData
import com.example.rokidphone.service.photo.PhotoRepository
import com.example.rokidphone.service.photo.ReceivedPhoto
import com.rokid.cxr.client.utils.ValueUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Phone AI Service
 * Receives voice commands from glasses, processes AI computation, returns results
 * 
 * Architecture:
 * 1. Glasses record -> Send audio via Bluetooth to phone
 * 2. Phone performs speech recognition (Gemini API)
 * 3. Phone performs AI conversation (Gemini API)
 * 4. Phone sends results back to glasses via Bluetooth for display
 */
class PhoneAIService : Service() {
    
    companion object {
        private const val TAG = "PhoneAIService"
        private const val VISUAL_TRANSLATION_FRAME_INTERVAL_MS = 3000L
        private const val VISUAL_TRANSLATION_RESULT_HOLD_MS = 20000L
        private const val VISUAL_TRANSLATION_VIEW_SETTLE_MS = 2500L
        private const val VISUAL_TRANSLATION_FRAME_HASH_SIMILAR_BITS = 24
        private const val CUSTOM_VISUAL_TRANSLATION_ROTATION_DEGREES = 90
    }

    private enum class PhotoAnalysisMode {
        DESCRIPTION,
        VISUAL_TRANSLATION
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // AI service (supports multiple providers)
    private var aiService: AiServiceProvider? = null
    
    // Speech recognition service (may differ from chat service)
    private var speechService: AiServiceProvider? = null
    
    // Dedicated STT service (for specialized STT providers like Deepgram, Azure, etc.)
    private var sttService: SttService? = null
    
    // TTS service
    private var ttsService: TextToSpeechService? = null
    
    // Bluetooth manager (legacy SPP connection)
    private var bluetoothManager: BluetoothSppManager? = null
    
    // CXR-M SDK Manager (for Rokid glasses connection and photo capture)
    private var cxrManager: CxrMobileManager? = null
    
    // Gemini Live session (real-time bidirectional voice)
    private var liveSession: GeminiLiveSession? = null
    
    // Photo repository for managing received photos
    private var photoRepository: PhotoRepository? = null
    
    // Conversation repository for persisting voice conversations
    private var conversationRepository: ConversationRepository? = null
    
    // Recording repository for saving glasses recordings
    private var recordingRepository: RecordingRepository? = null
    
    // Current voice conversation ID (for grouping voice interactions)
    private var currentVoiceConversationId: String? = null
    
    // Track recording IDs currently being processed to prevent duplicate transcription
    private val processingRecordingIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private var nextPhotoAnalysisMode: PhotoAnalysisMode = PhotoAnalysisMode.DESCRIPTION
    private var isVisualTranslationActive = false
    private var isVisualTranslationFrameInFlight = false
    private var lastVisualTranslationFrameMs = 0L
    private var lastVisualTranslationText = ""
    private var lastVisualTranslationNormalizedText = ""
    private var lastVisualTranslationSuccessMs = 0L
    private var lastVisualTranslationSeenFrameHash: Long? = null
    private var lastVisualTranslationSceneChangedMs = 0L
    private var lastVisualTranslationAnalyzedFrameHash: Long? = null

    private fun updatePipeline(
        title: String,
        detail: String,
        progress: Float,
        severity: ServiceBridge.PipelineSeverity
    ) {
        ServiceBridge.updatePipelineStatus(
            ServiceBridge.PipelineStatus(
                title = title,
                detail = detail,
                progress = progress,
                severity = severity
            )
        )
    }

    private fun validateGeminiKeyForStatus(settings: ApiSettings) {
        val geminiKey = settings.geminiApiKey.trim()
        val fingerprint = if (geminiKey.length >= 8) {
            "${geminiKey.take(4)}:${geminiKey.takeLast(4)}:${geminiKey.length}"
        } else {
            "missing"
        }

        if (fingerprint == lastValidatedGeminiKeyFingerprint) return
        lastValidatedGeminiKeyFingerprint = fingerprint

        if (geminiKey.isBlank()) {
            updatePipeline(
                title = "Gemini key missing",
                detail = "No Gemini key found in app settings or build-time .env.",
                progress = 1f,
                severity = ServiceBridge.PipelineSeverity.ERROR
            )
            return
        }

        updatePipeline(
            title = "Checking Gemini key",
            detail = "Validating the configured key with Google AI",
            progress = 0.2f,
            severity = ServiceBridge.PipelineSeverity.WORKING
        )

        serviceScope.launch {
            val result = validateGeminiApiKey(geminiKey)
            if (result == null) {
                updatePipeline(
                    title = "Gemini key valid",
                    detail = "Ready for image translation and transcription",
                    progress = 1f,
                    severity = ServiceBridge.PipelineSeverity.SUCCESS
                )
            } else {
                updatePipeline(
                    title = "Gemini key invalid",
                    detail = result,
                    progress = 1f,
                    severity = ServiceBridge.PipelineSeverity.ERROR
                )
            }
        }
    }

    private suspend fun validateGeminiApiKey(apiKey: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
                .get()
                .build()

            keyValidationClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) return@withContext null

                val body = response.body?.string()
                val apiMessage = try {
                    JSONObject(body ?: "{}")
                        .optJSONObject("error")
                        ?.optString("message")
                        ?.takeIf { it.isNotBlank() }
                } catch (e: Exception) {
                    null
                }

                apiMessage ?: "Google AI rejected the key with HTTP ${response.code}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gemini key validation failed", e)
            "Could not validate key: ${e.message ?: "network error"}"
        }
    }
    
    private val _messageFlow = MutableSharedFlow<Message>()
    val messageFlow = _messageFlow.asSharedFlow()

    private val keyValidationClient = OkHttpClient()
    private var lastValidatedGeminiKeyFingerprint: String? = null
    
    override fun onCreate() {
        super.onCreate()
        initializeServices()
        startForeground(Constants.NOTIFICATION_ID, createNotification())
        
        // Notify UI service has started (immediate state update)
        ServiceBridge.updateServiceState(true)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Notify UI service has stopped (immediate state update)
        ServiceBridge.updateServiceState(false)
        ServiceBridge.updateVisualTranslationActive(false)
        
        serviceScope.cancel()
        liveSession?.release()
        liveSession = null
        bluetoothManager?.disconnect()
        cxrManager?.release()
        ttsService?.shutdown()
        sttService?.release()
    }
    
    private fun initializeServices() {
        Log.d(TAG, "Initializing services...")
        
        try {
            // Get settings
            val settingsRepository = SettingsRepository.getInstance(this)
            val settings = settingsRepository.getSettings()
            
            // Initialize conversation repository for persisting voice conversations
            conversationRepository = ConversationRepository.getInstance(this)
            
            // Create or get the current voice conversation session
            serviceScope.launch {
                ensureVoiceConversationSession(settings)
            }
            
            // Validate model and provider compatibility
            val validatedSettings = validateAndCorrectSettings(settings)
            
            // Use factory to create AI service
            aiService = createAiService(validatedSettings)
            Log.d(TAG, "AI service created: ${aiService != null}")
            validateGeminiKeyForStatus(validatedSettings)
            
            // Set speech recognition service (prefer providers supporting STT)
            speechService = createSpeechService(validatedSettings)
            Log.d(TAG, "Speech service created: ${speechService != null}")
            
            // Create dedicated STT service if a specialized provider is selected
            sttService = createSttService(validatedSettings)
            Log.d(TAG, "Dedicated STT service created: ${sttService != null}, provider: ${validatedSettings.sttProvider}")
            
            Log.d(TAG, "Using AI provider: ${validatedSettings.aiProvider}, model: ${validatedSettings.aiModelId}")
            
            // Monitor settings changes
            serviceScope.launch {
                settingsRepository.settingsFlow.collect { newSettings ->
                    Log.d(TAG, "Settings changed, updating services...")
                    val validatedNewSettings = validateAndCorrectSettings(newSettings)
                    aiService = createAiService(validatedNewSettings)
                    speechService = createSpeechService(validatedNewSettings)
                    sttService = createSttService(validatedNewSettings)
                    validateGeminiKeyForStatus(validatedNewSettings)
                    
                    // Handle Live mode transitions
                    handleLiveModeTransition(validatedNewSettings)
                    
                    Log.d(TAG, "Services updated: ${validatedNewSettings.aiProvider}, STT: ${validatedNewSettings.sttProvider}")
                }
            }
            
            // Initialize TTS
            ttsService = TextToSpeechService(this)
            Log.d(TAG, "TTS service initialized")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AI services, but continuing with Bluetooth", e)
        }
        
        // Initialize Bluetooth manager (independent of AI services)
        try {
            bluetoothManager = BluetoothSppManager(this, serviceScope)
            
            // Start listening for Bluetooth connections (only if permission granted)
            if (bluetoothManager?.hasBluetoothPermission() == true) {
                bluetoothManager?.startListening()
                Log.d(TAG, "Bluetooth manager started listening")
            } else {
                Log.w(TAG, "Bluetooth permission not granted, waiting for permission")
            }
            
            // Monitor Bluetooth connection state
            serviceScope.launch {
                try {
                    bluetoothManager?.connectionState?.collect { state ->
                        Log.d(TAG, "Bluetooth state: $state")
                        ServiceBridge.updateBluetoothState(state)
                        
                        // Update notification
                        updateNotification(state)
                        
                        // Initialize CXR Bluetooth when SPP connected
                        if (state == BluetoothConnectionState.CONNECTED) {
                            bluetoothManager?.connectedDevice?.let { device ->
                                Log.d(TAG, "Initializing CXR Bluetooth with device: ${device.name}")
                                cxrManager?.initBluetooth(device)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in Bluetooth state collector", e)
                }
            }
            
            // Monitor connected device name
            serviceScope.launch {
                try {
                    bluetoothManager?.connectedDeviceName?.collect { name ->
                        Log.d(TAG, "Connected device name: $name")
                        ServiceBridge.updateConnectedDeviceName(name)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in device name collector", e)
                }
            }
            
            // Listen for messages from glasses
            serviceScope.launch {
                try {
                    bluetoothManager?.messageFlow?.collect { message ->
                        handleGlassesMessage(message)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in message flow collector", e)
                }
            }
            
            // Initialize photo repository
            photoRepository = PhotoRepository(this, serviceScope)
            
            // Initialize recording repository for saving glasses recordings
            recordingRepository = RecordingRepository.getInstance(this, serviceScope)
            
            // Listen for received photos from glasses
            serviceScope.launch {
                try {
                    bluetoothManager?.receivedPhoto?.collect { receivedPhoto ->
                        handleReceivedPhoto(receivedPhoto)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in received photo collector", e)
                }
            }
            
            // Monitor photo transfer state
            serviceScope.launch {
                try {
                    bluetoothManager?.photoTransferState?.collect { state ->
                        handlePhotoTransferState(state)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in photo transfer state collector", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Bluetooth manager", e)
        }
        
        // Listen for send-to-glasses requests from text chat
        serviceScope.launch {
            ServiceBridge.sendToGlassesFlow.collect { message ->
                Log.d(TAG, "Forwarding message to glasses: type=${message.type}")
                bluetoothManager?.sendMessage(message)
            }
        }

        // Listen for capture photo requests from UI
        serviceScope.launch {
            ServiceBridge.capturePhotoFlow.collect {
                Log.d(TAG, "Capture photo request from UI")
                nextPhotoAnalysisMode = PhotoAnalysisMode.DESCRIPTION
                requestGlassesToCapturePhoto()
            }
        }

        // Listen for visual translation capture requests from UI
        serviceScope.launch {
            ServiceBridge.captureTranslationPhotoFlow.collect {
                Log.d(TAG, "Visual translation photo request from UI")
                nextPhotoAnalysisMode = PhotoAnalysisMode.VISUAL_TRANSLATION
                requestGlassesToCapturePhoto()
            }
        }

        serviceScope.launch {
            ServiceBridge.startVisualTranslationFlow.collect {
                startVisualTranslation()
            }
        }

        serviceScope.launch {
            ServiceBridge.stopVisualTranslationFlow.collect {
                stopVisualTranslation()
            }
        }
        
        // Listen for connection control requests from UI
        serviceScope.launch {
            ServiceBridge.startListeningFlow.collect {
                Log.d(TAG, "Start listening request from UI")
                bluetoothManager?.let { manager ->
                    // Restart Bluetooth listening
                    manager.stopListening()
                    kotlinx.coroutines.delay(300) // Wait for socket cleanup
                    if (manager.hasBluetoothPermission()) {
                        manager.startListening()
                        Log.d(TAG, "Bluetooth listening restarted")
                    }
                }
            }
        }
        
        serviceScope.launch {
            ServiceBridge.disconnectFlow.collect {
                Log.d(TAG, "Disconnect request from UI")
                bluetoothManager?.disconnect(restartListening = true)
            }
        }
        
        // Listen for transcription requests from UI (phone recordings)
        serviceScope.launch {
            try {
                ServiceBridge.transcribeRecordingFlow.collect { request ->
                    Log.d(TAG, "Transcription request received: ${request.recordingId}")
                    processPhoneRecording(request.recordingId, request.filePath)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in transcription request collector", e)
            }
        }
        
        // Listen for glasses recording start requests from UI
        serviceScope.launch {
            try {
                ServiceBridge.startGlassesRecordingFlow.collect { recordingId ->
                    Log.d(TAG, "Glasses recording start command: $recordingId")
                    bluetoothManager?.sendMessage(
                        Message(type = MessageType.REMOTE_RECORD_START, payload = recordingId)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in glasses recording start collector", e)
            }
        }
        
        // Listen for glasses recording stop requests from UI
        serviceScope.launch {
            try {
                ServiceBridge.stopGlassesRecordingFlow.collect {
                    Log.d(TAG, "Glasses recording stop command")
                    bluetoothManager?.sendMessage(
                        Message(type = MessageType.REMOTE_RECORD_STOP)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in glasses recording stop collector", e)
            }
        }
        
        // Initialize CXR-M SDK (for Rokid glasses photo capture)
        initializeCxrSdk()
    }
    
    /**
     * Request glasses to capture and send photo via SPP
     */
    private suspend fun requestGlassesToCapturePhoto() {
        if (bluetoothManager?.connectionState?.value != BluetoothConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot capture photo: not connected to glasses")
            return
        }
        
        // Check API key before sending capture command
        val settingsRepository = SettingsRepository.getInstance(this)
        val apiKey = settingsRepository.getSettings().geminiApiKey
        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is not configured, aborting photo capture")
            // Notify UI to show API key warning dialog
            notifyApiKeyMissing()
            // Also send error message to glasses
            bluetoothManager?.sendMessage(Message.aiError("API key not configured. Please set up an API key in Settings."))
            return
        }
        
        Log.d(TAG, "Sending CAPTURE_PHOTO command to glasses")
        bluetoothManager?.sendMessage(Message(type = MessageType.CAPTURE_PHOTO))
    }
    
    /**
     * Initialize CXR-M SDK for Rokid glasses connection
     * This enables:
     * - AI key event listening (long press on glasses)
     * - Remote photo capture from glasses
     */
    private fun initializeCxrSdk() {
        if (!CxrMobileManager.isSdkAvailable()) {
            Log.w(TAG, "CXR-M SDK not available")
            return
        }
        
        try {
            cxrManager = CxrMobileManager(this)
            
            // Set AI event listener for glasses key press
            cxrManager?.setAiEventListener(
                onKeyDown = {
                    Log.d(TAG, "CXR: AI key pressed on glasses")
                    // Trigger photo capture when AI key is pressed
                    serviceScope.launch {
                        capturePhotoFromGlasses()
                    }
                },
                onKeyUp = {
                    Log.d(TAG, "CXR: AI key released")
                },
                onExit = {
                    Log.d(TAG, "CXR: AI scene exited")
                }
            )
            
            // Monitor CXR Bluetooth connection state
            serviceScope.launch {
                cxrManager?.bluetoothState?.collect { state ->
                    Log.d(TAG, "CXR Bluetooth state: $state")
                    when (state) {
                        is CxrMobileManager.BluetoothState.Connected -> {
                            Log.d(TAG, "CXR connected: ${state.macAddress}")
                        }
                        is CxrMobileManager.BluetoothState.Disconnected -> {
                            Log.d(TAG, "CXR disconnected")
                        }
                        is CxrMobileManager.BluetoothState.Failed -> {
                            Log.e(TAG, "CXR connection failed: ${state.error}")
                        }
                        else -> {}
                    }
                }
            }
            
            Log.d(TAG, "CXR-M SDK initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CXR-M SDK", e)
        }
    }
    
    /**
     * Capture photo from glasses using CXR-M SDK
     */
    private suspend fun capturePhotoFromGlasses() {
        val cxr = cxrManager ?: run {
            Log.w(TAG, "CXR manager not available, using legacy photo transfer")
            return
        }
        
        if (!cxr.isBluetoothConnected()) {
            Log.w(TAG, "CXR not connected to glasses")
            bluetoothManager?.sendMessage(Message.aiError(getString(R.string.glasses_not_connected_cxr)))
            return
        }
        
        // Check API key before triggering photo capture
        val settingsRepository = SettingsRepository.getInstance(this)
        val apiKey = settingsRepository.getSettings().geminiApiKey
        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is not configured, aborting photo capture")
            bluetoothManager?.sendMessage(Message.aiError("API key not configured. Please set up an API key in Settings."))
            return
        }
        
        Log.d(TAG, "Capturing photo from glasses via CXR SDK...")
        
        // Notify glasses: taking photo
        cxr.sendTtsContent(getString(R.string.taking_photo))
        
        // Take photo using CXR SDK
        val status = cxr.takePhoto(
            width = 1280,
            height = 720,
            quality = 80
        ) { resultStatus, photoData ->
            serviceScope.launch {
                when (resultStatus) {
                    ValueUtil.CxrStatus.RESPONSE_SUCCEED -> {
                        if (photoData != null && photoData.isNotEmpty()) {
                            Log.d(TAG, "CXR photo received: ${photoData.size} bytes")
                            handleCxrPhotoResult(photoData)
                        } else {
                            Log.e(TAG, "CXR photo is empty")
                            bluetoothManager?.sendMessage(Message.aiError(getString(R.string.photo_empty)))
                        }
                    }
                    ValueUtil.CxrStatus.RESPONSE_TIMEOUT -> {
                        Log.e(TAG, "CXR photo timeout")
                        bluetoothManager?.sendMessage(Message.aiError(getString(R.string.photo_timeout)))
                    }
                    else -> {
                        Log.e(TAG, "CXR photo failed: $resultStatus")
                        bluetoothManager?.sendMessage(Message.aiError(getString(R.string.photo_capture_failed, resultStatus)))
                    }
                }
            }
        }
        
        Log.d(TAG, "CXR takePhoto request status: $status")
    }
    
    /**
     * Handle photo captured via CXR SDK
     */
    private suspend fun handleCxrPhotoResult(photoData: ByteArray) {
        try {
            Log.d(TAG, "Processing CXR photo: ${photoData.size} bytes")
            
            // Create a ReceivedPhoto object
            val receivedPhoto = ReceivedPhoto(
                data = photoData,
                timestamp = System.currentTimeMillis(),
                transferTimeMs = 0  // Direct capture, no transfer time
            )
            
            // Process the photo
            handleReceivedPhoto(receivedPhoto)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process CXR photo", e)
            bluetoothManager?.sendMessage(Message.aiError(getString(R.string.photo_process_failed, e.message ?: "")))
        }
    }
    
    private suspend fun handleGlassesMessage(message: Message) {
        Log.d(TAG, "Received message from glasses: ${message.type}")
        
        when (message.type) {
            MessageType.VOICE_END -> {
                // Voice input ended, audio data is in binaryData
                message.binaryData?.let { audioData ->
                    Log.d(TAG, "Processing voice data: ${audioData.size} bytes")
                    
                    // If Live mode is active, audio is already streamed in real-time.
                    // VOICE_END only signals end-of-turn for the live session.
                    if (liveSession != null && liveSession?.sessionState?.value == GeminiLiveSession.SessionState.ACTIVE) {
                        Log.d(TAG, "Live mode active, signaling end of turn")
                        liveSession?.endOfTurn()
                    } else {
                        processVoiceData(audioData)
                    }
                }
            }
            MessageType.VOICE_START -> {
                Log.d(TAG, "Voice recording started on glasses")
                
                // In Live mode, audio is streamed in real-time — no STT step needed
                if (liveSession != null && liveSession?.sessionState?.value == GeminiLiveSession.SessionState.ACTIVE) {
                    Log.d(TAG, "Live mode active, audio streams directly to Gemini")
                    return
                }
                
                // Check STT service availability before allowing recording
                if (sttService == null && speechService == null) {
                    Log.e(TAG, "STT service not available - API key not configured")
                    val errorMsg = getString(R.string.service_not_ready)
                    bluetoothManager?.sendMessage(Message.aiError(errorMsg))
                    ServiceBridge.emitConversation(Message(
                        type = MessageType.AI_ERROR,
                        payload = errorMsg
                    ))
                    notifyApiKeyMissing()
                    return
                }
                
                // Notify phone UI
                ServiceBridge.emitConversation(Message(
                    type = MessageType.AI_PROCESSING,
                    payload = getString(R.string.glasses_recording)
                ))
            }
            MessageType.HEARTBEAT -> {
                bluetoothManager?.sendMessage(Message(type = MessageType.HEARTBEAT_ACK))
            }
            // Receive real-time video frames from glasses and forward to Gemini Live Session
            MessageType.VIDEO_FRAME -> {
                message.binaryData?.let { frameData ->
                    if (isVisualTranslationActive) {
                        handleVisualTranslationFrame(frameData)
                    } else if (liveSession != null && liveSession?.sessionState?.value == GeminiLiveSession.SessionState.ACTIVE) {
                        Log.d(TAG, "Forwarding video frame to Live session: ${frameData.size} bytes")
                        liveSession?.sendVideoFrame(frameData)
                    } else {
                        Log.w(TAG, "Received VIDEO_FRAME but Live session is not active")
                    }
                }
            }
            // Receive real-time transcription text from glasses and forward to phone UI
            MessageType.LIVE_TRANSCRIPTION -> {
                message.payload?.let { text ->
                    Log.d(TAG, "Received live transcription from glasses: $text")
                    ServiceBridge.emitConversation(Message(
                        type = MessageType.LIVE_TRANSCRIPTION,
                        payload = text
                    ))
                }
            }
            else -> { 
                Log.d(TAG, "Unhandled message type: ${message.type}")
            }
        }
    }
    
    /**
     * Handle received photo from glasses
     */
    private suspend fun handleReceivedPhoto(receivedPhoto: ReceivedPhoto) {
        Log.d(TAG, "Received photo: ${receivedPhoto.data.size} bytes, transfer time: ${receivedPhoto.transferTimeMs}ms")
        
        // Process and store the photo
        val photoData = photoRepository?.processReceivedPhoto(receivedPhoto)
        
        if (photoData != null) {
            Log.d(TAG, "Photo saved: ${photoData.filePath}")
            
            // Notify UI about the photo path for display
            ServiceBridge.emitLatestPhotoPath(photoData.filePath)
            
            // Notify UI that a photo was received
            ServiceBridge.emitConversation(Message(
                type = MessageType.AI_PROCESSING,
                payload = getString(R.string.photo_received)
            ))
            
            // Analyze the photo with AI
            analyzePhotoWithAI(photoData)
        } else {
            Log.e(TAG, "Failed to process received photo")
            bluetoothManager?.sendMessage(Message.aiError(getString(R.string.photo_processing_failed)))
        }
    }
    
    /**
     * Handle photo transfer state changes
     */
    private fun handlePhotoTransferState(state: PhotoTransferState) {
        when (state) {
            is PhotoTransferState.Idle -> {
                Log.d(TAG, "Photo transfer: Idle")
            }
            is PhotoTransferState.InProgress -> {
                Log.d(TAG, "Photo transfer: ${state.currentChunk}/${state.totalChunks} " +
                        "(${state.progressPercent.toInt()}%)")
            }
            is PhotoTransferState.Success -> {
                Log.d(TAG, "Photo transfer: Success (${state.data.size} bytes)")
            }
            is PhotoTransferState.Error -> {
                Log.e(TAG, "Photo transfer error: ${state.message}")
                serviceScope.launch {
                    bluetoothManager?.sendMessage(Message.aiError(
                        getString(R.string.photo_transfer_failed, state.message)
                    ))
                }
            }
        }
    }
    
    /**
     * Analyze photo with AI and send results back to glasses
     */
    private suspend fun analyzePhotoWithAI(photoData: PhotoData) {
        try {
            // Get photo bytes for AI analysis
            val photoBytes = photoRepository?.getPhotoBytes(photoData) ?: return
            
            Log.d(TAG, "Analyzing photo with AI: ${photoBytes.size} bytes")
            
            // Notify glasses: analyzing photo
            bluetoothManager?.sendMessage(Message.aiProcessing(getString(R.string.analyzing_photo)))
            
            ServiceBridge.emitConversation(Message(
                type = MessageType.AI_PROCESSING,
                payload = getString(R.string.analyzing_photo)
            ))

            val analysisMode = nextPhotoAnalysisMode
            nextPhotoAnalysisMode = PhotoAnalysisMode.DESCRIPTION

            val analysisResult = when (analysisMode) {
                PhotoAnalysisMode.DESCRIPTION -> {
                    aiService?.analyzeImage(photoBytes, getString(R.string.image_analysis_prompt))
                        ?: getString(R.string.ai_analysis_unavailable)
                }
                PhotoAnalysisMode.VISUAL_TRANSLATION -> {
                    analyzePhotoForTranslationMultiPass(photoBytes)
                }
            }
            
            // Clean markdown for glasses display
            val cleanedResult = cleanMarkdown(analysisResult)
            
            Log.d(TAG, "Photo analysis result: $cleanedResult")
            
            // Update photo data with analysis result
            photoData.analysisResult = cleanedResult
            
            // Send result to glasses
            bluetoothManager?.sendMessage(Message(
                type = MessageType.PHOTO_ANALYSIS_RESULT,
                payload = cleanedResult
            ))
            
            // Notify phone UI
            ServiceBridge.emitConversation(Message(
                type = MessageType.AI_RESPONSE_TEXT,
                payload = cleanedResult
            ))
            
            // TTS voice playback on glasses
            speakOnGlasses(cleanedResult)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to analyze photo", e)
            bluetoothManager?.sendMessage(Message.aiError(
                getString(R.string.photo_analysis_failed, e.message ?: "")
            ))
        }
    }

    private suspend fun startVisualTranslation() {
        if (bluetoothManager?.connectionState?.value != BluetoothConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot start visual translation: not connected to glasses")
            return
        }

        val settingsRepository = SettingsRepository.getInstance(this)
        val apiKey = settingsRepository.getSettings().geminiApiKey
        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is not configured, aborting visual translation")
            updatePipeline(
                title = "Visual translation blocked",
                detail = "Gemini API key is missing. Add it to .env and rebuild, or enter it in Settings.",
                progress = 0f,
                severity = ServiceBridge.PipelineSeverity.ERROR
            )
            notifyApiKeyMissing()
            bluetoothManager?.sendMessage(Message.aiError("API key not configured. Please set up an API key in Settings."))
            return
        }

        Log.d(TAG, "Starting continuous visual translation")
        val visualLanguage = VisualTranslationLanguages.displayName(settingsRepository.getSettings().visualTranslationSourceLanguage)
        updatePipeline(
            title = "Visual translation starting",
            detail = "Requesting camera frames from glasses ($visualLanguage to English)",
            progress = 0.1f,
            severity = ServiceBridge.PipelineSeverity.WORKING
        )
        isVisualTranslationActive = true
        ServiceBridge.updateVisualTranslationActive(true)
        lastVisualTranslationFrameMs = 0L
        lastVisualTranslationText = ""
        lastVisualTranslationNormalizedText = ""
        lastVisualTranslationSuccessMs = 0L
        lastVisualTranslationSeenFrameHash = null
        lastVisualTranslationSceneChangedMs = 0L
        lastVisualTranslationAnalyzedFrameHash = null
        bluetoothManager?.sendMessage(Message(type = MessageType.VISUAL_TRANSLATION_START))
        bluetoothManager?.sendMessage(Message.aiProcessing("Live visual translation active: $visualLanguage to English"))
    }

    private suspend fun stopVisualTranslation() {
        Log.d(TAG, "Stopping continuous visual translation")
        updatePipeline(
            title = "Visual translation stopped",
            detail = "Camera frame translation is off",
            progress = 0f,
            severity = ServiceBridge.PipelineSeverity.IDLE
        )
        isVisualTranslationActive = false
        ServiceBridge.updateVisualTranslationActive(false)
        isVisualTranslationFrameInFlight = false
        bluetoothManager?.sendMessage(Message(type = MessageType.VISUAL_TRANSLATION_END))
    }

    private fun handleVisualTranslationFrame(frameData: ByteArray) {
        val now = System.currentTimeMillis()
        if (lastVisualTranslationSuccessMs > 0 && now - lastVisualTranslationSuccessMs < VISUAL_TRANSLATION_RESULT_HOLD_MS) {
            val remainingSeconds = ((VISUAL_TRANSLATION_RESULT_HOLD_MS - (now - lastVisualTranslationSuccessMs)) / 1000L)
                .coerceAtLeast(1L)
            updatePipeline(
                title = "Reading timer",
                detail = "Holding translation for ${remainingSeconds}s before the next API call",
                progress = remainingSeconds.toFloat() / (VISUAL_TRANSLATION_RESULT_HOLD_MS / 1000f),
                severity = ServiceBridge.PipelineSeverity.SUCCESS
            )
            return
        }

        if (isVisualTranslationFrameInFlight || now - lastVisualTranslationFrameMs < VISUAL_TRANSLATION_FRAME_INTERVAL_MS) {
            return
        }

        lastVisualTranslationFrameMs = now
        isVisualTranslationFrameInFlight = true

        serviceScope.launch {
            try {
                Log.d(TAG, "Analyzing visual translation frame: ${frameData.size} bytes")
                val receivedFramePath = saveVisualTranslationDebugFrame(frameData, "latest_received.jpg", "received")
                val receivedFrameMeta = visualTranslationFrameMeta(frameData)
                ServiceBridge.updateVisualTranslationDebug(
                    ServiceBridge.VisualTranslationDebugInfo(
                        receivedFramePath = receivedFramePath,
                        receivedFrameMeta = receivedFrameMeta,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )

                val currentFrameHash = visualTranslationFrameHash(frameData)
                if (currentFrameHash != null) {
                    val previousFrameHash = lastVisualTranslationSeenFrameHash
                    if (previousFrameHash == null) {
                        lastVisualTranslationSeenFrameHash = currentFrameHash
                        lastVisualTranslationSceneChangedMs = now
                        updatePipeline(
                            title = "View settling",
                            detail = "Camera view changed; waiting briefly before translating",
                            progress = 0.35f,
                            severity = ServiceBridge.PipelineSeverity.WORKING
                        )
                        return@launch
                    }

                    if (!areVisualTranslationFramesSimilar(previousFrameHash, currentFrameHash)) {
                        lastVisualTranslationSeenFrameHash = currentFrameHash
                        if (lastVisualTranslationSceneChangedMs == 0L) {
                            lastVisualTranslationSceneChangedMs = now
                        }
                    }

                    if (now - lastVisualTranslationSceneChangedMs < VISUAL_TRANSLATION_VIEW_SETTLE_MS) {
                        updatePipeline(
                            title = "View settling",
                            detail = "Waiting for scrolling or motion to stop",
                            progress = 0.35f,
                            severity = ServiceBridge.PipelineSeverity.WORKING
                        )
                        return@launch
                    }

                    val analyzedFrameHash = lastVisualTranslationAnalyzedFrameHash
                    if (lastVisualTranslationText.isNotBlank() &&
                        analyzedFrameHash != null &&
                        areVisualTranslationFramesSimilar(analyzedFrameHash, currentFrameHash)
                    ) {
                        updatePipeline(
                            title = "Rechecking view",
                            detail = "Reading timer ended; sending the stable view again",
                            progress = 0.4f,
                            severity = ServiceBridge.PipelineSeverity.WORKING
                        )
                    }
                }

                val settings = SettingsRepository.getInstance(this@PhoneAIService).getSettings()
                val providerLabel = if (settings.aiProvider == AiProvider.CUSTOM) {
                    "Custom/${settings.customModelName.ifBlank { settings.aiModelId }}"
                } else {
                    settings.aiProvider.name
                }
                val orientedFrameData = if (settings.aiProvider == AiProvider.CUSTOM) {
                    rotateJpegFrame(frameData, CUSTOM_VISUAL_TRANSLATION_ROTATION_DEGREES)
                } else {
                    frameData
                }
                val analysisFrameData = prepareVisualTranslationImage(orientedFrameData)
                val visualLanguage = settings.visualTranslationSourceLanguage
                val analyzedFramePath = saveVisualTranslationDebugFrame(analysisFrameData, "latest_analyzed.jpg", "analyzed")
                val analyzedFrameMeta = visualTranslationFrameMeta(analysisFrameData)

                updatePipeline(
                    title = "Frame received",
                    detail = "Sending camera frame to $providerLabel for translation",
                    progress = 0.45f,
                    severity = ServiceBridge.PipelineSeverity.WORKING
                )
                ServiceBridge.updateVisualTranslationDebug(
                    ServiceBridge.VisualTranslationDebugInfo(
                        receivedFramePath = receivedFramePath,
                        analyzedFramePath = analyzedFramePath,
                        receivedFrameMeta = receivedFrameMeta,
                        analyzedFrameMeta = analyzedFrameMeta,
                        providerLabel = providerLabel,
                        sourceLanguage = VisualTranslationLanguages.displayName(visualLanguage),
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
                val result = aiService?.analyzeImage(analysisFrameData, buildVisualTranslationPrompt(visualLanguage))
                    ?: getString(R.string.ai_analysis_unavailable)
                val cleanedResult = cleanMarkdown(result)
                Log.d(TAG, "Visual translation result from $providerLabel: ${cleanedResult.take(160)}")
                ServiceBridge.updateVisualTranslationDebug(
                    ServiceBridge.VisualTranslationDebugInfo(
                        receivedFramePath = receivedFramePath,
                        analyzedFramePath = analyzedFramePath,
                        receivedFrameMeta = receivedFrameMeta,
                        analyzedFrameMeta = analyzedFrameMeta,
                        providerLabel = providerLabel,
                        sourceLanguage = VisualTranslationLanguages.displayName(visualLanguage),
                        rawResponse = cleanedResult,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
                if (currentFrameHash != null) {
                    lastVisualTranslationAnalyzedFrameHash = currentFrameHash
                }

                if (cleanedResult.contains("API key not valid", ignoreCase = true)) {
                    updatePipeline(
                        title = "API key rejected",
                        detail = "The app reached $providerLabel, but the API key is invalid in this APK/settings.",
                        progress = 1f,
                        severity = ServiceBridge.PipelineSeverity.ERROR
                    )
                    isVisualTranslationActive = false
                    ServiceBridge.updateVisualTranslationActive(false)
                    bluetoothManager?.sendMessage(Message.aiError("$providerLabel API key invalid. Rebuild from .env or update Settings."))
                    bluetoothManager?.sendMessage(Message(type = MessageType.VISUAL_TRANSLATION_END))
                    return@launch
                }

                if (isNoVisualTranslationResult(cleanedResult, visualLanguage)) {
                    ServiceBridge.updateVisualTranslationDebug(
                        ServiceBridge.VisualTranslationDebugInfo(
                            receivedFramePath = receivedFramePath,
                            analyzedFramePath = analyzedFramePath,
                            receivedFrameMeta = receivedFrameMeta,
                            analyzedFrameMeta = analyzedFrameMeta,
                            providerLabel = providerLabel,
                            sourceLanguage = VisualTranslationLanguages.displayName(visualLanguage),
                            rawResponse = cleanedResult,
                            skipReason = "No readable matching text",
                            updatedAtMs = System.currentTimeMillis()
                        )
                    )
                    updatePipeline(
                        title = "Frame skipped",
                        detail = "No readable matching text in this frame; keeping the last translation",
                        progress = 0.75f,
                        severity = ServiceBridge.PipelineSeverity.WORKING
                    )
                    return@launch
                }

                val normalizedResult = normalizeVisualTranslationText(cleanedResult)
                if (normalizedResult.isNotBlank() && normalizedResult == lastVisualTranslationNormalizedText) {
                    lastVisualTranslationSuccessMs = System.currentTimeMillis()
                    updatePipeline(
                        title = "Translation held",
                        detail = "Same text still visible; keeping the current translation",
                        progress = 1f,
                        severity = ServiceBridge.PipelineSeverity.SUCCESS
                    )
                    return@launch
                }

                if (cleanedResult.isNotBlank()) {
                    lastVisualTranslationText = cleanedResult
                    lastVisualTranslationNormalizedText = normalizedResult
                    lastVisualTranslationSuccessMs = System.currentTimeMillis()
                    updatePipeline(
                        title = "Translation updated",
                        detail = cleanedResult.take(120),
                        progress = 1f,
                        severity = ServiceBridge.PipelineSeverity.SUCCESS
                    )
                    bluetoothManager?.sendMessage(Message.aiResponseText(cleanedResult))
                    ServiceBridge.emitConversation(Message(
                        type = MessageType.AI_RESPONSE_TEXT,
                        payload = cleanedResult
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Visual translation frame failed", e)
                updatePipeline(
                    title = "Translation failed",
                    detail = e.message ?: "Unexpected visual translation error",
                    progress = 1f,
                    severity = ServiceBridge.PipelineSeverity.ERROR
                )
            } finally {
                isVisualTranslationFrameInFlight = false
            }
        }
    }

    private fun normalizeVisualTranslationText(text: String): String {
        return text
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun rotateJpegFrame(frameData: ByteArray, rotationDegrees: Int): ByteArray {
        if (rotationDegrees % 360 == 0) return frameData

        return try {
            val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.size) ?: return frameData
            val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            ByteArrayOutputStream().use { output ->
                rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, output)
                if (rotated != bitmap) {
                    bitmap.recycle()
                }
                rotated.recycle()
                output.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to rotate custom visual translation frame", e)
            frameData
        }
    }

    /**
     * Multi-pass photo translation:
     *   1. Full rotated frame as-is.
     *   2. Same frame upscaled 1.5x (more pixels per glyph, helps fine text).
     *   3. 60% center zoom, upscaled to 1280 wide (~1.67x zoom on the middle of the view).
     *   4. 35% center zoom, upscaled to 1280 wide (~2.86x zoom, last-ditch detail pass).
     * Short-circuits on the first pass whose response is *not* a "no translatable text"
     * boilerplate. If every pass comes back empty, returns the last "no text" string.
     */
    private suspend fun analyzePhotoForTranslationMultiPass(photoData: ByteArray): String {
        val rotated = rotateJpegFrame(photoData, -90)
        val passes: List<Pair<String, () -> ByteArray>> = listOf(
            "pass1_full" to { rotated },
            "pass2_upscale" to { upscaleJpeg(rotated, 1.5f) },
            "pass3_zoom60" to { centerZoomAndUpscale(rotated, 0.60f, 1280) },
            "pass4_zoom35" to { centerZoomAndUpscale(rotated, 0.35f, 1280) }
        )

        val prompt = buildPhotoTranslationPrompt()
        val service = aiService ?: return getString(R.string.ai_analysis_unavailable)

        var lastResult: String = "No translatable text visible."
        for ((tag, transform) in passes) {
            val bytes = transform()
            saveLatestVisualTranslationFrame(bytes, "latest_photo_translation_${tag}.jpg")
            Log.d(TAG, "Photo translation $tag: ${visualTranslationFrameMeta(bytes)}")
            val raw = try {
                service.analyzeImage(bytes, prompt)
            } catch (e: Exception) {
                Log.w(TAG, "Photo translation $tag failed: ${e.message}")
                continue
            }
            val cleaned = cleanMarkdown(raw).trim()
            Log.d(TAG, "Photo translation $tag result: ${cleaned.take(160)}")
            lastResult = cleaned.ifBlank { lastResult }
            if (cleaned.isNotBlank() && !isNoTranslatableTextResponse(cleaned)) {
                return cleaned
            }
        }
        return lastResult
    }

    private fun isNoTranslatableTextResponse(text: String): Boolean {
        val normalized = text.lowercase().trim().trimEnd('.', '!', '?').trim()
        return normalized.startsWith("no translatable text") ||
            normalized.startsWith("no readable matching text") ||
            normalized.startsWith("no readable text") ||
            normalized.startsWith("no text visible")
    }

    private fun upscaleJpeg(jpegData: ByteArray, scaleFactor: Float): ByteArray {
        if (scaleFactor <= 1.0f) return jpegData
        return try {
            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size) ?: return jpegData
            val targetWidth = (bitmap.width * scaleFactor).toInt().coerceAtLeast(1)
            val targetHeight = (bitmap.height * scaleFactor).toInt().coerceAtLeast(1)
            val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            ByteArrayOutputStream().use { output ->
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)
                if (scaled != bitmap) bitmap.recycle()
                scaled.recycle()
                output.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to upscale JPEG", e)
            jpegData
        }
    }

    private fun centerZoomAndUpscale(jpegData: ByteArray, cropRatio: Float, targetWidth: Int): ByteArray {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size) ?: return jpegData
            val cropWidth = (bitmap.width * cropRatio).toInt().coerceIn(1, bitmap.width)
            val cropHeight = (bitmap.height * cropRatio).toInt().coerceIn(1, bitmap.height)
            val left = ((bitmap.width - cropWidth) / 2).coerceAtLeast(0)
            val top = ((bitmap.height - cropHeight) / 2).coerceAtLeast(0)
            val cropped = android.graphics.Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
            val scale = targetWidth.toFloat() / cropWidth.toFloat()
            val scaledHeight = (cropHeight * scale).toInt().coerceAtLeast(1)
            val scaled = android.graphics.Bitmap.createScaledBitmap(cropped, targetWidth, scaledHeight, true)
            ByteArrayOutputStream().use { output ->
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)
                if (cropped != bitmap) bitmap.recycle()
                if (scaled != cropped) cropped.recycle()
                scaled.recycle()
                output.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to crop+upscale JPEG", e)
            jpegData
        }
    }

    private fun prepareVisualTranslationImage(frameData: ByteArray): ByteArray {
        val analyzed = extractAndEnhanceReadableSurfaceRoi(frameData, "Live visual translation")
        Log.d(
            TAG,
            "Live visual translation model input prepared: oriented=${visualTranslationFrameMeta(frameData)} " +
                "analyzed=${visualTranslationFrameMeta(analyzed)}"
        )
        return analyzed
    }

    private fun extractAndEnhanceReadableSurfaceRoi(photoData: ByteArray, label: String): ByteArray {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(photoData, 0, photoData.size) ?: return photoData
            val screenBox = detectBrightScreenRegion(bitmap)
            val cropped = if (screenBox != null) {
                android.graphics.Bitmap.createBitmap(
                    bitmap,
                    screenBox.left,
                    screenBox.top,
                    screenBox.width(),
                    screenBox.height()
                )
            } else {
                bitmap
            }
            val analysisBitmap = buildReadableSurfaceAnalysisBitmap(cropped)
            val enhanced = enhanceReadableTextSurface(analysisBitmap)
            ByteArrayOutputStream().use { output ->
                enhanced.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, output)
                if (bitmap != cropped) bitmap.recycle()
                if (cropped != analysisBitmap) cropped.recycle()
                if (analysisBitmap != enhanced) analysisBitmap.recycle()
                enhanced.recycle()
                output.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prepare $label ROI", e)
            photoData
        }
    }

    private fun detectBrightScreenRegion(bitmap: android.graphics.Bitmap): android.graphics.Rect? {
        val width = bitmap.width
        val height = bitmap.height
        val maxSide = 360
        val sampleScale = minOf(
            1f,
            maxSide.toFloat() / width.toFloat(),
            maxSide.toFloat() / height.toFloat()
        )
        val sampleWidth = (width * sampleScale).toInt().coerceAtLeast(1)
        val sampleHeight = (height * sampleScale).toInt().coerceAtLeast(1)
        val sampleBitmap = if (sampleWidth == width && sampleHeight == height) {
            bitmap
        } else {
            android.graphics.Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true)
        }

        val pixelBuffer = IntArray(sampleWidth * sampleHeight)
        sampleBitmap.getPixels(pixelBuffer, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)

        val brightMask = BooleanArray(sampleWidth * sampleHeight)
        var i = 0
        while (i < pixelBuffer.size) {
            val pixel = pixelBuffer[i]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            val luminance = red * 0.299f + green * 0.587f + blue * 0.114f
            val channelSpread = maxOf(red, green, blue) - minOf(red, green, blue)
            brightMask[i] = luminance > 145f && channelSpread < 90
            i++
        }

        val visited = BooleanArray(brightMask.size)
        val queueX = IntArray(brightMask.size)
        val queueY = IntArray(brightMask.size)
        var bestScore = 0f
        var bestMinX = 0
        var bestMinY = 0
        var bestMaxX = -1
        var bestMaxY = -1
        var bestPixels = 0

        var y = 0
        while (y < sampleHeight) {
            var x = 0
            while (x < sampleWidth) {
                val index = y * sampleWidth + x
                if (brightMask[index] && !visited[index]) {
                    var head = 0
                    var tail = 0
                    queueX[tail] = x
                    queueY[tail] = y
                    tail++
                    visited[index] = true

                    var minX = x
                    var minY = y
                    var maxX = x
                    var maxY = y
                    var pixels = 0

                    while (head < tail) {
                        val cx = queueX[head]
                        val cy = queueY[head]
                        head++
                        pixels++
                        minX = minOf(minX, cx)
                        minY = minOf(minY, cy)
                        maxX = maxOf(maxX, cx)
                        maxY = maxOf(maxY, cy)

                        var direction = 0
                        while (direction < 4) {
                            val nx = when (direction) {
                                0 -> cx + 1
                                1 -> cx - 1
                                else -> cx
                            }
                            val ny = when (direction) {
                                2 -> cy + 1
                                3 -> cy - 1
                                else -> cy
                            }
                            if (nx in 0 until sampleWidth && ny in 0 until sampleHeight) {
                                val neighborIndex = ny * sampleWidth + nx
                                if (brightMask[neighborIndex] && !visited[neighborIndex]) {
                                    visited[neighborIndex] = true
                                    queueX[tail] = nx
                                    queueY[tail] = ny
                                    tail++
                                }
                            }
                            direction++
                        }
                    }

                    val boxWidth = maxX - minX + 1
                    val boxHeight = maxY - minY + 1
                    val boxArea = boxWidth * boxHeight
                    val minSurfaceArea = sampleWidth * sampleHeight * 0.035f
                    if (pixels > 60 && boxArea > minSurfaceArea) {
                        val score = pixels + boxArea * 0.25f
                        if (score > bestScore) {
                            bestScore = score
                            bestPixels = pixels
                            bestMinX = minX
                            bestMinY = minY
                            bestMaxX = maxX
                            bestMaxY = maxY
                        }
                    }
                }
                x++
            }
            y++
        }

        if (sampleBitmap != bitmap) {
            sampleBitmap.recycle()
        }

        if (bestMaxX <= bestMinX || bestMaxY <= bestMinY) {
            Log.d(TAG, "Readable surface ROI: no connected bright screen/object region detected")
            return null
        }

        val inverseScale = 1f / sampleScale
        val minX = (bestMinX * inverseScale).toInt()
        val minY = (bestMinY * inverseScale).toInt()
        val maxX = ((bestMaxX + 1) * inverseScale).toInt().coerceAtMost(width)
        val maxY = ((bestMaxY + 1) * inverseScale).toInt().coerceAtMost(height)
        val boxWidth = maxX - minX
        val boxHeight = maxY - minY
        val padX = (boxWidth * 0.06f).toInt() + 20
        val padY = (boxHeight * 0.06f).toInt() + 20
        val rect = android.graphics.Rect(
            (minX - padX).coerceAtLeast(0),
            (minY - padY).coerceAtLeast(0),
            (maxX + padX).coerceAtMost(width),
            (maxY + padY).coerceAtMost(height)
        )
        Log.d(TAG, "Readable surface ROI: ${width}x${height} -> ${rect.width()}x${rect.height()} pixels=$bestPixels")
        return rect
    }

    private fun scaleForReadableTextSurface(bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        val targetWidth = 1600
        val width = bitmap.width
        val height = bitmap.height
        if (width == targetWidth) return bitmap
        val scale = targetWidth.toFloat() / width.toFloat()
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return android.graphics.Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun buildReadableSurfaceAnalysisBitmap(bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        val fullSurface = scaleForReadableTextSurface(bitmap)
        val zoomSource = createReadableTextClusterCrop(bitmap) ?: createReadableCenterCrop(bitmap)
        val zoomSurface = scaleForReadableTextSurface(zoomSource)
        val separatorHeight = 16
        val targetWidth = maxOf(fullSurface.width, zoomSurface.width)
        val result = android.graphics.Bitmap.createBitmap(
            targetWidth,
            fullSurface.height + separatorHeight + zoomSurface.height,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(result)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(fullSurface, 0f, 0f, null)
        val separatorPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(32, 32, 32)
        }
        canvas.drawRect(
            0f,
            fullSurface.height.toFloat(),
            targetWidth.toFloat(),
            (fullSurface.height + separatorHeight).toFloat(),
            separatorPaint
        )
        canvas.drawBitmap(zoomSurface, 0f, (fullSurface.height + separatorHeight).toFloat(), null)

        if (fullSurface != bitmap) fullSurface.recycle()
        if (zoomSource != bitmap) zoomSource.recycle()
        if (zoomSurface != zoomSource) zoomSurface.recycle()
        return result
    }

    private fun createReadableTextClusterCrop(bitmap: android.graphics.Bitmap): android.graphics.Bitmap? {
        val region = detectDenseTextRegion(bitmap) ?: return null
        if (region.width() < bitmap.width * 0.12f || region.height() < bitmap.height * 0.04f) {
            return null
        }

        return android.graphics.Bitmap.createBitmap(
            bitmap,
            region.left,
            region.top,
            region.width(),
            region.height()
        )
    }

    private fun createReadableCenterCrop(bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 480 || height < 360) return bitmap

        val left = (width * 0.12f).toInt().coerceIn(0, width - 1)
        val top = (height * 0.12f).toInt().coerceIn(0, height - 1)
        val right = (width * 0.98f).toInt().coerceIn(left + 1, width)
        val bottom = (height * 0.78f).toInt().coerceIn(top + 1, height)
        val cropWidth = right - left
        val cropHeight = bottom - top
        if (cropWidth < width * 0.35f || cropHeight < height * 0.25f) return bitmap

        return android.graphics.Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    }

    private fun detectDenseTextRegion(bitmap: android.graphics.Bitmap): android.graphics.Rect? {
        val width = bitmap.width
        val height = bitmap.height
        val maxSide = 520
        val sampleScale = minOf(
            1f,
            maxSide.toFloat() / width.toFloat(),
            maxSide.toFloat() / height.toFloat()
        )
        val sampleWidth = (width * sampleScale).toInt().coerceAtLeast(1)
        val sampleHeight = (height * sampleScale).toInt().coerceAtLeast(1)
        val sampleBitmap = if (sampleWidth == width && sampleHeight == height) {
            bitmap
        } else {
            android.graphics.Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true)
        }

        val pixelBuffer = IntArray(sampleWidth * sampleHeight)
        sampleBitmap.getPixels(pixelBuffer, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
        val isDarkPixel = BooleanArray(pixelBuffer.size)
        val isBrightPixel = BooleanArray(pixelBuffer.size)
        var p = 0
        while (p < pixelBuffer.size) {
            val luminance = pixelLuminance(pixelBuffer[p])
            isDarkPixel[p] = luminance < 145f
            isBrightPixel[p] = luminance > 170f
            p++
        }

        val darkMask = BooleanArray(sampleWidth * sampleHeight)
        val neighborhoodRadius = (sampleWidth / 160).coerceAtLeast(3)
        val minYBound = sampleHeight * 0.05f
        val maxYBound = sampleHeight * 0.95f
        var y = 0
        while (y < sampleHeight) {
            var x = 0
            val rowStart = y * sampleWidth
            val inYBounds = y > minYBound && y < maxYBound
            while (x < sampleWidth) {
                val idx = rowStart + x
                darkMask[idx] = isDarkPixel[idx] && inYBounds &&
                    hasBrightNeighbor(isBrightPixel, sampleWidth, sampleHeight, x, y, neighborhoodRadius)
                x++
            }
            y++
        }

        val dilatedMask = BooleanArray(darkMask.size)
        val dilateX = (sampleWidth / 55).coerceAtLeast(3)
        val dilateY = (sampleHeight / 95).coerceAtLeast(2)
        y = 0
        while (y < sampleHeight) {
            var x = 0
            while (x < sampleWidth) {
                if (darkMask[y * sampleWidth + x]) {
                    var yy = (y - dilateY).coerceAtLeast(0)
                    val maxY = (y + dilateY).coerceAtMost(sampleHeight - 1)
                    while (yy <= maxY) {
                        var xx = (x - dilateX).coerceAtLeast(0)
                        val maxX = (x + dilateX).coerceAtMost(sampleWidth - 1)
                        while (xx <= maxX) {
                            dilatedMask[yy * sampleWidth + xx] = true
                            xx++
                        }
                        yy++
                    }
                }
                x++
            }
            y++
        }

        val visited = BooleanArray(dilatedMask.size)
        val queueX = IntArray(dilatedMask.size)
        val queueY = IntArray(dilatedMask.size)
        var bestScore = 0f
        var bestMinX = 0
        var bestMinY = 0
        var bestMaxX = -1
        var bestMaxY = -1
        var bestPixels = 0

        y = 0
        while (y < sampleHeight) {
            var x = 0
            while (x < sampleWidth) {
                val index = y * sampleWidth + x
                if (dilatedMask[index] && !visited[index]) {
                    var head = 0
                    var tail = 0
                    queueX[tail] = x
                    queueY[tail] = y
                    tail++
                    visited[index] = true

                    var minX = x
                    var minY = y
                    var maxX = x
                    var maxY = y
                    var pixels = 0

                    while (head < tail) {
                        val cx = queueX[head]
                        val cy = queueY[head]
                        head++
                        pixels++
                        minX = minOf(minX, cx)
                        minY = minOf(minY, cy)
                        maxX = maxOf(maxX, cx)
                        maxY = maxOf(maxY, cy)

                        var direction = 0
                        while (direction < 4) {
                            val nx = when (direction) {
                                0 -> cx + 1
                                1 -> cx - 1
                                else -> cx
                            }
                            val ny = when (direction) {
                                2 -> cy + 1
                                3 -> cy - 1
                                else -> cy
                            }
                            if (nx in 0 until sampleWidth && ny in 0 until sampleHeight) {
                                val neighborIndex = ny * sampleWidth + nx
                                if (dilatedMask[neighborIndex] && !visited[neighborIndex]) {
                                    visited[neighborIndex] = true
                                    queueX[tail] = nx
                                    queueY[tail] = ny
                                    tail++
                                }
                            }
                            direction++
                        }
                    }

                    val boxWidth = maxX - minX + 1
                    val boxHeight = maxY - minY + 1
                    val boxArea = boxWidth * boxHeight
                    if (pixels > 40 &&
                        boxArea > sampleWidth * sampleHeight * 0.0025f &&
                        boxWidth > sampleWidth * 0.08f &&
                        boxHeight > sampleHeight * 0.02f
                    ) {
                        val centerX = (minX + maxX) / 2f / sampleWidth
                        val centerY = (minY + maxY) / 2f / sampleHeight
                        val centralBonus = (1f - minOf(kotlin.math.abs(centerX - 0.55f), 0.55f) / 0.55f) * 900f
                        val bottomPenalty = if (centerY > 0.78f) (centerY - 0.78f) * 2600f else 0f
                        val topChromePenalty = if (centerY < 0.16f && boxHeight < sampleHeight * 0.09f) 700f else 0f
                        val shapeBonus = if (boxWidth > sampleWidth * 0.22f && boxHeight > sampleHeight * 0.05f) 600f else 0f
                        val score = pixels * 1.35f + boxArea * 0.16f + centralBonus + shapeBonus - bottomPenalty - topChromePenalty
                        if (score > bestScore) {
                            bestScore = score
                            bestPixels = pixels
                            bestMinX = minX
                            bestMinY = minY
                            bestMaxX = maxX
                            bestMaxY = maxY
                        }
                    }
                }
                x++
            }
            y++
        }

        if (sampleBitmap != bitmap) {
            sampleBitmap.recycle()
        }

        if (bestMaxX <= bestMinX || bestMaxY <= bestMinY) {
            Log.d(TAG, "Readable text cluster ROI: no dense text cluster detected")
            return null
        }

        val inverseScale = 1f / sampleScale
        val minX = (bestMinX * inverseScale).toInt()
        val minY = (bestMinY * inverseScale).toInt()
        val maxX = ((bestMaxX + 1) * inverseScale).toInt().coerceAtMost(width)
        val maxY = ((bestMaxY + 1) * inverseScale).toInt().coerceAtMost(height)
        val boxWidth = maxX - minX
        val boxHeight = maxY - minY
        val padX = (boxWidth * 0.22f).toInt() + 30
        val padY = (boxHeight * 0.45f).toInt() + 30
        val rect = android.graphics.Rect(
            (minX - padX).coerceAtLeast(0),
            (minY - padY).coerceAtLeast(0),
            (maxX + padX).coerceAtMost(width),
            (maxY + padY).coerceAtMost(height)
        )
        Log.d(TAG, "Readable text cluster ROI: ${width}x${height} -> ${rect.width()}x${rect.height()} pixels=$bestPixels")
        return rect
    }

    private fun pixelLuminance(pixel: Int): Float {
        val red = android.graphics.Color.red(pixel)
        val green = android.graphics.Color.green(pixel)
        val blue = android.graphics.Color.blue(pixel)
        return red * 0.299f + green * 0.587f + blue * 0.114f
    }

    private fun hasBrightNeighbor(
        isBrightPixel: BooleanArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        radius: Int
    ): Boolean {
        val left = (x - radius).coerceAtLeast(0)
        val right = (x + radius).coerceAtMost(width - 1)
        val top = (y - radius).coerceAtLeast(0)
        val bottom = (y + radius).coerceAtMost(height - 1)
        return isBrightPixel[top * width + left] ||
            isBrightPixel[top * width + right] ||
            isBrightPixel[bottom * width + left] ||
            isBrightPixel[bottom * width + right] ||
            isBrightPixel[y * width + x]
    }

    private fun enhanceReadableTextSurface(bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        val result = android.graphics.Bitmap.createBitmap(bitmap.width, bitmap.height, android.graphics.Bitmap.Config.ARGB_8888)
        val contrast = 1.25f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val colorMatrix = android.graphics.ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
        }
        android.graphics.Canvas(result).drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    private fun visualTranslationFrameHash(frameData: ByteArray): Long? {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.size) ?: return null
            val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 8, 8, true)
            if (scaled != bitmap) {
                bitmap.recycle()
            }

            val luminance = IntArray(64)
            var total = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val pixel = scaled.getPixel(x, y)
                    val red = (pixel shr 16) and 0xff
                    val green = (pixel shr 8) and 0xff
                    val blue = pixel and 0xff
                    val value = (red * 30 + green * 59 + blue * 11) / 100
                    val index = y * 8 + x
                    luminance[index] = value
                    total += value
                }
            }
            scaled.recycle()

            val average = total / luminance.size
            var hash = 0L
            luminance.forEachIndexed { index, value ->
                if (value >= average) {
                    hash = hash or (1L shl index)
                }
            }
            hash
        } catch (e: Exception) {
            Log.w(TAG, "Failed to hash visual translation frame", e)
            null
        }
    }

    private fun areVisualTranslationFramesSimilar(first: Long, second: Long): Boolean {
        return java.lang.Long.bitCount(first xor second) <= VISUAL_TRANSLATION_FRAME_HASH_SIMILAR_BITS
    }

    private fun isNoVisualTranslationResult(result: String, sourceLanguageCode: String): Boolean {
        if (result.isBlank()) return true

        val normalized = result
            .trim()
            .trim('.', '!', '?')
            .lowercase()

        val expectedNoText = if (sourceLanguageCode == VisualTranslationLanguages.AUTO) {
            "no translatable text visible"
        } else {
            "no ${VisualTranslationLanguages.displayName(sourceLanguageCode).lowercase()} text visible"
        }

        return normalized == expectedNoText ||
            normalized == "no translatable text visible" ||
            normalized.startsWith("no translatable text") ||
            normalized.startsWith("no matching text") ||
            normalized.startsWith("no readable text") ||
            normalized.contains("text visible") && normalized.startsWith("no ")
    }

    private fun saveLatestVisualTranslationFrame(frameData: ByteArray, fileName: String): String? {
        return try {
            val frameDir = java.io.File(filesDir, "live_visual_frames").apply { mkdirs() }
            java.io.File(frameDir, fileName).apply {
                writeBytes(frameData)
            }.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save latest visual translation frame", e)
            null
        }
    }

    private fun saveVisualTranslationDebugFrame(
        frameData: ByteArray,
        latestFileName: String,
        uniquePrefix: String
    ): String? {
        return try {
            val frameDir = java.io.File(filesDir, "live_visual_frames").apply { mkdirs() }
            java.io.File(frameDir, latestFileName).writeBytes(frameData)

            val uniqueFileName = "${uniquePrefix}_${System.currentTimeMillis()}.jpg"
            java.io.File(frameDir, uniqueFileName).apply {
                writeBytes(frameData)
            }.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save visual translation debug frame", e)
            null
        }
    }

    private fun visualTranslationFrameMeta(frameData: ByteArray): String {
        val dimensions = try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(frameData, 0, frameData.size, options)
            if (options.outWidth > 0 && options.outHeight > 0) {
                "${options.outWidth}x${options.outHeight}"
            } else {
                "unknown"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read visual translation frame dimensions", e)
            "unknown"
        }
        return "$dimensions, ${frameData.size / 1024} KB"
    }

    private fun buildVisualTranslationPrompt(sourceLanguageCode: String): String {
        val sourceText = VisualTranslationLanguages.promptSource(sourceLanguageCode)
        val noTextMessage = if (sourceLanguageCode == VisualTranslationLanguages.AUTO) {
            "No translatable text visible."
        } else {
            "No ${VisualTranslationLanguages.displayName(sourceLanguageCode)} text visible."
        }

        return "This is a live camera frame from smart glasses worn by an English speaker. " +
            "Ignore the wearer's own English monitor, phone, UI chrome, and notes unless they contain $sourceText. " +
            "Scan the whole frame for $sourceText on signs, labels, menus, packages, books, screens, pages, or any other surface. " +
            "Translate only the matching non-English text into natural English for the glasses display. " +
            "If the image includes both a full view and an enlarged crop of the same surface, use whichever view is clearer and do not translate duplicate text twice. " +
            "If there are multiple signs or lines, keep the same order with short line breaks. " +
            "If some matching text is readable, translate the clear parts and skip only the unreadable parts. " +
            "Only if no matching non-English text is truly readable anywhere in the frame, say exactly: $noTextMessage"
    }

    private fun buildPhotoTranslationPrompt(): String {
        return "This is a photo from smart glasses worn by an English speaker. " +
            "The frame likely also contains the wearer's own computer monitor, phone screen, or notes in English — ignore all of that. " +
            "Scan the entire image for any non-English text (Japanese, Chinese, Korean, Spanish, German, French, Arabic, Russian, etc.) on signs, labels, menus, packages, books, foreign-language screens, or any other surface. " +
            "Translate only that non-English text into natural English. Return only the English translation for the glasses display, one line per source line, preserving the original order. " +
            "If part of the non-English text is clear and part is blurry, translate the clear parts and skip the unclear ones — do not refuse the whole image just because some text is unreadable. " +
            "Only if you genuinely cannot find any non-English text anywhere in the frame, respond with exactly: No translatable text visible."
    }
    
    /**
     * Process voice data received from glasses
     */
    private suspend fun processVoiceData(audioData: ByteArray) {
        try {
            val recording = try {
                recordingRepository?.saveGlassesRecording(audioData = audioData)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save raw glasses recording", e)
                null
            }

            // Check if any speech service is available
            if (sttService == null && speechService == null) {
                Log.e(TAG, "Speech service not available - no API key configured")
                val errorMsg = getString(R.string.service_not_ready)
                bluetoothManager?.sendMessage(Message.aiError(errorMsg))
                ServiceBridge.emitConversation(Message(
                    type = MessageType.AI_ERROR,
                    payload = errorMsg
                ))
                // Notify UI to show settings prompt
                notifyApiKeyMissing()
                return
            }
            
            // 1. Notify glasses: recognizing
            bluetoothManager?.sendMessage(Message.aiProcessing(getString(R.string.recognizing_speech)))
            
            // 2. Speech recognition - prefer dedicated STT service if available
            Log.d(TAG, "Starting speech recognition...")
            val settings = SettingsRepository.getInstance(this).getSettings()
            val transcriptResult = if (sttService != null) {
                Log.d(TAG, "Using dedicated STT service: ${sttService?.provider?.name}")
                sttService?.transcribe(audioData, settings.speechLanguage)
            } else {
                Log.d(TAG, "Using AI-based speech service, language: ${settings.speechLanguage}")
                speechService?.transcribe(audioData, settings.speechLanguage)
            }
            
            val transcript = when (transcriptResult) {
                is SpeechResult.Success -> {
                    Log.d(TAG, "Transcript: ${transcriptResult.text}")
                    transcriptResult.text
                }
                is SpeechResult.Error -> {
                    Log.e(TAG, "Transcription error: ${transcriptResult.message}")
                    bluetoothManager?.sendMessage(Message.aiError(transcriptResult.message))
                    // Check if error is API key related
                    if (transcriptResult.message.contains("API", ignoreCase = true) ||
                        transcriptResult.message.contains("key", ignoreCase = true) ||
                        transcriptResult.message.contains("401") ||
                        transcriptResult.message.contains("403")) {
                        notifyApiKeyMissing()
                    }
                    return
                }
                null -> {
                    val errorMsg = getString(R.string.service_not_ready)
                    bluetoothManager?.sendMessage(Message.aiError(errorMsg))
                    notifyApiKeyMissing()
                    return
                }
            }
            
            // 3. Send user voice text to glasses and phone UI
            bluetoothManager?.sendMessage(Message(
                type = MessageType.USER_TRANSCRIPT,
                payload = transcript
            ))
            
            ServiceBridge.emitConversation(Message(
                type = MessageType.USER_TRANSCRIPT,
                payload = transcript
            ))
            
            // 3.1 Save user message to database for history
            saveUserMessage(transcript)
            
            // 4. Notify thinking
            bluetoothManager?.sendMessage(Message.aiProcessing(getString(R.string.thinking)))
            
            // 5. AI conversation (using main AI service)
            Log.d(TAG, "Getting AI response...")
            val rawAiResponse = aiService?.chat(transcript) ?: "Sorry, an error occurred while processing."
            
            // Clean markdown formatting for better display on glasses
            val aiResponse = cleanMarkdown(rawAiResponse)
            
            Log.d(TAG, "AI response: $aiResponse")
            
            // 6. Send AI response to glasses and phone UI
            bluetoothManager?.sendMessage(Message.aiResponseText(aiResponse))
            
            ServiceBridge.emitConversation(Message(
                type = MessageType.AI_RESPONSE_TEXT,
                payload = aiResponse
            ))
            
            // 6.1 Save AI response to database for history
            saveAssistantMessage(aiResponse, settings.aiModelId)
            
            // 6.2 Update saved glasses recording with transcript and AI response
            try {
                if (recording != null) {
                    recordingRepository?.updateTranscriptAndAiResponse(
                        id = recording.id,
                        transcript = transcript,
                        aiResponse = aiResponse,
                        providerId = settings.aiProvider.name,
                        modelId = settings.aiModelId
                    )
                    Log.d(TAG, "Glasses recording updated with transcript and AI response")
                } else {
                    recordingRepository?.saveGlassesRecording(
                        audioData = audioData,
                        transcript = transcript,
                        aiResponse = aiResponse,
                        providerId = settings.aiProvider.name,
                        modelId = settings.aiModelId
                    )
                    Log.d(TAG, "Glasses recording saved to database")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save glasses recording", e)
            }
            
            // 7. TTS voice playback on glasses (optional)
            speakOnGlasses(aiResponse)
            
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // Service is being stopped, don't treat this as an error
            Log.d(TAG, "Voice processing cancelled (service stopping)")
            throw e  // Re-throw to properly propagate cancellation
        } catch (e: Exception) {
            Log.e(TAG, "Error processing voice data", e)
            bluetoothManager?.sendMessage(Message.aiError(getString(R.string.processing_failed, e.message ?: "")))
        }
    }
    
    // ========== Gemini Live Mode ==========
    
    /**
     * Handle provider transitions to/from Live mode.
     * Called whenever settings change.
     */
    private fun handleLiveModeTransition(settings: ApiSettings) {
        if (settings.aiProvider == AiProvider.GEMINI_LIVE) {
            // Start Live session if not already active
            if (liveSession == null || liveSession?.sessionState?.value == GeminiLiveSession.SessionState.IDLE
                || liveSession?.sessionState?.value == GeminiLiveSession.SessionState.ERROR) {
                startLiveSession(settings)
            }
        } else {
            // Stop Live session if switching away from Live mode
            if (liveSession != null) {
                Log.d(TAG, "Switching away from Live mode, stopping session")
                liveSession?.release()
                liveSession = null
                
                // Notify glasses that live session ended
                serviceScope.launch {
                    bluetoothManager?.sendMessage(Message(type = MessageType.LIVE_SESSION_END))
                }
            }
        }
    }
    
    /**
     * Start a Gemini Live session for real-time bidirectional voice.
     */
    private fun startLiveSession(settings: ApiSettings) {
        val apiKey = settings.geminiApiKey
        if (apiKey.isBlank()) {
            Log.e(TAG, "Cannot start Live session: Gemini API key not configured")
            serviceScope.launch { notifyApiKeyMissing() }
            return
        }
        
        Log.d(TAG, "Starting Gemini Live session")
        
        liveSession = GeminiLiveSession(
            context = this,
            apiKey = apiKey,
            modelId = settings.aiModelId.ifBlank { "gemini-2.5-flash-preview-native-audio-dialog" },
            systemPrompt = buildSystemPromptWithLanguage(settings.systemPrompt, settings.responseLanguage)
        )
        
        // Collect Live session events
        collectLiveSessionEvents()
        
        // Start the session
        val started = liveSession?.start() ?: false
        if (started) {
            Log.d(TAG, "Live session started successfully")
            serviceScope.launch {
                bluetoothManager?.sendMessage(Message(type = MessageType.LIVE_SESSION_START))
            }
        } else {
            Log.e(TAG, "Failed to start Live session")
        }
    }
    
    /**
     * Collect event flows from the Live session and forward to glasses/UI.
     */
    private fun collectLiveSessionEvents() {
        val session = liveSession ?: return
        
        // User speech transcription
        serviceScope.launch {
            session.inputTranscription.collect { text ->
                Log.d(TAG, "Live input transcription: $text")
                bluetoothManager?.sendMessage(Message(
                    type = MessageType.LIVE_TRANSCRIPTION,
                    payload = text
                ))
                ServiceBridge.emitConversation(Message(
                    type = MessageType.USER_TRANSCRIPT,
                    payload = text
                ))
                saveUserMessage(text)
            }
        }
        
        // AI response transcription
        serviceScope.launch {
            session.outputTranscription.collect { text ->
                Log.d(TAG, "Live output transcription: $text")
                bluetoothManager?.sendMessage(Message(
                    type = MessageType.AI_RESPONSE_TEXT,
                    payload = text
                ))
                ServiceBridge.emitConversation(Message(
                    type = MessageType.AI_RESPONSE_TEXT,
                    payload = text
                ))
                val settings = SettingsRepository.getInstance(this@PhoneAIService).getSettings()
                saveAssistantMessage(text, settings.aiModelId)
            }
        }
        
        // Turn complete
        serviceScope.launch {
            session.turnComplete.collect {
                Log.d(TAG, "Live turn complete")
            }
        }
        
        // Interrupted
        serviceScope.launch {
            session.interrupted.collect {
                Log.d(TAG, "Live session interrupted by user")
            }
        }
        
        // Session state changes
        serviceScope.launch {
            session.sessionState.collect { state ->
                Log.d(TAG, "Live session state: $state")
                when (state) {
                    GeminiLiveSession.SessionState.ERROR -> {
                        val error = session.errorMessage.value ?: "Live session error"
                        Log.e(TAG, "Live session error: $error")
                        serviceScope.launch {
                            bluetoothManager?.sendMessage(Message.aiError(error))
                            bluetoothManager?.sendMessage(Message(type = MessageType.LIVE_SESSION_END))
                        }
                    }
                    GeminiLiveSession.SessionState.IDLE -> {
                        // Session stopped
                    }
                    else -> { /* CONNECTING, ACTIVE, PAUSED, DISCONNECTING */ }
                }
            }
        }
    }
    
    /**
     * Process phone recording - transcribe and analyze with AI
     * Called when user stops recording from phone microphone
     * @param recordingId The ID of the recording in database
     * @param filePath The path to the WAV file
     */
    private suspend fun processPhoneRecording(recordingId: String, filePath: String) {
        // Deduplicate: skip if this recording is already being processed
        if (!processingRecordingIds.add(recordingId)) {
            Log.w(TAG, "Recording $recordingId is already being processed, skipping duplicate request")
            return
        }
        
        try {
            // Early check for empty file path
            if (filePath.isBlank()) {
                Log.w(TAG, "Recording $recordingId has empty file path, skipping")
                return
            }
            
            // Check if already processed in database (prevents duplicate processing)
            val existingRecording = recordingRepository?.getRecordingById(recordingId)
            if (existingRecording != null && 
                !existingRecording.transcript.isNullOrBlank() && 
                !existingRecording.aiResponse.isNullOrBlank()) {
                Log.d(TAG, "Recording $recordingId already has transcript and AI response, skipping duplicate")
                return
            }
            
            // Check settings - should we auto-analyze?
            val settings = SettingsRepository.getInstance(this).getSettings()
            if (!settings.autoAnalyzeRecordings) {
                Log.d(TAG, "Auto-analyze disabled, skipping recording: $recordingId")
                return
            }
            
            Log.d(TAG, "Processing phone recording: $recordingId, path: $filePath")
            
            // Check if any speech service is available
            if (sttService == null && speechService == null) {
                Log.e(TAG, "Speech service not available - no API key configured")
                recordingRepository?.markError(recordingId, getString(R.string.service_not_ready))
                notifyApiKeyMissing()
                return
            }
            
            // Read audio file
            val audioFile = java.io.File(filePath)
            if (!audioFile.exists()) {
                Log.e(TAG, "Recording file not found: $filePath")
                recordingRepository?.markError(recordingId, "File not found")
                return
            }
            
            val audioData = audioFile.readBytes()
            Log.d(TAG, "Read audio file: ${audioData.size} bytes")
            
            // Notify UI and glasses: transcribing
            if (settings.pushRecordingToGlasses) {
                bluetoothManager?.sendMessage(Message.aiProcessing(getString(R.string.recognizing_speech)))
            }
            updatePipeline(
                title = "Audio received",
                detail = "Sending recording to speech recognition",
                progress = 0.35f,
                severity = ServiceBridge.PipelineSeverity.WORKING
            )
            ServiceBridge.emitConversation(Message(
                type = MessageType.AI_PROCESSING,
                payload = getString(R.string.recognizing_speech)
            ))
            
            // 1. Speech recognition
            val transcriptResult = performSpeechRecognition(audioData, filePath, settings.speechLanguage)
            val transcript = extractTranscript(transcriptResult, recordingId, settings) ?: return
            updatePipeline(
                title = "Speech recognized",
                detail = transcript.take(120),
                progress = 0.65f,
                severity = ServiceBridge.PipelineSeverity.SUCCESS
            )
            
            // 2. Update recording with transcript
            recordingRepository?.updateTranscript(recordingId, transcript)
            
            // 3. Notify UI and glasses: analyzing
            if (settings.pushRecordingToGlasses) {
                bluetoothManager?.sendMessage(Message.aiProcessing(getString(R.string.thinking)))
            }
            updatePipeline(
                title = "Asking AI",
                detail = "Generating answer from transcript",
                progress = 0.8f,
                severity = ServiceBridge.PipelineSeverity.WORKING
            )
            ServiceBridge.emitConversation(Message(
                type = MessageType.AI_PROCESSING,
                payload = getString(R.string.thinking)
            ))
            
            // 4. AI conversation
            Log.d(TAG, "Getting AI response for phone recording...")
            val rawAiResponse = aiService?.chat(transcript) ?: getString(R.string.ai_analysis_unavailable)
            val aiResponse = cleanMarkdown(rawAiResponse)
            
            Log.d(TAG, "Phone recording AI response: $aiResponse")
            
            // 5. Update recording with AI response
            recordingRepository?.updateAiResponse(
                id = recordingId,
                response = aiResponse,
                providerId = settings.aiProvider.name,
                modelId = settings.aiModelId
            )
            
            // 6. Send result to glasses (if enabled) and phone UI
            if (settings.pushRecordingToGlasses) {
                bluetoothManager?.sendMessage(Message(type = MessageType.USER_TRANSCRIPT, payload = transcript))
                bluetoothManager?.sendMessage(Message.aiResponseText(aiResponse))
            }
            
            ServiceBridge.emitConversation(Message(
                type = MessageType.USER_TRANSCRIPT,
                payload = transcript
            ))
            ServiceBridge.emitConversation(Message(
                type = MessageType.AI_RESPONSE_TEXT,
                payload = aiResponse
            ))
            
            // 7. Save to conversation history
            saveUserMessage(transcript)
            saveAssistantMessage(aiResponse, settings.aiModelId)
            
            // 8. TTS playback on glasses (optional)
            speakOnGlasses(aiResponse)
            
            Log.d(TAG, "Phone recording processed successfully: $recordingId")
            updatePipeline(
                title = "Recording processed",
                detail = "Transcript and AI response saved",
                progress = 1f,
                severity = ServiceBridge.PipelineSeverity.SUCCESS
            )
            
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            Log.d(TAG, "Phone recording processing cancelled (service stopping)")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error processing phone recording", e)
            notifyProcessingError(recordingId, e.message ?: "Unknown error")
        } finally {
            processingRecordingIds.remove(recordingId)
        }
    }
    
    /**
     * Determine audio MIME type from file extension
     */
    private fun getAudioMimeType(filePath: String): String = when {
        filePath.endsWith(".m4a", ignoreCase = true) || filePath.endsWith(".mp4", ignoreCase = true) -> "audio/mp4"
        filePath.endsWith(".aac", ignoreCase = true) -> "audio/aac"
        filePath.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
        filePath.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
        else -> "audio/wav"
    }
    
    /**
     * Perform speech recognition using the best available STT service
     */
    private suspend fun performSpeechRecognition(
        audioData: ByteArray,
        filePath: String,
        languageCode: String
    ): SpeechResult? {
        val isEncodedAudio = filePath.endsWith(".m4a", ignoreCase = true) ||
                filePath.endsWith(".mp4", ignoreCase = true) ||
                filePath.endsWith(".aac", ignoreCase = true) ||
                filePath.endsWith(".mp3", ignoreCase = true) ||
                filePath.endsWith(".ogg", ignoreCase = true)
        val audioMimeType = getAudioMimeType(filePath)
        
        Log.d(TAG, "Starting speech recognition for phone recording... (encoded=$isEncodedAudio, mimeType=$audioMimeType)")
        
        val stt = sttService
        val speech = speechService
        val serviceName = if (stt != null) "dedicated STT (${stt.provider.name})" else "AI-based speech"
        Log.d(TAG, "Using $serviceName service, language: $languageCode")
        
        return when {
            stt != null && isEncodedAudio -> stt.transcribeAudioFile(audioData, audioMimeType, languageCode)
            stt != null -> stt.transcribe(audioData, languageCode)
            speech != null && isEncodedAudio -> speech.transcribeAudioFile(audioData, audioMimeType, languageCode)
            speech != null -> speech.transcribe(audioData, languageCode)
            else -> null
        }
    }
    
    /**
     * Extract transcript text from SpeechResult, notifying errors as needed.
     * Returns null if transcription failed (caller should return early).
     */
    private suspend fun extractTranscript(
        result: SpeechResult?,
        recordingId: String,
        settings: ApiSettings
    ): String? {
        return when (result) {
            is SpeechResult.Success -> {
                Log.d(TAG, "Phone recording transcript: ${result.text}")
                result.text
            }
            is SpeechResult.Error -> {
                Log.e(TAG, "Phone recording transcription error: ${result.message}")
                notifyRecordingError(recordingId, result.message, settings)
                null
            }
            null -> {
                notifyRecordingError(recordingId, getString(R.string.service_not_ready), settings)
                notifyApiKeyMissing()
                null
            }
        }
    }
    
    /**
     * Notify recording error to database, glasses, and phone UI
     */
    private suspend fun notifyRecordingError(recordingId: String, message: String, settings: ApiSettings) {
        updatePipeline(
            title = "Recording failed",
            detail = message,
            progress = 1f,
            severity = ServiceBridge.PipelineSeverity.ERROR
        )
        recordingRepository?.markError(recordingId, message)
        if (settings.pushRecordingToGlasses) {
            bluetoothManager?.sendMessage(Message.aiError(message))
        }
        ServiceBridge.emitConversation(Message(type = MessageType.AI_ERROR, payload = message))
    }
    
    /**
     * Notify processing error with best-effort error propagation
     */
    private suspend fun notifyProcessingError(recordingId: String, errorMsg: String) {
        recordingRepository?.markError(recordingId, errorMsg)
        try {
            val settings = SettingsRepository.getInstance(this).getSettings()
            if (settings.pushRecordingToGlasses) {
                bluetoothManager?.sendMessage(Message.aiError(errorMsg))
            }
            ServiceBridge.emitConversation(Message(type = MessageType.AI_ERROR, payload = errorMsg))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify error state", e)
        }
    }
    
    /**
     * Clean markdown formatting from AI response for better display
     */
    private fun cleanMarkdown(text: String): String {
        return text
            // Remove bold/italic markers
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")  // **bold**
            .replace(Regex("\\*(.+?)\\*"), "$1")        // *italic*
            .replace(Regex("__(.+?)__"), "$1")          // __bold__
            .replace(Regex("_(.+?)_"), "$1")            // _italic_
            // Remove headers
            .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
            // Remove code blocks
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("`(.+?)`"), "$1")
            // Remove links but keep text
            .replace(Regex("\\[(.+?)]\\(.+?\\)"), "$1")
            // Remove bullet points
            .replace(Regex("^[\\-*+]\\s+", RegexOption.MULTILINE), "• ")
            // Remove numbered lists formatting
            .replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "")
            // Clean up extra whitespace
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
    
    /**
     * Ensure a voice conversation session exists for persisting voice interactions
     * Creates a new conversation if needed, or continues using existing one from today
     */
    private suspend fun ensureVoiceConversationSession(settings: ApiSettings) {
        try {
            if (currentVoiceConversationId == null) {
                // First, try to find an existing voice session from today
                val existingSession = conversationRepository?.findTodayVoiceSession()
                
                if (existingSession != null) {
                    // Reuse existing session from today
                    currentVoiceConversationId = existingSession.id
                    Log.d(TAG, "Reusing existing voice conversation session: $currentVoiceConversationId")
                } else {
                    // Create a new conversation for voice interactions
                    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    val title = getString(R.string.voice_session_title, dateFormat.format(java.util.Date()))
                    
                    val conversation = conversationRepository?.createConversation(
                        providerId = settings.aiProvider.name,
                        modelId = settings.aiModelId,
                        title = title,
                        systemPrompt = settings.systemPrompt
                    )
                    
                    currentVoiceConversationId = conversation?.id
                    Log.d(TAG, "Created voice conversation session: $currentVoiceConversationId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating voice conversation session", e)
        }
    }
    
    /**
     * Save user message to database
     */
    private suspend fun saveUserMessage(content: String) {
        currentVoiceConversationId?.let { conversationId ->
            try {
                conversationRepository?.addUserMessage(conversationId, content)
                Log.d(TAG, "Saved user message to conversation: $conversationId")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving user message", e)
            }
        }
    }
    
    /**
     * Save AI response to database
     */
    private suspend fun saveAssistantMessage(content: String, modelId: String?) {
        currentVoiceConversationId?.let { conversationId ->
            try {
                conversationRepository?.addAssistantMessage(
                    conversationId = conversationId,
                    content = content,
                    modelId = modelId
                )
                Log.d(TAG, "Saved assistant message to conversation: $conversationId")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving assistant message", e)
            }
        }
    }
    
    private fun updateNotification(state: BluetoothConnectionState) {
        val statusText = when (state) {
            BluetoothConnectionState.DISCONNECTED -> getString(R.string.disconnected)
            BluetoothConnectionState.LISTENING -> getString(R.string.waiting_glasses)
            BluetoothConnectionState.CONNECTING -> getString(R.string.connecting)
            BluetoothConnectionState.CONNECTED -> getString(R.string.connected_glasses)
        }
        
        val notification = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(createPendingIntent())
            .setOngoing(true)
            .build()
        
        startForeground(Constants.NOTIFICATION_ID, notification)
    }
    
    private fun createPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Rokid AI Assistant")
            .setContentText("Service running, waiting for glasses connection...")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(createPendingIntent())
            .setOngoing(true)
            .build()
    }
    
    /**
     * Prepend an explicit language-enforcement instruction to [basePrompt].
     *
     * Older / smaller models (e.g. Gemini 2.0 Flash) may ignore a general
     * "respond in the user's language" instruction unless it is prominently placed at
     * the very beginning of the system prompt.  This approach keeps the original
     * user-written base prompt intact while ensuring model compliance.
     *
     * If [responseLanguage] is blank the base prompt is returned unchanged — the model
     * will rely on the conversation language as usual.
     *
     * TODO: Verify via manual testing that the prepended instruction does not conflict
     *       with any user-customised system prompt.
     */
    private fun buildSystemPromptWithLanguage(basePrompt: String, responseLanguage: String): String {
        if (responseLanguage.isBlank()) return basePrompt

        val locale = java.util.Locale.forLanguageTag(responseLanguage)

        // Short English language name used in the closing phrase, e.g. "Korean", "French".
        // Falls back to the raw tag if the JVM returns an empty string for an unknown code.
        val englishLanguageName = locale.getDisplayLanguage(java.util.Locale.ENGLISH)
            .takeIf { it.isNotBlank() } ?: responseLanguage

        // Full English label including region qualifier for disambiguation when needed,
        // e.g. "Chinese (Taiwan)" vs "Chinese (China)", "French (France)".
        val fullEnglishLabel = locale.getDisplayName(java.util.Locale.ENGLISH)
            .takeIf { it.isNotBlank() } ?: englishLanguageName

        // Native self-name, e.g. "한국어", "日本語", "français".
        // Omitted when it is identical to the English name (e.g. for "English").
        val nativeName = locale.getDisplayLanguage(locale)
            .takeIf { it.isNotBlank() && !it.equals(englishLanguageName, ignoreCase = true) }

        val languageLabel = if (nativeName != null) "$fullEnglishLabel ($nativeName)" else fullEnglishLabel

        val langInstruction =
            "CRITICAL INSTRUCTION: You MUST respond ONLY in $languageLabel. " +
            "Never mix in other languages. All responses must be in pure $englishLanguageName.\n\n"

        return langInstruction + basePrompt
    }

    /**
     * Create AI service
     */
    private fun createAiService(settings: ApiSettings): AiServiceProvider {
        // If no API key configured, notify user
        val effectiveSettings = if (settings.getCurrentApiKey().isBlank()) {
            if (settings.aiProvider == AiProvider.CUSTOM && settings.customBaseUrl.isNotBlank()) {
                Log.d(TAG, "Custom provider has no API key; keeping custom provider and using a local placeholder key")
                settings.copy(customApiKey = settings.customApiKey.ifBlank { "local" })
            // Check if BuildConfig has a valid Gemini API key as fallback
            } else if (BuildConfig.GEMINI_API_KEY.isNotBlank()) {
                Log.d(TAG, "No API key for ${settings.aiProvider}, using development fallback")
                settings.copy(
                    aiProvider = AiProvider.GEMINI,
                    aiModelId = "gemini-2.5-flash",
                    geminiApiKey = BuildConfig.GEMINI_API_KEY
                )
            } else {
                // No fallback available - user must configure API key
                Log.w(TAG, "No API key configured. Please set up an API key in Settings.")
                settings
            }
        } else {
            settings
        }
        
        val promptEnhancedSettings = effectiveSettings.copy(
            systemPrompt = buildSystemPromptWithLanguage(
                effectiveSettings.systemPrompt,
                effectiveSettings.responseLanguage
            )
        )
        return AiServiceFactory.createService(promptEnhancedSettings)
    }
    
    /**
     * Create speech recognition service
     * Prefer providers supporting STT
     */
    private fun createSpeechService(settings: ApiSettings): AiServiceProvider? {
        // Check if current provider supports speech recognition
        if (settings.aiProvider.supportsSpeech && settings.getCurrentApiKey().isNotBlank()) {
            Log.d(TAG, "Using current provider ${settings.aiProvider} for STT")
            return AiServiceFactory.createService(settings)
        }
        
        // Try other configured providers that support STT
        val sttProviders = listOf(AiProvider.GEMINI, AiProvider.OPENAI, AiProvider.GROQ, AiProvider.XAI)
        for (provider in sttProviders) {
            val apiKey = settings.getApiKeyForProvider(provider)
            if (apiKey.isNotBlank()) {
                val sttModel = getSttFallbackModelId(provider)
                if (sttModel != null) {
                    Log.d(TAG, "Using ${provider.name} for speech recognition, model: $sttModel")
                    return AiServiceFactory.createService(settings.copy(
                        aiProvider = provider,
                        aiModelId = sttModel
                    ))
                }
            }
        }
        
        // Try fallback to BuildConfig Gemini key
        if (BuildConfig.GEMINI_API_KEY.isNotBlank()) {
            Log.d(TAG, "No STT provider configured, using fallback Gemini")
            return AiServiceFactory.createService(settings.copy(
                aiProvider = AiProvider.GEMINI,
                aiModelId = "gemini-2.5-flash",
                geminiApiKey = BuildConfig.GEMINI_API_KEY
            ))
        }
        
        // No speech service available
        Log.e(TAG, "No speech recognition service available!")
        return null
    }
    
    /**
     * Get known-working STT model for a provider.
     * Uses stable model IDs that are confirmed to exist in each provider's API,
     * rather than picking the first model from the display list (which may include
     * unreleased/preview models like gemini-3-pro).
     */
    private fun getSttFallbackModelId(provider: AiProvider): String? {
        return when (provider) {
            AiProvider.GEMINI -> "gemini-2.5-flash"
            AiProvider.OPENAI -> "gpt-5-mini"
            AiProvider.GROQ -> "whisper-large-v3"
            AiProvider.XAI -> "grok-2-latest"
            else -> AvailableModels.getModelsForProvider(provider).firstOrNull()?.id
        }
    }
    
    /**
     * Create dedicated STT service for specialized providers
     * Uses SttServiceFactory for providers like Deepgram, Azure, Aliyun, etc.
     */
    private fun createSttService(settings: ApiSettings): SttService? {
        val sttProvider = settings.sttProvider
        
        // Check if this is a provider that uses main AI API keys (handled by speechService)
        val aiBasedProviders = listOf(
            SttProvider.GEMINI,
            SttProvider.OPENAI_WHISPER,
            SttProvider.GROQ_WHISPER
        )
        
        if (sttProvider in aiBasedProviders) {
            Log.d(TAG, "STT provider ${sttProvider.name} uses main AI service, no dedicated STT service needed")
            return null
        }
        
        // Create dedicated STT service using SttServiceFactory
        val sttCredentials = settings.toSttCredentials()
        val service = SttServiceFactory.createService(sttCredentials, settings)
        
        if (service != null) {
            Log.d(TAG, "Created dedicated STT service for provider: ${sttProvider.name}")
        } else {
            Log.w(TAG, "Failed to create STT service for ${sttProvider.name} - credentials may be missing")
        }
        
        return service
    }
    
    /**
     * Check if speech service is available
     */
    fun isSpeechServiceAvailable(): Boolean = speechService != null || sttService != null
    
    /**
     * Validate and correct settings
     * Ensure selected model is compatible with AI provider
     */
    private fun validateAndCorrectSettings(settings: ApiSettings): ApiSettings {
        // Migrate deprecated/unreleased model IDs to currently-callable replacements.
        // Gemini 3.x ids are forward-looking placeholders; the v1beta endpoint still 404s
        // them as of 2026-05, so route saved settings to the stable 2.5 family until they ship.
        val deprecatedModelMigrations = mapOf(
            "sonar-reasoning" to "sonar-reasoning-pro",
            "gemini-3.1-flash" to "gemini-2.5-flash",
            "gemini-3.1-flash-lite" to "gemini-2.5-flash-lite",
            "gemini-3.1-pro-deep-think" to "gemini-2.5-pro",
            "gemini-3.1-pro-preview" to "gemini-2.5-pro",
            "gemini-3-flash-preview" to "gemini-2.5-flash"
        )
        val migratedSettings = deprecatedModelMigrations[settings.aiModelId]?.let { replacement ->
            Log.w(TAG, "Model '${settings.aiModelId}' is deprecated, migrating to '$replacement'")
            settings.copy(aiModelId = replacement)
        } ?: settings

        if (migratedSettings.aiProvider == AiProvider.CUSTOM) {
            val customModels = AvailableModels.getModelsForProvider(AiProvider.CUSTOM)
            val customModelId = customModels.firstOrNull { it.id == migratedSettings.aiModelId }?.id
                ?: customModels.firstOrNull { it.id == "qwen3" }?.id
                ?: customModels.firstOrNull()?.id
                ?: "custom"
            return migratedSettings.copy(
                aiProvider = AiProvider.CUSTOM,
                aiModelId = customModelId,
                customApiKey = migratedSettings.customApiKey.ifBlank { "local" }
            )
        }
        
        val modelInfo = AvailableModels.findModel(migratedSettings.aiModelId)
        
        // If model info not found, use provider's default model
        if (modelInfo == null) {
            Log.w(TAG, "Unknown model: ${migratedSettings.aiModelId}, using default model for ${migratedSettings.aiProvider}")
            val defaultModel = AvailableModels.getModelsForProvider(migratedSettings.aiProvider).firstOrNull()
            return if (defaultModel != null) {
                migratedSettings.copy(aiModelId = defaultModel.id)
            } else {
                // Fall back to Gemini
                migratedSettings.copy(
                    aiProvider = AiProvider.GEMINI,
                    aiModelId = "gemini-2.5-flash"
                )
            }
        }
        
        // If model doesn't match provider, correct the provider
        if (modelInfo.provider != migratedSettings.aiProvider) {
            Log.w(TAG, "Model ${migratedSettings.aiModelId} belongs to ${modelInfo.provider}, correcting provider")
            return migratedSettings.copy(aiProvider = modelInfo.provider)
        }
        
        // Check if provider has API key
        if (migratedSettings.getCurrentApiKey().isBlank()) {
            Log.w(TAG, "No API key for ${migratedSettings.aiProvider}, checking for fallback")
            // Try to use provider that has API key
            for (provider in AiProvider.entries) {
                val apiKey = migratedSettings.getApiKeyForProvider(provider)
                if (apiKey.isNotBlank()) {
                    val defaultModel = AvailableModels.getModelsForProvider(provider).firstOrNull()
                    if (defaultModel != null) {
                        Log.d(TAG, "Falling back to ${provider.name}")
                        return migratedSettings.copy(
                            aiProvider = provider,
                            aiModelId = defaultModel.id
                        )
                    }
                }
            }
        }
        
        return migratedSettings
    }

    private fun speakOnGlasses(text: String) {
        if (text.trimStart().startsWith("Sorry,", ignoreCase = true)) {
            Log.d(TAG, "Skipping TTS for error response: ${text.take(80)}")
            return
        }
        ttsService?.speak(text) { audioData ->
            serviceScope.launch {
                val sent = bluetoothManager?.sendMessage(Message.aiResponseTts(audioData)) == true
                if (sent) {
                    Log.d(TAG, "Sent TTS audio to glasses: ${audioData.size} bytes")
                } else {
                    Log.w(TAG, "Unable to send TTS audio to glasses")
                }
            }
        }
    }
}

/**
 * STT Service (Simplified version)
 */
class SpeechToTextService(private val apiKey: String) {
    
    suspend fun transcribe(audioData: ByteArray): String? {
        return withContext(Dispatchers.IO) {
            try {
                // TODO: Implement OpenAI Whisper API call
                // Returning mock result here
                "This is a test voice input"
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * TTS Service
 * Routes through EdgeTtsClient (high-quality neural voices) or System TTS
 * based on user preference in ApiSettings.ttsProvider.
 */
class TextToSpeechService(private val context: android.content.Context) {

    private val TAG = "TextToSpeechService"

    private var tts: android.speech.tts.TextToSpeech? = null
    private var systemTtsReady = false
    private val edgeTtsClient = com.example.rokidphone.service.EdgeTtsClient()
    private val ttsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Main-thread dispatcher reference, indirected so it can be substituted in tests
    // (Sonar `kotlin:S6311` — avoid hardcoded dispatchers in suspend bodies).
    private val mainDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main

    private data class SystemTtsPending(
        val tempFile: java.io.File,
        val onAudioChunk: (ByteArray) -> Unit
    )

    private val pendingSystemTtsUtterances =
        java.util.concurrent.ConcurrentHashMap<String, SystemTtsPending>()

    private val systemTtsListener = object : android.speech.tts.UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { /* no-op */ }

        override fun onDone(utteranceId: String?) {
            val pending = utteranceId?.let { pendingSystemTtsUtterances.remove(it) } ?: return
            ttsScope.launch {
                try {
                    val bytes = pending.tempFile.readBytes()
                    if (bytes.isNotEmpty()) {
                        pending.onAudioChunk(bytes)
                    } else {
                        android.util.Log.w(TAG, "System TTS produced empty audio file")
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to read System TTS output", e)
                } finally {
                    pending.tempFile.delete()
                }
            }
        }

        @Deprecated("Required override; modern path uses onError(String?, Int).")
        override fun onError(utteranceId: String?) {
            val pending = utteranceId?.let { pendingSystemTtsUtterances.remove(it) } ?: return
            android.util.Log.e(TAG, "System TTS synthesis error (legacy callback)")
            pending.tempFile.delete()
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            val pending = utteranceId?.let { pendingSystemTtsUtterances.remove(it) } ?: return
            android.util.Log.e(TAG, "System TTS synthesis error: $errorCode")
            pending.tempFile.delete()
        }
    }

    init {
        // Always initialise system TTS so it's available as fallback / if user picks SYSTEM_TTS
        tts = android.speech.tts.TextToSpeech(context) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                val defaultLocale = java.util.Locale.getDefault()
                val langResult = tts?.setLanguage(defaultLocale)
                systemTtsReady = langResult != android.speech.tts.TextToSpeech.LANG_MISSING_DATA
                        && langResult != android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setOnUtteranceProgressListener(systemTtsListener)
            } else {
                android.util.Log.e(TAG, "System TTS initialisation failed (status=$status)")
            }
        }
    }

    /**
     * Primary speak entry-point. Reads TTS provider preference from [SettingsRepository].
     */
    fun speak(text: String, onAudioChunk: (ByteArray) -> Unit) {
        val settings = SettingsRepository.getInstance(context).getSettings()

        when (settings.ttsProvider) {
            com.example.rokidphone.data.TtsProvider.EDGE_TTS -> speakWithEdge(text, settings, onAudioChunk)
            com.example.rokidphone.data.TtsProvider.SYSTEM_TTS -> speakWithSystemTts(text, settings, onAudioChunk)
            com.example.rokidphone.data.TtsProvider.GOOGLE_TRANSLATE_TTS -> speakWithSystemTts(text, settings, onAudioChunk)
        }
    }

    // ── Edge TTS ─────────────────────────────────────────

    private fun speakWithEdge(text: String, settings: com.example.rokidphone.data.ApiSettings, onAudioChunk: (ByteArray) -> Unit) {
        android.util.Log.d(TAG, "TTS engine: Edge")
        ttsScope.launch {
            try {
                // Resolve voice
                val voice = if (settings.ttsVoiceOverride.isNotBlank()) {
                    settings.ttsVoiceOverride
                } else {
                    val locale = try {
                        java.util.Locale.forLanguageTag(settings.speechLanguage)
                    } catch (_: Exception) { java.util.Locale.getDefault() }
                    com.example.rokidphone.ui.detectEdgeVoice(text, locale)
                }

                // Format rate & pitch
                val rate = com.example.rokidphone.ui.formatEdgeRate(settings.ttsSpeechRate)
                val pitch = com.example.rokidphone.ui.formatEdgePitch(settings.ttsPitch)

                android.util.Log.d(TAG, "Edge TTS: voice=$voice, rate=$rate, pitch=$pitch")

                // For Korean voices, strip embedded Han characters that would otherwise
                // be read with a Chinese accent.
                val cleanedText = if (voice.startsWith("ko-KR")) {
                    sanitizeForTts(text, java.util.Locale.KOREAN)
                } else {
                    text
                }

                val result = edgeTtsClient.synthesize(cleanedText, voice, rate, pitch)

                result.onSuccess { audioData ->
                    if (audioData.isNotEmpty()) {
                        onAudioChunk(audioData)
                    } else {
                        android.util.Log.w(TAG, "Edge TTS returned empty data, falling back to system TTS")
                        withContext(mainDispatcher) { speakWithSystemTts(text, settings, onAudioChunk) }
                    }
                }

                result.onFailure { err ->
                    android.util.Log.w(TAG, "Edge TTS failed: ${err.message}, falling back to system TTS")
                    withContext(mainDispatcher) { speakWithSystemTts(text, settings, onAudioChunk) }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Edge TTS error", e)
                withContext(mainDispatcher) { speakWithSystemTts(text, null, onAudioChunk) }
            }
        }
    }

    // ── System TTS ───────────────────────────────────────

    private fun speakWithSystemTts(
        text: String,
        settings: com.example.rokidphone.data.ApiSettings?,
        onAudioChunk: (ByteArray) -> Unit
    ) {
        android.util.Log.d(TAG, "TTS engine: System")
        val engine = tts
        if (!systemTtsReady || engine == null) {
            android.util.Log.e(TAG, "System TTS not ready")
            return
        }

        val locale = detectLocaleForText(text)
        val langResult = engine.setLanguage(locale)
        if (langResult == android.speech.tts.TextToSpeech.LANG_MISSING_DATA ||
            langResult == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
            android.util.Log.w(TAG, "System TTS: locale '$locale' not supported, using device default")
            engine.setLanguage(java.util.Locale.getDefault())
        } else {
            // Prefer an offline native voice, but fall back to network voices instead
            // of discarding them (Samsung's Korean voice is often network-only, and
            // dropping it forces the engine to fall back to its Chinese default).
            val nativeVoice = engine.voices
                ?.asSequence()
                ?.filter { it.locale.language == locale.language }
                ?.sortedBy { if (it.isNetworkConnectionRequired) 1 else 0 }
                ?.firstOrNull()
            if (nativeVoice != null) {
                engine.voice = nativeVoice
                android.util.Log.d(
                    TAG,
                    "System TTS: voice=${nativeVoice.name}, network=${nativeVoice.isNetworkConnectionRequired}"
                )
            }
        }

        engine.setSpeechRate(settings?.systemTtsSpeechRate ?: 1.0f)
        engine.setPitch(settings?.systemTtsPitch ?: 1.0f)
        val cleaned = sanitizeForTts(text, locale)

        // Synthesize to a file so the bytes can be forwarded to the glasses over SPP
        // instead of being played on the phone's local speaker.
        val utteranceId = "system_tts_${System.currentTimeMillis()}_${System.nanoTime()}"
        val tempFile = try {
            java.io.File.createTempFile("system_tts_", ".wav", context.cacheDir)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to create System TTS temp file", e)
            return
        }
        pendingSystemTtsUtterances[utteranceId] = SystemTtsPending(tempFile, onAudioChunk)
        val result = engine.synthesizeToFile(cleaned, null, tempFile, utteranceId)
        if (result != android.speech.tts.TextToSpeech.SUCCESS) {
            android.util.Log.w(TAG, "System TTS synthesizeToFile rejected request (code=$result)")
            pendingSystemTtsUtterances.remove(utteranceId)
            tempFile.delete()
        }
    }

    // ── Lifecycle ────────────────────────────────────────

    fun shutdown() {
        ttsScope.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        pendingSystemTtsUtterances.values.forEach { it.tempFile.delete() }
        pendingSystemTtsUtterances.clear()
    }

    private fun detectLocaleForText(text: String): java.util.Locale {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return java.util.Locale.getDefault()

        return when {
            trimmed.any { it in '\uAC00'..'\uD7AF' } -> java.util.Locale.KOREAN
            trimmed.any { it in '\u4E00'..'\u9FFF' } -> java.util.Locale.TRADITIONAL_CHINESE
            trimmed.any { it in '\u3040'..'\u30FF' } -> java.util.Locale.JAPANESE
            else -> java.util.Locale.getDefault()
        }
    }

    /**
     * Strip characters that the resolved TTS locale cannot pronounce natively.
     * Currently used to remove embedded Han characters from Korean output, which
     * would otherwise be read by Samsung TTS using its Chinese voice mid-sentence.
     */
    internal fun sanitizeForTts(text: String, locale: java.util.Locale): String =
        when (locale.language) {
            "ko" -> text.filter { it !in '\u4E00'..'\u9FFF' }
                        .replace(Regex("\\s{2,}"), " ").trim()
            else -> text
        }
}

/**
 * Bluetooth Manager (Simplified version)
 */
class BluetoothManager(private val context: android.content.Context) {
    
    private val _messageFlow = MutableSharedFlow<Message>()
    val messageFlow = _messageFlow.asSharedFlow()
    
    suspend fun sendMessage(message: Message) {
        // TODO: Implement Bluetooth send
    }
    
    fun disconnect() {
        // TODO: Implement Bluetooth disconnect
    }
}
