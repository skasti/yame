package no.skasti.yame.ppp.proxy

import no.skasti.yame.observer.AbstractYameEvent
import no.skasti.yame.observer.VersionNumber
import no.skasti.yame.ppp.proxy.transform.ResourceTransformationSummary
import java.net.URI
import java.time.Instant
import java.util.ArrayList
import java.util.Collections

/** Why the proxy resource graph emitted an observation. */
enum class ResourceGraphChange {
    CREATED,
    USED,
    REMOVED,
}

/** Why the proxy resource registry emitted an observation. */
enum class ResourceLifecycleChange {
    ADMITTED,
    DISCOVERED,
    ASSOCIATION_ADDED,
    UPDATED,
    FETCH_STARTED,
    FETCH_RETRIED,
    FETCH_STATE_CHANGED,
    FETCH_COMPLETED,
    KIND_UPDATED,
    TRANSFORMATIONS_UPDATED,
    REMOVED,
}

/**
 * Immutable observation of one graph lifetime transition. [occurredAt] is
 * captured by the registry at the authoritative transition, before event
 * publication or subscriber delivery.
 */
class ResourceGraphLifecycleEvent(
    val change: ResourceGraphChange,
    val graphId: Long,
    val rootLegacyUri: URI,
    val occurredAt: Instant,
    val usageCount: Long,
    val lastUsedAt: Instant?,
    initialResources: Collection<ResourceLifecycleEvent> = emptyList(),
) : AbstractYameEvent() {
    /** Initial resource values are present on CREATED and empty for later graph transitions. */
    val initialResources: List<ResourceLifecycleEvent> = immutableSnapshotList(initialResources)

    override val typeName = "yame.ppp.proxy.resource-graph.lifecycle"
    override val version = VersionNumber(1, 0, 0)
}

/**
 * Immutable, self-contained observation of one resource's state in one graph
 * lifetime. [canonicalResourceKey] is the registry's normalized identity;
 * [resourceLegacyUri] remains available for display and legacy-client use.
 */
class ResourceLifecycleEvent(
    val change: ResourceLifecycleChange,
    val graphId: Long,
    val rootLegacyUri: URI,
    val canonicalResourceKey: String,
    val resourceLegacyUri: URI,
    val upstreamUri: URI?,
    val role: ReferenceRole,
    val kind: ResourceKind,
    val contentBase: URI?,
    val state: ResourceState,
    val prefetched: Boolean,
    val fetchAttemptId: Long?,
    val fetchAttemptNumber: Long?,
    associations: Collection<ResourceEdgeSnapshot>,
    transformations: Collection<ResourceTransformationSummary>,
    val graphUsageCount: Long,
    val graphLastUsedAt: Instant?,
    val occurredAt: Instant,
    /** Authoritative, monotonic revision within this graph's resource membership. */
    val resourceRevision: Long = 1L,
) : AbstractYameEvent() {
    init { require(resourceRevision > 0) }
    /** Independent, unmodifiable copies: neither registry state nor callers can mutate the event. */
    val associations: List<ResourceEdgeSnapshot> = immutableSnapshotList(associations)
    val transformations: List<ResourceTransformationSummary> = immutableSnapshotList(transformations)

    override val typeName = "yame.ppp.proxy.resource.lifecycle"
    override val version = VersionNumber(1, 1, 0)
}

internal fun <T> immutableSnapshotList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
