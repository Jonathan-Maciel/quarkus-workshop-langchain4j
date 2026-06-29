package dev.langchain4j.quarkus.workshop.observability;

import dev.langchain4j.model.output.TokenUsage;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class GenAiTokenUsageHolder {

    private final AtomicLong accumulatedInputTokens = new AtomicLong(0);
    private final AtomicLong accumulatedOutputTokens = new AtomicLong(0);
    private final AtomicLong accumulatedTotalTokens = new AtomicLong(0);

    public void store(TokenUsage tokenUsage) {
        if (tokenUsage != null) {
            if (tokenUsage.inputTokenCount() != null) {
                accumulatedInputTokens.addAndGet(tokenUsage.inputTokenCount());
            }
            if (tokenUsage.outputTokenCount() != null) {
                accumulatedOutputTokens.addAndGet(tokenUsage.outputTokenCount());
            }
            if (tokenUsage.totalTokenCount() != null) {
                accumulatedTotalTokens.addAndGet(tokenUsage.totalTokenCount());
            }
        }
    }

    public TokenUsage consume() {
        long inputTokens = accumulatedInputTokens.getAndSet(0);
        long outputTokens = accumulatedOutputTokens.getAndSet(0);
        long totalTokens = accumulatedTotalTokens.getAndSet(0);

        // Return null if no tokens were accumulated
        if (inputTokens == 0 && outputTokens == 0 && totalTokens == 0) {
            return null;
        }

        // Create a TokenUsage object with accumulated values
        return new TokenUsage(
                inputTokens > 0 ? (int) inputTokens : null,
                outputTokens > 0 ? (int) outputTokens : null,
                totalTokens > 0 ? (int) totalTokens : null
        );
    }
}
