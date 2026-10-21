<?php
declare(strict_types=1);

// Standalone synthetic contract test. No Nextcloud bootstrap, users or database.
namespace OCP {
    interface IRequest {}
    interface IDBConnection {}
    interface IGroupManager {}
    interface IUserManager {}
    interface IUser {}
}
namespace OCP\DB\QueryBuilder {
    interface IQueryBuilder {
        public const PARAM_INT = 1;
        public const PARAM_INT_ARRAY = 2;
    }
}
namespace OCP\AppFramework {
    class Controller { public function __construct(...$args) {} }
    class Http {
        public const STATUS_UNAUTHORIZED = 401;
        public const STATUS_BAD_REQUEST = 400;
        public const STATUS_SERVICE_UNAVAILABLE = 503;
        public const STATUS_NOT_FOUND = 404;
    }
}
namespace OCP\AppFramework\Http {
    class JSONResponse {
        public function __construct(public mixed $data, public int $status = 200) {}
    }
}
namespace OCA\MissionLebenAnnouncements\Service {
    class SignedRequestVerifier {}
    class AnnouncementHistory { public function synchronize(): void {} }
}
namespace {
    require $argv[1];

    class Parameter { public function __construct(public mixed $value) {} }
    class Result {
        public function __construct(private array $rows) {}
        public function fetchAllAssociative(): array { return $this->rows; }
        public function fetchFirstColumn(): array { return array_column($this->rows, 'announcement_id'); }
    }
    class Query implements \OCP\DB\QueryBuilder\IQueryBuilder {
        private array $conditions = [];
        private int $limit = PHP_INT_MAX;
        public function __construct(private array $rows) {}
        public function __call($name, $args): self { return $this; }
        public function expr(): self { return $this; }
        public function createNamedParameter($value, $type): Parameter { return new Parameter($value); }
        private function value($value, $row): mixed {
            return $value instanceof Parameter ? $value->value : ($row[substr($value, strpos($value, '.') + 1)] ?? null);
        }
        public function eq($left, $right): \Closure { return fn ($r) => $this->value($left, $r) === $this->value($right, $r); }
        public function gt($left, $right): \Closure { return fn ($r) => $this->value($left, $r) > $this->value($right, $r); }
        public function isNull($field): \Closure { return fn ($r) => $this->value($field, $r) === null; }
        public function in($field, $values): \Closure { return fn ($r) => in_array($this->value($field, $r), $values->value, true); }
        public function orX(...$conditions): \Closure { return fn ($r) => count(array_filter($conditions, fn ($c) => $c($r))) > 0; }
        public function where($condition): self { $this->conditions = [$condition]; return $this; }
        public function andWhere($condition): self { $this->conditions[] = $condition; return $this; }
        public function setMaxResults($limit): self { $this->limit = $limit; return $this; }
        public function executeQuery(): Result {
            $rows = array_filter($this->rows, fn ($r) => $r['gid'] !== null
                && count(array_filter($this->conditions, fn ($c) => !$c($r))) === 0);
            return new Result(array_slice(array_values($rows), 0, $this->limit));
        }
    }
    class Database implements \OCP\IDBConnection {
        public function __construct(public array $rows) {}
        public function getQueryBuilder(): Query { return new Query($this->rows); }
    }
    class Person implements \OCP\IUser {
        public function __construct(public string $email, public array $groups, public bool $active = true) {}
        public function isEnabled(): bool { return $this->active; }
        public function getEMailAddress(): string { return $this->email; }
    }
    class Users implements \OCP\IUserManager {
        public array $users;
        public function __construct() {
            $this->users = ['uid-a' => new Person('a@example.invalid', ['house-a']),
                'uid-b' => new Person('b@example.invalid', ['house-b']),
                'uid-off' => new Person('off@example.invalid', ['house-a'], false),
                'uid-no-mail' => new Person('', ['house-a'])];
        }
        public function get($id): ?Person { return $this->users[$id] ?? null; }
        public function getByEmail($email): array {
            // Deliberately fuzzy lookup; the real controller must enforce exact identity.
            return array_values(array_filter($this->users, fn ($u) => str_contains($u->email, $email)));
        }
    }
    class Groups implements \OCP\IGroupManager {
        public function getUserGroupIds($user): array { return $user->groups; }
    }
    class Request implements \OCP\IRequest {}
    class Controller extends \OCA\MissionLebenAnnouncements\Controller\AnnouncementPushController {
        public ?array $body = [];
        protected function payload(string $operation): ?array { return $this->body; }
    }
    function check($condition, $message): void {
        if (!$condition) { throw new \RuntimeException($message); }
    }
    function row($id, $group, $delete = 0): array {
        return ['announcement_id' => $id, 'announcement_subject' => 'Synthetic notice',
            'announcement_plain_message' => 'Synthetic content', 'announcement_time' => time(),
            'announcement_delete_time' => $delete, 'gid' => $group];
    }
    $db = new Database([row(1, 'everyone'), row(2, 'house-a'), row(3, 'house-b'),
        row(4, 'everyone', time() - 1), row(5, null)]);
    $users = new Users();
    $controller = new Controller('synthetic', new Request(),
        new \OCA\MissionLebenAnnouncements\Service\SignedRequestVerifier(), $db, new Groups(), $users,
        new \OCA\MissionLebenAnnouncements\Service\AnnouncementHistory());
    check($controller->catalog()->data['ids'] === [1, 2, 3], 'catalogue must exclude expired/unpublished posts');
    $recipients = [ ['key' => 'a', 'email' => 'a@example.invalid'],
        ['key' => 'b', 'email' => 'b@example.invalid'], ['key' => 'off', 'email' => 'off@example.invalid'],
        ['key' => 'unknown', 'email' => 'missing@example.invalid'],
        ['key' => 'fuzzy', 'email' => 'example.invalid'],
        ['key' => 'uid', 'email' => '', 'user_id' => 'uid-no-mail'],
        ['key' => 'removed-uid', 'email' => 'a@example.invalid', 'user_id' => 'removed'] ];
    $controller->body = ['ids' => [1, 2, 3, 4, 5], 'recipients' => $recipients];
    $response = $controller->resolve();
    check($response->status === 200, 'valid resolution');
    $resolved = array_column($response->data['recipients'], 'announcements', 'key');
    check(array_column($resolved['a'], 'id') === [1, 2], 'house A isolation');
    check(array_column($resolved['b'], 'id') === [1, 3], 'house B isolation');
    check(array_column($resolved['uid'], 'id') === [1, 2], 'stable UID requires no mailbox');
    foreach (['off', 'unknown', 'fuzzy', 'removed-uid'] as $key) {
        check($resolved[$key] === [], 'disabled/missing/ambiguous identity must be denied: ' . $key);
    }
    $users->users['uid-duplicate'] = new Person('a@example.invalid', ['house-b']);
    check($controller->resolve()->data['recipients'][0]['announcements'] === [], 'duplicate mail identity denied');
    $controller->body['recipients'] = [$recipients[0], $recipients[0]];
    check($controller->resolve()->status === 400, 'duplicate recipient keys denied');
    $controller->body = ['ids' => ['1'], 'recipients' => [$recipients[0]]];
    check($controller->resolve()->status === 400, 'typed identifiers required');
    $controller->body = ['ids' => [1], 'recipients' => [$recipients[0]], 'groups' => ['everyone']];
    check($controller->resolve()->status === 400, 'caller-supplied groups denied');
    $controller->body = null;
    check($controller->resolve()->status === 401, 'unsigned request denied');
    check($controller->catalog()->status === 401, 'unsigned catalogue denied');
    $controller->body = [];
    $db->rows = array_map(fn ($id) => row($id, 'everyone'), range(1, 501));
    check($controller->catalog()->status === 503, 'catalogue overflow fails closed, never truncates');
    echo "Announcement push: 14 synthetic contract checks passed\n";
}
