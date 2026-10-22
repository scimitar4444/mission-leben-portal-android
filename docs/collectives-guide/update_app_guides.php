<?php
declare(strict_types=1);
// Run as www-data on the identified Nextcloud host. No groups, permissions or attachments change.
define('OC_CONSOLE', true);
require '/opt/nextcloud/lib/base.php';

try {
    $paths = [
        94019508 => 'Readme.md',
        95685097 => 'Webportal im Browser.md',
        95685099 => 'Android-App/Readme.md',
        95685102 => 'Android-App/Persönliches Handy.md',
        95685139 => 'Android-App/Gemeinsames Tablet.md',
        95685145 => 'Android-App/Gerät einrichten – für Leitungen und IT.md',
    ];
    $writeIds = [95685099, 95685102, 95685145];
    $prefix = 'appdata_oc1asepkpk60/collectives/7/Mission Leben Zentral/';
    $folder = \OC::$server->get(\OCP\Files\AppData\IAppDataFactory::class)
        ->get('collectives')->getFolder('7')->getFolder('Mission Leben Zentral');
    $db = \OC::$server->get(\OCP\IDBConnection::class);
    $text = \OC::$server->get(\OCA\Text\Service\DocumentService::class);
    // Text bundled with Nextcloud 35 identifies documents by context, not file ID.
    // Detect the supported API explicitly: never treat an unknown API as "no edits".
    $resetMethod = new ReflectionMethod($text, 'resetDocument');
    if ($resetMethod->getNumberOfRequiredParameters() === 2 &&
        $resetMethod->getParameters()[0]->getName() === 'contextType' &&
        $resetMethod->getParameters()[1]->getName() === 'contextId') {
        $documents = \OC::$server->get(\OCA\Text\Db\DocumentMapper::class);
        $steps = \OC::$server->get(\OCA\Text\Db\StepMapper::class);
        $getDocument = static fn(int $id) => $documents->load('file', $id);
        $hasUnsavedChanges = static fn($document): bool => $document !== null &&
            ($steps->getLatestVersion($document->getId()) ?: 0) !== $document->getLastSavedVersion();
        $resetDocument = static fn(int $id) => $text->resetDocument('file', $id);
        $textApi = 'context-file';
    } elseif ($resetMethod->getNumberOfRequiredParameters() === 1 &&
        method_exists($text, 'hasUnsavedChanges')) {
        $getDocument = static fn(int $id) => $text->getDocument($id);
        $hasUnsavedChanges = static fn($document): bool => $document !== null && $text->hasUnsavedChanges($document);
        $resetDocument = static fn(int $id) => $text->resetDocument($id);
        $textApi = 'legacy-file-id';
    } else {
        throw new RuntimeException('Unsupported Text document API; review before updating guides');
    }
    $files = [];
    foreach ($paths as $id => $path) {
        $qb = $db->getQueryBuilder();
        $qb->select('path')->from('filecache')->where($qb->expr()->eq('fileid',
            $qb->createNamedParameter($id, \OCP\DB\QueryBuilder\IQueryBuilder::PARAM_INT)));
        if ($qb->executeQuery()->fetchOne() !== $prefix . $path) {
            throw new RuntimeException('File ID/path mismatch: ' . $id);
        }
        $parent = str_contains($path, '/') ? $folder->getFolder(dirname($path)) : $folder;
        $files[$id] = $parent->getFile(basename($path));
        if ($files[$id]->getId() !== $id) {
            throw new RuntimeException('AppData ID mismatch: ' . $id);
        }
    }
    $attachmentSnapshot = static function () use ($folder): array {
        $out = [];
        $android = $folder->getFolder('Android-App');
        foreach ([95685099, 95685102, 95685139, 95685145] as $id) {
            foreach ($android->getFolder('.attachments.' . $id)->getDirectoryListing() as $file) {
                $bytes = $file->getContent();
                $out[] = ['pageId' => $id, 'id' => $file->getId(), 'name' => $file->getName(),
                    'sha256' => hash('sha256', $bytes), 'size' => strlen($bytes)];
            }
        }
        usort($out, static fn(array $a, array $b): int => $a['id'] <=> $b['id']);
        return $out;
    };
    if (($argv[1] ?? '') === '--snapshot') {
        $pages = [];
        foreach ($files as $id => $file) {
            $content = $file->getContent();
            $document = $getDocument($id);
            $pages[] = ['id' => $id, 'path' => $paths[$id], 'content' => $content,
                'sha256' => hash('sha256', $content),
                'unsavedTextChanges' => $hasUnsavedChanges($document)];
        }
        echo json_encode(['textApi' => $textApi, 'pages' => $pages, 'attachments' => $attachmentSnapshot()],
            JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES), "\n";
        exit(0);
    }
    $planPath = $argv[1] ?? '';
    if (!preg_match('~^/tmp/ml-app-guides\.[A-Za-z0-9]+/plan\.json$~', $planPath)) {
        throw new RuntimeException('Unexpected plan path');
    }
    $plan = json_decode(file_get_contents($planPath), true, 512, JSON_THROW_ON_ERROR);
    $operation = $argv[2] ?? '--check';
    if (!in_array($operation, ['--check', '--apply', '--rollback'], true) || count($plan['pages']) !== count($paths)) {
        throw new RuntimeException('Unexpected operation or page count');
    }
    if ($plan['attachments'] !== $attachmentSnapshot()) {
        throw new RuntimeException('Attachment inventory changed');
    }
    $seen = [];
    $changes = [];
    foreach ($plan['pages'] as $row) {
        $id = $row['id'];
        if (!isset($paths[$id]) || isset($seen[$id]) || $row['path'] !== $paths[$id] ||
            !hash_equals($row['beforeSha256'], hash('sha256', $row['beforeContent']))) {
            throw new RuntimeException('Invalid read-derived page: ' . $id);
        }
        $seen[$id] = true;
        $changed = $row['beforeContent'] !== $row['content'];
        if ($changed && !in_array($id, $writeIds, true)) {
            throw new RuntimeException('Page outside write scope: ' . $id);
        }
        $expected = $operation === '--rollback' ? hash('sha256', $row['content']) : $row['beforeSha256'];
        if (!hash_equals($expected, hash('sha256', $files[$id]->getContent()))) {
            throw new RuntimeException('Concurrent page edit: ' . $id);
        }
        if ($changed) {
            $document = $getDocument($id);
            if ($hasUnsavedChanges($document)) {
                throw new RuntimeException('Unsaved Text edits: ' . $id);
            }
            $changes[] = $row;
        }
    }
    $results = [];
    if ($operation !== '--check') {
        foreach ($changes as $row) {
            $id = $row['id'];
            $expected = $operation === '--rollback' ? hash('sha256', $row['content']) : $row['beforeSha256'];
            $document = $getDocument($id);
            if (!hash_equals($expected, hash('sha256', $files[$id]->getContent())) ||
                $hasUnsavedChanges($document)) {
                throw new RuntimeException('Concurrent edit before save: ' . $id);
            }
            $content = $operation === '--rollback' ? $row['beforeContent'] : $row['content'];
            $files[$id]->putContent($content);
            $actual = hash('sha256', $files[$id]->getContent());
            if (!hash_equals(hash('sha256', $content), $actual)) {
                throw new RuntimeException('Readback mismatch: ' . $id);
            }
            $document = $getDocument($id);
            $reset = $document !== null && $document->getChecksum() !== $text::computeCheckSum($content);
            if ($reset) {
                $resetDocument($id); // Never force over unsaved changes.
            }
            $results[] = ['id' => $id, 'sha256' => $actual, 'textSessionReset' => $reset];
        }
    }
    if ($plan['attachments'] !== $attachmentSnapshot()) {
        throw new RuntimeException('Attachment inventory changed after update');
    }
    echo json_encode(['operation' => $operation, 'textApi' => $textApi, 'changedPageIds' => array_column($changes, 'id'),
        'attachmentsUnchanged' => true, 'pages' => $results], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES), "\n";
} catch (Throwable $e) {
    fwrite(STDERR, get_class($e) . ': ' . $e->getMessage() . "\n");
    exit(1);
}
