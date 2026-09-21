package de.missionleben.portal.push

import java.net.URI

object NtfySubscriptionPolicy {
    fun validMessageId(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9_-]{8,64}"))

    fun shouldProcessMessage(messageId: String, lastMessageId: String): Boolean =
        validMessageId(messageId) && messageId != lastMessageId

    fun accepts(
        baseUrl: String,
        topic: String,
        token: String,
        expectedBaseUrl: String,
    ): Boolean {
        val normalized = baseUrl.trimEnd('/')
        val expected = expectedBaseUrl.trimEnd('/')
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return false
        return normalized == expected &&
            uri.scheme == "https" &&
            uri.userInfo == null &&
            uri.query == null &&
            uri.fragment == null &&
            topic.matches(Regex("[A-Za-z0-9_-]{16,80}")) &&
            token.matches(Regex("tk_[a-z0-9]{29}"))
    }
}
