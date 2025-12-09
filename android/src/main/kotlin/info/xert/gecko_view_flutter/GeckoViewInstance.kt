package info.xert.gecko_view_flutter

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import info.xert.gecko_view_flutter.common.FindDirection
import info.xert.gecko_view_flutter.common.Offset
import info.xert.gecko_view_flutter.common.Position
import info.xert.gecko_view_flutter.common.ResultConsumer
import info.xert.gecko_view_flutter.common.FindRequest
import info.xert.gecko_view_flutter.common.FindResult
import info.xert.gecko_view_flutter.delegate.FlutterPromptDelegate
import info.xert.gecko_view_flutter.delegate.FlutterNavigationDelegate
import info.xert.gecko_view_flutter.delegate.FlutterScrollDelegate
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController.SCROLL_BEHAVIOR_AUTO
import org.mozilla.geckoview.PanZoomController.SCROLL_BEHAVIOR_SMOOTH
import org.mozilla.geckoview.ScreenLength
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtension.MessageDelegate

internal class GeckoViewInstance(
    context: Context,
    private val runtimeController: GeckoRuntimeController,
    private val proxy: GeckoViewProxy
) {

    private val TAG: String = GeckoViewInstance::class.java.name

    val view: GeckoView

    data class SessionWrapper(val session: GeckoSession, var tabId: Int?)

    private val sessions: MutableMap<Int, SessionWrapper> = HashMap()

    init {
        Log.d(TAG, "Initializing GeckoView")
        view = GeckoView(context)
    }

    fun init() {
    }

    fun createTab(tabId: Int) {
        if (sessions.containsKey(tabId)) {
            throw InternalError("Internal tab id reused")
        }

        val session = GeckoSession()
        sessions[tabId] = SessionWrapper(session, null)

        session.promptDelegate = FlutterPromptDelegate(proxy)
        session.scrollDelegate = FlutterScrollDelegate()
        session.navigationDelegate = FlutterNavigationDelegate()

        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int>? {
                return when (perm.permission) {
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                    GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION ->
                        GeckoResult.fromValue(
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                        )

                    else -> super.onContentPermissionRequest(session, perm)
                }
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCrash(session: GeckoSession) {
                handleSessionCrash(tabId)
            }

            override fun onKill(session: GeckoSession) {
                handleSessionKill(tabId)
            }
        }

        if (runtimeController.tabDataInitializer.enabled) {
            val extension = runtimeController.tabDataInitializer.extension
            if (extension != null) {
                Handler(Looper.getMainLooper()).post {
                    session.webExtensionController.setMessageDelegate(
                        extension,
                        object : MessageDelegate {
                            override fun onMessage(
                                nativeApp: String,
                                message: Any,
                                sender: WebExtension.MessageSender
                            ): GeckoResult<Any>? {
                                val sessionWrapper = sessions[tabId] ?: return null
                                val jsonMessage = message as? JSONObject ?: return null

                                val type = jsonMessage.optString("type", "")
                                Log.d(TAG, "onMessage type=$type tabId=$tabId")

                                when (type) {
                                    "tabId" -> {
                                        if (sessionWrapper.tabId == null) {
                                            val internalId = jsonMessage.optInt("value")
                                            sessionWrapper.tabId = internalId
                                            Log.d(TAG, "Internal browserTabId set: $internalId for tabId=$tabId")
                                        }
                                    }

                                    "callHandler" -> {
                                        Log.d(TAG, "callHandler from JS for tabId=$tabId")

                                        val handlerName = jsonMessage.getString("handlerName")
                                        val argsJson = jsonMessage.optJSONArray("args")

                                        val argsList = mutableListOf<Any?>()
                                        if (argsJson != null) {
                                            for (i in 0 until argsJson.length()) {
                                                argsList.add(argsJson.get(i))
                                            }
                                        }

                                        val result = GeckoResult<Any>()
                                        Handler(Looper.getMainLooper()).post {
                                            proxy.invokeJsHandler(
                                                tabId,
                                                handlerName,
                                                argsList
                                            ) { success, valueResult, errorCode, errorMessage ->
                                                if (success) {
                                                    result.complete(valueResult)
                                                } else {
                                                    result.completeExceptionally(
                                                        Exception(errorMessage ?: errorCode ?: "Unknown error")
                                                    )
                                                }
                                            }
                                        }
                                        return result
                                    }

                                }

                                return null
                            }
                        },
                        "browser"
                    )
                }
            }
        }
        session.open(runtimeController.getRuntime())
    }

    fun getActiveTabId(): Int? {
        val tabEntry = sessions.entries.firstOrNull { entry -> entry.value.session == view.session }
        return tabEntry?.key
    }

    private fun getSessionByTabId(tabId: Int): GeckoSession {
        return sessions[tabId]?.session ?: throw InternalError("Tab does not exist")
    }

    private fun getInternalTabIdByTabId(tabId: Int): Int? {
        return sessions[tabId]?.tabId ?: throw InternalError("Tab does not exist")
    }

    fun isTabActive(tabId: Int): Boolean {
        val session = getSessionByTabId(tabId)
        return session == view.session
    }

    fun activateTab(tabId: Int) {
        val session = getSessionByTabId(tabId)
        view.setSession(session)
    }

    fun currentUrl(tabId: Int): String? {
        val session = getSessionByTabId(tabId)
        val navigation = session.navigationDelegate
        if (navigation is FlutterNavigationDelegate) {
            return navigation.currentUrl
        } else {
            throw InternalError("Invalid session")
        }
    }

    fun getUserAgent(tabId: Int): String? {
        val session = getSessionByTabId(tabId)
        return session.userAgent.poll()
    }

    fun openURI(tabId: Int, uri: String) {
        val session = getSessionByTabId(tabId)
        session.loadUri(uri)
    }

    fun reload(tabId: Int) {
        val session = getSessionByTabId(tabId)
        session.reload()
    }

    fun goBack(tabId: Int) {
        val session = getSessionByTabId(tabId)
        session.goBack()
    }

    fun goForward(tabId: Int) {
        val session = getSessionByTabId(tabId)
        session.goForward()
    }

    fun getScrollOffset(tabId: Int): Offset {
        val session = getSessionByTabId(tabId)
        val scroll = session.scrollDelegate
        if (scroll is FlutterScrollDelegate) {
            return scroll.scrollOffset
        } else {
            throw InternalError("Invalid session")
        }
    }

    fun scrollToBottom(tabId: Int) {
        val session = getSessionByTabId(tabId)
        session.panZoomController.scrollToBottom()
    }

    fun scrollToTop(tabId: Int) {
        val session = getSessionByTabId(tabId)
        session.panZoomController.scrollToTop()
    }

    fun scrollBy(tabId: Int, offset: Offset, smooth: Boolean) {
        val session = getSessionByTabId(tabId)
        session.panZoomController.scrollBy(
            ScreenLength.fromPixels(offset.x.toDouble()),
            ScreenLength.fromPixels(offset.y.toDouble()),
            if (smooth) SCROLL_BEHAVIOR_SMOOTH else SCROLL_BEHAVIOR_AUTO
        )
    }

    fun scrollTo(tabId: Int, position: Position, smooth: Boolean) {
        val session = getSessionByTabId(tabId)
        session.panZoomController.scrollTo(
            ScreenLength.fromPixels(position.x.toDouble()),
            ScreenLength.fromPixels(position.y.toDouble()),
            if (smooth) SCROLL_BEHAVIOR_SMOOTH else SCROLL_BEHAVIOR_AUTO
        )
    }

    fun runJsAsync(tabId: Int, script: String) {
        val browserTabId = getInternalTabIdByTabId(tabId)
            ?: throw InternalError("Invalid session state! TabId not initialized")
        runtimeController.hostJsExecutor.runAsync(script, browserTabId)
    }

    fun findNext(tabId: Int, request: FindRequest, callback: ResultConsumer<FindResult>) {
        val session = getSessionByTabId(tabId)
        val finder = session.finder

        var searchFlags: Int = 0
        if (request.direction == FindDirection.BACKWARDS) {
            searchFlags = searchFlags or GeckoSession.FINDER_FIND_BACKWARDS
        }
        if (request.matchCase) {
            searchFlags = searchFlags or GeckoSession.FINDER_FIND_MATCH_CASE
        }
        if (request.wholeWord) {
            searchFlags = searchFlags or GeckoSession.FINDER_FIND_WHOLE_WORD
        }
        if (request.linksOnly) {
            searchFlags = searchFlags or GeckoSession.FINDER_FIND_LINKS_ONLY
        }

        var displayFlags: Int = 0
        if (request.highlightAll) {
            displayFlags = displayFlags or GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL
        }
        if (request.dimPage) {
            displayFlags = displayFlags or GeckoSession.FINDER_DISPLAY_DIM_PAGE
        }
        if (request.drawLinkOutline) {
            displayFlags = displayFlags or GeckoSession.FINDER_DISPLAY_DRAW_LINK_OUTLINE
        }

        finder.displayFlags = displayFlags
        finder.find(request.searchString, searchFlags).accept(
            { result ->
                if (result != null) {
                    callback.success(
                        FindResult(
                            result.found,
                            result.current,
                            result.linkUri.orEmpty(),
                            result.total,
                            result.wrapped
                        )
                    )
                } else {
                    callback.success(
                        FindResult(
                            false, 0, "", 0, false
                        )
                    )
                }
            },
            { e ->
                Log.e(TAG, "Failed to perform find", e)
                callback.error(TAG, "Failed to perform find", e)
            }
        )
    }

    fun findClear(tabId: Int) {
        val session = getSessionByTabId(tabId)
        val finder = session.finder
        finder.clear()
    }

    fun closeTab(tabId: Int) {
        val session = getSessionByTabId(tabId)
        session.close()
        sessions.remove(tabId)
    }

    private fun handleSessionCrash(tabId: Int) {
        try {
            val url = currentUrl(tabId)
            closeTab(tabId)
            createTab(tabId)
            activateTab(tabId)
            if (url != null) {
                openURI(tabId, url)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to recover from crash in tab $tabId: ${e.message}")
        }
    }

    private fun handleSessionKill(tabId: Int) {
        try {
            val url = currentUrl(tabId)
            closeTab(tabId)
            createTab(tabId)
            activateTab(tabId)
            if (url != null) {
                openURI(tabId, url)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to recover from kill in tab $tabId: ${e.message}")
        }
    }

    fun dispose() {
        sessions.forEach { (_, sessionWrapper) ->
            sessionWrapper.session.close()
        }
        sessions.clear()
    }

    companion object {
        // JS-мост, инжектится в каждую страницу
        val injectBridgeScript = """
(function () {
  var code = `
    (function () {
      if (window.flutter_inappwebview && !window.flutter_inappwebview.__isPolyfill) {
        console.log("bridge.js: real flutter_inappwebview already defined");
        return;
      }

      console.log("bridge.js: injecting flutter_inappwebview, this === window?", this === window);

      if (!window.flutter_inappwebview) {
        const earlyCalls = [];
        window.flutter_inappwebview = {
          __isPolyfill: true,
          __earlyCalls: earlyCalls,
          callHandler: function (handlerName, ...args) {
            return new Promise(function (resolve, reject) {
              earlyCalls.push({ handlerName, args, resolve, reject });
            });
          }
        };
        console.log("bridge.js: polyfill installed");
      }

      const old = window.flutter_inappwebview;
      const earlyCalls = Array.isArray(old.__earlyCalls) ? old.__earlyCalls : [];
      const pending = {};

      function sendToNative(msg) {
        try {
          browser.runtime.sendNativeMessage("browser", msg);
        } catch (e) {
          console.error("bridge.js sendToNative error", e);
        }
      }

      window.flutter_inappwebview = {
        callHandler: function (handlerName, ...args) {
          return new Promise(function (resolve, reject) {
            const callId = Math.random().toString(36).substr(2, 9);
            pending[callId] = { resolve, reject };
            sendToNative({
              type: "callHandler",
              handlerName: handlerName,
              args: args,
              callId: callId
            });
          });
        }
      };

      if (earlyCalls.length > 0) {
        console.log("bridge.js: replaying early calls", earlyCalls.length);
        earlyCalls.forEach(function (c) {
          window.flutter_inappwebview
            .callHandler(c.handlerName, ...c.args)
            .then(c.resolve)
            .catch(c.reject);
        });
        old.__earlyCalls.length = 0;
      }

      try {
        const ev = new Event("flutterInAppWebViewPlatformReady");
        window.dispatchEvent(ev);
        console.log("bridge.js: flutterInAppWebViewPlatformReady dispatched");
      } catch (e) {
        console.error("bridge.js: dispatch event error", e);
      }

      try {
        browser.runtime.onMessage.addListener(function (message, sender) {
          if (!message || !message.type || !message.callId) return;
          const entry = pending[message.callId];
          if (!entry) return;
          delete pending[message.callId];

          if (message.type === "callHandlerResult") {
            entry.resolve(message.result);
          } else if (message.type === "callHandlerError") {
            entry.reject(new Error(message.errorMessage || "Error from native"));
          }
        });
        console.log("bridge.js: onMessage listener attached");
      } catch (e) {
        console.error("bridge.js: onMessage attach error", e);
      }
    })();
  `;

  var s = document.createElement('script');
  s.type = 'text/javascript';
  s.textContent = code;
  (document.head || document.documentElement).appendChild(s);
  s.parentNode.removeChild(s);
})();
""".trimIndent()

    }
}
