<?php

declare(strict_types=1);

namespace OCA\MissionLebenAnnouncements\Controller;

use OCP\AppFramework\Controller;
use OCP\AppFramework\Http;
use OCP\AppFramework\Http\Attribute\NoCSRFRequired;
use OCP\AppFramework\Http\Attribute\OpenAPI;
use OCP\AppFramework\Http\Attribute\PublicPage;
use OCP\AppFramework\Http\JSONResponse;
use OCP\DB\QueryBuilder\IQueryBuilder;
use OCP\IConfig;
use OCP\IDBConnection;
use OCP\IGroupManager;
use OCP\IRequest;
use OCP\IUser;
use OCP\IUserManager;

#[OpenAPI(scope: OpenAPI::SCOPE_IGNORE)]
class AnnouncementsController extends Controller {
    private const SIGNATURE_PATH = '/apps/missionleben_announcements/api/v1/announcements';
    private const MAX_CLOCK_SKEW = 60;

    public function __construct(
        string $appName,
        IRequest $request,
        private IConfig $config,
        private IDBConnection $db,
        private IGroupManager $groupManager,
        private IUserManager $userManager,
    ) {
        parent::__construct($appName, $request);
    }

    #[PublicPage]
    #[NoCSRFRequired]
    public function list(): JSONResponse {
        $rawBody = file_get_contents('php://input');
        if (!is_string($rawBody)) {
            return new JSONResponse(['error' => 'invalid request'], Http::STATUS_BAD_REQUEST);
        }
        if (!$this->validSignature($rawBody)) {
            return new JSONResponse(['error' => 'authentication failed'], Http::STATUS_UNAUTHORIZED);
        }
        $payload = json_decode($rawBody, true);
        if (!is_array($payload)) {
            return new JSONResponse(['error' => 'invalid request'], Http::STATUS_BAD_REQUEST);
        }
        $user = $this->resolveUser($payload);
        if (!$user instanceof IUser || !$user->isEnabled()) {
            return new JSONResponse(['error' => 'user could not be resolved'], Http::STATUS_NOT_FOUND);
        }
        $groups = $this->groupManager->getUserGroupIds($user);
        $groups[] = 'everyone';
        $groups = array_values(array_unique(array_filter($groups, 'is_string')));
        if ($groups === []) {
            return new JSONResponse(['announcements' => []]);
        }

        $now = time();
        $query = $this->db->getQueryBuilder();
        $query->selectDistinct(
            'a.announcement_id',
            'a.announcement_time',
            'a.announcement_user',
            'a.announcement_subject',
            'a.announcement_message',
            'a.announcement_plain_message',
            'a.announcement_delete_time'
        )
            ->from('announcements', 'a')
            ->innerJoin(
                'a',
                'announcements_map',
                'ag',
                $query->expr()->eq('a.announcement_id', 'ag.announcement_id')
            )
            ->where(
                $query->expr()->in(
                    'ag.gid',
                    $query->createNamedParameter($groups, IQueryBuilder::PARAM_STR_ARRAY)
                )
            )
            ->andWhere(
                $query->expr()->orX(
                    $query->expr()->isNull('a.announcement_delete_time'),
                    $query->expr()->eq(
                        'a.announcement_delete_time',
                        $query->createNamedParameter(0, IQueryBuilder::PARAM_INT)
                    ),
                    $query->expr()->gt(
                        'a.announcement_delete_time',
                        $query->createNamedParameter($now, IQueryBuilder::PARAM_INT)
                    )
                )
            )
            ->orderBy('a.announcement_time', 'DESC')
            ->setMaxResults(7);

        $result = $query->executeQuery();
        $announcements = [];
        while ($row = $result->fetch()) {
            $authorId = (string)($row['announcement_user'] ?? '');
            $plain = trim((string)($row['announcement_plain_message'] ?? ''));
            $announcements[] = [
                'id' => (int)$row['announcement_id'],
                'subject' => trim(str_replace("\n", ' ', (string)$row['announcement_subject'])),
                'message' => $plain !== '' ? $plain : trim((string)$row['announcement_message']),
                'author' => $this->userManager->getDisplayName($authorId) ?? $authorId,
                'time' => (int)$row['announcement_time'],
                'delete_time' => isset($row['announcement_delete_time'])
                    ? (int)$row['announcement_delete_time']
                    : null,
            ];
        }
        $result->closeCursor();

        return new JSONResponse(['announcements' => $announcements]);
    }

    private function resolveUser(array $payload): ?IUser {
        $userId = trim((string)($payload['user_id'] ?? ''));
        if ($userId !== '' && strlen($userId) <= 200) {
            return $this->userManager->get($userId);
        }
        $email = strtolower(trim((string)($payload['email'] ?? '')));
        if ($email === '' || strlen($email) > 320 || filter_var($email, FILTER_VALIDATE_EMAIL) === false) {
            return null;
        }
        $matches = array_values(array_filter(
            $this->userManager->getByEmail($email),
            static fn (mixed $user): bool => $user instanceof IUser && $user->isEnabled()
        ));
        return count($matches) === 1 ? $matches[0] : null;
    }

    private function validSignature(string $rawBody): bool {
        $settings = $this->config->getSystemValue('missionleben_announcements', []);
        $secret = is_array($settings) ? (string)($settings['secret'] ?? '') : '';
        $timestamp = $this->request->getHeader('X-ML-Timestamp');
        $nonce = $this->request->getHeader('X-ML-Nonce');
        $signature = $this->request->getHeader('X-ML-Signature');
        if (
            strlen($secret) < 32
            || !preg_match('/^[0-9]{10}$/', $timestamp)
            || abs(time() - (int)$timestamp) > self::MAX_CLOCK_SKEW
            || !preg_match('/^[A-Za-z0-9_-]{16,64}$/', $nonce)
            || !preg_match('/^[A-Za-z0-9_-]{43}$/', $signature)
        ) {
            return false;
        }
        $canonical = "POST\n" . self::SIGNATURE_PATH . "\n" . $timestamp . "\n" . $nonce . "\n"
            . hash('sha256', $rawBody);
        $expected = rtrim(strtr(base64_encode(hash_hmac('sha256', $canonical, $secret, true)), '+/', '-_'), '=');
        return hash_equals($expected, $signature);
    }
}
