# <span style="color:hsl(201,80%,58%)">user-service</span>

## <span style="color:hsl(151,80%,58%)">Table of contents</span>

1. 🧩 [Overview](#overview)
2. 🚀 [Run and credentials](#run-and-credentials)
3. 🔎 [Eureka registration](#eureka-registration)
4. 🔐 [Security](#security)
5. 📊 [Actuator endpoints and Spring Boot Admin views](#actuator-endpoints)
6. ⏱️ [Quartz demo job, file logging, application.yml](#quartz-logging-config)
7. 🧪 [Tests](#tests)
8. ⚠️ [Gotchas](#gotchas)
9. ❓ [Q&A](#qa)
    - 9.1 [What are the different ways Spring Boot Admin can call `user-service`, and what does `user-service` need for each?](#qa-sba-call-options)

Eureka client exposing a read-only `/api/users` API. One of the 6 modules of the
service-discovery demo — see the root [../README.md](../README.md) for the
whole picture (Eureka, gateway, Spring Boot Admin, run order).

<a id="overview"></a>

## <span style="color:hsl(288,80%,58%)">1. 🧩 Overview</span>

- ***Role:*** a plain Spring MVC microservice that registers with Eureka as `USER-SERVICE` and serves users from its own
  Postgres (Flyway-seeded, see [docker-compose.yaml](docker-compose.yaml) on host port 5434).
- ***Port:*** `8081` (`server.port` in [application.yml](src/main/resources/application.yml)).
- ***Called by:*** `gateway-service` (route `/api/users/**` → `lb://USER-SERVICE`) and ***`order-service` directly*** —
  its `UserClient` uses base URL `http://USER-SERVICE` resolved through Eureka + Spring Cloud LoadBalancer, sending HTTP
  Basic credentials on every call (no gateway hop).

### Stack (as of 2026)

| Concern        | Dependency ([pom.xml](pom.xml))                                                                        |
|----------------|--------------------------------------------------------------------------------------------------------|
| Web            | `spring-boot-starter-webmvc` (Spring Boot 4.x, servlet stack)                                          |
| Discovery      | `spring-cloud-starter-netflix-eureka-client` (Spring Cloud 2025.1.2)                                   |
| Client-side LB | `spring-cloud-starter-loadbalancer`                                                                    |
| Ops            | `spring-boot-starter-actuator`, `jolokia-support-springboot` 2.6.3 (version from `learning-bom` 3.1.0) |
| Tracing        | `spring-boot-starter-opentelemetry` → OTLP to [../zipkin-server](../zipkin-server/README.md)           |
| Security       | `spring-boot-starter-security`                                                                         |
| Scheduling     | `spring-boot-starter-quartz` (in-memory job store)                                                     |
| API docs       | `springdoc-openapi-starter-webmvc-ui` → http://localhost:8081/swagger-ui.html                          |
| Boilerplate    | Lombok (`@Slf4j` in the Quartz job)                                                                    |
| Tests          | `spring-boot-starter-webmvc-test`, `spring-boot-starter-security-test`                                 |

### REST API

| Method | Path              | Response                                                                          | Source                                                                                          |
|--------|-------------------|-----------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------|
| `GET`  | `/api/users`      | JSON array of all 3 users                                                         | [UserController.java](src/main/java/com/learning/discovery/user/controller/UserController.java) |
| `GET`  | `/api/users/{id}` | single user, or ***`404`*** via `ResponseStatusException` ("User {id} not found") | same                                                                                            |

- ***Data source:*** an immutable in-memory `List.of(...)` constant — no database, no writes.
- Model is a Java record [User.java](src/main/java/com/learning/discovery/user/model/User.java):
  `User(Long id, String name, String email)`.

| id | name  | email             |
|----|-------|-------------------|
| 1  | Alice | alice@example.com |
| 2  | Bob   | bob@example.com   |
| 3  | Carol | carol@example.com |

- Both endpoints ***require HTTP Basic*** (see [§4](#security)).
- Entry point [UserServiceApplication.java](src/main/java/com/learning/discovery/user/UserServiceApplication.java) uses
  a package-private `static void main` (Java 25 instance-main style) and installs a `BufferingApplicationStartup`.
- Custom [banner.txt](src/main/resources/banner.txt) prints project metadata and the Swagger UI URL at startup.

<a id="run-and-credentials"></a>

## <span style="color:hsl(66,80%,50%)">2. 🚀 Run and credentials</span>

- ***Start `eureka-server` first*** (root [run order](../README.md#run-order)), then:

```bash
cd learning-spring-cloud
mvn -pl user-service spring-boot:run
```

- ***Credentials:*** one in-memory user from `spring.security.user.*`, default `admin`/`admin`, role `ACTUATOR`.
  Override with env vars:

```bash
ACTUATOR_USER=ops ACTUATOR_PASSWORD=s3cret mvn -pl user-service spring-boot:run
```

- The same values feed the Eureka `defaultZone` URL and the metadata-map, so ***every module must use the same pair***
  (they all read the same env vars).

### curl examples

```bash
# direct
curl -u admin:admin http://localhost:8081/api/users
curl -u admin:admin http://localhost:8081/api/users/2
curl -u admin:admin -i http://localhost:8081/api/users/999   # 404
curl -i http://localhost:8081/api/users                       # 401, no credentials
curl http://localhost:8081/actuator/health                    # public

# via gateway-service (8080) -> lb://USER-SERVICE
curl -u admin:admin http://localhost:8080/api/users

# via order-service, which calls user-service itself
curl -u admin:admin http://localhost:8082/api/orders/1/with-user
```

### Two instances for the load-balancing demo

- Start a second copy on another port; both register under `USER-SERVICE` with different instance IDs
  (`host:user-service:port`):

```bash
mvn -pl user-service spring-boot:run                                            # 8081
mvn -pl user-service spring-boot:run -Dspring-boot.run.arguments=--server.port=8091
```

- Eureka dashboard (http://localhost:8761) shows `USER-SERVICE` with 2 instances; repeated calls through the gateway or
  `order-service` are spread ***round-robin*** by Spring Cloud LoadBalancer (watch both consoles / log files).
- Allow up to ~30s for clients' registry caches to see the new instance.

<a id="eureka-registration"></a>

## <span style="color:hsl(25,80%,58%)">3. 🔎 Eureka registration</span>

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://${spring.security.user.name}:${spring.security.user.password}@localhost:8761/eureka/
  instance:
    prefer-ip-address: true
    metadata-map:
      "[user.name]": ${spring.security.user.name}
      "[user.password]": ${spring.security.user.password}
```

- ***Credentialed `defaultZone`:*** `eureka-server` is itself behind HTTP Basic, so the user-info part of the URL makes
  the Eureka client send an `Authorization` header on register / heartbeat / registry fetch.
- ***`prefer-ip-address: true`:*** the instance registers its ***IP address*** instead of its hostname, so callers
  (gateway, `order-service`, SBA) build URLs like `http://192.168.x.y:8081` — avoids unresolvable hostnames across
  machines/containers.
- ***Metadata-map credentials:*** Spring Boot Admin discovers this instance from Eureka and reads the `user.name` /
  `user.password` metadata keys to authenticate its calls to `/actuator/**`.
- ***Why `"[user.name]"`:*** in Spring Boot relaxed binding a `.` in a YAML key means nesting, so `user.name:` would
  bind as map key `user` → `name`. The ***bracket notation*** keeps the dot as part of a single literal map key
  `user.name`, which is exactly what SBA looks up. Quotes are needed because `[` starts a flow sequence in YAML.
- Tests disable the client entirely (`eureka.client.enabled: false`
  in [application-test.yml](src/test/resources/application-test.yml)).

<a id="security"></a>

## <span style="color:hsl(339,80%,58%)">4. 🔐 Security</span>

Defined in [SecurityConfig.java](src/main/java/com/learning/discovery/user/config/SecurityConfig.java) as a single
`SecurityFilterChain` bean:

```java
http
    .authorizeHttpRequests(auth -> auth
        .requestMatchers("/actuator/health/**", "/actuator/info",
                         "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/error").permitAll()
        .anyRequest().authenticated())
    .httpBasic(Customizer.withDefaults())
    .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/actuator/**"))
    .build();
```

| Rule                | Paths                                                   | Why                                                                                                                                  |
|---------------------|---------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| ***permitAll***     | `/actuator/health/**`, `/actuator/info`                 | Eureka/SBA/load-balancer health probes without creds                                                                                 |
| ***permitAll***     | `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**` | Swagger UI browsable without login                                                                                                   |
| ***permitAll***     | `/error`                                                | error responses (e.g. the 404 body) are not turned into 401s                                                                         |
| ***authenticated*** | everything else (`/api/**`, other actuator endpoints)   | HTTP Basic, user from `spring.security.user.*`                                                                                       |
| ***CSRF ignored***  | `/api/**`, `/actuator/**`                               | stateless API clients and SBA POSTs (`/actuator/env`, `/refresh`, `/shutdown`, `/restart`, logger level changes) carry no CSRF token |

- ***No form login*** — only `httpBasic`; a request without credentials gets `401` with `WWW-Authenticate: Basic`.
- ***Audit events:*** [ActuatorConfig.java](src/main/java/com/learning/discovery/user/config/ActuatorConfig.java)
  registers an `InMemoryAuditEventRepository`; once that bean exists, Boot's security audit listener records
  `AUTHENTICATION_SUCCESS` / `AUTHENTICATION_FAILURE` (and authorization failures) published by Spring Security, served
  at `/actuator/auditevents` (SBA "Audit Log").

```mermaid
sequenceDiagram
    participant C as Caller (curl / gateway / order-service)
    participant U as user-service :8081
    participant E as eureka-server :8761
    participant S as admin-server (SBA) :8084
    U->>E: register + heartbeat (Basic creds in defaultZone, metadata user.name/user.password)
    C->>U: GET /api/users (Authorization: Basic admin:admin)
    U->>U: BasicAuthenticationFilter -> authenticated -> AuditEvent AUTHENTICATION_SUCCESS
    U-->>C: 200 [Alice, Bob, Carol]
    C->>U: GET /api/users (no credentials)
    U-->>C: 401 WWW-Authenticate: Basic
    S->>E: fetch registry, read instance metadata creds
    S->>U: GET /actuator/health (public)
    S->>U: GET/POST /actuator/** (Basic from metadata, no CSRF token)
    U-->>S: actuator JSON
```

<a id="actuator-endpoints"></a>

## <span style="color:hsl(116,80%,58%)">5. 📊 Actuator endpoints and Spring Boot Admin views</span>

- ***Exposure:*** `management.endpoints.web.exposure.include` lists every endpoint below explicitly; all except
  `health`/`info` need Basic auth.
- ***`health.show-details: always`*** — full component details (disk, ping, discovery, etc.) even to anonymous callers.

| Endpoint         | SBA view                     | Backing config in this module                                                                                                        |
|------------------|------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| `health`, `info` | Details / Health             | public; `show-details: always`                                                                                                       |
| `heapdump`       | JVM → Heap dump              | `management.endpoint.heapdump.access: unrestricted`                                                                                  |
| `logfile`        | Logfile                      | `logging.file.name: logs/user-service.log` (endpoint only exists when a log file is configured)                                      |
| `env`            | Environment (view)           | exposed                                                                                                                              |
| `env` POST       | Environment "Update" form    | `management.endpoint.env.post.enabled: true` (Spring Cloud Context)                                                                  |
| `shutdown`       | "Shutdown" button            | `management.endpoint.shutdown.access: unrestricted`                                                                                  |
| `restart`        | "Restart" button             | `management.endpoint.restart.access: unrestricted` (Spring Cloud Context)                                                            |
| `refresh`        | Environment "Refresh"        | `management.endpoint.refresh.access: unrestricted` — re-binds `@ConfigurationProperties` / `@RefreshScope` beans                     |
| `metrics`        | Metrics                      | exposed (Micrometer, auto-configured)                                                                                                |
| `loggers`        | Loggers (change levels live) | exposed; POST works because CSRF is ignored on `/actuator/**`                                                                        |
| `threaddump`     | Thread dump                  | exposed                                                                                                                              |
| `mappings`       | Mappings                     | exposed                                                                                                                              |
| `beans`          | Beans                        | exposed                                                                                                                              |
| `configprops`    | Configuration properties     | exposed                                                                                                                              |
| `httpexchanges`  | HTTP Exchanges               | `InMemoryHttpExchangeRepository` bean in [ActuatorConfig.java](src/main/java/com/learning/discovery/user/config/ActuatorConfig.java) |
| `startup`        | Startup                      | `BufferingApplicationStartup(2048)` set in `main()` — ***only when started via `main`***                                             |
| `auditevents`    | Audit Log                    | `InMemoryAuditEventRepository` bean in [ActuatorConfig.java](src/main/java/com/learning/discovery/user/config/ActuatorConfig.java)   |
| `sbom`           | SBOM                         | `cyclonedx-maven-plugin` in the root [pom.xml](../pom.xml) writes `META-INF/sbom/application.cdx.json` at build time                 |
| `quartz`         | Quartz (jobs/triggers)       | `spring-boot-starter-quartz` + demo job in [§6](#quartz-logging-config)                                                              |
| `jolokia`        | JMX                          | `jolokia-support-springboot` 2.6.3 (JMX over HTTP)                                                                                   |

```bash
curl -u admin:admin http://localhost:8081/actuator                       # list of links
curl -u admin:admin http://localhost:8081/actuator/quartz/jobs/demo/heartbeatJob
curl -u admin:admin http://localhost:8081/actuator/auditevents
curl -u admin:admin -X POST http://localhost:8081/actuator/loggers/com.learning \
     -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'
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

## <span style="color:hsl(254,80%,58%)">6. ⏱️ Quartz demo job, file logging, application.yml</span>

### Quartz heartbeat job

- ***Purpose:*** give the SBA Quartz view something to show.
- [QuartzConfig.java](src/main/java/com/learning/discovery/user/job/QuartzConfig.java) defines a durable `JobDetail` ***
  `demo.heartbeatJob`*** ("Logs a heartbeat line every minute") and a `SimpleTrigger` ***`demo.heartbeatTrigger`*** with
  `repeatMinutelyForever(1)`.
- [HeartbeatJob.java](src/main/java/com/learning/discovery/user/job/HeartbeatJob.java) extends `QuartzJobBean` and logs
  `Heartbeat from user-service, next fire at <time>`.
- ***`spring.quartz.job-store-type: memory`*** — RAMJobStore, no DB tables; schedule is lost on restart.

### File logging

- `logging.file.name: logs/${spring.application.name}.log` → `logs/user-service.log`, ***relative to the working
  directory*** (the module dir under `spring-boot:run`).
- Setting it is what makes `/actuator/logfile` (SBA Logfile view) appear.

### Annotated application.yml (excerpt)

```yaml
spring:
  application:
    name: user-service                  # Eureka service id USER-SERVICE
  security:
    user:
      name: ${ACTUATOR_USER:admin}      # shared Basic user for API + actuator
      password: ${ACTUATOR_PASSWORD:admin}
      roles: ACTUATOR
  quartz:
    job-store-type: memory              # in-memory Quartz store

server:
  port: 8081

eureka:
  client:
    service-url:
      defaultZone: http://${spring.security.user.name}:${spring.security.user.password}@localhost:8761/eureka/
  instance:
    prefer-ip-address: true             # register IP, not hostname
    metadata-map:                       # SBA reads these to call /actuator
      "[user.name]": ${spring.security.user.name}
      "[user.password]": ${spring.security.user.password}

logging:
  file:
    name: logs/${spring.application.name}.log   # enables /actuator/logfile

management:
  endpoints.web.exposure.include: [health, info, env, metrics, loggers, threaddump,
    heapdump, mappings, beans, configprops, logfile, shutdown, restart, refresh,
    httpexchanges, startup, auditevents, sbom, quartz, jolokia]
  endpoint:
    shutdown.access: unrestricted
    restart.access: unrestricted
    refresh.access: unrestricted
    env.post.enabled: true
    health.show-details: always
    heapdump.access: unrestricted
```

- The real file lists the exposure entries as a YAML block list; the flow form above is condensed for reading.

<a id="tests"></a>

## <span style="color:hsl(31,80%,58%)">7. 🧪 Tests</span>

- Both test classes run with profile `test` → [application-test.yml](src/test/resources/application-test.yml) sets ***
  `eureka.client.enabled: false`***, so no Eureka server is needed.

```bash
mvn -pl user-service verify   # UserControllerTest + UserServiceApplicationIT (*IT naming, failsafe-style)
```

### [UserControllerTest.java](src/test/java/com/learning/discovery/user/controller/UserControllerTest.java) —
`@WebMvcTest` slice

- `@WebMvcTest(UserController.class)` + `@Import(SecurityConfig.class)` so the real filter chain applies; class-level
  `@WithMockUser`.

| Test                                   | Checks                                            |
|----------------------------------------|---------------------------------------------------|
| `getUsersReturnsSeedList`              | `GET /api/users` → 200, 3 items, first is `Alice` |
| `getUserByIdReturnsMatch`              | `GET /api/users/2` → `Bob`, `bob@example.com`     |
| `getUserByUnknownIdReturns404`         | `GET /api/users/999` → 404                        |
| `getUsersWithoutCredentialsReturns401` | `@WithAnonymousUser` → 401                        |

### [UserServiceApplicationIT.java](src/test/java/com/learning/discovery/user/UserServiceApplicationIT.java) — full stack, random port

- `@SpringBootTest(RANDOM_PORT)` with two `RestTestClient`s: anonymous and one with `setBasicAuth("admin","admin")`.

| Test                                           | Checks                                                                                                      |
|------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| `fullStackReturnsUsersOverHttp`                | authed `GET /api/users` → 2xx, 3 items                                                                      |
| `healthEndpointReportsUp`                      | anonymous `GET /actuator/health` → `status: UP`                                                             |
| `sbaEndpointsAreExposed`                       | authed `GET /actuator` has `_links` for `sbom`, `jolokia`, `httpexchanges`, `auditevents`                   |
| `apiWithoutCredentialsReturns401`              | anonymous `GET /api/users` → 401                                                                            |
| `sensitiveActuatorEndpointRequiresCredentials` | anonymous `/actuator/quartz` → 401; authed `/actuator/quartz/jobs/demo/heartbeatJob` → `name: heartbeatJob` |

<a id="gotchas"></a>

## <span style="color:hsl(169,80%,58%)">8. ⚠️ Gotchas</span>

- ***Credentials must match across modules*** — they are used for the API, the Eureka `defaultZone` and the SBA
  metadata; change `ACTUATOR_USER`/`ACTUATOR_PASSWORD` everywhere or registration fails with 401.
- ***`/actuator/startup` is empty/absent in tests*** — `BufferingApplicationStartup` is installed only in `main()`;
  `@SpringBootTest` doesn't go through it.
- ***`/actuator/sbom` needs a Maven build*** — the SBOM file comes from the root CycloneDX plugin; running from an IDE
  without `mvn package`/`process-classes` leaves it missing.
- ***`shutdown` / `restart` / `heapdump` / `env` POST are dangerous*** — fine for a local demo, but anyone with the
  shared password can kill the JVM, dump memory (secrets included) or rewrite properties.
- ***`show-details: always` leaks health internals*** to anonymous callers, since `/actuator/health/**` is public.
- ***Audit and HTTP exchanges are in-memory*** — bounded buffers, lost on restart; not an audit trail.
- ***Log file path is relative*** — starting from a different working directory writes `logs/` somewhere else.
- ***No CSRF on `/api/**`*** is safe here only because the API is read-only and Basic-authenticated, with no session
  cookies.
- ***Second instance needs a different port*** — two instances on 8081 fail with "port already in use";
  `prefer-ip-address` means both show up with the same IP and different ports.

<a id="qa"></a>

## <span style="color:hsl(306,80%,58%)">9. ❓ Q&A</span>

<a id="qa-sba-call-options"></a>

**Q1. What are the different ways Spring Boot Admin can call `user-service`, and what does `user-service` need for each?**

- ***SBA always pulls.*** admin-server polls `/actuator/health` (every 10 s), reads `/actuator/info`, and proxies every
  UI view through itself (`/instances/{id}/actuator/**`), so the browser never calls `user-service` directly.
- ***Two choices vary:*** how SBA *learns* the actuator URL (A) and how it *authenticates* the call (B).

***A. How SBA finds `user-service`***

| # | Way                                       | Who knows the URL                     | Used here |
|---|-------------------------------------------|---------------------------------------|-----------|
| 1 | Eureka discovery                          | Eureka registry, read by admin-server | **yes**   |
| 2 | SBA client (push)                         | `user-service` registers itself with SBA   | no        |
| 3 | Static list (`SimpleDiscoveryClient`)     | admin-server's `application.yml`      | no        |
| 4 | Kubernetes discovery                      | Kubernetes API (Services/Endpoints)   | no        |

**A1. Eureka discovery (current setup).** No SBA dependency in `user-service`; SBA turns each Eureka instance into an SBA
instance.

```yaml
# user-service/src/main/resources/application.yml
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

**A2. SBA client (push).** `user-service` POSTs its own URLs to admin-server at startup and DELETEs on shutdown. Useful
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
          # service-base-url: http://user-service.example:8081   # override when the auto-detected URL is unreachable (NAT, containers)
          metadata:
            user.name: ${spring.security.user.name}       # credentials SBA sends BACK to user-service's actuator
            user.password: ${spring.security.user.password}
```

- admin-server must permit `POST /instances` with Basic and exclude it from CSRF.
- Don't combine with A1: the same app shows up twice (one entry per source).

**A3. Static list on admin-server.** `user-service` needs nothing SBA-specific, only exposed and secured actuator; its URL
and credentials live in admin-server's config:

```yaml
# admin-server/src/main/resources/application.yml
spring:
  cloud:
    discovery:
      client:
        simple:
          instances:
            user-service:
              - uri: http://localhost:8081
                metadata:
                  user.name: admin
                  user.password: admin
```

- No health-based registration: a stopped instance stays listed as `OFFLINE` until you edit the list.

**A4. Kubernetes.** admin-server uses `spring-cloud-starter-kubernetes-client` instead of Eureka. `user-service` needs a
Kubernetes `Service`; name a separate actuator port `management` in the Service's ports, and put `user.name` /
`tags.*` style keys in the Service's labels/annotations (Spring Cloud Kubernetes copies them into the instance metadata).

***B. How SBA authenticates to `user-service`'s actuator***

| #  | Method                     | `user-service` side                                                                      | admin-server side                                                                                                                                   |
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
