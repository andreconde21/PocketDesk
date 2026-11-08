package dev.outsmartis.carmirror

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // adb shell am start -n dev.outsmartis.carmirror/.MainActivity --es server http://host:8080
        intent?.getStringExtra("server")?.let { prefs.serverUrl = it }
        if (Build.VERSION.SDK_INT >= 33) {
            // notifications for the service; nearby Wi-Fi devices = reaching the car on the hotspot
            val wanted = listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.NEARBY_WIFI_DEVICES)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
        }
        if (prefs.startOnLaunch && !AppState.serviceRunning.value) MirrorService.start(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Screen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuBridge.refresh()
        ShizukuBridge.bindIfPossible()
    }

    /** "ready", "reboot" (turned on, waiting for a phone restart) or "off". */
    private fun desktopState(): String {
        val r = contentResolver
        val on = Settings.Global.getInt(r, "enable_freeform_support", 0) == 1 &&
            Settings.Global.getInt(r, "force_desktop_mode_on_external_displays", 0) == 1
        if (!on) return "off"
        val setAt = getSharedPreferences("desk", MODE_PRIVATE).getLong("setAt", 0)
        val bootedAt = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        return if (setAt == 0L || bootedAt > setAt) "ready" else "reboot"
    }

    @Composable
    private fun Screen() {
        val running by AppState.serviceRunning.collectAsState()
        val server by AppState.server.collectAsState()
        val online by AppState.serverOnline.collectAsState()
        val code by AppState.pairCode.collectAsState()
        val paired by AppState.pairedCars.collectAsState()
        val screen by AppState.car.collectAsState()
        val lastError by AppState.lastError.collectAsState()
        val shizukuState by AppState.shizuku.collectAsState()
        var desk by remember { mutableStateOf(desktopState()) }
        var deskMsg by remember { mutableStateOf("") }
        var showSetup by remember { mutableStateOf(false) }
        var showPairing by remember { mutableStateOf(false) }
        var showAdvanced by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            while (true) {
                ShizukuBridge.refresh()
                desk = desktopState()
                delay(3000)
            }
        }

        val helperOn = shizukuState == ShizukuState.READY
        val setupDone = helperOn && desk == "ready"
        val host = prefs.serverUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')

        Scaffold { pad ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Spacer(Modifier.padding(top = 8.dp))
                    Text("PocketDesk", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Your phone as a desktop on any screen", style = MaterialTheme.typography.bodyMedium)
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val (headline, color) = when {
                                !running -> "PocketDesk is stopped" to MaterialTheme.colorScheme.error
                                screen != null -> screen!!.replace("car browser", "Screen").replace("Tesla", "Screen") to Ok
                                !setupDone -> "Finish the setup below" to MaterialTheme.colorScheme.error
                                !online -> "Connecting to the server…" to MaterialTheme.colorScheme.onSurface
                                paired == 0 -> "Pair a screen below" to MaterialTheme.colorScheme.onSurface
                                else -> "Ready: open $host on the screen" to Ok
                            }
                            Text(headline, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = color)
                            StatusLine(ok = helperOn, text = if (helperOn) "Desktop helper running" else "Desktop helper not running")
                            StatusLine(
                                ok = desk == "ready",
                                text = when (desk) {
                                    "ready" -> "Desktop windows on"
                                    "reboot" -> "Desktop windows: restart the phone once"
                                    else -> "Desktop windows off"
                                },
                            )
                            if (!running) Button(onClick = { MirrorService.start(this@MainActivity) }) { Text("Start") }
                        }
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Header(
                                title = if (setupDone) "✓ Setup done" else "Setup",
                                open = showSetup || !setupDone,
                                color = if (setupDone) Ok else MaterialTheme.colorScheme.onSurface,
                            ) { showSetup = !showSetup }
                            if (showSetup || !setupDone) {
                                Step(
                                    done = helperOn,
                                    title = "1. Desktop helper",
                                    body = "Creating a second screen needs a helper with adb rights. PocketDesk starts it itself: " +
                                        "pair once with Wireless debugging (no computer needed) and it starts again after every restart, " +
                                        "as soon as the phone is on Wi-Fi. Turn on Developer options first (tap Build number 7 times).",
                                ) {
                                    SelfStartBlock(shizukuState)
                                    if (ShizukuBridge.isShizukuInstalled() && shizukuState != ShizukuState.READY) {
                                        Text("Or use Shizuku: " + ShizukuBridge.describe(shizukuState), style = MaterialTheme.typography.bodySmall)
                                        when (shizukuState) {
                                            ShizukuState.NOT_RUNNING, ShizukuState.ERROR -> OutlinedButton(onClick = { openShizuku() }) { Text("Open Shizuku") }
                                            ShizukuState.NO_PERMISSION -> OutlinedButton(onClick = { ShizukuBridge.requestPermission() }) { Text("Allow Shizuku") }
                                            else -> {}
                                        }
                                    }
                                }
                                Step(
                                    done = desk == "ready",
                                    title = "2. Desktop windows",
                                    body = if (desk == "reboot") "Turned on. Restart the phone once so Android picks it up."
                                    else "Turns on Android's desktop mode for external screens: apps open in windows you can move and resize. " +
                                        "Needs the helper, then one phone restart.",
                                ) {
                                    if (desk == "off") {
                                        Button(enabled = helperOn, onClick = {
                                            deskMsg = "Turning on…"
                                            kotlin.concurrent.thread {
                                                val r = runCatching { ShizukuBridge.service?.prepareDesktop() ?: error("helper not running") }
                                                if (r.getOrNull() == "reboot") {
                                                    getSharedPreferences("desk", MODE_PRIVATE).edit().putLong("setAt", System.currentTimeMillis()).apply()
                                                }
                                                runOnUiThread {
                                                    desk = desktopState()
                                                    deskMsg = r.exceptionOrNull()?.let { "Failed: ${it.message}" } ?: ""
                                                }
                                            }
                                        }) { Text("Turn on") }
                                    }
                                    if (deskMsg.isNotEmpty()) Text(deskMsg, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val hasPair = paired > 0
                            Header(
                                title = if (hasPair) "✓ Screen paired" + if (paired > 1) " ($paired)" else "" else "3. Pair a screen",
                                open = showPairing || !hasPair,
                                color = if (hasPair) Ok else MaterialTheme.colorScheme.onSurface,
                            ) { showPairing = !showPairing }
                            if (showPairing || !hasPair) {
                                if (online && code != null) {
                                    Text("On the monitor, TV or laptop, open $host in Chrome or Edge and type:", style = MaterialTheme.typography.bodyMedium)
                                    Text(code!!.chunked(3).joinToString(" "), fontSize = 40.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { MirrorService.start(this@MainActivity, MirrorService.ACTION_NEW_CODE) }) { Text("New code") }
                                        if (hasPair) OutlinedButton(onClick = { MirrorService.start(this@MainActivity, MirrorService.ACTION_UNPAIR_ALL) }) { Text("Unpair all") }
                                    }
                                } else {
                                    Text(if (running) "Connecting to the server…" else "Start PocketDesk first.", style = MaterialTheme.typography.bodyMedium)
                                    Text("Server: $server", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                item {
                    Section("What the screen needs") {
                        Text(
                            "A browser on the screen's side: a laptop, a mini PC, a smart TV browser, or a Google TV / " +
                                "Chromecast with Google TV. Connect the keyboard and mouse to that device. Phone and screen " +
                                "on the same Wi-Fi gives the sharpest, quickest picture. A plain Miracast dongle won't work: it " +
                                "can only mirror the phone's own screen.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Header(title = "Advanced", open = showAdvanced) { showAdvanced = !showAdvanced }
                            if (showAdvanced) Advanced()
                        }
                    }
                }

                lastError?.let { err ->
                    item { Text("Last problem: $err", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                item { Spacer(Modifier.padding(bottom = 24.dp)) }
            }
        }
    }

    @Composable
    private fun Header(title: String, open: Boolean, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface, onToggle: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onToggle() },
        ) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = color, modifier = Modifier.weight(1f))
            Text(if (open) "▴" else "▾", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun StatusLine(ok: Boolean, text: String) {
        Text((if (ok) "✓ " else "• ") + text, color = if (ok) Ok else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }

    @Composable
    private fun Advanced() {
        var url by remember { mutableStateOf(prefs.serverUrl) }
        var autostart by remember { mutableStateOf(prefs.startOnLaunch) }
        val running by AppState.serviceRunning.collectAsState()
        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                prefs.serverUrl = url
                MirrorService.stop(this@MainActivity)
                window.decorView.postDelayed({ MirrorService.start(this@MainActivity) }, 800)
            }) { Text("Save & restart") }
            if (running) OutlinedButton(onClick = { MirrorService.stop(this@MainActivity) }) { Text("Stop") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Start when the app opens", modifier = Modifier.weight(1f))
            Switch(checked = autostart, onCheckedChange = {
                autostart = it
                prefs.startOnLaunch = it
            })
        }
        var offAfter by remember { mutableStateOf(SelfAdb.prefs(this@MainActivity).getBoolean("wifiDebugOffAfter", true)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Wireless debugging off after starting the helper")
                Text("The helper keeps running; nothing stays open on the network.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = offAfter, onCheckedChange = {
                offAfter = it
                SelfAdb.prefs(this@MainActivity).edit().putBoolean("wifiDebugOffAfter", it).apply()
            })
        }
        if (!ignoringBatteryOptimizations()) {
            Text("Battery: let PocketDesk run in the background so the helper can start after a restart.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { requestIgnoreBatteryOptimizations() }) { Text("Allow background") }
        }
    }

    /** CAR-002: our own ADB client starts the helper, so Shizuku isn't needed after a restart. */
    @Composable
    private fun SelfStartBlock(shizuku: ShizukuState) {
        var status by remember { mutableStateOf("") }
        val paired = SelfAdb.isPaired(this)
        val canToggle = SelfAdb.canToggleWirelessDebugging(this)
        Text("Start without Shizuku", fontWeight = FontWeight.SemiBold)
        Text(
            when {
                shizuku == ShizukuState.READY && ShizukuBridge.fromSelfHost -> "Helper running (started by PocketDesk)."
                !paired -> "Pair once: Developer options → Wireless debugging → Pair device with pairing code, " +
                    "then type the code in the PocketDesk notification. After that it starts by itself at every restart (on Wi-Fi)."
                !canToggle -> "Paired, but auto-start permission is missing. Tap Start while Wireless debugging is on."
                else -> "Paired. Starts by itself after a restart, once on Wi-Fi."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                PairingNotification.show(this@MainActivity)
                // its own task: reopening PocketDesk later must not land on Developer options
                runCatching { startActivity(Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text(if (paired) "Pair again" else "Pair") }
            if (paired) Button(onClick = {
                status = "Starting…"
                kotlin.concurrent.thread {
                    val r = SelfAdb.startHelper(applicationContext)
                    runOnUiThread { status = r.exceptionOrNull()?.let { "Failed: ${it.message}" } ?: "Started" }
                }
            }) { Text("Start now") }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }

    @Composable
    private fun Step(done: Boolean, title: String, body: String, action: @Composable () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text((if (done) "✓ " else "○ ") + title, fontWeight = FontWeight.SemiBold, color = if (done) Ok else MaterialTheme.colorScheme.onSurface)
            if (!done || title.startsWith("Share the screen") || title.startsWith("Shizuku")) {
                Text(body, style = MaterialTheme.typography.bodySmall)
                action()
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                content()
            }
        }
    }

    private fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${it.hostAddress} (${ni.name})" } }
    } catch (_: Exception) {
        emptyList()
    }

    private fun openShizuku() {
        val i = packageManager.getLaunchIntentForPackage(ShizukuBridge.SHIZUKU_PACKAGE)
        if (i != null) startActivity(i) else openShizukuStore()
    }

    private fun openShizukuStore() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShizukuBridge.SHIZUKU_PACKAGE}")))
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases")))
        }
    }

    private fun ignoringBatteryOptimizations(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private companion object {
        val Ok = androidx.compose.ui.graphics.Color(0xFF2ECC71)
    }
}
