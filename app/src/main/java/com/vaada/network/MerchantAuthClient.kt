
package com.vaada.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class MerchantAuthProfile(
    val userId: Int,
    val phone: String,
    val name: String,
    val shopName: String,
    val status: String,
    val voiceEnrolled: Boolean,
    val createdAt: String?
)

data class MerchantAuthSession(
    val token: String,
    val tokenType: String,
    val expiresAt: String?,
    val profile: MerchantAuthProfile
)

class MerchantAuthException(
    val httpCode: Int,
    message: String
) : IOException(message)

class MerchantAuthClient(
    private val baseUrl: String = com.vaada.config.ServerConfig.ledgerUrl
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun register(
        phone: String,
        name: String,
        shopName: String,
        pin: String
    ): MerchantAuthSession = withContext(Dispatchers.IO) {
        require(phone.matches(Regex("\\d{10}"))) { "Enter a valid 10-digit phone number." }
        require(pin.matches(Regex("\\d{6}"))) { "PIN must contain exactly 6 digits." }

        val body = JSONObject().apply {
            put("phone", phone)
            put("name", name)
            put("shop_name", shopName)
            put("pin", pin)
        }

        val json = execute("/auth/register", body)
        parseSession(json, registration = true)
    }

    suspend fun login(
        phone: String,
        pin: String
    ): MerchantAuthSession = withContext(Dispatchers.IO) {
        require(phone.matches(Regex("\\d{10}"))) { "Enter a valid 10-digit phone number." }
        require(pin.matches(Regex("\\d{6}"))) { "PIN must contain exactly 6 digits." }

        val body = JSONObject().apply {
            put("phone", phone)
            put("pin", pin)
        }

        val json = execute("/auth/login", body)
        parseSession(json, registration = false)
    }

    suspend fun getMe(token: String): MerchantAuthProfile =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/auth/me")
                .get()
                .header("Authorization", "Bearer $token")
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = parseJson(text)

                if (!response.isSuccessful) {
                    throw MerchantAuthException(
                        response.code,
                        json.optString("detail", "Unable to validate merchant session.")
                    )
                }

                parseProfile(json)
            }
        }

    suspend fun logout(token: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/auth/logout")
            .post(ByteArray(0).toRequestBody(null))
            .header("Authorization", "Bearer $token")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val json = parseJson(response.body?.string().orEmpty())
                throw MerchantAuthException(
                    response.code,
                    json.optString("detail", "Logout failed.")
                )
            }
        }
    }

    private fun execute(path: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}$path")
            .post(body.toString().toRequestBody(jsonType))
            .build()

        client.newCall(request).execute().use { response ->
            val json = parseJson(response.body?.string().orEmpty())

            if (!response.isSuccessful) {
                throw MerchantAuthException(
                    response.code,
                    json.optString("detail", "Authentication request failed.")
                )
            }

            return json
        }
    }

    private fun parseSession(
        json: JSONObject,
        registration: Boolean
    ): MerchantAuthSession {
        val user = if (registration) json else json.optJSONObject("user")
            ?: throw IOException("Login response is missing the user profile.")

        val token = json.optString("token")
        if (token.isBlank() || token == "null") {
            throw IOException("Authentication response did not contain a session token.")
        }

        return MerchantAuthSession(
            token = token,
            tokenType = json.optString("token_type", "Bearer"),
            expiresAt = json.optString("expires_at").takeIf { it.isNotBlank() && it != "null" },
            profile = parseProfile(user)
        )
    }

    private fun parseProfile(json: JSONObject) = MerchantAuthProfile(
        userId = json.optInt("user_id"),
        phone = json.optString("phone"),
        name = json.optString("name"),
        shopName = json.optString("shop_name"),
        status = json.optString("status"),
        voiceEnrolled = json.optBoolean("voice_enrolled", false),
        createdAt = json.optString("created_at").takeIf {
            it.isNotBlank() && it != "null"
        }
    )

    private fun parseJson(text: String): JSONObject {
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject()
        }
    }
}
