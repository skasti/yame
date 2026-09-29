package no.skasti.yame.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TuiLoginCommandTest {
    @Test
    fun `parses login credentials and preserves colons in password`() {
        assertEquals(
            TuiLoginCredentials("modem-user", "secret:part-two"),
            parseLoginInput("/login modem-user:secret:part-two"),
        )
    }

    @Test
    fun `rejects incomplete or non-ascii credentials`() {
        assertNull(parseLoginInput("/login user"))
        assertNull(parseLoginInput("/login :secret"))
        assertNull(parseLoginInput("/login user:"))
        assertNull(parseLoginInput("/login usuário:secret"))
    }

    @Test
    fun `masks login password while keeping username visible`() {
        val displayed = maskLoginPassword("/login modem-user:top-secret")

        assertEquals("/login modem-user:••••••••••", displayed)
        assertFalse(displayed.contains("top-secret"))
        assertFalse(parseLoginInput("/login modem-user:top-secret")
            .toString().contains("top-secret"))
    }

    @Test
    fun `parses tone on and off command arguments`() {
        assertEquals(true, parseToneInput("/tone on"))
        assertEquals(false, parseToneInput("/TONE off"))
        assertNull(parseToneInput("/tone maybe"))
        assertNull(parseToneInput("/tone on now"))
    }
}
