package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.ppp.proxy.ReferenceRole
import no.skasti.yame.ppp.proxy.ResourceKind

internal class ReadablePageTransformer(
    private val maxOutputBytes: Int,
    private val interpreter: PageInterpreter = ReadabilityPageInterpreter(),
    private val renderer: LegacyHtmlRenderer = LegacyHtmlRenderer(),
) : ResourceTransformer {
    override val id = HtmlTransformer.READABILITY.command
    override val phase = ResourceTransformPhase.STRUCTURAL
    override val priority = 0

    override fun supports(context: ResourceTransformationContext, state: ResourceTransformationState): Boolean {
        val current = state.resource.representation
        fun header(name: String) = current.headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
        return context.requestMethod == "GET" && context.kind == ResourceKind.DOCUMENT &&
            context.role == ReferenceRole.NAVIGATION && current.statusCode == 200 &&
            current.body.isNotEmpty() && current.body.size <= ReadabilityPageInterpreter.MAX_INPUT_CHARS &&
            header("content-type")?.substringBefore(';')?.trim().equals("text/html", true) &&
            (header("content-encoding") == null || header("content-encoding").equals("identity", true))
    }

    override fun transform(context: ResourceTransformationContext, state: ResourceTransformationState): ResourceTransformationState {
        val current = state.resource.representation
        // Failures leave the complete prior representation available to the ordinary cleanup stages.
        val result = try {
            rewriteEncodedTextBody(current.headers, current.body) { html ->
                interpreter.interpret(context.upstreamUri, html)?.let(renderer::render) ?: html
            }
        } catch (_: Exception) {
            return state
        }
        if (result === current.body || result.size > maxOutputBytes) return state
        return state.copy(resource = state.resource.copy(transformed = TransformedRepresentation(
            context.transformationProfile,
            ResourceRepresentation(current.statusCode, transformedHeadersFrom(current.headers), result),
            state.resource.transformed?.transformations.orEmpty() + ResourceTransformationSummary(
                id, current.body.size, result.size, "Readability4J article rendered as legacy HTML",
            ),
        )))
    }
}
