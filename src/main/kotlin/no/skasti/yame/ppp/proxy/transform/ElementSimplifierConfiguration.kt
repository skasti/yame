package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.config.*
import java.util.Collections
import java.util.Locale

data class ElementSimplifierRule(val target: String, val style: String? = null) {
    init {
        require(TAG_NAME.matches(target)) { "Invalid element-simplifier target '$target'" }
        require(target.lowercase(Locale.ROOT) !in NON_CONTAINER_TARGETS) {
            "Element-simplifier target must be a normal container element: $target"
        }
        require(style == null || (style.length <= 1_024 && style.none { it.isISOControl() })) {
            "Element-simplifier style must be at most 1024 characters without control characters"
        }
    }
}

/** Immutable rules for one process lifetime; user edits take effect after restart. */
class ElementSimplifierConfiguration(rules: Map<String, ElementSimplifierRule>) {
    val rules: Map<String, ElementSimplifierRule>

    init {
        require(rules.size <= 256) { "Element-simplifier supports at most 256 rules" }
        val normalized = rules.mapKeys { (name, _) ->
            require(TAG_NAME.matches(name)) { "Invalid element-simplifier tag '$name'" }
            name.lowercase(Locale.ROOT)
        }.mapValues { (_, rule) -> rule.copy(target = rule.target.lowercase(Locale.ROOT)) }
        require(normalized.size == rules.size) { "Duplicate element-simplifier tags ignoring case" }
        this.rules = Collections.unmodifiableMap(normalized)
    }

    override fun equals(other: Any?): Boolean = other is ElementSimplifierConfiguration && rules == other.rules
    override fun hashCode(): Int = rules.hashCode()

    companion object {
        fun fromConfigStore(): ElementSimplifierConfiguration {
            return ConfigStore[ElementSimplifierSettings.Rules]
        }

        fun defaults(): ElementSimplifierConfiguration = ElementSimplifierSettings.Rules.default()
    }
}

internal val TAG_NAME = Regex("[A-Za-z][A-Za-z0-9:_-]{0,63}")
private val NON_CONTAINER_TARGETS = setOf(
    "area", "base", "basefont", "br", "col", "embed", "frame", "hr", "img", "input", "isindex", "link", "meta",
    "param", "source", "track", "wbr", "script", "style", "textarea", "title", "xmp", "iframe", "plaintext", "listing",
)
