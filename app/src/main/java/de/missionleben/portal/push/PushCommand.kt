package de.missionleben.portal.push

sealed interface PushCommand {
    data class Legacy(val action: PushAction) : PushCommand
    data class Fetch(val eventId: String, val eventType: PushAction, val revision: String) : PushCommand
    data class Cancel(val eventId: String) : PushCommand

    companion object {
        fun parse(data: Map<String, String>): PushCommand? {
            return when (val actionName = data["action"]) {
                "fetch_notification" -> {
                    val eventId = data["event_id"]?.takeIf(::validEventId) ?: return null
                    val eventType = PushAction.fromWireName(data["event_type"])
                        ?.takeUnless { it == PushAction.REFRESH_SECURITY_STATE }
                        ?: return null
                    val revision = data["revision"].orEmpty().take(64)
                    Fetch(eventId, eventType, revision)
                }

                "cancel_notification" -> {
                    val eventId = data["event_id"]?.takeIf(::validEventId) ?: return null
                    Cancel(eventId)
                }

                else -> PushAction.fromWireName(actionName)?.let(::Legacy)
            }
        }

        fun validEventId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{16,128}"))
    }
}
