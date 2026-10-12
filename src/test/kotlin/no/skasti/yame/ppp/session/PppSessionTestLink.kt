package no.skasti.yame.ppp.session

import no.skasti.yame.ppp.PppControlOption
import no.skasti.yame.ppp.PppControlPacket
import no.skasti.yame.ppp.PppFrame
import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.ppp.ipcp.IpcpOptionType
import no.skasti.yame.ppp.lcp.LcpOption
import no.skasti.yame.ppp.lcp.LcpOptionType
import no.skasti.yame.ppp.lcp.LcpPacket
import kotlin.test.assertTrue

internal fun openIpcp(
    session: PppSession,
    sent: MutableList<PppFrame>,
    peerMru: Int? = null,
) {
    session.start()

    val lcpOptions = buildList {
        if (peerMru != null) {
            add(
                LcpOption(
                    type = LcpOptionType.MRU,
                    data = byteArrayOf(
                        (peerMru ushr 8).toByte(),
                        peerMru.toByte(),
                    ),
                ),
            )
        }
        add(
            LcpOption(
                type = LcpOptionType.ACCM,
                data = byteArrayOf(0x00, 0x00, 0x00, 0x00),
            ),
        )
    }.fold(ByteArray(0)) { bytes, option -> bytes + option.encode() }

    session.receive(
        PppFrame(
            protocol = PppSession.LCP_PROTOCOL,
            payload = LcpPacket(
                code = LcpPacket.CONFIGURE_REQUEST,
                identifier = 0x1c,
                data = lcpOptions,
            ).encode(),
        ),
    )

    val localLcpRequest = sent
        .first { it.protocol == PppSession.LCP_PROTOCOL }
        .let { requireNotNull(LcpPacket.parse(it.payload)) }
    session.receive(
        PppFrame(
            protocol = PppSession.LCP_PROTOCOL,
            payload = LcpPacket(
                code = LcpPacket.CONFIGURE_ACK,
                identifier = localLcpRequest.identifier,
                data = localLcpRequest.data,
            ).encode(),
        ),
    )

    val localIpcpRequest = sent
        .last { it.protocol == PppSession.IPCP_PROTOCOL }
        .let { requireNotNull(PppControlPacket.parse(it.payload)) }
    session.receive(
        PppFrame(
            protocol = PppSession.IPCP_PROTOCOL,
            payload = PppControlPacket(
                code = PppControlPacket.CONFIGURE_REQUEST,
                identifier = 9,
                data =
                    PppControlOption(
                        type = IpcpOptionType.IP_ADDRESS,
                        data = Ipv4Address.parse("10.0.0.2").toByteArray(),
                    ).encode() +
                            PppControlOption(
                                type = IpcpOptionType.PRIMARY_DNS,
                                data = Ipv4Address.parse("10.0.0.1").toByteArray(),
                            ).encode(),
            ).encode(),
        ),
    )
    session.receive(
        PppFrame(
            protocol = PppSession.IPCP_PROTOCOL,
            payload = PppControlPacket(
                code = PppControlPacket.CONFIGURE_ACK,
                identifier = localIpcpRequest.identifier,
                data = localIpcpRequest.data,
            ).encode(),
        ),
    )

    assertTrue(session.ipcpOpen)
}
