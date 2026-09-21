<?php

declare(strict_types=1);

namespace OCA\MissionLebenAnnouncements\Controller;

use OCA\MissionLebenAnnouncements\Service\SignedRequestVerifier;
use OCP\AppFramework\Controller;
use OCP\AppFramework\Http;
use OCP\AppFramework\Http\Attribute\NoCSRFRequired;
use OCP\AppFramework\Http\Attribute\OpenAPI;
use OCP\AppFramework\Http\Attribute\PublicPage;
use OCP\AppFramework\Http\JSONResponse;
use OCP\DB\QueryBuilder\IQueryBuilder;
use OCP\IConfig;
use OCP\IDBConnection;
use OCP\IRequest;

#[OpenAPI(scope: OpenAPI::SCOPE_IGNORE)]
class TalkBotSyncController extends Controller {
    private const SIGNATURE_PATH = '/apps/missionleben_announcements/api/v1/talk-bot-sync';
    private const DEFAULT_BOT_NAME = 'Mission Leben Hinweise';
    private const BOT_STATE_DISABLED = 0;
    private const BOT_STATE_ENABLED = 1;
    private const BOT_FEATURE_WEBHOOK = 1;
    private const ROOM_TYPE_ONE_TO_ONE = 1;
    private const ROOM_TYPE_GROUP = 2;
    private const MAX_USERS = 5000;

    public function __construct(
        string $appName,
        IRequest $request,
        private SignedRequestVerifier $signatureVerifier,
        private IDBConnection $db,
        private IConfig $config,
    ) {
        parent::__construct($appName, $request);
    }

    #[PublicPage]
    #[NoCSRFRequired]
    public function sync(): JSONResponse {
        $rawBody = file_get_contents('php://input');
        if (!is_string($rawBody)) {
            return new JSONResponse(['error' => 'invalid request'], Http::STATUS_BAD_REQUEST);
        }
        if (!$this->signatureVerifier->valid('POST', self::SIGNATURE_PATH, $rawBody)) {
            return new JSONResponse(['error' => 'authentication failed'], Http::STATUS_UNAUTHORIZED);
        }

        $payload = json_decode($rawBody, true);
        $userIds = is_array($payload) ? ($payload['user_ids'] ?? null) : null;
        if (!is_array($userIds) || count($userIds) > self::MAX_USERS) {
            return new JSONResponse(['error' => 'invalid request'], Http::STATUS_BAD_REQUEST);
        }

        $normalizedUserIds = [];
        foreach ($userIds as $userId) {
            if (!is_string($userId)) {
                return new JSONResponse(['error' => 'invalid request'], Http::STATUS_BAD_REQUEST);
            }
            $userId = trim($userId);
            if ($userId === '' || strlen($userId) > 200 || preg_match('/[\x00-\x1f\x7f]/', $userId)) {
                return new JSONResponse(['error' => 'invalid request'], Http::STATUS_BAD_REQUEST);
            }
            $normalizedUserIds[$userId] = true;
        }
        $normalizedUserIds = array_keys($normalizedUserIds);
        sort($normalizedUserIds, SORT_STRING);

        $bot = $this->findBot();
        if ($bot === null) {
            return new JSONResponse(['error' => 'Talk notification bot is unavailable'], Http::STATUS_CONFLICT);
        }

        [$desiredTokens, $matchedUsers] = $this->desiredRoomTokens($normalizedUserIds);
        $existingTokens = $this->existingRoomTokens((int)$bot['id']);
        $addTokens = array_values(array_diff($desiredTokens, $existingTokens));
        $removeTokens = array_values(array_diff($existingTokens, $desiredTokens));

        $this->db->beginTransaction();
        try {
            foreach ($addTokens as $token) {
                $query = $this->db->getQueryBuilder();
                $query->insert('talk_bots_conversation')
                    ->values([
                        'bot_id' => $query->createNamedParameter((int)$bot['id'], IQueryBuilder::PARAM_INT),
                        'token' => $query->createNamedParameter($token),
                        'state' => $query->createNamedParameter(self::BOT_STATE_ENABLED, IQueryBuilder::PARAM_INT),
                    ])
                    ->executeStatement();
            }

            if ($desiredTokens !== []) {
                $query = $this->db->getQueryBuilder();
                $query->update('talk_bots_conversation')
                    ->set('state', $query->createNamedParameter(self::BOT_STATE_ENABLED, IQueryBuilder::PARAM_INT))
                    ->where($query->expr()->eq(
                        'bot_id',
                        $query->createNamedParameter((int)$bot['id'], IQueryBuilder::PARAM_INT)
                    ))
                    ->andWhere($query->expr()->in(
                        'token',
                        $query->createNamedParameter($desiredTokens, IQueryBuilder::PARAM_STR_ARRAY)
                    ))
                    ->executeStatement();
            }

            if ($removeTokens !== []) {
                $query = $this->db->getQueryBuilder();
                $query->delete('talk_bots_conversation')
                    ->where($query->expr()->eq(
                        'bot_id',
                        $query->createNamedParameter((int)$bot['id'], IQueryBuilder::PARAM_INT)
                    ))
                    ->andWhere($query->expr()->in(
                        'token',
                        $query->createNamedParameter($removeTokens, IQueryBuilder::PARAM_STR_ARRAY)
                    ))
                    ->executeStatement();
            }
            $this->db->commit();
        } catch (\Throwable $error) {
            $this->db->rollBack();
            throw $error;
        }

        return new JSONResponse([
            'eligible_users' => count($normalizedUserIds),
            'matched_users' => $matchedUsers,
            'desired_rooms' => count($desiredTokens),
            'added' => count($addTokens),
            'removed' => count($removeTokens),
        ]);
    }

    private function findBot(): ?array {
        $settings = $this->config->getSystemValue('missionleben_announcements', []);
        $botName = is_array($settings)
            ? trim((string)($settings['talk_bot_name'] ?? self::DEFAULT_BOT_NAME))
            : self::DEFAULT_BOT_NAME;
        if ($botName === '') {
            $botName = self::DEFAULT_BOT_NAME;
        }

        $query = $this->db->getQueryBuilder();
        $query->select('id', 'state', 'features')
            ->from('talk_bots_server')
            ->where($query->expr()->eq('name', $query->createNamedParameter($botName)))
            ->setMaxResults(2);
        $rows = $query->executeQuery()->fetchAll();
        if (count($rows) !== 1) {
            return null;
        }
        $bot = $rows[0];
        if (
            (int)$bot['state'] === self::BOT_STATE_DISABLED
            || (((int)$bot['features']) & self::BOT_FEATURE_WEBHOOK) === 0
        ) {
            return null;
        }
        return $bot;
    }

    private function desiredRoomTokens(array $userIds): array {
        if ($userIds === []) {
            return [[], 0];
        }

        $query = $this->db->getQueryBuilder();
        $query->selectDistinct(['r.token', 'a.actor_id'])
            ->from('talk_rooms', 'r')
            ->innerJoin(
                'r',
                'talk_attendees',
                'a',
                $query->expr()->eq('a.room_id', 'r.id')
            )
            ->where($query->expr()->eq('a.actor_type', $query->createNamedParameter('users')))
            ->andWhere($query->expr()->eq(
                'a.archived',
                $query->createNamedParameter(0, IQueryBuilder::PARAM_INT)
            ))
            ->andWhere($query->expr()->in(
                'a.actor_id',
                $query->createNamedParameter($userIds, IQueryBuilder::PARAM_STR_ARRAY)
            ))
            ->andWhere($query->expr()->in(
                'r.type',
                $query->createNamedParameter(
                    [self::ROOM_TYPE_ONE_TO_ONE, self::ROOM_TYPE_GROUP],
                    IQueryBuilder::PARAM_INT_ARRAY
                )
            ))
            ->andWhere($query->expr()->eq(
                'r.has_federation',
                $query->createNamedParameter(0, IQueryBuilder::PARAM_INT)
            ));

        $tokens = [];
        $matchedUsers = [];
        $result = $query->executeQuery();
        while ($row = $result->fetch()) {
            $token = trim((string)($row['token'] ?? ''));
            $userId = trim((string)($row['actor_id'] ?? ''));
            if ($token !== '') {
                $tokens[$token] = true;
            }
            if ($userId !== '') {
                $matchedUsers[$userId] = true;
            }
        }
        $result->closeCursor();
        $tokens = array_keys($tokens);
        sort($tokens, SORT_STRING);
        return [$tokens, count($matchedUsers)];
    }

    private function existingRoomTokens(int $botId): array {
        $query = $this->db->getQueryBuilder();
        $query->select('token')
            ->from('talk_bots_conversation')
            ->where($query->expr()->eq(
                'bot_id',
                $query->createNamedParameter($botId, IQueryBuilder::PARAM_INT)
            ));
        $tokens = [];
        $result = $query->executeQuery();
        while ($row = $result->fetch()) {
            $token = trim((string)($row['token'] ?? ''));
            if ($token !== '') {
                $tokens[] = $token;
            }
        }
        $result->closeCursor();
        sort($tokens, SORT_STRING);
        return $tokens;
    }
}
