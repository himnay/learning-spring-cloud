# <span style="color:hsl(13,80%,58%)">zipkin-server</span>

## <span style="color:hsl(151,80%,58%)">Table of contents</span>

1. 🔭 [How spans get here](#how-spans-get-here)
2. 📦 [Why this module has no parent and runs Boot 3.5](#no-parent-boot-35)
3. ⚙️ [Configuration](#configuration)
4. 🚀 [Run](#run)
5. 🧪 [Tests](#tests)
6. ⚠️ [Gotchas](#gotchas)
7. ❓ [Q&A](#qa)
    - 7.1 🔥 [Sampling is 100 % and storage is in-memory — at 500 req/s through the gateway, what fails first, and does it hurt the services?](#qa-sampling-memory-capacity)
    - 7.2 🔥 [Every service sets `probability: 0.1` on its own — do you get 10 % complete traces or a mess of partial ones, and who actually decides?](#qa-head-sampling-who-decides)
    - 7.3 🔥 [Why do some spans start their own trace instead of joining the request's — e.g. `OrderCacheRefresher` runs, work handed to an executor, a Reactor chain?](#qa-context-propagation-boundaries)
    - 7.4 🔥🔥 [50 services, 5k req/s: keep every failed or slow (> 2 s) trace, but only ~1 % of the rest — design the pipeline](#qa-tail-sampling-pipeline)
    - 7.5 🔥🔥 [This module pins Boot 3.5, escapes the repo's BOM and relies on Zipkin's internal `@EnableZipkinServer` — as the architect, what are the risks and what is your plan?](#qa-boot35-internal-api-risk)

Distributed tracing backend for the service-discovery demo: a Spring Boot module, like `eureka-server`,
that runs the Zipkin server through `@EnableZipkinServer`, with an OTLP/HTTP receiver so the services
can export straight to it. No Docker. See the root [../README.md](../README.md) for the whole picture.

<a id="how-spans-get-here"></a>

## <span style="color:hsl(151,80%,58%)">How spans get here</span>

```mermaid
flowchart LR
    GW["gateway-service"] -- " OTLP/HTTP " --> ZIP
    ORD["order-service"] -- " OTLP/HTTP " --> ZIP
    USR["user-service"] -- " OTLP/HTTP " --> ZIP
    PRD["product-service"] -- " OTLP/HTTP " --> ZIP
    ZIP["zipkin-server :9411<br/>/v1/traces (OTLP) · /api/v2/** · UI /zipkin<br/>in-memory storage"] -- " register " --> EUR["eureka-server :8761"]
```

- ***Services speak OpenTelemetry*** — `gateway-service`, `user-service`, `order-service` and `product-service`
  have `spring-boot-starter-opentelemetry` (Micrometer Tracing + OTel bridge + OTLP exporter) and export to
  `management.opentelemetry.tracing.export.otlp.endpoint` = `http://localhost:9411/v1/traces`
  (override with `OTLP_TRACES_ENDPOINT`).
- ***Zipkin takes OTLP itself*** — [`zipkin-module-otel`](https://github.com/openzipkin-contrib/zipkin-otel)
  adds the `/v1/traces` receiver on the same port as the UI and API, and converts OTLP spans to Zipkin's model.
  No OpenTelemetry Collector in between.
- ***Propagation*** — W3C `traceparent`. The gateway's routed calls and order-service's load-balanced
  `RestClient` (`LoadBalancerConfig` sets the `ObservationRegistry`) send it, so
  `GET /api/orders/101/with-user` through the gateway is one trace across three services.
- ***Sampling 100 %*** — `management.tracing.sampling.probability: 1.0` (Boot's default is 0.1).
- ***Noise filtered*** — each service's `TracingConfig` drops `/actuator/**` (Spring Boot Admin polling),
  `/eureka/**` client calls (heartbeats) and Spring Security's per-filter spans.
- ***Log correlation*** — Boot adds `[traceId-spanId]` to the services' log lines; grep a trace id from Zipkin in
  `logs/*.log`.
- ***Tests*** — the services set `management.tracing.export.enabled: false` in `application-test.yml`.

<a id="no-parent-boot-35"></a>

## <span style="color:hsl(288,80%,58%)">Why this module has no parent and runs Boot 3.5</span>

| Fact                                                                                                                                                                                                      | Consequence                                                                                                                                                      |
|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `@EnableZipkinServer` from `io.zipkin.java:zipkin-server` was last released as 2.12.9 (2019, Boot 2.1)                                                                                                    | Not usable on Boot 4 / Java 27                                                                                                                                   |
| Current `io.zipkin:zipkin-server` 3.6.1 is built on ***Spring Boot 3.5.12*** + Armeria; it still has `zipkin2.server.internal.EnableZipkinServer` (internal package: Zipkin supports only its own server) | This module uses that annotation and copies `zipkin.server.ZipkinServer`'s bootstrap                                                                             |
| On Boot 4.1 it fails at startup: `ClassNotFoundException: org.springframework.boot.web.server.Compression` (moved by Boot 4's modularization)                                                             | No `<parent>`: the repo's chain (super-pom → Boot 4.1) and `learning-bom` (imports `spring-boot-dependencies` 4.1) would override Zipkin's Boot 3.5 dependencies |
| No Zipkin server BOM/starter exists (only client BOMs: `brave-bom`, `zipkin-reporter-bom`)                                                                                                                | [pom.xml](pom.xml) lists 3 artifacts: `zipkin-server`, `zipkin-lens` (UI), `zipkin-module-otel`; the rest is transitive                                          |

Compiled for ***Java 27*** (`maven.compiler.release`), runs on the same JDK as the other modules. Still listed
in the root `<modules>`, so `mvn verify` builds and tests it.

<a id="configuration"></a>

## <span style="color:hsl(66,80%,50%)">Configuration</span>

Zipkin disables Boot auto-configuration and reads `zipkin-server*.yml` (`spring.config.name=zipkin-server`):

| File                                                                        | From                                                        | Content                                                                                  |
|-----------------------------------------------------------------------------|-------------------------------------------------------------|------------------------------------------------------------------------------------------|
| [zipkin-server.yml](src/main/resources/zipkin-server.yml)                   | this module, ***shadows*** the one in the zipkin-server jar | `spring.profiles.include: shared, otel, learning` — later profiles override earlier ones |
| `zipkin-server-shared.yml`                                                  | zipkin-server jar                                           | every setting and its env var (`QUERY_PORT`, `STORAGE_TYPE`, `EUREKA_SERVICE_URL`, ...)  |
| `zipkin-server-otel.yml`                                                    | zipkin-module-otel jar                                      | registers the OTLP/HTTP collector                                                        |
| [zipkin-server-learning.yml](src/main/resources/zipkin-server-learning.yml) | this module                                                 | Eureka: `http://admin:admin@localhost:8761/eureka`, app name `zipkin-server`             |

<a id="run"></a>

## <span style="color:hsl(256,80%,58%)">Run</span>

```bash
mvn -pl zipkin-server spring-boot:run      # or: java -jar zipkin-server/target/zipkin-server-0.0.1-SNAPSHOT.jar
```

- UI: http://localhost:9411 (redirects to `/zipkin/`), health: http://localhost:9411/health
- Registers in Eureka as `ZIPKIN-SERVER` if `eureka-server` is up; otherwise logs a warning and keeps running.
- Start order does not matter: services started before Zipkin only drop the spans they could not export.

```bash
curl -u admin:admin localhost:8080/api/orders/101/with-user
```

Expected trace (***Run Query*** in the UI): `gateway-service` server + client span → `order-service`
`http get /api/orders/{id}/with-user` → `order-service` client span → `user-service` `http get /api/users/{id}`.

<a id="tests"></a>

## <span style="color:hsl(171,80%,58%)">Tests</span>

[ZipkinServerApplicationIT](src/test/java/com/learning/discovery/zipkin/ZipkinServerApplicationIT.java) boots
the server the way `main()` does (`QUERY_PORT=0`, Eureka off), posts an OTLP protobuf span to `/v1/traces`
and reads it back from `/api/v2/trace/{id}` in Zipkin format; also checks `/health` and the UI.

<a id="gotchas"></a>

## <span style="color:hsl(220,80%,62%)">Gotchas</span>

| Symptom                                                         | Cause                                                                                    | Fix                                                                                                     |
|-----------------------------------------------------------------|------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| `Failed to export spans ... Connection refused` in service logs | zipkin-server not running                                                                | Start it; the services keep working without it                                                          |
| Zipkin empty right after a request                              | Boot batches spans (5 s schedule delay)                                                  | Wait a few seconds, re-run the query                                                                    |
| Order and user spans in separate traces                         | A `RestClient`/`WebClient` built without an `ObservationRegistry` sends no `traceparent` | Use Boot's builder, or set `.observationRegistry(..)` like `order-service`                              |
| `Failed to register ... to Eureka`                              | eureka-server down                                                                       | Harmless; set `DISCOVERY_EUREKA_ENABLED=false` to stop trying                                           |
| Traces gone after restart                                       | in-memory storage                                                                        | Expected for the demo; `STORAGE_TYPE=elasticsearch`/`cassandra3`/`mysql` need the optional storage jars |

<a id="qa"></a>

## <span style="color:hsl(339,80%,58%)">❓ Q&A</span>

<a id="qa-sampling-memory-capacity"></a>

**Q1. 🔥 Sampling is 100 % and storage is in-memory. At 500 req/s through the gateway, what fails first — and does it
hurt the services?**

- ***History fails first, silently: in-memory storage keeps a fixed number of spans, so at that rate the UI only holds
  a few minutes of traces.*** The services are protected by a bounded, asynchronous export queue — they drop spans,
  not requests.
- ***Span rate*** — `/with-user` through the gateway is ~5 spans (gateway server + client, order server + client,
  user server); a plain `/api/users` is ~3. Call it ~4 on average → 500 × 4 = ***2,000 spans/s***.
- ***Zipkin side*** — `zipkin.storage.mem.max-spans` (`MEM_MAX_SPANS`, default 500,000 as of Zipkin 3.6) purges the
  oldest traces beyond that: 500,000 / 2,000 ≈ ***4 minutes*** of history. Heap use grows towards that cap (hundreds
  of MB); a trace an on-call engineer looks for 10 minutes later is already gone. Restart = everything gone.
- ***Service side*** — the OTel batch span processor buffers up to
  `management.opentelemetry.tracing.export.max-queue-size` (2,048 spans), exports in batches of 512 at least every 5 s
  (`schedule-delay`), and ***drops*** spans when the queue is full (Zipkin slow or down). The request path never waits
  on Zipkin. Costs that remain: CPU and allocation for creating every span at 100 % sampling, and a log line per failed
  export.
- ***Fix for real load***:

| Lever                    | Effect                                                                    |
|--------------------------|---------------------------------------------------------------------------|
| Sampling 0.01–0.1        | 10–100× fewer spans; decided once at the edge (see [Q2](#qa-head-sampling-who-decides)) |
| Real storage             | `STORAGE_TYPE=elasticsearch`/`cassandra3` with a TTL (e.g. 7 days) instead of a span cap |
| `STORAGE_THROTTLE_ENABLED` | bounds concurrent storage writes and queues/drops beyond it instead of overloading the store |
| Collector in between     | buffering, batching, tail sampling ([Q4](#qa-tail-sampling-pipeline))       |

- ***Monitor*** — Zipkin's own collector metrics (spans received/dropped), exporter failures in service logs, heap
  on the Zipkin JVM.

<a id="qa-head-sampling-who-decides"></a>

**Q2. 🔥 Every service sets `management.tracing.sampling.probability: 0.1` on its own. Do you get 10 % complete traces
or a mess of partial ones — and who actually decides?**

- ***Complete traces: the first service in the request — the gateway — decides, and everyone downstream follows.***
  Boot configures a ***parent-based*** sampler (parent-based wrapping a trace-ID-ratio sampler, as of Boot 4.1): a span
  with a parent copies the parent's decision, only a root span rolls the dice.
- ***How the decision travels*** — the last field of the W3C `traceparent` header carries the sampled flag
  (`...-01` sampled, `...-00` not). The gateway's client span sends it; `order-service` honours it and passes it to
  `user-service` through the observed `RestClient`.
- ***Where it still breaks***:
  - ***Callers that bypass the gateway*** (SBA, a batch job, a teammate's `curl` straight to `:8082`) start a new
    root — so per-service probabilities still matter for them.
  - ***Clients that send their own `traceparent`*** — the gateway treats it as a parent: an external client can force
    100 % sampling (`-01`) and inflate your tracing bill, or pick trace IDs that collide. At a trust boundary, strip
    or re-root inbound trace headers, or only honour them from known callers.
  - ***Dropped observations*** — `TracingConfig` drops `/actuator/**` server observations; anything those requests
    call downstream becomes its own root trace.
- ***Trade-off*** — head sampling is cheap and consistent, but it decides ***before*** the outcome is known: at 10 %
  you keep 10 % of the errors too. Keeping all errors needs tail sampling ([Q4](#qa-tail-sampling-pipeline)).

<a id="qa-context-propagation-boundaries"></a>

**Q3. 🔥 Why do some spans start their own trace instead of joining the request's — e.g. `OrderCacheRefresher` runs,
work handed to an executor, a Reactor chain?**

- ***The current span lives in a thread-local; any hop to another thread that doesn't carry it over starts a fresh
  root.*** Some of these roots are intended, some are bugs.
- ***Intended roots*** — `OrderCacheRefresher`'s `@Scheduled` method runs with no incoming request, so each run is
  its own trace (Spring observes scheduled methods when an `ObservationRegistry` is present). Quartz jobs
  (`HeartbeatJob`) aren't observed at all. Both are fine — just don't expect them under a user's request.
- ***Bugs*** — a controller that hands work to an `ExecutorService`, `CompletableFuture.supplyAsync(...)` or
  `@Async`: the worker thread has no current span, so its client calls (e.g. to `user-service`) show up as separate
  traces and lose the end-to-end latency picture.
- ***Fixes*** (Micrometer context-propagation library, as of 2026):

| Boundary                         | Fix                                                                                         |
|----------------------------------|---------------------------------------------------------------------------------------------|
| Spring `TaskExecutor` / `@Async` | `ContextPropagatingTaskDecorator` on the executor                                           |
| Plain `ExecutorService`          | wrap tasks with a `ContextSnapshot` captured on the calling thread                          |
| Reactor (gateway, `WebClient`)   | `spring.reactor.context-propagation=auto` — restores thread-locals from the Reactor context |
| Kafka/JMS/async messaging        | instrumented templates/listeners put `traceparent` in message headers                       |

- ***How to spot it*** — a trace that ends at a client call with nothing below it, while Zipkin shows the
  downstream span in another trace with the same timestamp. Grep the service log: the downstream line carries a
  different trace ID from the request line.
- ***Virtual threads*** don't change this — a new virtual thread still starts with no current span.

<a id="qa-tail-sampling-pipeline"></a>

**Q4. 🔥🔥 50 services, 5k req/s: keep every failed or slow (> 2 s) trace, but only ~1 % of the rest. Design the
pipeline.**

***Head sampling can't do it — the decision has to wait until the trace is finished, so put an OpenTelemetry Collector
tier with tail sampling between the services and the storage, and route all spans of a trace to the same collector.***

```mermaid
flowchart LR
    S["50 services<br/>sample 100 % locally"] -- OTLP --> L1["Collector tier 1<br/>load-balancing exporter<br/>(routing key: trace ID)"]
    L1 -- "all spans of trace X" --> T1["Collector tier 2 #1<br/>tail_sampling"]
    L1 -- "all spans of trace Y" --> T2["Collector tier 2 #2<br/>tail_sampling"]
    T1 --> ST["Trace storage<br/>(Zipkin + Elasticsearch/Cassandra, TTL)"]
    T2 --> ST
```

- ***Services*** — sample 100 % (parent-based), export OTLP to a local/nearby collector: only
  `OTLP_TRACES_ENDPOINT` changes in this repo. Spans are cheap to ship, expensive to store.
- ***Tier 1*** — the load-balancing exporter hashes the trace ID, so every span of a trace lands on the same tier-2
  instance; without it, each instance sees fragments and can't judge the whole trace.
- ***Tier 2*** — `tail_sampling` processor with policies combined as OR: `status_code` = ERROR, `latency` > 2 s,
  `probabilistic` 1 %. It buffers each trace for a `decision_wait` (e.g. 10–30 s) before deciding.
- ***Capacity*** — 5k req/s × ~5 spans × ~1 KB × 30 s wait ≈ ***750 MB*** of buffered spans across tier 2, plus
  headroom; size by `num_traces` and memory limits, scale horizontally (consistent hashing keeps traces together).
  Stored volume: errors + slow + 1 % ≈ a few % of 25k spans/s.
- ***Failure modes***:
  - A span arriving after `decision_wait` (long async work) misses its trace's decision → set the wait above the
    p99.9 trace duration, accept rare orphans.
  - Tier-2 instance dies → its buffered traces are lost; acceptable for telemetry, don't add durability you'll pay
    for every second.
  - Rebalancing tier 2 briefly splits traces.
  - Collector down → services' bounded queues drop spans; the app is unaffected (see [Q1](#qa-sampling-memory-capacity)).
- ***Metrics vs traces*** — error-rate and latency SLOs come from metrics (100 % accurate, cheap); traces are for
  explaining individual requests. Don't build alerting on sampled traces.
- ***Trade-offs*** — more moving parts and memory vs head sampling's zero cost; a vendor backend with built-in tail
  sampling is the buy option.

<a id="qa-boot35-internal-api-risk"></a>

**Q5. 🔥🔥 This module pins Spring Boot 3.5, escapes the repo's BOM and relies on Zipkin's internal
`@EnableZipkinServer`. As the architect, what are the risks and what is your plan?**

***Treat it as a vendored third-party server, not as one of our Spring apps: the safest production path is to run
Zipkin's own released artifact (jar or container) — or replace it — rather than maintain a custom bootstrap on an
internal API.***

- ***Risks***:

| Risk                     | Why here                                                                                                                         |
|--------------------------|----------------------------------------------------------------------------------------------------------------------------------|
| Internal API             | `zipkin2.server.internal.*` can change in any Zipkin release; the bootstrap copies `ZipkinServer`'s and must track it by hand        |
| Support window           | Boot 3.5 is the last 3.x line and its OSS support runs out in 2026 — check the Spring Boot support calendar; after that, fixes come only through Zipkin's own releases |
| Escapes governance       | no `<parent>`, own Boot 3.5.12 — the repo's BOM, dependency convergence and version rules don't apply; CVE fixes in Netty/Armeria/Jackson arrive only when Zipkin ships |
| Two Boot lines in one build | developers copy patterns between modules that don't apply to Boot 3.5 (property names, packages moved in Boot 4)            |

- ***Options***:
  1. ***Run Zipkin's released jar/container*** — same server, no custom code, upgrades are a version bump; Eureka
     registration and the OTLP module are configured by env vars and the module jar. Loses "one Maven build for
     everything", which is a convenience, not a requirement.
  2. ***Keep the module, contain it*** — pin and document why, SBOM + dependency scanning in CI (the repo already
     produces CycloneDX SBOMs for the other modules), a smoke IT (exists: OTLP post + `/api/v2/trace` read), and a
     scheduled job that tries the next Zipkin release.
  3. ***Replace the backend*** — services already speak OTLP, so swapping Zipkin for another OTLP-capable backend
     (Jaeger, Grafana Tempo, a vendor) is an endpoint change, ideally behind a collector ([Q4](#qa-tail-sampling-pipeline)).
- ***Decision*** — for a demo, option 2 is fine and teaches the bootstrap. For production, option 1 now, option 3
  when tail sampling or retention needs outgrow Zipkin; ***never*** fork the internal bootstrap further.
- ***Lesson*** — instrumenting with a vendor-neutral protocol (OTLP) is what makes the backend replaceable; the
  instrumentation in the four services needs no change in any option.
