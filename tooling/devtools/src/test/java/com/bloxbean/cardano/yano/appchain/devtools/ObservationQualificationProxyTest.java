package com.bloxbean.cardano.yano.appchain.devtools;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ObservationQualificationProxyTest {
    @Test
    void partitionClosesBothDirectionsAndHealRestoresForwarding() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            ServerSocket echo = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            var workers = Executors.newVirtualThreadPerTaskExecutor();
            Set<Socket> accepted = ConcurrentHashMap.newKeySet();
            workers.submit(() -> {
                while (!echo.isClosed()) {
                    try {
                        Socket socket = echo.accept();
                        accepted.add(socket);
                        workers.submit(() -> {
                            try (socket) { socket.getInputStream().transferTo(socket.getOutputStream()); }
                            catch (IOException ignored) { }
                            finally { accepted.remove(socket); }
                        });
                    } catch (IOException stopped) { break; }
                }
            });
            try (var proxy = new ObservationQualificationProxy(2, List.of(
                    new ObservationQualificationProxy.Route(0, 1, 0, echo.getLocalPort()),
                    new ObservationQualificationProxy.Route(1, 0, 0, echo.getLocalPort())))) {
                try (Socket forward = connect(proxy.boundPort(0)); Socket reverse = connect(proxy.boundPort(1))) {
                    roundTrip(forward);
                    roundTrip(reverse);
                    proxy.partition(Set.of(1));
                    assertClosed(forward);
                    assertClosed(reverse);
                }
                try (Socket blocked = connect(proxy.boundPort(0))) { assertClosed(blocked); }
                proxy.partition(Set.of());
                try (Socket healed = connect(proxy.boundPort(0))) { roundTrip(healed); }
                assertThat((long) proxy.status().get("forwardedBytes")).isGreaterThanOrEqualTo(4);
                assertThat(proxy.status().get("isolated")).isEqualTo(List.of());
                assertThatThrownBy(() -> proxy.partition(Set.of(2))).isInstanceOf(IllegalArgumentException.class);
            } finally {
                echo.close();
                for (Socket socket : accepted) socket.close();
                workers.shutdownNow();
                assertThat(workers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            }
        });
    }

    @Test
    void rejectsSelfLinksAndInvalidTopology() {
        assertThatThrownBy(() -> new ObservationQualificationProxy(2, List.of(
                new ObservationQualificationProxy.Route(0, 0, 0, 12345))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ObservationQualificationProxy(6, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout(2000);
        return socket;
    }

    private static void roundTrip(Socket socket) throws IOException {
        socket.getOutputStream().write(42);
        assertThat(socket.getInputStream().read()).isEqualTo(42);
    }

    private static void assertClosed(Socket socket) throws IOException {
        try { assertThat(socket.getInputStream().read()).isEqualTo(-1); }
        catch (SocketException reset) { /* A TCP reset is also a closed link. */ }
    }
}
