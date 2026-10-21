package de.missionleben.portal.model

object AnnouncementHistoryPolicy {
    const val RETENTION_SECONDS = 14L * 24 * 60 * 60

    fun isArchived(item: AnnouncementItem, now: Long = System.currentTimeMillis() / 1000): Boolean =
        item.archived || (item.expiresAtEpochSeconds > 0 && item.expiresAtEpochSeconds <= now)

    fun isActive(item: AnnouncementItem, now: Long = System.currentTimeMillis() / 1000): Boolean =
        !isArchived(item, now)

    /** Authoritative, newly authorized server history only; no local content cache. */
    fun visible(items: List<AnnouncementItem>, now: Long = System.currentTimeMillis() / 1000): List<AnnouncementItem> =
        items.filter { it.id > 0 && it.publishedAtEpochSeconds in 1..now &&
            (!isArchived(it, now) || now - it.publishedAtEpochSeconds < RETENTION_SECONDS) }
            .distinctBy { it.id }.sortedByDescending { it.publishedAtEpochSeconds }.take(500)
}
