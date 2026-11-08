package dev.outsmartis.carmirror

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.webrtc.DataChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * PocketDesk: a whole desktop on its own display, the way a real external monitor works.
 * The privileged helper creates a display with system decorations (wallpaper, the launcher's
 * secondary home) around this session's encoder surface; apps open on it in freeform windows.
 * The browser on the monitor drives it with a real mouse (hover, buttons, wheel) and keyboard.
 */
class DeskSession(
    private val context: Context,
    override val sid: Long,
    private var width: Int,
    private var height: Int,
    private var dpi: Int,
    private var fps: Int,
    private var bitrate: Int,
    private val onStarted: (DeskSession) -> Unit,
    private val onEnded: (DeskSession, String?) -> Unit,
    private val onStats: (DeskSession, Long, Int) -> Unit,
    /** The windows on the desktop changed: JSON array [{id,pkg,visible,mode}], topmost first. */
    private val onTasks: (DeskSession, JSONArray) -> Unit,
) : CarSession {
    override val pkg = PKG
    private val tag = "PocketDesk"
    private val channelLatch = CountDownLatch(1)
    private val sender = VideoChannelSender(tag, sid) { requestKeyFrame() }
    @Volatile private var stopped = false
    @Volatile private var restart = false
    @Volatile private var keyRequested = false
    @Volatile private var displayId = -1
    @Volatile private var keyboard = false
    override var fullLog: String? = null

    /** Input in order, off the peer thread (key injection waits for Android's answer). */
    private val input = Executors.newSingleThreadExecutor()

    override fun attachChannel(dc: DataChannel) {
        sender.channel = dc
        channelLatch.countDown()
    }

    override fun start() {
        thread(name = "desk-$sid") {
            // keep the phone awake (screen dimmed): when it locks, Android pauses the apps on the desktop too
            val wake = (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).run {
                @Suppress("DEPRECATION")
                newWakeLock(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "PocketDesk:desk")
            }
            runCatching { wake.acquire(12 * 60 * 60 * 1000L) }
            val error = try {
                run()
                null
            } catch (e: Exception) {
                Log.w(tag, "desktop $sid ended: $e")
                e.message ?: e.toString()
            }
            releaseDisplay()
            runCatching { wake.release() }
            onEnded(this, if (stopped) null else error ?: "The desktop stopped")
        }
    }

    private fun run() {
        // right after a boot or an update the helper may still be starting (Wi-Fi, adb): wait for it
        val deadline = System.currentTimeMillis() + 30_000
        var found = runBlocking { ShizukuBridge.awaitService(4000) }
        while (found == null && !stopped && System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
            found = ShizukuBridge.service
        }
        val service = found ?: throw IllegalStateException("The desktop helper isn't running. Open PocketDesk on the phone.")
        if (!channelLatch.await(8, TimeUnit.SECONDS)) throw IllegalStateException("video channel did not open")
        runCatching { service.prepareDesktop() }
        var first = true
        while (!stopped) {
            restart = false
            encodeOnce(service) {
                if (first) {
                    first = false
                    onStarted(this)
                    startTaskWatch(service)
                }
            }
        }
    }

    private fun encodeOnce(service: IPrivileged, onReady: () -> Unit) {
        val w = width
        val h = height
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        try {
            if (stopped) return
            if (displayId < 0) {
                val id = service.createDesktop("PocketDesk", w, h, dpi, surface)
                // a failure in the helper comes back as 0 (exceptions don't cross the binder)
                if (id <= 0) throw IllegalStateException("The phone couldn't create the desktop screen")
                displayId = id
                runCatching { service.setKeyboard(displayId, keyboard) }
            } else {
                service.resizeDisplay(displayId, w, h, dpi, surface)
            }
            Log.i(tag, "desktop $sid on display $displayId ${w}x$h/$dpi")
            sender.sendSession(w, h)
            onReady()
            val info = MediaCodec.BufferInfo()
            var lastStats = System.currentTimeMillis()
            while (!stopped && !restart) {
                if (keyRequested) {
                    keyRequested = false
                    codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                }
                val idx = codec.dequeueOutputBuffer(info, 50_000)
                if (idx < 0) continue
                val buf = codec.getOutputBuffer(idx)
                if (buf != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(bytes)
                    val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    sender.sendPacket(config, key, if (config) 0 else info.presentationTimeUs, bytes)
                    val now = System.currentTimeMillis()
                    if (!config && now - lastStats >= 2000) {
                        lastStats = now
                        onStats(this, (System.nanoTime() / 1000 - info.presentationTimeUs) / 1000, sender.droppedFrames)
                    }
                }
                codec.releaseOutputBuffer(idx, false)
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { surface.release() }
        }
    }

    // ------------------------------------------------------------ windows (taskbar)

    private var lastTasks = ""

    private fun startTaskWatch(service: IPrivileged) {
        thread(name = "desk-tasks-$sid", isDaemon = true) {
            while (!stopped) {
                val id = displayId
                if (id >= 0) {
                    val json = runCatching { service.desktopTasks(id) }.getOrDefault("[]")
                    if (json != lastTasks) {
                        lastTasks = json
                        runCatching { onTasks(this, JSONArray(json)) }
                    }
                }
                Thread.sleep(1000)
            }
        }
    }

    fun tasksNow(): JSONArray = runCatching { JSONArray(lastTasks) }.getOrDefault(JSONArray())

    /** Opens an app in a window on the desktop (or brings its window to the front). */
    fun open(pkg: String): String? {
        val service = ShizukuBridge.service ?: return "The desktop helper isn't running"
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToString()
            ?: return "$pkg can't be opened"
        val id = displayId
        if (id < 0) return "The desktop isn't ready yet"
        input.execute {
            if (!runCatching { service.launchWindowed(launch, id) }.getOrDefault(false)) {
                Log.w(tag, "launch $pkg failed")
            }
            lastTasks = "" // refresh the taskbar right away
        }
        return null
    }

    fun task(taskId: Int, action: String) {
        val service = ShizukuBridge.service ?: return
        input.execute {
            runCatching { service.taskAction(taskId, action) }
            lastTasks = ""
        }
    }

    // ------------------------------------------------------------ mouse

    private var buttons = 0
    private var hovering = false

    /**
     * A mouse event from the browser. [a]: "move", "down", "up", "leave"; [b] the buttons held
     * afterwards (browser bits match Android's: 1 primary, 2 secondary, 4 middle, 8 back, 16 forward).
     */
    fun mouse(a: String, b: Int, x: Int, y: Int, w: Int, h: Int, meta: Int) {
        val service = ShizukuBridge.service ?: return
        val id = displayId
        if (id < 0 || w <= 0 || h <= 0) return
        val px = x.toFloat() * width / w
        val py = y.toFloat() * height / h
        val m = metaState(meta)
        input.execute {
            fun inj(action: Int, held: Int, actionButton: Int = 0) =
                runCatching { service.injectMouse(id, action, px, py, held, actionButton, 0f, 0f, m) }
            val before = buttons
            // back/forward buttons are keys on Android
            val keys = (b and (BACK or FORWARD)) and (before and (BACK or FORWARD)).inv()
            if (keys and BACK != 0) pressKey(service, id, KeyEvent.KEYCODE_BACK, 0)
            if (keys and FORWARD != 0) pressKey(service, id, KeyEvent.KEYCODE_FORWARD, 0)
            val now = b and (BACK or FORWARD).inv()
            when (a) {
                "leave" -> {
                    if (hovering && before == 0) inj(MotionEvent.ACTION_HOVER_EXIT, 0)
                    hovering = false
                }
                else -> {
                    val pressed = now and before.inv()
                    val released = before and now.inv()
                    if (pressed != 0) {
                        if (hovering) { inj(MotionEvent.ACTION_HOVER_EXIT, 0); hovering = false }
                        if (before == 0) inj(MotionEvent.ACTION_DOWN, now)
                        forEachBit(pressed) { bit -> inj(MotionEvent.ACTION_BUTTON_PRESS, now, bit) }
                    }
                    if (released != 0) {
                        forEachBit(released) { bit -> inj(MotionEvent.ACTION_BUTTON_RELEASE, now, bit) }
                        if (now == 0) inj(MotionEvent.ACTION_UP, 0)
                    }
                    if (pressed == 0 && released == 0) {
                        if (now != 0) {
                            inj(MotionEvent.ACTION_MOVE, now)
                        } else {
                            if (!hovering) { inj(MotionEvent.ACTION_HOVER_ENTER, 0); hovering = true }
                            inj(MotionEvent.ACTION_HOVER_MOVE, 0)
                        }
                    }
                    buttons = now
                }
            }
        }
    }

    /** Wheel notches: [dy] > 0 scrolls up (content moves down), [dx] > 0 scrolls right. */
    fun wheel(x: Int, y: Int, w: Int, h: Int, dx: Float, dy: Float, meta: Int) {
        val service = ShizukuBridge.service ?: return
        val id = displayId
        if (id < 0 || w <= 0 || h <= 0) return
        val px = x.toFloat() * width / w
        val py = y.toFloat() * height / h
        val m = metaState(meta)
        input.execute {
            runCatching { service.injectMouse(id, MotionEvent.ACTION_SCROLL, px, py, buttons, 0, dy, dx, m) }
        }
    }

    private inline fun forEachBit(mask: Int, f: (Int) -> Unit) {
        var bit = 1
        while (bit <= 16) {
            if (mask and bit != 0) f(bit)
            bit = bit shl 1
        }
    }

    // ------------------------------------------------------------ keyboard

    /**
     * A key from the monitor's keyboard. Plain characters are typed as characters (so the
     * browser's keyboard layout wins: ç, é, @ on a Portuguese keyboard); keys with Ctrl, Alt or
     * Meta, and keys that aren't characters, go as real key presses (shortcuts, arrows, F-keys).
     */
    fun keyEvent(down: Boolean, code: String, key: String, meta: Int, repeat: Int) {
        val service = ShizukuBridge.service ?: return
        val id = displayId
        if (id < 0) return
        val m = metaState(meta)
        val shortcut = meta and (M_CTRL or M_ALT or M_META) != 0
        if (key.codePointCount(0, key.length) == 1 && !shortcut && KeyMap.CODES[code]?.let { it == KeyEvent.KEYCODE_SPACE } != true) {
            if (down) input.execute {
                for (ev in Typing.textEvents(key)) runCatching { service.injectKey(ev, id) }
            }
            return
        }
        // Esc is Back, as on Android desktops and Chromebooks
        val kc = (if (code == "Escape" && !shortcut) KeyEvent.KEYCODE_BACK else KeyMap.CODES[code]) ?: return
        if (kc in KeyMap.MODIFIERS) return // their state travels as meta on the other keys
        input.execute {
            val now = SystemClock.uptimeMillis()
            val ev = KeyEvent(now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, kc, repeat, m,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            runCatching { service.injectKey(ev, id) }
        }
    }

    private fun pressKey(service: IPrivileged, id: Int, code: Int, meta: Int) {
        val now = SystemClock.uptimeMillis()
        for (a in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            runCatching {
                service.injectKey(KeyEvent(now, now, a, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), id)
            }
        }
    }

    private fun metaState(bits: Int): Int {
        var m = 0
        if (bits and M_SHIFT != 0) m = m or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (bits and M_CTRL != 0) m = m or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (bits and M_ALT != 0) m = m or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (bits and M_META != 0) m = m or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        return m
    }

    // ------------------------------------------------------------ CarSession

    /** Touch from a touch screen on the monitor side (laptop touch screen, tablet browser). */
    private data class Pointer(val id: Int, var x: Float, var y: Float)
    private val pointers = ArrayList<Pointer>()
    private var downTime = 0L

    @Synchronized
    override fun touch(action: Int, id: Long, x: Int, y: Int, w: Int, h: Int) {
        val service = ShizukuBridge.service ?: return
        if (displayId < 0 || w <= 0 || h <= 0) return
        val px = x.toFloat() * width / w
        val py = y.toFloat() * height / h
        val pid = if (id < 0) 0 else id.toInt()
        val now = SystemClock.uptimeMillis()
        var idx = pointers.indexOfFirst { it.id == pid }
        val motionAction: Int
        when (action) {
            0 -> {
                if (idx >= 0) pointers.removeAt(idx)
                pointers += Pointer(pid, px, py)
                idx = pointers.size - 1
                if (pointers.size == 1) downTime = now
                motionAction = if (pointers.size == 1) MotionEvent.ACTION_DOWN
                else MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            2 -> {
                if (idx < 0) return
                pointers[idx].x = px
                pointers[idx].y = py
                motionAction = MotionEvent.ACTION_MOVE
            }
            else -> {
                if (idx < 0) return
                pointers[idx].x = px
                pointers[idx].y = py
                motionAction = if (pointers.size == 1) MotionEvent.ACTION_UP
                else MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
        }
        val n = pointers.size
        val props = Array(n) { i -> MotionEvent.PointerProperties().apply { this.id = pointers[i].id; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(n) { i -> MotionEvent.PointerCoords().apply { this.x = pointers[i].x; this.y = pointers[i].y; pressure = 1f; size = 1f } }
        val ev = MotionEvent.obtain(downTime, now, motionAction, n, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        val disp = displayId
        input.execute {
            runCatching { service.injectMotion(ev, disp) }
            ev.recycle()
        }
        if (action != 0 && action != 2) pointers.removeAt(idx)
    }

    override fun scroll(x: Int, y: Int, w: Int, h: Int, dx: Float, dy: Float) = wheel(x, y, w, h, dx, dy, 0)

    override fun key(name: String) {
        val service = ShizukuBridge.service ?: return
        val id = displayId
        if (id < 0) return
        val code = when (name) {
            "home" -> KeyEvent.KEYCODE_HOME
            "recents" -> KeyEvent.KEYCODE_APP_SWITCH
            else -> KeyEvent.KEYCODE_BACK
        }
        input.execute { pressKey(service, id, code, 0) }
    }

    override fun typeKey(key: String, ctrl: Boolean, shift: Boolean) {
        val service = ShizukuBridge.service ?: return
        val id = displayId
        if (id < 0) return
        val events = Typing.keyEvents(key, ctrl, shift)
        input.execute { for (ev in events) runCatching { service.injectKey(ev, id) } }
    }

    override fun typeText(text: String): Boolean {
        val service = ShizukuBridge.service ?: return false
        val id = displayId
        if (id < 0 || text.isEmpty()) return false
        // pasted text: line by line, Enter between lines
        val events = text.replace("\r\n", "\n").split('\n').flatMapIndexed { i, line ->
            (if (i > 0) Typing.keyEvents("Enter", false, false) else emptyList()) + Typing.textEvents(line)
        }
        input.execute { for (ev in events) runCatching { service.injectKey(ev, id) } }
        return true
    }

    override fun setKeyboard(show: Boolean) {
        keyboard = show
        val id = displayId
        if (id >= 0) runCatching { ShizukuBridge.service?.setKeyboard(id, show) }
    }

    override fun resize(w: Int, h: Int) {
        val nw = w and 15.inv()
        val nh = h and 15.inv()
        if (nw == width && nh == height) return
        width = nw
        height = nh
        restart = true
    }

    override fun requestKeyFrame() {
        keyRequested = true
    }

    override fun reconfigure(w: Int, h: Int, dpi: Int, fps: Int, bitrate: Int) {
        width = w and 15.inv()
        height = h and 15.inv()
        this.dpi = dpi
        this.fps = fps
        this.bitrate = bitrate
        restart = true
    }

    @Synchronized
    private fun releaseDisplay() {
        val id = displayId
        if (id < 0) return
        displayId = -1
        runCatching { ShizukuBridge.service?.releaseDisplay(id) }
    }

    override fun stop() {
        stopped = true
        input.shutdown()
        releaseDisplay()
        channelLatch.countDown()
    }

    companion object {
        const val PKG = "@desktop"
        private const val BACK = 8
        private const val FORWARD = 16
        // modifier bits from the browser
        const val M_SHIFT = 1
        const val M_CTRL = 2
        const val M_ALT = 4
        const val M_META = 8
    }
}

/** Browser `KeyboardEvent.code` (physical key) to Android key codes. */
object KeyMap {
    val MODIFIERS = setOf(
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_CAPS_LOCK,
    )

    val CODES: Map<String, Int> = buildMap {
        for (c in 'A'..'Z') put("Key$c", KeyEvent.KEYCODE_A + (c - 'A'))
        for (d in 0..9) put("Digit$d", KeyEvent.KEYCODE_0 + d)
        for (d in 0..9) put("Numpad$d", KeyEvent.KEYCODE_NUMPAD_0 + d)
        for (f in 1..12) put("F$f", KeyEvent.KEYCODE_F1 + f - 1)
        putAll(
            mapOf(
                "Enter" to KeyEvent.KEYCODE_ENTER, "NumpadEnter" to KeyEvent.KEYCODE_NUMPAD_ENTER,
                "Backspace" to KeyEvent.KEYCODE_DEL, "Delete" to KeyEvent.KEYCODE_FORWARD_DEL,
                "Tab" to KeyEvent.KEYCODE_TAB, "Escape" to KeyEvent.KEYCODE_ESCAPE, "Space" to KeyEvent.KEYCODE_SPACE,
                "ArrowLeft" to KeyEvent.KEYCODE_DPAD_LEFT, "ArrowRight" to KeyEvent.KEYCODE_DPAD_RIGHT,
                "ArrowUp" to KeyEvent.KEYCODE_DPAD_UP, "ArrowDown" to KeyEvent.KEYCODE_DPAD_DOWN,
                "Home" to KeyEvent.KEYCODE_MOVE_HOME, "End" to KeyEvent.KEYCODE_MOVE_END,
                "PageUp" to KeyEvent.KEYCODE_PAGE_UP, "PageDown" to KeyEvent.KEYCODE_PAGE_DOWN,
                "Insert" to KeyEvent.KEYCODE_INSERT, "PrintScreen" to KeyEvent.KEYCODE_SYSRQ,
                "Minus" to KeyEvent.KEYCODE_MINUS, "Equal" to KeyEvent.KEYCODE_EQUALS,
                "BracketLeft" to KeyEvent.KEYCODE_LEFT_BRACKET, "BracketRight" to KeyEvent.KEYCODE_RIGHT_BRACKET,
                "Backslash" to KeyEvent.KEYCODE_BACKSLASH, "Semicolon" to KeyEvent.KEYCODE_SEMICOLON,
                "Quote" to KeyEvent.KEYCODE_APOSTROPHE, "Comma" to KeyEvent.KEYCODE_COMMA,
                "Period" to KeyEvent.KEYCODE_PERIOD, "Slash" to KeyEvent.KEYCODE_SLASH, "Backquote" to KeyEvent.KEYCODE_GRAVE,
                "NumpadAdd" to KeyEvent.KEYCODE_NUMPAD_ADD, "NumpadSubtract" to KeyEvent.KEYCODE_NUMPAD_SUBTRACT,
                "NumpadMultiply" to KeyEvent.KEYCODE_NUMPAD_MULTIPLY, "NumpadDivide" to KeyEvent.KEYCODE_NUMPAD_DIVIDE,
                "NumpadDecimal" to KeyEvent.KEYCODE_NUMPAD_DOT,
                "ShiftLeft" to KeyEvent.KEYCODE_SHIFT_LEFT, "ShiftRight" to KeyEvent.KEYCODE_SHIFT_RIGHT,
                "ControlLeft" to KeyEvent.KEYCODE_CTRL_LEFT, "ControlRight" to KeyEvent.KEYCODE_CTRL_RIGHT,
                "AltLeft" to KeyEvent.KEYCODE_ALT_LEFT, "AltRight" to KeyEvent.KEYCODE_ALT_RIGHT,
                "MetaLeft" to KeyEvent.KEYCODE_META_LEFT, "MetaRight" to KeyEvent.KEYCODE_META_RIGHT,
                "CapsLock" to KeyEvent.KEYCODE_CAPS_LOCK,
                "AudioVolumeUp" to KeyEvent.KEYCODE_VOLUME_UP, "AudioVolumeDown" to KeyEvent.KEYCODE_VOLUME_DOWN,
                "AudioVolumeMute" to KeyEvent.KEYCODE_VOLUME_MUTE, "MediaPlayPause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                "MediaTrackNext" to KeyEvent.KEYCODE_MEDIA_NEXT, "MediaTrackPrevious" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                "BrowserBack" to KeyEvent.KEYCODE_BACK,
            ),
        )
    }
}
