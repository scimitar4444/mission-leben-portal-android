package de.missionleben.portal.device

import de.missionleben.portal.model.DeviceMode
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
    fun acceptsPortalIssuedEnrollmentAndMode() {
        val token = "abcdefghijklmnopqrstuvwxyz0123456789_-"
        val tokenUuid = "123e4567-e89b-12d3-a456-426614174000"
        val payload = EnrollmentQrParser.parse(
            "de.missionleben.portal://enroll?token=$token&token_id=$tokenUuid&mode=shared",
        )
        assertEquals(token, payload?.token)
        assertEquals(tokenUuid, payload?.tokenUuid)
        assertEquals(DeviceMode.SHARED, payload?.mode)
    }

    @Test
    fun acceptsVerifiedHttpsInstallLinkWithSecretInFragment() {
        val token = "abcdefghijklmnopqrstuvwxyz0123456789_-"
        val tokenUuid = "123e4567-e89b-12d3-a456-426614174000"
        val payload = EnrollmentQrParser.parse(
            "https://geraete.mission-leben.de/install#token=$token&token_id=$tokenUuid&mode=personal",
        )
        assertEquals(token, payload?.token)
        assertEquals(tokenUuid, payload?.tokenUuid)
        assertEquals(DeviceMode.PERSONAL, payload?.mode)
    }

    @Test
    fun rejectsRawTokenAndForeignLink() {
        assertNull(EnrollmentQrParser.tokenFrom("abcdefghijklmnopqrstuvwxyz0123456789"))
        assertNull(
            EnrollmentQrParser.tokenFrom(
                "https://example.org/enroll?token=abcdefghijklmnopqrstuvwxyz0123456789",
            ),
        )
        assertNull(
            EnrollmentQrParser.tokenFrom(
                "https://geraete.mission-leben.de/install?token=abcdefghijklmnopqrstuvwxyz0123456789",
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
        assertNull(
            EnrollmentQrParser.parse(
                "de.missionleben.portal://enroll?token=abcdefghijklmnopqrstuvwxyz0123456789" +
                    "&token_id=not-a-uuid&mode=personal",
            ),
        )
        assertNull(
            EnrollmentQrParser.parse(
                "de.missionleben.portal://enroll?token=abcdefghijklmnopqrstuvwxyz0123456789&mode=personal",
            ),
        )
        assertNull(
            EnrollmentQrParser.parse(
                "de.missionleben.portal://enroll/other?token=abcdefghijklmnopqrstuvwxyz0123456789",
            ),
        )
        assertNull(
            EnrollmentQrParser.parse(
                "de.missionleben.portal://enroll?token=abcdefghijklmnopqrstuvwxyz0123456789&redirect=https%3A%2F%2Fevil.example",
            ),
        )
    }
}
