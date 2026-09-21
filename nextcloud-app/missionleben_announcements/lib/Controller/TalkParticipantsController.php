<?php

declare(strict_types=1);

namespace OCA\MissionLebenAnnouncements\Controller;

use OCA\MissionLebenAnnouncements\Service\SignedRequestVerifier;
use OCA\Talk\Exceptions\RoomNotFoundException;
use OCA\Talk\Manager;
use OCA\Talk\Service\ParticipantService;
use OCP\AppFramework\Controller;
use OCP\AppFramework\Http;
use OCP\AppFramework\Http\Attribute\NoCSRFRequired;
use OCP\AppFramework\Http\Attribute\OpenAPI;
use OCP\AppFramework\Http\Attribute\PublicPage;
use OCP\AppFramework\Http\JSONResponse;
use OCP\IRequest;
use OCP\IUser;
use OCP\IUserManager;

#[OpenAPI(scope: OpenAPI::SCOPE_IGNORE)]
class TalkParticipantsController extends Controller {
    private const SIGNATURE_PATH = '/apps/missionleben_announcements/api/v1/talk-participants';

    public function __construct(
        string $appName,
        IRequest $request,
        private SignedRequestVerifier $signatureVerifier,
        private Manager $talkManager,
        private ParticipantService $participantService,
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
        if (!$this->signatureVerifier->valid('POST', self::SIGNATURE_PATH, $rawBody)) {
            return new JSONResponse(['error' => 'authentication failed'], Http::STATUS_UNAUTHORIZED);
        }
        $payload = json_decode($rawBody, true);
        $roomToken = is_array($payload) ? trim((string)($payload['room_token'] ?? '')) : '';
        if (!preg_match('/^[A-Za-z0-9_-]{4,128}$/', $roomToken)) {
            return new JSONResponse(['error' => 'invalid request'], Http::STATUS_BAD_REQUEST);
        }

        try {
            $room = $this->talkManager->getRoomByToken($roomToken);
        } catch (RoomNotFoundException) {
            return new JSONResponse(['error' => 'room not found'], Http::STATUS_NOT_FOUND);
        }

        $users = [];
        foreach ($this->participantService->getParticipantUserIds($room) as $userId) {
            if (!is_string($userId) || $userId === '') {
                continue;
            }
            $user = $this->userManager->get($userId);
            if ($user instanceof IUser && $user->isEnabled()) {
                $users[] = $userId;
            }
        }
        $users = array_values(array_unique($users));
        sort($users, SORT_STRING);
        return new JSONResponse(['users' => $users]);
    }
}
