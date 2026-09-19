FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN groupadd --system --gid 10002 enrollment \
    && useradd --system --uid 10002 --gid enrollment --home-dir /nonexistent --shell /usr/sbin/nologin enrollment

WORKDIR /opt/mission-leben-device-enrollment
COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir .

USER 10002:10002
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/healthz', timeout=3).read()"]

# A single worker serializes redemption of short-lived Authentik enrollment
# tokens. Scale-out requires a shared distributed lock.
CMD ["uvicorn", "mission_leben_device_enrollment.app:create_app_from_env", "--factory", "--host", "0.0.0.0", "--port", "8080", "--workers", "1", "--no-access-log"]
