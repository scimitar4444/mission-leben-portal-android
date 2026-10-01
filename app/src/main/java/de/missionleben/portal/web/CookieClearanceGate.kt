package de.missionleben.portal.web

/** A timeout is failure, not permission to load a login page with old SSO cookies. */
class CookieClearanceGate(private val finish: (Boolean) -> Unit) {
    private var completed = false

    @Synchronized fun acknowledged(cookiesRemain: Boolean) = complete(!cookiesRemain)
    @Synchronized fun failed() = complete(false)

    private fun complete(success: Boolean) {
        if (completed) return
        completed = true
        finish(success)
    }
}
