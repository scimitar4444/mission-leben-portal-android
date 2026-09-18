package de.missionleben.portal.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnrollmentQrParserTest {
    @Test
    fun acceptsMissionLebenEnrollmentLink() {
        val token = "abcdefghijklmnopqrstuvwxyz0123456789_-"
        assertEquals(
            token,
            EnrollmentQrParser.tokenFrom("de.missionleben.portal://enroll?token=$token"),
        )
    }

    @Test
    fun rejectsRawTokenAndForeignLink() {
        assertNull(EnrollmentQrParser.tokenFrom("abcdefghijklmnopqrstuvwxyz0123456789"))
        assertNull(
            EnrollmentQrParser.tokenFrom(
                "https://example.org/enroll?token=abcdefghijklmnopqrstuvwxyz0123456789",
            ),
        )
    }

    @Test
    fun rejectsDuplicateOrShortToken() {
        assertNull(EnrollmentQrParser.tokenFrom("de.missionleben.portal://enroll?token=short"))
        assertNull(
            EnrollmentQrParser.tokenFrom(
                "de.missionleben.portal://enroll?token=abcdefghijklmnopqrstuvwxyz&token=0123456789abcdefghij",
            ),
        )
    }
}
