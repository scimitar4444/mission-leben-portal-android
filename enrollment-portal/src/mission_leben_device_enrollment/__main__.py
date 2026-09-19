from __future__ import annotations

import uvicorn


def main() -> None:
    uvicorn.run(
        "mission_leben_device_enrollment.app:create_app_from_env",
        factory=True,
        host="0.0.0.0",
        port=8080,
        workers=1,
        access_log=False,
    )


if __name__ == "__main__":
    main()
