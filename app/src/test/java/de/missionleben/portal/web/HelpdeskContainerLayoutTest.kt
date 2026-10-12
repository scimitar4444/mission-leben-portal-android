package de.missionleben.portal.web

import org.junit.Assert.*
import org.junit.Test

class HelpdeskContainerLayoutTest {
    @Test fun onlyTheExactCustomerPortalAndTheTwoApprovedPortsMatch() {
        assertTrue(HelpdeskContainerLayout.isHelpdeskPage("https://it-service.mission-leben.de:8443/otobo/customer.pl?Action=CustomerDashboard"))
        assertTrue(HelpdeskContainerLayout.isHelpdeskPage("https://it-service.mission-leben.de/otobo/customer.pl"))
        assertTrue(HelpdeskContainerLayout.isHelpdeskPage("https://it-service.mission-leben.de:443/otobo/customer.pl"))
        listOf("http://it-service.mission-leben.de/otobo/customer.pl",
            "https://it-service.mission-leben.de:8444/otobo/customer.pl",
            "https://it-service.mission-leben.de.evil.test/otobo/customer.pl",
            "https://someone@it-service.mission-leben.de/otobo/customer.pl",
            "https://it-service.mission-leben.de/otobo/index.pl",
            "https://id.mission-leben.de/if/flow/login/", "not a url").forEach {
            assertFalse(it, HelpdeskContainerLayout.isHelpdeskPage(it))
        }
    }

    @Test fun onlyVisibilityAndTheKnownDashboardLabelChangeNotThemeOrTicketData() {
        val script = HelpdeskContainerLayout.SCRIPT
        assertTrue(script.contains("window.top !== window"))
        assertTrue(script.contains("location.pathname !== '/otobo/customer.pl'"))
        assertTrue(script.contains("getElementById('ml-helpdesk-container-layout')"))
        assertTrue(script.contains(".oooTile_TicketList"))
        assertTrue(script.contains("Action=Logout"))
        assertTrue(script.contains("Action=CustomerPreferences"))
        assertTrue(script.contains(".oooTicketListItem"))
        assertTrue(script.contains("grid-template-rows: auto 24px"))
        assertTrue(script.contains("#oooHeader h1 { font-size: 20px"))
        assertTrue(script.contains(".oooTIDTitle { font-size: 16px"))
        assertTrue(script.contains("heading.textContent.trim() === 'IT-Service – Mission Leben'"))
        assertTrue(script.contains("heading.textContent = 'IT Hilfe'"))
        listOf("fetch(", "XMLHttpRequest", "localStorage", "innerHTML", "location.href =", "form.submit(",
            "background-color:", "color:", "input.value", "SessionID", "ChallengeToken").forEach {
            assertFalse(it, script.contains(it))
        }
    }

    @Test fun compactSubtitleNeverShrinksTheTicketSearchMagnifier() {
        val script = HelpdeskContainerLayout.SCRIPT
        assertTrue(script.contains("#oooHeader .oooWithSub h3:not(.ooog) { font-size: 13px"))
        assertFalse(script.contains("#oooHeader h3 {"))
        assertTrue(script.contains("#oooSearchBox h3.ooog { position: relative"))
        assertTrue(script.contains("font-size: 20px !important; line-height: 20px !important; padding: 0"))
        assertTrue(script.contains("top: 50% !important; left: 10px !important; transform: translateY(-50%)"))
        assertTrue(script.contains("#oooSearch { padding-left: 36px"))
    }

    @Test fun uploadLabelIsLocalizedWithoutChangingFileOrTicketData() {
        val script = HelpdeskContainerLayout.script("Datei hochladen / Foto machen")
        assertTrue(script.contains("const uploadLabel = \"Datei hochladen / Foto machen\";"))
        assertTrue(script.contains("querySelectorAll('.DnDUpload .ReadyForUpload')"))
        assertTrue(script.contains("button.setAttribute('aria-label', uploadLabel)"))
        assertTrue(script.contains("attributeFilter: ['class', 'style']"))
        assertTrue(script.contains("window.__mlHelpdeskContainerLayout.refresh()"))
        listOf("setInterval", ".click(", ".value =", "form.submit(", "innerHTML").forEach {
            assertFalse(it, script.contains(it))
        }
    }

    @Test fun labelEscapesJavascriptCharactersInOnePass() {
        val label = "\"\\\n\u0001\u2028\u2029__ML_UPLOAD_LABEL_JSON__"
        val script = HelpdeskContainerLayout.script(label)
        assertTrue(script.contains("const uploadLabel = \"\\\"\\\\\\u000a\\u0001\\u2028\\u2029__ML_UPLOAD_LABEL_JSON__\";"))
        assertFalse(script.contains("const uploadLabel = \"$label\";"))
    }

    @Test fun mobileEditorUsesTheSubjectWidthWithoutChangingSubjectOrEditorContents() {
        val script = HelpdeskContainerLayout.SCRIPT
        assertTrue(script.contains("@media only screen and (max-width: 767px)"))
        assertTrue(script.contains("#oooContent .RichTextHolder {"))
        assertTrue(script.contains("width: 91.2% !important; max-width: 100%"))
        assertTrue(script.contains("margin-left: auto !important; margin-right: auto !important"))
        assertTrue(script.contains(".ck-editor__editable { padding: 10px"))
        assertTrue(script.contains(".ck-button__icon { width: 18px !important; height: 18px"))
        assertTrue(script.contains("min-width: 44px !important; min-height: 44px"))
        assertTrue(script.contains(".ck-toolbar__items { min-width: 0 !important; gap: 2px"))
        assertTrue(script.contains(".ck-toolbar__separator { margin: 0"))
        assertFalse(script.contains("#Subject"))
        assertFalse(script.contains("contenteditable="))
        assertFalse(script.contains("#RichText"))
    }

    @Test fun attachmentAndSubmitSpacingCollapsesWithoutHidingFilesOrTheUploadOverlay() {
        val script = HelpdeskContainerLayout.SCRIPT
        assertTrue(script.contains("#oooContent .DnDUploadBox { display: block"))
        assertTrue(script.contains(".AttachmentListContainer { min-height: 0"))
        assertTrue(script.contains(".AttachmentListContainer[data-ml-upload-busy=\"true\"] { min-height: 48px"))
        assertTrue(script.contains("#oooContent #BottomActionRow { margin-top: 16px"))
        assertTrue(script.contains("getComputedStyle(busy).display !== 'none'"))
        assertFalse(script.contains(".AttachmentList { display: none"))
        assertFalse(script.contains(".Busy { display: none"))
        assertFalse(script.contains("submitRichText.click"))
    }
}
