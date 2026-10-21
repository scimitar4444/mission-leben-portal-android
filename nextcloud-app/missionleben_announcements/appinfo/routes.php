<?php

declare(strict_types=1);

return [
    'routes' => [
        ['name' => 'announcementPush#catalog', 'url' => '/api/v1/announcement-push/catalog', 'verb' => 'POST'],
        ['name' => 'announcementPush#resolve', 'url' => '/api/v1/announcement-push/resolve', 'verb' => 'POST'],
        [
            'name' => 'announcements#list',
            'url' => '/api/v1/announcements',
            'verb' => 'POST',
        ],
        [
            'name' => 'talkParticipants#list',
            'url' => '/api/v1/talk-participants',
            'verb' => 'POST',
        ],
        [
            'name' => 'talkBotSync#sync',
            'url' => '/api/v1/talk-bot-sync',
            'verb' => 'POST',
        ],
        [
            'name' => 'talkRooms#list',
            'url' => '/api/v1/my-talk-rooms',
            'verb' => 'GET',
        ],
    ],
];
