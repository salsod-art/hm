package com.rootboard

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val TAG = "RootBoard"
        // Packages to skip — system processes where hooking AudioRecord is pointless or risky
        private val SKIP_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",  // Don't skip phone — we DO want calls
            "de.robv.android.xposed.installer",
            "org.lsposed.manager"
        )
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        Log.i(TAG, "RootBoard Xposed module loaded in Zygote")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName

        // Skip system packages and our own package
        if (pkg in SKIP_PACKAGES || pkg == "com.rootboard") return

        // Only inject if a sound is actually playing or the module is active
        // This prevents unnecessary overhead in every app
        try {
            val playingFlag = java.io.File("/data/media/0/RootBoard/tmp/playing")
            val moduleActive = java.io.File("/data/media/0/RootBoard/module_active")

            // Install hooks if module is marked active (set by WebUI on launch)
            // OR if a sound is currently queued
            if (!moduleActive.exists() && !playingFlag.exists()) {
                return
            }

            Log.i(TAG, "Installing AudioRecord hook in: $pkg")
            AudioHook.install(lpparam.classLoader)

        } catch (e: Exception) {
            // Fail silently — better to not hook than to crash the app
            Log.w(TAG, "Hook install failed in $pkg: ${e.message}")
        }
    }
}
