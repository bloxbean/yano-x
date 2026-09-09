package com.bloxbean.cardano.yano.appchain.devtools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Loopback-only, bounded directed TCP links for app-peer partition tests. Never proxies L1 traffic. */
public final class ObservationQualificationProxy implements AutoCloseable {
    private static final int MAX_CONNECTIONS = 80;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore capacity = new Semaphore(MAX_CONNECTIONS);
    private final Set<Link> links = ConcurrentHashMap.newKeySet();
    private final List<ServerSocket> listeners = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final int nodes;
    private volatile Set<Integer> isolated = Set.of();

    record Route(int source, int target, int listenPort, int destinationPort) { }

    ObservationQualificationProxy(int nodes, List<Route> routes) throws IOException {
        if (nodes < 2 || nodes > 5 || routes.size() > 20) throw new IllegalArgumentException("Invalid topology");
        this.nodes = nodes;
        try {
            for (Route route : routes) {
                if (route.source() < 0 || route.source() >= nodes || route.target() < 0 || route.target() >= nodes
                        || route.source() == route.target() || route.destinationPort() < 1024
                        || route.destinationPort() > 65535) throw new IllegalArgumentException("Invalid directed link");
                ServerSocket listener = new ServerSocket();
                listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), route.listenPort()), 16);
                listeners.add(listener);
                workers.submit(() -> accept(listener, route));
            }
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    int boundPort(int index) { return listeners.get(index).getLocalPort(); }

    void partition(Set<Integer> nodesToIsolate) {
        if (nodesToIsolate.stream().anyMatch(node -> node < 0 || node >= nodes)) {
            throw new IllegalArgumentException("Invalid isolated node");
        }
        isolated = Set.copyOf(nodesToIsolate);
        links.stream().filter(link -> blocked(link.route)).forEach(Link::close);
    }

    Map<String, Object> status() {
        return Map.of("isolated", isolated.stream().sorted().toList(), "activeLinks", links.size(),
                "maxConnections", MAX_CONNECTIONS, "forwardedBytes", bytes.get(), "rejectedConnections", rejected.get());
    }

    private boolean blocked(Route route) { return isolated.contains(route.source()) || isolated.contains(route.target()); }

    private void accept(ServerSocket listener, Route route) {
        while (!closed.get()) {
            try {
                Socket incoming = listener.accept();
                if (blocked(route) || !capacity.tryAcquire()) {
                    rejected.incrementAndGet();
                    incoming.close();
                    continue;
                }
                try {
                    workers.submit(() -> connect(incoming, route));
                } catch (RejectedExecutionException stopping) {
                    incoming.close();
                    capacity.release();
                }
            } catch (IOException failure) {
                if (!closed.get()) rejected.incrementAndGet();
            }
        }
    }

    private void connect(Socket incoming, Route route) {
        Socket outgoing = new Socket();
        Link link = new Link(route, incoming, outgoing);
        links.add(link);
        try {
            if (closed.get() || blocked(route)) return;
            outgoing.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), route.destinationPort()), 2000);
            if (closed.get() || blocked(route)) return;
            workers.submit(() -> copy(link, outgoing, incoming));
            copy(link, incoming, outgoing);
        } catch (IOException failure) {
            rejected.incrementAndGet();
        } finally {
            link.close();
        }
    }

    private void copy(Link link, Socket source, Socket destination) {
        byte[] buffer = new byte[8192];
        try {
            var input = source.getInputStream();
            var output = destination.getOutputStream();
            for (int count; !closed.get() && !blocked(link.route) && (count = input.read(buffer)) >= 0;) {
                if (blocked(link.route)) break;
                output.write(buffer, 0, count);
                bytes.addAndGet(count);
            }
        } catch (IOException disconnected) {
            // Partition/reconnect is expected. No payload or credentials are logged.
        } finally {
            link.close();
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (ServerSocket listener : listeners) {
            try { listener.close(); } catch (IOException ignored) { }
        }
        links.forEach(Link::close);
        workers.shutdownNow();
    }

    private final class Link {
        private final Route route;
        private final Socket incoming;
        private final Socket outgoing;
        private final AtomicBoolean ended = new AtomicBoolean();

        private Link(Route route, Socket incoming, Socket outgoing) {
            this.route = route;
            this.incoming = incoming;
            this.outgoing = outgoing;
        }

        private void close() {
            if (!ended.compareAndSet(false, true)) return;
            try { incoming.close(); } catch (IOException ignored) { }
            try { outgoing.close(); } catch (IOException ignored) { }
            links.remove(this);
            capacity.release();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected proxy base port and five-node server base port");
        int base = Integer.parseInt(args[0]);
        int target = Integer.parseInt(args[1]);
        if (base < 1024 || base > 65511 || target < 1024 || target > 65531
                || !(base + 24 < target || target + 4 < base)) throw new IllegalArgumentException("Invalid port ranges");
        List<Route> routes = new ArrayList<>();
        for (int source = 0; source < 5; source++) for (int destination = 0; destination < 5; destination++) {
            if (source != destination) routes.add(new Route(source, destination, base + source * 5 + destination,
                    target + destination));
        }
        var json = new ObjectMapper();
        try (var proxy = new ObservationQualificationProxy(5, routes);
             var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII))) {
            System.out.println(json.writeValueAsString(Map.of("routes", routes, "status", proxy.status())));
            for (String line; (line = reader.readLine()) != null;) {
                if (line.length() > 64) throw new IllegalArgumentException("Control line too long");
                if (line.equals("quit")) break;
                if (line.equals("heal")) proxy.partition(Set.of());
                else if (line.startsWith("partition ")) {
                    String[] parts = line.substring(10).split(",", -1);
                    if (parts.length > 5) throw new IllegalArgumentException("Too many isolated nodes");
                    proxy.partition(IntStream.range(0, parts.length).mapToObj(i -> Integer.parseInt(parts[i]))
                            .collect(Collectors.toSet()));
                } else if (!line.equals("status")) throw new IllegalArgumentException("Unknown control command");
                System.out.println(json.writeValueAsString(proxy.status()));
            }
        }
    }
}
