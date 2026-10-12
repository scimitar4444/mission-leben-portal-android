package de.missionleben.portal.web

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class TicketAttachmentPolicyTest {
    @Test fun allowsCameraForUnrestrictedAndJpegCompatibleAttachmentFields() {
        listOf(emptyArray(), arrayOf(""), arrayOf("*/*"), arrayOf("image/*"), arrayOf("IMAGE/JPEG"),
            arrayOf(".jpg"), arrayOf(".jpeg"), arrayOf("application/pdf, image/jpeg")).forEach { types ->
            assertTrue(types.contentToString(), TicketAttachmentPolicy.acceptsJpeg(types, 0))
            assertTrue(types.contentToString(), TicketAttachmentPolicy.acceptsJpeg(types, 1))
        }
    }

    @Test fun doesNotOfferJpegForIncompatibleFilesOrSaveAndFolderRequests() {
        listOf(arrayOf("application/pdf"), arrayOf("image/png"), arrayOf("video/*"), arrayOf(".txt"))
            .forEach { assertFalse(TicketAttachmentPolicy.acceptsJpeg(it, 0)) }
        listOf(-1, 2, 3, 4).forEach { assertFalse(TicketAttachmentPolicy.acceptsJpeg(emptyArray(), it)) }
    }

    @Test fun bindsResultToTheRequestingPageButAllowsAnAnchorChange() {
        val page = "https://it-service.mission-leben.de:8443/otobo/customer.pl?Action=CustomerTicketMessage"
        assertTrue(TicketAttachmentPolicy.samePage(page, "$page#attachment"))
        assertFalse(TicketAttachmentPolicy.samePage(page, page.replace("Message", "Overview")))
        assertFalse(TicketAttachmentPolicy.samePage(page, "https://evil.test/otobo/customer.pl"))
        assertFalse(TicketAttachmentPolicy.samePage("", ""))
    }

    @Test fun rejectsOwnProvidersAndNonContentUrisFromAnUntrustedPicker() {
        val packageName = "de.missionleben.portal"
        listOf("file:///data/private", "https://evil.test/photo.jpg", "content:/missing/42",
            "content://$packageName.files/updates/app.apk", "content://$packageName.ticket-photos/ticket_photos/photo.jpg")
            .forEach { assertFalse(it, TicketAttachmentPolicy.isExternalSelectedFile(URI(it), packageName)) }
        assertTrue(TicketAttachmentPolicy.isExternalSelectedFile(URI("content://com.android.providers.media.documents/document/image%3A42"), packageName))
    }
}
