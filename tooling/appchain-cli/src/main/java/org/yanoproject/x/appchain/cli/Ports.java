package org.yanoproject.x.appchain.cli;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** Whether a local port is free, so a clash is reported before a node is launched. */
final class Ports {
    private Ports() {
    }

    /**
     * True only when nothing listens on the port: a bind on every address and on loopback succeeds, and nothing
     * answers a connection on loopback. The binds reuse the address as the node itself does, so a port its own
     * stopped node left in TIME_WAIT counts as free; a wildcard bind alone succeeds on some systems while another
     * process listens on 127.0.0.1, which the loopback bind and connection catch.
     */
    static boolean free(int port) {
        for (String address : new String[]{"0.0.0.0", "127.0.0.1"}) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(InetAddress.getByName(address), port));
            } catch (IOException busy) {
                return false;
            }
        }
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 200);
            return false; // something answered
        } catch (IOException nobody) {
            return true;
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
