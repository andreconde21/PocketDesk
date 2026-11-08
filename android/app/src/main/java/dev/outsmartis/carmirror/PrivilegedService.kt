package dev.outsmartis.carmirror

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.FileOutputStream
import kotlin.concurrent.thread
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku "user service": runs in its own process as the shell user (uid 2000), the rights of
 * `adb shell`. That's what lets it create a *trusted* virtual display, start any app on it and
 * inject touches there; a normal app can only do that for its own activities.
 *
 * The techniques (a context that presents itself as com.android.shell, the display flags,
 * InputEvent.setDisplayId) follow scrcpy's server (Apache 2.0). Unlike scrcpy, nothing is
 * encoded here: the app encodes into its own MediaCodec surface and passes that surface in.
 */
class PrivilegedService : IPrivileged.Stub() {
    private val tag = "CarMirrorPriv"
    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()

    init {
        if (Build.VERSION.SDK_INT >= 28) runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
    }

    override fun destroy() {
        displays.values.forEach { runCatching { it.release() } }
        exitProcess(0)
    }

    override fun uid(): Int = android.os.Process.myUid()

    override fun createDisplay(name: String, width: Int, height: Int, dpi: Int, surface: Surface): Int {
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            FLAG_SUPPORTS_TOUCH
        if (Build.VERSION.SDK_INT >= 33) {
            flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
        }
        if (Build.VERSION.SDK_INT >= 34) flags = flags or FLAG_OWN_FOCUS or FLAG_DEVICE_DISPLAY_GROUP
        val vd = displayManager().createVirtualDisplay(name, width, height, dpi, surface, flags)
            ?: throw IllegalStateException("createVirtualDisplay returned null")
        val id = vd.display.displayId
        displays[id] = vd
        setImePolicy(id, IME_POLICY_LOCAL)
        keepLandscape(id)
        Log.i(tag, "display $id ${width}x$height/$dpi")
        return id
    }

    override fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int, surface: Surface) {
        val vd = displays[displayId] ?: throw IllegalStateException("unknown display $displayId")
        vd.resize(width, height, dpi)
        vd.surface = surface
    }

    override fun releaseDisplay(displayId: Int) {
        displays.remove(displayId)?.release()
    }

    override fun launchOnDisplay(component: String, displayId: Int): Boolean {
        // `am` does the ActivityOptions.setLaunchDisplayId() dance for us, with shell rights
        val p = ProcessBuilder(
            "am", "start", "--display", displayId.toString(),
            "-f", "0x10000000", // NEW_TASK: brings a running app's task over to this display
            "-n", component,
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(10, TimeUnit.SECONDS)
        Log.i(tag, "launch $component on $displayId: ${out.trim()}")
        return !out.contains("Error")
    }

    override fun createDesktop(name: String, width: Int, height: Int, dpi: Int, surface: Surface): Int {
        // what a real external monitor gets: system decorations (wallpaper, the launcher's
        // secondary home), its own focus, touch, and content destroyed with it (apps go away
        // rather than piling onto the phone screen)
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or FLAG_SUPPORTS_TOUCH or
            FLAG_DESTROY_CONTENT_ON_REMOVAL or FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS or FLAG_TRUSTED or
            FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_OWN_FOCUS
        val vd = displayManager().createVirtualDisplay(name, width, height, dpi, surface, flags)
            ?: throw IllegalStateException("createVirtualDisplay returned null")
        val id = vd.display.displayId
        displays[id] = vd
        setImePolicy(id, IME_POLICY_LOCAL)
        keepLandscape(id)
        Log.i(tag, "desktop $id ${width}x$height/$dpi")
        return id
    }

    override fun launchWindowed(component: String, displayId: Int): Boolean {
        val out = sh(
            "am", "start", "--display", displayId.toString(), "--windowingMode", WINDOWING_MODE_FREEFORM.toString(),
            "-f", "0x10000000", "-n", component,
        )
        Log.i(tag, "launch windowed $component on $displayId: ${out.trim()}")
        return !out.contains("Error")
    }

    override fun desktopTasks(displayId: Int): String {
        val arr = org.json.JSONArray()
        runCatching {
            val atm = serviceInterface("activity_task", "android.app.IActivityTaskManager")
            val m = atm.javaClass.methods.filter { it.name == "getTasks" }.maxByOrNull { it.parameterCount }
                ?: error("no getTasks")
            val args: Array<Any?> = when (m.parameterCount) {
                4 -> arrayOf(50, false, false, displayId) // Android 13+: (max, onlyVisibleRecents, keepIntentExtra, displayId)
                3 -> arrayOf(50, false, false)
                else -> arrayOf(50)
            }
            @Suppress("UNCHECKED_CAST")
            val tasks = m.invoke(atm, *args) as List<Any>
            for (t in tasks) {
                val c = t.javaClass
                // TaskInfo fields are public (some @hide): getField also finds the inherited ones
                fun field(n: String): Any? = runCatching { c.getField(n).get(t) }.getOrNull()
                val disp = (field("displayId") as? Int) ?: continue
                if (disp != displayId) continue
                val base = (field("baseActivity") ?: field("topActivity")) as? android.content.ComponentName ?: continue
                val type = field("topActivityType") as? Int
                if (type == ACTIVITY_TYPE_HOME) continue
                arr.put(
                    org.json.JSONObject()
                        .put("id", field("taskId") as? Int ?: continue)
                        .put("pkg", base.packageName)
                        .put("visible", field("isVisible") as? Boolean ?: true)
                        .put("mode", (field("configuration") as? android.content.res.Configuration)?.let { cfg ->
                            runCatching {
                                val wc = cfg.javaClass.getField("windowConfiguration").get(cfg)
                                wc.javaClass.getMethod("getWindowingMode").invoke(wc) as Int
                            }.getOrNull()
                        } ?: 0),
                )
            }
        }.onFailure { Log.w(tag, "desktopTasks: $it") }
        return arr.toString()
    }

    override fun taskAction(taskId: Int, action: String): Boolean = runCatching {
        val atm = serviceInterface("activity_task", "android.app.IActivityTaskManager")
        when (action) {
            "close" -> atm.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(atm, taskId) as Boolean
            else -> {
                val ok = runCatching {
                    atm.javaClass.getMethod("setFocusedTask", Int::class.javaPrimitiveType).invoke(atm, taskId)
                    true
                }.getOrDefault(false)
                // setFocusedTask only focuses; a minimised/behind window also needs to come to the front
                runCatching {
                    val m = atm.javaClass.methods.first { it.name == "startActivityFromRecents" }
                    m.invoke(atm, taskId, null)
                }.isSuccess || ok
            }
        }
    }.onFailure { Log.w(tag, "taskAction $action $taskId: $it") }.getOrDefault(false)

    override fun prepareDesktop(): String {
        var reboot = false
        for ((key, value) in listOf(
            "enable_freeform_support" to "1",
            "force_desktop_mode_on_external_displays" to "1",
            "force_resizable_activities" to "1",
        )) {
            val now = sh("settings", "get", "global", key).trim()
            if (now != value) {
                sh("settings", "put", "global", key, value)
                // freeform support and desktop mode are read when the system starts
                if (key != "force_resizable_activities") reboot = true
            }
        }
        val supported = runCatching {
            val atm = serviceInterface("activity_task", "android.app.IActivityTaskManager")
            atm.javaClass.methods.firstOrNull { it.name == "supportsFreeform" }?.invoke(atm) as? Boolean
        }.getOrNull()
        Log.i(tag, "prepareDesktop: reboot=$reboot freeform=$supported")
        return if (reboot && supported != true) "reboot" else "ok"
    }

    override fun injectMouse(
        displayId: Int, action: Int, x: Float, y: Float, buttons: Int, actionButton: Int,
        vscroll: Float, hscroll: Float, metaState: Int,
    ): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) mouseDownTime = now
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (buttons != 0) 1f else 0f
            size = 1f
            if (action == MotionEvent.ACTION_SCROLL) {
                setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
                setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll)
            }
        })
        val pressed = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE ||
            action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE
        val ev = MotionEvent.obtain(
            if (pressed) mouseDownTime else now, now, action, 1, props, coords, metaState, buttons,
            1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0,
        )
        if (actionButton != 0) {
            runCatching { MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType).invoke(ev, actionButton) }
        }
        return try { inject(ev, displayId) } finally { ev.recycle() }
    }

    @Volatile private var mouseDownTime = 0L

    private fun sh(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(10, TimeUnit.SECONDS)
        return out
    }

    override fun injectMotion(event: MotionEvent, displayId: Int): Boolean = inject(event, displayId)

    override fun injectKey(event: KeyEvent, displayId: Int): Boolean = inject(event, displayId, INJECT_WAIT_FOR_RESULT)

    private fun inject(event: InputEvent, displayId: Int, mode: Int = INJECT_ASYNC): Boolean = try {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
        val im = shellContext().getSystemService(Context.INPUT_SERVICE) as InputManager
        InputManager::class.java.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            .invoke(im, event, mode) as Boolean
    } catch (e: Exception) {
        Log.w(tag, "inject failed: $e")
        false
    }

    /**
     * Phones without per-display focus route keys to the "top focused" display (the phone's own
     * screen). Touches move focus, keys don't: pull focus to the car display before a key.
     */
    override fun focusDisplay(displayId: Int) {
        runCatching {
            val wm = serviceInterface("window", "android.view.IWindowManager")
            wm.javaClass.getMethod("moveDisplayToTopIfAllowed", Int::class.javaPrimitiveType).invoke(wm, displayId)
        }.onFailure { Log.w(tag, "moveDisplayToTopIfAllowed: $it") }
        runCatching {
            val atm = serviceInterface("activity_task", "android.app.IActivityTaskManager")
            atm.javaClass.getMethod("focusTopTask", Int::class.javaPrimitiveType).invoke(atm, displayId)
        }.onFailure { Log.w(tag, "focusTopTask: $it") }
    }

    private fun serviceInterface(name: String, iface: String): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name) as IBinder
        return Class.forName("$iface\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    /**
     * Wi-Fi hotspot with a public-looking subnet: the Tesla browser refuses private (RFC 1918)
     * addresses, so on a 9.9.0.x hotspot it can reach the phone directly. Android's tethering
     * module supports a static server/client address pair for privileged callers
     * (TetheringRequest.Builder.setStaticIpv4Addresses, TETHER_PRIVILEGED, which shell holds);
     * its DHCP server then hands out that single client address, so one device (the car) can join.
     */
    override fun startCarHotspot(serverAddr: String, clientAddr: String): String = try {
        val tmCls = Class.forName("android.net.TetheringManager")
        val tm = tetheringManager()
        runCatching { tmCls.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tm, TETHERING_WIFI) }
        Thread.sleep(2000)
        val laCls = Class.forName("android.net.LinkAddress")
        val la = laCls.getConstructor(String::class.java)
        val bCls = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val b = bCls.getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
        bCls.getMethod("setStaticIpv4Addresses", laCls, laCls).invoke(b, la.newInstance(serverAddr), la.newInstance(clientAddr))
        runCatching { bCls.getMethod("setExemptFromEntitlementCheck", Boolean::class.javaPrimitiveType).invoke(b, true) }
        runCatching { bCls.getMethod("setShouldShowEntitlementUi", Boolean::class.javaPrimitiveType).invoke(b, false) }
        val request = bCls.getMethod("build").invoke(b)
        val cbCls = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val done = java.util.concurrent.CountDownLatch(1)
        var result = "timeout"
        val cb = java.lang.reflect.Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls)) { _, m, args ->
            when (m.name) {
                "onTetheringStarted" -> { result = "ok"; done.countDown() }
                "onTetheringFailed" -> { result = "failed (error ${args?.getOrNull(0)})"; done.countDown() }
            }
            null
        }
        val reqCls = Class.forName("android.net.TetheringManager\$TetheringRequest")
        tmCls.getMethod("startTethering", reqCls, java.util.concurrent.Executor::class.java, cbCls)
            .invoke(tm, request, java.util.concurrent.Executor { it.run() }, cb)
        done.await(20, TimeUnit.SECONDS)
        Log.i(tag, "car hotspot $serverAddr: $result")
        result
    } catch (e: Throwable) {
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        Log.w(tag, "car hotspot failed", cause)
        "error: $cause"
    }

    override fun hotspotAddress(): String = try {
        val tm = tetheringManager()
        @Suppress("UNCHECKED_CAST")
        val ifaces = Class.forName("android.net.TetheringManager").getMethod("getTetheredIfaces").invoke(tm) as Array<String>
        ifaces.asSequence()
            .filter { !it.startsWith("rndis") && !it.startsWith("usb") && !it.startsWith("bt") }
            .mapNotNull { name ->
                java.net.NetworkInterface.getByName(name)?.inetAddresses?.toList()
                    ?.firstOrNull { it is java.net.Inet4Address }?.hostAddress
            }
            .firstOrNull() ?: ""
    } catch (e: Throwable) {
        Log.w(tag, "hotspotAddress: $e")
        ""
    }

    /** The active media session (shell holds MEDIA_CONTENT_CONTROL): playing one first. */
    /**
     * Active media sessions straight from the media_session service: MediaSessionManager itself
     * needs app-process framework setup that app_process doesn't have.
     */
    private fun mediaControllers(): List<android.media.session.MediaController> {
        val ims = serviceInterface("media_session", "android.media.session.ISessionManager")
        val m = ims.javaClass.methods.first { it.name == "getSessions" }
        @Suppress("UNCHECKED_CAST")
        val raw = m.invoke(ims, null, 0)
        val tokens: List<Any> = when (raw) {
            is List<*> -> raw.filterNotNull()
            else -> (raw.javaClass.getMethod("getList").invoke(raw) as List<*>).filterNotNull() // ParceledListSlice
        }
        return tokens.mapNotNull { t ->
            runCatching { android.media.session.MediaController(shellContext(), t as android.media.session.MediaSession.Token) }.getOrNull()
        }
    }

    override fun nowPlaying(): String = try {
        val controllers = mediaControllers()
        val c = controllers.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()
        if (c == null) {
            ""
        } else {
            val md = c.metadata
            org.json.JSONObject()
                .put("pkg", c.packageName)
                .put("title", md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "")
                .put("artist", md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "")
                .put("playing", c.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING)
                .toString()
        }
    } catch (e: Throwable) {
        Log.w(tag, "nowPlaying: $e")
        ""
    }

    /** Back to the normal hotspot: stop, then start again without the static addresses. */
    override fun stopCarHotspot() {
        runCatching {
            val tmCls = Class.forName("android.net.TetheringManager")
            val tm = tetheringManager()
            tmCls.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tm, TETHERING_WIFI)
            Thread.sleep(2000)
            val cbCls = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            val cb = java.lang.reflect.Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls)) { _, _, _ -> null }
            tmCls.getMethod("startTethering", Int::class.javaPrimitiveType, java.util.concurrent.Executor::class.java, cbCls)
                .invoke(tm, TETHERING_WIFI, java.util.concurrent.Executor { it.run() }, cb)
        }.onFailure { Log.w(tag, "normal hotspot: $it") }
    }

    /**
     * A TetheringManager whose calls identify as com.android.shell (our uid): the one from
     * getSystemService() carries the system context's package ("android") and is rejected.
     */
    private fun tetheringManager(): Any {
        val tmCls = Class.forName("android.net.TetheringManager")
        val ctor = tmCls.getDeclaredConstructor(Context::class.java, java.util.function.Supplier::class.java)
        ctor.isAccessible = true
        val supplier = java.util.function.Supplier<IBinder> {
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "tethering") as IBinder
        }
        return ctor.newInstance(shellContext(), supplier)
    }

    @Volatile private var recorder: AudioRecord? = null

    @SuppressLint("MissingPermission", "WrongConstant")
    override fun startAudioCapture(): ParcelFileDescriptor {
        stopAudioCapture()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48000)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val builder = AudioRecord.Builder()
        if (Build.VERSION.SDK_INT >= 31) builder.setContext(shellContext())
        val rec = builder
            .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, 4096) * 4)
            .build()
        rec.startRecording()
        recorder = rec
        val pipe = ParcelFileDescriptor.createPipe()
        val out = FileOutputStream(pipe[1].fileDescriptor)
        val writeEnd = pipe[1]
        thread(name = "audio-capture") {
            val buf = ByteArray(3840) // 20 ms of 48 kHz stereo 16-bit
            try {
                while (recorder === rec) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n > 0) out.write(buf, 0, n)
                }
            } catch (e: Exception) {
                Log.i(tag, "audio capture ended: $e")
            } finally {
                runCatching { out.close() }
                runCatching { writeEnd.close() }
            }
        }
        Log.i(tag, "audio capture started")
        return pipe[0]
    }

    override fun stopAudioCapture() {
        val rec = recorder ?: return
        recorder = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
    }

    override fun setKeyboard(displayId: Int, show: Boolean) {
        setImePolicy(displayId, if (show) IME_POLICY_LOCAL else IME_POLICY_HIDE)
    }

    /** LOCAL: keyboard on the car display, not on the phone. HIDE: no on-screen keyboard at all. */
    private fun setImePolicy(displayId: Int, policy: Int) {
        runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "window") as IBinder
            val wm = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(wm, displayId, policy)
        }.onFailure { Log.w(tag, "IME policy: $it") }
    }

    /**
     * The car pane never turns: apps asking for portrait (YouTube leaving fullscreen) used to
     * rotate the display, and the car got the picture sideways until the app was reopened.
     * Rotation stays at 0 and orientation requests are ignored, so the app lays out for the pane.
     */
    private fun keepLandscape(displayId: Int) {
        val wm = runCatching { serviceInterface("window", "android.view.IWindowManager") }.getOrNull() ?: return
        runCatching {
            wm.javaClass.getMethod("setIgnoreOrientationRequest", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(wm, displayId, true)
        }.onFailure { Log.w(tag, "ignore orientation: $it") }
        runCatching {
            // Android 15 added a caller tag
            val m = wm.javaClass.methods.first { it.name == "freezeDisplayRotation" }
            if (m.parameterCount == 3) m.invoke(wm, displayId, 0, "CarMirror") else m.invoke(wm, displayId, 0)
        }.onFailure { Log.w(tag, "freeze rotation: $it") }
    }

    private fun displayManager(): DisplayManager {
        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(shellContext())
    }

    companion object {
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
        private const val WINDOWING_MODE_FREEFORM = 5
        private const val ACTIVITY_TYPE_HOME = 2
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private const val FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15
        private const val INJECT_ASYNC = 0
        private const val INJECT_WAIT_FOR_RESULT = 1
        private const val TETHERING_WIFI = 0
        private const val IME_POLICY_LOCAL = 0 // WindowManager.DISPLAY_IME_POLICY_LOCAL
        private const val IME_POLICY_HIDE = 2 // WindowManager.DISPLAY_IME_POLICY_HIDE

        @Volatile private var context: Context? = null

        /** A system context that identifies itself as com.android.shell (our uid), as system services require. */
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        fun shellContext(): Context {
            context?.let { return it }
            val at = Class.forName("android.app.ActivityThread")
            var thread = at.getMethod("currentActivityThread").invoke(null)
            if (thread == null) {
                thread = at.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
                at.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
            }
            val system = at.getDeclaredMethod("getSystemContext").invoke(thread) as Context
            val shell = object : ContextWrapper(system) {
                override fun getPackageName() = "com.android.shell"
                override fun getOpPackageName() = "com.android.shell"
                override fun getAttributionSource(): AttributionSource =
                    AttributionSource.Builder(2000).setPackageName("com.android.shell").build()
                override fun getApplicationContext(): Context = this
            }
            context = shell
            return shell
        }
    }
}
