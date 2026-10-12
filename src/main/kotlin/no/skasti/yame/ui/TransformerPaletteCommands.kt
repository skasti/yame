package no.skasti.yame.ui

import no.skasti.yame.config.HtmlTransformer
import no.skasti.yame.config.HtmlTransformerControl

/** Called by each transformer, without passing a palette or TUI observer through the proxy. */
internal fun registerTransformerCommand(transformer: HtmlTransformer, control: HtmlTransformerControl) {
    control.registerOnce(transformer) {
        val path = "/transformers/${transformer.command}"
        val on = CommandPalette.add("$path/on", { if (control.snapshot().enabled(transformer)) "current" else "" }) {
            control.setEnabled(transformer, true)
        }
        val off = try {
            CommandPalette.add("$path/off", { if (!control.snapshot().enabled(transformer)) "current" else "" }) {
                control.setEnabled(transformer, false)
            }
        } catch (error: Exception) {
            on.close()
            throw error
        }
        AutoCloseable { on.close(); off.close() }
    }
}
