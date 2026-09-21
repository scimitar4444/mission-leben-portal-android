# Mission Leben announcement bridge app

This private Nextcloud integration exposes narrow HMAC-protected endpoints for the communication bridge. It resolves a single Nextcloud user, applies that user's live Nextcloud group membership, returns only matching Announcement Center entries, resolves participants for one Talk room, and reconciles the dedicated Talk notification bot against the mobile-enabled accounts exported from Authentik.

The endpoints never return group names and never accept an administrator session. The only mutating operation is an idempotent Talk bot reconciliation: the dedicated bot is enabled for every active one-to-one or closed group chat containing at least one eligible account and removed from public meeting, archived, special, or otherwise ineligible rooms. It neither changes room membership nor message content. Call events are ignored by the Bridge. A session-authenticated endpoint returns only the current user's allowed room tokens so the Android chat-only WebView can hide the excluded conversations without receiving names or messages.

Configure a dedicated random secret of at least 32 characters:

```bash
sudo -u www-data php /opt/nextcloud/occ config:system:set \
  missionleben_announcements secret --value='REPLACE_WITH_RANDOM_SECRET'
```

Then enable the app with `occ app:enable missionleben_announcements` and mount the same secret into the Bridge as `BRIDGE_NEXTCLOUD_ANNOUNCEMENTS_SECRET_FILE`. The Talk bot name defaults to `Mission Leben Hinweise` and can be overridden through the `missionleben_announcements.talk_bot_name` system setting. The integration is intentionally version-bound to the supported Nextcloud range; repeat the signed endpoint, bot reconciliation, and group-filter tests after every Nextcloud, Talk, or Announcement Center update.
