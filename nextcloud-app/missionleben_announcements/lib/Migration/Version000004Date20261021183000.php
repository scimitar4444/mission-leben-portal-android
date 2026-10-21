<?php
declare(strict_types=1);

namespace OCA\MissionLebenAnnouncements\Migration;

use Closure;
use OCP\DB\ISchemaWrapper;
use OCP\DB\Types;
use OCP\Migration\IOutput;
use OCP\Migration\SimpleMigrationStep;

class Version000004Date20261021183000 extends SimpleMigrationStep {
    public function changeSchema(IOutput $output, Closure $schemaClosure, array $options): ?ISchemaWrapper {
        $schema = $schemaClosure();
        if ($schema->hasTable('ml_announcement_history')) {
            return null;
        }
        $table = $schema->createTable('ml_announcement_history');
        $table->addColumn('announcement_id', Types::BIGINT, ['notnull' => true]);
        $table->addColumn('payload', Types::TEXT, ['notnull' => true]);
        $table->addColumn('content_hash', Types::STRING, ['notnull' => true, 'length' => 64]);
        $table->addColumn('retain_until', Types::BIGINT, ['notnull' => true]);
        $table->setPrimaryKey(['announcement_id']);
        $table->addIndex(['retain_until'], 'ml_ann_history_retention');
        return $schema;
    }
}
