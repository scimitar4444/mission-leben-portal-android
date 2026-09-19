package de.missionleben.portal.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val validManifest = UpdateManifest(
        schema = 1,
        packageName = "de.missionleben.portal",
        versionCode = 27,
        versionName = "0.7.9",
        apkUrl = "https://github.com/scimitar4444/mission-leben-portal-android/" +
            "releases/download/v0.7.9/mission-leben-zentral.apk",
        sha256 = "a".repeat(64),
        sizeBytes = 40_000_000,
    )

    @Test
    fun `newer trusted manifest becomes available`() {
        val update = UpdatePolicy.availableUpdate(validManifest, currentVersionCode = 26)

        assertEquals(27L, update?.versionCode)
        assertEquals("0.7.9", update?.versionName)
    }

    @Test
    fun `current or older build is not offered`() {
        assertNull(UpdatePolicy.availableUpdate(validManifest, currentVersionCode = 27))
        assertNull(UpdatePolicy.availableUpdate(validManifest, currentVersionCode = 28))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `foreign package is rejected`() {
        UpdatePolicy.availableUpdate(validManifest.copy(packageName = "example.attacker"), 26)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `foreign download host is rejected`() {
        UpdatePolicy.availableUpdate(
            validManifest.copy(apkUrl = "https://example.org/mission-leben-zentral.apk"),
            26,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `path traversal and query parameters are rejected`() {
        UpdatePolicy.availableUpdate(
            validManifest.copy(
                apkUrl = "https://github.com/scimitar4444/mission-leben-portal-android/" +
                    "releases/download/v0.7.9/../mission-leben-zentral.apk?next=evil",
            ),
            26,
        )
    }

    @Test
    fun `automatic check is throttled for six hours`() {
        val now = 1_800_000_000_000L
        assertTrue(UpdatePolicy.shouldCheck(0L, now))
        assertFalse(UpdatePolicy.shouldCheck(now - UpdatePolicy.CHECK_INTERVAL_MILLIS + 1L, now))
        assertTrue(UpdatePolicy.shouldCheck(now - UpdatePolicy.CHECK_INTERVAL_MILLIS, now))
        assertTrue(UpdatePolicy.shouldCheck(now + 1L, now))
    }
}
