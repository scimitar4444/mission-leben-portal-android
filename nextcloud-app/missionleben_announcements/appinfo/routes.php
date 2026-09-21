<?php

declare(strict_types=1);

return [
    'routes' => [
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
