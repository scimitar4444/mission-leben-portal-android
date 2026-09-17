package de.missionleben.portal.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceServiceRepositoryTest {
    private val repository = DeviceServiceRepository()

    @Test
    fun extractsConversationTokenFromTalkUrl() {
        assertEquals(
            "aBcd_123-X",
            repository.extractTalkToken("https://nextcloud.example/call/aBcd_123-X?foo=bar"),
        )
    }

    @Test
    fun rejectsArbitraryUrlWithoutTalkToken() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.extractTalkToken("file:///etc/passwd")
        }
    }
}
