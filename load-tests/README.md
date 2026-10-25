# load-tests

[Grafana k6](https://grafana.com/docs/k6/latest/) scripts against `gateway-service` (:8080). Every request goes
gateway → Eureka lookup (`lb://`) → backend, and `/api/orders/{id}/with-user` adds an order-service → user-service hop.

## Install k6 (once)

```bash
# Linux x86_64, no sudo: binary into ~/.local/bin (already on PATH)
V=v2.3.0
curl -sSfLO https://github.com/grafana/k6/releases/download/$V/k6-$V-linux-amd64.tar.gz
tar xzf k6-$V-linux-amd64.tar.gz && install -m 755 k6-$V-linux-amd64/k6 ~/.local/bin/k6
k6 version
```

## Run

Start the stack first (root [Run order](../README.md#run-order)).

```bash
k6 run load-tests/gateway.js                     # smoke: 1 VU, 30 s
k6 run -e PROFILE=load load-tests/gateway.js     # 20 VUs, ~2 min
k6 run -e PROFILE=stress load-tests/gateway.js   # up to 100 VUs, ~3.5 min
k6 run -e BASE_URL=http://localhost:8080 -e ACTUATOR_USER=ops -e ACTUATOR_PASSWORD=s3cret load-tests/gateway.js
```

| Profile           | VUs                   | Duration |
|-------------------|-----------------------|----------|
| `smoke` (default) | 1                     | 30 s     |
| `load`            | ramp 0 → 20 → 0       | 2 min    |
| `stress`          | ramp 0 → 50 → 100 → 0 | 3.5 min  |

- ***Thresholds*** — the run fails (non-zero exit) if errors ≥ 1 %, p95 latency ≥ 500 ms, or checks pass < 99 %.
- ***Endpoints*** — `GET /api/users`, `/api/users/{id}`, `/api/products`, `/api/orders`, `/api/orders/{id}/with-user`
  (order IDs 101–106, user IDs 1–5 from the seed data); each iteration then sleeps 1 s.
- ***Watch it live*** — Zipkin (http://localhost:9411) shows the traces, Spring Boot Admin (http://localhost:8084) the
  metrics; add `--out web-dashboard` for k6's own live dashboard at http://localhost:5665.
- ***Eureka failover test*** — run `PROFILE=load` and stop one eureka node mid-run: requests keep succeeding because
  clients serve from their cached registry and fail over to another node.
