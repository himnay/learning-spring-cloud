# <span style="color:hsl(24,80%,58%)">order-service</span>

## <span style="color:hsl(201,80%,58%)">Table of contents</span>

1. 🧭 [Overview and REST API](#overview)
2. 🚀 [Run it](#run)
3. 🗄️ [Persistence: Spring Data JDBC + Flyway](#persistence)
4. ⚡ [Caching and the cache-refresh scheduler](#caching)
5. 🔐 [Security](#security)
6. 📊 [Actuator endpoints and Spring Boot Admin views](#actuator)
7. ⏰ [Quartz demo job, file logging, `application.yml`](#quartz-logging-config)
8. 🧪 [Tests](#tests)
9. 🛠️ [Gotchas and troubleshooting](#gotchas)
10. ❓ [Q&A](#qa)
    - 10.1 [What are the different ways Spring Boot Admin can call `order-service`, and what does `order-service` need for each?](#qa-sba-call-options)

The `order-service` module of the Eureka discovery demo. It is the "kitchen sink"
service: on top of the plain Eureka-client role shared with `user-service` and
`product-service`, it is the only module with a real database (Postgres via
Docker Compose), a cache, a scheduler, JDBC-backed HTTP sessions and a
service-to-service call. Repo-wide context (module list, run order, parent/BOM
chain, SBA feature matrix) lives in the root [../README.md](../README.md); this
file only covers what is specific to this module.

<a id="overview"></a>

## <span style="color:hsl(330,80%,58%)">1. 🧭 Overview and REST API</span>

- ***Spring MVC (servlet) app on port 8082*** — `server.port: 8082`, registers with Eureka as `ORDER-SERVICE`
  (`spring.application.name: order-service`, `prefer-ip-address: true`).
- ***Stack (as of 2026)*** — Spring Boot 4.1 (`spring-boot-starter-webmvc`), Spring Cloud 2025.1 (Eureka client +
  LoadBalancer), Java 25+ style entry point (package-private class with a non-public `static void main`), all versions
  inherited from the parent chain described in [../README.md §3](../README.md#parent--bom-chain).
- ***Needs Docker*** — Postgres is started by `spring-boot-docker-compose` from this
  module's [docker-compose.yaml](docker-compose.yaml) (`user-service` and `product-service` do the same with their own
  file).

**Dependencies ([pom.xml](pom.xml)):**

| Concern             | Dependency                                                                                                                                                          |
|---------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Web / REST          | `spring-boot-starter-webmvc`, `springdoc-openapi-starter-webmvc-ui` (Swagger UI)                                                                                    |
| Discovery           | `spring-cloud-starter-netflix-eureka-client`, `spring-cloud-starter-loadbalancer`                                                                                   |
| Persistence         | `spring-boot-starter-data-jdbc`, `spring-boot-starter-flyway`, `flyway-database-postgresql`, `postgresql` (runtime)                                                 |
| Local DB            | `spring-boot-docker-compose` (runtime, optional)                                                                                                                    |
| Caching             | `spring-boot-starter-cache`, `caffeine`                                                                                                                             |
| Security / sessions | `spring-boot-starter-security`, `spring-boot-starter-session-jdbc`                                                                                                  |
| Ops                 | `spring-boot-starter-actuator`, `spring-boot-starter-quartz`, `jolokia-support-springboot` **2.6.3** (version from `learning-bom` 3.1.0, no `<version>` in the pom) |
| Tracing             | `spring-boot-starter-opentelemetry` → OTLP to [../zipkin-server](../zipkin-server/README.md)                                                                        |
| Boilerplate         | `lombok` (optional; `@Slf4j`)                                                                                                                                       |
| Test                | `spring-boot-starter-webmvc-test`, `spring-boot-starter-security-test`, `spring-boot-testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-postgresql`   |

**REST API ([OrderController.java](src/main/java/com/learning/discovery/order/controller/OrderController.java)) — all
require Basic auth:**

| Method + path                    | Returns                         | Backed by                                               | Notes                                                                                |
|----------------------------------|---------------------------------|---------------------------------------------------------|--------------------------------------------------------------------------------------|
| `GET /api/orders`                | `List<Order>`                   | `OrderService.findAll()` → `OrderRepository.findAll()`  | ***Always hits Postgres*** — not cached                                              |
| `GET /api/orders/{id}`           | `Order`                         | `OrderService.findById(id)` (`@Cacheable("orders")`)    | Unknown id → `ResponseStatusException(404, "Order {id} not found")`                  |
| `GET /api/orders/{id}/with-user` | `OrderWithUser { order, user }` | `getOrder(id)` + `UserClient.fetchUser(order.userId())` | Calls `user-service` ***directly*** (no gateway); `user` is `null` if the call fails |

Records ([model/](src/main/java/com/learning/discovery/order/model/)):

```java

@Table("orders")
public record Order(@Id Long id, Long userId, String product, int quantity) {
}

public record UserSummary(Long id, String name, String email) {
}

public record OrderWithUser(Order order, UserSummary user) {
}
```

**How `/with-user` reaches
`user-service` ([UserClient.java](src/main/java/com/learning/discovery/order/client/UserClient.java), [LoadBalancerConfig.java](src/main/java/com/learning/discovery/order/config/LoadBalancerConfig.java)):**

- ***`http://USER-SERVICE` is a Eureka service id, not a host*** — the injected `RestClient.Builder` is `@LoadBalanced`,
  so Spring Cloud LoadBalancer resolves `USER-SERVICE` against the local Eureka registry cache and picks a live instance
  (round-robin by default).
- ***Basic auth on every call*** — `defaultHeaders(h -> h.setBasicAuth(user, pass))` with the same
  `spring.security.user.*` credentials, because `user-service` is secured with the same shared user.
- ***Graceful fallback*** — `fetchUser` catches `RestClientException` (connection refused, timeout, 4xx/5xx) and returns
  `Optional.empty()`; the controller turns that into `"user": null` with HTTP 200 instead of failing the whole request.
- ***Two `RestClient.Builder` beans*** — a `@Primary` unqualified one and a `@LoadBalanced` one. The `@Primary` one
  exists because Eureka's own `DiscoveryClient` looks up an unqualified `RestClient.Builder`; if it got the
  load-balanced one, its registration calls to `localhost:8761` would be routed through the load balancer and never
  succeed.
- ***Bounded timeouts on both builders*** — `JdkClientHttpRequestFactory` with 5 s connect and 30 s read timeout; the
  JDK client's default read timeout is infinite, so a hung upstream would otherwise pin a request thread.

```bash
curl -u admin:admin http://localhost:8082/api/orders/101/with-user
# {"order":{"id":101,"userId":1,"product":"Keyboard","quantity":1},
#  "user":{"id":1,"name":"...","email":"..."}}     <- "user":null if user-service is down
```

- ***Swagger UI*** — http://localhost:8082/swagger-ui.html, spec at `/v3/api-docs` (both public).
- ***Banner*** — [banner.txt](src/main/resources/banner.txt) prints an ASCII "ORDER SVC" plus project metadata and the
  Swagger URL.

<a id="run"></a>

## <span style="color:hsl(96,80%,45%)">2. 🚀 Run it</span>

- ***Docker must be running*** — on startup `spring-boot-docker-compose` runs `docker compose up`
  on [docker-compose.yaml](docker-compose.yaml), waits for Postgres, and ***builds the datasource URL/credentials
  itself*** from the running container (no `spring.datasource.*`
  in [application.yml](src/main/resources/application.yml)).
- ***Postgres 18 on host port 5433*** — DB/user/password all `orders`; `5433:5432` so it does not clash with another
  local Postgres on 5432.
- ***Start Eureka first*** (see [../README.md §4](../README.md#run-order)) — order-service runs without it, but logs
  registration errors and `/with-user` cannot resolve `USER-SERVICE`.

```bash
# from the repo root
mvn -pl eureka-server spring-boot:run -Dspring-boot.run.profiles=peer1 &
mvn -pl eureka-server spring-boot:run -Dspring-boot.run.profiles=peer2 &   # once peer1 is up; peer3 optional
mvn -pl user-service  spring-boot:run &     # only needed for /with-user
mvn -pl order-service spring-boot:run
```

**Where the compose file lives and how Boot finds it:**

- ***It sits in this module*** ([docker-compose.yaml](docker-compose.yaml)), but the working directory is the repo root:
  IntelliJ run configurations use the project root, and the [pom.xml](pom.xml) pins `spring-boot-maven-plugin`'s
  `<workingDirectory>${maven.multiModuleProjectDirectory}</workingDirectory>` so `mvn -pl order-service spring-boot:run`
  matches.
- So [application.yml](src/main/resources/application.yml) sets
  `spring.docker.compose.file: order-service/docker-compose.yaml` (relative to the root). `user-service` and
  `product-service` do the same with their own files.
- `name: order-db` gives it its own Compose project, so it never touches the other modules' Postgres containers.
- Side effect: the log file `logs/order-service.log` is written relative to the working directory (repo root when run
  from Maven/IDE; `order-service/logs/` when tests run inside the module).

**Credentials:**

| Variable            | Default | Used for                                                                                                            |
|---------------------|---------|---------------------------------------------------------------------------------------------------------------------|
| `ACTUATOR_USER`     | `admin` | `spring.security.user.name` — API, actuator, Eureka registration URL, SBA metadata, outbound call to `user-service` |
| `ACTUATOR_PASSWORD` | `admin` | `spring.security.user.password` — same places                                                                       |

```bash
curl -u admin:admin http://localhost:8082/api/orders
curl -u admin:admin http://localhost:8082/api/orders/104
curl -u admin:admin http://localhost:8082/api/orders/101/with-user
curl                http://localhost:8082/actuator/health          # public
curl -u admin:admin http://localhost:8082/actuator/caches
curl -u admin:admin http://localhost:8082/actuator/scheduledtasks
curl -u admin:admin http://localhost:8082/actuator/flyway
```

**Stopping the database:**

- ***Boot's default lifecycle is start-and-stop*** — the container is stopped (not removed) when the app shuts down, and
  reused on the next start.
- To remove it completely, from the repo root:

```bash
docker compose -f order-service/docker-compose.yaml down   # removes the postgres container; Flyway re-creates + re-seeds on next start
```

- ***Stale-container / port 5433 gotcha*** — if some other container already publishes host port 5433 (e.g. an older
  Postgres started from a different directory, which Compose treats as a different project), `docker compose up` fails
  with `Bind for 0.0.0.0:5433 failed: port is already allocated`. Find and remove it:

```bash
docker ps --filter publish=5433
docker rm -f <container-id>
```

<a id="persistence"></a>

## <span style="color:hsl(270,80%,62%)">3. 🗄️ Persistence: Spring Data JDBC + Flyway</span>

- ***Spring Data JDBC, not JPA*** — no Hibernate, no lazy loading, no dirty checking; an immutable `record` maps
  straight onto a row.
- ***`@Table("orders")`*** — `ORDER` is a reserved SQL word, so the table is plural; columns follow the default
  snake-case naming (`userId` → `user_id`).
- ***[OrderRepository.java](src/main/java/com/learning/discovery/order/repository/OrderRepository.java)*** —
  `interface OrderRepository extends ListCrudRepository<Order, Long>`, no custom queries; `findAll()` returns a `List`.
- ***Flyway owns the schema*** — runs [db/migration](src/main/resources/db/migration/) on startup, before any repository
  or session use; visible in `/actuator/flyway`.

| Migration                                                                        | What it does                                                                                                                                                                        |
|----------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [V1__create_orders.sql](src/main/resources/db/migration/V1__create_orders.sql)   | `orders(id BIGINT PK, user_id BIGINT NOT NULL, product VARCHAR(100) NOT NULL, quantity INT NOT NULL CHECK (quantity > 0))` — ids are assigned by the seed, no sequence              |
| [V2__seed_orders.sql](src/main/resources/db/migration/V2__seed_orders.sql)       | Inserts the 6 demo orders below                                                                                                                                                     |
| [V3__spring_session.sql](src/main/resources/db/migration/V3__spring_session.sql) | `SPRING_SESSION` + `SPRING_SESSION_ATTRIBUTES` tables and indexes, copied from spring-session-jdbc's `schema-postgresql.sql` (hence `spring.session.jdbc.initialize-schema: never`) |

**Seed data (V2):**

| id  | user_id | product  | quantity |
|-----|---------|----------|----------|
| 101 | 1       | Keyboard | 1        |
| 102 | 2       | Monitor  | 2        |
| 103 | 1       | Mouse    | 3        |
| 104 | 3       | Laptop   | 1        |
| 105 | 2       | Headset  | 2        |
| 106 | 3       | Webcam   | 1        |

`user_id` 1–3 line up with the users served by `user-service`, which is what makes `/with-user` return a populated
`user`.

<a id="caching"></a>

## <span style="color:hsl(48,85%,50%)">4. ⚡ Caching and the cache-refresh scheduler</span>

**Cache configuration ([application.yml](src/main/resources/application.yml)):**

```yaml
spring:
  cache:
    type: caffeine
    cache-names: orders
    caffeine:
      spec: maximumSize=1000,expireAfterWrite=10m,recordStats
```

| Spec part              | Effect                                                                                              |
|------------------------|-----------------------------------------------------------------------------------------------------|
| `maximumSize=1000`     | Size-bounded; Caffeine evicts (W-TinyLFU) beyond 1000 entries                                       |
| `expireAfterWrite=10m` | ***Every key expires 10 minutes after it was last written***                                        |
| `recordStats`          | Hit/miss/eviction counters → `cache.gets`, `cache.puts`, `cache.evictions` … in `/actuator/metrics` |

**Read path — [OrderService.java](src/main/java/com/learning/discovery/order/service/OrderService.java):**

- ***`@Cacheable(cacheNames = "orders", unless = "#result == null")` on `findById(Long id)`*** — key is the `id`; Spring
  unwraps the `Optional`, so the cached value is the `Order` itself.
- ***Misses are not cached*** — an unknown id yields an empty `Optional`, `#result` is `null`, so a 404 never poisons
  the cache.
- ***`findAll()` is not cached*** — `GET /api/orders` always queries Postgres.

**Write
path — [OrderCacheRefresher.java](src/main/java/com/learning/discovery/order/service/OrderCacheRefresher.java):**

- ***`@Scheduled(fixedRateString = "${orders.cache.refresh-interval:5m}")`*** — `orders.cache.refresh-interval: 5m` in
  `application.yml`, 5 m fallback in the annotation.
- ***Warms the cache at startup*** — a `fixedRate` task with no initial delay fires as soon as the scheduler starts, so
  the cache is full before the first request (the IT asserts order 104 is cached).
- ***Loads every row and `put`s it under its id*** — the same key `@Cacheable` uses, so a later `GET /api/orders/{id}`
  is a cache hit; logs `Refreshed 'orders' cache with N orders`.

**5 m refresh vs 10 m TTL — what actually expires:**

- ***Existing rows never expire*** — every 5 minutes each row is re-`put`, resetting its 10-minute write timer, so live
  keys stay cached indefinitely.
- ***Updated rows*** are stale for at most one refresh interval (≤ 5 min), then overwritten.
- ***Deleted rows*** stop being re-put; their entry expires 10 minutes after the last refresh that saw them — gone
  within two refresh cycles.
- ***If the scheduler stops*** (or the interval is raised past 10 m), entries begin to expire and requests fall through
  to Postgres via `@Cacheable`.

```mermaid
flowchart LR
subgraph Request path
C[GET /api/orders/id] --> S[OrderService.findById<br/>@Cacheable orders]
S -->|hit|CA[(Caffeine 'orders'<br/>max 1000, TTL 10m)]
S -->|miss|DB[(Postgres orders)]
DB -->|row found: put|CA
end
subgraph Scheduler every 5m + at startup
R[OrderCacheRefresher<br/>@Scheduled fixedRate] -->|findAll|DB
R -->|put id - > Order<br/>resets TTL|CA
end
```

**Why `@EnableCaching` / `@EnableScheduling` live
in [CacheSchedulingConfig.java](src/main/java/com/learning/discovery/order/config/CacheSchedulingConfig.java):**

- ***`@WebMvcTest` slices don't load the cache auto-configuration*** — if `@EnableCaching` sat on
  `OrderServiceApplication`, every slice test would fail looking for a `CacheManager`.
- A separate `@Configuration` class is not picked up by the MVC slice, so the slice stays clean while the full app gets
  both features.

<a id="security"></a>

## <span style="color:hsl(0,80%,60%)">5. 🔐 Security</span>

[SecurityConfig.java](src/main/java/com/learning/discovery/order/config/SecurityConfig.java) defines one
`SecurityFilterChain` (the class is `public` so `@WebMvcTest` can `@Import` it):

```java
http
        .authorizeHttpRequests(auth ->auth
        .

requestMatchers("/actuator/health/**","/actuator/info",
                        "/swagger-ui.html","/swagger-ui/**","/v3/api-docs/**","/error").

permitAll()
            .

anyRequest().

authenticated())
        .

httpBasic(Customizer.withDefaults())
        .

formLogin(Customizer.withDefaults())
        .

csrf(csrf ->csrf.

ignoringRequestMatchers("/api/**","/actuator/**"))
```

- ***Single in-memory user*** from `spring.security.user.*` — `admin`/`admin` (overridable via `ACTUATOR_USER`/
  `ACTUATOR_PASSWORD`), role `ACTUATOR`.
- ***Permitted without auth*** — `/actuator/health/**`, `/actuator/info`, `/swagger-ui/**`, `/v3/api-docs/**`, `/error`;
  everything else (all `/api/**`, every other actuator endpoint, `/login` page aside) needs authentication.
- ***HTTP Basic + form login side by side*** — API clients (e.g. `Accept: application/json`) get `401` with
  `WWW-Authenticate: Basic`; a browser navigating to a protected page is redirected to the default `/login` form.
- ***CSRF*** — ignored for `/api/**` (stateless API clients) and `/actuator/**` (SBA's POSTs for
  shutdown/restart/refresh/env/loggers carry no token); still enforced for `/login` and `/logout` form posts (the
  generated login page embeds the token).

**Spring Session JDBC:**

- ***`spring-boot-starter-session-jdbc` replaces the servlet `HttpSession` with rows in Postgres*** (`SPRING_SESSION`,
  `SPRING_SESSION_ATTRIBUTES`, created by Flyway V3).
- ***Basic-auth calls don't create sessions*** — Spring Security doesn't persist the context for Basic; ***log in
  through http://localhost:8082/login*** in a browser to create one.
- ***`/actuator/sessions` needs a `username` filter*** — `GET /actuator/sessions?username=admin`; SBA's Sessions view
  searches the same way (type the username), and can delete a session (which logs that browser out).
- Sessions survive an app restart because they live in the DB, not in memory.

**Audit events:**

- [ActuatorConfig.java](src/main/java/com/learning/discovery/order/config/ActuatorConfig.java) registers an
  `InMemoryAuditEventRepository`; with that bean present Boot's `AuthenticationAuditListener` records
  `AUTHENTICATION_SUCCESS` / `AUTHENTICATION_FAILURE` (and authorization failures) for every login attempt, shown at
  `/actuator/auditevents` / SBA "Audit Events".

**Outbound security** — Eureka registration uses `http://${user}:${pass}@localhost:8761/eureka/`, SBA reads
`eureka.instance.metadata-map.user.name/user.password` to poll this instance's actuator, and `UserClient` sends Basic
auth to `user-service`. Repo-wide picture: [../README.md §10](../README.md#security-and-sba-features).

<a id="actuator"></a>

## <span style="color:hsl(170,80%,45%)">6. 📊 Actuator endpoints and Spring Boot Admin views</span>

All endpoints below are exposed over HTTP via `management.endpoints.web.exposure.include` and (except health/info)
require Basic auth. SBA (`admin-server`, port 8084) discovers this instance through Eureka and renders each one as a
view (as of 2026, SBA 4.1).

| Endpoint                           | SBA view                                    | Backing config in this module                                                                                                                                                                                                            |
|------------------------------------|---------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `health`                           | Details / Health                            | public; `show-details: always` (db, diskSpace, discoveryComposite, ping …)                                                                                                                                                               |
| `info`                             | Details / Info                              | public                                                                                                                                                                                                                                   |
| `metrics`                          | Metrics                                     | includes Caffeine `cache.*` metrics thanks to `recordStats`                                                                                                                                                                              |
| `env` (GET + POST)                 | Environment (+ "Update")                    | `management.endpoint.env.post.enabled: true` (Spring Cloud Context)                                                                                                                                                                      |
| `refresh`                          | Environment → "Refresh"                     | `access: unrestricted`; re-binds `@ConfigurationProperties` / `@RefreshScope` beans                                                                                                                                                      |
| `restart`                          | "Restart" button                            | `access: unrestricted`; Spring Cloud Context restart endpoint                                                                                                                                                                            |
| `shutdown`                         | "Shutdown" button                           | `access: unrestricted`                                                                                                                                                                                                                   |
| `loggers`                          | Loggers                                     | change levels live                                                                                                                                                                                                                       |
| `threaddump`                       | Thread Dump                                 | default                                                                                                                                                                                                                                  |
| `heapdump`                         | Heap Dump                                   | `access: unrestricted` (Boot 3.5+ defaults to `none`)                                                                                                                                                                                    |
| `mappings`, `beans`, `configprops` | Mappings / Beans / Configuration Properties | default                                                                                                                                                                                                                                  |
| `caches`                           | Caches                                      | Caffeine `orders` cache (§4); can clear it from SBA                                                                                                                                                                                      |
| `scheduledtasks`                   | Scheduled Tasks                             | `OrderCacheRefresher.refreshOrdersCache`, fixed rate 5 m                                                                                                                                                                                 |
| `flyway`                           | Flyway                                      | migrations V1–V3 (§3)                                                                                                                                                                                                                    |
| `sessions`                         | Sessions                                    | Spring Session JDBC; search by username                                                                                                                                                                                                  |
| `logfile`                          | Logfile                                     | `logging.file.name: logs/${spring.application.name}.log`                                                                                                                                                                                 |
| `httpexchanges`                    | HTTP Exchanges                              | `InMemoryHttpExchangeRepository` bean (last 100 requests) in [ActuatorConfig.java](src/main/java/com/learning/discovery/order/config/ActuatorConfig.java)                                                                                |
| `auditevents`                      | Audit Events                                | `InMemoryAuditEventRepository` bean + Spring Security login events                                                                                                                                                                       |
| `startup`                          | Startup                                     | `BufferingApplicationStartup(2048)` set in `main()` of [OrderServiceApplication.java](src/main/java/com/learning/discovery/order/OrderServiceApplication.java) — ***only present when started via `main`***, absent in `@SpringBootTest` |
| `sbom`                             | SBOM                                        | `META-INF/sbom/application.cdx.json` from the root pom's `cyclonedx-maven-plugin`                                                                                                                                                        |
| `quartz`                           | Quartz                                      | `HeartbeatJob` in group `demo` (§7)                                                                                                                                                                                                      |
| `jolokia`                          | JMX                                         | `jolokia-support-springboot` 2.6.3 (servlet only)                                                                                                                                                                                        |

- ***`httpexchanges` and `auditevents` stay disabled without a repository bean*** — that is the only
  reason [ActuatorConfig.java](src/main/java/com/learning/discovery/order/config/ActuatorConfig.java) exists.
- ***`shutdown`, `restart`, `env` POST, `refresh` and `jolokia` mutate the running app*** — acceptable here behind Basic
  auth on localhost; never expose them publicly.

### Health, info, tags and custom metrics

- ***Custom health indicators*** (Details → Health):
    - `ordersCache`
      ([OrdersCacheHealthIndicator.java](src/main/java/com/learning/discovery/order/service/OrdersCacheHealthIndicator.java)) —
      UP with `entries`, `lastRefresh`, `lastRefreshCount`; UNKNOWN before the first refresh or when empty.
    - `userService`
      ([UserServiceHealthIndicator.java](src/main/java/com/learning/discovery/order/client/UserServiceHealthIndicator.java)) —
      user-service instance count + URLs from Eureka; UNKNOWN (never DOWN) when none, because `/with-user` degrades
      gracefully.
- ***Probes*** — `management.endpoint.health.probes.enabled: true` adds `/actuator/health/liveness` and
  `/actuator/health/readiness` (public); readiness here = `readinessState,db`, so the instance isn't ready without
  Postgres.
- ***Info*** — `info.app.*` (name, description, tier, environment) plus the `java`, `os`, `process` contributors →
  Details → Info.
- ***Tags*** — `eureka.instance.metadata-map.tags.environment` (`APP_ENV`, default `local`) and `tags.tier: backend` →
  badges in SBA's application list.
- ***Custom metrics*** (Metrics → pick name):

| Metric                                       | Type                     | Meaning                                                           |
|----------------------------------------------|--------------------------|-------------------------------------------------------------------|
| `orders.cache.refresh`                       | Timer                    | duration/count of each `OrderCacheRefresher` run                  |
| `orders.db.reads{op=findAll\|findById}`      | Counter                  | reads that reached Postgres — `findById` only counts cache misses |
| `cache.gets{result=hit\|miss}`, `cache.size` | Caffeine (`recordStats`) | built-in cache statistics                                         |

<a id="quartz-logging-config"></a>

## <span style="color:hsl(300,70%,60%)">7. ⏰ Quartz demo job, file logging, `application.yml`</span>

**Quartz ([job/](src/main/java/com/learning/discovery/order/job/)):**

- ***[HeartbeatJob.java](src/main/java/com/learning/discovery/order/job/HeartbeatJob.java)*** — a `QuartzJobBean` that
  logs `Heartbeat from order-service, next fire at …`.
- ***[QuartzJobConfig.java](src/main/java/com/learning/discovery/order/job/QuartzJobConfig.java)*** — `JobDetail`
  `demo.heartbeatJob` (`storeDurably()`, description "Logs a heartbeat line every minute") and `Trigger`
  `demo.heartbeatTrigger` with `SimpleScheduleBuilder.repeatMinutelyForever()`. Boot's Quartz auto-config registers both
  beans with the scheduler.
- ***In-memory job store*** — `spring.quartz.job-store-type: memory`; nothing in Postgres, state resets on restart.
- ***Two schedulers, on purpose*** — Spring's `@Scheduled` drives the cache refresher (`scheduledtasks` endpoint);
  Quartz drives the heartbeat (`quartz` endpoint, can be paused/triggered from SBA).

**File logging** — `logging.file.name: logs/order-service.log` (relative to the working directory, see §2) turns on
`/actuator/logfile`; console logging is unchanged.

**Annotated [application.yml](src/main/resources/application.yml) (abridged):**

```yaml
spring:
  application:
    name: order-service              # Eureka service id ORDER-SERVICE, log file name
  # no spring.datasource.* — docker-compose support wires it from docker-compose.yaml
  cache:
    type: caffeine
    cache-names: orders
    caffeine:
      spec: maximumSize=1000,expireAfterWrite=10m,recordStats
  security:
    user:
      name: ${ACTUATOR_USER:admin}
      password: ${ACTUATOR_PASSWORD:admin}
      roles: ACTUATOR
  session:
    jdbc:
      initialize-schema: never       # Flyway V3 owns the session tables
  quartz:
    job-store-type: memory

orders:
  cache:
    refresh-interval: 5m             # OrderCacheRefresher fixed rate

server:
  port: 8082

eureka:
  client:
    service-url:
      defaultZone: http://${spring.security.user.name}:${spring.security.user.password}@localhost:8761/eureka/
  instance:
    prefer-ip-address: true
    metadata-map: # SBA uses these to authenticate its actuator polling
      user.name: ${spring.security.user.name}
      user.password: ${spring.security.user.password}

logging:
  file:
    name: logs/${spring.application.name}.log

management:
  endpoints.web.exposure.include: [ health, info, env, metrics, loggers, threaddump, heapdump,
                                    mappings, beans, configprops, caches, scheduledtasks, flyway, logfile, shutdown, restart,
                                    refresh, httpexchanges, startup, auditevents, sbom, quartz, jolokia, sessions ]
  endpoint:
    shutdown.access: unrestricted
    restart.access: unrestricted
    refresh.access: unrestricted
    env.post.enabled: true
    health.show-details: always
    heapdump.access: unrestricted
```

<a id="tests"></a>

## <span style="color:hsl(140,70%,45%)">8. 🧪 Tests</span>

Both test classes run with `@ActiveProfiles("test")`; [application-test.yml](src/test/resources/application-test.yml)
sets `eureka.client.enabled: false` so no Eureka server is needed. Run with `mvn -pl order-service verify` (surefire
runs `*Test`, failsafe runs `*IT`).

**Unit — [OrderControllerTest.java](src/test/java/com/learning/discovery/order/controller/OrderControllerTest.java)**
(`@WebMvcTest(OrderController.class)` + `@Import(SecurityConfig.class)` + `@WithMockUser`; `OrderService` and
`UserClient` are `@MockitoBean`s, no DB, no Docker):

| Test                                                        | Checks                                                                        |
|-------------------------------------------------------------|-------------------------------------------------------------------------------|
| `getOrdersReturnsSeedList`                                  | `GET /api/orders` → 200, 3 items, first product `Keyboard`                    |
| `getOrderByIdReturnsMatch`                                  | `GET /api/orders/102` → `Monitor`, quantity 2                                 |
| `getOrderByUnknownIdReturns404`                             | `GET /api/orders/999` → 404                                                   |
| `getOrderWithUserEnrichesFromUserService`                   | `/api/orders/101/with-user` → `order.product = Keyboard`, `user.name = Alice` |
| `getOrderWithUserWhenUserServiceUnreachableReturnsNullUser` | `fetchUser` empty → 200, `order` present, `user` absent                       |
| `getOrdersWithoutCredentialsReturns401`                     | `@WithAnonymousUser` + `Accept: application/json` → 401                       |

**Integration — [OrderServiceApplicationIT.java](src/test/java/com/learning/discovery/order/OrderServiceApplicationIT.java)**
(`@SpringBootTest(RANDOM_PORT)` + `RestTestClient`; ***Testcontainers `postgres:18`*** with `@ServiceConnection`, so
Flyway V1–V3 run against a throwaway DB; Boot skips docker-compose support in tests, so the compose file is not used;
Docker still required):

| Test                                         | Checks                                                                                                                                                                   |
|----------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `fullStackReturnsFlywaySeededOrdersOverHttp` | `GET /api/orders` with `admin:admin` → 6 rows from V2                                                                                                                    |
| `schedulerWarmsOrdersCacheOnStartup`         | `cacheManager.getCache("orders").get(104L)` is non-null without any request                                                                                              |
| `unknownOrderReturns404`                     | `GET /api/orders/999` → 404                                                                                                                                              |
| `ordersWithoutCredentialsReturns401`         | anonymous → 401                                                                                                                                                          |
| `wrongPasswordReturns401`                    | `admin:wrong` → 401                                                                                                                                                      |
| `quartzEndpointListsHeartbeatJob`            | `/actuator/quartz/jobs` → `groups.demo.jobs[0] = heartbeatJob`                                                                                                           |
| `actuatorExposesSbaEndpoints`                | `/actuator` links include `httpexchanges`, `auditevents`, `sbom`, `quartz`, `jolokia`, `sessions` (not `startup` — only `main()` installs `BufferingApplicationStartup`) |
| `sessionsEndpointIsBackedByJdbcRepository`   | `/actuator/sessions?username=admin` → `sessions` is an array                                                                                                             |
| `healthEndpointReportsUpWithoutCredentials`  | anonymous `/actuator/health` → `status: UP`                                                                                                                              |

<a id="gotchas"></a>

## <span style="color:hsl(220,80%,62%)">9. 🛠️ Gotchas and troubleshooting</span>

| Symptom                                                                           | Cause                                                                                                                                            | Fix                                                                                                                      |
|-----------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------|
| `No Docker Compose file found` / `… docker-compose.yaml does not exist`           | Started with a working directory other than the repo root (e.g. the module, `$MODULE_WORKING_DIR$`)                                              | Set the run config's working directory to the project root, or override `spring.docker.compose.file=docker-compose.yaml` |
| `Bind for 0.0.0.0:5433 failed: port is already allocated`                         | Another/stale container (often from a different Compose project) holds 5433                                                                      | `docker ps --filter publish=5433`, then `docker rm -f <id>`; or `docker compose down` in the directory that started it   |
| `No host port mapping found` / datasource not configured                          | The compose service publishes no host port, so Boot can't build a JDBC URL                                                                       | Keep `ports: - "5433:5432"` in [docker-compose.yaml](docker-compose.yaml)                                                |
| Startup fails connecting to Docker                                                | Docker daemon not running                                                                                                                        | Start Docker; the app has no fallback datasource                                                                         |
| `401` on `/api/**` or actuator                                                    | Missing/wrong Basic credentials, or `ACTUATOR_USER/PASSWORD` set differently in this process vs. the caller                                      | `curl -u admin:admin …`; keep the env vars identical across all modules                                                  |
| `/with-user` returns `"user": null`                                               | `user-service` down, not yet registered/fetched by Eureka (registry cache lag ~30 s), or rejected the credentials                                | Start `user-service`, wait for it in the Eureka dashboard, check its logs                                                |
| SBA Sessions view empty                                                           | Only Basic-auth traffic so far — Basic creates no session                                                                                        | Log in at http://localhost:8082/login, then search username `admin`                                                      |
| SBA has no Startup view in tests                                                  | `BufferingApplicationStartup` is only set in `main()`                                                                                            | Expected                                                                                                                 |
| Changed `orders.cache.refresh-interval` + `/actuator/refresh`, interval unchanged | `@Scheduled(fixedRateString=…)` is resolved once when the task is registered; refresh only re-binds `@ConfigurationProperties` / `@RefreshScope` | Restart the app (SBA "Restart" works)                                                                                    |
| Deleted a row but `GET /api/orders/{id}` still returns it                         | Cache entry lives until its 10 m TTL after the last refresh                                                                                      | Wait ≤ 10 min, or clear the `orders` cache via SBA / `DELETE /actuator/caches/orders`                                    |

<a id="qa"></a>

## <span style="color:hsl(350,80%,62%)">10. ❓ Q&A</span>

<a id="qa-sba-call-options"></a>

**Q1. What are the different ways Spring Boot Admin can call `order-service`, and what does `order-service` need for each?**

- ***SBA always pulls.*** admin-server polls `/actuator/health` (every 10 s), reads `/actuator/info`, and proxies every
  UI view through itself (`/instances/{id}/actuator/**`), so the browser never calls `order-service` directly.
- ***Two choices vary:*** how SBA *learns* the actuator URL (A) and how it *authenticates* the call (B).

***A. How SBA finds `order-service`***

| # | Way                                       | Who knows the URL                     | Used here |
|---|-------------------------------------------|---------------------------------------|-----------|
| 1 | Eureka discovery                          | Eureka registry, read by admin-server | **yes**   |
| 2 | SBA client (push)                         | `order-service` registers itself with SBA   | no        |
| 3 | Static list (`SimpleDiscoveryClient`)     | admin-server's `application.yml`      | no        |
| 4 | Kubernetes discovery                      | Kubernetes API (Services/Endpoints)   | no        |

**A1. Eureka discovery (current setup).** No SBA dependency in `order-service`; SBA turns each Eureka instance into an SBA
instance.

```yaml
# order-service/src/main/resources/application.yml
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

**A2. SBA client (push).** `order-service` POSTs its own URLs to admin-server at startup and DELETEs on shutdown. Useful
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
          # service-base-url: http://order-service.example:8082   # override when the auto-detected URL is unreachable (NAT, containers)
          metadata:
            user.name: ${spring.security.user.name}       # credentials SBA sends BACK to order-service's actuator
            user.password: ${spring.security.user.password}
```

- admin-server must permit `POST /instances` with Basic and exclude it from CSRF.
- Don't combine with A1: the same app shows up twice (one entry per source).

**A3. Static list on admin-server.** `order-service` needs nothing SBA-specific, only exposed and secured actuator; its URL
and credentials live in admin-server's config:

```yaml
# admin-server/src/main/resources/application.yml
spring:
  cloud:
    discovery:
      client:
        simple:
          instances:
            order-service:
              - uri: http://localhost:8082
                metadata:
                  user.name: admin
                  user.password: admin
```

- No health-based registration: a stopped instance stays listed as `OFFLINE` until you edit the list.

**A4. Kubernetes.** admin-server uses `spring-cloud-starter-kubernetes-client` instead of Eureka. `order-service` needs a
Kubernetes `Service`; name a separate actuator port `management` in the Service's ports, and put `user.name` /
`tags.*` style keys in the Service's labels/annotations (Spring Cloud Kubernetes copies them into the instance metadata).

***B. How SBA authenticates to `order-service`'s actuator***

| #  | Method                     | `order-service` side                                                                      | admin-server side                                                                                                                                   |
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
