package com.vaada.config

import android.content.Context
import com.vaada.BuildConfig

/**
 * Single source of truth for where the ledger backend and the AI voice gateway live.
 *
 * The default host is injected at build time (`-PvaadaHost=<mac LAN ip>`, see app/build.gradle.kts) and can be
 * overridden at runtime from Settings, so a changed Mac IP never needs a rebuild. Never use 127.0.0.1 here: on the
 * phone that is the phone itself.
 */
object ServerConfig {
    private const val PREFS = "vaada_server"
    private const val KEY_HOST = "host"

    @Volatile private var host: String = BuildConfig.DEFAULT_HOST

    val currentHost: String get() = host
    val ledgerUrl: String get() = "http://$host:${BuildConfig.LEDGER_PORT}"
    val gatewayUrl: String get() = "http://$host:${BuildConfig.GATEWAY_PORT}"

    fun init(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOST, null)
            ?.takeIf { isValidHost(it) }?.let { host = it }
    }

    fun isValidHost(value: String): Boolean =
        value.isNotBlank() && value.matches(Regex("[A-Za-z0-9.\\-]{1,253}")) &&
            value != "localhost" && !value.startsWith("127.")

    /** Returns false (and changes nothing) for an unusable host. */
    fun setHost(context: Context, value: String): Boolean {
        val v = value.trim()
        if (!isValidHost(v)) return false
        host = v
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_HOST, v).apply()
        return true
    }
}
