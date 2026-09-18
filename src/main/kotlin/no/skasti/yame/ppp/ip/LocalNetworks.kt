package no.skasti.yame.ppp.ip

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class LocalIpv4Network(
    val interfaceName: String,
    val subnet: Ipv4Cidr,
)

fun discoverLocalIpv4Networks(): List<LocalIpv4Network> {
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()

    return Collections.list(interfaces)
        .filter { networkInterface ->
            runCatching {
                networkInterface.isUp
            }.getOrDefault(false)
        }
        .flatMap { networkInterface ->
            networkInterface.interfaceAddresses.mapNotNull { interfaceAddress ->
                val address = interfaceAddress.address as? Inet4Address
                    ?: return@mapNotNull null
                val prefix = interfaceAddress.networkPrefixLength.toInt()
                if (prefix !in 0..32) return@mapNotNull null

                val ip = Ipv4Address.fromBytes(address.address)
                LocalIpv4Network(
                    interfaceName = networkInterface.name,
                    subnet = Ipv4Cidr.parse("$ip/$prefix"),
                )
            }
        }
}