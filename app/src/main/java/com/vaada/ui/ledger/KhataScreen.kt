package com.vaada.ui.ledger

import com.vaada.models.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaada.models.CustomerAccount
import com.vaada.models.KhataUiState
import com.vaada.ui.theme.*

@Composable
fun KhataScreen(
    uiState: KhataUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCustomer by remember { mutableStateOf<CustomerAccount?>(null) }

    // Customer detail overlay
    if (selectedCustomer != null) {
        CustomerDetailView(
            customer = selectedCustomer!!,
            onBack = { selectedCustomer = null },
            modifier = modifier
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(KiranaCreamBg)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // ── Header ─────────────────────────────────────────────────────────────
        KhataHeader()
        Spacer(modifier = Modifier.height(16.dp))

        when (uiState) {
            is KhataUiState.Idle, is KhataUiState.Loading -> {
                // Search bar placeholder (non-functional while loading)
                SearchBarPlaceholder()
                Spacer(modifier = Modifier.height(24.dp))
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = KiranaGreenPrimary, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(tr("list_loading"), style = MaterialTheme.typography.bodyMedium, color = KiranaTextSecondary)
                    }
                }
            }

            is KhataUiState.BackendUnavailable -> {
                Spacer(modifier = Modifier.height(32.dp))
                BackendUnavailableState(onRetry = onRetry)
            }

            is KhataUiState.Error -> {
                Spacer(modifier = Modifier.height(32.dp))
                ErrorState(message = uiState.message, onRetry = onRetry)
            }

            is KhataUiState.Success -> {
                val customers = uiState.customers
                val filtered = remember(searchQuery, customers) {
                    if (searchQuery.isBlank()) customers
                    else {
                        val q = searchQuery.trim().lowercase()
                        customers.filter {
                            it.name.lowercase().contains(q) || it.phone.replace(" ", "").contains(q)
                        }
                    }
                }

                // Search bar
                CustomerSearchBar(query = searchQuery, onQueryChange = { searchQuery = it })
                Spacer(modifier = Modifier.height(16.dp))

                // Outstanding summary card
                if (customers.isNotEmpty()) {
                    OutstandingSummaryCard(
                        totalOutstanding = uiState.totalOutstanding,
                        owingCount = uiState.outstandingCount
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }

                // Customer list section header
                if (customers.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tr("customers_list"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = KiranaTextPrimary)
                        Text(tr("n_accounts", filtered.size), style = MaterialTheme.typography.labelSmall, color = KiranaTextMuted)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                when {
                    customers.isEmpty() -> EmptyCustomersState()
                    filtered.isEmpty()  -> EmptySearchState(searchQuery = searchQuery, onClearSearch = { searchQuery = "" })
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(filtered, key = { it.id }) { customer ->
                                CustomerCard(customer = customer, onClick = { selectedCustomer = customer })
                            }
                            item { Spacer(modifier = Modifier.height(20.dp)) }
                        }
                    }
                }
            }
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
private fun KhataHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(KiranaGreenPrimary))
                Spacer(modifier = Modifier.width(6.dp))
                Text("VAADA KHATA", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = KiranaGreenPrimary)
            }
            Text(tr("khata_title"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = KiranaTextPrimary)
            Text(tr("customer_accounts"), style = MaterialTheme.typography.bodyMedium, color = KiranaTextSecondary)
        }
    }
}

// ── Search bar ────────────────────────────────────────────────────────────────

@Composable
private fun CustomerSearchBar(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        placeholder = { Text(tr("search_ph"), fontSize = 14.sp, color = KiranaTextMuted) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = KiranaGreenPrimary, modifier = Modifier.size(20.dp)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = null, tint = KiranaTextMuted, modifier = Modifier.size(18.dp))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = KiranaSurface, unfocusedContainerColor = KiranaSurface,
            focusedBorderColor = KiranaGreenPrimary, unfocusedBorderColor = KiranaBorder,
            focusedTextColor = KiranaTextPrimary, unfocusedTextColor = KiranaTextPrimary
        )
    )
}

@Composable
private fun SearchBarPlaceholder() {
    OutlinedTextField(
        value = "",
        onValueChange = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        placeholder = { Text(tr("search_ph"), fontSize = 14.sp, color = KiranaTextMuted) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = KiranaTextMuted, modifier = Modifier.size(20.dp)) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            disabledContainerColor = KiranaSurface, disabledBorderColor = KiranaBorder,
            disabledTextColor = KiranaTextMuted
        )
    )
}

// ── Outstanding summary card ──────────────────────────────────────────────────

@Composable
private fun OutstandingSummaryCard(totalOutstanding: Double, owingCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = KiranaGreenPrimary),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(tr("total_outstanding"), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = KiranaMint)
                Surface(shape = RoundedCornerShape(8.dp), color = KiranaGreenLight) {
                    Text(tr("n_customers", owingCount), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("₹${String.format("%.0f", totalOutstanding)}", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(4.dp))
            Text(tr("total_credit_sub"), fontSize = 11.sp, color = KiranaMint.copy(alpha = 0.85f))
        }
    }
}

// ── Customer card ─────────────────────────────────────────────────────────────

@Composable
private fun CustomerCard(customer: CustomerAccount, onClick: () -> Unit) {
    val hasOutstanding = customer.currentBalance > 0
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = KiranaSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, KiranaBorder)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(if (hasOutstanding) KiranaMintPill else KiranaCreamBg),
                    contentAlignment = Alignment.Center
                ) {
                    Text(customer.name.firstOrNull()?.toString() ?: "?", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = KiranaGreenPrimary)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(customer.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = KiranaTextPrimary)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("${customer.recentTransactionNote} • ${customer.recentTransactionTime}", style = MaterialTheme.typography.labelSmall, color = KiranaTextSecondary, maxLines = 1)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (hasOutstanding) "₹${String.format("%.0f", customer.currentBalance)}" else "₹0",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = if (hasOutstanding) KiranaBaakiRed else KiranaJamaGreen
                )
                Spacer(modifier = Modifier.height(2.dp))
                Surface(shape = RoundedCornerShape(6.dp), color = if (hasOutstanding) KiranaBaakiRedLight else KiranaJamaGreenLight) {
                    Text(
                        text = if (hasOutstanding) tr("owes") else tr("settled"),
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = if (hasOutstanding) KiranaBaakiRed else KiranaJamaGreen,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

// ── Empty states ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyCustomersState() {
    Box(modifier = Modifier.fillMaxSize().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📒", fontSize = 48.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(tr("no_customers"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = KiranaTextPrimary, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(8.dp))
            Text(tr("empty_hint"), style = MaterialTheme.typography.bodyMedium, color = KiranaTextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
        }
    }
}

@Composable
private fun EmptySearchState(searchQuery: String, onClearSearch: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Search, contentDescription = null, tint = KiranaTextMuted, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(tr("no_match", searchQuery), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = KiranaTextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(tr("check_name"), style = MaterialTheme.typography.bodyMedium, color = KiranaTextSecondary)
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onClearSearch) {
                Text(tr("clear_search"), color = KiranaGreenPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun BackendUnavailableState(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔌", fontSize = 48.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(tr("backend_off"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = KiranaTextPrimary, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                tr("backend_body"),
                style = MaterialTheme.typography.bodyMedium,
                color = KiranaTextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KiranaGreenPrimary)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(tr("retry"), color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⚠️", fontSize = 48.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(tr("went_wrong"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = KiranaTextPrimary, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = KiranaBaakiRed, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onRetry, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = KiranaGreenPrimary)) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(tr("retry"), color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
