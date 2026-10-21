package no.skasti.yame.ppp.proxy

/** Process-owned registries shared by production component defaults. */
internal object YameRegistries {
    @Volatile
    private var sharedNavigationResources: NavigationResourceRegistry? = null

    /**
     * Lazily returns the process registry. The first production configuration establishes its
     * retention limits; reconnects and later accesses keep the same registry and its contents.
     * Isolated consumers should construct and inject their own [NavigationResourceRegistry].
     */
    fun navigationResources(
        config: PppHttpCompatibilityConfig = PppHttpCompatibilityConfig(),
    ): NavigationResourceRegistry =
        sharedNavigationResources ?: synchronized(this) {
            sharedNavigationResources ?: NavigationResourceRegistry(
                maxContexts = config.maxResourceContexts,
                maxNodesPerHost = config.maxResourceNodesPerContext,
                maxEdgesPerContext = config.maxResourceEdgesPerContext,
            ).also { sharedNavigationResources = it }
        }
}
