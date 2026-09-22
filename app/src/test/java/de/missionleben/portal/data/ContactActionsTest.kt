package de.missionleben.portal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactActionsTest {
    @Test fun businessNumbersAreFormattedForDialler() {
        assertEquals("+49123456", ContactActions.dialable("+49 123/456"))
        assertEquals("06151456", ContactActions.dialable("(06151) 456"))
        assertEquals("1234", ContactActions.dialable("1234"))
    }

    @Test fun serviceCodesUrisAndPausesAreRejected() {
        listOf("*21*123#", "tel:1234", "123,456", "123;456", "abc", "---", "++49123", "123\n456")
            .forEach { assertNull(it, ContactActions.dialable(it)) }
    }
}
