package no.skasti.serialmodem.ppp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PppAddressingTest {
    @Test
    fun `CIDR input is normalized and provides first two usable addresses`() {
        val subnet = Ipv4Cidr.parse("10.20.30.1/30")

        assertEquals("10.20.30.0/30", subnet.toString())
        assertEquals(Ipv4Address.parse("10.20.30.1"), subnet.firstUsableAddress())
        assertEquals(Ipv4Address.parse("10.20.30.2"), subnet.secondUsableAddress())
    }

    @Test
    fun `configured subnet is rejected when it overlaps a local interface`() {
        val resolver = PppAddressResolver(
            config = PppIpConfig(Ipv4Cidr.parse("192.168.1.0/30")),
            localNetworksProvider = {
                listOf(LocalIpv4Network("eth0", Ipv4Cidr.parse("192.168.1.0/24")))
            },
            logger = {},
        )

        assertFailsWith<IllegalStateException> {
            resolver.validateConfiguredSubnet()
        }
    }

    @Test
    fun `automatic subnet skips local interface ranges`() {
        val resolver = PppAddressResolver(
            localNetworksProvider = {
                listOf(LocalIpv4Network("vpn0", Ipv4Cidr.parse("10.0.0.0/8")))
            },
            logger = {},
        )

        val addresses = resolver.resolve()

        assertEquals(Ipv4Cidr.parse("172.16.0.0/30"), addresses.allocationSubnet)
        assertEquals(Ipv4Address.parse("172.16.0.1"), addresses.localAddress)
        assertEquals(Ipv4Address.parse("172.16.0.2"), addresses.peerAddress)
    }

    @Test
    fun `unconfigured policy accepts a safe peer requested address`() {
        val resolver = PppAddressResolver(
            localNetworksProvider = {
                listOf(LocalIpv4Network("eth0", Ipv4Cidr.parse("192.168.1.0/24")))
            },
            logger = {},
        )

        assertEquals(
            Ipv4Address.parse("192.168.50.10"),
            resolver.selectPeerAddress(Ipv4Address.parse("192.168.50.10")),
        )
    }

    @Test
    fun `unconfigured policy does not accept a peer address on a local subnet`() {
        val resolver = PppAddressResolver(
            localNetworksProvider = {
                listOf(LocalIpv4Network("eth0", Ipv4Cidr.parse("192.168.1.0/24")))
            },
            logger = {},
        )

        val addresses = resolver.resolve()

        assertEquals(
            addresses.peerAddress,
            resolver.selectPeerAddress(Ipv4Address.parse("192.168.1.10")),
        )
    }

    @Test
    fun `configured policy always assigns the configured peer address`() {
        val resolver = PppAddressResolver(
            config = PppIpConfig(Ipv4Cidr.parse("10.50.0.0/30")),
            localNetworksProvider = { emptyList() },
            logger = {},
        )

        assertEquals(
            Ipv4Address.parse("10.50.0.2"),
            resolver.selectPeerAddress(Ipv4Address.parse("192.168.50.10")),
        )
    }
    @Test
    fun `configured subnet rejects unusable PPP endpoint ranges`() {
        val unusableSubnets =
            listOf(
                "0.0.0.0/30",
                "127.0.0.0/30",
                "169.254.10.0/30",
                "224.0.0.0/30",
                "240.0.0.0/30",
            )

        for (subnet in unusableSubnets) {
            val resolver = PppAddressResolver(
                config = PppIpConfig(Ipv4Cidr.parse(subnet)),
                localNetworksProvider = { emptyList() },
                logger = {},
            )

            assertFailsWith<IllegalStateException>("Expected $subnet to be rejected") {
                resolver.validateConfiguredSubnet()
            }
        }
    }

    @Test
    fun `unconfigured policy rejects unusable peer requested addresses`() {
        val resolver = PppAddressResolver(
            localNetworksProvider = { emptyList() },
            logger = {},
        )
        val assignedPeer = resolver.resolve().peerAddress
        val unusableAddresses =
            listOf(
                "0.0.0.1",
                "127.0.0.1",
                "169.254.10.20",
                "224.0.0.1",
                "255.255.255.255",
            )

        for (address in unusableAddresses) {
            assertEquals(
                assignedPeer,
                resolver.selectPeerAddress(Ipv4Address.parse(address)),
                "Expected $address to be replaced with the assigned peer address",
            )
        }
    }

}
