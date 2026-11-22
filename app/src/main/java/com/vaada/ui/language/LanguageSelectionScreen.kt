package com.vaada.ui.language

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaada.models.AppLanguage
import com.vaada.models.UiStrings
import com.vaada.ui.theme.*

@Composable
fun LanguageSelectionScreen(
    initial: AppLanguage?,
    onConfirm: (AppLanguage) -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(initial) }
    val strings = UiStrings.of(selected ?: AppLanguage.ENGLISH)

    Column(
        modifier = modifier.fillMaxSize().background(KiranaCreamBg).padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("Choose your language", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = KiranaTextPrimary)
            Text(strings.chooseLanguage, fontSize = 18.sp, color = KiranaTextSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            AppLanguage.entries.forEach { lang ->
                val isSelected = lang == selected
                Card(
                    onClick = { selected = lang },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isSelected) KiranaMint else MaterialTheme.colorScheme.surface),
                    border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) KiranaGreenPrimary else KiranaTextMuted),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row {
                            Text(lang.nativeName, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = KiranaTextPrimary, modifier = Modifier.weight(1f))
                            if (isSelected) Text("✓", fontSize = 22.sp, color = KiranaGreenPrimary, fontWeight = FontWeight.Bold)
                        }
                        Text(lang.englishName, fontSize = 14.sp, color = KiranaTextSecondary)
                        Text(lang.greeting, fontSize = 15.sp, color = KiranaTextPrimary, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        Button(
            onClick = { selected?.let(onConfirm) },
            enabled = selected != null,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(strings.continueLabel, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        if (onBack != null) {
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}
