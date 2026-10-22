# deploy/trino: the HEL-113 proof of concept (historical)

**Not the deployed Trino.** The running Trino (ns `trino` on the LAN cluster) is configured
in hello-sre, at `hello-trino/apps/hello-trino/manifests/` (`configmap.yaml`, `deployment.yaml`,
`service.yaml`), and managed by ArgoCD. To change Trino, edit it there.

The 2026-07-30 PoC configs that sat here were removed in HEL-197. They used a different port
(8090, not 8080), a different discovery URI, and Oracle/Postgres catalogs that the GitOps
config does not carry. Editing them changed nothing that was deployed. They are in git history
before the HEL-197 commit (last changed in `b4c0250`).

What stays is the evidence behind `docs/hel113-trino-evaluation.md`: the benchmark harness and
its raw numbers in `poc/` (`bench_trino.py`, `bench_duckdb.py`, `*_results.json`).
