package org.yanoproject.x.appchain.cli;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/** Whether a local port is free, so a clash is reported before a node is launched. */
final class Ports {
    private Ports() {
    }

    static boolean free(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port));
            return true;
        } catch (IOException busy) {
            return false;
        }
    }

    /** The first free port at or after {@code from}, skipping {@code avoid}. */
    static int firstFree(int from, java.util.Set<Integer> avoid) {
        for (int port = from; port < from + 1000; port++) {
            if (!avoid.contains(port) && free(port)) return port;
        }
        throw CliException.failure("No free port found from " + from);
    }
}
