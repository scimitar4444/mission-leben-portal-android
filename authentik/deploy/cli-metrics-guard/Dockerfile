# This overlay preserves the currently deployed AD/group/flow customizations.
# Rebase only after inspecting the target vendor launcher and updating the guard.
ARG BASE_IMAGE=local/authentik-ml-ad-source-guard:2026.8.3-v1
FROM ${BASE_IMAGE}
USER root
RUN printf '%s  %s\n' \
    ebd34b20ab047cf912e619e7d9aeb260a4f51e3c7840d3691c053ee0de2bb62d \
    /lifecycle/ak | sha256sum -c - \
    && mv /lifecycle/ak /lifecycle/ak-vendor
COPY ak-cli-metrics-guard.sh /lifecycle/ak
RUN chmod 0755 /lifecycle/ak
USER 1000
