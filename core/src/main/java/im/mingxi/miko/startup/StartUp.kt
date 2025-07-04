package im.mingxi.miko.startup

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import com.tencent.mmkv.MMKV
import im.mingxi.core.R
import im.mingxi.loader.HookInit
import im.mingxi.loader.XposedPackage
import im.mingxi.loader.bridge.XPBridge
import im.mingxi.loader.bridge.XPBridge.HookParam
import im.mingxi.loader.hotpatch.HotPatch.hotPatchAPKPath
import im.mingxi.loader.util.Constants
import im.mingxi.loader.util.PathUtil
import im.mingxi.miko.proxy.ActivityProxyManager
import im.mingxi.miko.startup.HookInstaller.scanAndInstall
import im.mingxi.miko.util.HookEnv
import im.mingxi.miko.util.HybridClassLoader
import im.mingxi.miko.util.Reflex
import im.mingxi.miko.util.dexkit.NativeLoader
import im.mingxi.net.Beans
import im.mingxi.net.bean.ModuleInfo
import java.io.File
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean


object StartUp {
    private val isMMKVInit: AtomicBoolean = AtomicBoolean()
    private val isActInit: AtomicBoolean = AtomicBoolean()

    @JvmField
    var hostType: Int = -1

    @JvmStatic
    fun doLoad() {
        val startTime = System.currentTimeMillis()
        HookEnv.moduleClassLoader = StartUp::class.java.classLoader as ClassLoader
        Reflex.setHostClassLoader(XposedPackage.classLoader)
        HybridClassLoader.setLoaderParentClassLoader(StartUp::class.java.classLoader!!)
        var appClass: Class<*>?
        val mmClass = Reflex.loadClass("com.tencent.mm.app.Application")
        val mobileqqClass = Reflex.loadClass("com.tencent.mobileqq.qfix.QFixApplication")
        appClass = if (mobileqqClass != null) {
            hostType = 2
            mobileqqClass
        } else {
            hostType = 1
            mmClass
        }

        if (appClass == null) // 可能也许大概应该用的到这段
            appClass = Class.forName("android.app.Application")


        // hook android.app.Application.attachBaseContext
        XPBridge.hookAfter(
            Reflex.findMethod(appClass).setMethodName("attachBaseContext").get()
        ) { param: HookParam ->
            val context = param.args[0] as Context
            HookEnv.hostContext = context
            HookEnv.hostApplication = param.thisObject as Application
            ResStartUp.doLoad(context) // 重复注入资源防止部分免root框架注入资源异常
            ActivityProxyManager.initActivityProxyManager(
                context,
                if (Constants.isHotPatch) hotPatchAPKPath else PathUtil.moduleApkPath,
                R.string.app_name
            )
            if (!isMMKVInit.getAndSet(true)) initializeMMKV(
                context
            )
        }

        /*
         * To prevent the framework from passing the wrong class loader,
         *  we use {@link #getClassLoader()} to get the class loader.
         */
        XPBridge.hookAfter(
            Reflex.findMethod(Activity::class.java).setMethodName("onResume").get()
        ) { param: HookParam ->
            val activity = param.thisObject as Activity
            if (activity != null) {
            HookEnv.hostActivity = activity
            ResStartUp.doLoad(activity) // 重复注入资源防止部分免root框架注入资源异常
            if (!isActInit.getAndSet(true)) {
                val xLoader = activity.classLoader
                if (xLoader != null) {
                    HookEnv.hostClassLoader = xLoader
                    Reflex.setHostClassLoader(xLoader)
                    HybridClassLoader.hostClassLoader = xLoader
                    injectClassLoader()
                    registerModuleInfo()
                    scanAndInstall()
                    XPBridge.log("模块装载完成（costTime = ${System.currentTimeMillis() - startTime}）")
                }
            }
        }
        }
    }

    private fun initializeMMKV(ctx: Context) {
        // 由于Miko的hotPatch基于dexClassLoader并且没有传入library参数，所以必须提前加载libmmkv.so
        // 防止mmkv#initialize自载造成闪退
        NativeLoader.loadLibrary("libmmkv.so")

        val filesDir = ctx.filesDir
        val mmkvDir = File(filesDir, "Miko_MMKV")
        if (!mmkvDir.exists()) {
            mmkvDir.mkdirs()
        }
        // MMKV requires a ".tmp" cache directory, we have to create it manually
        val cacheDir = File(mmkvDir, ".tmp")
        if (!cacheDir.exists()) {
            cacheDir.mkdir()
        }
        MMKV.initialize(ctx, mmkvDir.absolutePath)
        MMKV.mmkvWithID("global_config", MMKV.MULTI_PROCESS_MODE)
        MMKV.mmkvWithID("global_cache", MMKV.MULTI_PROCESS_MODE)
    }

    private fun registerModuleInfo() {
        if (Beans.containsBean(ModuleInfo::class.java)) return
        val versionNameField =
            Class.forName("im.mingxi.miko.BuildConfig").getDeclaredField("VERSION_NAME")
        val versionCodeField =
            Class.forName("im.mingxi.miko.BuildConfig").getDeclaredField("VERSION_CODE")
        versionNameField.isAccessible = true
        versionCodeField.isAccessible = true
        val versionName = versionNameField.get(null) as String
        val versionCode = versionCodeField.get(null) as Int
        Beans.registerBean(ModuleInfo(versionName, versionCode))
    }

    @SuppressLint("DiscouragedPrivateApi")
    @Throws(Exception::class)
    private fun injectClassLoader() {
            val fParent: Field = ClassLoader::class.java.getDeclaredField("parent")
            fParent.isAccessible = true
            val mine = HookInit::class.java.classLoader
            var curr: ClassLoader? = fParent.get(mine) as ClassLoader
            if (curr == null) {
                curr = XPBridge::class.java.classLoader
            }
            if (curr!!.javaClass.name != HybridClassLoader::class.java.getName()) {
                HybridClassLoader.setLoaderParentClassLoader(curr)
                fParent.set(mine, HybridClassLoader.INSTANCE)
            }
    }
}