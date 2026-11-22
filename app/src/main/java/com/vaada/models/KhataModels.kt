package com.vaada.models

/**
 * Transaction type in the Kirana ledger:
 * - CREDIT: Udhaar / सामान दिया (Merchant gave goods on credit; customer owes merchant)
 * - PAYMENT: Jama / भुगतान मिला (Merchant received money; clears balance)
 */
enum class TransactionType {
    CREDIT,
    PAYMENT
}

/**
 * Individual transaction entry within a customer's ledger.
 */
data class LedgerTransaction(
    val id: String,
    val date: String,
    val amount: Double,
    val type: TransactionType,
    val note: String
)

/**
 * Customer account in the Kirana Khata.
 */
data class CustomerAccount(
    val id: String,
    val name: String,
    val phone: String,
    val currentBalance: Double,
    val recentTransactionNote: String,
    val recentTransactionTime: String,
    val transactions: List<LedgerTransaction>
)

/**
 * UI state for the customer ledger screen.
 * No demo data — data must come from the real backend API.
 */
sealed interface KhataUiState {
    data object Idle : KhataUiState
    data object Loading : KhataUiState
    data class Success(
        val customers: List<CustomerAccount>,
        val totalOutstanding: Double,
        val outstandingCount: Int
    ) : KhataUiState
    data class Error(val message: String) : KhataUiState
    /** Shown when backend is not yet connected. */
    data object BackendUnavailable : KhataUiState
}

/**
 * UI state for the merchant authentication flow.
 */
sealed interface AuthState {
    data object Unauthenticated : AuthState
    data object Authenticated : AuthState
    /** Validating a stored session on app start. */
    data object Restoring : AuthState
    /** Language choice; [canGoBack] when reached from Settings. */
    data class LanguageSelect(val canGoBack: Boolean = false) : AuthState
    data class PhoneEntry(val phone: String = "", val error: String? = null, val loading: Boolean = false) : AuthState
    data class OtpEntry(val phone: String, val otp: String = "", val error: String? = null, val loading: Boolean = false) : AuthState
    data class SignupEntry(val phone: String, val merchantName: String = "", val shopName: String = "", val error: String? = null, val loading: Boolean = false) : AuthState
    /** Navigate to voice verification after the account phase completes. */
    data class VoiceVerify(val profile: MerchantProfile) : AuthState
}

/**
 * Voice authentication session state — kept in memory only, never persisted.
 * Per docs/voice_auth.md: tokens are random 256-bit values; only their SHA-256 is kept server-side.
 * Bound to [sessionId] and [accountId]. Re-authentication required after [expiresAtMs].
 */
sealed interface VoiceAuthSession {
    /** No auth session yet or logged out. */
    data object None : VoiceAuthSession

    /** Verification attempt in progress. */
    data object Verifying : VoiceAuthSession

    /** Backend returned inconclusive — ask user to try again. */
    data class Inconclusive(val reason: String) : VoiceAuthSession

    /**
     * Successfully verified — [token] is the opaque X-Voice-Auth-Token.
     * Keep in memory only. NEVER write to disk or log.
     */
    data class Verified(
        val accountId: String,
        val sessionId: String,
        /** Opaque token. In-memory only. */
        val token: String,
        val expiresAtMs: Long
    ) : VoiceAuthSession {
        fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAtMs
        fun isNearExpiry(marginMs: Long = 60_000L): Boolean =
            System.currentTimeMillis() >= expiresAtMs - marginMs
    }

    /** Verification rejected (no_match). */
    data class Rejected(val reason: String, val retryAfterMs: Long? = null) : VoiceAuthSession

    /**
     * Not enrolled. The admin must enroll this account using:
     *   POST /v1/admin/voiceprints/{account_id}/enroll   (requires X-Admin-Token)
     * The Android app cannot perform enrollment without the admin token.
     */
    data class NotEnrolled(val accountId: String) : VoiceAuthSession

    /**
     * Voice auth is unavailable on the gateway (mode=off, model not loaded, threshold not set).
     * Continue without voice auth — turns will proceed unauthenticated.
     */
    data class Unavailable(val reason: String) : VoiceAuthSession

    /** Locked out — too many failed attempts. */
    data class Locked(val retryAfterMs: Long) : VoiceAuthSession

    /** Network/server error during verification. */
    data class NetworkError(val message: String) : VoiceAuthSession
}

/**
 * Merchant profile — set after authentication.
 * Null until authenticated.
 */
data class MerchantProfile(
    val id: String,
    val merchantName: String,
    val shopName: String,
    val phone: String,
    val language: String = "hi-IN"
)
