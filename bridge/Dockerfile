FROM docker.io/binwiederhier/ntfy:v2.28.0@sha256:f8060a494b69d53866ef505c36a205efcce3204618d6e183a87128cca6e7cac9 AS ntfy

FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN groupadd --system --gid 10001 bridge \
    && useradd --system --uid 10001 --gid bridge --home-dir /nonexistent --shell /usr/sbin/nologin bridge

WORKDIR /opt/mission-leben-bridge
COPY pyproject.toml ./
COPY src ./src
COPY --from=ntfy /usr/bin/ntfy /usr/local/bin/ntfy
RUN pip install --no-cache-dir .

USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/healthz', timeout=3).read()"]

CMD ["python", "-m", "mission_leben_bridge"]
