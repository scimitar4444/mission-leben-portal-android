<?php

declare(strict_types=1);

namespace OCA\MissionLebenAnnouncements\Service;

use OCP\IConfig;
use OCP\IRequest;

class SignedRequestVerifier {
    private const MAX_CLOCK_SKEW = 60;

    public function __construct(
        private IConfig $config,
        private IRequest $request,
    ) {
    }

    public function valid(string $method, string $path, string $rawBody): bool {
        $settings = $this->config->getSystemValue('missionleben_announcements', []);
        $secret = is_array($settings) ? (string)($settings['secret'] ?? '') : '';
        $timestamp = $this->request->getHeader('X-ML-Timestamp');
        $nonce = $this->request->getHeader('X-ML-Nonce');
        $signature = $this->request->getHeader('X-ML-Signature');
        if (
            strlen($secret) < 32
            || !preg_match('/^[0-9]{10}$/', $timestamp)
            || abs(time() - (int)$timestamp) > self::MAX_CLOCK_SKEW
            || !preg_match('/^[A-Za-z0-9_-]{16,64}$/', $nonce)
            || !preg_match('/^[A-Za-z0-9_-]{43}$/', $signature)
        ) {
            return false;
        }
        $canonical = strtoupper($method) . "\n" . $path . "\n" . $timestamp . "\n" . $nonce . "\n"
            . hash('sha256', $rawBody);
        $expected = rtrim(strtr(base64_encode(hash_hmac('sha256', $canonical, $secret, true)), '+/', '-_'), '=');
        return hash_equals($expected, $signature);
    }
}
