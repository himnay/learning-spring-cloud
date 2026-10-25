# <span style="color:hsl(28,80%,58%)">product-service</span>

## <span style="color:hsl(151,80%,58%)">Table of contents</span>

1. 🧭 [Overview](#overview)
2. 🚀 [Run and credentials](#run-and-credentials)
3. 📇 [Eureka registration](#eureka-registration)
4. 🔐 [Security in detail](#security-in-detail)
5. 📊 [Actuator endpoints and SBA views](#actuator-endpoints)
6. ⏰ [Quartz job, file logging and application.yml](#quartz-logging-config)
7. 🧪 [Tests](#tests)
8. ⚠️ [Gotchas](#gotchas)
9. ❓ [Q&A](#qa)
    - 9.1 [What are the different ways Spring Boot Admin can call `product-service`, and what does `product-service` need for each?](#qa-sba-call-options)

Part of the [learning-spring-cloud](../README.md) demo: a small Spring MVC
service that serves a fixed product catalogue, registers with Eureka, sits behind
`gateway-service`, and exposes enough actuator endpoints to light up almost
every Spring Boot Admin (SBA) view. Shared setup that applies to every module (parent/BOM chain, run order, the full SBA
view matrix) lives in the
[root README](../README.md); this file covers only what `product-service` itself does.

<a id="overview"></a>

## <span style="color:hsl(201,80%,58%)">1. 🧭 Overview</span>

- ***Role*** — Eureka client and REST microservice; registered as `PRODUCT-SERVICE`, reached directly on `:8083` or
  through the gateway route `/api/products/**` → `lb://PRODUCT-SERVICE`
  (see [root §2](../README.md#how-the-gateway-finds-services-via-eureka)).
- ***Port*** — `8083` (`server.port` in [application.yml](src/main/resources/application.yml)).
- ***Data source*** — ***no database***: three products are hard-coded in an immutable `List.of(...)`
  inside [ProductController.java](src/main/java/com/learning/discovery/product/controller/ProductController.java).
  Nothing is ever written.
- ***Model*** — a Java `record` [Product.java](src/main/java/com/learning/discovery/product/model/Product.java):
  `Product(Long id, String name, BigDecimal price)`.

**Stack (as of 2026)** — versions come from the parent chain in [../pom.xml](../pom.xml); the banner prints Spring Cloud
`2025.1.2`, and the root README links Spring Boot `4.1.x` sources.

| Dependency ([pom.xml](pom.xml))                                               | Why it is here                                                                                                               |
|-------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| `spring-boot-starter-webmvc`                                                  | Servlet (Tomcat) REST controller                                                                                             |
| `spring-boot-starter-actuator`                                                | `/actuator/**` endpoints consumed by SBA                                                                                     |
| `spring-boot-starter-opentelemetry`                                           | Traces over OTLP to [../zipkin-server](../zipkin-server/README.md)                                                           |
| `spring-cloud-starter-netflix-eureka-client`                                  | Registration with `eureka-server`; also brings Spring Cloud Context (`restart`, `refresh`, `env` POST)                       |
| `spring-cloud-starter-loadbalancer`                                           | On the classpath for consistency with the other clients; ***product-service makes no outbound calls***, so it is unused here |
| `spring-boot-starter-security`                                                | HTTP Basic, one in-memory user                                                                                               |
| `spring-boot-starter-quartz`                                                  | Demo `HeartbeatJob` → `/actuator/quartz`                                                                                     |
| `org.jolokia:jolokia-support-springboot` **2.6.3**                            | `/actuator/jolokia` → SBA JMX view (version from `learning-bom` 3.1.0, no `<version>` in the pom)                            |
| `springdoc-openapi-starter-webmvc-ui`                                         | Swagger UI + `/v3/api-docs`                                                                                                  |
| `lombok` (optional)                                                           | `@Slf4j` on the Quartz job                                                                                                   |
| `spring-boot-starter-webmvc-test`, `spring-boot-starter-security-test` (test) | `@WebMvcTest`, `MockMvc`, `RestTestClient`, `@WithMockUser`                                                                  |

**REST API** (all `GET`, all require Basic auth):

| Method + path            | Returns                      | Errors                                                                                      |
|--------------------------|------------------------------|---------------------------------------------------------------------------------------------|
| `GET /api/products`      | JSON array of all 3 products | `401` without credentials                                                                   |
| `GET /api/products/{id}` | single product               | `404` via `ResponseStatusException` (`"Product {id} not found"`), `401` without credentials |

Seed data:

| id  | name     | price  |
|-----|----------|--------|
| 201 | Keyboard | 49.99  |
| 202 | Monitor  | 199.99 |
| 203 | Mouse    | 19.99  |

- ***Swagger UI*** — http://localhost:8083/swagger-ui.html (spec at `/v3/api-docs`); both are public, but "Try it out"
  calls to `/api/**` still need credentials.
- ***Banner*** — [banner.txt](src/main/resources/banner.txt) prints project metadata, Boot/Java versions and the Swagger
  URL at startup.

<a id="run-and-credentials"></a>

## <span style="color:hsl(256,80%,58%)">2. 🚀 Run and credentials</span>

- ***Start `eureka-server` first*** (see [root run order](../README.md#run-order)), then:

```bash
cd learning-spring-cloud
mvn -pl product-service spring-boot:run
```

- ***Credentials*** — one user, `admin`/`admin`, role `ACTUATOR`, from `spring.security.user`. Override both with
  environment variables:

```bash
ACTUATOR_USER=ops ACTUATOR_PASSWORD=s3cret mvn -pl product-service spring-boot:run
```

- The same values are reused for the Eureka `defaultZone` URL and the SBA metadata, so ***eureka-server, admin-server
  and this service must agree*** on them.

**Direct calls:**

```bash
curl -u admin:admin http://localhost:8083/api/products
curl -u admin:admin http://localhost:8083/api/products/202
curl -i -u admin:admin http://localhost:8083/api/products/999   # 404
curl -i http://localhost:8083/api/products                       # 401 + WWW-Authenticate: Basic
curl http://localhost:8083/actuator/health                       # public
curl -u admin:admin http://localhost:8083/actuator/quartz        # secured
```

**Via the gateway** (`:8080`, which forwards the `Authorization` header downstream):

```bash
curl -u admin:admin http://localhost:8080/api/products
curl -u admin:admin http://localhost:8080/api/products/201
```

- ***Port 8083 clash*** — `8083` is also the default REST port of ***Kafka Connect***, and a Kafka Connect container has
  been seen holding it locally. Symptom: `Web server failed to start. Port 8083 was already in use.` Either stop the
  container or move this service:

```bash
docker ps --filter publish=8083            # find the culprit
docker stop <container>
# or run product-service elsewhere — Eureka/the gateway pick up the new port automatically
mvn -pl product-service spring-boot:run -Dspring-boot.run.arguments=--server.port=8093
SERVER_PORT=8093 mvn -pl product-service spring-boot:run
```

<a id="eureka-registration"></a>

## <span style="color:hsl(66,80%,50%)">3. 📇 Eureka registration</span>

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://${spring.security.user.name}:${spring.security.user.password}@localhost:8761/eureka/
  instance:
    prefer-ip-address: true
    metadata-map:
      user.name: ${spring.security.user.name}
      user.password: ${spring.security.user.password}
```

- ***Credentialed `defaultZone`*** — `eureka-server` is itself behind Basic auth, so the client embeds `user:password@`
  in the URL; the Eureka client turns the userinfo into a Basic `Authorization` header on register/renew/fetch calls.
- ***`prefer-ip-address: true`*** — the instance registers its IP instead of the machine hostname, so
  `lb://PRODUCT-SERVICE` in the gateway and SBA's polling resolve to an address that works even when the hostname does
  not resolve (containers, VPNs).
- ***`metadata-map` credentials for SBA*** — `admin-server` discovers this instance through Eureka and reads
  `user.name` / `user.password` from its metadata to call the secured `/actuator/**` endpoints. Without them SBA shows
  the instance but every view except health/info fails with `401`.
- ***Application name*** — `spring.application.name: product-service` becomes the Eureka app ID `PRODUCT-SERVICE`.
- ***Tests switch Eureka off*** — [application-test.yml](src/test/resources/application-test.yml) sets
  `eureka.client.enabled: false`.

<a id="security-in-detail"></a>

## <span style="color:hsl(339,80%,58%)">4. 🔐 Security in detail</span>

All of it is one bean in [SecurityConfig.java](src/main/java/com/learning/discovery/product/config/SecurityConfig.java):

```java

@Bean
SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/actuator/health/**", "/actuator/info",
                            "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/error").permitAll()
                    .anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults())
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/actuator/**"))
            .build();
}
```

- ***HTTP Basic only*** — no form login, no sessions to speak of; an unauthenticated request gets `401` with
  `WWW-Authenticate: Basic`.
- ***User store*** — Boot's auto-configured `InMemoryUserDetailsManager` built from `spring.security.user` (`admin`/
  `admin`, role `ACTUATOR`). Any authenticated user may call anything; there is ***no role-based authorization***.

| Path                                                    | Access        | Why                                                |
|---------------------------------------------------------|---------------|----------------------------------------------------|
| `/actuator/health/**` (also matches `/actuator/health`) | public        | Liveness/readiness probes, Eureka/SBA status       |
| `/actuator/info`                                        | public        | SBA instance info                                  |
| `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**` | public        | Browse the API docs without logging in             |
| `/error`                                                | public        | So a `404`/`500` error body is not masked as `401` |
| everything else (`/api/**`, other `/actuator/**`)       | authenticated | Basic auth                                         |

- ***CSRF rules*** — CSRF stays on by default but is ***ignored for `/api/**` and `/actuator/**`***, so stateless
  Basic-auth clients (curl, SBA, the gateway) can send `POST` to `/actuator/shutdown`, `/actuator/restart`,
  `/actuator/refresh`, `/actuator/env`, `/actuator/loggers/*` and `/actuator/jolokia` without a token. Any other unsafe
  request would still need a CSRF token.
- ***Audit events*** —
  because [ActuatorConfig.java](src/main/java/com/learning/discovery/product/config/ActuatorConfig.java) declares an
  `InMemoryAuditEventRepository`, Boot registers its Spring Security audit listeners: every Basic login produces
  `AUTHENTICATION_SUCCESS` / `AUTHENTICATION_FAILURE`, and denied access produces `AUTHORIZATION_FAILURE`, all visible
  at `/actuator/auditevents` (SBA "Audit Events"). Expect lots of `admin` successes — SBA polls continuously.

```mermaid
sequenceDiagram
    autonumber
    participant Caller as Caller (curl / gateway)
    participant Sec as product-service<br/>SecurityFilterChain
    participant Ctl as ProductController
    participant Audit as InMemoryAuditEventRepository
    participant SBA as admin-server (SBA)
    participant Eureka as eureka-server
    participant Act as product-service<br/>/actuator/**

    Caller->>Sec: GET /api/products (no Authorization)
    Sec-->>Caller: 401 WWW-Authenticate: Basic
    Caller->>Sec: GET /api/products, Authorization: Basic admin:admin
    Sec->>Audit: AUTHENTICATION_SUCCESS
    Sec->>Ctl: authenticated request
    Ctl-->>Caller: 200 [3 products]

    SBA->>Eureka: fetch registry (PRODUCT-SERVICE + metadata user.name/user.password)
    SBA->>Act: GET /actuator/health (public, no auth needed)
    Act-->>SBA: 200 {"status":"UP", details}
    SBA->>Act: GET /actuator/metrics, Basic from metadata
    Act-->>SBA: 200 metrics
    SBA->>Act: POST /actuator/loggers/{name} (CSRF ignored for /actuator/**)
    Act-->>SBA: 204
```

<a id="actuator-endpoints"></a>

## <span style="color:hsl(116,80%,58%)">5. 📊 Actuator endpoints and SBA views</span>

All 20 endpoints below are listed in `management.endpoints.web.exposure.include`. Only `health` and `info` are public;
the rest need Basic auth. The cross-module matrix is in [root §10](../README.md#security-and-sba-features).

| Endpoint        | SBA view                     | Backing config in this module                                                                                                                                                                                 |
|-----------------|------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `health`        | Details → Health             | `management.endpoint.health.show-details: always`                                                                                                                                                             |
| `info`          | Details → Info               | default (no `info.*` properties set)                                                                                                                                                                          |
| `heapdump`      | Heap Dump                    | `management.endpoint.heapdump.access: unrestricted` (Boot 3.5+ defaults it to `none`, as of 2026)                                                                                                             |
| `logfile`       | Logfile                      | `logging.file.name: logs/${spring.application.name}.log` — no file, no endpoint                                                                                                                               |
| `env`           | Environment                  | exposure only; `management.endpoint.env.post.enabled: true` enables the SBA "Update" form (`POST /actuator/env`, Spring Cloud Context)                                                                        |
| `shutdown`      | Shutdown button              | `management.endpoint.shutdown.access: unrestricted`                                                                                                                                                           |
| `restart`       | Restart button               | `management.endpoint.restart.access: unrestricted` (Spring Cloud Context `RestartEndpoint`)                                                                                                                   |
| `refresh`       | Environment → Refresh        | `management.endpoint.refresh.access: unrestricted`; re-binds `@ConfigurationProperties` / `@RefreshScope` beans                                                                                               |
| `metrics`       | Details / Metrics            | Micrometer, auto-configured                                                                                                                                                                                   |
| `loggers`       | Loggers (change levels live) | auto-configured                                                                                                                                                                                               |
| `threaddump`    | Threads                      | auto-configured                                                                                                                                                                                               |
| `mappings`      | Mappings                     | auto-configured                                                                                                                                                                                               |
| `beans`         | Beans                        | auto-configured                                                                                                                                                                                               |
| `configprops`   | Configuration Properties     | auto-configured                                                                                                                                                                                               |
| `httpexchanges` | HTTP Exchanges               | `InMemoryHttpExchangeRepository` bean in [ActuatorConfig.java](src/main/java/com/learning/discovery/product/config/ActuatorConfig.java) (keeps the last 100)                                                  |
| `startup`       | Startup                      | `new BufferingApplicationStartup(2048)` set in `main()` of [ProductServiceApplication.java](src/main/java/com/learning/discovery/product/ProductServiceApplication.java) — ***only when started via `main`*** |
| `auditevents`   | Audit Events                 | `InMemoryAuditEventRepository` bean in [ActuatorConfig.java](src/main/java/com/learning/discovery/product/config/ActuatorConfig.java) + Spring Security login events                                          |
| `sbom`          | SBOM                         | root [../pom.xml](../pom.xml) `cyclonedx-maven-plugin` writes `META-INF/sbom/application.cdx.json` during the build                                                                                           |
| `quartz`        | Quartz                       | `spring-boot-starter-quartz` + `HeartbeatJob` (§6)                                                                                                                                                            |
| `jolokia`       | JMX                          | `jolokia-support-springboot` 2.6.3 (servlet-only, which is why the WebFlux gateway/admin modules lack it)                                                                                                     |

```bash
curl -u admin:admin http://localhost:8083/actuator/startup | jq '.timeline.events | length'
curl -u admin:admin http://localhost:8083/actuator/quartz/jobs/demo/heartbeatJob
curl -u admin:admin -o heap.hprof http://localhost:8083/actuator/heapdump
curl -u admin:admin -X POST -H 'Content-Type: application/json' \
     -d '{"configuredLevel":"DEBUG"}' http://localhost:8083/actuator/loggers/com.learning
curl -u admin:admin -X POST http://localhost:8083/actuator/shutdown   # stops the instance
```

### Health probes, info and tags

- ***Probes*** — `management.endpoint.health.probes.enabled: true` adds public `/actuator/health/liveness` and
  `/actuator/health/readiness` (SBA Details → Health groups).
- ***Info*** — `info.app.*` (name, description, `tier: backend`, environment) plus the `java`, `os`, `process`
  contributors (`management.info.*.enabled`) → SBA Details → Info.
- ***Tags*** — `eureka.instance.metadata-map.tags.environment` (`APP_ENV`, default `local`) and `tags.tier: backend` →
  badges in SBA's application list.
- Custom health indicators and metrics live in [order-service](../order-service/README.md) only.

<a id="quartz-logging-config"></a>

## <span style="color:hsl(306,80%,58%)">6. ⏰ Quartz job, file logging and application.yml</span>

**Quartz demo job**

- ***[HeartbeatJob.java](src/main/java/com/learning/discovery/product/job/HeartbeatJob.java)*** — a `QuartzJobBean` that
  only logs `Heartbeat from product-service (fire time …)`.
- ***[QuartzConfig.java](src/main/java/com/learning/discovery/product/job/QuartzConfig.java)*** — `JobDetail`
  `demo.heartbeatJob` (`storeDurably()`) and `Trigger` `demo.heartbeatTrigger` with
  `SimpleScheduleBuilder.repeatMinutelyForever()`; Boot's `SchedulerFactoryBean` auto-registers both beans.
- ***In-memory job store*** — `spring.quartz.job-store-type: memory` (`RAMJobStore`): no tables, schedules vanish on
  restart, non-clustered.
- ***Purpose*** — gives SBA's Quartz view and `/actuator/quartz` something to show (jobs, triggers, next fire time); you
  can pause/resume it there.

**File logging**

- ***`logging.file.name: logs/product-service.log`*** — relative to the working directory, so
  `mvn -pl product-service spring-boot:run` writes `product-service/logs/product-service.log`. Its only purpose is to
  enable `/actuator/logfile` (SBA "Logfile"); console logging is unchanged.

**Annotated [application.yml](src/main/resources/application.yml)**

```yaml
spring:
  application:
    name: product-service                       # Eureka app ID PRODUCT-SERVICE, log file name
  security:
    user:
      name: ${ACTUATOR_USER:admin}              # single Basic-auth user
      password: ${ACTUATOR_PASSWORD:admin}
      roles: ACTUATOR
  quartz:
    job-store-type: memory                      # RAMJobStore, no DB

server:
  port: 8083                                    # clashes with Kafka Connect's default

eureka:
  client:
    service-url:
      defaultZone: http://${spring.security.user.name}:${spring.security.user.password}@localhost:8761/eureka/
  instance:
    prefer-ip-address: true                     # register IP, not hostname
    metadata-map:
      user.name: ${spring.security.user.name}   # SBA uses these to call the secured actuator
      user.password: ${spring.security.user.password}

logging:
  file:
    name: logs/${spring.application.name}.log   # enables /actuator/logfile

management:
  endpoints:
    web:
      exposure:
        include: [health, info, env, metrics, loggers, threaddump, heapdump, mappings,
                  beans, configprops, logfile, shutdown, restart, refresh, httpexchanges,
                  startup, auditevents, sbom, quartz, jolokia]   # a list in the real file
  endpoint:
    shutdown: { access: unrestricted }          # SBA Shutdown button
    restart:  { access: unrestricted }          # SBA Restart button
    refresh:  { access: unrestricted }          # SBA Environment → Refresh
    env:
      post: { enabled: true }                   # SBA Environment "Update" form
    health:
      show-details: always                      # details visible even to anonymous callers
    heapdump: { access: unrestricted }          # Boot 3.5+ default is none
```

<a id="tests"></a>

## <span style="color:hsl(184,80%,58%)">7. 🧪 Tests</span>

Both classes activate the `test` profile, so [application-test.yml](src/test/resources/application-test.yml) turns the
Eureka client off. Run with `mvn -pl product-service verify` (surefire runs `*Test`, failsafe runs `*IT`;
see [root §6](../README.md#tests)).

**[ProductControllerTest.java](src/test/java/com/learning/discovery/product/controller/ProductControllerTest.java)** —
`@WebMvcTest(ProductController.class)` + `@Import(SecurityConfig.class)` + class-level `@WithMockUser`, using `MockMvc`
(web slice only, no server).

| Test                                      | Checks                                                                     |
|-------------------------------------------|----------------------------------------------------------------------------|
| `getProductsReturnsSeedList`              | `GET /api/products` → `200`, 3 items, first is `Keyboard`                  |
| `getProductByIdReturnsMatch`              | `GET /api/products/202` → `200`, name `Monitor`, price `199.99`            |
| `getProductsWithoutCredentialsReturns401` | `@WithAnonymousUser` → `401` (proves the real `SecurityConfig` is applied) |
| `getProductByUnknownIdReturns404`         | `GET /api/products/999` → `404`                                            |

**[ProductServiceApplicationIT.java](src/test/java/com/learning/discovery/product/ProductServiceApplicationIT.java)** —
`@SpringBootTest(webEnvironment = RANDOM_PORT)`, real embedded Tomcat, `RestTestClient` bound to
`http://localhost:<port>`.

| Test                                        | Checks                                                                                                |
|---------------------------------------------|-------------------------------------------------------------------------------------------------------|
| `apiWithoutCredentialsReturns401`           | `GET /api/products` without auth → `401`                                                              |
| `fullStackReturnsProductsOverHttp`          | Basic `admin`/`admin` → `2xx`, 3 items                                                                |
| `healthEndpointIsPublicAndReportsUp`        | `GET /actuator/health` without auth → `2xx`, `status = UP`                                            |
| `secureActuatorEndpointsRequireCredentials` | `GET /actuator/quartz` → `401` without auth, `200` with it (also proves the Quartz endpoint is wired) |

<a id="gotchas"></a>

## <span style="color:hsl(45,80%,58%)">8. ⚠️ Gotchas</span>

- ***Port 8083 vs Kafka Connect*** — see [§2](#run-and-credentials); stop the container or override `server.port`.
- ***`/actuator/startup` only exists when launched via `main()`*** — `@SpringBootTest` does not call `main`, so there is
  no `BufferingApplicationStartup` and the endpoint is absent in tests. The buffer holds 2048 steps; later steps are
  dropped.
- ***Package-private `static void main`*** — `ProductServiceApplication` uses the Java 25 (JEP 512) instance-main launch
  protocol; on older JDKs the class has no runnable entry point.
- ***Credentials must match across modules*** — changing `ACTUATOR_USER`/`ACTUATOR_PASSWORD` here only works if
  `eureka-server` accepts them (otherwise registration fails with `401`) and SBA reads them from metadata. The IT
  hard-codes `admin`/`admin`, so exporting different values in your shell breaks it.
- ***Mutating endpoints are wide open to any authenticated user*** — `shutdown`, `restart`, `env` POST, `refresh`,
  `loggers` POST and `jolokia` can stop or reconfigure the JVM; CSRF is disabled for them. Acceptable for a local demo,
  never on a public port.
- ***`show-details: always` on a public endpoint*** — anonymous callers see health component details (disk space, ping,
  etc.).
- ***Heap dumps are large and contain secrets*** — including the plaintext Basic password held in memory.
- ***`/actuator/sbom` needs a Maven build*** — the CycloneDX file is generated at build time; running from an IDE
  without a Maven build leaves the endpoint empty/404.
- ***Quartz state is in-memory*** — pausing the job in SBA does not survive a restart.
- ***`logs/` is relative to the working directory*** — launched from elsewhere (e.g. an IDE with the repo root as CWD),
  the log lands in a different `logs/` folder.
- ***`spring-cloud-starter-loadbalancer` is unused here*** — the module never calls another service; only
  `order-service` does.
- ***Shutdown deregisters cleanly, a kill does not*** — `POST /actuator/shutdown` closes the context and the Eureka
  client cancels its lease; a `kill -9` leaves `PRODUCT-SERVICE` in the registry until the lease expires (~90s by
  default), and the gateway may route to a dead instance meanwhile.

<a id="qa"></a>

## <span style="color:hsl(0,80%,58%)">9. ❓ Q&A</span>

<a id="qa-sba-call-options"></a>

**Q1. What are the different ways Spring Boot Admin can call `product-service`, and what does `product-service` need for each?**

- ***SBA always pulls.*** admin-server polls `/actuator/health` (every 10 s), reads `/actuator/info`, and proxies every
  UI view through itself (`/instances/{id}/actuator/**`), so the browser never calls `product-service` directly.
- ***Two choices vary:*** how SBA *learns* the actuator URL (A) and how it *authenticates* the call (B).

***A. How SBA finds `product-service`***

| # | Way                                       | Who knows the URL                     | Used here |
|---|-------------------------------------------|---------------------------------------|-----------|
| 1 | Eureka discovery                          | Eureka registry, read by admin-server | **yes**   |
| 2 | SBA client (push)                         | `product-service` registers itself with SBA   | no        |
| 3 | Static list (`SimpleDiscoveryClient`)     | admin-server's `application.yml`      | no        |
| 4 | Kubernetes discovery                      | Kubernetes API (Services/Endpoints)   | no        |

**A1. Eureka discovery (current setup).** No SBA dependency in `product-service`; SBA turns each Eureka instance into an SBA
instance.

```yaml
# product-service/src/main/resources/application.yml
eureka:
  client:
    service-url:
      defaultZone: http://admin:admin@localhost:8761/eureka/,http://admin:admin@127.0.0.1:8762/eureka/,http://admin:admin@127.0.0.2:8763/eureka/
  instance:
    prefer-ip-address: true            # SBA calls the address registered here, it must be reachable
    metadata-map:
      user.name: ${spring.security.user.name}         # Basic credentials SBA sends (see B1)
      user.password: ${spring.security.user.password}
      tags.tier: backend                              # badge in SBA
management:
  endpoints:
    web:
      exposure:
        include: health, info, env, metrics, loggers  # ... whatever SBA views you want
```

Extra metadata SBA reads when actuator isn't on the defaults:

| Metadata key              | Set it when                                                                                           |
|---------------------------|-------------------------------------------------------------------------------------------------------|
| `management.port`         | actuator runs on its own `management.server.port` (Spring Cloud Netflix adds this one automatically)  |
| `management.context-path` | actuator isn't under `/actuator` (`management.endpoints.web.base-path`)                               |
| `management.address`      | actuator listens on a different host/IP than the app                                                  |
| `management.scheme`       | actuator uses another scheme than the app (e.g. `https`)                                              |
| `health.path`             | health isn't at `<context-path>/health`                                                               |

**A2. SBA client (push).** `product-service` POSTs its own URLs to admin-server at startup and DELETEs on shutdown. Useful
without a registry.

```xml
<dependency>
    <groupId>de.codecentric</groupId>
    <artifactId>spring-boot-admin-starter-client</artifactId>
</dependency>
```

```yaml
spring:
  boot:
    admin:
      client:
        url: http://localhost:8084                # where admin-server is
        username: ${ACTUATOR_USER:admin}          # credentials FOR admin-server (POST /instances)
        password: ${ACTUATOR_PASSWORD:admin}
        instance:
          prefer-ip: true                         # register the IP instead of the hostname
          # service-base-url: http://product-service.example:8083   # override when the auto-detected URL is unreachable (NAT, containers)
          metadata:
            user.name: ${spring.security.user.name}       # credentials SBA sends BACK to product-service's actuator
            user.password: ${spring.security.user.password}
```

- admin-server must permit `POST /instances` with Basic and exclude it from CSRF.
- Don't combine with A1: the same app shows up twice (one entry per source).

**A3. Static list on admin-server.** `product-service` needs nothing SBA-specific, only exposed and secured actuator; its URL
and credentials live in admin-server's config:

```yaml
# admin-server/src/main/resources/application.yml
spring:
  cloud:
    discovery:
      client:
        simple:
          instances:
            product-service:
              - uri: http://localhost:8083
                metadata:
                  user.name: admin
                  user.password: admin
```

- No health-based registration: a stopped instance stays listed as `OFFLINE` until you edit the list.

**A4. Kubernetes.** admin-server uses `spring-cloud-starter-kubernetes-client` instead of Eureka. `product-service` needs a
Kubernetes `Service`; name a separate actuator port `management` in the Service's ports, and put `user.name` /
`tags.*` style keys in the Service's labels/annotations (Spring Cloud Kubernetes copies them into the instance metadata).

***B. How SBA authenticates to `product-service`'s actuator***

| #  | Method                     | `product-service` side                                                                      | admin-server side                                                                                                                                   |
|----|----------------------------|-------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| B1 | Basic, per-instance        | `httpBasic()` on actuator + metadata `user.name` / `user.password` (**used here**)  | `spring.boot.admin.instance-auth.enabled: true` (default)                                                                                           |
| B2 | Basic, per-service         | `httpBasic()` only, no credentials in metadata                                      | `spring.boot.admin.instance-auth.service-map.<registered-name>.user-name` / `.user-password`                                                        |
| B3 | Basic, one shared login    | `httpBasic()` only                                                                  | `spring.boot.admin.instance-auth.default-user-name` / `.default-password`                                                                           |
| B4 | OAuth2 bearer token        | `spring-boot-starter-oauth2-resource-server`, actuator requires e.g. `SCOPE_actuator` | `client_credentials` client + `InstanceExchangeFilterFunction`, see [admin-server Q7](../admin-server/README.md#qa-basic-ui-oauth-backend)           |
| B5 | Custom header / API key    | a filter that checks e.g. `X-Api-Key` on `/actuator/**`                             | a `ReactiveHttpHeadersProvider` (or `HttpHeadersProvider`) bean returning that header                                                               |
| B6 | mutual TLS                 | `server.ssl.client-auth: need` and register https (`eureka.instance.secure-port-enabled: true`) | admin-server's instance `WebClient` configured with a client certificate                                                         |
| B7 | network only (no auth)     | `permitAll()` on `/actuator/**`, ideally on an internal-only `management.server.port` | nothing                                                                                                                                      |

- ***B1–B3 are tried in that order*** — SBA's `BasicAuthHttpHeaderProvider` uses metadata first, then `service-map`,
  then the default credentials. Metadata also accepts `user-name` / `user-password` or `username` / `userpassword`.
- ***Credentials in metadata are visible*** to anyone who can read the Eureka registry (`/eureka/apps`); B2/B3 keep
  them only on admin-server, B4 removes shared passwords entirely.
- ***POSTs need CSRF off for actuator*** — SBA's Environment update, Refresh, Restart and Shutdown send no CSRF token,
  hence `csrf.ignoringRequestMatchers("/actuator/**")` in this module's `SecurityConfig`.
- ***Public paths*** — `/actuator/health/**` and `/actuator/info` are `permitAll()` here, so status still shows even
  with wrong credentials; the other views then fail with `401`.
