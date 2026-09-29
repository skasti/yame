package no.skasti.yame.ui

import no.skasti.yame.modem.HayesModemConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `accepts maximum login lengths and rejects longer credentials`() {
        val maxUsername = "u".repeat(HayesModemConfig.MAX_LOGIN_USERNAME_LENGTH)
        val maxPassword = "p".repeat(HayesModemConfig.MAX_LOGIN_PASSWORD_LENGTH)

        assertEquals(
            TuiLoginCredentials(maxUsername, maxPassword),
            parseLoginInput("/login " + maxUsername + ":" + maxPassword),
        )
        assertNull(parseLoginInput("/login " + maxUsername + "u:" + maxPassword))
        assertNull(parseLoginInput("/login " + maxUsername + ":" + maxPassword + "p"))
        assertTrue(loginInputWithinLimits("/login " + maxUsername + ":" + maxPassword))
        assertFalse(loginInputWithinLimits("/login " + maxUsername + "u:" + maxPassword))
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
