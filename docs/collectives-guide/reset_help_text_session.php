<?php
declare(strict_types=1);
// Repair only the already-published Android help's stale Text state, never force unsaved edits.
define('OC_CONSOLE', true);
require '/opt/nextcloud/lib/base.php';

try {
    $directory = $argv[1] ?? '';
    if (!preg_match('~^/var/tmp/ml-help-text\.[A-Za-z0-9]+$~', $directory) ||
        !is_dir($directory) || is_link($directory) || (fileperms($directory) & 0077) !== 0) {
        throw new RuntimeException('Expected a private, task-owned backup directory');
    }
    umask(0077);
    $expectedSha = '43455701e7c99b5b0d7bd3e779f9358a272af61119179984c2da1faeab5d3cbc';
    $id = 95685099;
    $db = \OC::$server->get(\OCP\IDBConnection::class);
    $qb = $db->getQueryBuilder();
    $qb->select('path')->from('filecache')->where($qb->expr()->eq('fileid', $qb->createNamedParameter($id)));
    if ($qb->executeQuery()->fetchOne() !== 'appdata_oc1asepkpk60/collectives/7/Mission Leben Zentral/Android-App/Readme.md') {
        throw new RuntimeException('Help file ID/path changed');
    }
    $folder = \OC::$server->get(\OCP\Files\AppData\IAppDataFactory::class)
        ->get('collectives')->getFolder('7')->getFolder('Mission Leben Zentral')->getFolder('Android-App');
    $file = $folder->getFile('Readme.md');
    if ($file->getId() !== $id || !hash_equals($expectedSha, hash('sha256', $file->getContent()))) {
        throw new RuntimeException('Published help content changed');
    }
    $service = \OC::$server->get(\OCA\Text\Service\DocumentService::class);
    $document = $service->getDocument($id);
    if ($document === null || $document->getChecksum() === $service::computeCheckSum($file->getContent())) {
        echo json_encode(['id' => $id, 'status' => 'already_current']), PHP_EOL;
        exit(0);
    }
    if ($service->hasUnsavedChanges($document)) {
        throw new RuntimeException('Refusing reset: Text has unsaved edits');
    }
    $snapshot = ['id' => $id, 'time' => gmdate('c'), 'content' => $file->getContent(), 'tables' => []];
    foreach (['text_documents' => 'id', 'text_steps' => 'document_id', 'text_sessions' => 'document_id'] as $table => $column) {
        $qb = $db->getQueryBuilder();
        $qb->select('*')->from($table)->where($qb->expr()->eq($column, $qb->createNamedParameter($id)));
        $snapshot['tables'][$table] = $qb->executeQuery()->fetchAllAssociative();
    }
    try {
        $snapshot['state'] = $service->getStateFile($id)->getContent();
    } catch (\OCP\Files\NotFoundException) {
        $snapshot['state'] = null;
    }
    // State and session tokens stay in this private server-side snapshot, never in output or Git.
    $bytes = gzencode(serialize($snapshot), 6);
    if ($bytes === false) {
        throw new RuntimeException('Snapshot encoding failed');
    }
    $backup = $directory . '/help-text-state.ser.gz';
    $handle = fopen($backup, 'xb');
    if ($handle === false || fwrite($handle, $bytes) !== strlen($bytes)) {
        throw new RuntimeException('Snapshot write failed');
    }
    fclose($handle);
    if (!hash_equals(hash('sha256', $bytes), hash_file('sha256', $backup))) {
        throw new RuntimeException('Snapshot readback failed');
    }
    $current = $service->getDocument($id);
    if ($current === null || $current->getLastSavedVersion() !== $document->getLastSavedVersion() ||
        $service->hasUnsavedChanges($current) || !hash_equals($expectedSha, hash('sha256', $file->getContent()))) {
        throw new RuntimeException('Concurrent help edit detected');
    }
    $service->resetDocument($id); // Supported Text operation, force=false.
    if ($service->getDocument($id) !== null || !hash_equals($expectedSha, hash('sha256', $file->getContent()))) {
        throw new RuntimeException('Reset postcheck failed');
    }
    echo json_encode(['id' => $id, 'status' => 'stale_text_session_reset', 'contentSha256' => $expectedSha,
        'backupPath' => $backup, 'backupSha256' => hash_file('sha256', $backup),
        'backedUpRows' => array_map('count', $snapshot['tables'])], JSON_PRETTY_PRINT), PHP_EOL;
} catch (Throwable $error) {
    fwrite(STDERR, get_class($error) . ': ' . $error->getMessage() . PHP_EOL);
    exit(1);
}
