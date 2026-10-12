<?php
declare(strict_types=1);
// Run on the identified Nextcloud host as www-data, with a read-derived plan.
// Only the existing Android help page and its own five new attachments change.
define('OC_CONSOLE', true);
require '/opt/nextcloud/lib/base.php';

try {
    $planPath = $argv[1] ?? '';
    $rollback = ($argv[2] ?? '') === '--rollback';
    if (!preg_match('~^/tmp/ml-app-help\.[A-Za-z0-9]+/plan\.json$~', $planPath)) {
        throw new RuntimeException('Unexpected plan location');
    }
    $plan = json_decode(file_get_contents($planPath), true, 512, JSON_THROW_ON_ERROR);
    if ($plan['id'] !== 95685099 || $plan['path'] !== 'Android-App/Readme.md') {
        throw new RuntimeException('Unexpected page target');
    }
    if (!hash_equals($plan['beforeSha256'], hash('sha256', $plan['beforeContent']))) {
        throw new RuntimeException('Invalid read-derived backup');
    }
    $db = \OC::$server->get(\OCP\IDBConnection::class);
    $qb = $db->getQueryBuilder();
    $qb->select('path')->from('filecache')
        ->where($qb->expr()->eq('fileid', $qb->createNamedParameter(95685099, \OCP\DB\QueryBuilder\IQueryBuilder::PARAM_INT)));
    if ($qb->executeQuery()->fetchOne() !== 'appdata_oc1asepkpk60/collectives/7/Mission Leben Zentral/Android-App/Readme.md') {
        throw new RuntimeException('File ID/path changed');
    }
    $folder = \OC::$server->get(\OCP\Files\AppData\IAppDataFactory::class)
        ->get('collectives')->getFolder('7')->getFolder('Mission Leben Zentral')->getFolder('Android-App');
    $file = $folder->getFile('Readme.md');
    if ($file->getId() !== 95685099) {
        throw new RuntimeException('AppData page ID changed');
    }
    $expected = $rollback ? hash('sha256', $plan['content']) : $plan['beforeSha256'];
    if (!hash_equals($expected, hash('sha256', $file->getContent()))) {
        throw new RuntimeException('Concurrent page edit detected');
    }
    $attachmentName = '.attachments.95685099';
    $attachments = $folder->fileExists($attachmentName) ? $folder->getFolder($attachmentName) : null;
    $names = [
        'app-hilfe-symbole.png', 'app-hilfe-favorit.png', 'app-hilfe-nachrichten.png',
        'app-hilfe-kalender.png', 'app-hilfe-problem-melden.png',
    ];
    $outImages = [];
    if (!$rollback) {
        if (count($plan['images']) !== count($names)) {
            throw new RuntimeException('Unexpected image count');
        }
        $payloads = [];
        foreach ($plan['images'] as $image) {
            $name = $image['name'];
            if (!in_array($name, $names, true) || isset($payloads[$name])) {
                throw new RuntimeException('Unexpected or duplicate image name');
            }
            $bytes = file_get_contents(dirname($planPath) . '/' . $name);
            $dimensions = getimagesizefromstring($bytes);
            if (!hash_equals($image['sha256'], hash('sha256', $bytes)) ||
                $dimensions === false || $dimensions[0] !== 450 || $dimensions[1] > 200 ||
                $dimensions['mime'] !== 'image/png') {
                throw new RuntimeException('Image validation failed: ' . $name);
            }
            if ($attachments !== null && $attachments->fileExists($name) &&
                !hash_equals($image['sha256'], hash('sha256', $attachments->getFile($name)->getContent()))) {
                throw new RuntimeException('Refusing to overwrite different attachment: ' . $name);
            }
            $payloads[$name] = $bytes;
        }
        $attachments ??= $folder->newFolder($attachmentName);
        foreach ($plan['images'] as $image) {
            $name = $image['name'];
            $node = $attachments->fileExists($name) ? $attachments->getFile($name)
                : $attachments->newFile($name, $payloads[$name]);
            if (!hash_equals($image['sha256'], hash('sha256', $node->getContent()))) {
                throw new RuntimeException('Image readback failed: ' . $name);
            }
            $outImages[] = ['name' => $name, 'id' => $node->getId(), 'sha256' => $image['sha256']];
        }
    }
    // Check again after attachment creation; never overwrite an intervening edit.
    if (!hash_equals($expected, hash('sha256', $file->getContent()))) {
        throw new RuntimeException('Concurrent page edit detected before save');
    }
    $content = $rollback ? $plan['beforeContent'] : $plan['content'];
    $file->putContent($content);
    $actual = hash('sha256', $file->getContent());
    if (!hash_equals(hash('sha256', $content), $actual)) {
        throw new RuntimeException('Page readback failed');
    }
    echo json_encode([
        'operation' => $rollback ? 'rollback' : 'update', 'pageId' => $file->getId(),
        'pageSha256' => $actual, 'images' => $outImages,
        'rollbackNote' => 'Restore only the guarded page content; leave new attachments recoverable.',
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT), "\n";
} catch (Throwable $e) {
    fwrite(STDERR, get_class($e) . ': ' . $e->getMessage() . "\n");
    exit(1);
}
