package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the bundled adb server listens, as the arguments that go in front of every adb command.
 *
 * The server used to listen on a fixed loopback port (5042), which any app on the phone with the INTERNET permission can connect to
 * and speak the adb protocol to, on the connection this app has already authorised. Now it listens on a unix socket inside the app's
 * private folder, which only this app can open. Where the phone will not let the app make such a socket, the old port stays as the
 * fallback so adb keeps working.
 */
final class AdbServerSpec {
    /** The old loopback port: the fallback, and the one an older version's server may still hold. */
    static final int LEGACY_PORT = 5042;
    /** sockaddr_un.sun_path holds 108 bytes; keep clear of the limit. */
    static final int MAX_SOCKET_PATH = 100;
    static final String SOCKET_DIR = "adb_sock";
    static final String SOCKET_NAME = "s";

    private AdbServerSpec() {}

    static String socketPath(String filesDirPath) {
        return filesDirPath + "/" + SOCKET_DIR + "/" + SOCKET_NAME;
    }

    static boolean pathFits(String path) {
        return path != null && !path.isEmpty() && path.length() <= MAX_SOCKET_PATH && path.indexOf('\0') < 0;
    }

    /** {@code -L localfilesystem:<path>} for a usable socket path, else {@code -P 5042}. */
    static List<String> args(String socketPath) {
        List<String> a = new ArrayList<String>();
        if (pathFits(socketPath)) {
            a.add("-L");
            a.add("localfilesystem:" + socketPath);
        } else {
            a.add("-P");
            a.add(String.valueOf(LEGACY_PORT));
        }
        return a;
    }

    static boolean isPrivate(List<String> args) {
        return args != null && args.size() == 2 && "-L".equals(args.get(0)) && args.get(1).startsWith("localfilesystem:");
    }
}
