package com.sdlcagent.orchestrator.llm;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;

/** API-key backed client (profile "anthropic"). Timeout is governed by the HTTP client config, not per call. */
public class SpringAiAnthropicClient implements LlmClient {
    private final ChatClient chatClient;
    private final String defaultModel;

    public SpringAiAnthropicClient(ChatClient chatClient, String defaultModel) {
        this.chatClient = chatClient;
        this.defaultModel = defaultModel;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        String model = request.model() != null ? request.model() : defaultModel;
        long start = System.nanoTime();
        ChatResponse response;
        try {
            var spec = chatClient.prompt()
                    .options(AnthropicChatOptions.builder().model(model).build())
                    .user(request.user());
            if (!request.system().isBlank()) spec = spec.system(request.system());
            response = spec.call().chatResponse();
        } catch (RuntimeException e) {
            throw new LlmException(LlmException.Kind.PROCESS, "anthropic call failed: " + e.getMessage(), e);
        }
        if (response == null || response.getResult() == null) {
            throw new LlmException(LlmException.Kind.PROTOCOL, "empty response from anthropic");
        }
        AssistantMessage out = response.getResult().getOutput();
        var usage = response.getMetadata().getUsage();
        long in = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        long outTokens = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        return new LlmResponse(out.getText(), model, in, outTokens, 0.0,
                (System.nanoTime() - start) / 1_000_000, false);
    }
}
