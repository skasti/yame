package no.skasti.serialmodem.ppp.proxy

data class PppHttpCompatibilityConfig(
    val enabled: Boolean = true,
    val maxRedirects: Int = 8,
    val requestTimeoutMillis: Long = 30_000,
    val maxRequestBytes: Int = 16 * 1024 * 1024,
    val maxResponseBytes: Int = 16 * 1024 * 1024,
    val maxFlows: Int = 16,
    val maxResourceContexts: Int = 64,
    val maxResourceNodesPerContext: Int = 1_024,
    val maxResourceEdgesPerContext: Int = 2_048,
) {
    init {
        require(maxRedirects >= 0) { "HTTP compatibility maxRedirects must not be negative" }
        require(requestTimeoutMillis > 0) { "HTTP compatibility request timeout must be positive" }
        require(maxRequestBytes > 0) { "HTTP compatibility maxRequestBytes must be positive" }
        require(maxResponseBytes > 0) { "HTTP compatibility maxResponseBytes must be positive" }
        require(maxFlows > 0) { "HTTP compatibility maxFlows must be positive" }
        require(maxResourceContexts > 0) { "HTTP compatibility maxResourceContexts must be positive" }
        require(maxResourceNodesPerContext > 0) { "HTTP compatibility maxResourceNodesPerContext must be positive" }
        require(maxResourceEdgesPerContext > 0) { "HTTP compatibility maxResourceEdgesPerContext must be positive" }
    }
}
