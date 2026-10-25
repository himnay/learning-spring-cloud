# <span style="color:hsl(13,80%,58%)">learning-spring-cloud</span>

## <span style="color:hsl(151,80%,58%)">Table of contents</span>

1. 🧩 [Modules](#modules)
2. 🔎 [How the gateway finds services via Eureka](#how-the-gateway-finds-services-via-eureka)
3. 🏗️ [Parent / BOM chain](#parent--bom-chain)
4. 🚀 [Run order](#run-order)
5. ✅ [Verify](#verify)
6. 🧪 [Tests](#tests)
7. ❓ [FAQ](#faq)
    - 7.1 [What happens if a service's port changes after a restart?](#faq-port-change)
    - 7.2 [How does the gateway route calls when there are multiple instances of the same service (load balancing)?](#faq-load-balancing)
    - 7.3 [Does every gateway call go through Eureka, or does the gateway cache service locations?](#faq-gateway-cache)
    - 7.4 [Why does `gateway-service` need Spring Cloud LoadBalancer at all if it already round-robins between instances?](#faq-why-loadbalancer)
    - 7.5 [`order-service` now calls `user-service` directly (`GET /api/orders/{id}/with-user`) — does that bypass Eureka, since it skips the gateway?](#faq-order-calls-user)
    - 7.6 [Eureka is CAP-theorem AP (favors Availability + Partition tolerance over Consistency) — what does that actually mean here, and what's the failure mode?](#faq-eureka-ap)
    - 7.7 [What does self-preservation protect against, and is turning it off ever wrong?](#faq-self-preservation)
    - 7.8 [Two `user-service` instances register with the same `instanceId` (e.g. identical `hostname:port` — common in containers with the same internal hostname) — what happens?](#faq-duplicate-instance-id)
    - 7.9 [What's the worst-case time between an instance actually going down and the gateway noticing and routing around it?](#faq-worst-case-detection)
8. 🗺️ [Where this fits: Spring Cloud/Netflix OSS vs Kubernetes](#where-this-fits)
9. 🌐 [Virtual IPs (VIP)](#virtual-ips-vip)
10. 🔐 [Security and Spring Boot Admin features](#security-and-sba-features)
11. 📡 [Actuator endpoints](#actuator-endpoints)

Eureka service discovery demo. 6 Maven modules: 1 Eureka registry (run as 2–3
replicating nodes) + 3 REST microservices + 1 API gateway + 1 Spring Boot Admin
dashboard, all registering with the registry. Plus `zipkin-server`, a Zipkin tracing server module (Spring Boot 3.5, `@EnableZipkinServer`, OTLP
receiver).

<a id="modules"></a>

## <span style="color:hsl(288,80%,58%)">1. 🧩 Modules</span>

| Module (README)                                                                                | Port       | Role                                                                                                                                                | REST API                                                                                                                               | Swagger UI                                                                         |
|------------------------------------------------------------------------------------------------|------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------|
| [`eureka-server`](eureka-server/README.md)                                                     | 8761–8763  | Service registry / dashboard — 2-3 replicating nodes, one module, one profile each (`peer1`, `peer2`, `peer3`)                                      | —                                                                                                                                      | —                                                                                  |
| [`user-service`](user-service/README.md)                                                       | 8081       | Eureka client                                                                                                                                       | `GET /api/users`, `GET /api/users/{id}`                                                                                                | http://localhost:8081/swagger-ui.html                                              |
| [`order-service`](order-service/README.md)                                                     | 8082       | Eureka client                                                                                                                                       | `GET /api/orders`, `GET /api/orders/{id}`, `GET /api/orders/{id}/with-user` (calls `user-service` directly, load-balanced, no gateway) | http://localhost:8082/swagger-ui.html                                              |
| [`product-service`](product-service/README.md)                                                 | 8083       | Eureka client                                                                                                                                       | `GET /api/products`, `GET /api/products/{id}`                                                                                          | http://localhost:8083/swagger-ui.html                                              |
| [`gateway-service`](gateway-service/README.md)                                                 | 8080       | Eureka client + API gateway                                                                                                                         | routes `/api/users/**`, `/api/orders/**`, `/api/products/**` to the 3 services above via `lb://`                                       | http://localhost:8080/swagger-ui.html — aggregated, one dropdown entry per service |
| [`admin-server`](admin-server/README.md)                                                       | 8084       | Spring Boot Admin — discovers instances via Eureka, dashboard at http://localhost:8084 (login `admin`/`admin`)                                      | —                                                                                                                                      | —                                                                                  |
| [`zipkin-server`](zipkin-server/README.md)                                                     | 9411       | Zipkin server (`@EnableZipkinServer`, own Spring Boot 3.5 build, no parent), Eureka client, takes OTLP at `/v1/traces`, UI at http://localhost:9411 | —                                                                                                                                      | —                                                                                  |

Every module is secured with HTTP Basic, one shared user `admin`/`admin`
(override with `ACTUATOR_USER` / `ACTUATOR_PASSWORD`). Only
`/actuator/health` and `/actuator/info` are public — see
[§10](#security-and-sba-features). `order-service`, `user-service` and
`product-service` need Docker: each starts its own Postgres from the
`docker-compose.yaml` in its module directory (host ports 5433, 5434, 5436).
The Insomnia collection for the gateway routes is
[`gateway-service/insomnia_collection.json`](gateway-service/insomnia_collection.json).

The gateway and the 3 REST services export traces over OTLP (`spring-boot-starter-opentelemetry`) straight to
`zipkin-server`; start it to see one request
followed across gateway → order-service → user-service — see
[zipkin-server/README.md](zipkin-server/README.md). Each REST service publishes
its OpenAPI spec at `/v3/api-docs` (HTTP Basic declared, so Swagger UI's ***Authorize*** button works); the gateway's
Swagger UI shows all three.

<a id="how-the-gateway-finds-services-via-eureka"></a>

## <span style="color:hsl(66,80%,50%)">2. 🔎 How the gateway finds services via Eureka</span>

`gateway-service` never hardcodes a backend host or port. It is itself a
Eureka client (same `eureka.client.service-url.defaultZone` as the other 3
modules) and its routes point at a **service ID**, not a URL:

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: user-service
              uri: lb://USER-SERVICE      # not http://host:port
              predicates:
                - Path=/api/users/**
```

`lb://` is the load-balanced URI scheme. At request time Spring Cloud
LoadBalancer's [`ReactiveLoadBalancerClientFilter`][ReactiveLoadBalancerClientFilter] resolves `USER-SERVICE`
against the gateway's local Eureka registry cache (refreshed every 30s by
default), picks a live instance, and rewrites the URI to that instance's real
`host:port` before forwarding. Introspect the resolved routes at
`http://localhost:8080/actuator/gateway/routes`.

```mermaid
flowchart TB
    Client["Client<br/>(Insomnia / curl)"]
    Eureka["eureka-server<br/>:8761 · :8762 · :8763<br/>service registry, 2-3 nodes"]

    subgraph Gateway["gateway-service :8080"]
        Route["Route table<br/>Path=/api/users/** → lb://USER-SERVICE<br/>Path=/api/orders/** → lb://ORDER-SERVICE<br/>Path=/api/products/** → lb://PRODUCT-SERVICE"]
        LB["Spring Cloud LoadBalancer<br/>resolves service ID → live instance"]
    end

    UserSvc["user-service<br/>:8081"]
    OrderSvc["order-service<br/>:8082"]
    ProductSvc["product-service<br/>:8083"]
    AdminServer["admin-server<br/>:8084<br/>Spring Boot Admin dashboard"]
    UserSvc -- " register + heartbeat " --> Eureka
    OrderSvc -- " register + heartbeat " --> Eureka
    ProductSvc -- " register + heartbeat " --> Eureka
    Gateway -- " register + heartbeat " --> Eureka
    AdminServer -- " register + heartbeat " --> Eureka
    Eureka -. " registry pulled every 30s " .-> LB
    Eureka -. " registry pulled every 30s " .-> AdminServer
    Client -- " GET /api/users/1 " --> Route
    Route --> LB
    LB -- " USER-SERVICE → 192.168.x.x:8081 " --> UserSvc
    LB -.-> OrderSvc
    LB -.-> ProductSvc
    AdminServer -. " polls /actuator/health " .-> UserSvc
    AdminServer -. " polls /actuator/health " .-> OrderSvc
    AdminServer -. " polls /actuator/health " .-> ProductSvc
    AdminServer -. " polls /actuator/health " .-> Gateway
```

`admin-server` isn't part of the request path above — it's a separate observer.
It discovers the same instances via the same Eureka registry ([`DiscoveryClient`][DiscoveryClient],
no manual registration list), then polls each one's `/actuator/health` on its
own schedule to drive the dashboard. See the
[Spring Boot Admin](#spring-boot-admin) section below for what it adds over
the raw Eureka dashboard.

> ⚠️ So the dashboard has something to show, every service exposes `env`, `configprops`,
> `beans`, `threaddump` and `heapdump` over HTTP with no authentication. That is fine on a
> laptop and dangerous anywhere else: a heap dump contains every secret the process holds.
> Outside local development, serve actuator on a separate `management.server.port` that only
> the admin server can reach (or secure it), and drop `heapdump` from the exposure list.

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant Gateway as gateway-service :8080
    participant Eureka as eureka-server :8761
    participant User as user-service :8081
    participant Admin as admin-server :8084
    Note over User, Eureka: startup — happens once per service
    User ->> Eureka: register(USER-SERVICE, host:8081)
    loop every 30s
        User ->> Eureka: heartbeat (renew lease)
    end

    Note over Gateway, Eureka: startup — gateway is a client too
    Gateway ->> Eureka: register(GATEWAY-SERVICE, host:8080)
    Gateway ->> Eureka: fetch full registry
    loop every 30s
        Gateway ->> Eureka: fetch registry delta
    end

    Note over Admin, Eureka: startup — admin-server discovers instances, doesn't route traffic
    Admin ->> Eureka: register(ADMIN-SERVER, host:8084)
    Admin ->> Eureka: fetch full registry
    loop every ~10s
        Admin ->> User: GET /actuator/health
        User -->> Admin: 200 {status: UP, details: {...}}
    end

    Note over Client, User: per request — no hardcoded host:port
    Client ->> Gateway: GET /api/users/1
    Gateway ->> Gateway: Path predicate matches route "user-service"<br/>uri = lb://USER-SERVICE
    Gateway ->> Gateway: LoadBalancer resolves USER-SERVICE<br/>from cached registry → 192.168.x.x:8081
    Gateway ->> User: GET /api/users/1 (rewritten URI)
    User -->> Gateway: 200 {id, name, email}
    Gateway -->> Client: 200 {id, name, email}
```

Because resolution happens per-request against the cached registry, a
service can restart on a different host/port and the gateway keeps routing
correctly — no gateway config change needed, just the next registry refresh.

<a id="spring-boot-admin"></a>

### <span style="color:hsl(203,80%,58%)">Spring Boot Admin: discovery vs. the raw Eureka dashboard</span>

`eureka-server`'s own dashboard (`/`) shows what's registered and its lease
state — up/down, instance count, renewal timestamps. `admin-server` adds a
per-instance operational view on top of the same registry: live `/env`,
`/metrics`, `/loggers` (change a package's log level at runtime, no restart),
`/threaddump`, `/heapdump`, `/mappings`, `/beans`, `/configprops`, and a
"Journal" tab logging every status transition it has observed. It never
receives a manually-configured instance list — [`EurekaDiscoveryClient`][EurekaDiscoveryClient] feeds
it the same registry `gateway-service`'s `LoadBalancer` reads, so any
service that registers with Eureka shows up in Admin automatically, no
`admin-server` config change needed.

`eureka-server` registers with itself (`register-with-eureka: true`,
`fetch-registry: false`) purely so `admin-server` can discover it — the
registry is still a single node, it just lists itself.

### <span style="color:hsl(341,80%,58%)">Load balancing across multiple instances of the same service</span>

Run a second `user-service` instance on a different port (e.g.
`mvn -pl user-service spring-boot:run -Dspring-boot.run.arguments=--server.port=8091`)
and both register under the same Eureka app ID, `USER-SERVICE`, as two
separate `instanceId`s. `lb://USER-SERVICE` now resolves to a 2-instance
pool, and the default [`RoundRobinLoadBalancer`][RoundRobinLoadBalancer] alternates between them,
one call at a time, no stickiness:

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant Gateway as gateway-service :8080
    participant LB as RoundRobinLoadBalancer
    participant U1 as user-service#1<br/>:8081
    participant U2 as user-service#2<br/>:8091
    Note over U1, U2: both registered as USER-SERVICE<br/>(different instanceId, same app id)
    Client ->> Gateway: GET /api/users/1 (call 1)
    Gateway ->> LB: resolve USER-SERVICE
    LB ->> LB: pick next index → instance#1
    LB ->> U1: GET /api/users/1
    U1 -->> Client: 200
    Client ->> Gateway: GET /api/users/1 (call 2)
    Gateway ->> LB: resolve USER-SERVICE
    LB ->> LB: pick next index → instance#2
    LB ->> U2: GET /api/users/1
    U2 -->> Client: 200
    Client ->> Gateway: GET /api/users/1 (call 3)
    Gateway ->> LB: resolve USER-SERVICE
    LB ->> LB: pick next index → instance#1 (wrapped around)
    LB ->> U1: GET /api/users/1
    U1 -->> Client: 200
```

Verify it yourself: `curl -u admin:admin http://localhost:8761/eureka/apps/USER-SERVICE`
shows both instances under one `<application>`; then hit
`curl -u admin:admin http://localhost:8080/api/users` a few times in a row and watch the
port in each `user-service` log line alternate.

<a id="parent--bom-chain"></a>

## <span style="color:hsl(118,80%,58%)">3. 🏗️ Parent / BOM chain</span>

```
super-pom (com.org.llm:super-pom:1.2.1)   ← corporate parent (Boot 4.1.1, Java 27), imports learning-bom 3.1.0
  └── learning-spring-cloud (com.learning:learning-spring-cloud)  ← this repo's root pom, packaging=pom
        ├── eureka-server
        ├── admin-server
        ├── user-service
        ├── order-service
        ├── product-service
        └── gateway-service
```

- Root `pom.xml` declares `super-pom` as `<parent>`. `super-pom` imports
  `learning-bom` in its `dependencyManagement`.
- `learning-bom` already imports `spring-boot-dependencies`,
  `spring-cloud-dependencies` (Eureka artifacts) and the `springdoc` starters —
  every dependency this repo uses is already version-managed there.
- **No `<version>` is declared anywhere in this project** — every module pom
  and every `<dependency>` relies entirely on the inherited
  `dependencyManagement` chain (super-pom → learning-bom). Module artifact
  versions (`0.0.1-SNAPSHOT`) come from the parent `<parent><version>` +
  inheritance, never hardcoded per-module.
- No new dependency versions needed adding to `learning-bom` — Eureka (`spring-cloud-starter-netflix-eureka-server` /
  `-client`) and Swagger (`springdoc-openapi-starter-webmvc-ui`) were already present in its
  dependency management before this repo was created.

<a id="run-order"></a>

## <span style="color:hsl(256,80%,58%)">4. 🚀 Run order</span>

Eureka server must be up first so the other clients have somewhere to register.
The tracing stack is optional and can start at any time.

```bash
cd learning-spring-cloud
mvn -pl zipkin-server spring-boot:run &      # optional: tracing UI at http://localhost:9411
mvn -pl eureka-server spring-boot:run -Dspring-boot.run.profiles=peer1 &
# once it is up (it builds eureka-server/target, which the other nodes reuse):
mvn -pl eureka-server spring-boot:run -Dspring-boot.run.profiles=peer2 &
mvn -pl eureka-server spring-boot:run -Dspring-boot.run.profiles=peer3 &   # optional third node
# wait for them to come up, then:
mvn -pl user-service spring-boot:run &
mvn -pl order-service spring-boot:run &
mvn -pl product-service spring-boot:run &
mvn -pl gateway-service spring-boot:run &
mvn -pl admin-server spring-boot:run &
```

Load test the running stack with Grafana k6: `k6 run load-tests/gateway.js` (install + profiles
in [load-tests/README.md](load-tests/README.md)).

Or build everything first: `mvn clean install`, then run each module's jar.
`java -jar` skips `spring-boot-docker-compose` (the repackaged jar excludes it), so
for user/order/product-service start the module's `docker-compose.yaml` yourself
and pass `spring.datasource.*`, or use `spring-boot:run`.

<a id="verify"></a>

## <span style="color:hsl(33,80%,58%)">5. ✅ Verify</span>

- **Eureka dashboards**: http://localhost:8761, http://localhost:8762 and http://localhost:8763 (same registry,
  replicated; each node's *DS Replicas* lists the others) — list
  `USER-SERVICE`,
  `ORDER-SERVICE`, `PRODUCT-SERVICE`, `GATEWAY-SERVICE`, `ADMIN-SERVER` once all
  5 clients register (takes a few seconds after startup; default renewal interval).
- **Spring Boot Admin dashboard**: http://localhost:8084 — same instances as
  Eureka above (discovered via [`DiscoveryClient`][DiscoveryClient], not manually registered), but
  with drill-down per instance: health, `/env`, `/metrics`, `/loggers` (change
  log levels live), `/threaddump`, `/heapdump`, `/mappings`, `/beans`,
  `/configprops`, plus the views listed in [§10](#security-and-sba-features).
  `EUREKA-SERVER` is listed too — one instance per running node.
- **Health checks** (Spring Boot Actuator, `show-details: always`):
    - http://localhost:8761/actuator/health (other nodes: :8762, :8763)
    - http://localhost:8081/actuator/health
    - http://localhost:8082/actuator/health
    - http://localhost:8083/actuator/health
    - http://localhost:8080/actuator/health
    - http://localhost:8084/actuator/health
- **Through the gateway** (resolves target service via Eureka, no hardcoded port):
  ```bash
  curl -u admin:admin http://localhost:8080/api/users
  curl -u admin:admin http://localhost:8080/api/orders
  curl -u admin:admin http://localhost:8080/api/products
  ```
- **Swagger UI / OpenAPI** for each microservice (springdoc default paths):
    - UI: `http://localhost:<port>/swagger-ui.html`
    - Raw spec: `http://localhost:<port>/v3/api-docs`
- **Sample REST calls**:
  ```bash
  curl -u admin:admin http://localhost:8081/api/users
  curl -u admin:admin http://localhost:8082/api/orders
  curl -u admin:admin http://localhost:8083/api/products
  ```

<a id="tests"></a>

## <span style="color:hsl(171,80%,58%)">6. 🧪 Tests</span>

Each microservice module has:

- **Unit tests** (`*ControllerTest`) — [`@WebMvcTest`][WebMvcTest] + [`MockMvc`][MockMvc], no Spring
  context beyond the web layer, no network/Eureka involved.
- **Integration tests** (`*ApplicationIT`) — [`@SpringBootTest`][SpringBootTest] with a random
  port + [`RestTestClient`][RestTestClient] (`gateway-service`: [`WebTestClient`][WebTestClient]), hitting
  the real embedded server and the actuator health endpoint. `eureka.client.enabled=false` in
  `src/test/resources/application-test.yml` so tests don't need a live Eureka
  server.

`eureka-server` has its own `@SpringBootTest` that boots the registry and
asserts the dashboard and health endpoint respond.

Run all tests: `mvn clean verify` (failsafe plugin from `super-pom` runs the
`*IT` classes in the `integration-test` phase; surefire runs `*Test` in the
`test` phase).

<a id="faq"></a>

## <span style="color:hsl(308,80%,58%)">7. ❓ FAQ</span>

<a id="faq-port-change"></a>

**What happens if a service's port changes after a restart?**

A different `server.port` means a different Eureka `instanceId` (default
`host:appName:port`) — it's registered as a brand-new instance, not an
update of the old one. The old entry is only removed once its lease expires;
`eureka-server` has self-preservation on with a low local threshold (0.5), so eviction
happens about 30s after the last heartbeat unless many instances die at once. The gateway's own local
registry cache picks up the new instance on its next fetch cycle (every 30s
by default). In the gap between the old instance dying and the gateway's
cache refreshing, `lb://` may still resolve to the dead port and a call can
fail — no retry filter is configured on `gateway-service`, so that error
reaches the client as-is rather than being retried against another instance.
Once the cache refreshes, routing works again with **zero gateway config
change**, because routes point at a service ID (`lb://USER-SERVICE`), never
a baked-in host:port.

<a id="faq-load-balancing"></a>

**How does the gateway route calls when there are multiple instances of the
same service (load balancing)?**

Eureka groups instances by app name (`USER-SERVICE`) but keeps each one
under its own `instanceId`, so N running copies show up as N registry
entries under the same service ID. `lb://USER-SERVICE` resolves through a
[`ServiceInstanceListSupplier`][ServiceInstanceListSupplier], which returns every currently-`UP` instance
for that ID from the gateway's cached registry. The default
[`RoundRobinLoadBalancer`][RoundRobinLoadBalancer] cycles through that list one call at a time — no
sticky sessions, no weighting. Only instances Eureka reports as `UP` are
handed to the load balancer; the gateway does not itself health-check
instances before forwarding (`spring.cloud.loadbalancer.health-check.enabled`
would add that if needed). See the diagram in
[§2](#how-the-gateway-finds-services-via-eureka).

<a id="faq-gateway-cache"></a>

**Does every gateway call go through Eureka, or does the gateway cache
service locations?**

Cached. The gateway fetches the full registry from `eureka-server` once at
startup, then polls a delta every 30s, keeping the result in an in-memory
local cache ([`DiscoveryClient`][DiscoveryClient]). Every request resolves against that local
cache — there is no live call to `eureka-server` per request. Eureka is only
hit on gateway startup, on the periodic 30s poll, and for the gateway's own
heartbeats. One consequence: if `eureka-server` goes down mid-run, the
gateway keeps routing correctly off its last-known cache until instances
naturally age out — it doesn't fail calls just because the registry happens
to be unreachable at that instant.

<a id="faq-why-loadbalancer"></a>

**Why does `gateway-service` need Spring Cloud LoadBalancer at all if it
already round-robins between instances?**

It doesn't implement round-robin itself — "the gateway round-robins" is
observed behavior, not gateway code. `spring-cloud-starter-gateway-server-webflux`
pulls in `spring-cloud-starter-loadbalancer` transitively (neither module pom
declares it explicitly); the gateway's [`ReactiveLoadBalancerClientFilter`][ReactiveLoadBalancerClientFilter]
only recognizes the `lb://` scheme and delegates instance selection to a
[`ReactorServiceInstanceLoadBalancer`][ReactorServiceInstanceLoadBalancer] bean (default: `RoundRobinLoadBalancer`).
The split exists because the same load-balancer library is reused by plain
[`@LoadBalanced`][LoadBalanced] HTTP clients outside the gateway too — see the next question.

<a id="faq-order-calls-user"></a>

**`order-service` now calls `user-service` directly (`GET
/api/orders/{id}/with-user`) — does that bypass Eureka, since it skips the
gateway?**

No — it bypasses **the gateway**, not Eureka. `order-service`'s
`UserClient` (`order-service/src/main/java/.../client/UserClient.java`) uses
a [`RestClient.Builder`][RestClient] marked `@LoadBalanced`
(`config/LoadBalancerConfig.java`) and calls `http://USER-SERVICE/api/users/{id}`
— same Eureka-backed service ID resolution and `RoundRobinLoadBalancer` the
gateway uses, just invoked by `order-service`'s own `DiscoveryClient`
instead of the gateway's. This is exactly where a load-balanced client *needs* to be added per the earlier question: in
whichever module **makes**
the call, not the one that receives it — `user-service` needed no changes.
If `user-service` is unreachable, `UserClient.fetchUser` catches
[`RestClientException`][RestClientException] and the endpoint still returns `200` with `user: null`
rather than a `500`.

<a id="faq-eureka-ap"></a>

**Eureka is CAP-theorem AP (favors Availability + Partition tolerance over
Consistency) — what does that actually mean here, and what's the failure
mode?**

An AP registry hands out whatever it last knew, even if that data is stale
or the "true" state changed moments ago — it never blocks a read waiting to
confirm consistency. Concretely in this repo: the gateway's 30s-old cache
can point at an instance that already crashed; Eureka won't stop that read,
it just serves the cached entry. This trades a small, bounded chance of
routing to a dead instance for zero downtime on lookups — the opposite
choice (CP, e.g. a strongly-consistent registry) would instead block or
error the lookup during any disagreement, which is worse for a system where
"probably still up" beats "definitely correct but unavailable." The nuance:
this repo runs two or three `eureka-server` nodes (one module, profiles `peer1` :8761,
`peer2` :8762, `peer3` :8763). Replication between them is
asynchronous and best-effort — a registration hits one node and reaches the
others moments later, and during a partition each keeps serving its own,
possibly diverging, view. That's the AP choice in action, and the reason
clients list every node in `defaultZone`: one node down, they fail over.

<a id="faq-self-preservation"></a>

**What does self-preservation protect against, and is turning it off ever
wrong?**

Self-preservation is a circuit breaker for the registry itself: if the rate
of incoming heartbeats across *all* instances drops below ~85% of expected,
Eureka assumes it's suffering a network partition (clients are still alive,
just can't reach the registry) rather than a mass outage, and stops evicting
— because evicting during a real partition would wrongly kill every
instance's registration. `eureka-server` keeps it on (that also removes the
dashboard's `THE SELF PRESERVATION MODE IS TURNED OFF...` banner) but sets
`renewal-percent-threshold` to 0.5 locally, so killing one or two services in a
demo still gets them evicted instead of lingering as zombie entries. Turning it
off (`EUREKA_SELF_PRESERVATION=false`) evicts promptly no matter what. The tradeoff: on a real network
with actual partitions, disabling it means a transient blip can mass-evict
every healthy instance simultaneously, and every client (including the
gateway) suddenly sees an empty registry for that service. Off is tolerable
for single-host local dev; production Eureka deployments almost always leave it on.

<a id="faq-duplicate-instance-id"></a>

**Two `user-service` instances register with the same `instanceId` (e.g.
identical `hostname:port` — common in containers with the same internal
hostname) — what happens?**

Eureka's registry is keyed by `instanceId`, so the second registration **overwrites** the first in place rather than
adding a second entry. Even
with two processes actually running, Eureka — and therefore the gateway's
`RoundRobinLoadBalancer` — sees exactly one instance; all load lands on
whichever process registered most recently, and the other silently receives
zero traffic despite passing its own health checks. This repo avoids it via
`eureka.instance.prefer-ip-address: true` plus genuinely distinct
`server.port`s per module, so `instanceId`s never collide. In containers
where every replica shares a hostname, set
`eureka.instance.instance-id: ${spring.application.name}:${random.value}`
explicitly to guarantee uniqueness.

<a id="faq-worst-case-detection"></a>

**What's the worst-case time between an instance actually going down and
the gateway noticing and routing around it?**

Stack the intervals: up to ~30s for `eureka-server` to detect the missed
heartbeat and evict (self-preservation off, so no extra delay there) + up to
30s for the gateway's own `registryFetchIntervalSeconds` to pull that
change + the load balancer's own instance-list cache (Spring Cloud
LoadBalancer caches the resolved list separately, default TTL ~35s) before
it stops offering the dead instance. Worst case, a client can be routed to
an already-dead instance for **close to two minutes** after the failure,
purely from cache-staleness layered three deep — no bug involved, just three
independent caches each doing their job on their own clock. Tightening any
one of those intervals trades staleness for load on `eureka-server` and the
gateway.

<a id="where-this-fits"></a>

## <span style="color:hsl(86,80%,58%)">8. 🗺️ Where this fits: Spring Cloud/Netflix OSS vs Kubernetes</span>

This repo demonstrates the **Spring Cloud & Netflix OSS** column below —
`eureka-server` for service discovery and `gateway-service` for the API
gateway. Kubernetes solves the same concerns natively, which is why
Spring-Cloud-style discovery/gateway stacks are far less common in
Kubernetes-native shops.

![Microservices concerns: Spring Cloud/Netflix OSS vs Kubernetes](docs/images/microservices-concerns-comparison.png)

| Microservices Concern              | Spring Cloud & Netflix OSS              | Kubernetes                                         |
|------------------------------------|-----------------------------------------|----------------------------------------------------|
| Configuration Management           | Config Server, Consul, Netflix Archaius | Kubernetes ConfigMap & Secrets                     |
| Service Discovery                  | Netflix Eureka, Hashicorp Consul        | Kubernetes Service & Ingress Resources             |
| Load Balancing                     | Netflix Ribbon                          | Kubernetes Service                                 |
| API Gateway                        | Netflix Zuul                            | Kubernetes Service & Ingress Resources             |
| Service Security                   | Spring Cloud Security                   | —                                                  |
| Centralized Logging                | ELK Stack (LogStash)                    | EFK Stack (Fluentd)                                |
| Centralized Metrics                | Netflix Spectator & Atlas               | Heapster, Prometheus, Grafana                      |
| Distributed Tracing                | Spring Cloud Sleuth, Zipkin             | OpenTracing, Zipkin                                |
| Resilience & Fault Tolerance       | Netflix Hystrix, Turbine & Ribbon       | Kubernetes Health Check & resource isolation       |
| Auto Scaling & Self Healing        | —                                       | Kubernetes Health Check, Self Healing, Autoscaling |
| Packaging, Deployment & Scheduling | Spring Boot                             | Docker/Rkt, Kubernetes Scheduler & Deployment      |
| Job Management                     | Spring Batch                            | Kubernetes Jobs & Scheduled Jobs                   |
| Singleton Application              | Spring Cloud Cluster                    | Kubernetes Pods                                    |

<a id="virtual-ips-vip"></a>

## <span style="color:hsl(223,80%,58%)">9. 🌐 Virtual IPs (VIP)</span>

In Netflix Eureka, a Virtual IP (VIP) address is a logical identifier or
virtual hostname assigned to a service instance, defaulting to the
application name (`spring.application.name`).

**What is a Virtual IP (VIP) in Eureka?**

- **Logical name**: instead of pointing to a physical network IP address, a
  VIP acts as a logical name (like `USER-SERVICE`) that clients use to
  target a group of identical service instances.
- **Default value**: by default, Spring Cloud Netflix Eureka sets the
  virtual host (`vipAddress`) and secure virtual host (`secureVipAddress`)
  to the value of `${spring.application.name}`.
- **Load balancing**: clients use this virtual identifier to resolve where
  requests should go, allowing Eureka and client-side load balancers (like
  Ribbon or Spring Cloud LoadBalancer) to route traffic dynamically across
  healthy instances.

**How to configure VIP in Eureka**

Override the default virtual IP settings in application properties if the
logical identifier needs to differ from the application name:

```yaml
eureka:
  instance:
    virtual-host-name: custom-service-vip
    secure-virtual-host-name: secure-custom-service-vip
```

<!-- Library classes mentioned above, linked to their source at the versions this project builds with. -->

[DiscoveryClient]: https://github.com/spring-cloud/spring-cloud-commons/blob/v5.0.3/spring-cloud-commons/src/main/java/org/springframework/cloud/client/discovery/DiscoveryClient.java
[EurekaDiscoveryClient]: https://github.com/spring-cloud/spring-cloud-netflix/blob/v5.0.2/spring-cloud-netflix-eureka-client/src/main/java/org/springframework/cloud/netflix/eureka/EurekaDiscoveryClient.java
[LoadBalanced]: https://github.com/spring-cloud/spring-cloud-commons/blob/v5.0.3/spring-cloud-commons/src/main/java/org/springframework/cloud/client/loadbalancer/LoadBalanced.java
[MockMvc]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-test/src/main/java/org/springframework/test/web/servlet/MockMvc.java
[ReactiveLoadBalancerClientFilter]: https://github.com/spring-cloud/spring-cloud-gateway/blob/v5.0.3/spring-cloud-gateway-server-webflux/src/main/java/org/springframework/cloud/gateway/filter/ReactiveLoadBalancerClientFilter.java
[ReactorServiceInstanceLoadBalancer]: https://github.com/spring-cloud/spring-cloud-commons/blob/v5.0.3/spring-cloud-loadbalancer/src/main/java/org/springframework/cloud/loadbalancer/core/ReactorServiceInstanceLoadBalancer.java
[RestClient]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/web/client/RestClient.java
[RestClientException]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/web/client/RestClientException.java
[RestTestClient]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-test/src/main/java/org/springframework/test/web/servlet/client/RestTestClient.java
[RoundRobinLoadBalancer]: https://github.com/spring-cloud/spring-cloud-commons/blob/v5.0.3/spring-cloud-loadbalancer/src/main/java/org/springframework/cloud/loadbalancer/core/RoundRobinLoadBalancer.java
[ServiceInstanceListSupplier]: https://github.com/spring-cloud/spring-cloud-commons/blob/v5.0.3/spring-cloud-loadbalancer/src/main/java/org/springframework/cloud/loadbalancer/core/ServiceInstanceListSupplier.java
[SpringBootTest]: https://github.com/spring-projects/spring-boot/blob/v4.1.1/core/spring-boot-test/src/main/java/org/springframework/boot/test/context/SpringBootTest.java
[WebMvcTest]: https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-webmvc-test/src/main/java/org/springframework/boot/webmvc/test/autoconfigure/WebMvcTest.java
[WebTestClient]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-test/src/main/java/org/springframework/test/web/reactive/server/WebTestClient.java

<a id="security-and-sba-features"></a>

## <span style="color:hsl(0,80%,58%)">10. 🔐 Security and Spring Boot Admin features</span>

**Security (as of 2026, Spring Boot 4.1 / SBA 4.1):**

- ***One shared user per module*** — `spring.security.user` = `admin`/`admin` (`ACTUATOR_USER` / `ACTUATOR_PASSWORD`),
  HTTP Basic everywhere; `/actuator/health/**` and `/actuator/info` stay public for probes.
- ***Eureka needs credentials too*** — clients register via `http://admin:admin@localhost:8761/eureka/`; `eureka-server`
  disables CSRF for `/eureka/**`.
- ***SBA polls each actuator with metadata credentials*** — every instance publishes
  `eureka.instance.metadata-map.user.name/user.password`; `admin-server` falls back to
  `spring.boot.admin.instance-auth.default-*`.
- ***Gateway forwards `Authorization`*** — call the gateway with Basic auth and the same header reaches the downstream
  service; `order-service` adds Basic auth to its direct `user-service` call.
- ***`admin-server` and `gateway-service` are WebFlux*** — reactive `SecurityWebFilterChain`, a wrapped authentication
  manager publishes login events for Audit Events, and no Jolokia (servlet-only).

**SBA views and the endpoint behind each:**

| SBA view / action          | Endpoint              | Modules                             | Backed by                                                                             |
|----------------------------|-----------------------|-------------------------------------|---------------------------------------------------------------------------------------|
| Logfile                    | `logfile`             | all                                 | `logging.file.name: logs/<app>.log`                                                   |
| Heap Dump                  | `heapdump`            | all                                 | `management.endpoint.heapdump.access: unrestricted` (Boot 3.5+ defaults to `none`)    |
| Caches                     | `caches`              | order                               | Caffeine, `expireAfterWrite=10m`                                                      |
| Scheduled Tasks            | `scheduledtasks`      | order                               | `OrderCacheRefresher` every 5 min                                                     |
| Flyway                     | `flyway`              | order                               | `db/migration/V1..V3`                                                                 |
| HTTP Exchanges             | `httpexchanges`       | all                                 | `InMemoryHttpExchangeRepository`                                                      |
| Startup                    | `startup`             | all                                 | `BufferingApplicationStartup` in `main()` (absent in tests)                           |
| Audit Events               | `auditevents`         | all                                 | `InMemoryAuditEventRepository` + Spring Security login events                         |
| Sessions                   | `sessions`            | order                               | Spring Session JDBC (Postgres); log in via `/login` to create one                     |
| JMX                        | `jolokia`             | servlet modules (not gateway/admin) | `jolokia-support-springboot` 2.6.3                                                    |
| SBOM                       | `sbom`                | all                                 | root `cyclonedx-maven-plugin` → `META-INF/sbom/application.cdx.json`                  |
| Quartz                     | `quartz`              | all                                 | demo `HeartbeatJob`, every minute, in-memory store                                    |
| Health (custom + probes)   | `health`              | all (custom indicators: order)      | `probes.enabled` → liveness/readiness groups; order adds `ordersCache`, `userService` |
| Info                       | `info`                | all                                 | `info.app.*` + `management.info.java/os/process/env.enabled`                          |
| Tags                       | — (Eureka metadata)   | all                                 | `metadata-map.tags.environment` (`APP_ENV`) / `tags.tier` → badges                    |
| Custom metrics             | `metrics`             | order                               | `orders.cache.refresh` timer, `orders.db.reads{op}` counter                           |
| Shutdown / Restart         | `shutdown`, `restart` | all                                 | `access: unrestricted`; restart from Spring Cloud Context                             |
| Environment edit + Refresh | `env` POST, `refresh` | all                                 | `management.endpoint.env.post.enabled: true`                                          |

- ***Shutdown, restart, env POST and Jolokia mutate the running app*** — fine here behind Basic auth; never expose them
  on a public port.

<a id="actuator-endpoints"></a>

## <span style="color:hsl(170,80%,58%)">11. 📡 Actuator endpoints</span>

Every endpoint Spring Boot Actuator (Boot 4.x, as of 2026) and Spring Cloud can expose, and which module exposes it over
HTTP here (`management.endpoints.web.exposure.include` in each `application.yml`). ✅ = exposed, — = not exposed. All
of them sit behind HTTP Basic except `health` and `info`; see [§10](#security-and-sba-features) for the SBA view each
one feeds.

- ***Boot's own default*** — over HTTP only `health` is exposed; every endpoint except `shutdown` is accessible once
  exposed (`shutdown` needs `management.endpoint.shutdown.access: unrestricted`).
- ***`eureka`*** — `eureka-server`: one list for every node (`peer1`, `peer2`, `peer3`).
- ***`zipkin-server`*** is not in the table: it runs Zipkin's own server (it registers in Eureka as `zipkin-server`
  through `zipkin.discovery.eureka`), not Spring Boot Actuator.

**Spring Boot Actuator:**

| Endpoint           | What it shows / does                                                                   | Needs                                      | eureka    | admin | gateway | user | product | order |
|--------------------|----------------------------------------------------------------------------------------|--------------------------------------------|-----------|-------|---------|------|---------|-------|
| `health`           | App health with details and groups (`liveness`, `readiness`)                           | —                                          | ✅         | ✅     | ✅       | ✅    | ✅       | ✅     |
| `info`             | App info: `info.*`, build, git, java, os, process                                      | contributors via `management.info.*`       | ✅         | ✅     | ✅       | ✅    | ✅       | ✅     |
| `metrics`          | Micrometer metrics; `/metrics/{name}` for one                                          | —                                          | ✅         | ✅     | ✅       | ✅    | ✅       | ✅     |
| `prometheus`       | Metrics in Prometheus scrape format                                                    | `micrometer-registry-prometheus`           | —         | —     | —       | —    | —       | —     |
| `env`              | Property sources and resolved values (masked by default); POST edits with Spring Cloud | —                                          | ✅         | ✅     | ✅       | ✅    | ✅       | ✅     |
| `configprops`      | `@ConfigurationProperties` beans and values (masked by default)                        | —                                          | ✅         | ✅     | ✅       | ✅    | ✅       | ✅     |
| `beans`            | All beans in the context                                                               | —                                          | ✅         | ✅     | ✅       | ✅    | ✅       | ✅     |
| `conditions`       | Auto-configuration report: what matched or not, and why                                | —                                          | —         | —     | —       | —    | —       | —     |
| `mappings`         | All request mappings / router functions                                                | —                                          | ✅         | ✅     | ✅       | ✅    | ✅       | ✅     |
| `loggers`          | View and change log levels at runtime                                                  | —                                          | ✅         | ✅     | ✅       | ✅    | ✅       | ✅     |
| `logfile`          | Log file contents (byte ranges)                                                        | `logging.file.name` / `path`               | ✅         | ✅     | ✅       | ✅    | ✅       | ✅     |
| `threaddump`       | Thread dump                                                                            | —                                          | ✅         | ✅     | ✅       | ✅    | ✅       | ✅     |
| `heapdump`         | Heap dump download (`.hprof`); contains secrets                                        | HotSpot JVM                                | ✅         | ✅     | ✅       | ✅    | ✅       | ✅     |
| `scheduledtasks`   | `@Scheduled` tasks                                                                     | —                                          | —         | —     | —       | —    | —       | ✅     |
| `caches`           | Cache managers and caches; evict                                                       | a `CacheManager`                           | —         | —     | —       | —    | —       | ✅     |
| `httpexchanges`    | Recent HTTP requests and responses                                                     | `HttpExchangeRepository` bean              | ✅         | ✅     | ✅       | ✅    | ✅       | ✅     |
| `auditevents`      | Security audit events (logins, failures)                                               | `AuditEventRepository` bean                | ✅         | ✅     | ✅       | ✅    | ✅       | ✅     |
| `sessions`         | Spring Session sessions; list and delete                                               | Spring Session                             | —         | —     | —       | —    | —       | ✅     |
| `startup`          | Startup steps and timings                                                              | `BufferingApplicationStartup`              | ✅         | ✅     | ✅       | ✅    | ✅       | ✅     |
| `sbom`             | Software bill of materials                                                             | CycloneDX file in `META-INF/sbom`          | ✅         | ✅     | ✅       | ✅    | ✅       | ✅     |
| `flyway`           | Applied Flyway migrations                                                              | Flyway                                     | —         | —     | —       | —    | —       | ✅     |
| `liquibase`        | Applied Liquibase changesets                                                           | Liquibase                                  | —         | —     | —       | —    | —       | —     |
| `quartz`           | Quartz jobs and triggers                                                               | Quartz                                     | ✅         | ✅     | ✅       | ✅    | ✅       | ✅     |
| `integrationgraph` | Spring Integration component graph                                                     | Spring Integration                         | —         | —     | —       | —    | —       | —     |
| `shutdown`         | Graceful shutdown (POST)                                                               | off until `access: unrestricted`           | ✅         | ✅     | ✅       | ✅    | ✅       | ✅     |
| `jolokia`          | JMX over HTTP                                                                          | `jolokia-support-springboot`, servlet only | ✅         | —     | —       | ✅    | ✅       | ✅     |

**Spring Cloud** (present when the dependency is on the classpath):

| Endpoint          | What it shows / does                                        | Needs                       | eureka    | admin | gateway | user | product | order |
|-------------------|-------------------------------------------------------------|-----------------------------|-----------|-------|---------|------|---------|-------|
| `refresh`         | Re-bind `@RefreshScope` / `@ConfigurationProperties` (POST) | Spring Cloud Context        | ✅         | ✅     | ✅       | ✅    | ✅       | ✅     |
| `restart`         | Restart the context (POST)                                  | Spring Cloud Context        | ✅         | ✅     | ✅       | ✅    | ✅       | ✅     |
| `pause`           | Stop the context lifecycle (POST)                           | Spring Cloud Context        | —         | —     | —       | —    | —       | —     |
| `resume`          | Start it again (POST)                                       | Spring Cloud Context        | —         | —     | —       | —    | —       | —     |
| `features`        | Spring Cloud features in use                                | Spring Cloud Commons        | —         | —     | —       | —    | —       | —     |
| `serviceregistry` | Get/set this instance's registration status                 | a service registry (Eureka) | —         | —     | —       | —    | —       | —     |
| `gateway`         | Routes, predicates, filters; refresh, add, delete routes    | Spring Cloud Gateway        | —         | —     | ✅       | —    | —       | —     |
| `busrefresh`      | Broadcast refresh to all instances                          | Spring Cloud Bus            | —         | —     | —       | —    | —       | —     |
| `busenv`          | Broadcast an env change to all instances                    | Spring Cloud Bus            | —         | —     | —       | —    | —       | —     |

- ***Not exposed anywhere here*** — `prometheus` (metrics are read on demand through `metrics` / SBA; OTLP exports traces only),
  `conditions`, `liquibase`, `integrationgraph`, `pause`/`resume`, `features`, `serviceregistry` and the Bus endpoints;
  add the name to `exposure.include` to turn one on.
- ***Module-specific endpoints*** — `caches`, `scheduledtasks`, `sessions`: only `order-service` has a cache, a
  scheduler and Spring Session. `flyway`: user, product and order all migrate with Flyway, but only `order-service`
  exposes the endpoint. `gateway`: only `gateway-service` runs Spring Cloud Gateway (SBA → Web →
  Gateway). `jolokia`: servlet modules only (WebFlux `admin-server` and `gateway-service` can't run it).
