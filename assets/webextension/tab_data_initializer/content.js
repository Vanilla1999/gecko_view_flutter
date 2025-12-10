// content.js
(function () {
  console.log("content.js: start, this === window?", this === window);
  console.log("content.js: location", document.location && document.location.href);

  // -------- 1. tabId-флоу через background.js --------

  function handleError(error) {
    console.log(`Error: ${error}`);
  }

  const tabIdRequest = browser.runtime.sendMessage({ type: "tabId" });

  function handleTabId(message) {
    browser.runtime.sendNativeMessage("browser", {
      type: "tabId",
      value: message["tabId"]
    });
    console.log("content.js: tabId setup procedure finished!");
  }

  console.log("content.js: Triggering tabId setup procedure...");
  tabIdRequest.then(handleTabId, handleError);

  // -------- 2. Обёртка над sendNativeMessage ДЛЯ callHandler --------
  // (старый, рабочий поток для addJavaScriptHandler)

  function callNativeHandler(handlerName, argsArray) {
    console.log("content.js: callNativeHandler", handlerName, argsArray);
    return browser.runtime.sendNativeMessage("browser", {
      type: "callHandler",
      handlerName: handlerName,
      args: argsArray || []
    });
  }

  // -------- 3. Мост страница ↔ native ДЛЯ callHandler --------

  window.addEventListener("message", function (event) {
    const msg = event.data;
    if (!msg || msg.__from !== "bridge-page") return;
    if (msg.type !== "callHandler") return;

    const { handlerName, args, callId } = msg;
    callNativeHandler(handlerName, args).then(
      result => {
        window.postMessage(
          {
            __from: "bridge-content",
            handlerName,
            callId,
            success: true,
            result: result
          },
          "*"
        );
      },
      error => {
        window.postMessage(
          {
            __from: "bridge-content",
            handlerName,
            callId,
            success: false,
            errorMessage: String(error)
          },
          "*"
        );
      }
    );
  });

  // -------- 4. Мост ДЛЯ сканера (остаётся новый поток) --------

  window.addEventListener("message", function (event) {
    const msg = event.data;
    if (!msg || msg.__from !== "bridge-native") return;

    console.log("content.js: bridge-native message", msg);

    window.postMessage(
      {
        __from: "bridge-page-command",
        type: "scan",
        payload: msg.payload
      },
      "*"
    );
  });

  // -------- 5. Инжектим bridge.js в страницу --------

  try {
    var code = `
      (function () {
        // ----- flutter_inappwebview мост (старый протокол callHandler) -----

        if (window.flutter_inappwebview && !window.flutter_inappwebview.__isPolyfill) {
          console.log("bridge.js: real flutter_inappwebview already defined");
        } else {
          console.log("bridge.js: injecting flutter_inappwebview, this === window?", this === window);

          window.flutter_inappwebview = {
            callHandler: function (handlerName, ...args) {
              console.log("bridge.js: callHandler called", handlerName, args);
              return new Promise(function (resolve, reject) {
                const callId = Math.random().toString(36).substr(2, 9);

                function onMsg(event) {
                  const msg = event.data;
                  if (!msg || msg.__from !== "bridge-content") return;
                  if (msg.handlerName !== handlerName || msg.callId !== callId) return;

                  window.removeEventListener("message", onMsg);
                  if (msg.success) {
                    resolve(msg.result);
                  } else {
                    reject(new Error(msg.errorMessage || "Error from native"));
                  }
                }

                window.addEventListener("message", onMsg);

                window.postMessage(
                  {
                    __from: "bridge-page",
                    type: "callHandler",
                    handlerName: handlerName,
                    args: args,
                    callId: callId
                  },
                  "*"
                );
              });
            }
          };

          try {
            const ev = new Event("flutterInAppWebViewPlatformReady");
            window.dispatchEvent(ev);
            console.log("bridge.js: flutterInAppWebViewPlatformReady dispatched");
          } catch (e) {
            console.error("bridge.js: dispatch event error", e);
          }
        }

        // ----- 6. Сканерный мост: команда → tsdScanEvent -----

        window.addEventListener("message", function (event) {
          const msg = event.data;
          if (!msg || msg.__from !== "bridge-page-command") return;
          if (msg.type !== "scan") return;

          try {
            const payload = msg.payload || {};
            const barcode = String(payload.barcode || "");
            console.log("bridge.js: scan command, barcode =", barcode);

            const detail = { barcode: barcode };
            window.dispatchEvent(
              new CustomEvent("tsdScanEvent", { detail: detail })
            );
            console.log("bridge.js: tsdScanEvent dispatched", detail);
          } catch (e) {
            console.error("bridge.js: error while dispatching tsdScanEvent", e);
          }
        });
      })();
    `;

    var s = document.createElement("script");
    s.type = "text/javascript";
    s.textContent = code;
    (document.head || document.documentElement).appendChild(s);
    s.parentNode.removeChild(s);

    console.log("content.js: bridge script injected into page");
  } catch (e) {
    console.error("content.js: failed to inject bridge script", e);
  }
})();
