package de.missionleben.portal.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONObject
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceServiceRepositoryTest {
    private val repository = DeviceServiceRepository()

    @Test fun announcementParserRetainsArchiveAndExpiryMetadata() {
        val item = repository.parseAnnouncements(JSONObject("""{"results":[{"id":19,"subject":"Synthetic",
            "message":"Readable history","time":2000000000,"delete_time":2000000100,"archived":true}]}"""))
            .single()
        assertEquals(19L, item.id)
        assertEquals(2000000100L, item.expiresAtEpochSeconds)
        assertEquals(true, item.archived)
    }

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
