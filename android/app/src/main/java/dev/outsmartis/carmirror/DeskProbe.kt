package dev.outsmartis.carmirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.io.File
import java.io.FileOutputStream

/**
 * CAR-002 spike. Run with shell rights (adb, a USB dock, or Shizuku's uid):
 *   CLASSPATH=$(pm path <pkg> | cut -d: -f2) app_process / dev.outsmartis.carmirror.DeskProbe 1920 1080 160 [flagsHex]
 * Creates a "desktop" virtual display and writes its frames to /data/local/tmp/desk.png every 2 s,
 * so `am start --display`, `input -d` and freeform windowing can be checked headless.
 */
object DeskProbe {
    private const val FLAG_PUBLIC = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
    private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    private const val FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
    private const val FLAG_TRUSTED = 1 shl 10
    private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    private const val FLAG_OWN_FOCUS = 1 shl 14

    @JvmStatic
    fun main(args: Array<String>) {
        val w = args.getOrNull(0)?.toInt() ?: 1920
        val h = args.getOrNull(1)?.toInt() ?: 1080
        val dpi = args.getOrNull(2)?.toInt() ?: 160
        val flags = args.getOrNull(3)?.toInt(16) ?: (FLAG_PUBLIC or FLAG_SUPPORTS_TOUCH or
            FLAG_DESTROY_CONTENT_ON_REMOVAL or FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS or FLAG_TRUSTED or
            FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_OWN_FOCUS)

        Looper.prepareMainLooper()
        val ctx = PrivilegedService.shellContext()
        val dm = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(ctx)

        val io = HandlerThread("frames").apply { start() }
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var last = 0L
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - last > 2000) {
                    last = now
                    val p = img.planes[0]
                    val bmp = Bitmap.createBitmap(p.rowStride / p.pixelStride, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(p.buffer)
                    val tmp = File("/data/local/tmp/desk.png.tmp")
                    FileOutputStream(tmp).use { Bitmap.createBitmap(bmp, 0, 0, w, h).compress(Bitmap.CompressFormat.PNG, 90, it) }
                    tmp.renameTo(File("/data/local/tmp/desk.png"))
                }
            } finally { img.close() }
        }, Handler(io.looper))

        val vd = dm.createVirtualDisplay("PocketDesk", w, h, dpi, reader.surface, flags)
            ?: error("createVirtualDisplay returned null")
        println("DISPLAY ${vd.display.displayId} flags=0x${Integer.toHexString(flags)}")
        Runtime.getRuntime().addShutdownHook(Thread { vd.release() })
        Looper.loop()
    }
}
