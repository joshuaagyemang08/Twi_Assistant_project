package com.example.twiassistant.ui_icon

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log
import java.util.concurrent.TimeUnit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.twiassistant.BuildConfig
import com.example.twiassistant.R
import com.example.twiassistant.asr.AndroidSpeechRecognizerTwi
import com.example.twiassistant.asr.AndroidSpeechRecognizerEnglish
import com.example.twiassistant.asr.GhanaNlpAsrProvider
import com.example.twiassistant.asr.SilenceRecordingAsrRecognizer
import com.example.twiassistant.dialog.DialogManager
import com.example.twiassistant.dialog.DialogState
import com.example.twiassistant.device_control.DeviceActions
import com.example.twiassistant.nlu.IntentParser
import com.example.twiassistant.tts.AndroidTts
import com.example.twiassistant.tts.GhanaNlpTts
import com.example.twiassistant.tts.TtsEngine
import com.example.twiassistant.viewmodel.AssistantViewModel
import okhttp3.OkHttpClient
import androidx.core.app.ActivityCompat
import kotlin.math.sin

// Ghana Flag Colors - Design Specification
private val GhanaRed = Color(0xFFCE1126)
private val GhanaGold = Color(0xFFFCD116)
private val GhanaGreen = Color(0xFF006B3F)
private val SecondaryAmber = Color(0xFFF59E0B)

// Background & Typography
private val BackgroundGradient = Brush.verticalGradient(
    listOf(
        Color(0xFFFEF3C7), // amber-50
        Color(0xFFFEF3C7).copy(alpha = 0.8f), // yellow-50 blend  
        Color(0xFFFEE2E2).copy(alpha = 0.6f)  // red-50 blend
    )
)
private val TextDark = Color(0xFF1F2937) // gray-800
private val TextLight = Color(0xFFFFFFFF)
private val TextMuted = Color(0xFF6B7280) // gray-500

@Composable
fun AssistantHomeScreen(viewModel: AssistantViewModel = defaultAssistantViewModel()) {
    val context = LocalContext.current
    val dialogState = viewModel.dialogState
    val uiMode = viewModel.uiMode
    // Messages are now always input in Twi

    val httpClient = rememberHttpClient()
    val ttsKey = remember {
        BuildConfig.GHANA_NLP_TTS_KEY.ifBlank { BuildConfig.GHANA_NLP_ASR_KEY }
    }
    val ttsUrl = remember {
        if (BuildConfig.GHANA_NLP_TTS_URL.isNotBlank()) {
            BuildConfig.GHANA_NLP_TTS_URL
        } else {
            "https://translation-api.ghananlp.org/tts/v2"
        }
    }

    LaunchedEffect(Unit) {
        val keySuffix = if (ttsKey.length >= 4) ttsKey.takeLast(4) else "(none)"
        Log.d("AssistantHomeScreen", "TTS configured url=$ttsUrl keyLen=${ttsKey.length} keySuffix=$keySuffix")
        val asrKey = BuildConfig.GHANA_NLP_ASR_KEY
        val asrUrl = if (BuildConfig.GHANA_NLP_ASR_URL.isNotBlank()) BuildConfig.GHANA_NLP_ASR_URL else "(blank)"
        val asrSuffix = if (asrKey.length >= 4) asrKey.takeLast(4) else "(none)"
        Log.d("AssistantHomeScreen", "ASR configured url=$asrUrl keyLen=${asrKey.length} keySuffix=$asrSuffix")
    }

    val tts: TtsEngine = remember {
        if (ttsKey.isNotBlank()) {
            GhanaNlpTts(
                context = context.applicationContext,
                client = httpClient,
                baseUrl = ttsUrl,
                subscriptionKey = ttsKey,
                language = "twi",
                speakerId = "female",
            )
        } else {
            AndroidTts(context.applicationContext)
        }
    }

    var contactsPermissionGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var contactsPermissionRequestedOnce by remember { mutableStateOf(false) }

    fun openAppSettings() {
        val intent = Intent(
            "android.settings.APPLICATION_DETAILS_SETTINGS",
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun shouldOpenSettingsAfterDenied(): Boolean {
        val activity = context as? Activity ?: return false
        // If we've requested once and the system won't show rationale anymore,
        // it usually means "Don't ask again" (or policy restriction).
        return contactsPermissionRequestedOnce &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CONTACTS)
    }

    var callPermissionGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var callPermissionRequestedOnce by remember { mutableStateOf(false) }
    var allPermissionsRequestedOnce by remember { mutableStateOf(false) }
    
    // Track TTS speaking state for UI recomposition
    var isTtsSpeaking by remember { mutableStateOf(false) }
    
    var cameraPermissionGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var smsPermissionGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.SEND_SMS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var readSmsPermissionGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_SMS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    fun shouldOpenSettingsAfterCallDenied(): Boolean {
        val activity = context as? Activity ?: return false
        return callPermissionRequestedOnce &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CALL_PHONE)
    }

    val callLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            callPermissionGranted = granted
            if (granted) {
                viewModel.completePendingCallIfAny()?.let { tts.speak(it) }
            } else if (shouldOpenSettingsAfterCallDenied()) {
                tts.speak("Mma din no ammu dea, sɔ Settings no na fa permission no")
                openAppSettings()
            }
        }
    )

    val contactsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            contactsPermissionGranted = granted
            if (granted) {
                tts.speak("Akontaa no adidi")
            } else if (shouldOpenSettingsAfterDenied()) {
                tts.speak("Mma Sheri akwantu a-din no, sɔ Settings no")
                openAppSettings()
            }
        }
    )

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            cameraPermissionGranted = granted
            if (granted) {
                tts.speak("Kamera adidi")
            } else {
                tts.speak("Mma Sheri kamera, sɔ Settings no")
                openAppSettings()
            }
        }
    )

    val smsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            smsPermissionGranted = granted
            if (granted) {
                tts.speak("S M S adidi")
            } else {
                tts.speak("Mma Sheri SMS, sɔ Settings no")
                openAppSettings()
            }
        }
    )

    val readSmsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            readSmsPermissionGranted = granted
            if (granted) {
                tts.speak("Nkyerɛw box adidi")
            } else {
                tts.speak("Mma Sheri nkyerɛw box a-hwɛ, sɔ Settings no")
                openAppSettings()
            }
        }
    )
    var micPermissionGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Trigger greeting when screen is shown (including app reopens)
    val screenOpenedKey = remember { Any() }
    LaunchedEffect(screenOpenedKey) {
        viewModel.onAppOpened(canRecordAudio = micPermissionGranted)
    }

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            micPermissionGranted = granted
            if (granted) {
                viewModel.startListening()
            }
        }
    )

    val allPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { result ->
            micPermissionGranted = result[Manifest.permission.RECORD_AUDIO] == true
            contactsPermissionGranted = result[Manifest.permission.READ_CONTACTS] == true
            callPermissionGranted = result[Manifest.permission.CALL_PHONE] == true
            cameraPermissionGranted = result[Manifest.permission.CAMERA] == true
            smsPermissionGranted = result[Manifest.permission.SEND_SMS] == true
            readSmsPermissionGranted = result[Manifest.permission.READ_SMS] == true
            // For MODIFY_SYSTEM_SETTINGS (WRITE_SETTINGS) we cannot request via runtime dialog; we will prompt below.

            // Notify user of missing permissions succinctly
            val missing = mutableListOf<String>()
            if (!micPermissionGranted) missing += "microphone"
            if (!contactsPermissionGranted) missing += "contacts"
            if (!callPermissionGranted) missing += "phone"
            if (!cameraPermissionGranted) missing += "camera"
            if (!smsPermissionGranted) missing += "sms"
            if (!readSmsPermissionGranted) missing += "sms inbox"
            // Only speak if some permissions are missing (don't announce when all granted)
            if (missing.isNotEmpty()) {
                tts.speak("Please enable ${missing.joinToString(", ")} in Settings")
            }
        }
    )

    // One-shot bulk request when app opens
    LaunchedEffect(Unit) {
        if (!allPermissionsRequestedOnce) {
            allPermissionsRequestedOnce = true
            allPermissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.CAMERA,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_SMS,
                )
            )

            // System settings permission no longer needed for simplified assistant
        }
    }

    LaunchedEffect(dialogState) {
        // Auto-stop when execute finishes to keep UX minimal.
        if (dialogState == DialogState.EXECUTE) {
            viewModel.stopListening()
        }
    }

    LaunchedEffect(viewModel.executedAction) {
        val msg = viewModel.executedAction
        if (msg.isNotBlank()) {
            if (!contactsPermissionGranted && (msg.contains("access Contacts", ignoreCase = true) || msg.contains("Contacts", ignoreCase = true))) {
                contactsPermissionRequestedOnce = true
                contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
                return@LaunchedEffect
            }
            if (!callPermissionGranted && msg.contains("make calls", ignoreCase = true)) {
                callPermissionRequestedOnce = true
                callLauncher.launch(Manifest.permission.CALL_PHONE)
                return@LaunchedEffect
            }
            if (!cameraPermissionGranted && msg.contains("camera", ignoreCase = true)) {
                cameraLauncher.launch(Manifest.permission.CAMERA)
                return@LaunchedEffect
            }
            if (!smsPermissionGranted && (msg.contains("sms", ignoreCase = true) || msg.contains("message", ignoreCase = true))) {
                smsLauncher.launch(Manifest.permission.SEND_SMS)
                return@LaunchedEffect
            }
            if (!readSmsPermissionGranted && msg.contains("inbox", ignoreCase = true)) {
                readSmsLauncher.launch(Manifest.permission.READ_SMS)
                return@LaunchedEffect
            }
            tts.speak(msg)
        }
    }

    LaunchedEffect(viewModel.lastError) {
        val err = viewModel.lastError
        if (err.isNotBlank()) {
            if (!contactsPermissionGranted && (err.contains("access Contacts", ignoreCase = true) || err.contains("Contacts", ignoreCase = true))) {
                contactsPermissionRequestedOnce = true
                contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
                return@LaunchedEffect
            }
            if (!callPermissionGranted && err.contains("make calls", ignoreCase = true)) {
                callPermissionRequestedOnce = true
                callLauncher.launch(Manifest.permission.CALL_PHONE)
                return@LaunchedEffect
            }
            if (!cameraPermissionGranted && err.contains("camera", ignoreCase = true)) {
                cameraLauncher.launch(Manifest.permission.CAMERA)
                return@LaunchedEffect
            }
            if (!smsPermissionGranted && (err.contains("sms", ignoreCase = true) || err.contains("message", ignoreCase = true))) {
                smsLauncher.launch(Manifest.permission.SEND_SMS)
                return@LaunchedEffect
            }
            if (!readSmsPermissionGranted && err.contains("inbox", ignoreCase = true)) {
                readSmsLauncher.launch(Manifest.permission.READ_SMS)
                return@LaunchedEffect
            }
            tts.speak(err)
        }
    }
    
    // Update TTS speaking state periodically for UI recomposition
    LaunchedEffect(Unit) {
        while (true) {
            isTtsSpeaking = tts.isSpeaking()
            delay(100) // Check every 100ms
        }
    }

    DisposableEffect(Unit) {
        onDispose { tts.shutdown() }
    }

    // Main UI with Ghana Theme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundGradient)
    ) {
        // State tracking - Fixed priority and comprehensive processing detection
        val isListening = dialogState == DialogState.LISTENING
        val isSpeaking = isTtsSpeaking
        val isProcessing = viewModel.isProcessingCommand || viewModel.isProcessingPhoto || 
                          (dialogState == DialogState.EXECUTE && !isSpeaking) ||
                          viewModel.isAnalyzingSpeech
        val isActive = isListening || isProcessing || isSpeaking
        
        // Animation  
        val infiniteTransition = rememberInfiniteTransition(label = "main_anim")
        
        // Button color based on state - Design Specification
        val (buttonColor, buttonBorder) = when {
            isListening -> Pair(
                Brush.radialGradient(listOf(Color(0xFF16A34A), Color(0xFF22C55E))), // Green gradient
                Color(0xFF14532D) // Darker green border
            )
            viewModel.isAnalyzingSpeech -> Pair(
                Brush.radialGradient(listOf(Color(0xFF7C3AED), Color(0xFF8B5CF6))), // Purple gradient for analysis
                Color(0xFF5B21B6) // Darker purple border
            )
            isProcessing -> Pair(
                Brush.radialGradient(listOf(Color(0xFF2563EB), Color(0xFF3B82F6))), // Blue gradient for processing
                Color(0xFF1E40AF) // Darker blue border
            )
            isSpeaking -> Pair(
                Brush.radialGradient(listOf(SecondaryAmber, Color(0xFFFBBF24))), // Amber gradient
                Color(0xFFEA580C) // Orange border
            )
            else -> Pair(
                Brush.radialGradient(listOf(Color(0xFFDC2626), Color(0xFFEF4444))), // Red gradient (idle)
                Color(0xFF991B1B) // Darker red border  
            )
        }
        
        val scrollState = rememberScrollState()
        
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Kente-inspired Header Borders
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(GhanaRed, GhanaGold, GhanaGreen)
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(GhanaGold, GhanaGreen, GhanaRed)
                        )
                    )
            )
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(24.dp)
            ) {
                Spacer(Modifier.height(24.dp))
                
                // App Title with Ghana Flag Emoji
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "🇬🇭",
                        fontSize = 28.sp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "SHERI",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDark
                    )
                }
                
                Text(
                    text = "Wo Voice Assistant",
                    fontSize = 18.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )
            
                Spacer(Modifier.height(32.dp))
                
                // Status text - Design Specification Twi  
                val statusText = when {
                    isListening -> "Metie wo..."  // Listening...
                    viewModel.isAnalyzingSpeech -> "Merehu nsɛm no..." // Analyzing speech...
                    isProcessing -> "Merekyerɛw..." // Processing...
                    isSpeaking -> "Merekasa..." // Speaking...  
                    else -> "Mesrɛ wo, mia ha na kasa" // Please tap here to speak
                }
                
                Text(
                    text = statusText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextDark,
                    textAlign = TextAlign.Center
                )
                
                // English translation (optional)
                val englishText = when {
                    isListening -> "Listening..."
                    viewModel.isAnalyzingSpeech -> "Analyzing..."
                    isProcessing -> "Processing..." 
                    isSpeaking -> "Speaking..."
                    else -> "Tap here to speak"
                }
                
                Text(
                    text = englishText,
                    fontSize = 14.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )
            
                Spacer(Modifier.height(24.dp))
                
                // Transcript display removed - backend only processing
            
            Spacer(Modifier.height(20.dp))
            
                // Main Voice Button - Design Specification (48-64dp diameter, large circular)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(200.dp) // Container for effects
                ) {
                    // Pulsing effect for listening state  
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 1.0f,
                        targetValue = if (isListening) 1.05f else 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulse"
                    )
                    
                    // Outer rings when active
                    if (isActive) {
                        for (i in 0..1) {
                            val ringDelay = i * 500
                            val ringScale by infiniteTransition.animateFloat(
                                initialValue = 1f,
                                targetValue = 1.4f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1500, delayMillis = ringDelay),
                                    repeatMode = RepeatMode.Restart
                                ),
                                label = "ring_$i"
                            )
                            val ringAlpha by infiniteTransition.animateFloat(
                                initialValue = 0.3f,
                                targetValue = 0f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1500, delayMillis = ringDelay),
                                    repeatMode = RepeatMode.Restart
                                ),
                                label = "ring_alpha_$i"
                            )
                            
                            val ringColor = when {
                                isListening -> GhanaGreen
                                viewModel.isAnalyzingSpeech -> Color(0xFF7C3AED) // Purple for analysis
                                isProcessing -> SecondaryAmber
                                isSpeaking -> SecondaryAmber
                                else -> GhanaRed
                            }
                            
                            Box(
                                modifier = Modifier
                                    .size(120.dp)
                                    .scale(ringScale)
                                    .background(
                                        ringColor.copy(alpha = ringAlpha),
                                        CircleShape
                                    )
                            )
                        }
                    }
                    
                    // Main Button - 64dp diameter as specified
                    Surface(
                        modifier = Modifier
                            .size(64.dp)
                            .scale(pulseScale)
                            .clickable(enabled = !isProcessing && !isSpeaking) {
                                if (isListening) {
                                    viewModel.stopListening()
                                } else if (micPermissionGranted && !tts.isSpeaking() && !isProcessing) {
                                    // Stop TTS immediately when user clicks to speak
                                    tts.stop()
                                    
                                    // Check if we are in a state that requires the English recognizer
                                    val currentState = viewModel.flowState
                                    // English messaging active check removed
                                    val useEnglishRecognizer = when {
                                        currentState is AssistantViewModel.FlowState.AwaitingEnglishName -> true
                                        else -> false
                                    }

                                    Log.d("UI_MIC", "Mic clicked: state=$currentState, useEnglishRecognizer=$useEnglishRecognizer")

                                    if (useEnglishRecognizer) {
                                        viewModel.startEnglishListening()
                                    } else {
                                        viewModel.startListening()
                                    }
                                } else if (!micPermissionGranted) {
                                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                            .shadow(8.dp, CircleShape), // Raised with shadow
                        shape = CircleShape,
                        color = Color.Transparent
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(buttonColor, CircleShape)
                                .border(
                                    width = 3.dp, // 3px solid border as specified
                                    color = buttonBorder,
                                    shape = CircleShape
                                )
                        ) {
                            // Icon based on state - Design Specification
                            when {
                                isListening -> {
                                    // Animated sound waves
                                    SoundWaveIcon(
                                        modifier = Modifier.size(32.dp),
                                        color = TextLight
                                    )
                                }
                                viewModel.isAnalyzingSpeech -> {
                                    // Brain/thinking icon for speech analysis
                                    Text(
                                        text = "🧠",
                                        fontSize = 32.sp
                                    )
                                }
                                isProcessing -> {
                                    // Spinner for processing state
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(32.dp),
                                        color = TextLight,
                                        strokeWidth = 3.dp
                                    )
                                }
                                isSpeaking -> {
                                    // Speaker with sound waves - use emoji since drawable not available
                                    Text(
                                        text = "🔊",
                                        fontSize = 32.sp,
                                        color = TextLight
                                    )
                                }
                                else -> {
                                    // Microphone in white (idle)
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_phone_helper),
                                        contentDescription = "Microphone",
                                        tint = TextLight,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            
                Spacer(Modifier.height(32.dp))
                
                // Sound Wave Visualization when listening
                if (isListening) {
                    SoundWaveVisualization(
                        color = GhanaGreen,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .padding(horizontal = 32.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                }
                
                // Four Main Features - Design Specification
                Text(
                    text = "Nsɛm a metumi ayɛ ma wo:",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextDark,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                
                Spacer(Modifier.height(16.dp))
                
                // Feature Cards in 2x2 Grid
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 1. Calls Feature
                        FeatureCard(
                            title = "Frɛ",
                            subtitle = "Calls",
                            icon = "📞",
                            accentColor = GhanaGreen,
                            onClick = { viewModel.onCallFeatureSelected() },
                            modifier = Modifier.weight(1f)
                        )
                        
                        // 2. Messages Feature  
                        FeatureCard(
                            title = "Krataa", 
                            subtitle = "Messages",
                            icon = "💬",
                            accentColor = GhanaGold,
                            onClick = { viewModel.onMessageFeatureSelected() },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 3. Open Apps Feature  
                        FeatureCard(
                            title = "Bue Apps",
                            subtitle = "Open Apps",
                            icon = "📱",
                            accentColor = GhanaRed,
                            onClick = { viewModel.onOpenAppFeatureSelected() },
                            modifier = Modifier.weight(1f)
                        )
                        
                        // 4. Homework/Study Feature
                        FeatureCard(
                            title = "Adesua",
                            subtitle = "Homework",
                            icon = "🎓",
                            accentColor = SecondaryAmber,
                            onClick = { viewModel.onAdesuaFeatureSelected() },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            
                Spacer(Modifier.height(24.dp))
                
                // Mode indicator card - Ghana themed
                if (uiMode != AssistantViewModel.UiMode.DEFAULT) {
                    val modeColor = when (uiMode) {
                        AssistantViewModel.UiMode.CALL -> GhanaGreen
                        AssistantViewModel.UiMode.SMS -> GhanaGold  
                        AssistantViewModel.UiMode.OPEN_APPS -> GhanaRed
                        AssistantViewModel.UiMode.ADESUA -> SecondaryAmber
                        else -> TextMuted
                    }
                    
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .border(
                                width = 2.dp,
                                color = modeColor,
                                shape = RoundedCornerShape(16.dp)
                            )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = when (uiMode) {
                                    AssistantViewModel.UiMode.CALL -> "📞 Frɛ"
                                    AssistantViewModel.UiMode.SMS -> "💬 Krataa"
                                    AssistantViewModel.UiMode.OPEN_APPS -> "📱 Bue Apps"
                                    AssistantViewModel.UiMode.ADESUA -> "🎓 Adesua"
                                    else -> ""
                                },
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = modeColor
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = when (uiMode) {
                                    AssistantViewModel.UiMode.CALL -> "Ka din no wɔ borɔfo (English)"
                                    AssistantViewModel.UiMode.SMS -> "Din no wɔ borɔfo; asɛm no wɔ Twi"
                                    AssistantViewModel.UiMode.OPEN_APPS -> "Ka app no din"
                                    AssistantViewModel.UiMode.ADESUA -> "Ka wo nsɛmmisa no"
                                    else -> ""
                                },
                                fontSize = 14.sp,
                                color = TextDark,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            
                Spacer(Modifier.height(24.dp))
                
                // Homework Results Display - shows English content with Twi captions
                if (uiMode == AssistantViewModel.UiMode.ADESUA && viewModel.homeworkResults.isNotEmpty()) {
                    HomeworkResultsCard(
                        results = viewModel.homeworkResults,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                }
                
                // Permission button if needed
                if (!micPermissionGranted) {
                    Surface(
                        onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        shape = RoundedCornerShape(24.dp),
                        color = GhanaGreen,
                        modifier = Modifier.padding(horizontal = 48.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = "🎤",
                                fontSize = 20.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Ma kwan ma microphone",
                                fontSize = 16.sp,
                                color = TextLight,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                Spacer(Modifier.height(40.dp))
            }
        }
        
        // Contact Picker Overlay - Design Specification
        if (viewModel.pendingChoice != null) {
            when (val choice = viewModel.pendingChoice!!) {
                is AssistantViewModel.PendingChoice.MessageChoice -> {
                    MessageTypePickerOverlay(
                        contact = choice.contact,
                        onSmsSelected = { contact ->
                            viewModel.selectMessageType(contact, false)
                        },
                        onWhatsAppSelected = { contact ->
                            viewModel.selectMessageType(contact, true)
                        },
                        onDismiss = {
                            viewModel.clearPendingChoice()
                        }
                    )
                }
                // LanguageChoice case removed - no longer needed
                else -> {
                    ContactPickerOverlay(
                        pendingChoice = viewModel.pendingChoice!!,
                        onContactSelected = { contact ->
                            viewModel.selectContact(contact)
                        },
                        onDismiss = {
                            viewModel.clearPendingChoice()
                        }
                    )
                }
            }
        }
        
        // Ghana flag stripe at bottom
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(6.dp)
        ) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxSize().background(GhanaRed))
                Box(Modifier.weight(1f).fillMaxSize().background(GhanaGold))
                Box(Modifier.weight(1f).fillMaxSize().background(GhanaGreen))
            }
        }
    }
}

@Composable
private fun defaultAssistantViewModel(): AssistantViewModel {
    val context = LocalContext.current
    val httpClient = rememberHttpClient()
    return viewModel(factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return AssistantViewModel(
                parser = IntentParser(),
                dialogManager = DialogManager(),
                recognizerProvider = { onFinal, onPartial, onError ->
                    val ghanaKey = BuildConfig.GHANA_NLP_ASR_KEY
                    val ghanaUrl = if (BuildConfig.GHANA_NLP_ASR_URL.isNotBlank()) {
                        BuildConfig.GHANA_NLP_ASR_URL
                    } else {
                        "https://translation-api.ghananlp.org/asr/v2/transcribe"
                    }

                    val keySuffix = if (ghanaKey.length >= 4) ghanaKey.takeLast(4) else "(none)"
                    Log.d("AssistantHomeScreen", "Creating recognizer. url=$ghanaUrl keyLen=${ghanaKey.length} keySuffix=$keySuffix")

                    if (ghanaKey.isNotBlank()) {
                        val provider = GhanaNlpAsrProvider(
                            client = httpClient,
                            baseUrl = ghanaUrl,
                            apiKey = ghanaKey
                        )
                        SilenceRecordingAsrRecognizer(
                            context = context,
                            provider = provider,
                            onFinal = onFinal,
                            onPartial = onPartial,
                            onError = onError
                        )
                    } else {
                        AndroidSpeechRecognizerTwi(
                            context = context,
                            onFinal = onFinal,
                            onPartial = onPartial,
                            onErrorMessage = onError
                        )
                    }
                },
                englishRecognizerProvider = { onFinal, onPartial, onError ->
                    AndroidSpeechRecognizerEnglish(
                        context = context,
                        onFinal = onFinal,
                        onPartial = onPartial,
                        onErrorMessage = onError
                    )
                },
                translationApiKey = if (BuildConfig.GOOGLE_TRANSLATE_API_KEY.isNotBlank()) {
                    BuildConfig.GOOGLE_TRANSLATE_API_KEY
                } else if (BuildConfig.GHANA_NLP_TRANSLATION_KEY.isNotBlank()) {
                    BuildConfig.GHANA_NLP_TRANSLATION_KEY
                } else {
                    BuildConfig.GHANA_NLP_ASR_KEY
                },
                httpClient = httpClient,
                deviceActions = DeviceActions(context),
                ttsEngine = AndroidTts(context) // Enable TTS language switching for messaging
            ) as T
        }
    })
}

@Composable
private fun rememberHttpClient(): OkHttpClient {
    return remember {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            .build()
    }
}

// Sound Wave Visualization like in the reference image
@Composable
private fun SoundWaveVisualization(
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    
    // Create multiple animated values for wave bars
    val waves = (0..15).map { index ->
        infiniteTransition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 300 + (index * 50),
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "wave_$index"
        )
    }
    
    Canvas(modifier = modifier) {
        val barWidth = size.width / (waves.size * 2)
        val centerY = size.height / 2
        
        waves.forEachIndexed { index, animatedValue ->
            val barHeight = size.height * animatedValue.value * 0.8f
            val x = barWidth + (index * barWidth * 2)
            
            // Draw bar going up and down from center
            drawLine(
                color = color,
                start = Offset(x, centerY - barHeight / 2),
                end = Offset(x, centerY + barHeight / 2),
                strokeWidth = barWidth * 0.6f,
                cap = StrokeCap.Round
            )
        }
    }
}

// Ghana-themed number pad
@Composable
private fun GhanaNumberPad(onNumberClick: (Int) -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Row 1: 1, 2, 3
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            for (i in 1..3) {
                GhanaDialerButton(number = i, onClick = { onNumberClick(i) })
            }
        }
        
        // Row 2: 4, 5, 6
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            for (i in 4..6) {
                GhanaDialerButton(number = i, onClick = { onNumberClick(i) })
            }
        }
        
        // Row 3: 7, 8, 9
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            for (i in 7..9) {
                GhanaDialerButton(number = i, onClick = { onNumberClick(i) })
            }
        }
        
        // Row 4: ★(10), 0, #
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            // Star button (for menu option 10)
            GhanaDialerButton(label = "★", number = 10, color = GhanaGold, onClick = { onNumberClick(10) })
            // Zero button
            GhanaDialerButton(number = 0, onClick = { onNumberClick(0) })
            // Hash button (placeholder)
            GhanaDialerButton(label = "#", number = -1, color = GhanaRed, onClick = { /* No action */ })
        }
    }
}

@Composable
private fun GhanaDialerButton(
    number: Int,
    label: String = number.toString(),
    color: Color = SecondaryAmber, // Updated to use SecondaryAmber instead of GlowPink
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.2f))
            .border(
                width = 1.dp,
                color = color.copy(alpha = 0.5f),
                shape = CircleShape
            )
            .clickable { onClick() }
    ) {
        Text(
            text = label,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = TextLight
        )
    }
}

// Keep old versions for backward compatibility
@Composable
private fun NumberPadGrid(onNumberClick: (Int) -> Unit) {
    GhanaNumberPad(onNumberClick = onNumberClick)
}

@Composable
private fun DialerButton(
    number: Int,
    label: String = number.toString(),
    onClick: () -> Unit
) {
    GhanaDialerButton(number = number, label = label, onClick = onClick)
}

// Feature Card Component - Design Specification
@Composable
private fun FeatureCard(
    title: String,
    subtitle: String, 
    icon: String,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.95f),
        modifier = modifier
            .height(100.dp)
            .border(
                width = 2.dp,
                color = accentColor,
                shape = RoundedCornerShape(16.dp)
            )
            .shadow(4.dp, RoundedCornerShape(16.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White,
                            accentColor.copy(alpha = 0.05f)
                        )
                    )
                )
                .padding(12.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                // Icon
                Text(
                    text = icon,
                    fontSize = 32.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                
                // Twi title
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    textAlign = TextAlign.Center
                )
                
                // English subtitle
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// Sound Wave Icon for listening state
@Composable
private fun SoundWaveIcon(
    modifier: Modifier = Modifier,
    color: Color = TextLight
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sound_wave_icon")
    
    // Create animated values for 3 wave bars
    val waves = (0..2).map { index ->
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 400 + (index * 100),
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "wave_icon_$index"
        )
    }
    
    Canvas(modifier = modifier) {
        val barWidth = size.width / 5f
        val centerY = size.height / 2f
        val maxHeight = size.height * 0.8f
        
        waves.forEachIndexed { index, animatedValue ->
            val barHeight = maxHeight * animatedValue.value
            val x = barWidth + (index * barWidth * 1.2f)
            
            drawLine(
                color = color,
                start = Offset(x, centerY - barHeight / 2),
                end = Offset(x, centerY + barHeight / 2),
                strokeWidth = barWidth * 0.7f,
                cap = StrokeCap.Round
            )
        }
    }
}

// Contact Picker Overlay - Design Specification
@Composable
private fun ContactPickerOverlay(
    pendingChoice: AssistantViewModel.PendingChoice,
    onContactSelected: (DeviceActions.ContactMatch) -> Unit,
    onDismiss: () -> Unit
) {
    val contacts = when (pendingChoice) {
        is AssistantViewModel.PendingChoice.Call -> pendingChoice.candidates
        is AssistantViewModel.PendingChoice.SmsName -> pendingChoice.candidates
        is AssistantViewModel.PendingChoice.Sms -> pendingChoice.candidates
        else -> emptyList() // Handle other types that don't have candidates
    }
    
    val title = when (pendingChoice) {
        is AssistantViewModel.PendingChoice.Call -> {
            // Check if there are multiple contacts with same name
            val uniqueNames = contacts.map { it.displayName.lowercase() }.distinct()
            if (uniqueNames.size == 1 && contacts.size > 1) {
                "Hwan na wopɛ sɛ wofrɛ? (Hwɛ nɔma mu)" // Which one do you want to call? (Check the numbers)
            } else {
                "Hwan na wopɛ sɛ wofrɛ?" // Which one do you want to call?
            }
        }
        is AssistantViewModel.PendingChoice.SmsName -> {
            val uniqueNames = contacts.map { it.displayName.lowercase() }.distinct()
            if (uniqueNames.size == 1 && contacts.size > 1) {
                "Hwan na wopɛ sɛ wokyerɛw? (Hwɛ nɔma mu)" // Which one do you want to text? (Check the numbers)
            } else {
                "Hwan na wopɛ sɛ wokyerɛw?" // Which one do you want to text?
            }
        }
        is AssistantViewModel.PendingChoice.Sms -> {
            val uniqueNames = contacts.map { it.displayName.lowercase() }.distinct()
            if (uniqueNames.size == 1 && contacts.size > 1) {
                "Hwan na wopɛ sɛ wokyerɛw? (Hwɛ nɔma mu)" // Which one do you want to text? (Check the numbers)
            } else {
                "Hwan na wopɛ sɛ wokyerɛw?" // Which one do you want to text?
            }
        }
        else -> "Fa baako" // Choose one
    }
    
    // Semi-transparent overlay
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        // Contact Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFFFFBEB), // Very subtle yellow tint
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .border(
                    width = 2.dp,
                    color = GhanaRed,
                    shape = RoundedCornerShape(16.dp)
                )
                .shadow(16.dp, RoundedCornerShape(16.dp))
                .clickable(enabled = false) { } // Prevent dismissing when clicking card
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                // Header
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = GhanaRed,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Text(
                    text = "Me huu nnipa ${contacts.size}:",
                    fontSize = 16.sp,
                    color = TextDark,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 16.dp)
                )
                
                // Contact List
                contacts.forEachIndexed { index, contact ->
                    ContactItem(
                        contact = contact,
                        index = index + 1,
                        allContacts = contacts,
                        onClick = { onContactSelected(contact) }
                    )
                    if (index < contacts.size - 1) {
                        Spacer(Modifier.height(8.dp))
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                // Close button
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Ka",
                        color = TextMuted
                    )
                }
            }
        }
    }
}

// Helper function to generate unique identifier for contact
private fun getContactIdentifier(contact: DeviceActions.ContactMatch, allContacts: List<DeviceActions.ContactMatch>): AnnotatedString {
    // Check if there are other contacts with same name
    val sameNameContacts = allContacts.filter { it.displayName.equals(contact.displayName, ignoreCase = true) }
    
    return if (sameNameContacts.size > 1) {
        // Multiple contacts with same name - show full number with last two digits bolded
        val cleanNumber = contact.number.replace("[^0-9]".toRegex(), "")
        if (cleanNumber.length >= 2) {
            val firstPart = cleanNumber.dropLast(2)
            val lastTwo = cleanNumber.takeLast(2)
            buildAnnotatedString {
                withStyle(style = SpanStyle(color = GhanaGreen, fontWeight = FontWeight.Medium)) {
                    append(firstPart)
                }
                withStyle(style = SpanStyle(color = GhanaGreen, fontWeight = FontWeight.Bold)) {
                    append(lastTwo)
                }
            }
        } else {
            buildAnnotatedString {
                withStyle(style = SpanStyle(color = GhanaGreen, fontWeight = FontWeight.Medium)) {
                    append(cleanNumber)
                }
            }
        }
    } else {
        // Single contact with this name - show phone type or carrier hint
        val carrierName = when {
            contact.number.startsWith("024") || contact.number.startsWith("+233 24") -> "MTN"
            contact.number.startsWith("054") || contact.number.startsWith("+233 54") -> "MTN"
            contact.number.startsWith("055") || contact.number.startsWith("+233 55") -> "MTN"
            contact.number.startsWith("059") || contact.number.startsWith("+233 59") -> "MTN"
            contact.number.startsWith("027") || contact.number.startsWith("+233 27") -> "Vodafone"
            contact.number.startsWith("020") || contact.number.startsWith("+233 20") -> "Vodafone"
            contact.number.startsWith("050") || contact.number.startsWith("+233 50") -> "Vodafone"
            contact.number.startsWith("023") || contact.number.startsWith("+233 23") -> "AirtelTigo"
            contact.number.startsWith("026") || contact.number.startsWith("+233 26") -> "AirtelTigo"
            contact.number.startsWith("056") || contact.number.startsWith("+233 56") -> "AirtelTigo"
            contact.number.startsWith("057") || contact.number.startsWith("+233 57") -> "AirtelTigo"
            contact.number.length >= 2 -> {
                // Fallback to full number with last two digits bolded
                val cleanNumber = contact.number.replace("[^0-9]".toRegex(), "")
                val firstPart = cleanNumber.dropLast(2)
                val lastTwo = cleanNumber.takeLast(2)
                return buildAnnotatedString {
                    withStyle(style = SpanStyle(color = GhanaGreen, fontWeight = FontWeight.Medium)) {
                        append(firstPart)
                    }
                    withStyle(style = SpanStyle(color = GhanaGreen, fontWeight = FontWeight.Bold)) {
                        append(lastTwo)
                    }
                }
            }
            else -> "Mobile"
        }
        buildAnnotatedString {
            withStyle(style = SpanStyle(color = GhanaGreen, fontWeight = FontWeight.Medium)) {
                append(carrierName)
            }
        }
    }
}

// Helper function to get contact description for TTS
private fun getContactDescription(contact: DeviceActions.ContactMatch, allContacts: List<DeviceActions.ContactMatch>): String {
    val sameNameContacts = allContacts.filter { it.displayName.equals(contact.displayName, ignoreCase = true) }
    
    return if (sameNameContacts.size > 1) {
        val cleanNumber = contact.number.replace("[^0-9]".toRegex(), "")
        val lastTwoDigits = if (cleanNumber.length >= 2) {
            cleanNumber.takeLast(2)
        } else {
            cleanNumber
        }
        "${contact.displayName} ${lastTwoDigits}" // For TTS: "John 45"
    } else {
        contact.displayName
    }
}

@Composable
private fun ContactItem(
    contact: DeviceActions.ContactMatch,
    index: Int,
    allContacts: List<DeviceActions.ContactMatch>,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = Color(0xFFE5E7EB), // gray-200
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            // Number badge
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .background(GhanaGreen, CircleShape)
            ) {
                Text(
                    text = index.toString(),
                    color = TextLight,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            
            Spacer(Modifier.width(16.dp))
            
            // Contact info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = contact.displayName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDark,
                        modifier = Modifier.weight(1f)
                    )
                    
                    // Unique identifier (full number with bolded last two digits or carrier)
                    Text(
                        text = getContactIdentifier(contact, allContacts),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Text(
                    text = contact.number,
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }
            
            // Call icon
            Text(
                text = "📞",
                fontSize = 20.sp,
                color = GhanaGreen
            )
        }
    }
}

@Composable
private fun MessageTypePickerOverlay(
    contact: DeviceActions.ContactMatch,
    onSmsSelected: (DeviceActions.ContactMatch) -> Unit,
    onWhatsAppSelected: (DeviceActions.ContactMatch) -> Unit,
    onDismiss: () -> Unit
) {
    // Semi-transparent overlay
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        // Message Type Choice Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFFFFBEB), // Very subtle yellow tint
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .clickable { } // Prevent dismissing when clicking card
                .border(
                    width = 2.dp,
                    color = GhanaGold,
                    shape = RoundedCornerShape(16.dp)
                )
                .shadow(16.dp, RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Text(
                    text = "Message ${contact.displayName}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = GhanaRed,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Wopɛ sɛ wode S.M.S. anaa WhatsApp?",
                    fontSize = 16.sp,
                    color = TextDark,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // SMS Button
                Surface(
                    onClick = { onSmsSelected(contact) },
                    shape = RoundedCornerShape(12.dp),
                    color = GhanaGreen.copy(alpha = 0.1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, GhanaGreen, RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "💬",
                            fontSize = 20.sp
                        )
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        Text(
                            text = "S.M.S.",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = GhanaGreen
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // WhatsApp Button
                Surface(
                    onClick = { onWhatsAppSelected(contact) },
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF25D366).copy(alpha = 0.1f), // WhatsApp green
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF25D366), RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "💚",
                            fontSize = 20.sp
                        )
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        Text(
                            text = "Whats App",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF25D366)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Cancel button
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Ka",
                        color = TextMuted
                    )
                }
            }
        }
    }
}

/**
 * Display homework results with images and bilingual content
 * Shows English content for reading with Twi captions
 */
@Composable
fun HomeworkResultsCard(
    results: List<com.example.twiassistant.homework.QuestionAnswer>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
    ) {
        results.forEach { result: com.example.twiassistant.homework.QuestionAnswer ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .border(
                        width = 2.dp,
                        color = SecondaryAmber,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .shadow(4.dp, RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    // Images if available
                    if (result.imageUrls.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            result.imageUrls.forEach { imageUrl: String ->
                                AsyncImage(
                                    model = imageUrl,
                                    contentDescription = "Related image",
                                    modifier = Modifier
                                        .size(120.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, SecondaryAmber.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    
                    // Answer in English (for reading/display)
                    if (result.answerEnglish.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = GhanaGreen.copy(alpha = 0.1f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = "📖",
                                        fontSize = 18.sp,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "English:",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = GhanaGreen
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = result.answerEnglish,
                                            fontSize = 14.sp,
                                            color = TextDark,
                                            lineHeight = 20.sp
                                        )
                                    }
                                }
                            }
                        }
                        
                        Spacer(Modifier.height(8.dp))
                    }
                    
                    // Answer in Twi (caption - what was spoken)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SecondaryAmber.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "🔊",
                                    fontSize = 18.sp,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Twi (Nsɛm a ɛka):",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SecondaryAmber
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = result.answerTwi,
                                        fontSize = 14.sp,
                                        color = TextDark,
                                        lineHeight = 20.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
