package info.xert.gecko_view_flutter.delegate

import info.xert.gecko_view_flutter.GeckoViewProxy
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError

class FlutterNavigationDelegate(
    private val tabId: Int,
    private val proxy: GeckoViewProxy
) : GeckoSession.NavigationDelegate {

    var currentUrl: String? = null
        private set


    override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGestures: Boolean) {
        currentUrl = url
        //proxy.onLocationChange(tabId, url, hasUserGestures)
        super.onLocationChange(session, url, perms, hasUserGestures)
    }


    override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
        proxy.onReceivedError(
            tabId = tabId,
            url = uri,
            category = error.category,
            code = error.code,
            message = error.message
        )
        return null
    }


//    override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
//        val url = request.uri
//        val shouldOverride = proxy.shouldOverrideUrlLoading(tabId, url)
//        return if (shouldOverride) {
//            GeckoResult.deny()
//        } else {
//            GeckoResult.allow()
//        }
//    }
}
