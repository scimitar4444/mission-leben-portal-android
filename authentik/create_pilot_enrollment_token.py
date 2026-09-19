"""Deprecated: broad pilot enrollment is intentionally disabled."""

raise RuntimeError(
    "Broad pilot enrollment is disabled. Use create_enrollment_token.py with "
    "ML_DEVICE_MODE plus ML_AUTHENTIK_USERNAME or ML_AUTHENTIK_GROUP."
)
