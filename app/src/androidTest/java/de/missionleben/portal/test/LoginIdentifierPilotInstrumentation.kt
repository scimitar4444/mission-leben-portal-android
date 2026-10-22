package de.missionleben.portal.test

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.webkit.WebView
import androidx.lifecycle.ViewModelProvider
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.MainActivity
import de.missionleben.portal.MainViewModel
import de.missionleben.portal.data.AppPreferences
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState
import de.missionleben.portal.web.PortalBrowserActivity
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit production-signed local pilot. Input-only; no credential or authentication-state reads/writes. */
class LoginIdentifierPilotInstrumentation : Instrumentation() {
    private var approved = false
    private val result = JSONObject()
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        approved = arguments?.getString("input_only_pilot") == "USER_PRESENT_NO_SUBMIT"
        start()
    }

    override fun onStart() {
        var browser: PortalBrowserActivity? = null
        var success = false
        try {
            require(approved && !BuildConfig.DEBUG && BuildConfig.VERSION_NAME.endsWith("-local"))
            require(targetContext.packageName == "de.missionleben.portal" &&
                BuildConfig.AUTHENTIK_BASE_URL == "https://id.mission-leben.de")
            val prefs = AppPreferences(targetContext)
            require(prefs.deviceMode == DeviceMode.PERSONAL && prefs.enrollmentState == EnrollmentState.TRUSTED)
            val deviceBefore = prefs.deviceId
            val subjectBefore = prefs.boundPersonalSubject
            val main = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            lateinit var model: MainViewModel
            runOnMainSync { model = ViewModelProvider(main)[MainViewModel::class.java] }
            result.put("current_check", "waiting_for_user_biometry")
            val unlockUntil = SystemClock.elapsedRealtime() + 180_000L
            while (!model.uiState.value.signedIn && SystemClock.elapsedRealtime() < unlockUntil) SystemClock.sleep(250)
            require(model.uiState.value.signedIn && model.uiState.value.quickUnlockEnabled)
            result.put("human_unlocked_existing_personal_session", true)
            // A display-only flow URL: no OIDC request or login-context clearing.
            // The redirect marker only prevents treating this intentional login page as expired app content.
            // No authorizationIntent(), password/TOTP, cookie/token APIs or factor shortcuts are used.
            browser = startActivitySync(Intent(targetContext, PortalBrowserActivity::class.java)
                .putExtra("url", "https://id.mission-leben.de/if/flow/mission-leben-android-authentication/")
                .putExtra("title", "Lokaler Eingabetest – keine Anmeldung")
                .putExtra("redirect_uri", BuildConfig.OIDC_REDIRECT_URI)
                .putExtra("device_mode", DeviceMode.PERSONAL.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PortalBrowserActivity
            lateinit var web: WebView
            runOnMainSync {
                web = PortalBrowserActivity::class.java.getDeclaredField("webView").apply { isAccessible = true }
                    .get(browser) as WebView
            }
            val until = SystemClock.elapsedRealtime() + 60_000L
            result.put("current_check", "waiting_for_real_identification_dom")
            while (evaluate(web, READY) != "true" && SystemClock.elapsedRealtime() < until) SystemClock.sleep(300)
            require(evaluate(web, READY) == "true")
            require(targetContext.getSystemService(PowerManager::class.java).isInteractive)
            val checks = JSONObject(JSONTokener(evaluate(web, CHECKS)).nextValue() as String)
            result.put("real_authentik_shadow_dom_input_checks", checks)
            require(checks.getBoolean("passed"))
            require(prefs.deviceId == deviceBefore && prefs.boundPersonalSubject == subjectBefore &&
                prefs.enrollmentState == EnrollmentState.TRUSTED && model.uiState.value.signedIn)
            result.put("existing_registration_and_user_session_retained", true)
            result.put("real_authentication_or_denied_device_test", "NOT_PERFORMED")
            success = true
        } catch (error: Throwable) {
            result.put("failure_type", error.javaClass.simpleName)
        } finally {
            browser?.let { runOnMainSync { it.finish() } }
        }
        result.put("success", success)
        finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("login_identifier_checks", result.toString()) })
    }

    private fun evaluate(web: WebView, script: String): String {
        val done = CountDownLatch(1)
        var value = ""
        runOnMainSync { web.evaluateJavascript(script) { value = it; done.countDown() } }
        require(done.await(12, TimeUnit.SECONDS))
        return value
    }

    companion object {
        private const val FIND = """
            const find = root => {
              for (const el of root.querySelectorAll('*')) {
                if (el.tagName === 'AK-STAGE-IDENTIFICATION' &&
                    el.shadowRoot?.querySelector('input[name="uidField"]')) return el;
                if (el.shadowRoot) { const found = find(el.shadowRoot); if (found) return found; }
              }
              return null;
            };
        """
        private const val READY = "(() => {" + FIND +
            "return !!window.__mlLoginIdentifier && !!find(document);})()"
        private const val CHECKS = "(() => {" + FIND + """
            const root = find(document).shadowRoot;
            const field = root.querySelector('input[name="uidField"]');
            const form = root.querySelector('form');
            const original = field.value;
            let fallback = 0;
            const noSubmit = event => { fallback++; event.preventDefault(); event.stopImmediatePropagation(); };
            // Even a failing product guard can never submit this test form.
            form.addEventListener('submit', noSubmit, true);
            const out = {};
            const check = (yes, name) => { if (!yes) throw new Error(name); out[name] = true; };
            try {
              for (const [value, expected, key] of [
                ['@person.example','person.example','leading_at'],
                ['person.example@mission-leben.de','person.example','company_email']]) {
                field.value = value; field.dispatchEvent(new Event('input', {bubbles:true,composed:true}));
                check(field.validity.customError, key + '_explained');
                const event = new Event('submit', {bubbles:true,cancelable:true});
                form.dispatchEvent(event);
                check(event.defaultPrevented && fallback === 0, key + '_blocked_before_server');
                const button = field.nextElementSibling?.querySelector('button');
                check(button && !button.hidden && button.type === 'button', key + '_suggestion_visible');
                button.click();
                check(field.value === expected && !field.validity.customError, key + '_explicit_correction');
              }
              field.value = 'person.example@foreign.invalid';
              field.dispatchEvent(new Event('input', {bubbles:true,composed:true}));
              check(field.validity.customError && field.nextElementSibling.querySelector('button').hidden,
                'foreign_domain_not_guessed');
              for (const value of ['quarkholz','123456']) {
                field.value = value; field.dispatchEvent(new Event('input', {bubbles:true,composed:true}));
                check(!field.validity.customError && field.value === value, 'no_fake_identity_validation_' + value);
              }
              check(fallback === 0, 'no_test_form_reached_authentik_submit_handler');
              out.passed = true;
            } catch (_) { out.passed = false; }
            finally {
              field.value = original;
              field.dispatchEvent(new Event('input', {bubbles:true,composed:true}));
              // Keep the safety listener until the display-only Activity closes.
            }
            return JSON.stringify(out);
        """ + "})()"
    }
}
