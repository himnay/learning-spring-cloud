# <span style="color:hsl(13,80%,58%)">learning-spring-cloud</span>

## <span style="color:hsl(151,80%,58%)">Table of contents</span>

1. 🧩 [Modules](#modules)
2. 🏗️ [Parent / BOM chain](#parent--bom-chain)
3. 🚀 [Run order](#run-order)
4. ✅ [Verify](#verify)
5. 🧪 [Tests](#tests)
6. 🔎 [How the gateway finds services via Eureka](#how-the-gateway-finds-services-via-eureka)
7. 🌐 [Virtual IPs (VIP)](#virtual-ips-vip)
8. 🔐 [Security and Spring Boot Admin features](#security-and-sba-features)
9. 🔒 [HTTPS between services (SSL bundles)](#https)
10. 📡 [Actuator endpoints](#actuator-endpoints)
11. 🗺️ [Where this fits: Spring Cloud/Netflix OSS vs Kubernetes](#where-this-fits)
12. ❓ [FAQ](#faq)
    - 12.1 [What happens if a service's port changes after a restart?](#faq-port-change)
    - 12.2 [How does the gateway route calls when there are multiple instances of the same service (load balancing)?](#faq-load-balancing)
    - 12.3 [Does every gateway call go through Eureka, or does the gateway cache service locations?](#faq-gateway-cache)
    - 12.4 [Why does `gateway-service` need Spring Cloud LoadBalancer at all if it already round-robins between instances?](#faq-why-loadbalancer)
    - 12.5 [`order-service` now calls `user-service` directly (`GET /api/orders/{id}/with-user`) — does that bypass Eureka, since it skips the gateway?](#faq-order-calls-user)
    - 12.6 [Eureka is CAP-theorem AP (favors Availability + Partition tolerance over Consistency) — what does that actually mean here, and what's the failure mode?](#faq-eureka-ap)
    - 12.7 [What does self-preservation protect against, and is turning it off ever wrong?](#faq-self-preservation)
    - 12.8 [Two `user-service` instances register with the same `instanceId` (e.g. identical `hostname:port` — common in containers with the same internal hostname) — what happens?](#faq-duplicate-instance-id)
    - 12.9 [What's the worst-case time between an instance actually going down and the gateway noticing and routing around it?](#faq-worst-case-detection)
    - 12.10 🔥 [One shared `admin` user, the caller's `Authorization` header forwarded as-is, the password in Eureka metadata — what is the blast radius of one leaked credential, and how would you redesign it?](#faq-shared-credentials-blast-radius)
    - 12.11 🔥 [`user-service` degrades to a 25 s p99 under load — what happens to `order-service` and the gateway, and why doesn't the `user: null` fallback save you?](#faq-slow-downstream-cascade)
    - 12.12 🔥 [Delete `order-service`'s `@Primary` plain `RestClient.Builder` and keep only the `@LoadBalanced` one — why does Eureka registration break, and what else silently changes?](#faq-primary-restclient-builder)
    - 12.13 🔥🔥 [Design a zero-error rolling redeploy of `user-service` (3 instances) while traffic flows through the gateway and through `order-service`'s direct `UserClient` — what fails today, and what sequence fixes it?](#faq-zero-error-rolling-deploy)
    - 12.14 🔥🔥 [Move this stack onto Kubernetes without a big-bang cutover — keep Eureka and the gateway or replace them, and how do you migrate service by service with rollback?](#faq-migrate-to-kubernetes)

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
| [`order-service`](order-service/README.md)                                                     | 8082       | Eureka client                                                                                                                                       | `GET /api/orders`, `GET /api/orders/{id}`, `GET /api/orders/{id}/with-user` (calls `user-service` directly, load-balanced, no gateway) | https://localhost:8082/swagger-ui.html                                             |
| [`product-service`](product-service/README.md)                                                 | 8083       | Eureka client                                                                                                                                       | `GET /api/products`, `GET /api/products/{id}`                                                                                          | https://localhost:8083/swagger-ui.html                                             |
| [`gateway-service`](gateway-service/README.md)                                                 | 8080       | Eureka client + API gateway                                                                                                                         | routes `/api/users/**`, `/api/orders/**`, `/api/products/**` to the 3 services above via `lb://`                                       | http://localhost:8080/swagger-ui.html — aggregated, one dropdown entry per service |
| [`admin-server`](admin-server/README.md)                                                       | 8084 (https) | Spring Boot Admin — discovers instances via Eureka, dashboard at https://localhost:8084 (login `admin`/`admin`)                                      | —                                                                                                                                      | —                                                                                  |
| [`zipkin-server`](zipkin-server/README.md)                                                     | 9411       | Zipkin server (`@EnableZipkinServer`, own Spring Boot 3.5 build, no parent), Eureka client, takes OTLP at `/v1/traces`, UI at http://localhost:9411 | —                                                                                                                                      | —                                                                                  |

Every module is secured with HTTP Basic, one shared user `admin`/`admin`
(override with `ACTUATOR_USER` / `ACTUATOR_PASSWORD`). Only
`/actuator/health` and `/actuator/info` are public — see
[§8](#security-and-sba-features). `order-service`, `user-service` and
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

<a id="parent--bom-chain"></a>

## <span style="color:hsl(118,80%,58%)">2. 🏗️ Parent / BOM chain</span>

```
super-pom (com.org.llm:super-pom:1.2.2)   ← corporate parent (Boot 4.1.2, Java 27), imports learning-bom 3.1.1
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

## <span style="color:hsl(256,80%,58%)">3. 🚀 Run order</span>

Eureka server must be up first so the other clients have somewhere to register.
The tracing stack is optional and can start at any time.

```bash
cd learning-spring-cloud
./certs/generate-certs.sh                    # once: dev CA + TLS stores (§9)
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

## <span style="color:hsl(33,80%,58%)">4. ✅ Verify</span>

- **TLS first**: run [`./certs/generate-certs.sh`](certs/generate-certs.sh) once before building (registry,
  order and product serve HTTPS only — [§9](#https)); `curl` needs `--cacert certs/out/ca.crt`, browsers need the CA
  imported or a click-through.
- **Eureka dashboards**: https://localhost:8761, https://localhost:8762 and https://localhost:8763 (same registry,
  replicated; each node's *DS Replicas* lists the others) — list
  `USER-SERVICE`,
  `ORDER-SERVICE`, `PRODUCT-SERVICE`, `GATEWAY-SERVICE`, `ADMIN-SERVER` once all
  5 clients register (takes a few seconds after startup; heartbeats every 10 s).
- **Spring Boot Admin dashboard**: https://localhost:8084 — same instances as
  Eureka above (discovered via [`DiscoveryClient`][DiscoveryClient], not manually registered), but
  with drill-down per instance: health, `/env`, `/metrics`, `/loggers` (change
  log levels live), `/threaddump`, `/heapdump`, `/mappings`, `/beans`,
  `/configprops`, plus the views listed in [§8](#security-and-sba-features).
  `EUREKA-SERVER` is listed too — one instance per running node.
- **Health checks** (Spring Boot Actuator, `show-details: always`):
    - https://localhost:8761/actuator/health (other nodes: :8762, :8763)
    - http://localhost:8081/actuator/health
    - https://localhost:8082/actuator/health
    - https://localhost:8083/actuator/health
    - http://localhost:8080/actuator/health
    - https://localhost:8084/actuator/health
- **Through the gateway** (resolves target service via Eureka, no hardcoded port):
  ```bash
  curl -u admin:admin http://localhost:8080/api/users
  curl -u admin:admin http://localhost:8080/api/orders
  curl -u admin:admin http://localhost:8080/api/products
  ```
- **Swagger UI / OpenAPI** for each microservice (springdoc default paths):
    - UI: `http://localhost:8081/swagger-ui.html`, `https://localhost:8082/…`, `https://localhost:8083/…`
    - Raw spec: `<same base>/v3/api-docs`
- **Sample REST calls**:
  ```bash
  curl -u admin:admin http://localhost:8081/api/users
  curl --cacert certs/out/ca.crt -u admin:admin https://localhost:8082/api/orders
  curl --cacert certs/out/ca.crt -u admin:admin https://localhost:8083/api/products
  ```

<a id="tests"></a>

## <span style="color:hsl(171,80%,58%)">5. 🧪 Tests</span>

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

<a id="how-the-gateway-finds-services-via-eureka"></a>

## <span style="color:hsl(66,80%,50%)">6. 🔎 How the gateway finds services via Eureka</span>

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
> `beans`, `threaddump` and `heapdump` over HTTP, guarded only by the one shared Basic login
> (`admin`/`admin`, [§8](#security-and-sba-features)). That is fine on a laptop and dangerous anywhere else: a heap dump contains every secret the process holds.
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
    loop every 10s
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
`fetch-registry: true`) so `admin-server` can discover it — every running node
(`peer1`–`peer3`) lists itself under the one `EUREKA-SERVER` app.

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

Verify it yourself: `curl --cacert certs/out/ca.crt -u admin:admin https://localhost:8761/eureka/apps/USER-SERVICE`
shows both instances under one `<application>`; then hit
`curl -u admin:admin http://localhost:8080/api/users` a few times in a row and watch the
port in each `user-service` log line alternate.

<a id="virtual-ips-vip"></a>

## <span style="color:hsl(223,80%,58%)">7. 🌐 Virtual IPs (VIP)</span>

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

## <span style="color:hsl(0,80%,58%)">8. 🔐 Security and Spring Boot Admin features</span>

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

<a id="https"></a>

## <span style="color:hsl(45,80%,55%)">9. 🔒 HTTPS between services (SSL bundles)</span>

The registry, `order-service`, `product-service` and the Spring Boot Admin UI (`admin-server`) serve ***HTTPS only***. Every call that reaches them — Eureka
registration, heartbeats and registry fetches from every module, peer replication between registry nodes, Spring Boot
Admin polling, gateway routing — runs over TLS and verifies the server certificate against a private dev CA. All key
and trust material is wired through Spring Boot ***SSL bundles*** (`spring.ssl.bundle.*`); where a library can't read
a bundle, a small bean hands it the bundle's `SSLContext`.

| Caller → callee                                  | Scheme    | Who trusts the CA, and how                                                                                              |
|--------------------------------------------------|-----------|-------------------------------------------------------------------------------------------------------------------------|
| every Eureka client → `eureka-server` (8761–8763) | **https** | bundle `internal-ca` → `RestClientDiscoveryClientOptionalArgs` ([`EurekaClientTlsConfig`](order-service/src/main/java/com/learning/discovery/order/config/EurekaClientTlsConfig.java), one per module) |
| `eureka-server` → its peers (own client + replication) | **https** | bundle `internal-ca` → `Jersey3DiscoveryClientOptionalArgs` + custom `PeerEurekaNodes` ([`EurekaTlsConfig`](eureka-server/src/main/java/com/learning/discovery/eureka/config/EurekaTlsConfig.java)) |
| `admin-server` (SBA) → order, product, eureka, itself | **https** | bundle `internal-ca` → `spring.http.clients.ssl.bundle` (Boot's `WebClient.Builder`, which SBA builds its `InstanceWebClient` from) |
| `gateway-service` → order, product (`lb://`)      | **https** | bundle `internal-ca` → `spring.cloud.gateway.server.webflux.httpclient.ssl.ssl-bundle`                                         |
| `zipkin-server` → `eureka-server`                 | **https** | JVM default trust store set from `tls/truststore.p12` at startup ([`DevCaTrust`](zipkin-server/src/main/java/com/learning/discovery/zipkin/DevCaTrust.java)) |
| browser → SBA UI `:8084`                          | **https** | `server.ssl.bundle: ${app.tls.server-bundle}` (`service-identity`); import `certs/out/ca.crt` or click through   |
| SBA / gateway → user-service, gateway             | http      | unchanged: those two still serve plain HTTP                                                                             |
| `order-service` → `user-service` (`UserClient`)   | http      | unchanged                                                                                                                |
| browser / curl → gateway `:8080`                  | http      | unchanged (edge TLS is a separate step, see the end of this section)                                                     |

```mermaid
flowchart LR
    subgraph TLS["serve HTTPS (bundle: service-identity)"]
        E1["eureka peer1 :8761"]
        E2["eureka peer2 :8762"]
        O["order-service :8082"]
        P["product-service :8083"]
        A["admin-server :8084 (SBA UI)"]
    end
    U["user-service :8081"] -- "register / renew / fetch (https)" --> E1
    G["gateway :8080"] -- "register / fetch (https)" --> E2
    G -- "lb://ORDER-SERVICE → https://127.0.0.1:8082" --> O
    G -- "lb://PRODUCT-SERVICE → https" --> P
    A -- "poll /actuator (https)" --> O
    A -- "poll /actuator (https)" --> P
    A -- "poll /actuator (https)" --> E1
    O -- "register / renew (https)" --> E1
    P -- "register / renew (https)" --> E2
    E1 <-- "replication (https)" --> E2
    Z["zipkin-server :9411"] -- "register (https)" --> E1
    O -- "UserClient (http)" --> U
```

### What it takes to convert HTTP to HTTPS — step by step

Each step names what changed in this repo; in another project the same ten steps apply.

1. ***Create a CA and a server certificate*** — [`certs/generate-certs.sh`](certs/generate-certs.sh) (run once, rerun
   to rotate):

   ```bash
   ./certs/generate-certs.sh          # EXTRA_SAN="DNS:my.host,IP:10.0.0.5" to add names
   ```

   It writes `server.p12` (certificate + key, alias `server`) into the modules that *serve* TLS and `truststore.p12`
   (the CA only) into every module that *calls* an `https://` URL, under `src/main/resources/tls/`; the CA key stays
   in `certs/out/` (git-ignored). The certificate's ***SubjectAltName must list every host a caller puts in a URL***:
   `localhost`, `127.0.0.1`, `127.0.0.2` (the three registry nodes; step 4 pins order/product to `127.0.0.1`).
   Hostname verification is on everywhere; a missing name fails with
   `No subject alternative names matching IP address 192.168.0.12 found`.
2. ***Declare SSL bundles with self-describing names*** in each `application.yml` — `service-identity` (key store:
   this process's certificate + key) where the module serves TLS, `internal-ca` (trust store: the CAs it trusts as a
   client) where it calls TLS. A bundle is looked up ***by name***, so give it a name that says what it is, not who
   uses it — any number of consumers (Tomcat, `WebClient`, the gateway's Netty client, your own beans) can then share
   the same one:

   ```yaml
   spring:
     ssl:
       bundle:
         jks:
           service-identity:
             key:
               alias: server
             keystore:
               location: classpath:tls/server.p12
               password: ${TLS_KEYSTORE_PASSWORD:changeit}
               type: PKCS12
           internal-ca:
             truststore:
               location: classpath:tls/truststore.p12
               password: ${TLS_TRUSTSTORE_PASSWORD:changeit}
               type: PKCS12
   ```
   ***Declare each name once and reference it everywhere*** — YAML keys can't contain placeholders, so the name
   appears once as the bundle key above and once under `app.tls`; every consumer — YAML or Java — reads `app.tls.*`,
   so renaming a bundle (or pointing a module at a different one, e.g. a production `prod-ca`) is a two-line change:

   ```yaml
   app:
     tls:
       server-bundle: service-identity   # modules that serve TLS
       trust-bundle: internal-ca          # every module that calls TLS
   ```

3. ***Serve HTTPS*** (eureka-server, order-service, product-service, admin-server) — one line; the port stays the
   same and stops speaking plain HTTP (Tomcat answers `http://` with `400 Bad Request`; Netty in admin-server just
   closes the connection):

   ```yaml
   server:
     ssl:
       bundle: ${app.tls.server-bundle}
   ```
4. ***Advertise HTTPS in the registry*** — callers never see `server.ssl`; they build URLs from the `InstanceInfo`
   in Eureka. order/product/admin (and the registry nodes, so SBA polls them over TLS) register:

   ```yaml
   eureka:
     instance:
       secure-port-enabled: true        # securePort = server.port; isSecure() → https:// URIs
       non-secure-port-enabled: false
       ip-address: ${EUREKA_INSTANCE_IP:127.0.0.1}   # order/product/admin: a host that is in the SAN
   ```

   With `secure-port-enabled`, Spring Cloud also publishes `secureHealthCheckUrl` (`https://…/actuator/health`), which
   SBA's `EurekaServiceInstanceConverter` prefers, and `EurekaServiceInstance.getUri()` returns `https://host:securePort`,
   which the gateway's `lb://` resolution and SBA's management URL use. Without `ip-address` the instance registers
   its LAN IP (`prefer-ip-address: true`), which isn't in the certificate.
5. ***Point every Eureka client at `https://`*** — `defaultZone: https://user:pass@${eureka.server.instances[n]}/eureka/`
   in all six Spring modules, and `zipkin.discovery.eureka.service-url` in zipkin-server.
6. ***Make the Eureka client trust the CA*** — it does **not** read `spring.ssl.bundle.*`, only its own
   `eureka.client.tls.*` key/trust-store files, and its RestClient transport replaces the request factory of the
   `RestClient.Builder` it gets, so `spring.http.clients.ssl.bundle` never reaches it. The hook its own TLS support
   uses is the `SSLContext` on the optional-args bean, so each client module declares:

   ```java
   @Bean
   RestClientDiscoveryClientOptionalArgs restClientDiscoveryClientOptionalArgs(SslBundles sslBundles,
           @Value("${app.tls.trust-bundle}") String trustBundle,
           EurekaClientHttpRequestFactorySupplier requestFactorySupplier,
           ObjectProvider<RestClient.Builder> restClientBuilder) {
       var args = new RestClientDiscoveryClientOptionalArgs(requestFactorySupplier,
               () -> restClientBuilder.getIfAvailable(RestClient::builder));
       args.setSSLContext(sslBundles.getBundle(trustBundle).createSslContext());
       return args;                  // the auto-configured one backs off (@ConditionalOnMissingBean)
   }
   ```

   `DiscoveryClient` passes that `SSLContext` to `RestClientTransportClientFactories`, which builds an Apache
   HttpClient 5 connection manager with it (default hostname verifier).
7. ***Registry nodes: own client and peer replication*** — on the server classpath the node's own Eureka client uses
   Jersey, so `EurekaTlsConfig` sets the bundle's `SSLContext` on `Jersey3DiscoveryClientOptionalArgs`. Replication is
   harder: Spring Cloud's `RefreshablePeerEurekaNodes` builds each peer's Jersey client with the JVM default trust
   store (cacerts) unless `-Dcom.netflix.eureka.shouldSSLConnectionsUseSystemSocketFactory=true` plus
   `javax.net.ssl.trustStore` are set. `EurekaTlsConfig` replaces the `PeerEurekaNodes` bean (the auto-configured one
   is `@ConditionalOnMissingBean`) with one that builds the same Jersey client — Apache connector, peer timeouts and
   pool sizes, gzip and identity filters — on an `SSLConnectionSocketFactory(sslContext)`. Trade-off: peer URLs are no
   longer re-read on `/actuator/refresh`; the 10-minute peer-list update still runs. Details in the
   [eureka-server README](eureka-server/README.md#https).
8. ***Every other caller of a TLS service***:
   - ***gateway*** — `spring.cloud.gateway.server.webflux.httpclient.ssl.ssl-bundle: ${app.tls.trust-bundle}`. The load-balancer
     filter picks `https` itself (`LoadBalancerUriTools.reconstructURI` uses `ServiceInstance.isSecure()`), so routes
     stay `lb://ORDER-SERVICE`.
   - ***Spring Boot Admin*** — `spring.http.clients.ssl.bundle: ${app.tls.trust-bundle}`. SBA's `AdminServerInstanceWebClientConfiguration`
     builds `InstanceWebClient` from Boot's `WebClient.Builder`, whose connector gets the bundle. Covers status polling,
     endpoint detection, info, and every proxied detail view. Details in the
     [admin-server README](admin-server/README.md#https).
   - ***zipkin-server*** — no bundle hook (Zipkin disables Boot auto-configuration; Armeria's
     `EurekaUpdatingListener` has no TLS setting). [`DevCaTrust`](zipkin-server/src/main/java/com/learning/discovery/zipkin/DevCaTrust.java)
     copies `tls/truststore.p12` to a temp file and sets `javax.net.ssl.trustStore` before Zipkin starts; Netty's TLS
     client falls back to the JDK default trust managers, which read it.
9. ***Tests*** — the integration tests call the embedded server over plain HTTP, so order/product/admin
   `application-test.yml` and `EurekaServerApplicationTests` set `server.ssl.enabled: false`.
   [`EurekaTlsTests`](eureka-server/src/test/java/com/learning/discovery/eureka/EurekaTlsTests.java) keeps TLS on and
   checks that a client trusting the `internal-ca` bundle gets `200` from `https://localhost:<port>/eureka/apps`, that the
   JDK's default trust store gets an `SSLHandshakeException`, and that the TLS-aware `PeerEurekaNodes` is in place.
10. ***Verify***:

    ```bash
    CA=certs/out/ca.crt
    curl --cacert $CA -u admin:admin -H 'Accept: application/json' https://localhost:8761/eureka/apps   # registry
    curl --cacert $CA https://127.0.0.1:8082/actuator/health                                            # order
    curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8082/actuator/health                      # 400: TLS only
    curl -u admin:admin http://localhost:8080/api/orders                                                # gateway → https
    openssl s_client -connect localhost:8761 -CAfile $CA </dev/null | grep -E 'Verify return|subject='  # handshake
    ```

    In the registry, order/product show `securePort … enabled=true`, `port … enabled=false` and an `https://`
    `secureHealthCheckUrl`; on each dashboard *DS Replicas* still lists the other nodes (replication works over TLS);
    SBA shows every instance `UP` with `https://` health URLs for order, product and the registry nodes.

### Gotchas

- ***Browsers warn on the dashboards*** (`https://localhost:8761`, Swagger UI on 8082/8083) until you import
  `certs/out/ca.crt` as a trusted authority, or click through.
- ***`PKIX path building failed` / `unable to find valid certification path`*** — the caller doesn't trust the CA:
  missing `internal-ca` bundle, stale `truststore.p12` after rerunning the script (it creates a new CA; rebuild and restart
  everything), or a caller that bypasses the bundle (step 6–8).
- ***`No subject alternative names matching …`*** — the URL host isn't in the SAN: usually an instance that registered
  its LAN IP. Set `eureka.instance.ip-address`, or regenerate with `EXTRA_SAN`.
- ***Eureka dashboard "Status" links for order/product point at `http://`*** — Spring Cloud's
  `DefaultManagementMetadataProvider` always builds `statusPageUrl` with `http`, even with `secure-port-enabled`; only
  the health-check URL gets an `https` twin. Cosmetic: nothing routes through it.
- ***`Cannot clean connections … Client instance has been closed`*** at registry shutdown — an upstream race in
  `EurekaJersey3ClientImpl.destroyResources()` (the cleaner runs after the client closes); same with Spring Cloud's own
  peer clients. Harmless.
- ***Same port, new scheme*** — anything with a hard-coded `http://localhost:8082` (bookmarks, Postman, the load test
  if pointed at a service directly) now gets `400`.
- ***Rotation*** — Tomcat can pick up a renewed key store without a restart
  (`spring.ssl.bundle.jks.server.reload-on-update: true` plus `spring.ssl.bundle.watch.file.quiet-period`), but only
  for `file:` locations (a `classpath:` store inside the jar can't be watched, as here), and the
  client side here creates its `SSLContext` once at startup (Eureka optional args, peer nodes), so rotating the
  ***CA*** still means a rolling restart. Issue new server certificates from the same CA to avoid that.

### Beyond this demo

- ***Edge TLS*** — the gateway (and user-service) still listen on plain HTTP; give them a `service-identity` bundle,
  `server.ssl.bundle: ${app.tls.server-bundle}` and a secure registration the same way admin-server got one.
- ***Mutual TLS*** — add a key store to the `internal-ca` bundle on callers and `server.ssl.client-auth: need` plus a
  trust store on servers; then a stolen Basic credential alone no longer reaches the registry or actuators.
- ***Real certificates*** — in Kubernetes, cert-manager (or a service mesh doing mTLS transparently) replaces the
  script; in a VM fleet, an internal CA (Vault PKI, ACME) with short-lived certs plus `reload-on-update`.

<a id="actuator-endpoints"></a>

## <span style="color:hsl(170,80%,58%)">10. 📡 Actuator endpoints</span>

Every endpoint Spring Boot Actuator (Boot 4.x, as of 2026) and Spring Cloud can expose, and which module exposes it over
HTTP here (`management.endpoints.web.exposure.include` in each `application.yml`). ✅ = exposed, — = not exposed. All
of them sit behind HTTP Basic except `health` and `info`; see [§8](#security-and-sba-features) for the SBA view each
one feeds.

- ***Boot's own default*** — over HTTP only `health` is exposed; every endpoint except `shutdown` and `heapdump` is
  accessible once exposed (both need `management.endpoint.<id>.access: unrestricted`; `heapdump` since Boot 3.5).
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

<a id="where-this-fits"></a>

## <span style="color:hsl(86,80%,58%)">11. 🗺️ Where this fits: Spring Cloud/Netflix OSS vs Kubernetes</span>

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

<a id="faq"></a>

## <span style="color:hsl(308,80%,58%)">12. ❓ FAQ</span>

<a id="faq-port-change"></a>

**What happens if a service's port changes after a restart?**

A different `server.port` means a different Eureka `instanceId` (default
`host:appName:port`) — it's registered as a brand-new instance, not an
update of the old one. A clean shutdown deregisters the old entry at once; after a crash it is
only removed once its lease expires. `eureka-server` has self-preservation on with a low local threshold (0.5), so
eviction happens 60–90 s after the last heartbeat (lease expiry is effectively 2 × the 30 s
`lease-expiration-duration-in-seconds` — a known `Lease.renew()` quirk — plus up to one 30 s eviction sweep) unless
many instances die at once. The gateway's own local
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
[§6](#how-the-gateway-finds-services-via-eureka).

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
observed behavior, not gateway code. `spring-cloud-starter-netflix-eureka-client`
pulls in `spring-cloud-starter-loadbalancer` transitively (the gateway starter has it only as an optional
dependency, and `gateway-service/pom.xml` doesn't declare it; `order-service/pom.xml` does); the gateway's [`ReactiveLoadBalancerClientFilter`][ReactiveLoadBalancerClientFilter]
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

Stack the intervals: up to ~90s for `eureka-server` to evict the crashed
instance (lease expiry is effectively 2 × the 30 s `lease-expiration-duration-in-seconds`
because of a known `Lease.renew()` quirk, plus up to one 30 s eviction sweep; self-preservation
is on, but with the 0.5 threshold one dead instance doesn't trip it) + up to 30s for the server's
read-only response cache (`response-cache-update-interval-ms`) + up to
30s for the gateway's own `registryFetchIntervalSeconds` to pull that
change + the load balancer's own instance-list cache (Spring Cloud
LoadBalancer caches the resolved list separately, default TTL ~35s) before
it stops offering the dead instance. Worst case, a client can be routed to
an already-dead instance for **about three minutes** after the failure,
purely from cache-staleness layered four deep — no bug involved, just
independent caches each doing their job on their own clock. Tightening any
one of those intervals trades staleness for load on `eureka-server` and the
gateway.

<a id="faq-shared-credentials-blast-radius"></a>

**7.10 🔥 Every module shares one `admin` user, the gateway forwards the caller's `Authorization` header as-is, and
the password sits in each instance's Eureka metadata — what is the blast radius of one leaked credential, and how
would you redesign it?**

***One leaked password is root on the whole system***: it opens the registry, every service's API and every actuator
— including `shutdown`, `restart`, `env` POST + `refresh` and `heapdump` — on every instance.

- ***Why it leaks easily*** — `eureka.instance.metadata-map.user.password` is replicated to every registry node and
  returned by `GET /eureka/apps` to every client (that is exactly how `admin-server` reads it). Anyone who can fetch
  the registry reads every service's password; a heap dump of any client holds it too.
- ***Why it is total*** — the same `spring.security.user` guards `/api/**`, `/actuator/**` and `/eureka/**` in every
  module, and every module exposes `shutdown` (`access: unrestricted`) and `env` POST (`env.post.enabled: true`).
  `POST /actuator/env` + `POST /actuator/refresh` rewrite config at runtime — e.g. repoint
  `eureka.client.service-url` at a rogue registry.
- ***Confused deputy*** — `UserClient` calls `user-service` with `order-service`'s configured credentials, not the
  caller's, so `user-service` can't tell "end user X" from "anyone who reached `order-service`". Through the gateway
  the end user's Basic header travels unchanged to every hop, in clear text (no TLS anywhere in this demo).
- ***Redesign***:

| Concern            | Now                                     | Target                                                                                                                     |
|--------------------|-----------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| End-user auth      | Basic, forwarded to every service       | OAuth2/OIDC login at the gateway; services are resource servers validating a JWT (signature, `aud`, expiry); `TokenRelay` |
| Service-to-service | shared `admin` credentials              | per-service client-credentials token (or mTLS identity) with narrow scopes, e.g. `users:read`                             |
| Actuator           | same user; `shutdown`, `env` POST open  | separate management port/network, per-service credentials, `shutdown`/`env` POST off outside dev                          |
| SBA → actuator     | plaintext password in registry metadata | SBA holds its own credential (`spring.boot.admin.instance-auth.*` or a secret store) — nothing secret in metadata         |
| Transport          | HTTP                                    | TLS at the edge at minimum; mTLS inside without a mesh                                                                     |

- ***Interview point*** — registry metadata is ***discovery data, not a secret store***: assume every client can read
  all of it. Related: [§8](#security-and-sba-features).

<a id="faq-slow-downstream-cascade"></a>

**7.11 🔥 `user-service` degrades to a 25 s p99 under load. What happens to `order-service` and the gateway, and why
doesn't the `user: null` fallback save you?**

***The fallback only runs after the timeout, so slow is worse than down***: every in-flight `/with-user` call pins a
Tomcat worker thread for up to the 30 s read timeout (`LoadBalancerConfig.boundedTimeouts()`), and the pool runs dry
long before any fallback fires.

- ***Little's law*** — concurrency = throughput × latency. Tomcat's default is 200 worker threads and virtual threads
  aren't enabled here, so at 25 s per call ***200 / 25 = 8 req/s*** saturates the whole service. `/api/orders` and
  `/actuator/health` queue behind it — same pool.
- ***Down vs slow*** — `user-service` down: connection refused in milliseconds → `RestClientException` →
  `user: null`, cheap. Slow: each call waits the full read timeout before the same fallback.
- ***Gateway*** — reactive (Netty), so no thread exhaustion, but ***no response timeout is configured***
  (`spring.cloud.gateway.server.webflux.httpclient.response-timeout` unset): it holds connections as long as
  `order-service` does. Clients give up first and retry → a retry storm on an already saturated service.
- ***Health lies*** — `UserServiceHealthIndicator` counts registry entries, not latency: UP with 2 instances while
  both crawl. Meanwhile liveness probes queue behind busy threads and can fail → the orchestrator restarts
  blocked-but-healthy instances → less capacity.
- ***Fix, in order***:
  1. ***Timeout budget*** derived from the SLO, shrinking per hop: gateway response timeout (say 5 s) > order → user
     read timeout (say 1–2 s). A 30 s read timeout behind a client that quits at 10 s is pure wasted work.
  2. ***Circuit breaker*** (Resilience4j through Spring Cloud CircuitBreaker) around `fetchUser`: past a
     failure/slow-call rate threshold, return `user: null` immediately without calling.
  3. ***Bulkhead*** — cap concurrent calls to `user-service` so one dependency can't take every thread. Virtual
     threads remove the thread cap but not the downstream pile-up, so keep the bulkhead.
  4. ***Retries with a budget*** — at most one, on another instance, for idempotent GETs, with jitter; never at
     every layer at once.
- ***Monitor*** — `http.client.requests` p99 for `USER-SERVICE`, busy Tomcat threads, breaker state, gateway 5xx/504
  rate, and a counter on the fallback path (today `user: null` is silent).

<a id="faq-primary-restclient-builder"></a>

**7.12 🔥 `order-service` defines two `RestClient.Builder` beans — a `@Primary` plain one and a `@LoadBalanced` one.
Delete the plain one: why does Eureka registration break, and what else silently changes?**

***Eureka's own HTTP client becomes load-balanced***: Spring Cloud Netflix builds its `RestClient` transport from an
unqualified `RestClient.Builder` lookup, so with only the `@LoadBalanced` builder left, the call to
`https://localhost:8761/eureka/` treats `localhost` as a service ID.

- ***Chicken-and-egg*** — resolving `localhost` needs the registry (no instances under that ID), but fetching the
  registry goes through that same client. Register, renew and fetch all fail; the instance never shows up and
  `UserClient` has nothing to resolve `USER-SERVICE` against either.
- ***`@LoadBalanced` is a qualifier*** — Spring Cloud Commons' `LoadBalancerRestClientBuilderBeanPostProcessor` adds
  the service-ID-rewriting interceptor only to builders carrying it. `@Primary` on the plain builder means every
  unqualified injection point (Eureka, any other library) gets the non-LB one; `UserClient` opts in explicitly with
  `@LoadBalanced` on its constructor parameter.
- ***Tracing changes too*** — only the `@LoadBalanced` builder gets `.observationRegistry(...)`. `RestClient.builder()`
  is not Boot's auto-configured builder and records no observations by default, so moving `UserClient` to an
  unobserved builder means no client span and no `traceparent` header: Zipkin shows gateway → order and
  `user-service` as two unrelated traces. The flip side: the unobserved plain builder keeps Eureka's 10 s heartbeats
  out of the traces.
- ***Timeouts*** — both builders use `boundedTimeouts()` (5 s connect, 30 s read). The JDK client's default read
  timeout is infinite, so a builder created elsewhere without it can hang a thread forever on a stuck peer.
- ***Catch it in review*** — any module declaring a `@LoadBalanced` builder must keep a plain one for infrastructure
  clients; an integration test asserting the instance appears in `GET /eureka/apps` catches the regression.

<a id="faq-zero-error-rolling-deploy"></a>

**7.13 🔥🔥 Design a zero-error rolling redeploy of `user-service` (3 instances) while traffic flows through the
gateway and through `order-service`'s direct `UserClient`. What fails today, and what sequence fixes it?**

***Today every stopped instance keeps being picked for up to ~1.5 minutes plus the drain***, because shutdown runs
drain → stop web server → *then* deregister, and three caches sit between the registry and each caller. The fix is
***deregister first, wait out the caches, then drain***, with one retry on another instance as a safety net.

- ***What happens on SIGTERM today*** (lifecycle phases checked against Boot 4.1 / Spring Cloud Netflix 5.0, as of
  2026):
  1. `server.shutdown: graceful` — Boot's graceful-shutdown lifecycle (phase `Integer.MAX_VALUE - 1024`, stops first)
     stops accepting new requests and drains in-flight ones for up to
     `spring.lifecycle.timeout-per-shutdown-phase` (30 s).
  2. The web server stops.
  3. Only then `EurekaAutoServiceRegistration` (phase 0) stops and the client sends its cancel
     (`eureka.client.should-unregister-on-shutdown`, default true).
  4. Callers see it only after eureka-server's read-only response cache (`response-cache-update-interval-ms`, 30 s)
     → their registry fetch (30 s) → Spring Cloud LoadBalancer's instance cache (`spring.cloud.loadbalancer.cache.ttl`,
     35 s).
  - ***Result*** — round-robin over 3 instances sends ~1/3 of calls to a refusing instance for that window. The
    gateway returns the error as-is (no `Retry` filter); `UserClient` swallows it into `user: null` — a silent
    data-quality incident, not an alert.
- ***Target sequence***, one instance at a time, surge first (start the new instance before stopping an old one, so
  capacity never drops below 3):

```mermaid
sequenceDiagram
    autonumber
    participant D as Deployer
    participant U as user-service (old)
    participant E as eureka-server
    participant C as gateway / order-service
    D ->> D: start new instance, wait for readiness + UP in registry + one fetch cycle
    D ->> U: POST /actuator/serviceregistry {"status":"OUT_OF_SERVICE"}
    U ->> E: on-demand status update
    Note over E, C: wait > 30 s response cache + 30 s fetch + 35 s LB cache
    C -->> C: only UP instances offered — old one no longer picked
    D ->> U: SIGTERM → graceful drain of stragglers → deregister
```

- ***What has to change***:
  - Expose the `serviceregistry` actuator endpoint (Spring Cloud Commons) — it is in no exposure list here today —
    behind the actuator credentials.
  - Gate registration on real readiness: `eureka.client.healthcheck.enabled: true` propagates actuator health into
    the Eureka status, so a DOWN health never shows up as UP in the registry.
  - Safety net for the remaining race: gateway `Retry` filter on `GET` for connect errors/`BAD_GATEWAY`; for
    `UserClient`, Spring Cloud LoadBalancer retry (`spring.cloud.loadbalancer.retry.max-retries-on-next-service-instance`)
    — check which retry library your version needs on the classpath. Retry idempotent calls only.
  - Optionally shrink the windows (`registry-fetch-interval-seconds` 5–10 s on callers, a shorter LB `cache.ttl`,
    lower or disabled read-only response cache on the server) — paid for with N clients × fetch rate on the registry.

| Approach                               | Client-visible errors              | Deploy time per instance | Cost                        |
|----------------------------------------|------------------------------------|--------------------------|-----------------------------|
| Plain SIGTERM (today)                  | ~1/3 of calls for ~1.5 min + drain | fastest                  | none                        |
| Retries only                           | mostly masked; extra latency       | fastest                  | retry load; unsafe for POST |
| OUT_OF_SERVICE → wait → drain (+retry) | ~0                                 | +~100 s                  | deploy tooling              |
| Shrunken caches                        | shorter windows                    | shorter wait             | more registry traffic       |

- ***On Kubernetes*** the same idea is a `preStop` hook that marks OUT_OF_SERVICE and sleeps past the cache window
  before SIGTERM arrives.
- ***Monitor*** — gateway 5xx per route during deploys, a counter on the `user: null` path, Eureka renews per minute
  (a drop mid-deploy means instances left without cancelling). Window arithmetic: [12.9](#faq-worst-case-detection).

<a id="faq-migrate-to-kubernetes"></a>

**7.14 🔥🔥 You must move this stack onto Kubernetes without a big-bang cutover. Keep Eureka and the gateway, or
replace them — and how do you migrate service by service with rollback?**

***Replace Eureka with Kubernetes Services in the end, but bridge through Spring Cloud's `DiscoveryClient`
abstraction***: the code depends on service IDs (`lb://USER-SERVICE`, `http://USER-SERVICE`), not on Eureka, so
swapping the discovery backend is a dependency/config change per module, not a code rewrite.

- ***Why not just run Eureka inside the cluster forever*** — two sources of truth (pod readiness vs Eureka
  heartbeats + self-preservation) that disagree during exactly the incidents you care about, and one more stateful
  system to operate for something the platform already does.

| Option                               | How                                                                | Pros                                                         | Cons                                                                                             |
|--------------------------------------|--------------------------------------------------------------------|--------------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| A. Eureka in Kubernetes              | StatefulSet of 3, peers via headless-Service DNS                   | zero code change; VMs and pods share one registry            | two health models; pod IPs must be routable from VMs; you still run Eureka                       |
| B. Spring Cloud Kubernetes discovery | swap the Eureka client starter for the Kubernetes discovery client | `lb://` and `@LoadBalanced` keep working; SBA can use it too | needs RBAC to read Services/Endpoints; IDs become lowercase Service names                        |
| C. Plain Service DNS                 | `http://user-service:8081` through kube-proxy; drop client-side LB | no discovery library; readiness gates traffic                | L4 per-connection balancing (keep-alive connections stick to one pod); no retry on next instance |

- ***Migration plan (strangler, leaf services first)***:
  1. ***Normalize IDs*** — switch `lb://USER-SERVICE` to `lb://user-service` while still on Eureka (Eureka's VIP
     lookup is case-insensitive; Kubernetes Service names must be lowercase).
  2. ***Bridge phase*** — Eureka (option A, or kept on VMs) stays the shared registry while services move one at a
     time; Spring Cloud Commons' `CompositeDiscoveryClient` queries several discovery clients in order and returns
     the first non-empty answer, letting callers prefer Kubernetes and fall back to Eureka for not-yet-moved services.
  3. ***Probes*** — map Boot's `/actuator/health/liveness` and `/readiness` (already on: `probes.enabled: true`) to
     Kubernetes probes; readiness + Endpoints removal replace the OUT_OF_SERVICE dance from
     [12.13](#faq-zero-error-rolling-deploy) (still add a short `preStop` sleep for endpoint propagation).
  4. ***Edge*** — keep Spring Cloud Gateway as an in-cluster Deployment behind an Ingress/Gateway API listener while
     it still adds value (auth, Swagger aggregation, per-route filters such as `SetPath`); replace it with
     Gateway API `HTTPRoute`s only when routes are plain path → Service with simple rewrites.
  5. ***Operations*** — `admin-server` switches to Kubernetes discovery; `admin/admin` moves to Secrets; in-memory
     Quartz jobs run once per replica (fine for the heartbeat demo, wrong for real jobs → clustered JDBC job store
     or a Kubernetes CronJob).
  6. ***Decommission*** — stop Eureka only after the last VM caller is gone (watch registrations and fetch traffic
     on `eureka-server` drop to zero).
- ***Rollback*** — every step is a config flip (discovery client order, weighted DNS at the edge, a Deployment scaled
  back to VMs); keep the Eureka cluster running and the VM deploy pipeline intact until the end.
- ***Trade-off to state out loud*** — option C is the simplest end state, but losing client-side LB means losing
  "retry on the next instance"; add that back via a mesh or keep B. Background: [§11](#where-this-fits).
