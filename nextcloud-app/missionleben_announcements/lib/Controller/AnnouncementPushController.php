<?php

declare(strict_types=1);

namespace OCA\MissionLebenAnnouncements\Controller;

use OCA\MissionLebenAnnouncements\Service\SignedRequestVerifier;
use OCA\MissionLebenAnnouncements\Service\AnnouncementHistory;
use OCP\AppFramework\Controller;
use OCP\AppFramework\Http;
use OCP\AppFramework\Http\Attribute\NoCSRFRequired;
use OCP\AppFramework\Http\Attribute\OpenAPI;
use OCP\AppFramework\Http\Attribute\PublicPage;
use OCP\AppFramework\Http\JSONResponse;
use OCP\DB\QueryBuilder\IQueryBuilder;
use OCP\IDBConnection;
use OCP\IGroupManager;
use OCP\IRequest;
use OCP\IUser;
use OCP\IUserManager;

/** Bounded source discovery with an owned archive; never accepts caller-supplied groups. */
#[OpenAPI(scope: OpenAPI::SCOPE_IGNORE)]
class AnnouncementPushController extends Controller {
    private const ROOT = '/apps/missionleben_announcements/api/v1/announcement-push/';

    public function __construct(string $appName, IRequest $request,
        private SignedRequestVerifier $signatureVerifier, private IDBConnection $db,
        private IGroupManager $groupManager, private IUserManager $userManager,
        private AnnouncementHistory $history,
    ) {
        parent::__construct($appName, $request);
    }

    #[PublicPage]
    #[NoCSRFRequired]
    public function catalog(): JSONResponse {
        if ($this->payload('catalog') !== []) {
            return new JSONResponse(['error' => 'invalid signed request'], Http::STATUS_UNAUTHORIZED);
        }
        // Only this app's encrypted 14-day archive is written; source posts are untouched.
        $this->history->synchronize();
        $query = $this->publishedQuery();
        $query->selectDistinct('a.announcement_id')->orderBy('a.announcement_id', 'ASC')->setMaxResults(501);
        $ids = array_map('intval', $query->executeQuery()->fetchFirstColumn());
        // Do not silently truncate a catalogue and misinterpret omissions as deletions.
        if (count($ids) > 500) {
            return new JSONResponse(['error' => 'announcement catalogue exceeds limit'], Http::STATUS_SERVICE_UNAVAILABLE);
        }
        return new JSONResponse(['ids' => $ids]);
    }

    #[PublicPage]
    #[NoCSRFRequired]
    public function resolve(): JSONResponse {
        $payload = $this->payload('resolve');
        if ($payload === null) {
            return new JSONResponse(['error' => 'authentication failed'], Http::STATUS_UNAUTHORIZED);
        }
        $ids = $payload['ids'] ?? null;
        $recipients = $payload['recipients'] ?? null;
        if (!is_array($ids) || count($ids) < 1 || count($ids) > 100
            || !is_array($recipients) || count($recipients) < 1 || count($recipients) > 100
            || count(array_filter($ids, static fn ($id): bool => is_int($id) && $id > 0)) !== count($ids)
            || array_diff(array_keys($payload), ['ids', 'recipients']) !== []) {
            return new JSONResponse(['error' => 'invalid request'], Http::STATUS_BAD_REQUEST);
        }
        $query = $this->publishedQuery();
        $query->select('a.announcement_id', 'a.announcement_subject', 'a.announcement_plain_message',
            'a.announcement_time', 'a.announcement_delete_time', 'ag.gid');
        $query->andWhere($query->expr()->in('a.announcement_id',
            $query->createNamedParameter($ids, IQueryBuilder::PARAM_INT_ARRAY)));
        $rows = $query->executeQuery()->fetchAllAssociative();
        $results = [];
        $seen = [];
        foreach ($recipients as $recipient) {
            $key = is_array($recipient) ? ($recipient['key'] ?? null) : null;
            $email = is_array($recipient) ? ($recipient['email'] ?? null) : null;
            $userId = is_array($recipient) ? ($recipient['user_id'] ?? '') : null;
            if (!is_string($key) || !preg_match('/^[A-Za-z0-9_-]{1,200}$/', $key) || isset($seen[$key])
                || !is_string($email) || strlen($email) > 320
                || !is_string($userId) || strlen($userId) > 200
                || array_diff(array_keys($recipient), ['key', 'email', 'user_id']) !== []) {
                return new JSONResponse(['error' => 'invalid recipient'], Http::STATUS_BAD_REQUEST);
            }
            $seen[$key] = true;
            if ($userId !== '') {
                // Stable Nextcloud UID from verified Authentik userinfo; never
                // fall back to another email identity if that UID was removed.
                $user = $this->userManager->get($userId);
                $matches = $user instanceof IUser && $user->isEnabled() ? [$user] : [];
            } else {
                $matches = filter_var($email, FILTER_VALIDATE_EMAIL) ? array_values(array_filter(
                    $this->userManager->getByEmail($email),
                    static fn ($user): bool => $user instanceof IUser && $user->isEnabled()
                        && strtolower(trim((string)$user->getEMailAddress())) === strtolower(trim($email)))) : [];
            }
            $allowed = [];
            if (count($matches) === 1) {
                $groups = $this->groupManager->getUserGroupIds($matches[0]);
                $groups[] = 'everyone';
                foreach ($rows as $row) {
                    if (!in_array($row['gid'], $groups, true)) {
                        continue;
                    }
                    $id = (int)$row['announcement_id'];
                    $allowed[$id] = ['id' => $id, 'subject' => mb_substr((string)$row['announcement_subject'], 0, 200),
                        'message' => mb_substr((string)$row['announcement_plain_message'], 0, 500),
                        'time' => (int)$row['announcement_time'],
                        'delete_time' => (int)($row['announcement_delete_time'] ?? 0)];
                }
            }
            $results[] = ['key' => $key, 'announcements' => array_values($allowed)];
        }
        return new JSONResponse(['recipients' => $results]);
    }

    protected function payload(string $operation): ?array {
        $raw = file_get_contents('php://input');
        if (!is_string($raw) || strlen($raw) > 65536
            || !$this->signatureVerifier->valid('POST', self::ROOT . $operation, $raw)) {
            return null;
        }
        $payload = json_decode($raw, true);
        return is_array($payload) ? $payload : null;
    }

    private function publishedQuery(): IQueryBuilder {
        $query = $this->db->getQueryBuilder();
        // Announcement Center creates map rows only when actually published,
        // including scheduled posts. A draft/scheduled row alone is not enough.
        $query->from('announcements', 'a')->innerJoin('a', 'announcements_map', 'ag',
            $query->expr()->eq('a.announcement_id', 'ag.announcement_id'));
        $query->where($query->expr()->orX($query->expr()->isNull('a.announcement_delete_time'),
            $query->expr()->eq('a.announcement_delete_time', $query->createNamedParameter(0, IQueryBuilder::PARAM_INT)),
            $query->expr()->gt('a.announcement_delete_time', $query->createNamedParameter(time(), IQueryBuilder::PARAM_INT))));
        return $query;
    }
}
