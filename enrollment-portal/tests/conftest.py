from __future__ import annotations

import pytest

from mission_leben_device_enrollment.config import Settings


@pytest.fixture
def settings() -> Settings:
    return Settings(
        authentik_base_url="https://id.example.org",
        authentik_api_token="a" * 40,
        agent_connector_uuid="123e4567-e89b-12d3-a456-426614174000",
        public_origin="https://geraete.example.org",
        proxy_app_slug="mission-leben-device-init",
        csrf_secret="c" * 40,
    )
