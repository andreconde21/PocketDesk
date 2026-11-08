package dev.outsmartis.carmirror

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Starts the privileged helper without Shizuku (CAR-002): the app is its own ADB client.
 *
 * One-time setup, all on the phone: Developer options -> Wireless debugging -> "Pair device with
 * pairing code"; the app finds the pairing port by mDNS and the user types the 6-digit code. Once
 * paired, the app grants itself WRITE_SECURE_SETTINGS through that connection. From then on, at
 * boot (on Wi-Fi) it switches wireless debugging on, connects to its own adbd, starts SelfHost
 * with shell rights, and switches wireless debugging off again.
 */
object SelfAdb {
    private const val TAG = "PocketDeskAdb"
    private const val ADB_WIFI = "adb_wifi_enabled"

    private class Manager(dir: File) : AbsAdbConnectionManager() {
        private val key: PrivateKey
        private val cert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(10, TimeUnit.SECONDS)
            val keyFile = File(dir, "adb.key")
            val certFile = File(dir, "adb.crt")
            if (keyFile.exists() && certFile.exists()) {
                key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            } else {
                val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val name = X500Name("CN=PocketDesk")
                val now = System.currentTimeMillis()
                val holder = JcaX509v3CertificateBuilder(
                    name, BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 3650L * 86_400_000L), name, kp.public,
                ).build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
                key = kp.private
                cert = JcaX509CertificateConverter().getCertificate(holder)
                keyFile.writeBytes(key.encoded)
                certFile.writeBytes(cert.encoded)
            }
        }

        override fun getPrivateKey() = key
        override fun getCertificate() = cert
        override fun getDeviceName() = "PocketDesk"
    }

    @Volatile private var manager: Manager? = null
    private fun manager(ctx: Context): Manager =
        manager ?: synchronized(this) { manager ?: Manager(File(ctx.filesDir, "adb").apply { mkdirs() }).also { manager = it } }

    fun prefs(ctx: Context) = ctx.getSharedPreferences("selfadb", Context.MODE_PRIVATE)
    fun isPaired(ctx: Context) = prefs(ctx).getBoolean("paired", false)

    fun canToggleWirelessDebugging(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun wirelessDebuggingOn(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, ADB_WIFI, 0) == 1

    /** The pairing port shown in "Pair device with pairing code", found by mDNS (that dialog must be open). */
    fun findPairingPort(ctx: Context, timeoutMs: Long = 15_000): Int {
        val port = AtomicInteger(-1)
        val found = CountDownLatch(1)
        val mdns = AdbMdns(ctx, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, p ->
            if (host != null && p > 0) { port.set(p); found.countDown() }
        }
        mdns.start()
        try { found.await(timeoutMs, TimeUnit.MILLISECONDS) } finally { mdns.stop() }
        return port.get()
    }

    /** Blocking. Pairs with this phone's adbd, then grants ourselves WRITE_SECURE_SETTINGS. */
    fun pair(ctx: Context, port: Int, code: String) {
        val m = manager(ctx)
        check(m.pair("127.0.0.1", port, code.trim())) { "pairing was refused" }
        prefs(ctx).edit().putBoolean("paired", true).apply()
        Log.i(TAG, "paired")
        runCatching { connect(ctx, 20_000) }.onSuccess {
            shell(ctx, "pm grant ${ctx.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}")
        }.onFailure { Log.w(TAG, "connect after pairing: $it") }
    }

    private fun connect(ctx: Context, timeoutMs: Long) {
        val m = manager(ctx)
        if (m.isConnected) return
        check(m.connectTls(ctx, timeoutMs)) { "adbd refused the connection" }
    }

    /** Runs a shell command over our ADB connection and returns its output. */
    fun shell(ctx: Context, command: String): String {
        val stream = manager(ctx).openStream("shell:$command")
        val buf = java.io.ByteArrayOutputStream()
        val input = stream.openInputStream()
        val chunk = ByteArray(4096)
        // libadb signals the end of a shell stream with IOException("Stream closed."), not -1
        try {
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
            }
        } catch (e: java.io.IOException) {
            if (stream.isClosed.not()) throw e
        }
        val out = buf.toString(Charsets.UTF_8.name())
        runCatching { stream.close() }
        Log.i(TAG, "shell: $command -> ${out.trim().take(200)}")
        return out
    }

    /**
     * Blocking. Starts SelfHost with shell rights. Turns wireless debugging on for the moment it
     * needs it (if we hold WRITE_SECURE_SETTINGS) and back off afterwards if it was off before.
     */
    fun startHelper(ctx: Context, wasOn: Boolean = wirelessDebuggingOn(ctx)): Result<Unit> = runCatching {
        check(isPaired(ctx)) { "not paired yet" }
        if (!wirelessDebuggingOn(ctx)) {
            check(canToggleWirelessDebugging(ctx)) { "wireless debugging is off and we can't switch it on" }
            Settings.Global.putInt(ctx.contentResolver, ADB_WIFI, 1)
            Thread.sleep(1500) // adbd needs a moment to open its TLS port and announce it
        }
        try {
            connect(ctx, 30_000)
            val apk = ctx.applicationInfo.sourceDir
            val authority = SelfHostProvider.authority(ctx)
            // setsid + nohup: the helper outlives this adb connection and wireless debugging.
            // Stay connected until it has started and handed us its binder: dropping the
            // connection (and wireless debugging) too early kills it before it runs.
            val out = shell(ctx, "CLASSPATH=$apk setsid nohup app_process /system/bin ${SelfHost::class.java.name} $authority " +
                ">/data/local/tmp/pocketdesk-host.log 2>&1 </dev/null & echo started:\$!; sleep 1")
            check(out.contains("started:")) { "adbd didn't run the start command" }
            val deadline = System.currentTimeMillis() + 10_000
            while (ShizukuBridge.service == null && System.currentTimeMillis() < deadline) Thread.sleep(250)
            check(ShizukuBridge.service != null) {
                "helper started but didn't connect: " + runCatching { shell(ctx, "cat /data/local/tmp/pocketdesk-host.log") }.getOrDefault("")
            }
        } finally {
            runCatching { manager(ctx).disconnect() }
            if (!wasOn && prefs(ctx).getBoolean("wifiDebugOffAfter", true)) {
                Settings.Global.putInt(ctx.contentResolver, ADB_WIFI, 0)
            }
        }
    }
}
