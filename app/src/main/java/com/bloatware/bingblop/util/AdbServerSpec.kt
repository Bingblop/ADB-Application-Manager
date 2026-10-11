package com.bloatware.bingblop.util

/**
 * Where the bundled adb server listens, as arguments that precede every adb command.
 *
 * Hardens against unauthorized local loopback access (C-001) by listening on a private
 * Unix domain socket inside the application's private files directory (`adb_sock/s`),
 * which only this application's UID can access.
 * Falls back to legacy port 5042 only if the socket path is invalid or unavailable.
 */
object AdbServerSpec {
    const val LEGACY_PORT = 5042
    const val MAX_SOCKET_PATH = 100
    const val SOCKET_DIR = "adb_sock"
    const val SOCKET_NAME = "s"

    fun socketPath(filesDirPath: String): String {
        return "$filesDirPath/$SOCKET_DIR/$SOCKET_NAME"
    }

    fun pathFits(path: String?): Boolean {
        return !path.isNullOrEmpty() && path.length <= MAX_SOCKET_PATH && !path.contains('\u0000')
    }

    fun args(socketPath: String?): List<String> {
        return if (pathFits(socketPath)) {
            listOf("-L", "localfilesystem:$socketPath")
        } else {
            listOf("-P", LEGACY_PORT.toString())
        }
    }

    fun isPrivate(args: List<String>?): Boolean {
        return args != null && args.size == 2 && args[0] == "-L" && args[1].startsWith("localfilesystem:")
    }
}
