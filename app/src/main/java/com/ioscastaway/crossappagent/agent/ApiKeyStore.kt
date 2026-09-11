package com.ioscastaway.crossappagent.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.ioscastaway.crossappagent.BuildConfig

/**
 * The Anthropic key comes from `local.properties` at build time and reaches the app through
 * [BuildConfig]. Nothing stores it on the device and nothing types it in.
 *
 * That is the right shape for a sideloaded experiment on a phone its owner controls. A shipping app
 * would not carry a key at all — the APK is readable, so the call would go through a backend that
 * holds the credential instead.
 */
object ApiKeyStore {

    val isConfigured: Boolean get() = BuildConfig.ANTHROPIC_API_KEY.isNotBlank()

    /** Null when the build had no key; every caller must handle that rather than fail late. */
    fun client(): AnthropicClient? =
        BuildConfig.ANTHROPIC_API_KEY
            .takeIf { it.isNotBlank() }
            ?.let { AnthropicOkHttpClient.builder().apiKey(it).build() }
}
