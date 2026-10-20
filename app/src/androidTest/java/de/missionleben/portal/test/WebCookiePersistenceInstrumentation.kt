package de.missionleben.portal.test

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.security.PersonalSessionLockTracker
import de.missionleben.portal.web.PortalBrowserActivity
import de.missionleben.portal.web.WebSessionCookies
import de.missionleben.portal.web.WebSessionDiagnostics
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Two separate invocations with an intervening force-stop test the REAL Android cookie store.
 * Emulator + isolated debug/example.invalid only. No real login, cookie or account is accepted.
 */
class WebCookiePersistenceInstrumentation : Instrumentation() {
    private var phase = ""

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        phase = arguments?.getString("phase").orEmpty()
        start()
    }

    override fun onStart() {
        val results = JSONObject()
        var success = false
        try {
            require(BuildConfig.DEBUG && targetContext.packageName.endsWith(".debug"))
            require(BuildConfig.AUTHENTIK_BASE_URL == BASE)
            require(BuildConfig.OIDC_ISSUER.startsWith("$BASE/"))
            require(android.os.Build.HARDWARE == "ranchu" || android.os.Build.FINGERPRINT.contains("generic"))
            require(phase in setOf("seed", "reopen", "verify-cleared"))
            // Initialize the actual provider on the main thread, not a Robolectric shadow.
            runOnMainSync { WebView(targetContext).destroy() }
            val cookies = CookieManager.getInstance()
            when (phase) {
                "seed" -> {
                    awaitCallback { done -> cookies.removeAllCookies { done() } }
                    awaitCallback { done -> cookies.setCookie(BASE,
                        "authentik_session=synthetic-persistence-only; Secure; HttpOnly; SameSite=Lax; Path=/; Max-Age=3600") {
                        require(it); done()
                    } }
                    awaitCallback { done -> cookies.setCookie(MAIL,
                        "synthetic_mail=fixture; Secure; HttpOnly; Path=/; Max-Age=3600") { require(it); done() } }
                    runOnMainSync {
                        require(WebSessionCookies.forContext(targetContext).persistPersonal(DeviceMode.PERSONAL,
                            WebSessionDiagnostics.Event.AUTHORIZATION_RETURN, BASE) == true)
                    }
                    results.put("seed_flushed", true)
                }
                "reopen" -> {
                    // Read BEFORE any setCookie or reset operation in the NEW process.
                    require(cookies.getCookie(BASE).orEmpty().contains("authentik_session=synthetic-persistence-only"))
                    require(cookies.getCookie(MAIL).orEmpty().contains("synthetic_mail=fixture"))
                    results.put("cookies_recovered_in_new_process", true)
                    val tracker = PersonalSessionLockTracker { 1000L }
                    tracker.screenTurnedOff()
                    require(tracker.isLockRequired())
                    runOnMainSync {
                        require(WebSessionCookies.forContext(targetContext).persistPersonal(DeviceMode.PERSONAL,
                            WebSessionDiagnostics.Event.PERSONAL_PAUSE, BASE) == true)
                    }
                    require(cookies.getCookie(BASE).orEmpty().contains("authentik_session="))
                    results.put("personal_lock_preserves_cookie_store", true)
                    var resetSucceeded = false
                    awaitCallback { done -> PortalBrowserActivity.clearLoginContext(targetContext) {
                        resetSucceeded = it; done()
                    } }
                    require(resetSucceeded && !cookies.hasCookies())
                    results.put("fresh_personal_login_erases_all_web_cookies", true)
                    awaitCallback { done -> cookies.setCookie(BASE,
                        "authentik_session=synthetic-shared; Secure; Path=/; Max-Age=3600") { require(it); done() } }
                    runOnMainSync {
                        require(WebSessionCookies.forContext(targetContext).persistPersonal(DeviceMode.SHARED,
                            WebSessionDiagnostics.Event.PERSONAL_PAUSE, BASE) == null)
                    }
                    awaitCallback { done -> PortalBrowserActivity.clearLocalWebData(targetContext) { done() } }
                    require(!cookies.hasCookies())
                    results.put("shared_cleanup_erases_cookies", true)
                }
                "verify-cleared" -> {
                    require(!cookies.hasCookies())
                    results.put("erasure_survives_another_process_restart", true)
                }
            }
            results.put("phase", phase)
            results.put("real_user_web_sso", "NOT_TESTED")
            success = true
        } catch (error: Throwable) {
            // Neither exception messages/stack traces nor headers/URLs are exported.
            results.put("failure_type", error.javaClass.simpleName)
        }
        results.put("success", success)
        finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("web_cookie_checks", results.toString()) })
    }

    private fun awaitCallback(action: (() -> Unit) -> Unit) {
        val latch = CountDownLatch(1)
        runOnMainSync { action { latch.countDown() } }
        require(latch.await(10, TimeUnit.SECONDS))
    }

    companion object {
        private const val BASE = "https://example.invalid"
        private const val MAIL = "https://mail.example.invalid"
    }
}
