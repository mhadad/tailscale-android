// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.nena

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "NenaAdbBridge"
private const val MDNSD_TAG = "Nenamdnsd"

/**
 * Shells out to an on-device `adb` client binary (bundled under jniLibs as
 * `libnenaadb.so`, built from AOSP source targeting Android/Bionic instead
 * of a desktop host - see the Nena project's private AOSP build notes) to
 * pair/connect/pull logs from a watch reachable on this phone's local
 * network (e.g. its own Wi-Fi hotspot).
 *
 * Mirrors the desktop Nena app's `DesktopAdbClient` shape - same one-shot
 * `ProcessBuilder` pattern, just pointed at a bundled Android-target binary
 * instead of the host `adb` on PATH.
 */
class AdbBridge(private val context: Context) {

    private val binaryPath: String
        get() = File(context.applicationInfo.nativeLibraryDir, "libnenaadb.so").absolutePath

    private val mdnsdBinaryPath: String
        get() = File(context.applicationInfo.nativeLibraryDir, "libnenamdnsd.so").absolutePath

    /** Where adb stores its client identity key (`adbkey`/`adbkey.pub`) - must be app-writable. */
    private val adbHomeDir: File
        get() = File(context.filesDir, "adb-home").apply { mkdirs() }

    private var mdnsdProcess: Process? = null
    private var adbServerProcess: Process? = null

    /**
     * Starts our own mDNS responder daemon, built from the same AOSP source as adb's own
     * client library (see the private build notes). Stock Android doesn't run a real
     * Bonjour/mDNSResponder daemon adb's mDNS discovery can talk to - the socket it looks
     * for (patched at compile time to a path under this app's own private data dir, since
     * the real Android default `/dev/socket/mdnsd` isn't writable by an unprivileged app)
     * has no daemon behind it otherwise. Unlike a normal Unix daemon, this binary was
     * built with `__ANDROID__` defined, which skips its usual self-detaching `daemon()`
     * call - it runs in the foreground, so this Process handle stays valid for as long as
     * the daemon lives, and `stopMdnsDaemon()` can actually stop it.
     */
    /**
     * Finds this phone's own IPv4 address + netmask on whichever interface currently carries
     * real traffic (Wi-Fi, hotspot uplink, etc.) via [NetworkInterface] - a normal Java API
     * apps are allowed to use. mdnsd itself can't do this: its own raw ioctl(SIOCGIFCONF)
     * enumeration is blocked by Android's SELinux policy for unprivileged app processes, so
     * left to its own auto-discovery it only ever sees the loopback interface and never joins
     * the mDNS multicast group anywhere it could actually hear a nearby watch (confirmed via
     * /proc/net/igmp: no membership from our process on the real Wi-Fi interface at all).
     */
    private data class OwnInterface(val ipAddress: String, val netmask: String, val ifaceName: String)

    private fun findOwnIpv4WithNetmask(): OwnInterface? {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
            // Skip point-to-point interfaces (VPN tunnels like Tailscale's own "tun0" report
            // isPointToPoint()==true, matching the same IFF_POINTOPOINT exclusion mdnsd's own
            // interface enumeration uses on the native side) - confirmed empirically: this
            // matched our own paused-but-not-yet-torn-down tun0 instead of the real Wi-Fi
            // interface, one race we don't need to rely on the native side to route around.
            if (!iface.isUp || iface.isLoopback || iface.isPointToPoint) return@forEach
            iface.interfaceAddresses.forEach { addr ->
                val ip = addr.address
                if (ip is java.net.Inet4Address && !ip.isLoopbackAddress) {
                    val prefixLen = addr.networkPrefixLength.toInt()
                    val mask = if (prefixLen in 0..32) {
                        val bits = if (prefixLen == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLen)) and 0xFFFFFFFFL
                        InetAddress.getByAddress(
                            byteArrayOf(
                                (bits shr 24 and 0xFF).toByte(),
                                (bits shr 16 and 0xFF).toByte(),
                                (bits shr 8 and 0xFF).toByte(),
                                (bits and 0xFF).toByte(),
                            )
                        ).hostAddress
                    } else {
                        "255.255.255.0"
                    }
                    val ipAddress = ip.hostAddress ?: return@forEach
                    return OwnInterface(ipAddress, mask ?: "255.255.255.0", iface.name)
                }
            }
        }
        return null
    }

    /**
     * Android's per-UID network routing enforcement rejects an app process's attempt to send
     * a multicast packet out an interface other than the OS's chosen "system default network"
     * (confirmed empirically: EPERM from mdnsd's sendto(), even after it correctly registered
     * the right interface). android_setsocknetwork() - the NDK-public per-socket fix, called
     * from mdnsd's own native code - needs the target Network's opaque handle, which only the
     * JVM side can look up (there's no native equivalent available to mdnsd itself). We find
     * the Network whose LinkProperties reports the same interface name [findOwnIpv4WithNetmask]
     * already identified, so mdnsd binds its multicast socket to the network that interface
     * actually belongs to, not whatever the OS happens to default this UID to.
     */
    private fun findNetworkHandle(ifaceName: String): Long? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return cm.allNetworks.firstOrNull { network ->
            cm.getLinkProperties(network)?.interfaceName == ifaceName
        }?.networkHandle
    }

    /**
     * Starts `adb`'s own server ("server nodaemon" - runs in the foreground instead of
     * self-detaching, so this Process handle stays valid, same pattern as [startMdnsDaemon])
     * as a long-lived background process for the whole watch-logs session.
     *
     * This matters because mDNS discovery isn't instantaneous - it takes at least one real
     * query/response round trip over the network (typically hundreds of ms) before any
     * service records are known. Every other `adb` subcommand ([runAdb]) is a one-shot,
     * near-instant `ProcessBuilder` invocation (confirmed empirically: ~15-20ms end to end)
     * that just asks whatever server is already running for its current results - if no
     * server is running yet, adb auto-spawns one and asks its *brand new, empty* cache
     * immediately, before it could possibly have received anything, then exits (confirmed:
     * `ps -A` showed no persistent adb-server process at all between calls, since our
     * sandboxed exec model doesn't let a self-detached grandchild outlive its short-lived
     * invoking process the way it would in a normal desktop shell). Starting the server
     * explicitly and keeping it alive for the session gives it real time to accumulate
     * discovery state before anything asks it for results.
     *
     * Also forces `ADB_MDNS_OPENSCREEN=0`: adb's default discovery backend is actually its
     * newer openscreen-based mDNS stack (not the classic Bonjour/mdnsd client we built and
     * fixed) - it only falls back to Bonjour if openscreen hits what it considers a *fatal*
     * error (e.g. port 5353 already bound). Confirmed empirically: openscreen's own interface
     * enumeration (`network_interface_linux.cc`) hits the exact same netlink EPERM restriction
     * as everything else in this sandbox, but treats it as non-fatal - so it silently proceeds
     * with zero interfaces, forever finding nothing, and never triggers the Bonjour fallback.
     * This env var skips straight to the Bonjour path adb already supports, talking to our
     * own patched, actually-working `mdnsd` instead.
     */
    fun startAdbServer() {
        if (adbServerProcess?.isAlive == true) return
        Log.d(TAG, "starting adb server: $binaryPath (forcing classic bonjour mDNS backend)")
        adbServerProcess = try {
            ProcessBuilder(listOf(binaryPath, "server", "nodaemon"))
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] = adbHomeDir.absolutePath
                    environment()["ADB_MDNS_OPENSCREEN"] = "0"
                }
                .start()
        } catch (e: IOException) {
            Log.e(TAG, "failed to start adb server", e)
            null
        }
        adbServerProcess?.let { process ->
            Thread {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        Log.d(TAG, "adb server: $line")
                    }
                }
                Log.d(TAG, "adb server output stream closed (process exited=${!process.isAlive})")
            }.apply { isDaemon = true; start() }
        }
    }

    fun stopAdbServer() {
        adbServerProcess?.let {
            Log.d(TAG, "stopping adb server")
            it.destroy()
        }
        adbServerProcess = null
    }

    fun startMdnsDaemon() {
        if (mdnsdProcess?.isAlive == true) return
        Log.d(TAG, "starting mdnsd daemon: $mdnsdBinaryPath")
        val forcedIface = findOwnIpv4WithNetmask()
        val networkHandle = forcedIface?.let { findNetworkHandle(it.ifaceName) }
        Log.d(TAG, "own ipv4/netmask for forced mDNS interface: $forcedIface, networkHandle=$networkHandle")
        mdnsdProcess = try {
            // -debug forces its LogMsg() calls through stderr instead of syslog() (which we
            // have no visibility into on Android) - needed to actually see startup failures.
            ProcessBuilder(listOf(mdnsdBinaryPath, "-debug"))
                .redirectErrorStream(true)
                .apply {
                    if (forcedIface != null) {
                        environment()["NENA_FORCE_IF_ADDR"] = "${forcedIface.ipAddress}/${forcedIface.netmask}"
                    }
                    if (networkHandle != null) {
                        environment()["NENA_NETWORK_HANDLE"] = networkHandle.toString()
                    }
                }
                .start()
        } catch (e: IOException) {
            Log.e(TAG, "failed to start mdnsd", e)
            null
        }
        // Drain and log the daemon's own output continuously - otherwise it's invisible
        // (and an unread pipe can eventually block the process once its buffer fills).
        mdnsdProcess?.let { process ->
            Thread {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        Log.d(MDNSD_TAG, line)
                    }
                }
                Log.d(MDNSD_TAG, "output stream closed (process exited=${!process.isAlive})")
            }.apply { isDaemon = true; start() }
        }
    }

    fun stopMdnsDaemon() {
        mdnsdProcess?.let {
            Log.d(TAG, "stopping mdnsd daemon")
            it.destroy()
        }
        mdnsdProcess = null
    }

    sealed class PairResult {
        data class Success(val output: String) : PairResult()
        data class Failure(val message: String) : PairResult()
    }

    sealed class ConnectResult {
        data class Success(val deviceAddress: String) : ConnectResult()
        data class Failure(val message: String) : ConnectResult()
    }

    enum class ServiceKind { PAIRING, CONNECT, OTHER }

    data class DiscoveredService(
        val name: String,
        val kind: ServiceKind,
        val host: String,
        val port: Int,
    )

    /**
     * Lists watches currently broadcasting wireless-debugging mDNS services on the local
     * network (the same mechanism `adb pair`'s own interactive auto-discovery and Android
     * Studio's "Pair over Wi-Fi" use) - [ServiceKind.PAIRING] while the watch's "Pair new
     * device" screen is open, [ServiceKind.CONNECT] whenever wireless debugging is on.
     *
     * The underlying adb server's mDNS listener runs continuously once started, so a scan
     * run immediately after the very first command may come back empty - call again after
     * a couple of seconds if so.
     */
    suspend fun discoverServices(): List<DiscoveredService> = withContext(Dispatchers.IO) {
        val result = runAdb(listOf("mdns", "services"))
        Log.d(TAG, "mdns services: exitCode=${result.exitCode} stdout=[${result.stdout}] stderr=[${result.stderr}]")
        result.stdout.lineSequence()
            .drop(1) // header: "List of discovered mdns services"
            .mapNotNull { line -> parseServiceLine(line) }
            .toList()
    }

    private fun parseServiceLine(line: String): DiscoveredService? {
        val parts = line.split("\t")
        if (parts.size != 3) return null
        val (name, regType, hostPort) = parts
        val separatorIndex = hostPort.lastIndexOf(':')
        if (separatorIndex == -1) return null
        val host = hostPort.substring(0, separatorIndex)
        val port = hostPort.substring(separatorIndex + 1).toIntOrNull() ?: return null
        val kind = when {
            regType.contains("adb-tls-pairing") -> ServiceKind.PAIRING
            regType.contains("adb-tls-connect") -> ServiceKind.CONNECT
            else -> ServiceKind.OTHER
        }
        return DiscoveredService(name, kind, host, port)
    }

    suspend fun pair(host: String, port: Int, pairingCode: String): PairResult = withContext(Dispatchers.IO) {
        val result = runAdb(listOf("pair", "$host:$port", pairingCode))
        val output = result.stdout.ifBlank { result.stderr }
        if (result.exitCode == 0 && output.contains("Successfully paired", ignoreCase = true)) {
            PairResult.Success(output.trim())
        } else {
            PairResult.Failure(output.trim().ifEmpty { "Pairing failed - check the code and try again" })
        }
    }

    suspend fun connect(host: String, port: Int): ConnectResult = withContext(Dispatchers.IO) {
        val deviceAddress = "$host:$port"
        val result = runAdb(listOf("connect", deviceAddress))
        val output = result.stdout.ifBlank { result.stderr }
        if (result.exitCode == 0 && output.contains("connected to", ignoreCase = true)) {
            ConnectResult.Success(deviceAddress)
        } else {
            ConnectResult.Failure(output.trim().ifEmpty { "Could not connect to $deviceAddress" })
        }
    }

    /** Pulls a full bugreport (system + app logs) from the already-connected [deviceAddress]. */
    suspend fun pullBugreport(deviceAddress: String): String = withContext(Dispatchers.IO) {
        runAdb(listOf("-s", deviceAddress, "bugreport", "-")).stdout
    }

    /** Pulls the current logcat buffer from the already-connected [deviceAddress]. */
    suspend fun pullLogcat(deviceAddress: String): String = withContext(Dispatchers.IO) {
        runAdb(listOf("-s", deviceAddress, "logcat", "-d")).stdout
    }

    suspend fun deviceModel(deviceAddress: String): String = withContext(Dispatchers.IO) {
        runAdb(listOf("-s", deviceAddress, "shell", "getprop", "ro.product.model")).stdout.trim()
    }

    suspend fun androidVersion(deviceAddress: String): String = withContext(Dispatchers.IO) {
        runAdb(listOf("-s", deviceAddress, "shell", "getprop", "ro.build.version.release")).stdout.trim()
    }

    private data class ProcessOutput(val exitCode: Int, val stdout: String, val stderr: String)

    // Note: ConnectivityManager.bindProcessToNetwork() was tried here to route around our
    // embedded Tailscale VPN capturing this app's own traffic, but it only affects sockets
    // created directly by this JVM process - it does not propagate to a natively `exec`'d
    // child process (confirmed empirically). The actual fix lives one layer up: the
    // caller (WatchLogsViewModel) pauses the VPN entirely for the duration of local watch
    // operations, since that's the only mechanism that affects our adb/mdnsd subprocesses.

    private fun runAdb(args: List<String>): ProcessOutput {
        val command = listOf(binaryPath) + args
        Log.d(TAG, "exec: $command (binary exists=${File(binaryPath).exists()}, canExecute=${File(binaryPath).canExecute()}) HOME=${adbHomeDir.absolutePath}")
        val process = try {
            ProcessBuilder(command)
                .apply {
                    environment()["HOME"] = adbHomeDir.absolutePath
                    // Consistent with startAdbServer() - matters if this one-shot call ends up
                    // auto-spawning its own server (e.g. if ours hasn't come up yet).
                    environment()["ADB_MDNS_OPENSCREEN"] = "0"
                }
                .start()
        } catch (e: IOException) {
            Log.e(TAG, "exec failed to start: $command", e)
            return ProcessOutput(-1, "", "adb binary not found or not executable: ${e.message}")
        }
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        Log.d(TAG, "exec result: $command -> exitCode=$exitCode stdout=[$stdout] stderr=[$stderr]")
        return ProcessOutput(exitCode, stdout, stderr)
    }
}
