<?php
declare(strict_types=1);

namespace OCA\MissionLebenAnnouncements\Service;

use OCP\AppFramework\Utility\ITimeFactory;
use OCP\DB\QueryBuilder\IQueryBuilder;
use OCP\IDBConnection;
use OCP\Security\ICrypto;

/** Own encrypted archive, never modifies Announcement Center or its deletion jobs. */
class AnnouncementHistory {
    public const RETENTION_SECONDS = 14 * 86400;
    private const MAX_ITEMS = 500;
    private const TABLE = 'ml_announcement_history';

    public function __construct(private IDBConnection $db, private ICrypto $crypto, private ITimeFactory $clock) {}

    /** Called once per server discovery tick, not by every handset. */
    public function synchronize(): void {
        $now = $this->clock->getTime();
        $this->prune($now);
        $stored = array_column($this->storedRows($now), null, 'announcement_id');
        foreach ($this->sourceItems($now) as $id => $item) {
            if ($item['time'] + self::RETENTION_SECONDS <= $now) {
                continue; // Old but still active posts remain live, not archived indefinitely.
            }
            $json = json_encode($item, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE);
            $hash = hash('sha256', $json);
            if (($stored[$id]['content_hash'] ?? '') === $hash) {
                continue;
            }
            $this->writeSnapshot($id, $this->crypto->encrypt($json), $hash,
                $item['time'] + self::RETENTION_SECONDS);
        }
    }

    /** Source deletions/expiry retain the snapshot; CURRENT user groups still apply. */
    public function visible(array $userGroups): array {
        $now = $this->clock->getTime();
        $items = [];
        foreach ($this->storedRows($now) as $row) {
            $item = json_decode($this->crypto->decrypt((string)$row['payload']), true, 32, JSON_THROW_ON_ERROR);
            if (!is_array($item) || (int)($item['id'] ?? 0) !== (int)$row['announcement_id']
                || !is_int($item['time'] ?? null) || $item['time'] <= 0 || $item['time'] > $now
                || $item['time'] + self::RETENTION_SECONDS <= $now || !is_array($item['groups'] ?? null)) {
                throw new \RuntimeException('Invalid announcement archive');
            }
            $items[$item['id']] = $item;
        }
        // Live content/audience changes take precedence immediately, not after the next sync.
        $source = $this->sourceItems($now);
        $items = array_replace($items, $source);
        if (count($items) > self::MAX_ITEMS) {
            throw new \RuntimeException('Announcement history exceeds limit');
        }
        $visible = [];
        foreach ($items as $id => $item) {
            if (array_intersect($userGroups, $item['groups']) === []) {
                continue;
            }
            $archived = !isset($source[$id]) || ($item['delete_time'] > 0 && $item['delete_time'] <= $now);
            if ($archived && $item['time'] + self::RETENTION_SECONDS <= $now) {
                continue;
            }
            unset($item['groups']); // Never return an audience list to Bridge/Android.
            $item['archived'] = $archived;
            $visible[] = $item;
        }
        usort($visible, static fn ($a, $b) => [$b['time'], $b['id']] <=> [$a['time'], $a['id']]);
        return $visible;
    }

    private function sourceItems(int $now): array {
        $items = [];
        foreach ($this->sourceRows($now) as $row) {
            $id = (int)$row['announcement_id'];
            $published = (int)$row['announcement_time'];
            $group = $row['gid'];
            if ($id <= 0 || $published <= 0 || $published > $now || !is_string($group) || $group === '') {
                continue;
            }
            if (!isset($items[$id])) {
                $plain = trim((string)$row['announcement_plain_message']);
                $items[$id] = ['id' => $id,
                    'subject' => mb_substr(trim((string)$row['announcement_subject']), 0, 200),
                    'message' => mb_substr($plain !== '' ? $plain : strip_tags((string)$row['announcement_message']), 0, 500),
                    'author' => mb_substr((string)$row['announcement_user'], 0, 120),
                    'time' => $published, 'delete_time' => (int)($row['announcement_delete_time'] ?? 0), 'groups' => []];
            }
            $items[$id]['groups'][] = $group;
        }
        if (count($items) > self::MAX_ITEMS) {
            throw new \RuntimeException('Announcement history exceeds limit');
        }
        foreach ($items as &$item) {
            $item['groups'] = array_values(array_unique($item['groups']));
            sort($item['groups'], SORT_STRING);
        }
        return $items;
    }

    protected function sourceRows(int $now): array {
        $q = $this->db->getQueryBuilder();
        $q->select('a.announcement_id', 'a.announcement_subject', 'a.announcement_plain_message',
            'a.announcement_message', 'a.announcement_user', 'a.announcement_time', 'a.announcement_delete_time', 'ag.gid')
            ->from('announcements', 'a')->innerJoin('a', 'announcements_map', 'ag',
                $q->expr()->eq('a.announcement_id', 'ag.announcement_id'))
            ->where($q->expr()->lte('a.announcement_time', $q->createNamedParameter($now, IQueryBuilder::PARAM_INT)))
            ->andWhere($q->expr()->orX(
                $q->expr()->gt('a.announcement_time', $q->createNamedParameter($now - self::RETENTION_SECONDS, IQueryBuilder::PARAM_INT)),
                $q->expr()->isNull('a.announcement_delete_time'),
                $q->expr()->eq('a.announcement_delete_time', $q->createNamedParameter(0, IQueryBuilder::PARAM_INT)),
                $q->expr()->gt('a.announcement_delete_time', $q->createNamedParameter($now, IQueryBuilder::PARAM_INT))));
        // Bounded by published catalogue (<=500 IDs); fail closed rather than omit groups.
        $rows = $q->setMaxResults(50001)->executeQuery()->fetchAllAssociative();
        if (count($rows) > 50000) {
            throw new \RuntimeException('Announcement audience exceeds limit');
        }
        return $rows;
    }

    protected function storedRows(int $now): array {
        $q = $this->db->getQueryBuilder();
        $rows = $q->select('announcement_id', 'payload', 'content_hash', 'retain_until')->from(self::TABLE)
            ->where($q->expr()->gt('retain_until', $q->createNamedParameter($now, IQueryBuilder::PARAM_INT)))
            ->setMaxResults(self::MAX_ITEMS + 1)->executeQuery()->fetchAllAssociative();
        if (count($rows) > self::MAX_ITEMS) {
            throw new \RuntimeException('Announcement history exceeds limit');
        }
        return $rows;
    }

    protected function writeSnapshot(int $id, string $payload, string $hash, int $retainUntil): void {
        $this->db->setValues(self::TABLE, ['announcement_id' => $id],
            ['payload' => $payload, 'content_hash' => $hash, 'retain_until' => $retainUntil]);
    }

    protected function prune(int $now): void {
        $q = $this->db->getQueryBuilder();
        $q->delete(self::TABLE)->where($q->expr()->lte('retain_until',
            $q->createNamedParameter($now, IQueryBuilder::PARAM_INT)))->executeStatement();
    }
}
