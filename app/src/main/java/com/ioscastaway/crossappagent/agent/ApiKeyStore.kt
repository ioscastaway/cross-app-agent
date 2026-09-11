package com.ioscastaway.crossappagent.agent

import android.content.Context
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.ioscastaway.crossappagent.BuildConfig

enum class ApiKeySource { NONE, BUILD, IN_APP }

/**
 * Where the Anthropic key comes from. The debug console and the bubble service both need it, and
 * neither should care which of the two sources won.
 *
 * Storing a key in SharedPreferences is fine for a sideloaded experiment you control; a shipping app
 * would put it behind a backend instead of on the device at all.
 */
object ApiKeyStore {

    private const val PREFS = "agent"
    private const val KEY = "anthropic_api_key"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun saved(context: Context): String? =
        prefs(context).getString(KEY, null)?.trim()?.takeIf { it.isNotBlank() }

    fun source(context: Context): ApiKeySource = when {
        saved(context) != null -> ApiKeySource.IN_APP
        BuildConfig.ANTHROPIC_API_KEY.isNotBlank() -> ApiKeySource.BUILD
        else -> ApiKeySource.NONE
    }

    fun key(context: Context): String? =
        saved(context) ?: BuildConfig.ANTHROPIC_API_KEY.takeIf { it.isNotBlank() }

    fun save(context: Context, key: String) {
        prefs(context).edit().putString(KEY, key.trim()).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    /** Null when no key is configured. */
    fun client(context: Context): AnthropicClient? =
        key(context)?.let { AnthropicOkHttpClient.builder().apiKey(it).build() }
}
