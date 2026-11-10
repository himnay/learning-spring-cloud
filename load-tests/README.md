# load-tests

## <span style="color:hsl(151,80%,58%)">Table of contents</span>

1. 📥 [Install k6 (once)](#install-k6)
2. 🚀 [Run](#run)
3. ❓ [Q&A](#qa)
    - 3.1 🔥 [With `sleep(1)` and 100 VUs, what request rate does `stress` really generate — and why can this closed-model test hide a latency regression?](#qa-closed-vs-open-model)
    - 3.2 🔥 [`/with-user` passes its check even when `user-service` is down — why is the test blind to that, and what else do the aggregate thresholds hide?](#qa-blind-checks-thresholds)
    - 3.3 🔥 [One shared `admin` user, 11 hot IDs, k6 on the same laptop as ~10 JVMs — how do these results misrepresent production?](#qa-test-realism)
    - 3.4 🔥🔥 [Use this harness to find the stack's capacity limit and its first bottleneck — design the experiment](#qa-capacity-experiment)
    - 3.5 🔥🔥 [Turn the "Eureka failover test" into a rigorous resilience test: kill a `user-service` instance mid-run — what do you expect, and what are the pass/fail criteria?](#qa-resilience-under-load)

[Grafana k6](https://grafana.com/docs/k6/latest/) scripts against `gateway-service` (:8080). Every request goes
gateway → Eureka lookup (`lb://`) → backend, and `/api/orders/{id}/with-user` adds an order-service → user-service hop.

<a id="install-k6"></a>

## Install k6 (once)

```bash
# Linux x86_64, no sudo: binary into ~/.local/bin (already on PATH)
V=v2.3.0
curl -sSfLO https://github.com/grafana/k6/releases/download/$V/k6-$V-linux-amd64.tar.gz
tar xzf k6-$V-linux-amd64.tar.gz && install -m 755 k6-$V-linux-amd64/k6 ~/.local/bin/k6
k6 version
```

<a id="run"></a>

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
- ***Watch it live*** — Zipkin (http://localhost:9411) shows the traces, Spring Boot Admin (https://localhost:8084) the
  metrics; add `--out web-dashboard` for k6's own live dashboard at http://localhost:5665.
- ***Eureka failover test*** — run `PROFILE=load` and stop one eureka node mid-run: requests keep succeeding because
  clients serve from their cached registry and fail over to another node.

<a id="qa"></a>

## <span style="color:hsl(31,80%,58%)">❓ Q&A</span>

<a id="qa-closed-vs-open-model"></a>

**Q1. 🔥 With `sleep(1)` and 100 VUs, what request rate does `stress` really generate — and why can this closed-model
test hide a latency regression?**

- ***The rate is not fixed: it falls as the system slows down, because each VU waits for its responses before
  sending more.*** That is a closed model; real users arriving from the internet behave like an open model.
- ***Arithmetic*** — one iteration = 6 sequential requests + 1 s sleep, so

```text
req/s = VUs × 6 / (6 × latency + 1 s)
latency  10 ms → 100 × 6 / 1.06 s ≈ 566 req/s
latency 500 ms → 100 × 6 / 4.00 s ≈ 150 req/s
```

- ***Why it hides regressions*** — when the stack slows down, k6 automatically sends less: the server gets relief
  exactly when it should be pushed, and latency measured on fewer requests looks better than production would see
  (coordinated omission — the requests that would have waited in a queue are never sent).
- ***Fix: an open model*** — the `constant-arrival-rate` or `ramping-arrival-rate` executor starts iterations at a
  fixed rate regardless of response time (`rate`, `timeUnit`, `preAllocatedVUs`, `maxVUs`). If k6 runs out of VUs it
  reports `dropped_iterations` — itself a signal that the system can't keep up.

```javascript
export const options = {
    scenarios: {
        steady: {executor: 'constant-arrival-rate', rate: 200, timeUnit: '1s', duration: '2m',
                 preAllocatedVUs: 50, maxVUs: 400},
    },
};
```

- ***When closed is right*** — a fixed pool of clients (a batch job with N workers, a connection-pooled upstream);
  pick the model that matches who calls you.

<a id="qa-blind-checks-thresholds"></a>

**Q2. 🔥 `/with-user` passes its check even when `user-service` is down. Why is the test blind to that, and what else do
the aggregate thresholds hide?**

- ***Because the check only looks at the status code, and `order-service` degrades gracefully*** — `UserClient`
  catches the `RestClientException` and the endpoint answers `200` with `"user": null`. The run stays green while the
  feature is broken.
- ***Check the body, not just the status***:

```javascript
check(withUser, {
    'order with user 200': (r) => r.status === 200,
    'user resolved': (r) => r.json('user') !== null,
});
```

- ***Aggregate thresholds hide per-endpoint regressions*** — `http_req_duration: p(95)<500` is computed over all six
  requests. `/with-user` (two hops) can triple its latency while the five cheaper calls keep the global p95 green.
  Thresholds per tagged endpoint fix that:

```javascript
thresholds: {
    'http_req_duration{name:GET /api/orders/{id}/with-user}': ['p(95)<800'],
    'http_req_duration{name:GET /api/users/{id}}': ['p(95)<300'],
    'checks{check:user resolved}': ['rate>0.99'],
},
```

- ***Error rate hides error kind*** — `http_req_failed < 1 %` treats a `401` (credential drift between modules) like
  a `503` (no instance); tag or count by status to know which one you have.
- ***Interview point*** — graceful degradation needs its own SLI (share of `/with-user` responses with a user);
  otherwise the fallback turns outages into silent data-quality issues.

<a id="qa-test-realism"></a>

**Q3. 🔥 One shared `admin` user, 11 hot IDs, k6 on the same laptop as ~10 JVMs. How do these results misrepresent
production?**

- ***They measure a warm cache on a contended laptop, not the system under production conditions.***

| Assumption in the script                    | Effect on results                                                                                                                               | Fix                                                                       |
|---------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------|
| Order IDs 101–106 only                      | `OrderService.findById` is `@Cacheable` and the cache is pre-warmed by `OrderCacheRefresher` → ~100 % hits, Postgres barely touched (`orders.db.reads` stays flat) | IDs from a realistic distribution over a large seeded data set           |
| 5 users, tiny tables                        | every query hits a few hot pages in Postgres' buffer cache                                                                                      | production-sized data, cold-cache runs                                    |
| One shared credential                       | identical auth work per request; a per-client rate limiter would see one client                                                                  | a pool of test identities                                                 |
| Basic auth with an in-memory user           | Boot's default in-memory user isn't password-hashed, so per-request Basic auth is nearly free; with a hashed store (BCrypt), every hop re-hashes on every request | measure with the production auth scheme (tokens), or include the hashing |
| k6 and the whole stack on one machine       | load generator, ~10 JVMs, 3 Postgres containers share CPU — you measure CPU contention                                                          | load generator on separate hardware; watch k6's own CPU                   |
| 100 % trace sampling                        | span creation and export cost included                                                                                                          | production sampling rate                                                  |
| Short runs, cold JVMs                       | JIT warm-up and the first cache refresh land inside the measurement                                                                             | warm-up stage excluded from thresholds                                    |

- ***What the current test is good for*** — a smoke/regression gate (routing, auth, discovery, trace propagation
  working end to end) — not a capacity number.

<a id="qa-capacity-experiment"></a>

**Q4. 🔥🔥 Use this harness to find the stack's capacity limit and its first bottleneck. Design the experiment.**

***Step the arrival rate (open model) until latency bends, watch saturation signals on every hop, then confirm the
bottleneck by relieving it and re-running — one variable at a time.***

- ***Set-up***
  - Separate machine for k6; production-like data and sampling ([Q3](#qa-test-realism)); a warm-up stage excluded
    from thresholds.
  - Open model, `ramping-arrival-rate` in steps (e.g. +50 req/s every 2 min), each step long enough to reach steady
    state.
- ***What to watch per hop (USE: utilisation, saturation, errors)***:

| Hop                         | Saturation signal                                                                                     |
|-----------------------------|-------------------------------------------------------------------------------------------------------|
| gateway (Netty)             | event-loop CPU, pending connections, 5xx/504                                                         |
| `order-service` (Tomcat)    | busy threads near max (200 by default), `/with-user` p99, outbound `http.client.requests` to `USER-SERVICE` |
| DB-backed services          | HikariCP active connections at the pool max (10 by default) and connection wait time                  |
| Postgres                    | CPU, locks, slow queries                                                                              |
| Eureka                      | not on the request path — should stay flat; if not, something is fetching per request                 |

- ***Find the knee*** — throughput grows linearly with offered rate until a resource saturates; past it, latency
  climbs and `dropped_iterations` appears. Little's law tells you which pool fills first: concurrency =
  rate × latency, compared with 200 Tomcat threads or 10 DB connections.
- ***Expected first suspects here*** — the DB connection pool on `user-service`/`product-service` (every `/api/users`
  and `/api/products` hits Postgres; orders by ID are cached), then `order-service` threads for `/with-user`, which
  hold a thread across the downstream call.
- ***Confirm*** — relieve the suspected bottleneck (bigger pool, second instance of the service — Eureka + round
  robin spread it automatically) and re-run: capacity should move; if it doesn't, the suspect was wrong.
- ***Report*** — max sustainable rate at the SLO (e.g. p95 < 500 ms, errors < 1 %), the bottleneck, and the
  headroom you'd run at (typically ~60–70 % of the knee).
- ***Pitfalls*** — changing several things between runs; trusting averages; ignoring k6's own CPU; reading Zipkin
  at 100 % sampling as if it were free.

<a id="qa-resilience-under-load"></a>

**Q5. 🔥🔥 Turn the "Eureka failover test" into a rigorous resilience test: kill a `user-service` instance mid-run.
What do you expect, and what are the pass/fail criteria?**

***Predict the failure window from the configuration first, then run the experiment and compare: a resilience test is
a hypothesis test, not "see what happens".***

- ***Set-up*** — 2 `user-service` instances (second one on another port, see the root README's load-balancing
  section), `PROFILE=load` or an arrival-rate scenario, kill one instance at a recorded timestamp.
- ***Hypothesis per kill mode***:

| Kill mode           | What callers see                                                                                                    | Expected window                                                                                       |
|---------------------|---------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------|
| `kill -9` (crash)   | ~50 % of user calls go to the dead instance (round robin over 2) until every cache drops it                         | lease ~60 s + sweep ≤ 30 s + server response cache + client fetch + LB cache ≈ up to ~3 min ([eureka-server Q2](../eureka-server/README.md#qa-crash-eviction-timeline)) |
| SIGTERM (graceful)  | in-flight requests finish; new connections are refused during drain, then deregistration                            | drain + ~95 s of cache layers ([root FAQ 12.13](../README.md#faq-zero-error-rolling-deploy))            |

  - Gateway routes → client-visible 5xx (no `Retry` filter). `/with-user` → still `200`, `user: null` — only visible
    with the body check from [Q2](#qa-blind-checks-thresholds).
- ***Pass/fail criteria*** (agree them before the run):
  - Error budget: e.g. ≤ 1 % failed requests over the whole run, and no errors after window + margin.
  - Recovery time: errors stop within the predicted window; latency returns to baseline.
  - No collateral damage: `order-service` threads don't saturate, other routes keep their p95.
  - Registry convergence: the dead instance disappears from `GET /eureka/apps/USER-SERVICE` within the predicted
    time.
- ***Measure the window*** — k6 can't kill processes itself, so script the kill outside k6 and log its timestamp;
  export k6 results with timestamps (`--out json` or `--out csv`) and plot errors over time against the kill.
- ***Then improve and re-run*** — add a gateway `Retry` filter for GETs and LB retry for `UserClient`: the expected
  result is near-zero client errors with a small latency bump during the window. If the result differs from the
  prediction, the model of the system is wrong — that is the real finding.
- ***Also test*** — killing one eureka node (should be a non-event: clients fail over, caches serve), killing *all*
  eureka nodes (traffic between known instances keeps flowing — [eureka-server Q5](../eureka-server/README.md#qa-full-outage-cold-restart)),
  and a slow (not dead) instance, which is the nastiest case ([root FAQ 12.11](../README.md#faq-slow-downstream-cascade)).
