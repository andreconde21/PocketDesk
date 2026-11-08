package dev.outsmartis.carmirror

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log

/** Receives SelfHost's binder. Only shell (2000) or root (0) may hand one over. */
class SelfHostProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) {
            Log.w(TAG, "ignored call from uid $uid")
            return null
        }
        if (method != METHOD_BINDER) return null
        val binder = extras?.getBinder(EXTRA_BINDER) ?: return null
        ShizukuBridge.offerSelfHost(binder)
        return Bundle()
    }

    override fun onCreate() = true
    override fun query(u: android.net.Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?) = null
    override fun getType(u: android.net.Uri): String? = null
    override fun insert(u: android.net.Uri, v: ContentValues?) = null
    override fun delete(u: android.net.Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: android.net.Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

    companion object {
        private const val TAG = "PocketDeskHost"
        const val METHOD_BINDER = "binder"
        const val EXTRA_BINDER = "binder"
        fun authority(ctx: Context) = "${ctx.packageName}.selfhost"
    }
}
