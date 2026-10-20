package de.missionleben.portal.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordVisibilityPolicyTest {
    private fun script(base: String = "https://id.example.org") =
        PasswordVisibilityPolicy.script(base, "Passwort anzeigen", "Passwort verbergen")

    @Test fun onlyTrustedHttpsFlowMainFrameIsEnhanced() {
        val script = requireNotNull(script())
        assertTrue(script.contains("window.top !== window"))
        assertTrue(script.contains("location.origin !== \"https://id.example.org\""))
        assertTrue(script.contains("location.pathname.startsWith('/if/flow/')"))
    }

    @Test fun invalidOriginsAreRejected() {
        for (base in listOf("http://id.example.org", "javascript:alert(1)", "https://user@id.example.org",
            "https://id.example.org:65536", "https://", "not a URL")) {
            assertNull(base, script(base))
        }
    }

    @Test fun browserOriginNormalizationAndCustomPort() {
        assertTrue(requireNotNull(script("https://ID.example.org:443/"))
            .contains("location.origin !== \"https://id.example.org\""))
        assertTrue(requireNotNull(script("https://id.example.org:8443/"))
            .contains("location.origin !== \"https://id.example.org:8443\""))
        assertNotNull(script("https://[::1]:8443"))
    }

    @Test fun translatedLabelsCannotEscapeJavascriptStrings() {
        val script = requireNotNull(PasswordVisibilityPolicy.script(
            "https://id.example.org", "Anzeigen\";\\\n\u2028\u2029", "Verbergen\t\u0000",
        ))
        assertTrue(script.contains("const showLabel = \"Anzeigen\\\";\\\\\\u000a\\u2028\\u2029\";"))
        assertTrue(script.contains("const hideLabel = \"Verbergen\\u0009\\u0000\";"))
    }

    @Test fun replacementsAreNotRecursivelyExpanded() {
        val script = requireNotNull(PasswordVisibilityPolicy.script(
            "https://id.example.org", "__ML_HIDE_JSON__", "Hide",
        ))
        assertTrue(script.contains("const showLabel = \"__ML_HIDE_JSON__\";"))
    }

    @Test fun passwordHintIsPresentationOnlyAndEscapedLikeOtherLabels() {
        val script = requireNotNull(PasswordVisibilityPolicy.script(
            "https://id.example.org", "Show", "Hide", "__ML_SHOW_JSON__\"\n\u2028",
        ))
        assertTrue(script.contains("const passwordHint = \"__ML_SHOW_JSON__\\\"\\u000a\\u2028\";"))
        assertTrue(script.contains("hint.textContent = passwordHint"))
        assertTrue(script.contains("aria-describedby"))
        assertFalse(script.contains(".value"))
    }

    @Test fun helperDoesNotReadStoreOrSendPassword() {
        val script = requireNotNull(script())
        for (forbidden in listOf(".value", "localStorage", "sessionStorage", "fetch(", "postMessage",
            "XMLHttpRequest", "console.", "setInterval", "setTimeout")) {
            assertFalse(forbidden, script.contains(forbidden))
        }
        assertTrue(script.contains("button.type = 'button'"))
        assertTrue(script.contains("element.shadowRoot"))
        assertTrue(script.contains("document.hidden"))
        assertTrue(script.contains("window.addEventListener('pagehide', hideAll)"))
        assertTrue(script.contains("root.addEventListener('submit', hideAll, true)"))
    }
}
