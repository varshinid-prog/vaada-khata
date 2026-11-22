package com.vaada.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class VoiceTurnResult(
    val status: String,
    val sessionId: String,
    val transcript: String,
    val replyText: String,
    val replySpeak: List<String>,
    val replyKind: String,
    val dialogueState: String?,
    val awaiting: String?,
    val ledgerCommitted: Boolean,
    val ledgerTransactionId: Int?,
    val ledgerBalancePaise: Long?,
    val duplicate: Boolean,
    val audioStatus: String,
    val audioBase64: String?,
    val pendingCustomer: String? = null,
    val pendingAmountPaise: Long? = null,
    val pendingType: String? = null,
    val responseLanguage: String? = null
)

class VoiceGatewayException(
    val errorCode: String,
    message: String,
    val recoverable: Boolean,
    /** The gateway's own sentence for this failure, already in the merchant's language (reply.text of the error envelope). */
    val userMessage: String? = null,
    val httpStatus: Int = 0
) : IOException(message)

/** org.json turns JSON null into the STRING "null" with optString: this returns a real null instead. */
private fun JSONObject.nullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

class VoiceGatewayClient(
    private val baseUrl: String = com.vaada.config.ServerConfig.gatewayUrl
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** POST /v1/voice/replay: the base64 WAV of this session's last reply (or null). Touches no ledger and no recogniser. */
    suspend fun replay(sessionId: String, merchantToken: String): String? = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("session_id", sessionId).build()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/voice/replay")
            .header("Authorization", "Bearer ${merchantToken.trim()}")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val json = JSONObject(response.body?.string() ?: return@use null)
            json.optJSONObject("audio")?.optString("data")?.takeIf { it.isNotBlank() && it != "null" }
        }
    }

    suspend fun sendTurn(
        audioFile: File,
        sessionId: String,
        merchantToken: String,
        clientMsgId: String = UUID.randomUUID().toString(),
        language: String
    ): VoiceTurnResult = withContext(Dispatchers.IO) {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
            "Invalid session ID"
        }
        require(merchantToken.isNotBlank()) {
            "Merchant login token is missing. Please log in again."
        }
        require(clientMsgId.matches(Regex("[A-Za-z0-9_:.\\-]{1,100}"))) {
            "Invalid client message ID"
        }
        require(audioFile.exists() && audioFile.isFile) {
            "Audio file does not exist"
        }
        require(audioFile.extension.equals("wav", ignoreCase = true)) {
            "Gateway requires a WAV audio file"
        }
        require(audioFile.length() in 101..(5L * 1024 * 1024)) {
            "Audio file must be between 101 bytes and 5 MB"
        }

        val audioBody = audioFile.asRequestBody("audio/wav".toMediaType())
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio", audioFile.name, audioBody)
            .addFormDataPart("session_id", sessionId)
            .addFormDataPart("client_msg_id", clientMsgId)
            .addFormDataPart("language", language)
            .build()

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/voice/turn")
            .header("Authorization", "Bearer ${merchantToken.trim()}")
            .post(multipartBody)
            .build()

        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string()
                ?: throw IOException("Gateway returned an empty response")

            val json = try {
                JSONObject(responseText)
            } catch (e: Exception) {
                throw IOException("Invalid gateway response: ${e.message}")
            }

            if (!response.isSuccessful) {
                val error = json.optJSONObject("error")
                throw VoiceGatewayException(
                    errorCode = error?.optString("code", "HTTP_ERROR") ?: "HTTP_ERROR",
                    message = error?.optString("message", "Gateway request failed")
                        ?: "Gateway request failed",
                    recoverable = error?.optBoolean("recoverable", false) ?: false,
                    userMessage = json.optJSONObject("reply")?.nullableString("text"),
                    httpStatus = response.code
                )
            }

            val transcript = json.optJSONObject("transcript")
            val reply = json.optJSONObject("reply")
            val dialogue = json.optJSONObject("dialogue")
            val ledger = json.optJSONObject("ledger")
            val audio = json.optJSONObject("audio")

            val audioStatus = audio?.optString("status", "unavailable") ?: "unavailable"
            val audioBase64 = audio?.optString("data")
                ?.takeIf { it.isNotBlank() && it != "null" }

            val speakList = mutableListOf<String>()
            val speakArray = reply?.optJSONArray("speak")
            if (speakArray != null) {
                for (i in 0 until speakArray.length()) {
                    val item = speakArray.optString(i)
                    if (item.isNotBlank() && item != "null") speakList.add(item)
                }
            }

            VoiceTurnResult(
                status = json.optString("status", "unknown"),
                sessionId = json.optString("session_id", sessionId),
                transcript = transcript?.optString("text", "") ?: "",
                replyText = reply?.optString("text", "") ?: "",
                replySpeak = speakList,
                replyKind = reply?.optString("kind", "") ?: "",
                dialogueState = dialogue?.nullableString("state"),
                awaiting = dialogue?.nullableString("awaiting"),
                ledgerCommitted = ledger?.optBoolean("committed", false) ?: false,
                ledgerTransactionId = if (ledger != null && ledger.has("transaction_id")) {
                    ledger.optInt("transaction_id")
                } else null,
                ledgerBalancePaise = if (ledger != null && ledger.has("balance_paise")) {
                    ledger.optLong("balance_paise")
                } else null,
                duplicate = json.optBoolean("duplicate", false),
                audioStatus = audioStatus,
                audioBase64 = audioBase64,
                pendingCustomer = dialogue?.optJSONObject("pending")?.nullableString("customer_name"),
                pendingAmountPaise = dialogue?.optJSONObject("pending")?.let { if (it.isNull("amount_paise")) null else it.optLong("amount_paise") },
                pendingType = dialogue?.optJSONObject("pending")?.nullableString("transaction_type"),
                responseLanguage = json.nullableString("response_language")
            )
        }
    }
}
