package com.djkollmax.deck90

import org.junit.Assert.assertEquals
import org.junit.Test

class StoreTest {
    @Test fun `the mode kept is read back as it was, AUX included`() {
        for (m in listOf("radio", "usb", "cd", "server", "aux")) assertEquals(m, Store.modeOf(m))
    }

    @Test fun `an unknown or missing mode is the radio`() {
        assertEquals("radio", Store.modeOf(null))
        assertEquals("radio", Store.modeOf("tape"))
    }
}
