package com.vaada

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.vaada.models.AssistantState
import com.vaada.models.AuthState
import com.vaada.models.KhataUiState
import com.vaada.models.MerchantProfile
import com.vaada.models.VoiceAuthSession
import com.vaada.service.VaadaAudioService
import com.vaada.ui.assistant.HomeScreen
import com.vaada.ui.auth.LoginScreen
import com.vaada.ui.auth.OtpScreen
import com.vaada.ui.auth.SignupScreen
import com.vaada.ui.auth.VoiceVerifyScreen
import com.vaada.ui.auth.WelcomeScreen
import com.vaada.ui.components.VaadaBottomBar
import com.vaada.ui.ledger.KhataScreen
import com.vaada.ui.navigation.VaadaDestination
import com.vaada.ui.settings.SettingsScreen
import com.vaada.ui.theme.VaadaTheme
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import com.vaada.network.KhataApiClient
import com.vaada.network.MerchantAuthClient
import com.vaada.config.ServerConfig
import com.vaada.data.SessionStore
import com.vaada.models.AppLanguage
import com.vaada.models.LocalUiStrings
import com.vaada.models.UiStrings
import com.vaada.ui.language.LanguageSelectionScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServerConfig.init(applicationContext)
        setContent {
            VaadaTheme {
                val context = LocalContext.current
                val sessionStore = remember { SessionStore(context) }
                var language by remember { mutableStateOf(sessionStore.language) }
                var serverTick by remember { mutableStateOf(0) }
                LaunchedEffect(language) { language?.let { VaadaAudioService.setLanguage(it.locale) } }
                val assistantState by VaadaAudioService.assistantState.collectAsState()
                val lastMetadata by VaadaAudioService.lastCapturedMetadata.collectAsState()
                val turnView by VaadaAudioService.turnView.collectAsState()
                val detectionCount by VaadaAudioService.wakeWordDetectionCount.collectAsState()
                val lastScore by VaadaAudioService.lastWakeWordScore.collectAsState()
                val threshold by VaadaAudioService.wakeWordThreshold.collectAsState()
                val lastEvaluatedScore by VaadaAudioService.lastEvaluatedScore.collectAsState()

                // Voice auth session — observed from service companion StateFlow
                val voiceAuthSession by VaadaAudioService.voiceAuthSession.collectAsState()

                // ── Permissions ───────────────────────────────────────────────
                val requiredPermissions = remember {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        arrayOf(Manifest.permission.RECORD_AUDIO)
                    }
                }
                var hasPermissions by remember {
                    mutableStateOf(requiredPermissions.all {
                        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                    })
                }
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { results -> hasPermissions = results.values.all { it } }

                LaunchedEffect(Unit) {
                    if (!hasPermissions) permissionLauncher.launch(requiredPermissions)
                }

                // ── Auth state ────────────────────────────────────────────────
                var authState: AuthState by remember {
                    mutableStateOf(if (sessionStore.loadToken() != null) AuthState.Restoring else AuthState.Unauthenticated)
                }
                var merchantProfile: MerchantProfile? by remember { mutableStateOf(null) }
                var merchantToken by remember { mutableStateOf<String?>(null) }
                var loginPin by remember { mutableStateOf("") }
                var signupPin by remember { mutableStateOf("") }
                val merchantAuthClient = remember { MerchantAuthClient() }

                // ── Voice Verify: enrollment-capture recording state ──────────
                // Tracks whether the user is currently recording for voice verification
                // (distinct from wake-word recording which is managed by VaadaAudioService).
                var isVerifyRecording by remember { mutableStateOf(false) }
                var verifyAmplitudeRms by remember { mutableStateOf(0f) }
                val scope = rememberCoroutineScope()

                // ── Khata (ledger) state ──────────────────────────────────────
                var khataState by remember {
                    mutableStateOf<KhataUiState>(KhataUiState.Loading)
                }

                fun loadKhata() {
                    val token = merchantToken
                    if (token.isNullOrBlank()) {
                        khataState = KhataUiState.Error("Your session is missing. Please log in again.")
                        return
                    }

                    khataState = KhataUiState.Loading
                    scope.launch {
                        try {
                            // Every protected ledger request uses the authenticated merchant session.
                            val customers = KhataApiClient(sessionToken = token).loadCustomers()
                            khataState = KhataUiState.Success(
                                customers = customers,
                                totalOutstanding = customers.sumOf { it.currentBalance },
                                outstandingCount = customers.count { it.currentBalance > 0 }
                            )
                        } catch (e: com.vaada.network.KhataApiException) {
                            if (e.statusCode == 401) {
                                merchantToken = null
                                    VaadaAudioService.setMerchantToken(null)
                                merchantProfile = null
                                VaadaAudioService.resetVoiceAuth()
                                authState = AuthState.Unauthenticated
                                khataState = KhataUiState.Error("Your session expired. Please log in again.")
                            } else {
                                khataState = KhataUiState.Error(e.message ?: "Unable to load customer accounts")
                            }
                        } catch (e: Exception) {
                            khataState = KhataUiState.Error(
                                e.message ?: "Unable to load customer accounts"
                            )
                        }
                    }
                }

                // ── Navigation state ──────────────────────────────────────────
                var currentDestination by remember { mutableStateOf(VaadaDestination.Assistant) }

                // After a successful login/registration/restore: pick language first unless one is saved.
                fun afterSignIn() {
                    authState = if (sessionStore.language != null) AuthState.Authenticated else AuthState.LanguageSelect()
                    loadKhata()
                }

                // ── Restore a stored session: validate with GET /auth/me, never trust it blindly ──
                LaunchedEffect(Unit) {
                    if (authState is AuthState.Restoring) {
                        val stored = sessionStore.loadToken()
                        if (stored == null) { authState = AuthState.Unauthenticated; return@LaunchedEffect }
                        try {
                            val p = merchantAuthClient.getMe(stored)
                            merchantToken = stored
                            VaadaAudioService.setMerchantToken(stored)
                            merchantProfile = MerchantProfile(p.userId.toString(), p.name, p.shopName, p.phone)
                            afterSignIn()
                        } catch (e: com.vaada.network.MerchantAuthException) {
                            if (e.httpCode == 401 || e.httpCode == 403) sessionStore.clearToken()
                            authState = AuthState.Unauthenticated
                        } catch (e: Exception) {
                            // Network down: keep the stored token for the next start, but do not enter the app unverified.
                            authState = AuthState.Unauthenticated
                        }
                    }
                }

                // ── Voice auth: auto-proceed when verified ────────────────────
                LaunchedEffect(voiceAuthSession, authState) {
                    if (authState is AuthState.VoiceVerify &&
                        voiceAuthSession is VoiceAuthSession.Verified
                    ) {
                        // Brief pause so the user sees the green checkmark
                        kotlinx.coroutines.delay(1200)
                        authState = AuthState.Authenticated
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {

                    CompositionLocalProvider(
                        LocalUiStrings provides UiStrings.of(language ?: AppLanguage.ENGLISH),
                        com.vaada.models.LocalAppLanguage provides (language ?: AppLanguage.ENGLISH)
                    ) {
                    when (authState) {
                        is AuthState.Restoring -> {
                            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                androidx.compose.material3.CircularProgressIndicator()
                            }
                        }
                        is AuthState.LanguageSelect -> {
                            val ls = authState as AuthState.LanguageSelect
                            LanguageSelectionScreen(
                                initial = language,
                                onConfirm = { chosen ->
                                    sessionStore.language = chosen
                                    language = chosen
                                    authState = AuthState.Authenticated
                                },
                                onBack = if (ls.canGoBack) ({ authState = AuthState.Authenticated }) else null
                            )
                        }
                        // ── Auth screens ───────────────────────────────────────────
                        is AuthState.Unauthenticated -> {
                            WelcomeScreen(
                                onLoginClick = { loginPin = ""; authState = AuthState.PhoneEntry() },
                                onSignupClick = { signupPin = ""; authState = AuthState.SignupEntry(phone = "") }
                            )
                        }

                        is AuthState.PhoneEntry -> {
                            val s = authState as AuthState.PhoneEntry
                            LoginScreen(
                                phone = s.phone,
                                pin = loginPin,
                                onPhoneChange = { authState = s.copy(phone = it, error = null) },
                                onPinChange = { loginPin = it },
                                loading = s.loading,
                                error = s.error,
                                onLogin = {
                                    if (s.phone.length != 10 || loginPin.length != 6) {
                                        authState = s.copy(error = "Enter your 10-digit phone number and 6-digit PIN.")
                                    } else {
                                        authState = s.copy(loading = true, error = null)
                                        scope.launch {
                                            try {
                                                val session = merchantAuthClient.login(s.phone, loginPin)
                                                merchantToken = session.token
                                                VaadaAudioService.setMerchantToken(session.token)
                                                merchantProfile = MerchantProfile(
                                                    id = session.profile.userId.toString(),
                                                    merchantName = session.profile.name,
                                                    shopName = session.profile.shopName,
                                                    phone = session.profile.phone
                                                )
                                                loginPin = ""
                                                VaadaAudioService.resetVoiceAuth()
                                                sessionStore.saveToken(session.token)
                                                afterSignIn()
                                            } catch (e: Exception) {
                                                authState = (authState as? AuthState.PhoneEntry
                                                    ?: s).copy(
                                                    loading = false,
                                                    error = e.message ?: "Login failed. Please try again."
                                                )
                                            }
                                        }
                                    }
                                },
                                onBack = {
                                    loginPin = ""
                                    authState = AuthState.Unauthenticated
                                }
                            )
                        }

                        is AuthState.OtpEntry -> {
                            // OTP is not part of the current backend contract. This legacy state
                            // is retained in AuthState for compatibility but is never entered.
                            WelcomeScreen(
                                onLoginClick = { loginPin = ""; authState = AuthState.PhoneEntry() },
                                onSignupClick = { signupPin = ""; authState = AuthState.SignupEntry(phone = "") }
                            )
                        }

                        is AuthState.SignupEntry -> {
                            val s = authState as AuthState.SignupEntry
                            SignupScreen(
                                phone = s.phone,
                                pin = signupPin,
                                merchantName = s.merchantName,
                                shopName = s.shopName,
                                onPhoneChange = { authState = s.copy(phone = it, error = null) },
                                onPinChange = { signupPin = it },
                                onMerchantNameChange = { authState = s.copy(merchantName = it, error = null) },
                                onShopNameChange = { authState = s.copy(shopName = it, error = null) },
                                loading = s.loading,
                                error = s.error,
                                onSubmit = {
                                    if (s.phone.length != 10 || signupPin.length != 6 ||
                                        s.merchantName.isBlank() || s.shopName.isBlank()) {
                                        authState = s.copy(error = "Fill in all fields and enter a 6-digit PIN.")
                                    } else {
                                        authState = s.copy(loading = true, error = null)
                                        scope.launch {
                                            try {
                                                val session = merchantAuthClient.register(
                                                    phone = s.phone,
                                                    name = s.merchantName.trim(),
                                                    shopName = s.shopName.trim(),
                                                    pin = signupPin
                                                )
                                                merchantToken = session.token
                                                VaadaAudioService.setMerchantToken(session.token)
                                                merchantProfile = MerchantProfile(
                                                    id = session.profile.userId.toString(),
                                                    merchantName = session.profile.name,
                                                    shopName = session.profile.shopName,
                                                    phone = session.profile.phone
                                                )
                                                signupPin = ""
                                                VaadaAudioService.resetVoiceAuth()
                                                sessionStore.saveToken(session.token)
                                                afterSignIn()
                                            } catch (e: Exception) {
                                                authState = (authState as? AuthState.SignupEntry
                                                    ?: s).copy(
                                                    loading = false,
                                                    error = e.message ?: "Registration failed. Please try again."
                                                )
                                            }
                                        }
                                    }
                                },
                                onBack = {
                                    signupPin = ""
                                    authState = AuthState.Unauthenticated
                                }
                            )
                        }

                        // ── Voice Verification screen ──────────────────────────────
                        is AuthState.VoiceVerify -> {
                            val s = authState as AuthState.VoiceVerify

                            VoiceVerifyScreen(
                                profile = s.profile,
                                voiceAuthSession = voiceAuthSession,
                                isRecording = isVerifyRecording,
                                amplitudeRms = verifyAmplitudeRms,
                                onStartRecording = {
                                    if (!hasPermissions) {
                                        permissionLauncher.launch(requiredPermissions)
                                        return@VoiceVerifyScreen
                                    }
                                    // Guard: service must NOT be running so wake-word doesn't conflict
                                    // If service is running, we still use a separate AudioRecord
                                    // by launching our own capture below.
                                    if (isVerifyRecording) return@VoiceVerifyScreen
                                    isVerifyRecording = true
                                    verifyAmplitudeRms = 0f
                                    scope.launch {
                                        captureAndVerify(
                                            context = context,
                                            profile = s.profile,
                                            onAmplitude = { verifyAmplitudeRms = it },
                                            onDone = { isVerifyRecording = false }
                                        )
                                    }
                                },
                                onStopRecording = {
                                    // User manually stopped early — handled inside captureAndVerify
                                    isVerifyRecording = false
                                },
                                onProceedAnyway = {
                                    // Only when voice auth is Unavailable — proceed to dashboard
                                    authState = AuthState.Authenticated
                                },
                                onLogout = {
                                    // Clear session and go back to Welcome
                                    val currentSession = voiceAuthSession
                                    if (currentSession is VoiceAuthSession.Verified) {
                                        scope.launch {
                                            // Fire-and-forget logout; non-fatal
                                            try {
                                                com.vaada.network.VoiceAuthClient()
                                                    .logout(currentSession.token)
                                            } catch (_: Exception) {}
                                        }
                                    }
                                    VaadaAudioService.resetVoiceAuth()
                                    merchantToken = null
                                    sessionStore.clearToken()
                                    VaadaAudioService.setMerchantToken(null)
                                    merchantProfile = null
                                    authState = AuthState.Unauthenticated
                                }
                            )
                        }

                        // ── Main app (authenticated) ───────────────────────────────
                        is AuthState.Authenticated -> {
                            Scaffold(
                                bottomBar = {
                                    VaadaBottomBar(
                                        currentDestination = currentDestination,
                                        onNavigate = {
                                            currentDestination = it
                                            if (it == VaadaDestination.Ledger) {
                                                loadKhata()
                                            }
                                        }
                                    )
                                }
                            ) { innerPadding ->
                                when (currentDestination) {
                                    VaadaDestination.Assistant -> {
                                        HomeScreen(
                                            state = assistantState,
                                            turn = turnView,
                                            hasPermissions = hasPermissions,
                                            lastMetadata = lastMetadata,
                                            shopName = merchantProfile?.shopName ?: LocalUiStrings.current.yourShop,
                                            language = language ?: AppLanguage.ENGLISH,
                                            onLanguageChange = { chosen -> sessionStore.language = chosen; language = chosen },
                                            onReplay = { VaadaAudioService.replayLast(context) },
                                            onRetry = { VaadaAudioService.retryLast(context) },
                                            onStartService = { VaadaAudioService.startService(context) },
                                            onStopService  = { VaadaAudioService.stopService(context) },
                                            onStartRecording = { VaadaAudioService.startRecording(context) },
                                            onStopRecording  = { VaadaAudioService.stopRecording(context) },
                                            onRequestPermissions = { permissionLauncher.launch(requiredPermissions) },
                                            modifier = Modifier.padding(innerPadding)
                                        )
                                    }

                                    VaadaDestination.Ledger -> {
                                        KhataScreen(
                                            uiState  = khataState,
                                            onRetry  = { loadKhata() },
                                            modifier = Modifier.padding(innerPadding)
                                        )
                                    }

                                    VaadaDestination.Settings -> {
                                        SettingsScreen(
                                            state              = assistantState,
                                            hasPermissions     = hasPermissions,
                                            detectionCount     = detectionCount,
                                            lastTriggerScore   = lastScore,
                                            threshold          = threshold,
                                            lastEvaluatedScore = lastEvaluatedScore,
                                            metadata           = lastMetadata,
                                            onStartService     = { VaadaAudioService.startService(context) },
                                            onStopService      = { VaadaAudioService.stopService(context) },
                                            onStopRecording    = { VaadaAudioService.stopRecording(context) },
                                            merchantName       = merchantProfile?.merchantName ?: "",
                                            shopName           = merchantProfile?.shopName ?: "",
                                            phone              = merchantProfile?.phone ?: "",
                                            language           = language ?: AppLanguage.ENGLISH,
                                            onChangeLanguage   = { authState = AuthState.LanguageSelect(canGoBack = true) },
                                            gatewayUrl         = ServerConfig.gatewayUrl,
                                            ledgerUrl          = ServerConfig.ledgerUrl,
                                            currentHost        = ServerConfig.currentHost,
                                            onSaveHost         = { h -> ServerConfig.setHost(context, h).also { ok -> if (ok) serverTick++ } },
                                            onLogout           = {
                                                val token = merchantToken
                                                scope.launch {
                                                    try {
                                                        if (!token.isNullOrBlank()) merchantAuthClient.logout(token)
                                                    } catch (e: Exception) {
                                                        android.util.Log.w("MainActivity", "Merchant logout request failed", e)
                                                    } finally {
                                                        merchantToken = null
                                                        sessionStore.clearToken()
                                    VaadaAudioService.setMerchantToken(null)
                                                        merchantProfile = null
                                                        loginPin = ""
                                                        signupPin = ""
                                                        VaadaAudioService.resetVoiceAuth()
                                                        authState = AuthState.Unauthenticated
                                                    }
                                                }
                                            },
                                            modifier = Modifier.padding(innerPadding)
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
    }

    /**
     * Capture audio for voice verification using a dedicated AudioRecord instance.
     * This is separate from VaadaAudioService's AudioCaptureManager so it does NOT
     * conflict with wake-word detection (which runs in the service).
     *
     * Records up to 6 seconds, targeting ≥ 2 s of active speech as required by
     * docs/voice_auth.md (verify_min_total_s=1.5, verify_min_speech_s=1.2).
     */
    @Suppress("MissingPermission")
    private suspend fun captureAndVerify(
        context: android.content.Context,
        profile: MerchantProfile,
        onAmplitude: (Float) -> Unit,
        onDone: () -> Unit
    ) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val TAG = "VoiceVerifyCapture"
        android.util.Log.i(TAG, "Starting voice capture for verification. accountId=${profile.id}")

        val sampleRate = 16000
        val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
        val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
        val minBuf = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufSize = maxOf(minBuf * 2, 4096)

        val recorder = try {
            android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, channelConfig, audioFormat, bufSize
            ).also {
                if (it.state != android.media.AudioRecord.STATE_INITIALIZED) {
                    android.util.Log.e(TAG, "AudioRecord init failed")
                    VaadaAudioService.setVoiceAuthSession(
                        com.vaada.models.VoiceAuthSession.NetworkError("Microphone init failed")
                    )
                    onDone()
                    return@withContext
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "AudioRecord creation failed", e)
            VaadaAudioService.setVoiceAuthSession(
                com.vaada.models.VoiceAuthSession.NetworkError("Mic unavailable: ${e.message}")
            )
            onDone()
            return@withContext
        }

        try {
            recorder.startRecording()
            val byteStream = java.io.ByteArrayOutputStream()
            val buffer = ShortArray(bufSize / 2)
            val maxMs = 6000L
            val startMs = System.currentTimeMillis()

            while (System.currentTimeMillis() - startMs < maxMs) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) break
                // Amplitude feedback
                val rms = (buffer.take(read).map { it.toLong() * it.toLong() }
                    .average().let { kotlin.math.sqrt(it) } / 32767.0).toFloat()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onAmplitude(rms)
                }
                // Convert shorts to bytes and accumulate
                for (i in 0 until read) {
                    byteStream.write(buffer[i].toInt() and 0xFF)
                    byteStream.write((buffer[i].toInt() shr 8) and 0xFF)
                }
            }

            recorder.stop()
            val pcmBytes = byteStream.toByteArray()
            android.util.Log.i(TAG, "Captured ${pcmBytes.size} PCM bytes for voice verify")

            // Convert PCM → WAV
            val wavFile = File(context.cacheDir, "voice_verify_${System.currentTimeMillis()}.wav")
            writePcmToWav(pcmBytes, wavFile)

            android.util.Log.i(TAG, "WAV ready: ${wavFile.length()} bytes → uploading to /v1/auth/verify")

            // Send to service for verification (service has VoiceAuthClient)
            // We bind to service instance, but since the service may not be started,
            // we directly call a suspend function through a service instance.
            // Architecture note: use a local instance of VoiceAuthClient here since
            // VaadaAudioService may not be running during the auth phase.
            val client = com.vaada.network.VoiceAuthClient()
            VaadaAudioService.setVoiceAuthSession(com.vaada.models.VoiceAuthSession.Verifying)
            try {
                val result = client.verify(
                    accountId = profile.id,
                    sessionId = "verify-${profile.id}", // separate from voice-turn session
                    wavFile = wavFile
                )
                android.util.Log.i(TAG, "verify result: status=${result.status} reason=${result.reason}")
                val newSession: com.vaada.models.VoiceAuthSession = when (result.status) {
                    "verified" -> {
                        val token = result.authToken
                        if (token != null) {
                            val expiresAt = System.currentTimeMillis() + ((result.expiresInS ?: 300) * 1000L)
                            com.vaada.models.VoiceAuthSession.Verified(
                                accountId = profile.id,
                                sessionId = "verify-${profile.id}",
                                token = token,
                                expiresAtMs = expiresAt
                            )
                        } else {
                            com.vaada.models.VoiceAuthSession.NetworkError("Server verified but returned no auth_token")
                        }
                    }
                    "inconclusive" -> com.vaada.models.VoiceAuthSession.Inconclusive(result.reason)
                    "rejected" -> when (result.reason) {
                        "locked" -> com.vaada.models.VoiceAuthSession.Locked(
                            retryAfterMs = System.currentTimeMillis() + ((result.retryAfterS ?: 300) * 1000L)
                        )
                        "not_enrolled" -> com.vaada.models.VoiceAuthSession.NotEnrolled(accountId = profile.id)
                        else -> com.vaada.models.VoiceAuthSession.Rejected(reason = result.reason)
                    }
                    "unavailable" -> com.vaada.models.VoiceAuthSession.Unavailable(result.reason)
                    else -> com.vaada.models.VoiceAuthSession.NetworkError("Unknown status: ${result.status}")
                }
                VaadaAudioService.setVoiceAuthSession(newSession)
            } catch (e: java.io.IOException) {
                android.util.Log.e(TAG, "verify network error", e)
                VaadaAudioService.setVoiceAuthSession(
                    com.vaada.models.VoiceAuthSession.NetworkError(e.message ?: "Network error")
                )
            } finally {
                try { wavFile.delete() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "captureAndVerify error", e)
            VaadaAudioService.setVoiceAuthSession(
                com.vaada.models.VoiceAuthSession.NetworkError(e.message ?: "Capture error")
            )
        } finally {
            try { recorder.release() } catch (_: Exception) {}
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onDone()
            }
        }
    }

    /**
     * Write PCM bytes to a valid WAV file (PCM16, mono, 16 kHz).
     * Same algorithm as VaadaAudioService.pcmToWav — duplicated here because
     * this runs in MainActivity context (outside the service).
     */
    private fun writePcmToWav(pcmBytes: ByteArray, wavFile: File) {
        val sampleRate = 16000
        val channels: Short = 1
        val bitsPerSample: Short = 16
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = (channels * (bitsPerSample / 8)).toShort()
        val dataLen = pcmBytes.size
        val chunkSize = 36 + dataLen
        fun Int.le4(): ByteArray = byteArrayOf(
            (this and 0xFF).toByte(), ((this shr 8) and 0xFF).toByte(),
            ((this shr 16) and 0xFF).toByte(), ((this shr 24) and 0xFF).toByte()
        )
        fun Short.le2(): ByteArray = byteArrayOf(
            (this.toInt() and 0xFF).toByte(), ((this.toInt() shr 8) and 0xFF).toByte()
        )
        FileOutputStream(wavFile).use { out ->
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.write(chunkSize.le4())
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.write(16.le4())
            out.write((1).toShort().le2())
            out.write(channels.le2())
            out.write(sampleRate.le4())
            out.write(byteRate.le4())
            out.write(blockAlign.le2())
            out.write(bitsPerSample.le2())
            out.write("data".toByteArray(Charsets.US_ASCII))
            out.write(dataLen.le4())
            out.write(pcmBytes)
        }
    }
}
