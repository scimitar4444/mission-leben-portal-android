package de.missionleben.portal.web

enum class TalkAppTarget { NATIVE_APP, CHROME }

object TalkAppLaunchPolicy {
    /** A broken installed Talk app is an error, not permission to route a meeting elsewhere. */
    fun target(talkInstalled: Boolean): TalkAppTarget =
        if (talkInstalled) TalkAppTarget.NATIVE_APP else TalkAppTarget.CHROME
}
