FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN groupadd --system --gid 10001 bridge \
    && useradd --system --uid 10001 --gid bridge --home-dir /nonexistent --shell /usr/sbin/nologin bridge

WORKDIR /opt/mission-leben-bridge
COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir .

USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/healthz', timeout=3).read()"]

CMD ["python", "-m", "mission_leben_bridge"]
