# QuerySkiff root Dockerfile — RETIRED (HEL-149).
#
# The HEL-95 cutover moved QuerySkiff to Kotlin/Quarkus. Both k8s deployments
# (k8s/deployment.yaml and k8s/deployment-jvm.yaml) run
# docker.io/roguerzzz123/queryskiff-jvm:latest, and prod was verified running
# ONLY that image on 2026-08-05 (both pods, 0 restarts, 4d+ uptime).
#
# The old Python runtime build previously lived here and would still produce a
# deployable uvicorn image, so an ordinary `docker build .` at the repo root
# silently built the retired stack. This file now fails loudly instead.
#
# THE SUPPORTED BUILD IS:
#
#     docker build -f backend-jvm/Dockerfile -t queryskiff-jvm .
#
# The Python source tree (backend/, 191 MB) was DELETED on 2026-08-06 at the
# owner's direction. Nothing is lost that rollback depends on:
#
#   * The rollback target is the PUBLISHED IMAGE docker.io/roguerzzz123/queryskiff:latest,
#     which lives in the registry independently of this repo — verified present
#     on 2026-08-06. Rolling back is a one-line image flip in k8s/deployment.yaml,
#     and never required the source.
#   * The source itself remains recoverable from git history at 278d203^.
#
# Deleting the source therefore removes duplicate maintenance without removing
# any rollback capability.
FROM alpine:3.20
RUN echo "QuerySkiff: the root Dockerfile is retired (HEL-149)." \
 && echo "Use: docker build -f backend-jvm/Dockerfile -t queryskiff-jvm ." \
 && echo "The Python backend was removed 2026-08-06; rollback image: roguerzzz123/queryskiff:latest" \
 && exit 1
