'use strict';

function handleContentMessage(request, sender, sendResponse) {
  if (request && request.type === "tabId") {
    sendResponse({ tabId: sender.tab.id });
  }
}

browser.runtime.onMessage.addListener(handleContentMessage);
