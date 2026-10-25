package no.skasti.yame.ppp.session

import no.skasti.yame.observer.YameEvent
import no.skasti.yame.ppp.PppControlOption
import no.skasti.yame.ppp.PppControlPacket
import no.skasti.yame.ppp.PppFrame
import no.skasti.yame.ppp.icmp.IcmpPacket
import no.skasti.yame.ppp.ip.Ipv4Packet
import no.skasti.yame.ppp.ipcp.IpcpOptionType
import no.skasti.yame.ppp.lcp.LcpOption
import no.skasti.yame.ppp.lcp.LcpOptionType
import no.skasti.yame.ppp.lcp.LcpPacket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PppCcpRejectionTest {
    @Test
    fun `rejects all captured Win98 CCP requests while IPCP negotiation continues`() {
        val sent = mutableListOf<PppFrame>()
        val events = mutableListOf<YameEvent>()
        newSession(sent, events).use { session ->
            openLcp(session, sent)
            val ipcpRequest = sent.last { it.protocol == PppSession.IPCP_PROTOCOL }
            sent.clear()

            val captures = requireNotNull(javaClass.getResource("/ppp/ccp/win98-configure-requests.hex"))
                .readText().lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }
                .map { hex -> hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.toList()
            assertEquals(10, captures.size)
            for (payload in captures) {
                session.receive(PppFrame(PppSession.CCP_PROTOCOL, payload))
                assertReject(sent.last(), payload)
                assertTrue(session.lcpOpen)
                assertFalse(session.ipcpOpen)
            }
            assertEquals(captures.size, sent.size)
            assertEquals(captures.size, sent.map { it.payload[1] }.distinct().size)
            assertTrue(events.filterIsInstance<UnhandledPacket>().isEmpty())

            openIpcp(session, ipcpRequest)
            assertTrue(session.ipcpOpen)
            sent.clear()
            assertIpv4EchoWorks(session, sent)
            sent.clear()
            session.close()
            session.receive(PppFrame(PppSession.CCP_PROTOCOL, captures.first()))
            assertTrue(sent.isEmpty())
        }
    }

    @Test
    fun `CCP rejection after IPCP opens preserves uncompressed IPv4 traffic`() {
        val sent = mutableListOf<PppFrame>()
        newSession(sent).use { session ->
            openLcp(session, sent)
            openIpcp(session, sent.last { it.protocol == PppSession.IPCP_PROTOCOL })
            sent.clear()
            val ccp = byteArrayOf(1, 10, 0, 4)
            session.receive(PppFrame(PppSession.CCP_PROTOCOL, ccp))
            assertReject(sent.single(), ccp)
            assertTrue(session.lcpOpen)
            assertTrue(session.ipcpOpen)
            sent.clear()
            assertIpv4EchoWorks(session, sent)
        }
    }

    @Test
    fun `does not reply to CCP before LCP opens or after it closes`() {
        val sent = mutableListOf<PppFrame>()
        newSession(sent).use { session ->
            val ccp = PppFrame(PppSession.CCP_PROTOCOL, byteArrayOf(1, 10, 0, 4))
            session.receive(ccp)
            assertTrue(sent.isEmpty())
            session.start()
            sent.clear()
            session.receive(ccp)
            assertTrue(sent.isEmpty())

            // Even with the peer side acknowledged, our request has not been ACKed.
            session.receive(peerLcpRequest())
            sent.clear()
            session.receive(ccp)
            assertTrue(sent.isEmpty())
            session.close()
            session.receive(ccp)
            assertTrue(sent.isEmpty())
        }
    }

    @Test
    fun `LCP termination is acknowledged and prevents CCP replies and stale negotiation reopening`() {
        val sent = mutableListOf<PppFrame>()
        newSession(sent).use { session ->
            openLcp(session, sent)
            val oldLcpRequest = sent.first { it.protocol == PppSession.LCP_PROTOCOL }
            val oldIpcpRequest = sent.last { it.protocol == PppSession.IPCP_PROTOCOL }
            openIpcp(session, oldIpcpRequest)
            sent.clear()

            val terminate = PppFrame(
                PppSession.LCP_PROTOCOL,
                LcpPacket(LcpPacket.TERMINATE_REQUEST, 73, byteArrayOf(0x7e, 0x7d)).encode(),
            )
            session.receive(terminate)
            assertFalse(session.lcpOpen)
            assertFalse(session.ipcpOpen)
            assertEquals(PppSession.LCP_PROTOCOL, sent.single().protocol)
            assertContentEquals(byteArrayOf(6, 73, 0, 4), sent.single().payload)
            sent.clear()

            // A lost Terminate-Ack must be recoverable without reopening the link.
            session.receive(terminate)
            assertContentEquals(byteArrayOf(6, 73, 0, 4), sent.single().payload)
            sent.clear()
            acknowledge(session, oldLcpRequest)
            acknowledge(session, oldIpcpRequest)
            session.receive(PppFrame(PppSession.CCP_PROTOCOL, byteArrayOf(1, 10, 0, 4)))
            assertTrue(sent.isEmpty())
            assertFalse(session.lcpOpen)
            assertFalse(session.ipcpOpen)

            // The live session can negotiate again, but only with a fresh local request.
            session.receive(peerLcpRequest())
            val newRequest = sent.single { frame ->
                frame.protocol == PppSession.LCP_PROTOCOL &&
                    requireNotNull(LcpPacket.parse(frame.payload)).code == LcpPacket.CONFIGURE_REQUEST
            }
            assertTrue(newRequest.payload[1] != oldLcpRequest.payload[1])
            acknowledge(session, oldLcpRequest)
            assertFalse(session.lcpOpen)
            acknowledge(session, newRequest)
            assertTrue(session.lcpOpen)
            assertFalse(session.ipcpOpen)
            sent.clear()
            val ccp = byteArrayOf(1, 11, 0, 4)
            session.receive(PppFrame(PppSession.CCP_PROTOCOL, ccp))
            assertReject(sent.single(), ccp)
        }
    }

    @Test
    fun `termination clears negotiation directions in Request-Sent Ack-Sent and Ack-Received`() {
        for (state in listOf("Request-Sent", "Ack-Sent", "Ack-Received")) {
            val sent = mutableListOf<PppFrame>()
            newSession(sent).use { session ->
                session.start()
                val request = sent.single()
                when (state) {
                    "Ack-Sent" -> session.receive(peerLcpRequest())
                    "Ack-Received" -> acknowledge(session, request)
                }
                assertFalse(session.lcpOpen, state)
                sent.clear()
                session.receive(PppFrame(
                    PppSession.LCP_PROTOCOL,
                    LcpPacket(LcpPacket.TERMINATE_REQUEST, 74, ByteArray(0)).encode(),
                ))
                assertContentEquals(byteArrayOf(6, 74, 0, 4), sent.single().payload, state)
                sent.clear()

                if (state == "Ack-Received") {
                    // The old local ACK cannot combine with a new peer request to open LCP.
                    session.receive(peerLcpRequest())
                    assertFalse(session.lcpOpen, state)
                    sent.clear()
                } else {
                    // A late local ACK cannot combine with the old peer acknowledgement.
                    acknowledge(session, request)
                    assertFalse(session.lcpOpen, state)
                    assertTrue(sent.isEmpty(), state)
                }
                assertFalse(session.ipcpOpen, state)
                session.receive(PppFrame(PppSession.CCP_PROTOCOL, byteArrayOf(1, 10, 0, 4)))
                assertTrue(sent.isEmpty(), state)

                // Both directions must complete again before CCP rejection is enabled.
                if (state == "Ack-Received") acknowledge(session, request)
                else session.receive(peerLcpRequest())
                assertTrue(session.lcpOpen, state)
                assertEquals(PppSession.IPCP_PROTOCOL, sent.last().protocol, state)
                sent.clear()
                val ccp = byteArrayOf(1, 11, 0, 4)
                session.receive(PppFrame(PppSession.CCP_PROTOCOL, ccp))
                assertReject(sent.single(), ccp)
            }
        }
    }

    @Test
    fun `termination after local ACK resumes outstanding configure request retries`() {
        val retried = CountDownLatch(1)
        val terminated = AtomicBoolean(false)
        val request = AtomicReference<PppFrame>()
        PppSession(
            sendFrame = { frame ->
                if (frame.protocol == PppSession.LCP_PROTOCOL &&
                    LcpPacket.parse(frame.payload)?.code == LcpPacket.CONFIGURE_REQUEST
                ) request.compareAndSet(null, frame)
            },
            logger = {
                if (terminated.get() && it.contains("Configure-Request") && it.endsWith("retry")) {
                    retried.countDown()
                }
            },
            restartIntervalMillis = 200,
        ).use { session ->
            session.start()
            acknowledge(session, requireNotNull(request.get()))
            terminated.set(true)
            session.receive(PppFrame(PppSession.LCP_PROTOCOL,
                LcpPacket(LcpPacket.TERMINATE_REQUEST, 75, ByteArray(0)).encode()))
            assertTrue(retried.await(3, TimeUnit.SECONDS), "Negotiation retries did not resume after termination")
            assertFalse(session.lcpOpen)
            assertFalse(session.ipcpOpen)
        }
    }

    @Test
    fun `does not reply to CCP during LCP renegotiation`() {
        val sent = mutableListOf<PppFrame>()
        newSession(sent).use { session ->
            openLcp(session, sent)
            session.receive(peerLcpRequest())
            assertFalse(session.lcpOpen)
            sent.clear()
            session.receive(PppFrame(PppSession.CCP_PROTOCOL, byteArrayOf(1, 10, 0, 4)))
            assertTrue(sent.isEmpty())
        }
    }

    @Test
    fun `truncates rejected information to MRU while retaining header and protocol`() {
        for (mru in listOf(null, 576, 6)) {
            val sent = mutableListOf<PppFrame>()
            newSession(sent).use { session ->
                openLcp(session, sent, mru)
                sent.clear()
                val information = ByteArray(2000) { it.toByte() }
                session.receive(PppFrame(PppSession.CCP_PROTOCOL, information))
                val reply = sent.single()
                val limit = mru ?: PppSession.DEFAULT_MRU
                assertEquals(limit, reply.payload.size)
                assertReject(reply, information.copyOf(limit - 6))
            }
        }
    }

    @Test
    fun `does not emit malformed reject when peer MRU cannot fit required fields`() {
        val sent = mutableListOf<PppFrame>()
        newSession(sent).use { session ->
            openLcp(session, sent, 5)
            sent.clear()
            session.receive(PppFrame(PppSession.CCP_PROTOCOL, byteArrayOf(1, 10, 0, 4)))
            assertTrue(sent.isEmpty())
            assertTrue(session.lcpOpen)
        }
    }

    @Test
    fun `uses changing LCP identifiers with byte wraparound and preserves opaque CCP bytes`() {
        val sent = mutableListOf<PppFrame>()
        newSession(sent).use { session ->
            openLcp(session, sent)
            sent.clear()
            // CCP is unsupported: even empty or malformed CCP information is rejected verbatim.
            val payloads = listOf(byteArrayOf(), byteArrayOf(0x7e, 0x7d), byteArrayOf(1, 99, 0, 4))
            repeat(257) { index ->
                val payload = payloads[index % payloads.size]
                session.receive(PppFrame(PppSession.CCP_PROTOCOL, payload))
                val reject = assertReject(sent.last(), payload)
                assertEquals((index + 2) and 0xff, reject.identifier)
            }
        }
    }

    private fun newSession(
        sent: MutableList<PppFrame>,
        events: MutableList<YameEvent> = mutableListOf(),
    ): PppSession = PppSession(
        sendFrame = sent::add,
        logger = {},
        diagnosticSink = events::add,
        restartIntervalMillis = 60_000,
    )

    private fun openLcp(session: PppSession, sent: List<PppFrame>, mru: Int? = null) {
        session.start()
        val localRequest = sent.first { it.protocol == PppSession.LCP_PROTOCOL }
        session.receive(peerLcpRequest(mru))
        acknowledge(session, localRequest)
        assertTrue(session.lcpOpen)
    }

    private fun peerLcpRequest(mru: Int? = null): PppFrame = PppFrame(
        PppSession.LCP_PROTOCOL,
        LcpPacket(
            LcpPacket.CONFIGURE_REQUEST,
            20,
            if (mru == null) byteArrayOf() else LcpOption(
                LcpOptionType.MRU, byteArrayOf((mru ushr 8).toByte(), mru.toByte()),
            ).encode(),
        ).encode(),
    )

    private fun openIpcp(session: PppSession, localRequest: PppFrame) {
        session.receive(PppFrame(
            PppSession.IPCP_PROTOCOL,
            PppControlPacket(
                PppControlPacket.CONFIGURE_REQUEST,
                21,
                PppControlOption(IpcpOptionType.IP_ADDRESS, session.peerIpAddress.toByteArray()).encode() +
                    PppControlOption(IpcpOptionType.PRIMARY_DNS, session.localIpAddress.toByteArray()).encode(),
            ).encode(),
        ))
        acknowledge(session, localRequest)
    }

    private fun acknowledge(session: PppSession, frame: PppFrame) {
        val packet = requireNotNull(PppControlPacket.parse(frame.payload))
        session.receive(PppFrame(frame.protocol, packet.copy(code = PppControlPacket.CONFIGURE_ACK).encode()))
    }

    private fun assertReject(frame: PppFrame, information: ByteArray): LcpPacket {
        assertEquals(PppSession.LCP_PROTOCOL, frame.protocol)
        val reject = requireNotNull(LcpPacket.parse(frame.payload))
        assertEquals(8, reject.code)
        assertEquals(6 + information.size, frame.payload.size)
        assertContentEquals(byteArrayOf(0x80.toByte(), 0xfd.toByte()) + information, reject.data)
        return reject
    }

    private fun assertIpv4EchoWorks(session: PppSession, sent: List<PppFrame>) {
        val echo = IcmpPacket(type = 8, code = 0, body = byteArrayOf(0, 1, 0, 1, 42))
        session.receive(PppFrame(
            PppSession.IPV4_PROTOCOL,
            Ipv4Packet(
                protocol = Ipv4Packet.ICMP_PROTOCOL,
                source = session.peerIpAddress,
                destination = session.localIpAddress,
                payload = echo.encode(),
            ).encode(),
        ))
        val reply = sent.single()
        assertEquals(PppSession.IPV4_PROTOCOL, reply.protocol)
        val ip = requireNotNull(Ipv4Packet.parse(reply.payload))
        assertEquals(session.peerIpAddress, ip.destination)
        assertContentEquals(requireNotNull(echo.toEchoReply()).encode(), ip.payload)
    }
}
