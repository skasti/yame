package no.skasti.yame.ui

/** The same visible tree drives keyboard navigation and viewport positioning. */
internal data class HttpProxyRow(
    val host: DashboardHttpHost,
    val resource: DashboardHttpResource? = null,
    val transformationIndex: Int? = null,
)

internal fun httpProxyRows(hosts: List<DashboardHttpHost>): List<HttpProxyRow> = buildList {
    hosts.forEach { host ->
        add(HttpProxyRow(host))
        if (host.expanded) host.resources.forEach { resource ->
            add(HttpProxyRow(host, resource))
            if (resource.expanded) {
                // Include an explicit empty state so expansion also explains a passthrough.
                repeat(resource.transformations.size.coerceAtLeast(1)) { index ->
                    add(HttpProxyRow(host, resource, index))
                }
            }
        }
    }
}
