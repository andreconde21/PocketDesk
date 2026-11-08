package dev.outsmartis.carmirror

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.File
import kotlin.system.exitProcess

/**
 * The privileged helper without Shizuku (CAR-002). Started with shell rights by SelfAdb (or a USB
 * dock / `adb shell`):
 *   CLASSPATH=<apk> app_process /system/bin dev.outsmartis.carmirror.SelfHost <pkg>.selfhost
 * It hosts the same PrivilegedService as the Shizuku path and hands its binder to the app through
 * SelfHostProvider, again every few seconds so a restarted app process picks it up. It exits when
 * the APK it was started from is replaced (an update starts a fresh helper).
 */
object SelfHost {
    private const val TAG = "PocketDeskHost"
    private const val EVERY_MS = 3000L

    @JvmStatic
    fun main(args: Array<String>) {
        val authority = args.getOrNull(0) ?: error("usage: SelfHost <authority>")
        val apk = System.getenv("CLASSPATH")?.let(::File)
        Looper.prepareMainLooper()
        // app_process has no ActivityThread: build the system context here, on the main looper.
        // Built lazily on a binder thread it fails ("Can't create handler… Looper.prepare()").
        PrivilegedService.shellContext()
        val service = PrivilegedService()
        val handler = Handler(Looper.getMainLooper())
        println("SelfHost uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()} -> $authority")

        val tick = object : Runnable {
            override fun run() {
                if (apk != null && !apk.exists()) {
                    Log.i(TAG, "APK replaced, exiting")
                    service.destroy()
                    exitProcess(0)
                }
                runCatching { sendBinder(authority, service.asBinder()) }
                    .onFailure { Log.d(TAG, "app not reachable: $it") }
                handler.postDelayed(this, EVERY_MS)
            }
        }
        handler.post(tick)
        Looper.loop()
    }

    /** IActivityManager.getContentProviderExternal + IContentProvider.call, as Shizuku's server does. */
    @SuppressLint("PrivateApi")
    private fun sendBinder(authority: String, binder: IBinder) {
        val sm = Class.forName("android.os.ServiceManager")
        val amBinder = sm.getMethod("getService", String::class.java).invoke(null, "activity") as IBinder
        val am = Class.forName("android.app.IActivityManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, amBinder)
        val token = Binder()
        val get = am.javaClass.methods.first { it.name == "getContentProviderExternal" }
        val holder = get.invoke(am, authority, 0, token, "PocketDesk") ?: error("provider $authority not found")
        try {
            val provider = holder.javaClass.getField("provider").get(holder) ?: error("no provider in holder")
            val extras = Bundle().apply { putBinder(SelfHostProvider.EXTRA_BINDER, binder) }
            val calls = provider.javaClass.methods.filter { it.name == "call" && it.returnType == Bundle::class.java }
            val call = calls.firstOrNull { it.parameterCount == 5 && it.parameterTypes[0] == AttributionSource::class.java }
                ?: calls.firstOrNull { it.parameterCount == 6 && it.parameterTypes[0] == String::class.java }
                ?: error("no usable IContentProvider.call among $calls")
            val shell = AttributionSource.Builder(2000).setPackageName("com.android.shell").build()
            when (call.parameterCount) {
                // Android 12+: call(AttributionSource, authority, method, arg, extras)
                5 -> call.invoke(provider, shell, authority, SelfHostProvider.METHOD_BINDER, null, extras)
                // Android 11: call(callingPkg, featureId, authority, method, arg, extras)
                6 -> call.invoke(provider, "com.android.shell", null, authority, SelfHostProvider.METHOD_BINDER, null, extras)
                else -> error("unknown IContentProvider.call signature: $call")
            }
        } finally {
            runCatching {
                am.javaClass.methods.first { it.name == "removeContentProviderExternalAsUser" }
                    .invoke(am, authority, token, 0)
            }.recoverCatching {
                am.javaClass.methods.first { it.name == "removeContentProviderExternal" }.invoke(am, authority, token)
            }
        }
    }
}
