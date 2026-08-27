package no.skasti.serialmodem

import kotlin.test.Test
import kotlin.test.assertFailsWith

class MainTest {
    @Test
    fun `tone test rejects blank number`() {
        assertFailsWith<IllegalArgumentException> {
            main(arrayOf("--test-tone", ""))
        }
    }

    @Test
    fun `tone test rejects number that normalizes to empty`() {
        assertFailsWith<IllegalArgumentException> {
            main(arrayOf("--test-tone", "---"))
        }
    }
}
