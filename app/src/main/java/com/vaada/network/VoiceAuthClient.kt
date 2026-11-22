package com.vaada.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for the voice authentication endpoints on the Vaada gateway.
 *
 * Documented in docs/voice_auth.md. Endpoints:
 *   POST /v1/auth/verify   — 1:1 speaker verification (returns auth_token when verified)
 *   GET  /v1/auth/status   — check if a token is still valid
 *   POST /v1/auth/logout   — invalidate a token server-side
 *
 * Enrollment (/v1/admin/voiceprints/{account_id}/enroll) requires VAADA_ADMIN_TOKEN and
 * is a controlled operator workflow. The Android app does NOT perform enrollment directly.
 *
 * Auth tokens are kept in memory only. Never stored on disk or logged.
 */

/** Result of POST /v1/auth/verify */
data class VoiceVerifyResult(
    /** "verified" | "rejected" | "unavailable" | "inconclusive" */
    val status: String,
    /** Stable machine word e.g. no_match, locked, too_short, insufficient_speech, not_enrolled, threshold_not_configured */
    val reason: String,
    /** Opaque token — present ONLY when status == "verified". Keep in memory only, never on disk. */
    val authToken: String?,
    /** Seconds until the token expires (null when not verified). */
    val expiresInS: Int?,
    /** Seconds to wait before retrying (429 locked). */
    val retryAfterS: Int?
)

/** Result of GET /v1/auth/status */
data class VoiceAuthStatusResult(
    val authenticated: Boolean,
    val expiresInS: Int?
)

class VoiceAuthClient(
    private val baseUrl: String = com.vaada.config.ServerConfig.gatewayUrl
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * POST /v1/auth/verify
     *
     * Sends a WAV file (PCM 16-bit, mono, 16 kHz) to verify the speaker against
     * the voiceprint stored server-side for [accountId].
     *
     * Audio requirements (from docs/voice_auth.md):
     *   - WAV PCM16 mono 16 kHz
     *   - 1.5–30 s total duration
     *   - ≥ ~1.2 s of active speech
     *
     * HTTP statuses:
     *   200 → verified or inconclusive
     *   401 → rejected (no_match)
     *   429 → locked (Retry-After header present)
     *   503 → unavailable (gateway not configured for auth)
     *
     * @param accountId The claimed account id (ledger customer id). A claim, not proof.
     * @param sessionId The same session_id that will be used for /v1/voice/turn.
     * @param wavFile   A valid WAV file — PCM16 mono 16 kHz. Must NOT be a renamed PCM file.
     */
    suspend fun verify(
        accountId: String,
        sessionId: String,
        wavFile: File
    ): VoiceVerifyResult = withContext(Dispatchers.IO) {
        require(wavFile.exists() && wavFile.isFile) { "WAV file does not exist: ${wavFile.path}" }
        require(wavFile.extension.equals("wav", ignoreCase = true)) { "verify() requires a WAV file" }
        require(wavFile.length() > 0) { "WAV file is empty" }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("account_id", accountId)
            .addFormDataPart("session_id", sessionId)
            .addFormDataPart("audio", wavFile.name, wavFile.asRequestBody("audio/wav".toMediaType()))
            .build()

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/auth/verify")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: throw IOException("Empty response from /v1/auth/verify")
            val json = try { JSONObject(text) } catch (e: Exception) {
                throw IOException("Invalid JSON from /v1/auth/verify: ${e.message}")
            }
            VoiceVerifyResult(
                status = json.optString("status", "unavailable"),
                reason = json.optString("reason", ""),
                authToken = json.optString("auth_token").takeIf { it.isNotEmpty() && it != "null" },
                expiresInS = if (json.has("expires_in_s") && !json.isNull("expires_in_s"))
                    json.optInt("expires_in_s") else null,
                retryAfterS = if (json.has("retry_after_s") && !json.isNull("retry_after_s"))
                    json.optInt("retry_after_s") else null
            )
        }
    }

    /**
     * GET /v1/auth/status
     *
     * Checks whether a previously-issued auth token is still valid for the given session.
     * Call this to detect near-expiry and prompt re-verification before the token lapses.
     */
    suspend fun status(
        authToken: String,
        sessionId: String
    ): VoiceAuthStatusResult = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/v1/auth/status?session_id=${sessionId}"
        val request = Request.Builder()
            .url(url)
            .get()
            .header("X-Voice-Auth-Token", authToken)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: throw IOException("Empty response from /v1/auth/status")
            val json = try { JSONObject(text) } catch (e: Exception) {
                throw IOException("Invalid JSON from /v1/auth/status: ${e.message}")
            }
            VoiceAuthStatusResult(
                authenticated = json.optBoolean("authenticated", false),
                expiresInS = if (json.has("expires_in_s") && !json.isNull("expires_in_s"))
                    json.optInt("expires_in_s") else null
            )
        }
    }

    /**
     * POST /v1/auth/logout
     *
     * Invalidates the auth token server-side. Must be called on:
     *   - App logout
     *   - Account switch (before switching)
     *   - App background/destroy when a session is active
     *
     * Failure is non-fatal (token expires on its own after VAADA_AUTH_SESSION_TTL_S).
     */
    suspend fun logout(authToken: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/auth/logout")
            .post(ByteArray(0).toRequestBody(null))
            .header("X-Voice-Auth-Token", authToken)
            .build()

        return@withContext try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: ""
                try {
                    JSONObject(text).optBoolean("logged_out", false)
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: IOException) {
            false // Non-fatal: token will expire server-side anyway
        }
    }
}
