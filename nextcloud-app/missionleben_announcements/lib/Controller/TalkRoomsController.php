<?php

declare(strict_types=1);

namespace OCA\MissionLebenAnnouncements\Controller;

use OCP\AppFramework\Controller;
use OCP\AppFramework\Http;
use OCP\AppFramework\Http\Attribute\NoAdminRequired;
use OCP\AppFramework\Http\Attribute\OpenAPI;
use OCP\AppFramework\Http\JSONResponse;
use OCP\DB\QueryBuilder\IQueryBuilder;
use OCP\IDBConnection;
use OCP\IRequest;
use OCP\IUserSession;

#[OpenAPI(scope: OpenAPI::SCOPE_IGNORE)]
class TalkRoomsController extends Controller {
    private const ROOM_TYPE_ONE_TO_ONE = 1;
    private const ROOM_TYPE_GROUP = 2;

    public function __construct(
        string $appName,
        IRequest $request,
        private IDBConnection $db,
        private IUserSession $userSession,
    ) {
        parent::__construct($appName, $request);
    }

    #[NoAdminRequired]
    public function list(): JSONResponse {
        $user = $this->userSession->getUser();
        if ($user === null || !$user->isEnabled()) {
            return new JSONResponse(['error' => 'authentication required'], Http::STATUS_UNAUTHORIZED);
        }

        $query = $this->db->getQueryBuilder();
        $query->selectDistinct('r.token')
            ->from('talk_rooms', 'r')
            ->innerJoin(
                'r',
                'talk_attendees',
                'a',
                $query->expr()->eq('a.room_id', 'r.id')
            )
            ->where($query->expr()->eq('a.actor_type', $query->createNamedParameter('users')))
            ->andWhere($query->expr()->eq('a.actor_id', $query->createNamedParameter($user->getUID())))
            ->andWhere($query->expr()->eq(
                'a.archived',
                $query->createNamedParameter(0, IQueryBuilder::PARAM_INT)
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
            ))
            ->orderBy('r.last_activity', 'DESC');

        $tokens = [];
        $result = $query->executeQuery();
        while ($row = $result->fetch()) {
            $token = trim((string)($row['token'] ?? ''));
            if ($token !== '') {
                $tokens[] = $token;
            }
        }
        $result->closeCursor();

        $response = new JSONResponse(['room_tokens' => $tokens]);
        $response->addHeader('Cache-Control', 'no-store, private');
        return $response;
    }
}
