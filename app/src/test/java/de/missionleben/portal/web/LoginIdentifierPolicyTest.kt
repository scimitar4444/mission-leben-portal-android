package de.missionleben.portal.web

import org.junit.Assert.*
import org.junit.Test

class LoginIdentifierPolicyTest {
    private fun script(base: String = "https://id.example.org", hint: String = "Hint") =
        LoginIdentifierPolicy.script(base, hint, "Invalid", "Use", "Denied", "Summary", "Technical")

    @Test fun rejectsUntrustedOriginDefinitions() {
        for (base in listOf("http://id.example.org", "javascript:alert(1)", "https://user@id.example.org",
            "https://id.example.org:65536", "not-a-url")) assertNull(script(base))
    }

    @Test fun canonicalOriginAndScopedMainFrameOnly() {
        val s = requireNotNull(script("https://ID.example.org:443/"))
        assertTrue(s.contains("location.origin === \"https://id.example.org\""))
        assertTrue(s.contains("window.top === window"))
        assertTrue(s.contains("mission-leben-android-(?:authentication|authorization)"))
        assertTrue(s.contains("AK-STAGE-IDENTIFICATION"))
        assertTrue(s.contains("getAttribute('name') === 'uidField'"))
    }

    @Test fun escapesLabelsWithoutRecursiveReplacement() {
        val s = requireNotNull(script(hint = "__ML_LOGIN_USE__\"\\\n\u2028"))
        assertTrue(s.contains("const hintText = \"__ML_LOGIN_USE__\\\"\\\\\\u000a\\u2028\";"))
    }

    @Test fun noNetworkPersistenceOrAutomaticSubmission() {
        val s = requireNotNull(script())
        for (bad in listOf("fetch(", "XMLHttpRequest", "localStorage", "sessionStorage", "console.",
            "postMessage", "requestSubmit(", ".submit(")) assertFalse(bad, s.contains(bad))
        assertTrue(s.contains("button.type = 'button'"))
        assertTrue(s.contains("technicalLabel"))
        assertTrue(s.contains("event.preventDefault(); event.stopImmediatePropagation()"))
    }
}
