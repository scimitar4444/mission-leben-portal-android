<?php
declare(strict_types=1);
if (gethostname() !== 'ml-nextcloud') { throw new RuntimeException('Wrong host'); }
chdir('/opt/nextcloud');
require '/opt/nextcloud/lib/base.php';
$config = \OCP\Server::get(\OCP\IConfig::class);
if ($config->getAppValue('missionleben_announcements', 'installed_version', '') !== '0.3.3') {
    throw new RuntimeException('Unexpected installed adapter version');
}
if (!\OC_App::updateApp('missionleben_announcements')) {
    throw new RuntimeException('Targeted adapter upgrade failed');
}
echo 'adapter_version=', $config->getAppValue('missionleben_announcements', 'installed_version', ''), "\n";
$db = \OCP\Server::get(\OCP\IDBConnection::class);
$q = $db->getQueryBuilder();
echo 'archive_rows=', $q->select($q->func()->count('*'))->from('ml_announcement_history')->executeQuery()->fetchOne(), "\n";
