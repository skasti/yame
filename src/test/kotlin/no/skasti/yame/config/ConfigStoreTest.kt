package no.skasti.yame.config

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

class ConfigStoreTest {
    private val serial = SettingGroup("serial")
    private val modem = SettingGroup("modem")
    private val baud = SettingKey<Int>("baud", serial, { require(it > 0) })
    private val tone = SettingKey<Boolean>("tone", modem)

    @Test fun `declared group and key names must match lowercase INI identity`() {
        for (name in listOf("Serial", "serial.Port", "SERIAL.port", "serial..port")) {
            val error = assertFailsWith<IllegalArgumentException> { SettingGroup(name) }
            assertTrue(error.message.orEmpty().contains("lowercase"), name)
        }
        for (name in listOf("Baud", "baudRate", "FOO", "bad.name")) {
            val error = assertFailsWith<IllegalArgumentException> { SettingKey<Int>(name, serial) }
            assertTrue(error.message.orEmpty().contains("lowercase"), name)
        }
        val group = SettingGroup("serial.options_2")
        assertEquals("serial.options_2.flow-control_2", SettingKey<Int>("flow-control_2", group).path)
    }

    @Test fun `batch validates saves once and delivers indexed group views after commit`() {
        var saves = 0
        val store = ConfigStore(listOf(baud to 38400, tone to true), persist = { saves++ })
        val events = mutableListOf<ConfigChanges>()
        val handler: (ConfigChanges) -> Unit = {
            assertEquals(9600, it[baud]?.value)
            assertNull(it[tone])
            assertFalse(store[tone])
            events += it
        }
        store.onChange[serial] += handler
        var modemEvents = 0
        val subscription = store.onChange[modem].subscribe { modemEvents++ }
        store.set(baud to 9600, tone to false)
        assertEquals(1, saves)
        assertEquals(1, events.size)
        assertEquals(1, modemEvents)
        assertEquals(listOf("serial.baud"), events.single().map { it.key.path })
        store.set(baud to 9600, tone to false)
        assertEquals(1, saves)
        store.onChange[serial] -= handler
        subscription.close()
        store.set(baud to 19200, tone to true)
        assertEquals(1, events.size)
        assertEquals(1, modemEvents)
    }

    @Test fun `validation duplicates unregistered keys and save failures leave state untouched`() {
        var saves = 0
        val store = ConfigStore(listOf(baud to 38400, tone to true), persist = { saves++; throw IOException("disk full") })
        var events = 0
        store.onChange.subscribe { events++ }
        assertFailsWith<IllegalArgumentException> { store.set(tone to false, baud to -1) }
        assertFailsWith<IllegalArgumentException> { store.set(baud to 9600, baud to 19200) }
        assertFailsWith<IllegalArgumentException> { store.set(SettingKey<Int>(baud.name, serial) to 9600) }
        assertEquals(0, saves)
        assertFailsWith<IOException> { store.set(baud to 9600, tone to false) }
        assertEquals(38400, store[baud])
        assertTrue(store[tone])
        assertEquals(0, events)
    }

    @Test fun `nullable lookup distinguishes absent from present null and initial subscription is ordered`() {
        val port = SettingKey<String?>("port", serial)
        val store = ConfigStore(listOf(port to "COM1", baud to 38400))
        val values = mutableListOf<String?>()
        store.onChange[serial].subscribe(initial = true) { changes ->
            val value = assertNotNull(changes[port])
            values += value.value
            if (values.size == 1) store.set(port to null)
        }
        assertEquals(listOf("COM1", null), values)
        assertNull(store[port])
    }

    @Test fun `subscriber failure does not undo commits and reentrant changes preserve event order`() {
        var failures = 0
        val store = ConfigStore(listOf(baud to 38400, tone to true), onSubscriberFailure = { failures++ })
        val seen = mutableListOf<Int>()
        store.onChange[serial].subscribe { throw IllegalStateException() }
        store.onChange[serial].subscribe {
            val value = it[baud]!!.value
            seen += value
            if (value == 9600) store.set(baud to 19200)
        }
        store.set(baud to 9600)
        assertEquals(listOf(9600, 19200), seen)
        assertEquals(2, failures)
        assertEquals(19200, store[baud])
    }

    @Test fun `concurrent saves serialize and listeners can await another committing thread without deadlock`() {
        val saving = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val savedValues = mutableListOf<Int>()
        val events = mutableListOf<Int>()
        val store = ConfigStore(listOf(baud to 38400), persist = {
            val value = it[baud]!!.value
            if (value == 9600) {
                saving.countDown()
                check(releaseSave.await(5, TimeUnit.SECONDS))
            }
            savedValues += value
        })
        store.onChange[serial].subscribe { events += it[baud]!!.value }
        val first = thread { store.set(baud to 9600) }
        assertTrue(saving.await(5, TimeUnit.SECONDS))
        val second = thread { store.set(baud to 19200) }
        releaseSave.countDown()
        first.join(5000); second.join(5000)
        assertFalse(first.isAlive); assertFalse(second.isAlive)
        assertEquals(listOf(9600, 19200), savedValues)
        assertEquals(savedValues, events)
        val completed = CountDownLatch(1)
        val handle = store.onChange[serial].subscribe {
            if (it[baud]?.value == 4800) {
                thread { store.set(baud to 2400); completed.countDown() }
                assertTrue(completed.await(5, TimeUnit.SECONDS))
            }
        }
        store.set(baud to 4800)
        handle.close()
        assertEquals(2400, store[baud])
    }

    @Test fun `cleanup skips queued delivery and earlier change notifications keep their values`() {
        val store = ConfigStore(listOf(baud to 38400, tone to true))
        lateinit var subscription: AutoCloseable
        store.onChange[serial].subscribe { subscription.close() }
        var calls = 0
        subscription = store.onChange[serial].subscribe { calls++ }
        store.set(baud to 9600)
        assertEquals(0, calls)
        var first: ConfigChanges? = null
        store.onChange[serial].subscribe(initial = true) { first = it }.close()
        store.set(baud to 19200)
        assertEquals(9600, first?.get(baud)?.value)
    }
}
