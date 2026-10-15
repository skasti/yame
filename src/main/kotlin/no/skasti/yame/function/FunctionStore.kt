package no.skasti.yame.function

class FunctionKey<A, R>(val path: String)

/** Typed synchronous operations. Exceptions propagate to the invoking adapter. */
class FunctionStore {
    private val lock = Any()
    private class Registration<A, R>(val key: FunctionKey<A, R>, val function: (A) -> R)
    private val entries = linkedMapOf<String, Registration<*, *>>()

    fun <A, R> register(key: FunctionKey<A, R>, function: (A) -> R): AutoCloseable {
        val registration = Registration(key, function)
        synchronized(lock) {
            require(key.path !in entries) { "Function already registered: ${key.path}" }
            entries[key.path] = registration
        }
        return AutoCloseable { synchronized(lock) { if (entries[key.path] === registration) entries.remove(key.path) } }
    }

    @Suppress("UNCHECKED_CAST")
    fun <A, R> invoke(key: FunctionKey<A, R>, argument: A): R {
        val registration = synchronized(lock) {
            requireNotNull(entries[key.path]) { "Unavailable function: ${key.path}" }.also {
                require(it.key === key) { "Function key mismatch: ${key.path}" }
            }
        } as Registration<A, R>
        return registration.function(argument)
    }

    fun invoke(key: FunctionKey<Unit, Unit>) = invoke(key, Unit)
    fun keys(): List<FunctionKey<*, *>> = synchronized(lock) { entries.values.map { it.key } }
}
