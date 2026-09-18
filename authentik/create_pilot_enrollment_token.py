"""Create a 24-hour Authentik enrollment token for the Android pilot.

The caller must redirect stdout to a root-only file. The final output line is the token key.
"""

from datetime import timedelta

from django.utils.timezone import now

from authentik.endpoints.connectors.agent.models import AgentConnector, EnrollmentToken
from authentik.endpoints.models import DeviceAccessGroup


connector = AgentConnector.objects.get(name="Mission Leben Android")
device_group = DeviceAccessGroup.objects.get(name="Mission Leben Android - Pilot")
token = EnrollmentToken.objects.create(
    name="Mission Leben Android Pilot",
    connector=connector,
    device_group=device_group,
    expiring=True,
    expires=now() + timedelta(hours=24),
)
print(token.key)
