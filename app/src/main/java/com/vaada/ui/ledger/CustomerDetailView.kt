package com.vaada.ui.ledger

import com.vaada.models.tr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaada.models.CustomerAccount
import com.vaada.models.LedgerTransaction
import com.vaada.models.TransactionType
import com.vaada.ui.theme.KiranaBaakiRed
import com.vaada.ui.theme.KiranaBaakiRedLight
import com.vaada.ui.theme.KiranaBorder
import com.vaada.ui.theme.KiranaCreamBg
import com.vaada.ui.theme.KiranaGreenDark
import com.vaada.ui.theme.KiranaGreenPrimary
import com.vaada.ui.theme.KiranaJamaGreen
import com.vaada.ui.theme.KiranaJamaGreenLight
import com.vaada.ui.theme.KiranaMintPill
import com.vaada.ui.theme.KiranaSurface
import com.vaada.ui.theme.KiranaTextMuted
import com.vaada.ui.theme.KiranaTextPrimary
import com.vaada.ui.theme.KiranaTextSecondary

@Composable
fun CustomerDetailView(
    customer: CustomerAccount,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Intercept physical / gesture Android back action
    BackHandler {
        onBack()
    }

    val hasOutstanding = customer.currentBalance > 0

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(KiranaCreamBg)
    ) {
        // ── Top Navigation Bar ───────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = KiranaSurface,
            tonalElevation = 2.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, KiranaBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = tr("back_cd"),
                        tint = KiranaGreenPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = customer.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = KiranaTextPrimary
                    )
                    Text(
                        text = customer.phone,
                        style = MaterialTheme.typography.labelSmall,
                        color = KiranaTextSecondary
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Customer Balance Hero Card ───────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (hasOutstanding) KiranaBaakiRedLight else KiranaJamaGreenLight
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (hasOutstanding) KiranaBaakiRed.copy(alpha = 0.25f) else KiranaJamaGreen.copy(alpha = 0.25f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (hasOutstanding) tr("you_receive") else tr("account_status"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (hasOutstanding) KiranaBaakiRed else KiranaJamaGreen
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (hasOutstanding) "₹${String.format("%.0f", customer.currentBalance)}" else "₹0",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (hasOutstanding) KiranaBaakiRed else KiranaJamaGreen
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (hasOutstanding) tr("credit_pending") else tr("all_settled"),
                            fontSize = 12.sp,
                            color = KiranaTextSecondary
                        )
                    }
                }
            }

            // ── Transaction History Header ───────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tr("txn_history"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = KiranaTextPrimary
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = KiranaMintPill
                    ) {
                        Text(
                            text = tr("n_entries", customer.transactions.size),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = KiranaGreenDark,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // ── Transaction Items List ───────────────────────────────────────
            items(customer.transactions, key = { it.id }) { tx ->
                TransactionRowCard(transaction = tx)
            }

            // ── Bottom Spacing ───────────────────────────────────────────────
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun TransactionRowCard(transaction: LedgerTransaction) {
    val isCredit = transaction.type == TransactionType.CREDIT

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = KiranaSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, KiranaBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transaction.note,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = KiranaTextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = transaction.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = KiranaTextMuted
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (isCredit) "+₹${String.format("%.0f", transaction.amount)}" else "-₹${String.format("%.0f", transaction.amount)}",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isCredit) KiranaBaakiRed else KiranaJamaGreen
                )
                Spacer(modifier = Modifier.height(2.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isCredit) KiranaBaakiRedLight else KiranaJamaGreenLight
                ) {
                    Text(
                        text = if (isCredit) tr("gave") else tr("got"),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isCredit) KiranaBaakiRed else KiranaJamaGreen,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
