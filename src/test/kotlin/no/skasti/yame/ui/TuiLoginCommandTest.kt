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
            parseLoginAddInput("/login-add modem-user:secret:part-two"),
        )
    }

    @Test
    fun `rejects incomplete or non-ascii credentials`() {
        assertNull(parseLoginAddInput("/login-add user"))
        assertNull(parseLoginAddInput("/login-add :secret"))
        assertNull(parseLoginAddInput("/login-add user:"))
        assertNull(parseLoginAddInput("/login-add usuário:secret"))
    }

    @Test
    fun `masks login password while keeping username visible`() {
        val displayed = maskLoginAddPassword("/login-add modem-user:top-secret")

        assertEquals("/login-add modem-user:••••••••••", displayed)
        assertFalse(displayed.contains("top-secret"))
        assertFalse(parseLoginAddInput("/login-add modem-user:top-secret")
            .toString().contains("top-secret"))
    }
}
