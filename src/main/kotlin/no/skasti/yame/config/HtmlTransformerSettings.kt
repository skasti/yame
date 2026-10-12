package no.skasti.yame.config

import java.util.concurrent.atomic.AtomicReference

enum class HtmlTransformer(val command: String) {
    SCRIPT_REMOVER("script-remover"),
    STYLE_REMOVER("style-remover"),
    ATTRIBUTE_CLEANER("attribute-cleaner"),
    ELEMENT_SIMPLIFIER("element-simplifier"),
}

data class HtmlTransformerSettings(
    val scriptRemover: Boolean = true,
    val styleRemover: Boolean = true,
    val attributeCleaner: Boolean = true,
    val elementSimplifier: Boolean = true,
) {
    fun enabled(transformer: HtmlTransformer): Boolean = when (transformer) {
        HtmlTransformer.SCRIPT_REMOVER -> scriptRemover
        HtmlTransformer.STYLE_REMOVER -> styleRemover
        HtmlTransformer.ATTRIBUTE_CLEANER -> attributeCleaner
        HtmlTransformer.ELEMENT_SIMPLIFIER -> elementSimplifier
    }

    fun withEnabled(transformer: HtmlTransformer, enabled: Boolean): HtmlTransformerSettings = when (transformer) {
        HtmlTransformer.SCRIPT_REMOVER -> copy(scriptRemover = enabled)
        HtmlTransformer.STYLE_REMOVER -> copy(styleRemover = enabled)
        HtmlTransformer.ATTRIBUTE_CLEANER -> copy(attributeCleaner = enabled)
        HtmlTransformer.ELEMENT_SIMPLIFIER -> copy(elementSimplifier = enabled)
    }

    val profileToken: String get() = HtmlTransformer.entries.joinToString("") { if (enabled(it)) "1" else "0" }
}

/** Shared across PPP/TCP instances; each HTTP request takes one immutable snapshot. */
class HtmlTransformerControl(
    initial: HtmlTransformerSettings,
    private val onChanged: (HtmlTransformerSettings) -> Unit = {},
) : AutoCloseable {
    private val settings = AtomicReference(initial)
    private val registrations = linkedMapOf<HtmlTransformer, AutoCloseable>()
    private var closed = false

    fun snapshot(): HtmlTransformerSettings = settings.get()

    fun setEnabled(transformer: HtmlTransformer, enabled: Boolean) {
        val previous = settings.getAndUpdate { it.withEnabled(transformer, enabled) }
        if (previous.enabled(transformer) != enabled) onChanged(snapshot())
    }

    @Synchronized
    internal fun registerOnce(transformer: HtmlTransformer, register: () -> AutoCloseable) {
        if (!closed && transformer !in registrations) registrations[transformer] = register()
    }

    @Synchronized
    override fun close() {
        closed = true
        registrations.values.forEach(AutoCloseable::close)
        registrations.clear()
    }
}
