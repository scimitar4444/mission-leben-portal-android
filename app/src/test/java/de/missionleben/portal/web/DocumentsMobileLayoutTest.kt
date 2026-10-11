package de.missionleben.portal.web

import org.junit.Assert.*
import org.junit.Test

class DocumentsMobileLayoutTest {
    @Test fun matchesOnlyTheExactHttpsDocumentOrigin() {
        assertTrue(DocumentsMobileLayout.isDocumentOrigin("https://dokumente.mission-leben.de/dashboard"))
        assertTrue(DocumentsMobileLayout.isDocumentOrigin("https://dokumente.mission-leben.de:443/my-files"))
        listOf("http://dokumente.mission-leben.de", "https://dokumente.mission-leben.de:8443",
            "https://user@dokumente.mission-leben.de", "https://dokumente.mission-leben.de.evil.invalid",
            "https://id.mission-leben.de").forEach { assertFalse(DocumentsMobileLayout.isDocumentOrigin(it)) }
    }

    @Test fun stylingIsNarrowScreenOnlyAndNeverReadsOrDownloadsDocuments() {
        val script = DocumentsMobileLayout.SCRIPT
        assertTrue(script.contains("@media (max-width: 480px)"))
        assertTrue(script.contains("location.origin !== 'https://dokumente.mission-leben.de'"))
        assertTrue(script.contains("window.top !== window"))
        assertTrue(script.contains("main .grid.grid-cols-2:has(> a[href=\"/my-files\"])"))
        assertFalse(script.contains("fetch("))
        assertFalse(script.contains("XMLHttpRequest"))
        assertFalse(script.contains("localStorage"))
        assertFalse(script.contains(".click("))
    }

    @Test fun myFilesShrinksTheContainerAndWrapsControlsInsteadOfHidingOverflow() {
        val script = DocumentsMobileLayout.SCRIPT
        assertTrue(script.contains("main:has(#my-files-search)"))
        assertTrue(script.contains("min-width: 0; width: 100%; max-width: 100%;"))
        assertTrue(script.contains("grid-template-columns: minmax(0, 1fr) minmax(0, 1fr)"))
        assertTrue(script.contains("#my-files-category, #my-files-owner, #my-files-sort"))
        assertTrue(script.contains("flex-direction: column; align-items: stretch;"))
        assertTrue(script.contains("width: 100%; flex-wrap: wrap; justify-content: flex-end"))
        assertTrue(script.contains("white-space: normal; overflow-wrap: anywhere; line-height: 1.4"))
        assertFalse(script.contains("overflow-x: hidden"))
        assertTrue(script.contains("main button[data-ml-pdf-preview] { display: none !important; }"))
    }

    @Test fun myFilesGridKeepsTwoColumnsWithSmallPreviewsAndAccessibleActions() {
        val script = DocumentsMobileLayout.SCRIPT
        assertTrue(script.contains("grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 8px"))
        assertTrue(script.contains("height: 88px !important; aspect-ratio: auto"))
        assertTrue(script.contains("width: 100%; flex-wrap: wrap; gap: 0; justify-content: flex-end"))
        assertTrue(script.contains(".grid.grid-cols-2 > .group .sr-only"))
    }

    @Test fun hidesOnlyConfirmedPdfPreviewControlsAndLeavesDownloadAndImagesUntouched() {
        val script = DocumentsMobileLayout.SCRIPT
        assertTrue(script.contains("name.toLowerCase().endsWith('.pdf')"))
        assertTrue(script.contains("button.querySelector('svg.lucide-eye')"))
        assertTrue(script.contains("attributeFilter: ['aria-label']"))
        assertTrue(script.contains("restoreThumbnail(button)"))
        assertTrue(script.contains("window.__mlDocumentsPdfPreviewPolicy"))
        assertFalse(script.contains("svg.lucide-download"))
        assertFalse(script.contains("removeChild("))
    }

    @Test fun fileTypesUseOnlyCurrentAuthorizedPageMetadataAndSameOriginFileLinks() {
        val script = DocumentsMobileLayout.SCRIPT
        assertTrue(script.contains("data-ml-file-type"))
        assertTrue(script.contains("file.mime === 'application/pdf'"))
        assertTrue(script.contains("file.mime === 'text/plain'"))
        assertTrue(script.contains("file.mime.startsWith('image/')"))
        assertTrue(script.contains("url.origin === location.origin"))
        assertTrue(script.contains("document.addEventListener('inertia:navigate'"))
        assertTrue(script.contains("files = new Map()"))
        assertFalse(script.contains("innerHTML"))
        assertFalse(script.contains("sessionStorage"))
    }

    @Test fun bothOverviewAndMyFilesUseCompactAccessibleIconsInsteadOfTextBadges() {
        val script = DocumentsMobileLayout.SCRIPT
        assertTrue(script.contains("props.latest_files"))
        assertTrue(script.contains("document.createElementNS(ns, 'svg')"))
        assertTrue(script.contains("badge.setAttribute('role', 'img')"))
        assertTrue(script.contains("badge.setAttribute('aria-label', label)"))
        assertTrue(script.contains("title.insertBefore(badge, title.firstChild)"))
        assertTrue(script.contains("badge.replaceChildren(fileIcon(kind))"))
        assertFalse(script.contains("badge.textContent = label"))
        assertTrue(script.contains("width: 24px; height: 24px"))
        assertTrue(script.contains("main .bg-card.flex:has([data-ml-file-type]) button[data-ml-pdf-thumbnail]"))
        assertTrue(script.contains("main .grid > .group:has([data-ml-file-type]) > button[data-ml-pdf-thumbnail]"))
        assertFalse(script.contains("svg.lucide-image) { display: none"))
    }

    @Test fun onlyClientOverviewGreetingBecomesCompactDocumentsTitleAndNavigationRestoresOtherHeadings() {
        val script = DocumentsMobileLayout.SCRIPT
        assertTrue(script.contains("page.component === 'portal/dashboard'"))
        assertTrue(script.contains("setAttribute('data-ml-client-documents-dashboard', '')"))
        assertTrue(script.contains("removeAttribute('data-ml-client-documents-dashboard')"))
        assertTrue(script.contains("[data-ml-client-documents-dashboard] main .mb-8[class~=\"space-y-0.5\"]:has(> h1)"))
        assertFalse(script.contains("main h1 { display: none"))
        assertTrue(script.contains("de: 'Meine Dokumente'"))
        assertTrue(script.contains("heading.textContent !== label"))
        assertTrue(script.contains("space-y-0.5\"] > p { display: none"))
    }

    @Test fun accountFooterIsHiddenAndHamburgerPreservesTheSidebarButton() {
        val script = DocumentsMobileLayout.SCRIPT
        assertTrue(script.contains("[data-sidebar=\"footer\"] > [data-sidebar=\"menu\"] { display: none"))
        assertTrue(script.contains("trigger.querySelector('svg.lucide-panel-left')"))
        assertTrue(script.contains("['M4 6h16', 'M4 12h16', 'M4 18h16']"))
        assertTrue(script.contains("icon.replaceChildren(...paths)"))
        assertTrue(script.contains("icon.setAttribute('data-ml-hamburger', '')"))
        assertTrue(script.contains("de: 'Menü öffnen'"))
        assertFalse(script.contains("trigger.replaceWith("))
        assertFalse(script.contains("addEventListener('click'"))
        assertFalse(script.contains("[data-sidebar=\"footer\"] { display: none"))
    }
}
