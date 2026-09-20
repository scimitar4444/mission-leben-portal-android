# Mission Leben announcement bridge app

This private Nextcloud integration exposes one HMAC-protected, read-only endpoint for the communication bridge. It resolves a single Nextcloud user, applies that user's live Nextcloud group membership, and returns only matching Announcement Center entries.

The endpoint never returns group names, never accepts an administrator session, and does not provide create, update, or delete operations.

Configure a dedicated random secret of at least 32 characters:

```bash
sudo -u www-data php /opt/nextcloud/occ config:system:set \
  missionleben_announcements secret --value='REPLACE_WITH_RANDOM_SECRET'
```

Then enable the app with `occ app:enable missionleben_announcements` and mount the same secret into the Bridge as `BRIDGE_NEXTCLOUD_ANNOUNCEMENTS_SECRET_FILE`. The integration is intentionally version-bound to the supported Nextcloud range; repeat the signed endpoint and group-filter tests after every Nextcloud or Announcement Center update.
