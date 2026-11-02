# <span style="color:hsl(201,80%,58%)">gateway-service</span>

## <span style="color:hsl(151,80%,58%)">Table of contents</span>

1. 🧭 [Overview](#overview)
2. 🔀 [How a request reaches a downstream service](#request-routing-flow)
3. 🚀 [Run and credentials](#run-and-credentials)
4. 🔐 [Security](#security)
5. 📊 [Actuator endpoints](#actuator-endpoints)
6. ⏱️ [Quartz job, logging and configuration](#quartz-logging-config)
7. 🧪 [Tests](#tests)
8. ⚠️ [Gotchas](#gotchas)
9. ❓ [Q&A](#qa)
    - 9.1 🔥 [The `gateway` actuator endpoint is `access: unrestricted` and `env` POST + `refresh` are on — what can someone holding the credentials do to the route table, and why is that worse than on a backend?](#qa-actuator-route-injection)
    - 9.2 🔥 [Why are the service specs routed at `/api-docs/{service}` instead of `/v3/api-docs/{service}` — and what decides who wins when a gateway route and a local handler match the same path?](#qa-route-vs-handler-precedence)
    - 9.3 🔥 [A teammate adds a `GlobalFilter` that checks an API key with a blocking JDBC lookup — what happens under load, and how do you catch and fix it?](#qa-blocking-in-filter)
    - 9.4 🔥🔥 [Traffic grows 10× across 3 gateway instances — design per-client rate limiting, timeouts and circuit breaking at this edge, given that every client here logs in as the same `admin` user](#qa-edge-rate-limit-breaker)
    - 9.5 🔥🔥 [Canary `user-service` v2 at 5 % of traffic through this gateway — how, given Eureka discovery and `order-service` calling `user-service` directly?](#qa-canary-release)
    - 9.6 🔥 [What are the drawbacks of Spring Cloud LoadBalancer, which picks the instance for every `lb://` route here?](#qa-loadbalancer-drawbacks)
    - 9.7 [Does Spring Cloud LoadBalancer need Redis?](#qa-loadbalancer-redis)
    - 9.8 [Which load-balancing strategies does Spring Cloud LoadBalancer offer, and how would you switch the gateway to one?](#qa-loadbalancer-strategies)

API gateway module of [learning-spring-cloud](../README.md). The project-wide
picture (all 6 modules, Eureka registry flow, Spring Boot Admin) lives in the root
README; this file covers only what `gateway-service` itself implements.

<a id="overview"></a>

## <span style="color:hsl(288,80%,58%)">1. 🧭 Overview</span>

- ***Single entry point on port 8080*** — routes `/api/users/**`, `/api/orders/**` and `/api/products/**` to the three
  backend services without knowing a single host or port.
- ***Reactive stack*** — `spring-cloud-starter-gateway-server-webflux` (Netty, Project Reactor), so security, actuator
  and tests use the WebFlux variants (`SecurityWebFilterChain`, `WebTestClient`), not the servlet ones.
- ***Itself a Eureka client*** — registers as `GATEWAY-SERVICE` and pulls the registry it needs for `lb://` resolution.
- ***Observed by Spring Boot Admin*** — exposes a broad set of actuator endpoints and publishes its credentials in
  Eureka metadata so `admin-server` can poll them.

**Stack (as of 2026):**

| Concern         | Dependency ([pom.xml](pom.xml))                                                                                                                   | Version source                                                             |
|-----------------|---------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------|
| Gateway         | `spring-cloud-starter-gateway-server-webflux`                                                                                                     | Spring Cloud 2025.1.2 (via root [pom.xml](../pom.xml) / `super-pom` 1.2.1) |
| Discovery       | `spring-cloud-starter-netflix-eureka-client`                                                                                                      | Spring Cloud BOM                                                           |
| Ops             | `spring-boot-starter-actuator`                                                                                                                    | Spring Boot 4.1                                                            |
| Tracing         | `spring-boot-starter-opentelemetry` → OTLP to [../zipkin-server](../zipkin-server/README.md)                                                      | Spring Boot 4.1                                                            |
| API docs        | `springdoc-openapi-starter-webflux-ui` → aggregated Swagger UI at http://localhost:8080/swagger-ui.html (specs routed from `/api-docs/{service}`) | springdoc 3.1                                                              |
| Auth            | `spring-boot-starter-security`                                                                                                                    | Spring Boot BOM                                                            |
| Scheduling demo | `spring-boot-starter-quartz`                                                                                                                      | Spring Boot BOM                                                            |
| Tests           | `spring-boot-starter-test`, `spring-boot-webtestclient`, `spring-boot-starter-security-test`                                                      | Spring Boot BOM                                                            |
| SBOM            | `cyclonedx-maven-plugin` (declared in the root [pom.xml](../pom.xml))                                                                             | `spring-boot-starter-parent` pluginManagement                              |

**Routes** (declared in [application.yml](src/main/resources/application.yml) under
`spring.cloud.gateway.server.webflux.routes`):

| Route id          | Predicate               | URI                    | Filters                         |
|-------------------|-------------------------|------------------------|---------------------------------|
| `user-service`    | `Path=/api/users/**`    | `lb://USER-SERVICE`    | none (path forwarded unchanged) |
| `order-service`   | `Path=/api/orders/**`   | `lb://ORDER-SERVICE`   | none                            |
| `product-service` | `Path=/api/products/**` | `lb://PRODUCT-SERVICE` | none                            |

- ***No `StripPrefix` / `RewritePath`*** — `/api/users/1` arrives downstream as `/api/users/1`, matching the backends'
  own controller mappings.
- ***No `RemoveRequestHeader=Authorization`*** — deliberate, see [§4](#security).
- ***`lb://` = load-balanced service ID*** — Spring Cloud LoadBalancer's `ReactiveLoadBalancerClientFilter` looks the ID
  up in the gateway's local Eureka cache (refreshed every 30s by default), picks an instance (round-robin by default),
  and rewrites the request to that instance's `host:port`. Step by step in [§2](#request-routing-flow); registry
  side: [root README §2](../README.md#how-the-gateway-finds-services-via-eureka).
- ***Inspect the live route table*** — `GET /actuator/gateway/routes` (authenticated).

<a id="request-routing-flow"></a>

## <span style="color:hsl(201,80%,58%)">2. 🔀 How a request reaches a downstream service</span>

- ***The Eureka client doesn't route anything.*** On the request path it only answers "which instances does
  `USER-SERVICE` have?" — and it answers from ***its local in-memory registry copy***, not by calling `eureka-server`.
  ***Spring Cloud LoadBalancer*** picks the instance; ***Reactor Netty*** sends the request.
- ***One request, `GET /api/users/1`*** (class names checked against Gateway 5.0.3, eureka-client 5.0.2,
  loadbalancer 5.0.3, as of 2026):

```text
client ─► gateway-service :8080
  1. RoutePredicateHandlerMapping       Path=/api/users/** matches route user-service (uri: lb://USER-SERVICE)
  2. RouteToRequestUrlFilter            target URL = lb://USER-SERVICE/api/users/1
  3. ReactiveLoadBalancerClientFilter   lb:// scheme ──► Spring Cloud LoadBalancer
       RoundRobinLoadBalancer.choose()
         └─ CachingServiceInstanceListSupplier             (cache.ttl 35 s)
              └─ DiscoveryClientServiceInstanceListSupplier
                   └─ EurekaReactiveDiscoveryClient.getInstances("USER-SERVICE")
                        └─ Eureka client's local registry copy   ◄── no network call
       picks 192.168.1.20:8081 → URL rewritten to http://192.168.1.20:8081/api/users/1
  4. NettyRoutingFilter                 Reactor Netty HttpClient calls that instance directly
```

- ***Who owns which step*** — steps 1, 2 and 4 are Gateway; choosing the instance in step 3 is Spring Cloud
  LoadBalancer; the Eureka client is only the ***data source*** at the bottom of step 3.

<a id="request-routing-eureka-client-jobs"></a>

**Everything the Eureka client does in this module**

| Job | When | Network call to `eureka-server`? |
|---|---|---|
| Register `GATEWAY-SERVICE` | startup | yes |
| Heartbeat | every 10 s (`lease-renewal-interval-in-seconds: 10`) | yes |
| Fetch the registry (full, then deltas) | startup, then every 30 s (default `registry-fetch-interval-seconds`, not overridden here) | yes |
| ***Answer `getInstances()` during routing*** | ***every request*** (the balancer caches the result for 35 s) | ***no — local copy*** |
| Return only `UP` instances | every lookup | no (`filter-only-up-instances`, default `true`) |
| Expose instance metadata (zone, IP/port via `prefer-ip-address`, weight/hint tags) | every lookup | no — read by the balancer's zone/weighted/hint filters ([Q8](#qa-loadbalancer-strategies)) |

**Two less obvious roles**

- ***It brings in the load balancer*** — [pom.xml](pom.xml) doesn't declare `spring-cloud-starter-loadbalancer`; the
  dependency tree shows it arriving through `spring-cloud-starter-netflix-eureka-client`. So the Eureka starter is
  why `lb://` works at all, even though the balancing code isn't part of it.
- ***It decides what the balancer can see*** — `DOWN` and `OUT_OF_SERVICE` instances are filtered out before
  balancing. That's why marking an instance `OUT_OF_SERVICE` drains it (root
  [FAQ: zero-error rolling deploy](../README.md#faq-zero-error-rolling-deploy)), and why a crashed instance that Eureka
  hasn't evicted yet still gets traffic ([Q6](#qa-loadbalancer-drawbacks)).

**What follows from this**

- ***`eureka-server` down → routing keeps working*** from the last local copy; the gateway just learns nothing new
  (root [FAQ: gateway cache](../README.md#faq-gateway-cache)).
- ***Eureka is swappable*** — Gateway and LoadBalancer only use the `ReactiveDiscoveryClient` abstraction, so Consul,
  the Kubernetes discovery client or a static list (`spring.cloud.discovery.client.simple.instances`) can replace it
  with no route changes.

<a id="run-and-credentials"></a>

## <span style="color:hsl(66,80%,50%)">3. 🚀 Run and credentials</span>

- ***Start `eureka-server` first***, then the backends, then the gateway
  (order: [root README §4](../README.md#run-order)).

```bash
cd learning-spring-cloud
mvn -pl gateway-service spring-boot:run
# or with custom credentials
ACTUATOR_USER=ops ACTUATOR_PASSWORD=s3cret mvn -pl gateway-service spring-boot:run
```

- ***One user*** — `spring.security.user.name/password` = `${ACTUATOR_USER:admin}` / `${ACTUATOR_PASSWORD:admin}`, role
  `ACTUATOR`.
- ***The same values are reused three times*** — to log in to the gateway, embedded in the Eureka `defaultZone` URL, and
  published as Eureka metadata for SBA. Change the env vars on every module together, because the backends check the
  same header (see [§4](#security)).

```bash
# Through the gateway (Basic creds checked by the gateway AND by the downstream service)
curl -u admin:admin http://localhost:8080/api/users
curl -u admin:admin http://localhost:8080/api/users/1
curl -u admin:admin http://localhost:8080/api/orders
curl -u admin:admin http://localhost:8080/api/products

# Public, no credentials
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/info

# Gateway introspection
curl -u admin:admin http://localhost:8080/actuator/gateway/routes

# 401 — no credentials
curl -i http://localhost:8080/api/users
```

<a id="security"></a>

## <span style="color:hsl(256,80%,58%)">4. 🔐 Security</span>

Implemented in [SecurityConfig.java](src/main/java/com/learning/discovery/gateway/config/SecurityConfig.java)
(`@EnableWebFluxSecurity`).

- ***Reactive `SecurityWebFilterChain`*** — the gateway runs on WebFlux, so the servlet `SecurityFilterChain` /
  `HttpSecurity` don't apply; the bean takes `ServerHttpSecurity`, the auto-configured `ReactiveUserDetailsService`
  (built from `spring.security.user.*`) and the `ApplicationEventPublisher`.
- ***HTTP Basic only*** — no form login, no sessions to speak of; every request carries `Authorization: Basic ...`.
- ***Permitted without auth*** — `/actuator/health/**`, `/actuator/info`, `/swagger-ui/**`, `/v3/api-docs/**`, `/error`.
  Everything else (`anyExchange().authenticated()`), including all `/api/**` routes and every other actuator endpoint,
  needs credentials.
- ***CSRF disabled*** — `ServerHttpSecurity.CsrfSpec::disable`: stateless API proxy with no browser session, and SBA's
  POST actions (shutdown, restart, refresh, env update) would otherwise be rejected.

**Authorization header forwarded downstream:**

- ***Why it matters*** — `user-service`, `order-service` and `product-service` are each secured with the same Basic
  user, so the backend must receive the client's credentials.
- ***How it happens*** — Spring Cloud Gateway forwards request headers unchanged; its `RemoveHopByHopHeadersFilter`
  strips only hop-by-hop headers (`Connection`, `Keep-Alive`, `Transfer-Encoding`, ...) and `Authorization` is not one
  of them.
- ***What would break it*** — adding `RemoveRequestHeader=Authorization` to a route (a common pattern when the gateway
  is the only trust boundary) would turn every proxied call into a downstream 401. The comment in `application.yml`
  above `routes:` records this choice.
- ***Trade-off*** — this is credential pass-through, not token relay; a production setup would typically validate a JWT
  at the gateway and relay it (or mint a service token) instead.

**Audit-event publishing wrapper:**

- ***Problem*** — reactive Spring Security publishes no `AuthenticationSuccessEvent` / failure events by default, so
  `/actuator/auditevents` (SBA "Audit Events" view) would stay empty.
- ***Fix*** — `auditing(...)` wraps `UserDetailsRepositoryReactiveAuthenticationManager` and plugs it into
  `httpBasic(basic -> basic.authenticationManager(...))`:

```java
var publisher = new DefaultAuthenticationEventPublisher(eventPublisher);
return authentication -> delegate.authenticate(authentication)
        .doOnNext(publisher::publishAuthenticationSuccess)
        .doOnError(AuthenticationException.class,
                ex -> publisher.publishAuthenticationFailure(ex, authentication));
```

- ***Result*** — Boot's `AuthenticationAuditListener` converts those events into `AUTHENTICATION_SUCCESS` /
  `AUTHENTICATION_FAILURE` audit events, stored in the `InMemoryAuditEventRepository`
  from [ActuatorConfig.java](src/main/java/com/learning/discovery/gateway/config/ActuatorConfig.java).

**Request flow:**

```mermaid
sequenceDiagram
    autonumber
    participant C as Client (curl / Insomnia)
    participant G as gateway-service :8080
    participant E as eureka-server :8761
    participant U as user-service :8081

    Note over G,E: Background: gateway registers and pulls registry every 30s<br/>(Basic creds in defaultZone URL)
    G->>E: GET /eureka/apps (admin:admin)
    E-->>G: registry (USER-SERVICE -> 192.168.x.x:8081, ...)

    C->>G: GET /api/users/1<br/>Authorization: Basic admin:admin
    G->>G: SecurityWebFilterChain: Basic auth<br/>(auditing manager publishes AUTHENTICATION_SUCCESS)
    alt bad / missing credentials
        G-->>C: 401 Unauthorized (+ AUTHENTICATION_FAILURE audit event)
    else authenticated
        G->>G: route match Path=/api/users/** -> lb://USER-SERVICE
        G->>G: ReactiveLoadBalancerClientFilter picks instance from local cache
        G->>U: GET /api/users/1<br/>Authorization header forwarded unchanged
        U->>U: servlet SecurityFilterChain: Basic auth
        U-->>G: 200 JSON
        G-->>C: 200 JSON
    end
```

<a id="actuator-endpoints"></a>

## <span style="color:hsl(33,80%,58%)">5. 📊 Actuator endpoints</span>

All exposed via `management.endpoints.web.exposure.include` in [application.yml](src/main/resources/application.yml).
Only `health` and `info` are public; the rest need Basic auth. SBA (`admin-server`) authenticates with the `user.name` /
`user.password` Eureka metadata.

| Endpoint        | SBA view / action                                  | Backing config in this module                                                                                                                                                                   |
|-----------------|----------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `health`        | Details / status                                   | `show-details: always`; public                                                                                                                                                                  |
| `info`          | Details                                            | public                                                                                                                                                                                          |
| `gateway`       | Gateway (routes, global filters, route predicates) | `management.endpoint.gateway.access: unrestricted`; supplied by Spring Cloud Gateway                                                                                                            |
| `env`           | Environment (+ Update form)                        | `management.endpoint.env.post.enabled: true` (POST via Spring Cloud Context)                                                                                                                    |
| `refresh`       | Environment "Refresh"                              | `access: unrestricted`; re-binds `@ConfigurationProperties` / `@RefreshScope`                                                                                                                   |
| `metrics`       | Metrics                                            | default Micrometer meters                                                                                                                                                                       |
| `loggers`       | Loggers (change levels live)                       | default                                                                                                                                                                                         |
| `threaddump`    | Threads                                            | default                                                                                                                                                                                         |
| `heapdump`      | Heap Dump                                          | `management.endpoint.heapdump.access: unrestricted` (Boot 3.5+ defaults to `none`)                                                                                                              |
| `mappings`      | Mappings                                           | default (lists WebFlux handler mappings)                                                                                                                                                        |
| `beans`         | Beans                                              | default                                                                                                                                                                                         |
| `configprops`   | Configuration Properties                           | default                                                                                                                                                                                         |
| `logfile`       | Logfile                                            | `logging.file.name: logs/${spring.application.name}.log`                                                                                                                                        |
| `shutdown`      | Shutdown button                                    | `management.endpoint.shutdown.access: unrestricted`                                                                                                                                             |
| `restart`       | Restart button                                     | `access: unrestricted`; endpoint from Spring Cloud Context                                                                                                                                      |
| `httpexchanges` | HTTP Exchanges                                     | `InMemoryHttpExchangeRepository` bean in [ActuatorConfig.java](src/main/java/com/learning/discovery/gateway/config/ActuatorConfig.java); recorded by `HttpExchangesWebFilter` (WebFlux variant) |
| `startup`       | Startup                                            | `BufferingApplicationStartup(2048)` set in `main()` of [GatewayServiceApplication.java](src/main/java/com/learning/discovery/gateway/GatewayServiceApplication.java)                            |
| `auditevents`   | Audit Events                                       | `InMemoryAuditEventRepository` bean + auditing auth manager ([§4](#security))                                                                                                                   |
| `sbom`          | SBOM                                               | root `cyclonedx-maven-plugin` writes `META-INF/sbom/application.cdx.json` at build time                                                                                                         |
| `quartz`        | Quartz                                             | `spring.quartz.job-store-type: memory` + `HeartbeatJob` ([§6](#quartz-logging-config))                                                                                                          |

- ***`startup` only exists when launched via `main()`*** — `@SpringBootTest` builds its own `SpringApplication` and
  skips `main()`, so there is no `BufferingApplicationStartup` and `/actuator/startup` is absent in tests.
- ***No `jolokia`*** — `jolokia-support-springboot` is servlet-only; the servlet modules (user/order/product/eureka)
  expose JMX via Jolokia, the WebFlux gateway (and `admin-server`) cannot, so SBA's JMX tab is missing here.
- ***Mutating endpoints*** — `shutdown`, `restart`, `refresh` and `env` POST change the running process; acceptable only
  because they sit behind Basic auth on a laptop.

```bash
curl -u admin:admin http://localhost:8080/actuator/gateway/routes
curl -u admin:admin "http://localhost:8080/actuator/auditevents?type=AUTHENTICATION_SUCCESS"
curl -u admin:admin http://localhost:8080/actuator/httpexchanges
curl -u admin:admin -o gateway.hprof http://localhost:8080/actuator/heapdump
curl -u admin:admin -X POST http://localhost:8080/actuator/refresh
```

### Health probes, info and tags

- ***Probes*** — `management.endpoint.health.probes.enabled: true` adds public `/actuator/health/liveness` and
  `/actuator/health/readiness` (SBA Details → Health groups).
- ***Info*** — `info.app.*` (name, description, `tier: edge`, environment) plus the `java`, `os`, `process` contributors
  (`management.info.*.enabled`) → SBA Details → Info.
- ***Tags*** — `eureka.instance.metadata-map.tags.environment` (`APP_ENV`, default `local`) and `tags.tier: edge` →
  badges in SBA's application list.
- Custom health indicators and metrics live in [order-service](../order-service/README.md) only.

<a id="quartz-logging-config"></a>

## <span style="color:hsl(171,80%,58%)">6. ⏱️ Quartz job, logging and configuration</span>

**Quartz demo job** ([job/](src/main/java/com/learning/discovery/gateway/job)):

- ***`HeartbeatJob`*** ([HeartbeatJob.java](src/main/java/com/learning/discovery/gateway/job/HeartbeatJob.java)) —
  extends `QuartzJobBean`, logs `Heartbeat from gateway-service (fire time ...)` at INFO.
- ***`QuartzConfig`*** ([QuartzConfig.java](src/main/java/com/learning/discovery/gateway/job/QuartzConfig.java)) —
  `JobDetail` `demo.heartbeatJob` (`storeDurably()`), `Trigger` `demo.heartbeatTrigger` with
  `SimpleScheduleBuilder.repeatMinutelyForever()`.
- ***In-memory store*** — `spring.quartz.job-store-type: memory`; nothing persisted, schedule resets on restart. The job
  exists purely so SBA's Quartz view has something to show.

**File logging:**

- ***`logging.file.name: logs/gateway-service.log`*** — relative to the process working directory; enables
  `/actuator/logfile`.
- ***Banner*** — [banner.txt](src/main/resources/banner.txt) prints "GATEWAY" plus project metadata and the gateway URL
  `http://localhost:${server.port}`.

**Annotated [application.yml](src/main/resources/application.yml) excerpt:**

<details><summary><h2><code><b>APPLICATION.YML — CLICK TO VIEW</b></code></h2></summary>

```yaml
spring:
  application:
    name: gateway-service            # Eureka app name GATEWAY-SERVICE
  security:
    user:
      name: ${ACTUATOR_USER:admin}
      password: ${ACTUATOR_PASSWORD:admin}
      roles: ACTUATOR
  quartz:
    job-store-type: memory           # backs /actuator/quartz
  cloud:
    gateway:
      server:
        webflux:                     # Spring Cloud 2025.x property prefix (was spring.cloud.gateway.routes)
          routes:                    # no RemoveRequestHeader: Authorization reaches the backend
            - id: user-service
              uri: lb://USER-SERVICE # service ID, resolved via Eureka + LoadBalancer
              predicates:
                - Path=/api/users/**
            # order-service, product-service: same shape

server:
  port: 8080

eureka:
  client:
    service-url:                     # Eureka server is Basic-secured too
      defaultZone: http://${spring.security.user.name}:${spring.security.user.password}@localhost:8761/eureka/
  instance:
    prefer-ip-address: true          # register IP, not hostname
    metadata-map:                    # SBA reads these to poll the secured actuator
      user.name: ${spring.security.user.name}
      user.password: ${spring.security.user.password}

logging:
  file:
    name: logs/${spring.application.name}.log   # /actuator/logfile

management:
  endpoint:
    gateway:
      access: unrestricted
    heapdump:
      access: unrestricted
    shutdown / restart / refresh:
      access: unrestricted           # SBA buttons (shown collapsed here)
    env:
      post:
        enabled: true
    health:
      show-details: always
```

</details>

<a id="tests"></a>

## <span style="color:hsl(0,80%,58%)">7. 🧪 Tests</span>

[GatewayServiceApplicationIT.java](src/test/java/com/learning/discovery/gateway/GatewayServiceApplicationIT.java) —
`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureWebTestClient` +
`@ActiveProfiles("test")`; [application-test.yml](src/test/resources/application-test.yml) sets
`eureka.client.enabled: false` so no registry is needed.

| Test                                              | Checks                                                                                                                                                                   |
|---------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `healthEndpointReportsUpWithoutCredentials`       | `GET /actuator/health` with no auth → 200, `$.status == "UP"` (permitAll works)                                                                                          |
| `protectedPathWithoutCredentialsIsUnauthorized`   | `GET /actuator/env` with no auth → 401                                                                                                                                   |
| `protectedPathWithCredentialsIsAllowedAndAudited` | `GET /actuator/env` as `admin:admin` → 200; then `/actuator/auditevents?type=AUTHENTICATION_SUCCESS` returns `$.events[0].principal == "admin"` (auditing wrapper works) |
| `quartzHeartbeatJobIsRegistered`                  | `GET /actuator/quartz/jobs/demo/heartbeatJob` → 200, `$.name == "heartbeatJob"`                                                                                          |

- ***Not covered*** — actual proxying through `lb://` routes (would need registered backends or a stubbed
  `ServiceInstanceListSupplier`).
- ***Run*** — `mvn -pl gateway-service verify` (failsafe runs `*IT` in `integration-test`;
  see [root README §6](../README.md#tests)).

<a id="gotchas"></a>

## <span style="color:hsl(306,80%,58%)">8. ⚠️ Gotchas</span>

- ***503 Service Unavailable on `/api/**`*** — the gateway authenticated you but LoadBalancer found no instance for
  `USER-SERVICE` etc.: backend not started, not yet registered, or the gateway's registry cache hasn't refreshed (up
  to ~30s, plus Eureka's own response cache). Check http://localhost:8761 and retry.
- ***401 from the gateway*** — missing/wrong Basic creds, or `ACTUATOR_USER/ACTUATOR_PASSWORD` set differently from what
  the client sends.
- ***401 that came from the backend*** — gateway and backend run with different credentials; the forwarded header passes
  the gateway but fails downstream. Keep the env vars identical across modules.
- ***Eureka registration fails with 401*** — `eureka-server` uses the same creds; mismatched values break the
  `defaultZone` URL.
- ***Lombok is not on the classpath*** — unlike user/order/product, this module's [pom.xml](pom.xml) has no Lombok; use
  plain SLF4J `LoggerFactory` (as `HeartbeatJob` does), constructors and records.
- ***Changing `spring.application.name` breaks `lb://` for others, not for itself*** — renaming a backend (e.g.
  `user-service`) changes its Eureka ID, so `lb://USER-SERVICE` here resolves to nothing (503); renaming the gateway
  only changes its own registration and log file name.
- ***Servlet-only features don't work here*** — no Jolokia, no `spring-boot-starter-web`, no `HttpSecurity`; adding
  `spring-boot-starter-web` would conflict with the WebFlux gateway server.
- ***Old property prefix*** — Spring Cloud 2025.x (as of 2026) moved routes to
  `spring.cloud.gateway.server.webflux.routes`; copying older examples that use `spring.cloud.gateway.routes` is
  deprecated and should not be relied on.

<a id="qa"></a>

## <span style="color:hsl(116,80%,58%)">9. ❓ Q&A</span>

<a id="qa-actuator-route-injection"></a>

**Q1. 🔥 The `gateway` actuator endpoint is `access: unrestricted` and `env` POST + `refresh` are on. What can someone
holding the credentials do to the route table, and why is that worse than on a backend?**

- ***They can rewrite where traffic goes.*** `POST /actuator/gateway/routes/{id}` with a route definition, then
  `POST /actuator/gateway/refresh`, adds a live route to ***any*** URI — no restart, no config change in Git.
  `POST /actuator/env` + `POST /actuator/refresh` does the same via properties.
- ***Why the gateway is the worst place for this***:
  - ***Credential exfiltration*** — routes forward the caller's `Authorization` header unchanged ([§4](#security)).
    A route `Path=/api/users/**` → `https://attacker.example` (ordered before the real one) harvests every user's
    Basic credentials while still proxying the response, so nobody notices.
  - ***SSRF*** — a route to `http://169.254.169.254/` or an internal admin port turns the gateway into a proxy into
    the private network, with the gateway's own network position.
  - ***History*** — CVE-2022-22947 was remote code execution through exactly this endpoint (SpEL in route
    definitions added via the actuator), fixed in Spring Cloud Gateway 3.1.1 / 3.0.7. Patched versions still let an
    authorised caller redirect traffic — that's a feature, not a bug.
- ***Hardening*** (beyond the shared-credential problem in the root [FAQ 7.10](../README.md#faq-shared-credentials-blast-radius)):

| Control                    | How                                                                                                    |
|----------------------------|--------------------------------------------------------------------------------------------------------|
| Restrict writes            | `POST`/`DELETE` on `/actuator/gateway/**` need a separate admin role (method-based security matcher), or don't expose `gateway` in prod |
| Remove runtime config edit | `env.post.enabled: false`; no `refresh`/`restart` exposure outside dev                                 |
| Separate management plane  | `management.server.port` bound to an internal interface; not reachable through the public listener     |
| Routes as code             | routes only from versioned config; diff `GET /actuator/gateway/routes` against Git in a periodic check |
| Egress allowlist           | network policy so the gateway can only reach backend subnets — limits SSRF even if a route is injected |

- ***Detect*** — audit events for every actuator POST (the `auditing` manager already records who authenticated),
  alerts on route count changes, and egress to unexpected hosts.

<a id="qa-route-vs-handler-precedence"></a>

**Q2. 🔥 Why are the service specs routed at `/api-docs/{service}` instead of `/v3/api-docs/{service}` — and what
decides who wins when a gateway route and a local handler match the same path?**

- ***Local handlers win.*** The gateway's `RoutePredicateHandlerMapping` runs at order `1`
  (`spring.cloud.gateway.server.webflux.handler-mapping.order`, default 1, as of 2026), after WebFlux's annotated
  controller mapping (order 0). springdoc serves `/v3/api-docs/{group}` from a controller, so a gateway route on that
  path is never reached — springdoc would answer with an (empty or unknown) group named `user-service`. Moving the
  routes to `/api-docs/**` sidesteps the collision; `SetPath=/v3/api-docs` rewrites them for the backends.
- ***Among routes***, the first matching route wins, ordered by each route's `order` (default 0) and then
  declaration order. Overlaps bite when someone later adds `/api/users/admin/**` below `/api/users/**`: it never
  matches unless given a lower `order`.
- ***Security sees the path before routing*** — `SecurityWebFilterChain` runs as a `WebFilter` ahead of any handler
  mapping, so `public-paths` must list the ***external*** path (`/api-docs/**`), not the rewritten one. Every new
  public route is a security decision: `/api-docs/**` here exposes the downstream specs without credentials (they are
  public on the services too).
- ***How to check*** — `GET /actuator/mappings` lists local handlers with their order; `GET /actuator/gateway/routes`
  lists routes in match order. A collision shows up as a path present in both.
- ***Review rule*** — never route a path prefix that a local starter owns (`/actuator/**`, `/v3/api-docs/**`,
  `/swagger-ui/**`, `/webjars/**`); add an integration test per route that asserts the response came from the
  backend (e.g. a backend-only header or body field).

<a id="qa-blocking-in-filter"></a>

**Q3. 🔥 A teammate adds a `GlobalFilter` that checks an API key with a blocking JDBC lookup. What happens under load,
and how do you catch and fix it?**

- ***The whole gateway stalls, not just that route.*** Reactor Netty runs every request on a small set of event-loop
  threads (about one per CPU core). A 20 ms blocking query on an event loop means that loop serves nothing else for
  20 ms — at 8 cores the gateway tops out near 8 / 0.02 = ***400 req/s for every route***, health checks included.
- ***Symptoms*** — latency climbs on all routes at once while CPU stays low; thread dumps show
  `reactor-http-nio-*` threads in socket reads or JDBC calls; liveness probes time out and the instance gets
  restarted.
- ***Same trap elsewhere in this module*** — the `auditing` authentication manager is non-blocking only because
  `ReactiveUserDetailsService` is in-memory here. Swap it for a JDBC-backed user store and every Basic login blocks an
  event loop.
- ***Catch it***:
  - BlockHound in tests — fails a test when a blocking call happens on a non-blocking thread.
  - Code review rule: no `JdbcTemplate`, `RestTemplate`, `RestClient`, `.block()` or `Thread.sleep` in gateway
    filters.
  - Metrics: Reactor Netty event-loop pending tasks, and p99 across all routes moving together.
- ***Fix, best first***:
  1. A reactive client (R2DBC, `WebClient`, reactive Redis) for the lookup.
  2. Cache the key → principal mapping (Caffeine `AsyncCache`) so the lookup is rare.
  3. Last resort: wrap the call in `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` — moves the
     block off the event loop, but bounded elastic has a cap (10 × cores by default) and queues behind it.
- ***Trade-off*** — `boundedElastic` is a pressure valve, not a design: under real load it just moves the queue.

<a id="qa-edge-rate-limit-breaker"></a>

**Q4. 🔥🔥 Traffic grows 10× and you run 3 gateway instances. Design per-client rate limiting, timeouts and circuit
breaking at this edge — given that every client here logs in as the same `admin` user.**

***Distributed token-bucket limiting keyed by a real client identity, a per-route timeout budget, and a circuit
breaker with a fast fallback — and first fix identity, because a key resolver on the principal puts every client in
one bucket here.***

- ***Rate limiting***
  - Spring Cloud Gateway's `RequestRateLimiter` filter with `RedisRateLimiter` (token bucket in Redis, needs the
    reactive Redis starter): `replenishRate`, `burstCapacity`, `requestedTokens`; responds `429` with
    `X-RateLimit-*` headers.
  - ***Key choice*** — the default `PrincipalNameKeyResolver` keys on the authenticated name: with one shared
    `admin` user, one noisy client exhausts the budget for everyone. Use a per-client identity (API key, OAuth2
    `client_id`, JWT `sub`) — not `X-Forwarded-For`, which clients can spoof.
  - ***Local vs distributed*** — a per-instance in-memory limiter allows 3× the intended rate across 3 instances and
    shifts as the LB rebalances; Redis gives one global bucket at the cost of a network hop per request and a new
    dependency. Decide what happens when Redis is down (fail open for availability, fail closed for abuse-sensitive
    routes).
- ***Timeouts*** — set `spring.cloud.gateway.server.webflux.httpclient.connect-timeout` / `response-timeout`
  globally (unset today), override per route with route metadata (`response-timeout`, `connect-timeout`) — e.g.
  orders with a downstream call to `user-service` get more than plain lookups. Edge timeout > downstream timeout (see
  root [FAQ 7.11](../README.md#faq-slow-downstream-cascade)).
- ***Circuit breaker*** — `CircuitBreaker` filter (Spring Cloud CircuitBreaker + Resilience4j) per route with a
  `fallbackUri: forward:/fallback/users` returning a fast `503` + `Retry-After`, so a dying backend costs microseconds
  instead of a timeout each.
- ***Retry*** — `Retry` filter only for `GET`, only on connect errors/`502`/`503`, at most 1 retry with backoff,
  ***inside*** the breaker so failed retries count towards opening it.

```yaml
- id: user-service
  uri: lb://USER-SERVICE
  predicates:
    - Path=/api/users/**
  metadata:
    response-timeout: 2000
  filters:
    - name: RequestRateLimiter
      args:
        redis-rate-limiter.replenishRate: 50
        redis-rate-limiter.burstCapacity: 100
        key-resolver: "#{@clientIdKeyResolver}"
    - name: CircuitBreaker
      args:
        fallbackUri: forward:/fallback/users
    - name: Retry
      args:
        retries: 1            # methods defaults to GET
        statuses: BAD_GATEWAY,SERVICE_UNAVAILABLE
```

- ***Capacity check*** — Netty handles 10× connections cheaply; the real limits are downstream (Tomcat threads in
  the backends) and Redis round-trips. Load-test with [../load-tests](../load-tests/README.md) and watch where p99
  breaks first.
- ***Monitor*** — `429` rate per client, breaker state transitions, gateway `5xx`/`504` per route, Redis latency.
- ***Trade-off to state*** — the limiter protects backends from clients; the breaker protects the gateway and
  clients from backends. You need both, and neither replaces capacity planning.

<a id="qa-canary-release"></a>

**Q5. 🔥🔥 Canary `user-service` v2 at 5 % of traffic through this gateway. How, given Eureka discovery and
`order-service` calling `user-service` directly?**

***Do the split where all callers meet — the load-balancer layer, using instance metadata — not only in gateway
routes; a gateway-only canary leaks, because `order-service` reaches `user-service` without the gateway.***

- ***Option A: gateway `Weight` predicate*** — register v2 under a separate service ID (`USER-SERVICE-V2`), two
  routes in one weight group (`Weight=users, 95` / `Weight=users, 5`). Simple and visible in
  `/actuator/gateway/routes`, but `order-service`'s `UserClient` (`http://USER-SERVICE`) never sees v2, so the
  canary only covers external traffic.
- ***Option B: metadata-aware load balancing*** — keep one service ID; v2 instances register with
  `eureka.instance.metadata-map.version: v2`. A custom `ServiceInstanceListSupplier` (or `ReactorLoadBalancer`) in
  ***both*** the gateway and `order-service` picks v2 for a hash-selected 5 % of users. All callers see the same split.
- ***Option C: header-based routing for testers first*** — route `X-Canary: true` to v2 (gateway predicate +
  propagate the header to `UserClient`), then switch to percentage.

| Concern              | A. Weight predicate         | B. Metadata-aware LB                       |
|----------------------|-----------------------------|--------------------------------------------|
| Covers service calls | no — gateway traffic only   | yes — every `@LoadBalanced` caller         |
| Stickiness           | random per request          | hash on user ID → same user, same version  |
| Change the split     | edit routes + `refresh`     | config property read by the supplier       |
| Effort               | config only                 | code in each calling module                |

- ***Stickiness matters*** — random per-request splits make one user flip between v1 and v2 within a page; hash on a
  stable key (user ID, `client_id`) so a user's experience and error reports are consistent.
- ***Compatibility*** — v1 and v2 serve simultaneously: response shape changes must be additive (old
  `UserSummary` readers in `order-service` must tolerate new fields); DB migrations expand-then-contract.
- ***Rollback*** — split to 0 % (or deregister v2 instances: `OUT_OF_SERVICE`, see root
  [FAQ 7.13](../README.md#faq-zero-error-rolling-deploy)); cache layers mean rollback takes up to a couple of
  minutes to fully apply.
- ***Decide with data*** — tag spans and metrics with the instance version (a `version` resource attribute or tag),
  compare v1 vs v2 error rate and p99 in Zipkin and metrics; automate promote/rollback on thresholds.

<a id="qa-loadbalancer-drawbacks"></a>

**Q6. 🔥 What are the drawbacks of Spring Cloud LoadBalancer, which picks the instance for every `lb://` route here?**

- ***Where it comes from here*** — `gateway-service/pom.xml` doesn't declare it: `spring-cloud-starter-loadbalancer`
  arrives transitively (5.0.3 as of 2026). For each `lb://USER-SERVICE` request the gateway's
  `ReactiveLoadBalancerClientFilter` asks a `RoundRobinLoadBalancer` for an instance from Eureka's cached list
  (root [FAQ: load balancing](../README.md#faq-load-balancing), [why LoadBalancer](../README.md#faq-why-loadbalancer)).
  There's no `spring.cloud.loadbalancer.*` config in this module, so everything below runs on defaults.
- ***Drawbacks***

| # | Drawback | What it means in this module |
|---|---|---|
| 1 | ***Blind algorithms*** — only round robin and random are built in | No least-connections or latency awareness: an instance stuck on slow Postgres queries still gets every Nth request |
| 2 | ***Stale instance list*** — three caches stack up | Eureka eviction (~60–90 s after a crash, [eureka Q2](../eureka-server/README.md#qa-crash-eviction-timeline)) + this gateway's registry fetch (default 30 s, not overridden here) + the balancer's own cache (`spring.cloud.loadbalancer.cache.ttl`, default 35 s) → a dead instance can keep getting traffic for a minute or more ([root FAQ: worst case](../README.md#faq-worst-case-detection)) |
| 3 | ***No outlier ejection*** | An instance that is `UP` in Eureka but returns 500s keeps its full share. Envoy-style meshes eject it automatically; here you need a circuit breaker plus retry ([Q4](#qa-edge-rate-limit-breaker)) |
| 4 | ***No retry by default*** | No `Retry` filter on these routes, so a request that lands on a dead instance fails to the client. `spring.cloud.loadbalancer.retry.*` applies to `@LoadBalanced` clients; the gateway's own `Retry` route filter is the gateway equivalent (idempotent methods only) |
| 5 | ***Default cache in production*** | Caffeine isn't on this module's classpath, so the balancer uses its simple built-in cache and logs a startup warning recommending Caffeine. Adding `com.github.ben-manes.caffeine:caffeine` switches it (`spring-context-support`, the other requirement, already comes in through Quartz) |
| 6 | ***No global view*** | Each of the 3 gateway instances ([Q4](#qa-edge-rate-limit-breaker)) keeps its own round-robin counter; they don't coordinate, and with few instances the spread can be uneven for short periods |
| 7 | ***Health checks multiply*** | The optional `health-check` filter makes ***every*** gateway instance probe ***every*** backend instance (`interval` default 25 s): callers × instances requests to `/actuator/health` |
| 8 | ***Java-only library*** | Every caller configures it separately (`order-service` has its own `@LoadBalanced` client, [root FAQ](../README.md#faq-order-calls-user)); a non-Java client can't use it. A mesh or server-side LB does it once for everyone |
| 9 | ***Long-lived connections*** | HTTP/2 or WebSocket connections are balanced once, at connect time — later requests on that connection don't rebalance |

- ***When it's still the right choice*** — no extra network hop or proxy to run, and it works anywhere Eureka works.
  On Kubernetes the cluster's `Service` already balances, so this layer usually adds little ([root FAQ: Kubernetes](../README.md#faq-migrate-to-kubernetes)).

<a id="qa-loadbalancer-redis"></a>

**Q7. Does Spring Cloud LoadBalancer need Redis?**

- ***No.*** It runs ***in memory inside each caller*** (here, inside every `gateway-service` instance):
  - the instance list comes from the local Eureka registry copy and is cached in the JVM (`cache.ttl` 35 s);
  - the round-robin position, sticky choices and retry state also live in the caller;
  - nothing is shared between callers, so there's nothing to put in Redis.
- ***Where Redis does appear in Spring Cloud*** — the gateway's `RequestRateLimiter` filter with `RedisRateLimiter`,
  which needs a shared counter across gateway instances ([Q4](#qa-edge-rate-limit-breaker)), and Spring Session.
  Neither is load balancing, and this module uses neither (no Redis dependency in `pom.xml`).
- ***Sticky sessions don't need Redis either*** — `request-based-sticky-session` keeps the chosen instance in a cookie
  (`sc-lb-instance-id` by default) that the client sends back.

<a id="qa-loadbalancer-strategies"></a>

**Q8. Which load-balancing strategies does Spring Cloud LoadBalancer offer, and how would you switch the gateway to one?**

- ***Two layers*** — an ***algorithm*** picks one instance; ***instance-list filters*** first narrow down which
  instances the algorithm can pick from. Names below checked against `spring-cloud-loadbalancer` 5.0.3 (as of 2026).

| Layer | Strategy | What it does | How to enable |
|---|---|---|---|
| Algorithm | `RoundRobinLoadBalancer` ***(default, used here)*** | Cycles through instances, one counter per gateway instance | nothing |
| Algorithm | `RandomLoadBalancer` | Random pick | `ReactorLoadBalancer` bean in a `@LoadBalancerClient` config |
| Algorithm | Custom | Your own `ReactorServiceInstanceLoadBalancer.choose()` — least-connections, latency-aware, hash on a user ID | same as above |
| Filter | `default` ***(used here)*** | Discovery client + cache | `spring.cloud.loadbalancer.configurations=default` |
| Filter | `zone-preference` | Prefer instances in the caller's zone (`spring.cloud.loadbalancer.zone`) | `configurations=zone-preference` |
| Filter | `health-check` | Caller probes each instance and drops failing ones (`health-check.interval` 25 s) | `configurations=health-check` |
| Filter | `same-instance-preference` | Stick to the previously chosen instance while it's available | `configurations=same-instance-preference` |
| Filter | `request-based-sticky-session` | Route by the `sc-lb-instance-id` cookie | `configurations=request-based-sticky-session` + `sticky-session.add-service-instance-cookie=true` |
| Filter | `weighted` | Share of traffic by a weight in instance metadata | `configurations=weighted` |
| Filter | `subset` | Each caller uses only a deterministic subset of instances (`subset.size`, default 100) — fewer connections in big fleets | `configurations=subset` |
| Filter | `api-version` | Pick instances that serve the requested API version (Spring Framework 7 API versioning) | `configurations=api-version` |
| Filter | Hint-based | Route by a hint header (`X-SC-LB-Hint`) matched to a `hint` in instance metadata | custom supplier built with `ServiceInstanceListSupplier.builder()...withHints()` |

- ***Switch the whole gateway*** — one property in `application.yml`:

```yaml
spring:
  cloud:
    loadbalancer:
      configurations: health-check   # or zone-preference, weighted, subset, ...
```

- ***Switch one service only*** — a `@LoadBalancerClient(name = "USER-SERVICE", configuration = UserLbConfig.class)`
  whose config class is ***not*** component-scanned (otherwise it applies to every service); each service ID gets its own
  child context on first use. Full mechanics and a custom-algorithm example:
  [learning-architecture-design-pattern-microservice.md §18](https://github.com/himnay/learning/blob/main/learning-architecture-design-pattern-microservice.md#18-how-does-client-side-load-balancing-work-spring-cloud-loadbalancer).
- ***Pick by problem*** — dead instances lingering → `health-check` + `Retry` filter; multi-zone → `zone-preference`;
  mixed instance sizes or canary → `weighted` ([Q5](#qa-canary-release)); hundreds of instances → `subset`;
  slow-instance avoidance → custom algorithm or a service mesh.
