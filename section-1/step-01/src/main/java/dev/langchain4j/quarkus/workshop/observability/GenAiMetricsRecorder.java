package dev.langchain4j.quarkus.workshop.observability;

import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class GenAiMetricsRecorder {

    private static final AttributeKey<String> GEN_AI_SYSTEM = AttributeKey.stringKey("gen_ai.system");
    private static final AttributeKey<String> GEN_AI_OPERATION_NAME = AttributeKey.stringKey("gen_ai.operation.name");
    private static final AttributeKey<String> GEN_AI_REQUEST_MODEL = AttributeKey.stringKey("gen_ai.request.model");

    private final LongCounter workflowCompletedCounter;

    @Inject
    GenAiTokenUsageHolder tokenUsageHolder;

    public GenAiMetricsRecorder() {
        Meter meter = GlobalOpenTelemetry.getMeter("dev.langchain4j.quarkus.workshop.genai");

        this.workflowCompletedCounter = meter.counterBuilder("genai.chat.workflow.completed")
                .setDescription("Number of completed GenAI chat workflows")
                .setUnit("{workflow}")
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

        return "openai";
    }

    public void recordOperation(long durationNanos, String modelName) {
        recordOperation(durationNanos, modelName, 0, 0);
    }

    public void recordOperation(long durationNanos, String modelName,
                                long inputTokens, long outputTokens) {
        double durationSeconds = durationNanos / 1_000_000_000.0d;

        TokenUsage accumulatedTokens = tokenUsageHolder.consume();
        long totalInputTokens = inputTokens;
        long totalOutputTokens = outputTokens;

        if (accumulatedTokens != null) {
            if (accumulatedTokens.inputTokenCount() != null) {
                totalInputTokens = accumulatedTokens.inputTokenCount();
            }
            if (accumulatedTokens.outputTokenCount() != null) {
                totalOutputTokens = accumulatedTokens.outputTokenCount();
            }
        }

        String provider = inferProvider(modelName);

        Attributes baseAttributes = Attributes.builder()
                .put(GEN_AI_SYSTEM, provider)
                .put(GEN_AI_OPERATION_NAME, "chat")
                .put(GEN_AI_REQUEST_MODEL, modelName == null ? "unknown" : modelName)
                .build();

        workflowCompletedCounter.add(1, baseAttributes);

        Log.infof(
                "Recorded workflow completion: duration=%.3fs model=%s inputTokens=%d outputTokens=%d",
                durationSeconds,
                modelName,
                totalInputTokens,
                totalOutputTokens);
    }
}
