package com.sdlcagent.urlshortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sdlcagent.urlshortener.provider.HttpExternalCheckProvider;
import com.sdlcagent.urlshortener.service.ExternalCheckUnavailableException;
import com.sdlcagent.urlshortener.util.ExternalCheckProperties;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ExternalCheckProviderTest {

    @Autowired
    ExternalCheckProperties configured;

    @Test
    @DisplayName("AC-6 configured timeout is 3000 ms with 2 retries and 200 ms backoff")
    void ac6_configuredTimeoutAndRetries() {
        assertThat(configured.getTimeoutMs()).isEqualTo(3000);
        assertThat(configured.getMaxRetries()).isEqualTo(2);
        assertThat(configured.getBackoffMs()).isEqualTo(200);
    }

    @Test
    @DisplayName("AC-6 a call that never answers times out and is attempted 3 times, then fails")
    void ac6_timeoutIsRetriedTwiceThenFails() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        List<Socket> held = new CopyOnWriteArrayList<>();
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try {
                    while (!server.isClosed()) {
                        Socket s = server.accept();
                        accepted.incrementAndGet();
                        held.add(s);
                    }
                } catch (Exception ignored) {
                    // server closed
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            ExternalCheckProperties props = new ExternalCheckProperties();
            props.setTimeoutMs(300);
            props.setMaxRetries(2);
            props.setBackoffMs(10);
            HttpExternalCheckProvider provider = new HttpExternalCheckProvider(props);

            URI uri = URI.create("http://localhost:" + server.getLocalPort() + "/");
            assertThatThrownBy(() -> provider.check(uri, List.of(InetAddress.getLoopbackAddress())))
                    .isInstanceOf(ExternalCheckUnavailableException.class);

            assertThat(accepted.get()).isEqualTo(3);
        } finally {
            for (Socket s : held) {
                try {
                    s.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }
}
