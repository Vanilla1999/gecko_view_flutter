package info.xert.gecko_view_flutter

import org.mozilla.geckoview.GeckoRuntime
import android.content.Context
import info.xert.gecko_view_flutter.common.ResultConsumer
import info.xert.gecko_view_flutter.webextension.CookieManagerExtension
import info.xert.gecko_view_flutter.webextension.HostJSExecutionExtension
import info.xert.gecko_view_flutter.webextension.TabDataInitializerExtension
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterAssets
import org.mozilla.geckoview.GeckoRuntimeSettings

class GeckoRuntimeController(context: Context, private val assets: FlutterAssets) {
    val runtimeSettings = GeckoRuntimeSettings.Builder()
        .remoteDebuggingEnabled(true)   // включаем удалённую отладку
        .debugLogging(true)             // детальные логи GeckoView (опционально)
        .build()
    private val runtime: GeckoRuntime = GeckoRuntime.create(context,runtimeSettings)

    val tabDataInitializer = TabDataInitializerExtension()
    val hostJsExecutor = HostJSExecutionExtension()
    val cookieManagerExtension = CookieManagerExtension()

    fun getRuntime(): GeckoRuntime {
        return runtime
    }

    fun enableRemoteDebugging() {
        runtime.settings.setRemoteDebuggingEnabled(true)
    }

    fun enableHostJsExecution(callback: ResultConsumer<Unit>) {
        if (tabDataInitializer.enabled) {
            hostJsExecutor.enable(runtime, assets, callback)
        } else {
            tabDataInitializer.enable(runtime, assets, object: ResultConsumer<Unit> {
                override fun success(result: Unit) {
                    hostJsExecutor.enable(runtime, assets, callback)
                }

                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    callback.error(errorCode, errorMessage, errorDetails)
                }
            })
        }
    }

    fun enableCookieManager(callback: ResultConsumer<Unit>) {
        cookieManagerExtension.enable(runtime, assets, callback)
    }
}