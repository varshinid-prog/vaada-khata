package com.vaada.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaada.ui.navigation.VaadaDestination
import com.vaada.ui.theme.KiranaBorder
import com.vaada.ui.theme.KiranaCreamBg
import com.vaada.ui.theme.KiranaGreenDark
import com.vaada.ui.theme.KiranaGreenPrimary
import com.vaada.ui.theme.KiranaMint
import com.vaada.ui.theme.KiranaSurface
import com.vaada.ui.theme.KiranaTextMuted

@Composable
fun VaadaBottomBar(
    currentDestination: VaadaDestination,
    onNavigate: (VaadaDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier.height(72.dp),
        containerColor = KiranaSurface,
        tonalElevation = 6.dp
    ) {
        VaadaDestination.entries.forEach { destination ->
            val isSelected = currentDestination == destination
            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigate(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = destination.titleEn,
                        modifier = Modifier.size(24.dp)
                    )
                },
                label = {
                    Text(
                        text = when (destination) {
                            com.vaada.ui.navigation.VaadaDestination.Assistant -> com.vaada.models.LocalUiStrings.current.navAssistant
                            com.vaada.ui.navigation.VaadaDestination.Ledger -> com.vaada.models.LocalUiStrings.current.navKhata
                            com.vaada.ui.navigation.VaadaDestination.Settings -> com.vaada.models.LocalUiStrings.current.navSettings
                        },
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = KiranaGreenPrimary,
                    selectedTextColor = KiranaGreenPrimary,
                    indicatorColor = KiranaMint,
                    unselectedIconColor = KiranaTextMuted,
                    unselectedTextColor = KiranaTextMuted
                )
            )
        }
    }
}
