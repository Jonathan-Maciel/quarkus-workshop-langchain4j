package dev.langchain4j.quarkus.workshop.observability;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class GenAiChatModelListener implements ChatModelListener {

    private static final String OTEL_SPAN_KEY_NAME = "OTelSpan";
    private static final String OTEL_SCOPE_KEY_NAME = "OTelScope";
    private static final String START_TIME_NANOS_KEY = "start_time_nanos";

    private static final AttributeKey<String> GEN_AI_SYSTEM = AttributeKey.stringKey("gen_ai.system");
    private static final AttributeKey<String> GEN_AI_OPERATION_NAME = AttributeKey.stringKey("gen_ai.operation.name");
    private static final AttributeKey<String> GEN_AI_REQUEST_MODEL = AttributeKey.stringKey("gen_ai.request.model");
    private static final AttributeKey<String> GEN_AI_RESPONSE_MODEL = AttributeKey.stringKey("gen_ai.response.model");
    private static final AttributeKey<String> GEN_AI_TOKEN_TYPE = AttributeKey.stringKey("gen_ai.token.type");

    private final DoubleHistogram operationDurationHistogram;
    private final LongHistogram tokenUsageHistogram;
    private final Tracer tracer;

    @Inject
    GenAiTokenUsageHolder tokenUsageHolder;

    public GenAiChatModelListener() {
        Meter meter = GlobalOpenTelemetry.getMeter("dev.langchain4j.quarkus.workshop.genai");
        this.tracer = GlobalOpenTelemetry.getTracer("dev.langchain4j.quarkus.workshop.genai.tracer");

        // Histogram for tracking LLM operation duration
        this.operationDurationHistogram = meter.histogramBuilder("gen_ai.client.operation.duration")
                .setDescription("Duration of GenAI client operations")
                .setUnit("s")
                .build();

        // Histogram for tracking token usage per call
        this.tokenUsageHistogram = meter.histogramBuilder("gen_ai.client.token.usage")
                .ofLongs()
                .setDescription("Token usage for GenAI client operations")
                .setUnit("{token}")
                .build();
    }

    /**
     * Infer the AI provider from the model name.
     */
    private String inferProvider(String modelName) {
        if (modelName == null || modelName.isEmpty()) {
            return "unknown";
        }

        String lowerModel = modelName.toLowerCase();

        if (lowerModel.contains("granite") || lowerModel.contains("ibm/")) {
            return "watsonx";
        } else if (lowerModel.contains("gpt") || lowerModel.contains("openai/")) {
            return "openai";
        } else if (lowerModel.contains("claude")) {
            return "anthropic";
        } else if (lowerModel.contains("llama") || lowerModel.contains("mistral") ||
                lowerModel.contains("qwen") || lowerModel.contains("gemma")) {
            return "ollama";
        }

        // Default to openai for unknown models (OpenAI-compatible API)
        return "openai";
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        if (context == null || context.chatRequest() == null) {
            return;
        }

        var attributes = context.attributes();
        var parentSpan = Span.current();

        String requestModel = context.chatRequest().modelName();
        if (requestModel == null) {
            requestModel = "unknown";
        }

        String provider = inferProvider(requestModel);

        Span span = tracer.spanBuilder("gen_ai chat")
                .setParent(io.opentelemetry.context.Context.current().with(parentSpan))
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("gen_ai.system", provider)
                .setAttribute("gen_ai.operation.name", "chat")
                .setAttribute("gen_ai.request.model", requestModel)
                .startSpan();

        Scope scope = span.makeCurrent();
        attributes.put(OTEL_SPAN_KEY_NAME, span);
        attributes.put(OTEL_SCOPE_KEY_NAME, scope);
        attributes.put(START_TIME_NANOS_KEY, System.nanoTime());

        Log.debugf("GenAI request started with %d messages, span created for model=%s",
                context.chatRequest().messages().size(), requestModel);
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        if (context == null || context.chatResponse() == null) {
            return;
        }

        var attributes = context.attributes();

        Span span = (Span) attributes.get(OTEL_SPAN_KEY_NAME);
        Scope scope = (Scope) attributes.get(OTEL_SCOPE_KEY_NAME);

        Long startTimeNanos = (Long) attributes.get(START_TIME_NANOS_KEY);
        double durationSeconds = 0.0;
        if (startTimeNanos != null) {
            long durationNanos = System.nanoTime() - startTimeNanos;
            durationSeconds = durationNanos / 1_000_000_000.0d;
        }

        String responseModel = context.chatResponse().modelName();
        if (responseModel == null) {
            responseModel = "unknown";
        }

        String provider = inferProvider(responseModel);

        TokenUsage tokenUsage = context.chatResponse().tokenUsage();
        if (tokenUsage != null) {
            tokenUsageHolder.store(tokenUsage);

            if (span != null) {
                span.setAttribute("gen_ai.response.model", responseModel);
                span.setStatus(StatusCode.OK);
                span.end();
            }

            Attributes baseAttributes = Attributes.builder()
                    .put(GEN_AI_SYSTEM, provider)
                    .put(GEN_AI_OPERATION_NAME, "chat")
                    .put(GEN_AI_REQUEST_MODEL, responseModel)
                    .put(GEN_AI_RESPONSE_MODEL, responseModel)
                    .build();

            Attributes durationInputAttributes = Attributes.builder()
                    .putAll(baseAttributes)
                    .put(GEN_AI_TOKEN_TYPE, "input")
                    .build();
            operationDurationHistogram.record(durationSeconds, durationInputAttributes);

            Attributes durationOutputAttributes = Attributes.builder()
                    .putAll(baseAttributes)
                    .put(GEN_AI_TOKEN_TYPE, "output")
                    .build();
            operationDurationHistogram.record(durationSeconds, durationOutputAttributes);

            if (tokenUsage.inputTokenCount() != null && tokenUsage.inputTokenCount() > 0) {
                Attributes inputAttributes = Attributes.builder()
                        .putAll(baseAttributes)
                        .put(GEN_AI_TOKEN_TYPE, "input")
                        .build();
                tokenUsageHistogram.record(tokenUsage.inputTokenCount(), inputAttributes);
            }

            if (tokenUsage.outputTokenCount() != null && tokenUsage.outputTokenCount() > 0) {
                Attributes outputAttributes = Attributes.builder()
                        .putAll(baseAttributes)
                        .put(GEN_AI_TOKEN_TYPE, "output")
                        .build();
                tokenUsageHistogram.record(tokenUsage.outputTokenCount(), outputAttributes);
            }

            Log.debugf("GenAI call completed: input=%d output=%d total=%d model=%s operation=chat duration=%.3fs",
                    tokenUsage.inputTokenCount(),
                    tokenUsage.outputTokenCount(),
                    tokenUsage.totalTokenCount(),
                    responseModel,
                    durationSeconds);
        } else if (span != null) {
            span.setStatus(StatusCode.OK);
            span.end();
        }

        safeCloseScope(scope);
    }

    private void safeCloseScope(Scope scope) {
        if (scope != null) {
            try {
                scope.close();
            } catch (Exception e) {
                Log.warnf(e, "Failed to close OpenTelemetry scope");
            }
        }
    }
}
