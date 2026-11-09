package com.openclaw.assistant

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.service.HotwordService
import com.openclaw.assistant.service.OpenClawAssistantService
import com.openclaw.assistant.speech.TTSUtils
import com.openclaw.assistant.speech.diagnostics.DiagnosticStatus
import com.openclaw.assistant.speech.diagnostics.VoiceDiagnostic
import com.openclaw.assistant.speech.diagnostics.VoiceDiagnostics
import com.openclaw.assistant.ui.components.CollapsibleSection
import com.openclaw.assistant.ui.theme.VoiceRelayTheme
import com.openclaw.assistant.ui.SetupGuideScreen

data class PermissionStatusInfo(
    val permissionName: String,
    val isGranted: Boolean
)

data class PermissionInfo(
    val permission: String,
    val nameResId: Int,
    val descResId: Int
)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var settings: SettingsRepository
    private var tts: TextToSpeech? = null
    private var voiceDiagnostic by mutableStateOf<VoiceDiagnostic?>(null)
    private var missingPermissions by mutableStateOf<List<PermissionInfo>>(emptyList())
    private var allPermissionsStatus by mutableStateOf<List<PermissionStatusInfo>>(emptyList())
    private var pendingHotwordStart = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

        if (pendingHotwordStart) {
            pendingHotwordStart = false
            if (recordAudioGranted) {
                settings.hotwordEnabled = true
                HotwordService.start(this)
                Toast.makeText(this, getString(R.string.hotword_started), Toast.LENGTH_SHORT).show()
            } else {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                    showPermissionSettingsDialog()
                } else {
                    showPermissionDeniedDialog()
                }
            }
        } else {
            if (!permissions.values.all { it }) {
                // Some permissions were denied
            }
        }
        refreshMissingPermissions()
        refreshAllPermissionsStatus()
    }

    override fun attachBaseContext(newBase: Context) {
        // ComponentActivity does not participate in AppCompat's locale delegation,
        // so we must manually apply the saved locale here. This guarantees every
        // new instance (whether created by recreate() or a system config change)
        // immediately has the correct locale without relying on timing.
        val tag = try {
            SettingsRepository.getInstance(newBase).appLanguage.trim()
        } catch (e: Exception) { "" }
        if (tag.isNotBlank()) {
            val locale = java.util.Locale.forLanguageTag(tag)
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository.getInstance(this)

        initializeTTS()
        // Removed checkPermissions() from onCreate to allow SetupGuideScreen to handle it
        refreshMissingPermissions()
        refreshAllPermissionsStatus()

        setContent {
            VoiceRelayTheme {
                val hasCompletedSetup by remember { mutableStateOf(settings.hasCompletedSetup) }
                var showSetupGuide by remember { mutableStateOf(!hasCompletedSetup) }

                if (showSetupGuide) {
                    SetupGuideScreen(
                        settings = settings,
                        onComplete = {
                            showSetupGuide = false
                            // After setup, we might want to trigger permission refresh or other once
                            refreshMissingPermissions()
                            refreshAllPermissionsStatus()
                        }
                    )
                } else {
                    MainScreen(
                        settings = settings,
                        diagnostic = voiceDiagnostic,
                        missingPermissions = missingPermissions,
                        allPermissionsStatus = allPermissionsStatus,
                        onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                        onOpenAssistantSettings = { openAssistantSettings() },
                        onRefreshDiagnostics = {
                            initializeTTS() // Re-init on manual refresh
                            refreshAllPermissionsStatus()
                        },
                        onRequestPermissions = { permissions ->
                            val ungranted = permissions.filter {
                                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                            }
                            if (ungranted.isNotEmpty()) {
                                permissionLauncher.launch(ungranted.toTypedArray())
                            }
                        },
                        onOpenAppSettings = { openAppSettings() }
                    )
                }
            }
        }
    }

    private fun initializeTTS() {
        tts?.shutdown()
        Log.e("MainActivity", "Initializing TTS with Google Engine priority...")
        tts = TextToSpeech(this, this, TTSUtils.GOOGLE_TTS_PACKAGE)
    }

    override fun onInit(status: Int) {
        Log.e("MainActivity", "TTS onInit status=$status")
        if (status == TextToSpeech.SUCCESS) {
            runDiagnostics()
        } else {
            Log.e("MainActivity", "Google TTS failed, trying default...")
            tts = TextToSpeech(this) { 
                runDiagnostics()
            }
        }
    }

    private fun runDiagnostics() {
        voiceDiagnostic = VoiceDiagnostics(this).performFullCheck(tts)
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun refreshMissingPermissions() {
        val missing = mutableListOf<PermissionInfo>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(PermissionInfo(Manifest.permission.RECORD_AUDIO, R.string.permission_record_audio, R.string.permission_record_audio_desc))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(PermissionInfo(Manifest.permission.POST_NOTIFICATIONS, R.string.permission_post_notifications, R.string.permission_post_notifications_desc))
        }
        missingPermissions = missing
    }

    private fun refreshAllPermissionsStatus() {
        val list = mutableListOf<PermissionStatusInfo>()
        list.add(PermissionStatusInfo(getString(R.string.permission_record_audio), ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(PermissionStatusInfo(getString(R.string.permission_notifications), ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED))
        }
        allPermissionsStatus = list
    }

    private fun openAssistantSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, getString(R.string.could_not_open_settings), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun toggleHotwordService(enabled: Boolean) {
        if (enabled) {
            val isConnectionConfigured = settings.httpUrl.isNotBlank()
            if (!isConnectionConfigured) {
                Toast.makeText(this, getString(R.string.wakeword_requires_connection_error), Toast.LENGTH_LONG).show()
                return
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
                settings.hotwordEnabled = true
                HotwordService.start(this)
                Toast.makeText(this, getString(R.string.hotword_started), Toast.LENGTH_SHORT).show()
            } else if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                // Show rationale dialog before requesting permission
                showPermissionRationaleDialog()
            } else {
                // First-time request: launch directly
                pendingHotwordStart = true
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
        } else {
            settings.hotwordEnabled = false
            HotwordService.stop(this)
            Toast.makeText(this, getString(R.string.hotword_stopped), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Автозапуск прослушивания при открытии приложения (шлем, запуск по adb).
     */
    private fun maybeAutoStartListening() {
        val settings = SettingsRepository.getInstance(this)
        val cfg = com.openclaw.assistant.data.RelayConfig.load(this) ?: return
        if (!cfg.optBoolean("listen", false)) return
        if (!settings.isConfigured()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return
        if (HotwordService.isRunning) return
        settings.hotwordEnabled = true
        HotwordService.start(this)
        android.util.Log.d("MainActivity", "автозапуск прослушивания по relay.json")
    }

    private fun showPermissionRationaleDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.mic_permission_rationale_title))
            .setMessage(getString(R.string.mic_permission_rationale_message))
            .setPositiveButton(getString(R.string.grant_permission)) { _, _ ->
                pendingHotwordStart = true
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showPermissionDeniedDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.mic_permission_denied_title))
            .setMessage(getString(R.string.mic_permission_denied_message))
            .setPositiveButton(getString(R.string.open_settings)) { _, _ -> openAppSettings() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showPermissionSettingsDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.mic_permission_denied_title))
            .setMessage(getString(R.string.mic_permission_denied_permanently))
            .setPositiveButton(getString(R.string.open_settings)) { _, _ -> openAppSettings() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    fun isAssistantActive(): Boolean {
        return try {
            Settings.Secure.getString(contentResolver, "assistant")?.contains(packageName) == true
        } catch (e: Exception) { false }
    }

    override fun onResume() {
        super.onResume()
        refreshMissingPermissions()
        refreshAllPermissionsStatus()

        // Шлем: приложение открывают редко, а слушать надо постоянно. Если
        // внешний конфиг (files/relay.json) просит слушать — поднимаем сервис
        // отсюда, из видимой активности: Android 14 разрешает микрофон
        // foreground-сервису только при запуске «пока приложение на экране».
        maybeAutoStartListening()

        // Detect app language change made in SettingsActivity and recreate to apply it.
        // attachBaseContext() ensures the new instance always gets the correct locale,
        // so a single recreate() is always sufficient (no guard needed).
        // When savedTag is blank (System Default), compare against Locale.getDefault()
        // so that reverting from e.g. zh-CN back to system locale also triggers recreate.
        val savedTag = SettingsRepository.getInstance(this).appLanguage.trim()
        val displayedLanguage = resources.configuration.locales[0].language
        val expectedLanguage = if (savedTag.isNotBlank()) {
            java.util.Locale.forLanguageTag(savedTag).language
        } else {
            java.util.Locale.getDefault().language
        }
        if (displayedLanguage != expectedLanguage) {
            recreate()
            return
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settings: SettingsRepository,
    diagnostic: VoiceDiagnostic?,
    missingPermissions: List<PermissionInfo> = emptyList(),
    allPermissionsStatus: List<PermissionStatusInfo> = emptyList(),
    onOpenSettings: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
    onRefreshDiagnostics: () -> Unit,
    onRequestPermissions: (List<String>) -> Unit = {},
    onOpenAppSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    var isConfigured by remember { mutableStateOf(settings.isConfigured()) }
    var hotwordEnabled by remember { mutableStateOf(settings.hotwordEnabled) }
    val displayWakeWord = settings.getWakeWordDisplayName()
    var isAssistantSet by remember { mutableStateOf((context as? MainActivity)?.isAssistantActive() ?: false) }
    var showTroubleshooting by rememberSaveable { mutableStateOf(false) }
    var showHowToUse by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isConfigured = settings.isConfigured()
                hotwordEnabled = settings.hotwordEnabled
                isAssistantSet = (context as? MainActivity)?.isAssistantActive() ?: false
                onRefreshDiagnostics()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Check for updates on startup
    LaunchedEffect(Unit) {
        try {
            val versionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            val info = com.openclaw.assistant.utils.UpdateChecker.checkUpdate(versionName ?: "")
            if (info != null && info.hasUpdate) {
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.update_available, info.latestVersion),
                    actionLabel = context.getString(R.string.update_action),
                    duration = SnackbarDuration.Indefinite
                )
                if (result == SnackbarResult.ActionPerformed) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl))
                    context.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            // Ignore startup update check errors
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    val context = LocalContext.current
                    IconButton(onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/matthewnilsen"))
                            context.startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            // No browser available; ignore
                        }
                    }) {
                        Icon(Icons.Default.VolunteerActivism, contentDescription = stringResource(R.string.credits_support_kofi_button))
                    }
                    IconButton(onClick = { showHowToUse = true }) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = stringResource(R.string.how_to_use))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Webhook configuration status
            val webhookConfigured = settings.httpUrl.isNotBlank()
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (webhookConfigured) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                    contentColor = if (webhookConfigured) Color(0xFF1B5E20) else Color(0xFFB71C1C)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (webhookConfigured) Color(0xFF4CAF50) else Color(0xFFF44336))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (webhookConfigured) stringResource(R.string.webhook_configured) else stringResource(R.string.webhook_not_configured),
                        fontSize = 13.sp,
                        color = (if (webhookConfigured) Color(0xFF1B5E20) else Color(0xFFB71C1C)).copy(alpha = 0.8f),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onOpenSettings, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = (if (webhookConfigured) Color(0xFF1B5E20) else Color(0xFFB71C1C)).copy(alpha = 0.6f))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (missingPermissions.isNotEmpty()) {
                PermissionStatusCard(
                    missingPermissions = missingPermissions,
                    onRequestPermissions = onRequestPermissions,
                    onOpenAppSettings = onOpenAppSettings
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (diagnostic != null || allPermissionsStatus.isNotEmpty()) {
                CollapsibleSection(
                    title = stringResource(R.string.diagnostics_title),
                    initiallyExpanded = diagnostic?.let { it.sttStatus != DiagnosticStatus.READY || it.ttsStatus != DiagnosticStatus.READY } ?: false
                ) {
                    if (diagnostic != null) {
                        DiagnosticPanel(diagnostic, onRefreshDiagnostics)
                    }
                    if (diagnostic != null && allPermissionsStatus.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (allPermissionsStatus.isNotEmpty()) {
                        PermissionDiagnosticsPanel(allPermissionsStatus, onRefreshDiagnostics)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Quick Actions
            Text(text = stringResource(R.string.activation_methods), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(12.dp))

            val displayWakeWord = settings.getWakeWordDisplayName()

            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CompactActionCard(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    icon = Icons.Default.Home,
                    title = stringResource(R.string.home_button),
                    description = if (isAssistantSet) "ON" else "OFF",
                    isActive = isAssistantSet,
                    onClick = onOpenAssistantSettings,
                    showInfoIcon = true,
                    onInfoClick = { showTroubleshooting = true }
                )
                CompactActionCard(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    icon = if (hotwordEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                    title = stringResource(R.string.wake_word),
                    description = "$displayWakeWord (${if (hotwordEnabled) "ON" else "OFF"})",
                    showSwitch = true,
                    switchValue = hotwordEnabled,
                    onSwitchChange = { enabled ->
                        (context as? MainActivity)?.toggleHotwordService(enabled)
                        hotwordEnabled = enabled
                    },
                    isActive = hotwordEnabled,
                    showInfoIcon = false
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            val chatContext = LocalContext.current
            Button(
                onClick = { chatContext.startActivity(Intent(chatContext, SessionListActivity::class.java)) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.open_chat), fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }

            if (!isConfigured) {
                Spacer(modifier = Modifier.height(24.dp))
                WarningCard(message = stringResource(R.string.setup_required_hint), onClick = onOpenSettings)
            }
        }
    }

    if (showTroubleshooting) TroubleshootingDialog(onDismiss = { showTroubleshooting = false })
    if (showHowToUse) HowToUseDialog(displayWakeWord = displayWakeWord, onDismiss = { showHowToUse = false })
}

@Composable
fun DiagnosticPanel(diagnostic: VoiceDiagnostic, onRefresh: () -> Unit) {

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.diagnostic_engines), fontWeight = FontWeight.Medium, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onRefresh, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp)) }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DiagnosticItem(label = "In: ${diagnostic.sttEngine?.take(10) ?: "Def"}", status = diagnostic.sttStatus, modifier = Modifier.weight(1f))
                DiagnosticItem(label = "Out: ${diagnostic.ttsEngine?.split('.')?.lastOrNull() ?: "null"}", status = diagnostic.ttsStatus, modifier = Modifier.weight(1f))
            }
            if (diagnostic.suggestions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                diagnostic.suggestions.forEach { SuggestionItem(it) }
            }
        }
    }
}

@Composable
fun PermissionDiagnosticsPanel(allPermissionsStatus: List<PermissionStatusInfo>, onRefresh: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.diagnostic_app_permissions), fontWeight = FontWeight.Medium, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onRefresh, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp)) }
            }
            Spacer(modifier = Modifier.height(8.dp))
            allPermissionsStatus.forEach { perm ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val color = if (perm.isGranted) Color(0xFF4CAF50) else Color(0xFFF44336)
                    val icon = if (perm.isGranted) Icons.Default.Check else Icons.Default.Close
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(perm.permissionName, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(
                        if (perm.isGranted) stringResource(R.string.permission_status_granted)
                        else stringResource(R.string.permission_status_denied),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
            }
        }
    }
}

@Composable
fun DiagnosticItem(label: String, status: DiagnosticStatus, modifier: Modifier = Modifier) {
    val color = when (status) { DiagnosticStatus.READY -> Color(0xFF4CAF50); DiagnosticStatus.WARNING -> Color(0xFFFFC107); DiagnosticStatus.ERROR -> Color(0xFFF44336) }
    val icon = when (status) { DiagnosticStatus.READY -> Icons.Default.Check; DiagnosticStatus.WARNING -> Icons.Default.Info; DiagnosticStatus.ERROR -> Icons.Default.Error }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SuggestionItem(suggestion: com.openclaw.assistant.speech.diagnostics.DiagnosticSuggestion) {
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = suggestion.message, modifier = Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp)
            if (suggestion.actionLabel != null && suggestion.intent != null) {
                TextButton(onClick = { try { context.startActivity(suggestion.intent) } catch (e: Exception) { Toast.makeText(context, context.getString(R.string.state_error), Toast.LENGTH_SHORT).show() } }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(suggestion.actionLabel, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun CompactActionCard(modifier: Modifier = Modifier, icon: ImageVector, title: String, description: String, isActive: Boolean = false, onClick: (() -> Unit)? = null, showSwitch: Boolean = false, switchValue: Boolean = false, onSwitchChange: ((Boolean) -> Unit)? = null, showInfoIcon: Boolean = false, onInfoClick: (() -> Unit)? = null) {
    Card(modifier = modifier, onClick = { onClick?.invoke() }, enabled = onClick != null && !showSwitch, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(modifier = Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    if (showInfoIcon) Icon(imageVector = Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp).clickable { onInfoClick?.invoke() })
                    if (showSwitch) Switch(checked = switchValue, onCheckedChange = onSwitchChange, modifier = Modifier.scale(0.8f).offset(y = (-8).dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Text(text = description, fontSize = 12.sp, color = if (isActive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
fun MissingScopeCard(error: String, onClick: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(), 
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), 
        onClick = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Error, 
                    contentDescription = null, 
                    tint = MaterialTheme.colorScheme.error, 
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.permission_error_title), 
                        fontWeight = FontWeight.Bold, 
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = stringResource(R.string.permission_error_desc), 
                        fontSize = 13.sp, 
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
                    )
                    if (!expanded) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.permission_error_action), 
                            fontSize = 12.sp, 
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, 
                    contentDescription = null, 
                    tint = MaterialTheme.colorScheme.error
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(16.dp))
                // Fix Request Section
                Text(
                    text = stringResource(R.string.fix_request_label),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                val fixMessage = stringResource(R.string.fix_request_message)
                Text(
                    text = fixMessage,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val intent = Intent(context, ChatActivity::class.java).apply {
                            putExtra("EXTRA_PREFILL_TEXT", fixMessage)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_ask_ai))
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.technical_details),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = error,
                    fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Error", error)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, R.string.error_copied, Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_copy_error))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onClick, // This now just toggles via the card click, wait... user might want to open settings. 
                        // The `onClick` passed to MissingScopeCard was originally to open settings?
                        // Let's check where it's called.
                        // In MainScreen: MissingScopeCard(error = it) { settingsIntent... }
                        // So onClick DOES open settings.
                        // My previous edit in step 284 changed the Card's onClick to expansion toggle.
                        // So I need to make sure the "Open Settings" button calls the `onClick` param.
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.action_open_settings))
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionStatusCard(
    missingPermissions: List<PermissionInfo>,
    onRequestPermissions: (List<String>) -> Unit,
    onOpenAppSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.permissions_missing_title),
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE65100)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            missingPermissions.forEach { perm ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = null,
                        tint = Color(0xFFF44336),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(perm.nameResId),
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            color = Color(0xFFE65100)
                        )
                        Text(
                            text = stringResource(perm.descResId),
                            fontSize = 12.sp,
                            color = Color(0xFF795548)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onOpenAppSettings) {
                    Text(stringResource(R.string.open_settings), color = Color(0xFFE65100))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onRequestPermissions(missingPermissions.map { it.permission }) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text(stringResource(R.string.permission_grant))
                }
            }
        }
    }
}

@Composable
fun WarningCard(message: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)), onClick = onClick) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF9800), modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = message, color = Color(0xFFE65100), modifier = Modifier.weight(1f))
            Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFFFF9800))
        }
    }
}



@Composable
fun UsageStep(number: String, text: String) {
    Row(modifier = Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) { Text(text = number, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, fontSize = 14.sp)
    }
}

@Composable
fun HowToUseDialog(displayWakeWord: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss, 
        title = { Text(stringResource(R.string.how_to_use)) }, 
        text = { 
            Column { 
                (1..4).forEach { i ->
                    val resId = context.resources.getIdentifier("step_$i", "string", context.packageName)
                    val text = if (i == 1) {
                        stringResource(resId, displayWakeWord)
                    } else {
                        stringResource(resId)
                    }
                    UsageStep(i.toString(), text) 
                } 
            } 
        }, 
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.got_it)) } }
    )
}

@Composable
fun TroubleshootingDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.assist_gesture_not_working)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.troubleshooting_intro), fontSize = 14.sp)
            listOf("circle_to_search", "gesture_navigation", "google_app_setting", "refresh_binding").forEach { key -> 
                val titleId = context.resources.getIdentifier("${key}_title", "string", context.packageName)
                val descId = context.resources.getIdentifier("${key}_desc", "string", context.packageName)
                BulletPoint(stringResource(titleId), stringResource(descId)) 
            }
            Spacer(modifier = Modifier.height(8.dp)); HorizontalDivider(); Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { context.startService(Intent(context, OpenClawAssistantService::class.java).apply { action = OpenClawAssistantService.ACTION_SHOW_ASSISTANT }) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)) { Text(stringResource(R.string.debug_force_start)) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.got_it)) } })
}

@Composable
fun BulletPoint(title: String, desc: String) {
    Column { Text("• $title", fontWeight = FontWeight.Bold, fontSize = 14.sp); Text(desc, fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(start = 12.dp)) }
}
