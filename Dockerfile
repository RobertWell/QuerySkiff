# QuerySkiff root Dockerfile — RETIRED (HEL-149).
#
# The HEL-95 cutover moved QuerySkiff to Kotlin/Quarkus. Both k8s deployments
# (k8s/deployment.yaml and k8s/deployment-jvm.yaml) run
# docker.io/roguerzzz123/queryskiff-jvm:latest, and prod was verified running
# ONLY that image on 2026-08-05 (both pods, 0 restarts, 4d+ uptime).
#
# The old Python runtime build previously lived here and would still produce a
# deployable uvicorn image, so an ordinary `docker build .` at the repo root
# silently built the retired stack. It is preserved verbatim (for reference and
# for the pre-cutover rollback story) at:
#
#     backend/Dockerfile.python-retired
#
# THE SUPPORTED BUILD IS:
#
#     docker build -f backend-jvm/Dockerfile -t queryskiff-jvm .
#
# This file fails loudly rather than quietly building the wrong thing. The
# backend/ Python tree itself is intentionally still present until the HEL-131
# soak reaches its go/no-go boundary — quarantining the BUILD removes the
# accident without destroying the rollback material.
FROM alpine:3.20
RUN echo "QuerySkiff: the root Dockerfile is retired (HEL-149)." \
 && echo "Use: docker build -f backend-jvm/Dockerfile -t queryskiff-jvm ." \
 && echo "The retired Python build is kept at backend/Dockerfile.python-retired." \
 && exit 1
