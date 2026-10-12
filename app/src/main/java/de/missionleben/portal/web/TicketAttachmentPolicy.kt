package de.missionleben.portal.web

import java.net.URI

internal object TicketAttachmentPolicy {
    fun acceptsJpeg(acceptTypes: Array<String>, mode: Int): Boolean {
        if (mode !in setOf(0, 1)) return false // OPEN / OPEN_MULTIPLE, never SAVE or folders.
        val types = acceptTypes.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return types.isEmpty() || types.any { it in setOf("*/*", "image/*", "image/jpeg", ".jpg", ".jpeg") }
    }

    fun samePage(requestUrl: String, currentUrl: String): Boolean =
        requestUrl.isNotBlank() && requestUrl.substringBefore('#') == currentUrl.substringBefore('#')

    fun isExternalSelectedFile(uri: URI, packageName: String): Boolean =
        uri.scheme == "content" && !uri.authority.isNullOrBlank() &&
            uri.authority !in setOf("$packageName.files", "$packageName.ticket-photos")
}
