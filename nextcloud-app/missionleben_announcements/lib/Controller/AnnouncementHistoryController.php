<?php
declare(strict_types=1);

namespace OCA\MissionLebenAnnouncements\Controller;

use OCA\MissionLebenAnnouncements\Service\AnnouncementHistory;
use OCA\MissionLebenAnnouncements\Service\SignedRequestVerifier;
use OCP\AppFramework\Controller;
use OCP\AppFramework\Http;
use OCP\AppFramework\Http\Attribute\NoCSRFRequired;
use OCP\AppFramework\Http\Attribute\OpenAPI;
use OCP\AppFramework\Http\Attribute\PublicPage;
use OCP\AppFramework\Http\JSONResponse;
use OCP\IGroupManager;
use OCP\IRequest;
use OCP\IUser;
use OCP\IUserManager;

#[OpenAPI(scope: OpenAPI::SCOPE_IGNORE)]
class AnnouncementHistoryController extends Controller {
    private const PATH = '/apps/missionleben_announcements/api/v1/announcement-history';

    public function __construct(string $appName, IRequest $request,
        private SignedRequestVerifier $verifier, private AnnouncementHistory $history,
        private IUserManager $users, private IGroupManager $groups) {
        parent::__construct($appName, $request);
    }

    #[PublicPage]
    #[NoCSRFRequired]
    public function list(): JSONResponse {
        $payload = $this->payload();
        if ($payload === null) {
            return new JSONResponse(['error' => 'authentication failed'], Http::STATUS_UNAUTHORIZED);
        }
        $uid = $payload['user_id'] ?? '';
        $email = $payload['email'] ?? '';
        if (!is_string($uid) || strlen($uid) > 200 || !is_string($email) || strlen($email) > 320
            || array_diff(array_keys($payload), ['user_id', 'email']) !== []) {
            return new JSONResponse(['error' => 'invalid request'], Http::STATUS_BAD_REQUEST);
        }
        if ($uid !== '') {
            $user = $this->users->get($uid);
            $matches = $user instanceof IUser && $user->isEnabled() ? [$user] : [];
        } else {
            $matches = filter_var($email, FILTER_VALIDATE_EMAIL) ? array_values(array_filter(
                $this->users->getByEmail($email), static fn ($u): bool => $u instanceof IUser && $u->isEnabled()
                    && strtolower(trim((string)$u->getEMailAddress())) === strtolower(trim($email)))) : [];
        }
        if (count($matches) !== 1) {
            return new JSONResponse(['error' => 'user could not be resolved'], Http::STATUS_NOT_FOUND);
        }
        try {
            $groups = $this->groups->getUserGroupIds($matches[0]);
            $groups[] = 'everyone';
            $items = $this->history->visible(array_values(array_unique($groups)));
        } catch (\Throwable) {
            return new JSONResponse(['error' => 'announcement history unavailable'], Http::STATUS_SERVICE_UNAVAILABLE);
        }
        return new JSONResponse(['announcements' => $items, 'retention_days' => 14]);
    }

    protected function payload(): ?array {
        $raw = file_get_contents('php://input');
        if (!is_string($raw) || strlen($raw) > 8192 || !$this->verifier->valid('POST', self::PATH, $raw)) {
            return null;
        }
        $value = json_decode($raw, true);
        return is_array($value) ? $value : null;
    }
}
