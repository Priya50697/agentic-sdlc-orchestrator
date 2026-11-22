package com.sdlcagent.orchestrator.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Record/replay decorator. key = sha256(model + system + user). One JSON file per entry. */
public class CachingLlmClient implements LlmClient {
    public enum Mode { RECORD, REPLAY, OFF }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmClient delegate;
    private final Path dir;
    private final Mode mode;
    private final String defaultModel;

    public CachingLlmClient(LlmClient delegate, Path dir, Mode mode, String defaultModel) {
        this.delegate = delegate;
        this.dir = dir;
        this.mode = mode;
        this.defaultModel = defaultModel == null ? "" : defaultModel;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (mode == Mode.OFF) return delegate.complete(request);

        String key = key(request);
        Path file = dir.resolve(key + ".json");
        if (Files.exists(file)) {
            try {
                return MAPPER.readValue(file.toFile(), LlmResponse.class).asCacheHit();
            } catch (IOException e) {
                throw new UncheckedIOException("corrupt cache entry " + file, e);
            }
        }
        if (mode == Mode.REPLAY) {
            throw new LlmException(LlmException.Kind.CACHE_MISS, "no recorded response for key " + key);
        }
        LlmResponse response = delegate.complete(request);
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, key, ".tmp");
            MAPPER.writeValue(tmp.toFile(), response);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write cache entry " + file, e);
        }
        return response;
    }

    String key(LlmRequest r) {
        String model = r.model() != null ? r.model() : defaultModel;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(model.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(r.system().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(r.user().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
