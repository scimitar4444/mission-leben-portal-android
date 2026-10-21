# Mission Leben announcement bridge app

This private Nextcloud integration exposes narrow HMAC-protected endpoints for the communication bridge. It resolves a single Nextcloud user, applies that user's live Nextcloud group membership, returns only matching Announcement Center entries, resolves participants for one Talk room, and reconciles the dedicated Talk notification bot against the mobile-enabled accounts exported from Authentik.

The endpoints never return group names and never accept an administrator session. The only mutating operation is an idempotent Talk bot reconciliation: the dedicated bot is enabled for every active one-to-one or closed group chat containing at least one eligible account and removed from public meeting, archived, special, or otherwise ineligible rooms. It neither changes room membership nor message content. Call events are ignored by the Bridge. A session-authenticated endpoint returns only the current user's allowed room tokens so the Android chat-only WebView can hide the excluded conversations without receiving names or messages.

Configure a dedicated random secret of at least 32 characters:

```bash
sudo -u www-data php /opt/nextcloud/occ config:system:set \
  missionleben_announcements secret --value='REPLACE_WITH_RANDOM_SECRET'
```

Then enable the app with `occ app:enable missionleben_announcements` and mount the same secret into the Bridge as `BRIDGE_NEXTCLOUD_ANNOUNCEMENTS_SECRET_FILE`. The Talk bot name defaults to `Mission Leben Hinweise` and can be overridden through the `missionleben_announcements.talk_bot_name` system setting. The integration is intentionally version-bound to the supported Nextcloud range; repeat the signed endpoint, bot reconciliation, and group-filter tests after every Nextcloud, Talk, or Announcement Center update.

## Announcement push (adapter 0.3.3, Bridge 0.10.12, Android 0.23.2)

Pilot status on 2026-10-21: adapter 0.3.3 and Bridge 0.10.12/schema 12 are
deployed. Signed catalogue/audience routes, unsigned denial, database integrity
and the no-historical-broadcast baseline are live verified. One compatible
personal handset received a narrowly queued existing announcement and fetched
its details; its existing read state correctly suppressed a repeated alert.
The following fresh publication was discovered automatically and delivered
exactly once to that handset. Its separate Android alert opened the native
bell; home expand/collapse, the directly reachable read button, removal from
home and retention as read in the bell passed on-device. Agenda independence
has synthetic rendering coverage; the handset had no visible Today panel in
this check. Multi-handset, restricted-group and network-interruption live
checks are not claimed by this pilot. OTA publication is tracked separately
from these device results; old clients remain excluded.

Two additional read-only POST endpoints use the existing HMAC secret and the
exact request path in the signature:

- `/api/v1/announcement-push/catalog`: `{}` returns at most 500 published,
  non-expired IDs, without content or group names. Overflow fails closed.
- `/api/v1/announcement-push/resolve`: `{ids, recipients}` accepts at most 100
  positive integer IDs and 100 recipients (`key`, `email`, optional `user_id`).
  Each active, unambiguous Nextcloud identity is resolved server-side. A stable
  UID takes precedence and never falls back to a different email identity if
  it is missing/disabled. Mail lookup requires an exact, unique match. Current
  Nextcloud groups plus `everyone` determine visibility; caller-supplied groups
  and unknown fields are rejected. Results contain no group names.

Map rows are created by Announcement Center only on actual publication,
including scheduled publication. No Announcement Center/Core file is patched.
The Bridge discovers changes every 15 seconds on the server, resolving only
new IDs in batches of 20 posts / 50 recipients. Idle handsets do not poll.
Delivery is not a hard real-time guarantee: healthy-source discovery and the
existing dispatcher each have a 15-second cadence; a large fleet, connectivity,
Android restrictions and user channel settings can add delay.

`BRIDGE_ANNOUNCEMENT_PUSH_ENABLED=false` is the entrypoint default. After a
coordinated adapter/Bridge rollout and device test, enable it explicitly. The
first successful catalogue is a durable no-broadcast baseline, so historical
posts do not all generate notifications. Subsequent failures retain pending
work. Stable per-post/per-subject events plus per-device delivery records and
Android deduplication prevent repeated visible alerts. Only Android >=0.23.2
receives `open_announcements`; old apps keep their existing foreground view.
The dispatch-time audience recheck is batched and isolated from the normal
mail/Talk/ticket queue; a Nextcloud timeout does not produce one timeout per
phone. Detail retrieval rechecks live visibility again. Deletes, expiry and
group revocation deny later content/delivery; an already visible Android alert
is not remotely recalled by this implementation.

Announcements use their own Android channel, not the calendar channel. As
system notices they bypass the app's communication-off/quiet-hours switch;
Android notification permission/channel controls still apply. The selected
privacy level remains in force; shared devices receive generic text only.
ntfy carries event IDs/type/revision, never announcement text or recipient data.
A tap opens the native bell after normal app authentication, not a supplied URL.
On the home screen, `Gelesen` hides the notice without deleting it from the bell;
the agenda has its own stable identity/expanded state.

Run `php tests/AnnouncementPushTest.php lib/Controller/AnnouncementPushController.php`
from this directory for the isolated PHP audience/bounds contract checks. The
test stubs have no Nextcloud bootstrap, production user or DB connection.
Also run the Bridge announcement/client/migration tests and Android home/push
tests. Real signed-route and physical-device acceptance remain separate gates.
