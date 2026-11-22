
package com.vaada.network

import com.vaada.models.CustomerAccount
import com.vaada.models.LedgerTransaction
import com.vaada.models.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class KhataApiException(
    val statusCode: Int,
    message: String
) : Exception(message)

class KhataApiClient(
    private val sessionToken: String,
    private val baseUrl: String = com.vaada.config.ServerConfig.ledgerUrl
) {
    private fun get(path: String): String {
        val connection = URL(baseUrl.trimEnd('/') + path)
            .openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("Authorization", "Bearer $sessionToken")
            connection.setRequestProperty("Accept", "application/json")

            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream.bufferedReader().use { it.readText() }

            if (code !in 200..299) {
                val message = try {
                    JSONObject(body).optString("detail").takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                } ?: "Khata request failed (HTTP $code)"
                throw KhataApiException(code, message)
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    suspend fun loadCustomers(): List<CustomerAccount> =
        withContext(Dispatchers.IO) {
            val customersJson = JSONArray(get("/customers"))
            val result = mutableListOf<CustomerAccount>()

            for (i in 0 until customersJson.length()) {
                val customer = customersJson.getJSONObject(i)
                val customerId = customer.getInt("customer_id")
                val encodedId = customerId.toString()

                val balance = JSONObject(
                    get("/customers/$customerId/balance")
                )

                val transactionsJson = JSONArray(
                    get("/customers/$customerId/transactions")
                )

                val transactions = mutableListOf<LedgerTransaction>()

                for (j in 0 until transactionsJson.length()) {
                    val tx = transactionsJson.getJSONObject(j)
                    if (tx.optString("status") != "COMMITTED") continue

                    transactions.add(
                        LedgerTransaction(
                            id = tx.optInt("transaction_id").toString(),
                            date = tx.optString(
                                "transaction_date",
                                tx.optString("created_at", "")
                            ),
                            amount = tx.optDouble("amount_rupees", 0.0),
                            type = if (
                                tx.optString("type").equals("PAYMENT", true)
                            ) TransactionType.PAYMENT
                            else TransactionType.CREDIT,
                            note = tx.optString("item", "")
                        )
                    )
                }

                val latest = transactions.lastOrNull()

                result.add(
                    CustomerAccount(
                        id = encodedId,
                        name = customer.optString("name", ""),
                        phone = customer.optString("phone", ""),
                        currentBalance = balance.optDouble("balance_rupees", 0.0),
                        recentTransactionNote = latest?.note ?: "",
                        recentTransactionTime = latest?.date ?: "",
                        transactions = transactions
                    )
                )
            }

            result
        }
}
