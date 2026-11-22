package com.sdlcagent.orchestrator.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Scripted client for engine tests: no quota spent. Records every request it receives. */
public class FakeLlmClient implements LlmClient {
    private final Function<LlmRequest, String> responder;
    private final List<LlmRequest> requests = new ArrayList<>();

    public FakeLlmClient(Function<LlmRequest, String> responder) {
        this.responder = responder;
    }

    /** Returns the given texts in order; the last one repeats. */
    public static FakeLlmClient sequence(String... texts) {
        int[] i = {0};
        return new FakeLlmClient(r -> texts[Math.min(i[0]++, texts.length - 1)]);
    }

    @Override
    public synchronized LlmResponse complete(LlmRequest request) {
        requests.add(request);
        String text = responder.apply(request);
        return new LlmResponse(text, "fake", 0, 0, 0.0, 0, false);
    }

    public synchronized List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    public synchronized int callCount() {
        return requests.size();
    }
}
