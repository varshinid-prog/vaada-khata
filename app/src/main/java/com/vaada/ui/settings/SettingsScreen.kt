package com.vaada.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaada.models.AssistantState
import com.vaada.models.AudioCaptureMetadata
import com.vaada.ui.theme.*

@Composable
fun SettingsScreen(
    // Service controls
    state: AssistantState,
    hasPermissions: Boolean,
    detectionCount: Int,
    lastTriggerScore: Float,
    threshold: Float,
    lastEvaluatedScore: Float,
    metadata: AudioCaptureMetadata?,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onStopRecording: () -> Unit,
    // Auth / profile
    merchantName: String,
    shopName: String,
    phone: String,
    onLogout: () -> Unit,
    language: com.vaada.models.AppLanguage,
    onChangeLanguage: () -> Unit,
    gatewayUrl: String,
    ledgerUrl: String,
    currentHost: String,
    onSaveHost: (String) -> Boolean,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(KiranaCreamBg)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = KiranaTextPrimary
        )
        Text(
            text = "Profile, diagnostics & app preferences",
            style = MaterialTheme.typography.bodyMedium,
            color = KiranaTextSecondary
        )

        // ── Merchant Profile Card ──────────────────────────────────────────────
        SectionCard(title = "Merchant Profile") {
            ProfileRow(label = "Name", value = merchantName.ifBlank { "—" })
            ProfileRow(label = "Shop", value = shopName.ifBlank { "—" })
            ProfileRow(label = "Phone", value = phone.ifBlank { "—" })

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, KiranaBaakiRed),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = KiranaBaakiRed)
            ) {
                Text("Logout", fontWeight = FontWeight.SemiBold)
            }
        }

        // ── Voice Engine Controls ──────────────────────────────────────────────
        SectionCard(title = "Voice Engine") {
            val isActive = state !is AssistantState.Stopped
            StatusRow(label = "Service", value = if (isActive) "Running" else "Stopped", active = isActive)
            StatusRow(label = "Wake Phrase", value = "\"Hey Jarvis\"")
            StatusRow(label = "Wake Detections", value = "$detectionCount")
            StatusRow(label = "Threshold", value = String.format("%.2f", threshold))
            StatusRow(label = "Last Score", value = if (lastEvaluatedScore > 0f) String.format("%.4f", lastEvaluatedScore) else "—")
            StatusRow(label = "Last Trigger", value = if (lastTriggerScore > 0f) String.format("%.4f", lastTriggerScore) else "—")

            Spacer(modifier = Modifier.height(12.dp))

            if (state is AssistantState.Stopped) {
                Button(
                    onClick = onStartService,
                    enabled = hasPermissions,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KiranaGreenPrimary)
                ) {
                    Text("Start Voice Service", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            } else {
                OutlinedButton(
                    onClick = onStopService,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, KiranaBaakiRed),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = KiranaBaakiRed)
                ) {
                    Text("Stop Voice Service", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // ── Last Capture Metadata ─────────────────────────────────────────────
        if (metadata != null) {
            SectionCard(title = "Last Recording") {
                StatusRow(label = "Duration", value = "${metadata.durationMs} ms")
                StatusRow(label = "Size", value = "${metadata.totalBytes} bytes")
                StatusRow(label = "Sample Rate", value = "${metadata.sampleRate} Hz")
                StatusRow(label = "Avg RMS", value = String.format("%.4f", metadata.averageRms))
                StatusRow(label = "Max Amplitude", value = "${metadata.maxAmplitude}")
            }
        }

        // ── Gateway Config ────────────────────────────────────────────────────
        val ui = com.vaada.models.LocalUiStrings.current
        SectionCard(title = ui.language) {
            StatusRow(label = ui.language, value = "${language.nativeName} (${language.locale})")
            androidx.compose.material3.OutlinedButton(onClick = onChangeLanguage) { Text(ui.changeLanguage) }
        }

        SectionCard(title = "Server") {
            StatusRow(label = "Gateway", value = gatewayUrl)
            StatusRow(label = "Ledger", value = ledgerUrl)
            var hostText by androidx.compose.runtime.remember(currentHost) { androidx.compose.runtime.mutableStateOf(currentHost) }
            var hostError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.material3.OutlinedTextField(
                value = hostText, onValueChange = { hostText = it; hostError = false },
                label = { Text("Mac LAN IP / host") }, singleLine = true, isError = hostError,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            androidx.compose.material3.OutlinedButton(onClick = { hostError = !onSaveHost(hostText) }) { Text("Save host") }
            Text(
                text = if (hostError) "Enter the Mac's LAN IP (not localhost)." else "Phone and Mac must be on the same Wi-Fi.",
                fontSize = 11.sp, color = if (hostError) KiranaBaakiRed else KiranaTextMuted,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        // ── App Info ──────────────────────────────────────────────────────────
        SectionCard(title = "App Info") {
            StatusRow(label = "Version", value = "1.0.0 (dev)")
            StatusRow(label = "Build", value = "Debug")
            StatusRow(label = "Hackathon", value = "Gnani Voice Hackathon – PS2")
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = KiranaSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, KiranaBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = KiranaTextMuted,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            content()
        }
    }
}

@Composable
private fun ProfileRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = KiranaTextSecondary)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = KiranaTextPrimary)
    }
}

@Composable
private fun StatusRow(label: String, value: String, active: Boolean? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = KiranaTextSecondary)
        if (active != null) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (active) KiranaJamaGreenLight else KiranaBaakiRedLight
            ) {
                Text(
                    text = value,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (active) KiranaJamaGreen else KiranaBaakiRed,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        } else {
            Text(text = value, style = MaterialTheme.typography.labelMedium, color = KiranaTextPrimary)
        }
    }
}
