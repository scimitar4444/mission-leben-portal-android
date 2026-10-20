package de.missionleben.portal.web

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import androidx.webkit.WebViewCompat
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.web.WebSessionDiagnostics.CookiePresence
import de.missionleben.portal.web.WebSessionDiagnostics.Event

/** No native tokens, cookie copies or synthetic authentication. Flush only the existing store. */
internal class WebSessionCookies(
    private val authentikBaseUrl: String,
    private val readCookieHeader: (String) -> String?,
    private val flush: () -> Unit,
    private val webViewVersion: () -> String?,
    private val emit: (String) -> Unit,
) {
    fun record(event: Event, url: String? = authentikBaseUrl) {
        val presence = runCatching { WebSessionDiagnostics.cookiePresence(readCookieHeader(authentikBaseUrl)) }
            .getOrDefault(CookiePresence.UNAVAILABLE)
        val version = runCatching(webViewVersion).getOrNull()
        emit(WebSessionDiagnostics.line(event, presence, version, url))
    }

    fun persistPersonal(mode: DeviceMode?, event: Event, url: String?): Boolean? {
        if (mode != DeviceMode.PERSONAL) return null
        record(event, url)
        return persist(url)
    }

    /** Also used after intentional erasure: persist the deletion, never restore a saved header. */
    fun persist(url: String? = authentikBaseUrl): Boolean {
        record(Event.PERSIST_STARTED, url)
        val completed = runCatching(flush).isSuccess
        record(if (completed) Event.PERSIST_COMPLETED else Event.PERSIST_FAILED, url)
        return completed
    }

    companion object {
        fun forContext(context: Context): WebSessionCookies = WebSessionCookies(
            authentikBaseUrl = BuildConfig.AUTHENTIK_BASE_URL,
            readCookieHeader = { CookieManager.getInstance().getCookie(it) },
            flush = { CookieManager.getInstance().flush() },
            webViewVersion = { WebViewCompat.getCurrentWebViewPackage(context)?.versionName },
            emit = { Log.i("MissionLebenWebSession", it) },
        )
    }
}
