package com.sdlcagent.orchestrator.agents.livecheck;

/** Boots the generated app and hands back its base URL. A seam so dry-run tests never spawn a JVM. */
public interface LiveAppRunner {
    interface Running extends AutoCloseable {
        String baseUrl();

        @Override
        void close();
    }

    /** @throws Exception with a human-readable reason when the app cannot be packaged or does not come up */
    Running start() throws Exception;
}
