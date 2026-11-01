# <span style="color:hsl(201,80%,58%)">eureka-server</span>

## <span style="color:hsl(151,80%,58%)">Table of contents</span>

1. 🧭 [Overview](#overview)
2. 🚀 [Run it](#run-it)
3. 🔐 [Security](#security)
4. 📊 [Actuator endpoints and SBA views](#actuator-endpoints)
5. ⏱️ [Quartz demo job, file logging, configuration](#quartz-logging-config)
6. 🧪 [Tests](#tests)
7. ⚠️ [Gotchas](#gotchas)
8. ❓ [Q&A](#qa)
    - 8.1 [What is self-preservation mode in Eureka?](#qa-self-preservation)
    - 8.2 🔥 [Lease expiry is 30 s and the sweep runs every 30 s — why can a crashed instance stay in this registry for ~90 s, and in callers' caches for ~3 min?](#qa-crash-eviction-timeline)
    - 8.3 🔥 [Self-preservation off, 5 of 9 instances killed at once — why don't all 5 disappear at the next eviction sweep?](#qa-eviction-cap)
    - 8.4 🔥 [A client registers with `peer1`, which dies before replicating — what do `peer2` and the client see, and how does the cluster heal?](#qa-lost-replication)
    - 8.5 🔥🔥 [All registry nodes are down for 10 minutes, then come back cold at the same moment — what do clients, the gateway and SBA experience, and how would you design the recovery?](#qa-full-outage-cold-restart)
    - 8.6 🔥🔥 [Scale this registry to 2,000 instances on 3 nodes with today's 10 s heartbeats — estimate the load, find the bottleneck, decide what to tune](#qa-registry-capacity)

Service registry for the [learning-spring-cloud](../README.md) demo, run as a cluster of two or three replicating nodes
from this one module: the Spring profile (`peer1`, `peer2`, `peer3`) picks the node. Every other
module (`user-service`, `order-service`, `product-service`, `gateway-service`, `admin-server`) registers here. For how
the gateway resolves `lb://` IDs against this registry, how Spring Boot Admin discovers instances, and the FAQ on
AP/CAP, self-preservation and eviction timing, see the [root README](../README.md#faq).

<a id="overview"></a>

## <span style="color:hsl(288,80%,58%)">1. 🧭 Overview</span>

| Item                                | Value                                                                                                                                                                                                                                     |
|-------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Role                                | Netflix Eureka ***service registry*** + dashboard                                                                                                                                                                                         |
| Port                                | `8761` / `8762` / `8763` for profile `peer1` / `peer2` / `peer3` ([`application.yml`](src/main/resources/application.yml) `server.port`)                                                                                                  |
| Web stack                           | Servlet (Spring MVC / Tomcat)                                                                                                                                                                                                             |
| Stack (as of 2026)                  | Spring Boot 4.1.1, Spring Cloud 2025.1.x (banner says 2025.1.2), Java 27                                                                                                                                                                  |
| Main class                          | [`EurekaServerApplication`](src/main/java/com/learning/discovery/eureka/EurekaServerApplication.java) — `@EnableEurekaServer` + `@SpringBootApplication`                                                                                  |
| Dependencies ([`pom.xml`](pom.xml)) | `spring-cloud-starter-netflix-eureka-server`, `spring-boot-starter-actuator`, `spring-boot-starter-security`, `spring-boot-starter-quartz`, `jolokia-support-springboot` 2.6.3; tests: `spring-boot-starter-test`, `spring-security-test` |

- ***One module, one profile per node*** — `peer1` (:8761, hostname `localhost`), `peer2` (:8762, `127.0.0.1`),
  `peer3` (:8763, `127.0.0.2`). Each profile section of [`application.yml`](src/main/resources/application.yml) sets
  only `server.port` and `eureka.instance.hostname`; everything else is shared. `peer1` is also the default: its section
  is activated by `peer1 | !(peer2 | peer3)`, so a plain run (IDE, `spring-boot:run`, tests, `prod` alone) is node 1.
  All nodes share one `defaultZone` listing the three; every register/heartbeat/cancel a node receives is replicated to
  the other two. Details in [Run a cluster](#run-cluster).
- ***Registers with the cluster*** — `eureka.client.register-with-eureka: true` so `admin-server` (which discovers apps
  *from* Eureka) lists `EUREKA-SERVER` (one instance per running node) alongside the other services. All nodes keep
  `spring.application.name: eureka-server`: the dashboard counts a peer as `available-replicas` only if it finds it
  among *its own* app's instances.
- ***Fetch the registry*** — `fetch-registry: true`: on startup a node copies the registry its own Eureka client fetched
  from a peer — without it a restarted node comes up empty. Spring Cloud enables that copy
  (`eureka.server.registry-sync-retries: 5`) whenever `register-with-eureka` is true.
- ***Self-preservation: on*** — `enable-self-preservation: ${EUREKA_SELF_PRESERVATION:true}`, so the dashboard no longer
  shows `THE SELF PRESERVATION MODE IS TURNED OFF...` and a network blip can't mass-evict the registry. Threshold
  `EUREKA_RENEWAL_THRESHOLD` defaults to `0.5` locally (killing one or two services in a ~8-instance demo still evicts
  them) and `0.85` under the `prod` profile (the `on-profile: prod` section of [application.yml](src/main/resources/application.yml)). Tradeoffs
  in the root [FAQ](../README.md#faq).
- ***Faster leases (all modules)*** — clients heartbeat every 10 s and expire 30 s after the last one
  (`lease-renewal-interval-in-seconds` / `lease-expiration-duration-in-seconds`, defaults 30 / 90); the server matches
  with `expected-client-renewal-interval-seconds: 10` (so the self-preservation threshold is computed correctly), sweeps
  every 30 s.
- ***Dashboard header*** — `eureka.environment: ${EUREKA_ENVIRONMENT:sit}` and
  `eureka.datacenter: ${EUREKA_DATACENTER:TX}` replace the defaults `test` / `default` shown as "Environment" / "Data
  center" on the dashboard. Labels only — they change no registry behaviour.
- ***No other eviction tuning*** — lease renewal/expiry and the eviction timer run on Eureka defaults; only
  self-preservation is overridden.
- ***Hostname*** — `peer1` is `localhost`, `peer2` `127.0.0.1`, `peer3` `127.0.0.2`: nodes need distinct hostnames,
  and all three are loopback without an `/etc/hosts` entry. Linux and Windows route all of `127.0.0.0/8` to loopback;
  on macOS run `sudo ifconfig lo0 alias 127.0.0.2` once before starting `peer3`.
- ***Startup banner*** — [`banner.txt`](src/main/resources/banner.txt) prints project metadata and the dashboard URL.

<a id="run-it"></a>

## <span style="color:hsl(66,80%,50%)">2. 🚀 Run it</span>

Start it ***first*** — every other module needs somewhere to register (full order in the
root [Run order](../README.md#run-order)).

```bash
mvn -pl eureka-server spring-boot:run -Dspring-boot.run.profiles=peer1 &   # :8761, also the default
# once it is up (it builds eureka-server/target, which the other nodes reuse):
mvn -pl eureka-server spring-boot:run -Dspring-boot.run.profiles=peer2 &   # :8762
mvn -pl eureka-server spring-boot:run -Dspring-boot.run.profiles=peer3 &   # :8763, optional
# or with custom credentials (same on every node, and on every client)
ACTUATOR_USER=ops ACTUATOR_PASSWORD=s3cret mvn -pl eureka-server spring-boot:run -Dspring-boot.run.profiles=peer1
```

| What              | URL                                            | Auth                                          |
|-------------------|------------------------------------------------|-----------------------------------------------|
| Dashboard         | http://localhost:8761/                         | `admin`/`admin` (browser login form or Basic) |
| Registry REST API | http://localhost:8761/eureka/apps              | Basic                                         |
| One app           | http://localhost:8761/eureka/apps/USER-SERVICE | Basic                                         |
| Health            | http://localhost:8761/actuator/health          | public                                        |
| Info              | http://localhost:8761/actuator/info            | public                                        |

```bash
curl -u admin:admin -H 'Accept: application/json' http://localhost:8761/eureka/apps
curl http://localhost:8761/actuator/health
```

<a id="run-cluster"></a>

### Run a cluster (2 or 3 nodes)

| Profile | Port | `eureka.instance.hostname` | Dashboard             | Log file                      |
|---------|------|----------------------------|-----------------------|-------------------------------|
| `peer1` | 8761 | `localhost`                | http://localhost:8761 | `logs/eureka-server-8761.log` |
| `peer2` | 8762 | `127.0.0.1`                | http://localhost:8762 | `logs/eureka-server-8762.log` |
| `peer3` | 8763 | `127.0.0.2`                | http://localhost:8763 | `logs/eureka-server-8763.log` |

- ***Pick the node per process*** — `-Dspring-boot.run.profiles=peer2` (Maven), `--spring.profiles.active=peer2` (jar),
  `SPRING_PROFILES_ACTIVE=peer2` (env), or *Active profiles* in an IntelliJ Spring Boot run configuration (one per
  node, module `eureka-server`). Add `prod` as `peer2,prod`; no peer profile at all means `peer1`.
- ***One `target/` for all nodes*** — three `spring-boot:run` started at the same moment compile into the same
  `eureka-server/target`; start `peer1` first, or build once and run the jar:

```bash
mvn -pl eureka-server package -DskipTests
java -jar eureka-server/target/eureka-server-0.0.1-SNAPSHOT.jar --spring.profiles.active=peer1 &
java -jar eureka-server/target/eureka-server-0.0.1-SNAPSHOT.jar --spring.profiles.active=peer2 &
java -jar eureka-server/target/eureka-server-0.0.1-SNAPSHOT.jar --spring.profiles.active=peer3 &
```

- ***Every node lists all three*** — the shared `defaultZone` is
  `...@localhost:8761/eureka/,...@127.0.0.1:8762/eureka/,...@127.0.0.2:8763/eureka/`. For replication a node drops the
  entry whose host is its own hostname (log line `Adding new peer nodes [...]` shows the other two); its own Eureka
  client may register with any of the three, itself included.
- ***Clients list all three*** — every client module's `defaultZone` is the same list. Eureka 2.x shuffles it per
  client, so clients spread over the nodes; each sticks with one and fails over to the next on errors.
  `zipkin-server` takes a single URI only, so it points at `peer1` (`localhost:8761`).
- ***Check replication*** — each dashboard's *DS Replicas* shows the other nodes, and *General Info* lists them under
  `available-replicas`; an app that registers with one node appears on all; `EUREKA-SERVER` shows one instance per
  running node.
- ***Two nodes are enough*** — `peer1` + `peer2` give HA too; `peer3` is then a configured node that's down: both
  dashboards list it under `unavailable-replicas`, and each running node logs
  `Network level connection to peer 127.0.0.2; retrying after delay` (with a `Connection refused` stack trace) every
  10 s. Starting `peer3` ends it. Same for any node you stop.
- ***Startup*** — a node copies the registry from a peer that's already up (`Got N instances from neighboring DS node`),
  so a restarted node is complete from its first request. On a cold start no peer is up yet: the nodes log
  `Connection refused` for each other, the copy retries after 30 s (`registry-sync-retry-wait-ms`), and
  `Started Eureka Server` comes ~30 s after `Tomcat started`; the node serves requests meanwhile.
- ***Log files*** — `logs/eureka-server-8761.log`, `-8762.log`, `-8763.log` (`logging.file.name` is named after the
  port, so nodes never share a file).

- ***Credentials*** — `spring.security.user.name`/`password` default to `admin`/`admin`, overridable via env vars
  `ACTUATOR_USER` / `ACTUATOR_PASSWORD`. The user gets role `ACTUATOR`.
- ***Clients must use the same values*** — every module shares this one user, so if you override them here, override
  them for all modules.

<a id="security"></a>

## <span style="color:hsl(118,80%,58%)">3. 🔐 Security</span>

Defined in [`SecurityConfig`](src/main/java/com/learning/discovery/eureka/config/SecurityConfig.java) as a single
servlet `SecurityFilterChain`:

```java
http
    .authorizeHttpRequests(auth -> auth
        .requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/info", "/error").permitAll()
        .anyRequest().authenticated())
    .httpBasic(Customizer.withDefaults())
    .formLogin(Customizer.withDefaults())
    .csrf(csrf -> csrf.ignoringRequestMatchers("/eureka/**", "/actuator/**"))
    .build();
```

| Rule            | Paths                                                                   | Why                                                                        |
|-----------------|-------------------------------------------------------------------------|----------------------------------------------------------------------------|
| `permitAll`     | `/actuator/health`, `/actuator/health/**`, `/actuator/info`, `/error`   | Liveness/readiness probes and error pages work without credentials         |
| `authenticated` | everything else — dashboard `/`, `/eureka/**`, other actuator endpoints | Registry and ops endpoints are not public                                  |
| HTTP Basic      | all                                                                     | Machine clients (Eureka clients, SBA, `curl`)                              |
| Form login      | all                                                                     | Browser hitting the dashboard gets Spring Security's default `/login` page |
| CSRF ignored    | `/eureka/**`, `/actuator/**`                                            | See below                                                                  |

- ***Why CSRF is off for `/eureka/**`*** — Eureka clients `POST` (register), `PUT` (heartbeat/status) and `DELETE`
  (deregister) against the REST API with Basic auth only; they never fetch a CSRF token, so with CSRF on every write
  would be rejected with 403 and nothing could register.
- ***Why CSRF is off for `/actuator/**`*** — SBA invokes write operations (`POST /actuator/shutdown`, `/restart`,
  `/refresh`, `/env`, `/loggers/...`, Jolokia) server-to-server with Basic auth and no token.
- ***CSRF stays on for the dashboard/form-login paths*** — the browser session flow keeps normal protection.
- ***Clients authenticate via `defaultZone`*** — credentials go in the URL userinfo; the Eureka client turns them into a
  Basic header:

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://admin:admin@localhost:8761/eureka/
```

- ***The nodes use the same trick among themselves*** — the shared `defaultZone` embeds
  `${spring.security.user.name}:${spring.security.user.password}` in all three URLs, for a node's own registration and
  for replication to its peers alike.
- ***SBA credentials via metadata*** — `eureka.instance.metadata-map.user.name` / `user.password` publish the actuator
  credentials in the registry entry; `admin-server` reads them and uses them as Basic auth when polling `/actuator/**`
  (fallback: `spring.boot.admin.instance-auth.default-*` — root [§10](../README.md#security-and-sba-features)).

```mermaid
sequenceDiagram
    participant Client as Eureka client (e.g. user-service)
    participant Eureka as eureka-server :8761
    participant SBA as admin-server :8084

    Client->>Eureka: POST /eureka/apps/USER-SERVICE (Basic admin:admin, no CSRF token)
    Eureka-->>Client: 204 registered
    loop every 30s
        Client->>Eureka: PUT /eureka/apps/USER-SERVICE/{instanceId} (heartbeat)
        Eureka-->>Client: 200 lease renewed
    end
    SBA->>Eureka: GET /eureka/apps (Basic)
    Eureka-->>SBA: instances + metadata (user.name, user.password)
    SBA->>Eureka: GET /actuator/** on eureka-server itself (Basic from metadata)
    SBA->>Client: GET /actuator/** (Basic from that instance's metadata)
    Note over Client,Eureka: Without credentials in defaultZone: 401, client never appears
```

<a id="actuator-endpoints"></a>

## <span style="color:hsl(256,80%,58%)">4. 📊 Actuator endpoints and SBA views</span>

All exposed under `/actuator/` via `management.endpoints.web.exposure.include`; all except `health` and `info` require
Basic auth. `health.show-details: always`.

| Endpoint        | SBA view / action                  | Backing config in this module                                                                                                                                                                      |
|-----------------|------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `health`        | Health (details shown)             | `management.endpoint.health.show-details: always`; public                                                                                                                                          |
| `info`          | Info                               | public                                                                                                                                                                                             |
| `heapdump`      | Heap Dump                          | `management.endpoint.heapdump.access: unrestricted` (Boot 3.5+ defaults to `none`, as of 2026)                                                                                                     |
| `logfile`       | Logfile                            | `logging.file.name: logs/${spring.application.name}-${server.port}.log` (one file per node)                                                                                                        |
| `env`           | Environment                        | exposed; `management.endpoint.env.post.enabled: true` enables the SBA "Update" form (POST, Spring Cloud Context)                                                                                   |
| `shutdown`      | Shutdown button                    | `management.endpoint.shutdown.access: unrestricted`                                                                                                                                                |
| `restart`       | Restart button                     | `management.endpoint.restart.access: unrestricted` (Spring Cloud Context)                                                                                                                          |
| `refresh`       | Environment "Refresh"              | `management.endpoint.refresh.access: unrestricted` — rebinds `@ConfigurationProperties` / `@RefreshScope`                                                                                          |
| `metrics`       | Metrics / Details                  | exposed, Micrometer defaults                                                                                                                                                                       |
| `loggers`       | Loggers (change levels at runtime) | exposed                                                                                                                                                                                            |
| `threaddump`    | Threads                            | exposed                                                                                                                                                                                            |
| `configprops`   | Configuration Properties           | exposed                                                                                                                                                                                            |
| `beans`         | Beans                              | exposed                                                                                                                                                                                            |
| `mappings`      | Mappings                           | exposed                                                                                                                                                                                            |
| `httpexchanges` | HTTP Exchanges                     | `InMemoryHttpExchangeRepository` bean in [`ActuatorConfig`](src/main/java/com/learning/discovery/eureka/config/ActuatorConfig.java)                                                                |
| `startup`       | Startup                            | `BufferingApplicationStartup(2048)` set in `main()` of [`EurekaServerApplication`](src/main/java/com/learning/discovery/eureka/EurekaServerApplication.java) — ***only when launched via `main`*** |
| `auditevents`   | Audit Events                       | `InMemoryAuditEventRepository` bean in [`ActuatorConfig`](src/main/java/com/learning/discovery/eureka/config/ActuatorConfig.java) + Spring Security authentication events                          |
| `sbom`          | SBOM                               | root [`pom.xml`](../pom.xml) `cyclonedx-maven-plugin` writes `META-INF/sbom/application.cdx.json` at build time                                                                                    |
| `quartz`        | Quartz                             | `spring-boot-starter-quartz`, in-memory store, demo `HeartbeatJob` (§5)                                                                                                                            |
| `jolokia`       | JMX                                | `jolokia-support-springboot` 2.6.3 (servlet only, supports Boot 4 as of 2026)                                                                                                                      |

- ***`shutdown`, `restart`, `env` POST and Jolokia mutate the running registry*** — acceptable behind Basic auth on
  localhost; never expose them on a public port.

### Health probes, info and tags

- ***Probes*** — `management.endpoint.health.probes.enabled: true` adds public `/actuator/health/liveness` and
  `/actuator/health/readiness` (SBA Details → Health groups).
- ***Info*** — `info.app.*` (name, description, `tier: infra`, environment) plus the `java`, `os`, `process`
  contributors (`management.info.*.enabled`) → SBA Details → Info.
- ***Tags*** — `eureka.instance.metadata-map.tags.environment` (`APP_ENV`, default `local`) and `tags.tier: infra` →
  badges in SBA's application list.
- Custom health indicators and metrics live in [order-service](../order-service/README.md) only.

<a id="quartz-logging-config"></a>

## <span style="color:hsl(33,80%,58%)">5. ⏱️ Quartz demo job, file logging, configuration</span>

**Quartz** — exists only so the SBA Quartz view has data:

| Piece       | File                                                                                   | Detail                                                                                                       |
|-------------|----------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|
| Job         | [`HeartbeatJob`](src/main/java/com/learning/discovery/eureka/job/HeartbeatJob.java)    | `QuartzJobBean`; logs `Heartbeat from eureka-server (next fire: ...)`                                        |
| `JobDetail` | [`QuartzConfig`](src/main/java/com/learning/discovery/eureka/config/QuartzConfig.java) | identity `heartbeatJob` / group `eureka`, description "Logs a heartbeat line every minute", `storeDurably()` |
| `Trigger`   | [`QuartzConfig`](src/main/java/com/learning/discovery/eureka/config/QuartzConfig.java) | identity `heartbeatTrigger` / group `eureka`, `SimpleScheduleBuilder.repeatMinutelyForever()`                |
| Store       | [`application.yml`](src/main/resources/application.yml)                                | `spring.quartz.job-store-type: memory` (RAMJobStore)                                                         |

- ***No scheduler wiring code*** — Boot auto-registers every `JobDetail` and `Trigger` bean with the auto-configured
  scheduler.
- ***Not to be confused with Eureka client heartbeats*** — this job only writes a log line.

**File logging** — `logging.file.name: logs/${spring.application.name}-${server.port}.log` (one file per node:
`eureka-server-8761.log`, ...; relative to the working directory) is what switches on `/actuator/logfile`.

**Annotated [`application.yml`](src/main/resources/application.yml) excerpt:**

```yaml
spring:
  application:
    name: eureka-server
  security:
    user:
      name: ${ACTUATOR_USER:admin}          # shared demo user
      password: ${ACTUATOR_PASSWORD:admin}
      roles: ACTUATOR
  quartz:
    job-store-type: memory                  # RAMJobStore for HeartbeatJob

eureka:
  instance:
    metadata-map:                           # SBA reads these to poll /actuator
      user.name: ${spring.security.user.name}
      user.password: ${spring.security.user.password}
  client:
    register-with-eureka: true              # appear in SBA; also turns on the startup registry copy
    fetch-registry: true                    # what that copy reads
    service-url:                            # the whole cluster (credentials shortened here)
      defaultZone: http://${…}@localhost:8761/eureka/,http://${…}@127.0.0.1:8762/eureka/,http://${…}@127.0.0.2:8763/eureka/
  server:
    enable-self-preservation: true          # no mass eviction on a network blip; threshold 0.5 locally

logging:
  file:
    name: logs/${spring.application.name}-${server.port}.log   # one per node, backs /actuator/logfile

management:
  endpoint:
    shutdown: { access: unrestricted }
    restart:  { access: unrestricted }
    refresh:  { access: unrestricted }
    env:      { post: { enabled: true } }
    health:   { show-details: always }
    heapdump: { access: unrestricted }

---
spring.config.activate.on-profile: "peer1 | !(peer2 | peer3)"   # node 1, and the default
server.port: 8761
eureka.instance.hostname: localhost
---
spring.config.activate.on-profile: peer2
server.port: 8762
eureka.instance.hostname: 127.0.0.1
---
spring.config.activate.on-profile: peer3
server.port: 8763
eureka.instance.hostname: 127.0.0.2
```

<a id="tests"></a>

## <span style="color:hsl(171,80%,58%)">6. 🧪 Tests</span>

[`EurekaServerApplicationTests`](src/test/java/com/learning/discovery/eureka/EurekaServerApplicationTests.java) —
`@SpringBootTest(webEnvironment = RANDOM_PORT)` driven by `RestTestClient` against the real embedded server.

| Test                                     | Checks                                                                                                           |
|------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| `contextLoads`                           | `@EnableEurekaServer` context boots without errors                                                               |
| `dashboardIsServedAtRootWithCredentials` | `GET /` with Basic `admin:admin` → 2xx and body contains `Eureka`                                                |
| `healthEndpointIsPublicAndReportsUp`     | `GET /actuator/health` without auth → 200, `$.status == "UP"`                                                    |
| `registryApiRejectsAnonymousCallers`     | `GET /eureka/apps` without auth → 401                                                                            |
| `registryApiAcceptsBasicAuth`            | `GET /eureka/apps` with Basic → 200                                                                              |
| `sbaEndpointsAreExposedBehindAuth`       | `/actuator/quartz` anonymous → 401; `quartz`, `httpexchanges`, `auditevents`, `jolokia`, `sbom` with Basic → 200 |

[`PeerProfilesTests`](src/test/java/com/learning/discovery/eureka/PeerProfilesTests.java) — loads `application.yml`
only (`ConfigDataEnvironmentPostProcessor.applyTo`, no server) and checks which node each profile combination resolves
to: port, `eureka.instance.hostname` and log file for no profile, `peer1`, `peer2`, `peer3`, `prod` and `peer3,prod`.

- ***Why `register-with-eureka=false` and `fetch-registry=false` in the test*** — `defaultZone` lists the other nodes,
  which aren't running during the test; registering or fetching would just spam connection errors. The test boots
  the node standalone.
- ***Why `startup` isn't tested*** — `BufferingApplicationStartup` is installed only in `main()`; `@SpringBootTest`
  doesn't run `main`, so the endpoint isn't there.

```bash
mvn -pl eureka-server test
```

<a id="gotchas"></a>

## <span style="color:hsl(308,80%,58%)">7. ⚠️ Gotchas</span>

- ***Start it first*** — clients started before it log connection-refused retries and only appear once they retry
  successfully.
- ***Clients get 401 without credentials*** — a `defaultZone` of `http://localhost:8761/eureka/` (no `user:pass@`) fails
  registration silently from the dashboard's point of view; the client logs 401s and never shows up.
- ***Mismatched credentials*** — overriding `ACTUATOR_USER`/`ACTUATOR_PASSWORD` here without doing the same on the
  clients has the same 401 effect.
- ***Shutting it down*** — `POST /actuator/shutdown` (or the SBA button) kills the registry: clients keep serving from
  their cached registry copy, but new registrations and heartbeats fail and `admin-server` loses discovery until it is
  back.
- ***Password visible in metadata*** — `metadata-map.user.password` is readable in plain text by anyone who can
  `GET /eureka/apps` (and in the dashboard). Fine for a local demo; use per-service secrets or SBA's `instance-auth` in
  anything real.
- ***Self-preservation is on*** — a crashed (not gracefully stopped) instance lingers if renewals drop below the
  threshold; the dashboard then shows `EMERGENCY! EUREKA MAY BE INCORRECTLY CLAIMING INSTANCES ARE UP...`. Graceful
  stops send a cancel and vanish immediately. Briefly after startup the same banner can appear until a full minute of
  heartbeats is counted.
- ***Heartbeat `400` / `Root name ('timestamp') does not match expected ('instance')`*** — a node that doesn't know an
  instance answers its heartbeat with `404`, and the client re-registers. Out of the box Jersey sends that 404 via
  `sendError()`, Spring Boot's `/error` page adds a JSON body, the client can't parse it as `InstanceInfo` and never
  re-registers. [`JerseyConfig`](src/main/java/com/learning/discovery/eureka/config/JerseyConfig.java) sets Jersey's
  `setStatusOverSendError` so the 404 stays bare (test `heartbeatForUnknownInstanceIsBare404`).
- ***Peers need distinct hostnames*** — Eureka compares only the host of a peer URL with its own
  `eureka.instance.hostname` (not the port); two nodes both named `localhost` treat each other as "me" and never
  replicate. Hence `localhost` / `127.0.0.1` / `127.0.0.2` — don't "tidy" them to the same name, and give a fourth
  node its own (e.g. `127.0.0.3`). The dashboard's *DS Replicas* list is keyed by host too.
- ***`logs/` is relative*** — the log file lands under whatever directory you launched from.

<a id="qa"></a>

## <span style="color:hsl(223,80%,58%)">8. ❓ Q&A</span>

<a id="qa-self-preservation"></a>

**Q1. What is self-preservation mode in Eureka?**

- ***A circuit breaker on eviction.*** When far fewer heartbeats arrive than expected, the server assumes the problem
  is ***the network between clients and the registry***, not that all those instances died at once — so it ***stops
  evicting expired leases*** and keeps serving the registry it has.
- ***Why:*** without it, a network partition would make every healthy instance miss its renewals, the server would
  evict them all, and clients (the gateway included) would fetch an empty registry and fail every call. A slightly
  stale registry is better than an empty one — the AP choice (see the root [FAQ](../README.md#faq-self-preservation)).
- ***When it trips:*** every minute the server compares ***Renews (last min)*** with ***Renews threshold*** (both on
  the dashboard):

```text
expected renews/min = registered instances × (60 / expected-client-renewal-interval-seconds)
renews threshold    = expected renews/min × renewal-percent-threshold
self-preservation   = renews (last min) < renews threshold
```

- ***With this module's numbers*** (10 s heartbeats, threshold `0.5`): 8 registered instances → 8 × 6 = 48 expected
  renews/min → threshold 24. It trips only once more than half the instances go silent at the same time; under the
  `prod` profile (`0.85`) losing about 2 of 8 is enough.

| | Self-preservation inactive | Self-preservation active |
|---|---|---|
| Lease expired (no heartbeat for `lease-expiration-duration-in-seconds`, 30 s here) | evicted at the next sweep (every 30 s here) | ***kept*** — shown as UP until renewals recover |
| Graceful shutdown (client sends a cancel) | removed at once | removed at once — cancels are always honoured |
| New registrations, heartbeats | processed | processed |
| Dashboard | normal | red `EMERGENCY! EUREKA MAY BE INCORRECTLY CLAIMING INSTANCES ARE UP WHEN THEY'RE NOT...` |

- ***It ends by itself*** — once renewals climb back above the threshold, eviction resumes and the zombie entries
  expire at the next sweep.
- ***Small clusters are the trap*** — with 2 instances, one crash is a 50 % drop, so at `0.85` the crashed instance is
  never evicted. That is why the local threshold here is `0.5`.
- ***False alarms after a restart*** — the first minute of renewals isn't counted in full yet, so the banner can show
  briefly; the threshold is also only recomputed on register/cancel and every
  `renewal-threshold-update-interval-ms` (default 15 min).
- ***Clients still have to cope with zombies*** — the registry may list a dead instance, so callers need timeouts and
  retries on another instance (Spring Cloud LoadBalancer retry, Resilience4j).
- ***Turning it off*** — `EUREKA_SELF_PRESERVATION=false` (`enable-self-preservation: false`): dead instances vanish
  within ~1 min, but one network blip can wipe the registry. Acceptable on a single dev machine, not in production.

<a id="qa-crash-eviction-timeline"></a>

**Q2. 🔥 Lease expiry is 30 s and the sweep runs every 30 s here — why can a crashed instance stay in this registry for
~90 s, and in callers' caches for ~3 minutes?**

- ***Because Eureka counts the lease duration twice.*** `Lease.renew()` stores `now + duration` as the last-update
  time, and `isExpired()` then adds `duration` again — so a 30 s lease really expires ***60 s*** after the last
  heartbeat. The Eureka authors document this as a known minor bug they won't fix (it only affects crashed,
  not gracefully stopped, instances; checked against eureka-core 2.0.6, as of 2026).
- ***Timeline for a crash right after a heartbeat*** (`lease-expiration-duration-in-seconds: 30`,
  `eviction-interval-timer-in-ms: 30000`, self-preservation not tripped):

| t (s)   | Event                                                                                                  |
|---------|--------------------------------------------------------------------------------------------------------|
| 0       | last heartbeat; stored last-update = 30                                                                |
| 60      | lease counts as expired (30 + 30)                                                                      |
| ≤ 90    | next eviction sweep removes it (sweeps every 30 s; a late sweep adds its lag as compensation)          |
| ≤ 120   | read-only response cache refreshed from the read-write cache (`response-cache-update-interval-ms`, 30 s) |
| ≤ 150   | caller's registry delta fetch (`registry-fetch-interval-seconds`, default 30 s)                        |
| ≤ ~185  | Spring Cloud LoadBalancer instance cache expires (`spring.cloud.loadbalancer.cache.ttl`, 35 s)         |

- ***Graceful stop is different*** — the cancel removes the lease at once and invalidates the read-write cache; only
  the last three rows apply. The root [FAQ 7.9](../README.md#faq-worst-case-detection) stacks the client-side layers;
  this is the server-side half.
- ***Levers*** — a shorter lease (more false evictions on GC pauses), `eureka.server.use-read-only-response-cache:
  false` (reads hit the read-write cache, which is invalidated on every change — fresher, slightly more CPU), shorter
  client fetch and LB TTL (more registry traffic). In every case ***callers still need a retry on another instance***
  — you can shrink the window, never close it.

<a id="qa-eviction-cap"></a>

**Q3. 🔥 With self-preservation off, you kill 5 of 9 registered instances at once — why don't all 5 disappear at the
next eviction sweep?**

- ***Each sweep evicts at most `registrySize − (int)(registrySize × renewal-percent-threshold)` leases***, picked at
  random — independent of whether self-preservation is enabled.
- ***Here*** — threshold `0.5`, 9 instances: `9 − (int)(4.5) = 9 − 4 = 5` → all 5 can go in one sweep. Under `prod`
  (`0.85`): `9 − (int)(7.65) = 9 − 7 = 2` → 2 random ones per 30 s sweep, the rest linger for later sweeps (and the
  registry size shrinks each time, so the cap shrinks too).
- ***Why it exists*** — it is a second safety net against clock jumps or a long GC pause on the server itself:
  after a 2-minute stop-the-world pause every lease looks expired, and without the cap the first sweep would wipe
  the registry even with self-preservation off.
- ***Interview trap*** — "self-preservation off = everything expired is evicted at once" is wrong; the eviction cap
  and self-preservation are separate mechanisms that share the same threshold property.
- ***Consequence*** — after a large failure, zombie entries drain over several sweeps; callers must tolerate dead
  entries (timeouts, retry on the next instance) for minutes, not seconds.
- ***Watch*** — the dashboard's instance list against what is actually running, and the server log's eviction lines
  (`Evicting N items (expired=…, evictionLimit=…)`) to see the cap biting.

<a id="qa-lost-replication"></a>

**Q4. 🔥 A client registers with `peer1`, which dies before replicating it to `peer2`. What do `peer2` and the client
see, and how does the cluster heal?**

***It heals through the next heartbeat***: the client fails over to another node in its `defaultZone`, renews there,
gets `404` for an unknown instance, and re-registers — Eureka's registry is soft state rebuilt from heartbeats, not
from a replicated log.

- ***Replication is asynchronous and best-effort*** — `peer1` acknowledges the client first, then pushes the
  register to its peers in batches (marked as replication so peers don't re-replicate). A crash in between loses it;
  there is no quorum or write-ahead log.
- ***Between crash and heal*** — `peer2` doesn't list the instance; callers fetching from `peer2` (clients shuffle
  `defaultZone`, so roughly a third to a half of them) can't reach it — for at most one heartbeat interval (10 s here)
  plus their own cache layers.
- ***The healing step depends on a bare 404*** — the client only re-registers if it can read the renew response as a
  plain `404`. That's exactly what [`JerseyConfig`](src/main/java/com/learning/discovery/eureka/config/JerseyConfig.java)
  guarantees; without it the client logs a parse error and stays unregistered on that node (see [Gotchas](#gotchas)).
- ***Peer-to-peer variant*** — if a ***replicated*** heartbeat reaches a peer that doesn't know the instance, the peer
  answers `404` and the sender replicates a full register. If the peer holds a ***newer*** copy (higher
  `lastDirtyTimestamp`), it answers with a conflict and the sender takes the peer's version.
- ***Conflict rule on register*** — when a registration arrives for an instance the node already has, the copy with
  the newer `lastDirtyTimestamp` wins, so a late, stale replication can't overwrite fresher state.
- ***Why accept this*** — that's the AP trade ([root FAQ](../README.md#faq-eureka-ap)): writes never block on peers,
  and every client re-asserts its own state every 10 s, so any lost write is bounded by one renewal interval.

<a id="qa-full-outage-cold-restart"></a>

**Q5. 🔥🔥 All registry nodes are down for 10 minutes, then come back cold at the same moment. What do clients, the
gateway and SBA experience during and after, and how would you design the recovery?**

***During the outage, traffic between already-known instances keeps flowing from client caches; after a cold restart
the registry rebuilds itself from heartbeats within about one renewal interval — the dangerous part is the moment a
node serves a partial registry.***

- ***During the outage***
  - ***Clients keep their last good registry*** — a failed fetch doesn't clear the cache, so `lb://USER-SERVICE` and
    `UserClient` keep resolving known instances.
  - ***Nothing new is visible*** — instances started during the outage can't register; scaling out does nothing
    for callers. Instances that die stay in every cache, so errors grow over the 10 minutes.
  - ***`admin-server`*** can't discover anything new (see [Gotchas](#gotchas)); `zipkin-server` registers with
    `peer1` only.
- ***Cold restart***
  1. Each node tries to copy the registry from a peer (`registry-sync-retries` 5, `registry-sync-retry-wait-ms`
     30 s — the ~30 s gap described in [Run a cluster](#run-cluster)). All peers are empty or down → it gets 0
     instances.
  2. A node that synced ***0 instances*** refuses registry ***reads*** (`403`) for `wait-time-in-ms-when-sync-empty`
     (default 5 min, as of 2026) — this protects clients from replacing a good cache with an empty one. Writes
     (register, renew) are accepted.
  3. Clients' next heartbeats get `404` → they re-register (bare 404 again, thanks to `JerseyConfig`). Within
     ~10 s of the nodes being up, the registry is complete again; replication spreads the registrations.
  4. Self-preservation's expected-renewal count starts from what was synced and grows with each register, so the
     threshold is briefly meaningless — don't read anything into banners in the first minutes.
- ***The trap: a partial registry*** — if one node comes up first and collects a few registrations, a node starting
  a moment later syncs ***a non-zero but incomplete*** registry, skips the 5-minute read block, and serves it.
  Clients fetching from it replace their full cache with the partial one → instances vanish from routing even though
  they're healthy.
- ***Design***:
  - Never restart all nodes together — rolling restarts, so every node syncs from a complete peer (registry intact).
  - Keep `wait-time-in-ms-when-sync-empty` at its default; consider a readiness gate in front of the nodes (if they
    sit behind a load balancer) that only opens once renews per minute approach the pre-outage value.
  - Clients: list every node in `defaultZone` (done here, except `zipkin-server`) so failover is automatic.
  - Callers: retries on another instance + circuit breakers, because during and after the outage caches are stale in
    both directions.
- ***Monitor*** — renews per minute vs the pre-incident baseline, registered instance count per node (they should
  converge), `403` rate on `/eureka/apps`, gateway 5xx.
- ***Alternative*** — a CP registry (Consul, etcd-backed Kubernetes Services) keeps state across restarts but refuses
  service without quorum; Eureka trades that for always answering. See [root FAQ](../README.md#faq-eureka-ap).

<a id="qa-registry-capacity"></a>

**Q6. 🔥🔥 Scale this registry to 2,000 instances on 3 nodes, keeping today's 10 s heartbeats. Estimate the load, find
the bottleneck, and decide what to tune.**

***Reads are cheap (served from the response cache); writes are the cost, because every heartbeat is replicated to
every other node — adding nodes adds read capacity, not write capacity.***

- ***Back-of-envelope*** (all numbers per cluster unless noted):

| Traffic                   | Formula                                     | Load                                            |
|---------------------------|---------------------------------------------|-------------------------------------------------|
| Client heartbeats         | 2,000 / 10 s                                | 200 req/s, spread over 3 nodes (~67/s each)     |
| Replicated heartbeats     | 200/s × (3 − 1) peers                       | 400 renewals/s, sent in batches                 |
| Delta fetches             | 2,000 / 30 s                                | ~67 req/s, mostly cache hits                    |
| Full fetch payload        | 2,000 × ~1–2 KB per instance (JSON + metadata) | ~2–4 MB per fetch before compression          |
| Mass client restart       | 2,000 full fetches                          | several GB in minutes — the real spike          |

- ***Bottlenecks, in order***
  1. ***Thundering herd of full fetches*** — a fleet-wide restart, or a delta hash mismatch on many clients, triggers
     full-registry downloads. Keep metadata small (today it carries `user.password`, tags and more on every
     instance).
  2. ***Replication fan-out*** — all-to-all; going from 3 to 5 nodes doubles replicated writes per node. Keep the
     cluster small (3) and scale reads with the response cache.
  3. ***Heartbeat rate*** — the 10 s interval is a demo choice for faster detection; at this size it triples write
     load for detection you then lose to caches anyway (see Q2).
- ***Tuning decision***
  - Heartbeats back to 30 s, lease 90 s (defaults) — and `expected-client-renewal-interval-seconds` back to 30 to
    keep the self-preservation maths right ([Q1](#qa-self-preservation)).
  - `renewal-percent-threshold` 0.85 (the `prod` profile): at 2,000 instances a 15 % drop is a meaningful signal,
    unlike in a 9-instance demo.
  - Keep the read-only response cache on and delta fetches on; jitter client start-up.
  - Size the server heap for the response cache (full payload × formats/encodings), not for the registry itself,
    which is only a few MB.
- ***When to stop scaling Eureka*** — beyond a single region or once the platform already has a registry (Kubernetes
  Services), the right move is the platform's discovery, not a bigger Eureka cluster (root
  [§8](../README.md#where-this-fits)).
- ***Monitor*** — renews per minute vs threshold, replication batch failures in the server log, `/eureka/apps`
  latency and response size, GC pauses on the nodes (a long pause looks like a mass expiry — [Q3](#qa-eviction-cap)).
