# <span style="color:hsl(201,80%,58%)">eureka-server</span>

## <span style="color:hsl(151,80%,58%)">Table of contents</span>

1. 🧭 [Overview](#overview)
2. 🚀 [Run it](#run-it)
3. 🔐 [Security](#security)
4. 🔒 [HTTPS and mutual TLS](#https)
5. 📊 [Actuator endpoints and SBA views](#actuator-endpoints)
6. ⏱️ [Quartz demo job, file logging, configuration](#quartz-logging-config)
7. 🔬 [Internals: eureka-core 2.0.6](#eureka-core-internals)
    - 7.1 [Package map](#core-1)
    - 7.2 [Design](#core-2)
    - 7.3 [Lease and timing](#core-3)
    - 7.4 [Register, replicate, read — sequence](#core-4)
    - 7.5 [Heartbeat and conflict resolution](#core-5)
    - 7.6 [Startup, sync and `shouldAllowAccess`](#core-6)
    - 7.7 [Self-preservation, the formula](#core-7)
    - 7.8 [Response cache](#core-8)
    - 7.9 [Replication engine](#core-9)
    - 7.10 [Tunables (Spring property → default → here)](#core-10)
    - 7.11 [Design patterns and principles in eureka-core](#core-11)
    - 7.12 [Tech stack: how Eureka and its dashboard are built](#core-12)
8. 🔬 [Internals: eureka-client 2.0.6](#eureka-client-internals)
    - 8.1 [Design](#client-1)
    - 8.2 [Threads and timers](#client-2)
    - 8.3 [Start, register, heartbeat, fetch — sequence](#client-3)
    - 8.4 [Where the TLS hooks sit](#client-4)
    - 8.5 [Design patterns and principles](#client-5)
9. 🧪 [Tests](#tests)
10. ⚠️ [Gotchas](#gotchas)
11. ❓ [Q&A](#qa)
    - 11.1 [What is self-preservation mode in Eureka?](#qa-self-preservation)
    - 11.2 🔥 [Lease expiry is 30 s and the sweep runs every 30 s — why can a crashed instance stay in this registry for ~90 s, and in callers' caches for ~3 min?](#qa-crash-eviction-timeline)
    - 11.3 🔥 [Self-preservation off, 5 of 9 instances killed at once — why don't all 5 disappear at the next eviction sweep?](#qa-eviction-cap)
    - 11.4 🔥 [A client registers with `peer1`, which dies before replicating — what do `peer2` and the client see, and how does the cluster heal?](#qa-lost-replication)
    - 11.5 🔥🔥 [All registry nodes are down for 10 minutes, then come back cold at the same moment — what do clients, the gateway and SBA experience, and how would you design the recovery?](#qa-full-outage-cold-restart)
    - 11.6 🔥🔥 [Scale this registry to 2,000 instances on 3 nodes with today's 10 s heartbeats — estimate the load, find the bottleneck, decide what to tune](#qa-registry-capacity)

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
| Stack (as of 2026)                  | Spring Boot 4.1.2, Spring Cloud 2025.1.x (banner says 2025.1.2), Java 27                                                                                                                                                                  |
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
- ***Dashboard header*** — `eureka.environment: ${EUREKA_ENVIRONMENT:SIT}` and
  `eureka.datacenter: ${EUREKA_DATACENTER:TX}` replace the defaults `test` / `default` shown as "Environment" / "Data
  center" on the dashboard. Labels only — they change no registry behaviour.
- ***No other eviction tuning*** — beyond the lease/sweep settings above and the self-preservation switch and
  threshold, eviction runs on Eureka defaults.
- ***Hostname*** — `peer1` is `localhost`, `peer2` `127.0.0.1`, `peer3` `127.0.0.2`: nodes need distinct hostnames,
  and all three are loopback without an `/etc/hosts` entry. Linux and Windows route all of `127.0.0.0/8` to loopback;
  on macOS run `sudo ifconfig lo0 alias 127.0.0.2` once before starting `peer3`.
- ***HTTPS only*** — every node serves TLS with the `service-identity` SSL bundle, registers as secure, and replicates to its
  peers over TLS; clients use `https://` in `defaultZone`. Run `./certs/generate-certs.sh` once before the first build
  ([§4](#https), root [§9](../README.md#https)).
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

| What              | URL                                             | Auth                                          |
|-------------------|-------------------------------------------------|-----------------------------------------------|
| Dashboard         | https://localhost:8761/                         | `admin`/`admin` (browser login form or Basic) |
| Registry REST API | https://localhost:8761/eureka/apps              | Basic                                         |
| One app           | https://localhost:8761/eureka/apps/USER-SERVICE | Basic                                         |
| Health            | https://localhost:8761/actuator/health          | public                                        |
| Info              | https://localhost:8761/actuator/info            | public                                        |

```bash
export CURL_CA_BUNDLE=certs/out/ca.crt   # dev CA: registry, order, product serve HTTPS (root README §9)
curl -u admin:admin -H 'Accept: application/json' https://localhost:8761/eureka/apps
curl https://localhost:8761/actuator/health
```

<a id="run-cluster"></a>

### Run a cluster (2 or 3 nodes)

| Profile | Port | `eureka.instance.hostname` | Dashboard              | Log file                      |
|---------|------|----------------------------|------------------------|-------------------------------|
| `peer1` | 8761 | `localhost`                | https://localhost:8761 | `logs/eureka-server-8761.log` |
| `peer2` | 8762 | `127.0.0.1`                | https://localhost:8762 | `logs/eureka-server-8762.log` |
| `peer3` | 8763 | `127.0.0.2`                | https://localhost:8763 | `logs/eureka-server-8763.log` |

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

- ***Every node lists all three*** — the shared `defaultZone` is built from `eureka.server.instances`:
  `...@localhost:8761/eureka/,...@127.0.0.1:8762/eureka/,...@127.0.0.2:8763/eureka/`. For replication a node drops the
  entry whose host is its own hostname (log line `Adding new peer nodes [...]` shows the other two); its own Eureka
  client may register with any of the three, itself included.
- ***Clients list all three*** — every client module builds `defaultZone` from the same `eureka.server.instances` list. Eureka 2.x shuffles it per
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
  (fallback: `spring.boot.admin.instance-auth.default-*` — root [§8](../README.md#security-and-sba-features)).

```mermaid
sequenceDiagram
    participant Client as Eureka client (e.g. user-service)
    participant Eureka as eureka-server :8761
    participant SBA as admin-server :8084

    Client->>Eureka: POST /eureka/apps/USER-SERVICE (Basic admin:admin, no CSRF token)
    Eureka-->>Client: 204 registered
    loop every 10s
        Client->>Eureka: PUT /eureka/apps/USER-SERVICE/{instanceId} (heartbeat)
        Eureka-->>Client: 200 lease renewed
    end
    SBA->>Eureka: GET /eureka/apps (Basic)
    Eureka-->>SBA: instances + metadata (user.name, user.password)
    SBA->>Eureka: GET /actuator/** on eureka-server itself (Basic from metadata)
    SBA->>Client: GET /actuator/** (Basic from that instance's metadata)
    Note over Client,Eureka: Without credentials in defaultZone: 401, client never appears
```

<a id="https"></a>

## <span style="color:hsl(45,80%,55%)">4. 🔒 HTTPS and mutual TLS</span>

Every node serves ***HTTPS only*** on its port (`server.ssl.bundle: ${app.tls.server-bundle}` → `service-identity`),
asks callers for a client certificate (`server.ssl.client-auth: want`), registers itself as secure
(`secure-port-enabled: true`, `non-secure-port-enabled: false`) and talks to its peers over mutual TLS with its own
certificate, `CN=eureka-server,OU=learning-spring-cloud`. The step-by-step conversion for the whole stack is in the
root [§9](../README.md#https); this is the registry's part.

| Path                                   | Client library                                   | How it gets the `service-identity` bundle                                               |
|----------------------------------------|--------------------------------------------------|----------------------------------------------------------------------------------------|
| Inbound: clients, peers, SBA, browser  | Tomcat                                           | `server.ssl.bundle` (`tls/identity.p12`, SAN `localhost`, `127.0.0.1`, `127.0.0.2`; client certificates verified against `tls/truststore.p12`) |
| Own Eureka client → peers (register, fetch) | Jersey 3 (`Jersey3TransportClientFactories`) | `Jersey3DiscoveryClientOptionalArgs.setSSLContext(...)` in [`EurekaTlsConfig`](src/main/java/com/learning/discovery/eureka/config/EurekaTlsConfig.java) |
| Peer replication (`/eureka/peerreplication/batch`) | Jersey 3 + Apache HttpClient 4 (`Jersey3ReplicationClient`) | custom `PeerEurekaNodes` (`TlsPeerEurekaNodes`) in the same class                |

- ***Who may call the registry: `ClientCertificateSubjectFilter`***: [the filter](src/main/java/com/learning/discovery/eureka/config/ClientCertificateSubjectFilter.java)
  (servlet, order `-110`, before Spring Security) checks the subject of the client certificate against
  `app.tls.client-cert.allowed-subjects`. All six services are on the list (every one of them registers), and so is
  `eureka-server` itself for the peers. A certificate with any other subject gets `403`. `/eureka/peerreplication/**`
  is in `required-paths`: a replication batch without a certificate gets `403 client certificate required`, so a
  leaked Basic password alone can no longer inject registrations through replication. Everything else accepts
  certificate-less callers: zipkin-server registers without one (Armeria's TLS client sends none), and browsers and
  curl send none either.

  ```bash
  export CURL_CA_BUNDLE=certs/out/ca.crt
  curl -u admin:admin --cert certs/out/dev-cli.crt --key certs/out/dev-cli.key https://localhost:8761/eureka/apps
  # 403 client certificate subject not allowed: CN=dev-cli,OU=learning-spring-cloud
  ```
- ***Why replication needs its own bean*** — Spring Cloud's `RefreshablePeerEurekaNodes.createReplicationClient`
  builds each peer client with `EurekaJersey3ClientBuilder`. Without the system property
  `com.netflix.eureka.shouldSSLConnectionsUseSystemSocketFactory=true` the builder creates a plain
  `PoolingHttpClientConnectionManager()`, whose HTTPS socket factory uses the JVM default trust store (cacerts). The
  only other knob, `withTrustStoreFile(path, password)`, wants a JKS file and switches hostname verification off.
  `TlsPeerEurekaNodes.createPeerEurekaNode` builds the same client by hand — Apache connector, peer-node timeouts and
  pool sizes, full JSON codec, `Jersey3DynamicGZIPContentEncodingFilter`, `EurekaIdentityHeaderFilter` — with
  `new SSLConnectionSocketFactory(sslContext)` (default hostname verifier) registered for `https`. The
  `SSLContext` comes from the client bundle, so its key manager presents this node's certificate to the peer.
- ***What you give up*** — `RefreshablePeerEurekaNodes` also re-resolves peers on an `EnvironmentChangeEvent`
  (`/actuator/refresh` after changing `defaultZone`); `TlsPeerEurekaNodes` doesn't. The periodic peer-list update of
  `PeerEurekaNodes.start()` (every `peer-eureka-nodes-update-interval-ms`, 10 min) still applies changes.
- ***Basic auth still in the URL*** — `Jersey3ReplicationClient` (via `AbstractJersey3EurekaHttpClient`) turns the
  `admin:admin@` user-info of each peer URL into an `HttpAuthenticationFeature.basic(...)`, over TLS now.
- ***Tests*** — [`EurekaServerApplicationTests`](src/test/java/com/learning/discovery/eureka/EurekaServerApplicationTests.java)
  runs with `server.ssl.enabled=false` (plain `RestTestClient`, filter off);
  [`EurekaTlsTests`](src/test/java/com/learning/discovery/eureka/EurekaTlsTests.java) keeps TLS on and does real
  handshakes. With the node's certificate, `/eureka/apps` and a replication batch get `200`. Without one,
  `/eureka/apps` still gets `200` but replication gets `403`. The JDK default trust store gets an
  `SSLHandshakeException`, and `TlsPeerEurekaNodes` is in place.
  [`ClientCertificateSubjectFilterTests`](src/test/java/com/learning/discovery/eureka/config/ClientCertificateSubjectFilterTests.java)
  covers the subject rules.
- ***Verify replication over TLS*** — register anything with one node and read it from another:
  `curl --cacert certs/out/ca.crt -u admin:admin -H 'Accept: application/json' https://127.0.0.1:8762/eureka/apps`.
  A node whose peer is down logs `Network level connection to peer 127.0.0.2; retrying after delay`; a trust problem
  shows `PKIX path building failed` instead, and a peer without its certificate shows `403` in the receiving node's
  log (`ClientCertificateSubjectFilter : 403 POST /eureka/peerreplication/batch/ … client certificate required`).

<a id="actuator-endpoints"></a>

## <span style="color:hsl(256,80%,58%)">5. 📊 Actuator endpoints and SBA views</span>

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
| `quartz`        | Quartz                             | `spring-boot-starter-quartz`, in-memory store, demo `HeartbeatJob` (§6)                                                                                                                            |
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

## <span style="color:hsl(33,80%,58%)">6. ⏱️ Quartz demo job, file logging, configuration</span>

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
      defaultZone: http://${…}@${eureka.server.instances[0]}/eureka/,http://${…}@${eureka.server.instances[1]}/eureka/,http://${…}@${eureka.server.instances[2]}/eureka/
  server:
    instances: [ localhost:8761, 127.0.0.1:8762, 127.0.0.2:8763 ]   # every node; hostnames must differ
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

<a id="eureka-core-internals"></a>

## <span style="color:hsl(270,70%,62%)">7. 🔬 Internals: eureka-core 2.0.6</span>

`eureka-core` (Netflix, `com.netflix.eureka`) is the registry itself; `spring-cloud-netflix-eureka-server` 5.0.x
(Spring Cloud 2025.1) only boots it inside Spring and swaps a few beans. Everything below was read from the 2.0.6
sources (`eureka-core`, `eureka-core-jersey3`) and Spring Cloud Netflix 5.0.2.

<a id="core-1"></a>

### 7.1 Package map

| Package                         | Key types                                                                                                   | Job                                                                       |
|---------------------------------|-------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------|
| `com.netflix.eureka`            | `EurekaServerContext`, `DefaultEurekaServerContext`, `EurekaServerConfig`, `EurekaBootStrap`                | wiring: config, registry, peers, codecs; lifecycle                         |
| `…eureka.registry`              | `AbstractInstanceRegistry`, `PeerAwareInstanceRegistryImpl`, `ResponseCacheImpl`, `Key`, `RemoteRegionRegistry` | the in-memory registry, self-preservation, the read cache              |
| `…eureka.lease`                 | `Lease<T>`, `LeaseManager<T>`                                                                               | lease = holder + timestamps; register/renew/cancel/evict contract          |
| `…eureka.registry.rule`         | `FirstMatchWinsCompositeRule`, `DownOrStartingRule`, `OverrideExistsRule`, `LeaseExistsRule`                | which status an instance really has (overrides vs. reported)               |
| `…eureka.cluster`               | `PeerEurekaNodes`, `PeerEurekaNode`, `ReplicationTaskProcessor`, `HttpReplicationClient`                    | replication to the other registry nodes                                    |
| `…eureka.util.batcher`          | `TaskDispatchers`, `AcceptorExecutor`, `TaskExecutors`, `TrafficShaper`                                     | generic batching/retry engine used by replication                          |
| `…eureka.resources`             | `ApplicationsResource`, `ApplicationResource`, `InstanceResource`, `PeerReplicationResource`, `ServerCodecs` | the JAX-RS (Jersey) REST API under `/eureka`                               |
| `…eureka.transport` (jersey3)   | `Jersey3ReplicationClient`, `Jersey3DynamicGZIPContentEncodingFilter`                                       | HTTP client for replication                                                |
| `…eureka.aws`                   | `EIPManager`, `Route53Binder`, `AwsAsgUtil`                                                                 | AWS-only (EIP/ASG binding); unused here                                    |

<a id="core-2"></a>

### 7.2 Design

```mermaid
classDiagram
    direction LR
    class LeaseManager~T~ {
        <<interface>>
        register(T, int leaseDuration, boolean isReplication)
        renew(app, id, isReplication) boolean
        cancel(app, id, isReplication) boolean
        evict()
    }
    class AbstractInstanceRegistry {
        -ConcurrentHashMap~String, Map~String, Lease~~ registry
        -ConcurrentLinkedQueue~RecentlyChangedItem~ recentlyChangedQueue
        #ConcurrentMap~String, InstanceStatus~ overriddenInstanceStatusMap
        -MeasuredRate renewsLastMin
        #int numberOfRenewsPerMinThreshold
        #ResponseCache responseCache
        +getApplicationDeltas() Applications
        #postInit() starts EvictionTask
    }
    class PeerAwareInstanceRegistryImpl {
        #PeerEurekaNodes peerEurekaNodes
        +syncUp() int
        +openForTraffic(aim, count)
        +isLeaseExpirationEnabled() boolean
        +shouldAllowAccess() boolean
        -replicateToPeers(Action, ...)
    }
    class InstanceRegistry {
        <<Spring Cloud>>
        publishes EurekaInstanceRegistered/Renewed/CanceledEvent
        openForTraffic(count==0 ? 1 : count)
    }
    class ResponseCacheImpl {
        -ConcurrentMap~Key, Value~ readOnlyCacheMap
        -LoadingCache~Key, Value~ readWriteCacheMap
        +get(Key) String
        +invalidate(app, vip, svip)
    }
    class PeerEurekaNodes {
        <<abstract>>
        +start() / shutdown()
        #createPeerEurekaNode(url)* PeerEurekaNode
    }
    class PeerEurekaNode {
        -TaskDispatcher batchingDispatcher
        -TaskDispatcher nonBatchingDispatcher
        +register(info) / heartbeat(...) / cancel(...) / statusUpdate(...)
    }
    class ReplicationTaskProcessor {
        +process(List~ReplicationTask~) ProcessingResult
    }
    class Lease~T~ {
        -T holder
        -long registrationTimestamp
        -long lastUpdateTimestamp
        -long evictionTimestamp
        -long duration
        +renew() / cancel() / isExpired()
    }
    LeaseManager <|.. AbstractInstanceRegistry
    AbstractInstanceRegistry <|-- PeerAwareInstanceRegistryImpl
    PeerAwareInstanceRegistryImpl <|-- InstanceRegistry
    AbstractInstanceRegistry o-- Lease
    AbstractInstanceRegistry --> ResponseCacheImpl
    PeerAwareInstanceRegistryImpl --> PeerEurekaNodes
    PeerEurekaNodes "1" o-- "*" PeerEurekaNode
    PeerEurekaNode --> ReplicationTaskProcessor : via TaskDispatcher
    PeerEurekaNodes <|-- RefreshablePeerEurekaNodes : Spring Cloud default
    PeerEurekaNodes <|-- TlsPeerEurekaNodes : this repo (EurekaTlsConfig)
```

- ***Two-level map, one lease per instance*** — `registry` is `appName → (instanceId → Lease<InstanceInfo>)`. All
  writers (`register`, `cancel`, `statusUpdate`) take the ***read*** lock of one `ReentrantReadWriteLock`, and only
  `getApplicationDeltas()` takes the ***write*** lock: writers run concurrently on the concurrent maps, and the delta
  read gets a frozen snapshot of `recentlyChangedQueue` plus the full-registry hash.
- ***Every write is three steps*** — mutate the lease, append a `RecentlyChangedItem` to `recentlyChangedQueue`,
  `invalidateCache(app, vip, svip)`; then `PeerAwareInstanceRegistryImpl` replicates it unless it *is* a replication
  (`isReplication == true` stops the echo).
- ***Spring Cloud's layer*** — `InstanceRegistry` (Spring) extends `PeerAwareInstanceRegistryImpl`: it publishes
  `EurekaInstanceRegisteredEvent` / `RenewedEvent` / `CanceledEvent` as Spring events and opens for traffic with a
  count of at least 1 (`defaultOpenForTrafficCount`), so a lone node doesn't refuse reads for 5 minutes (7.6).

<a id="core-3"></a>

### 7.3 Lease and timing

```text
register:  lastUpdateTimestamp = now                       duration = leaseExpirationDuration (30 s here)
renew:     lastUpdateTimestamp = now + duration            ← documented upstream bug: adds duration once too often
isExpired: evictionTimestamp > 0 || now > lastUpdateTimestamp + duration + additionalLeaseMs
```

- ***Effective lease is 2 × duration*** — after the last heartbeat at `t`, `lastUpdateTimestamp = t + 30 s`, and the
  lease only counts as expired after `t + 60 s`. Upstream left it as is (the javadoc on `Lease.isExpired` calls it a
  minor bug). Add up to one sweep interval (30 s here) and a dead instance leaves the registry ~60–90 s after its last
  heartbeat — the timeline in [Q&A 11.2](#qa-crash-eviction-timeline).
- ***`EvictionTask`*** — a `java.util.Timer` (`Eureka-EvictionTimer`) every `eviction-interval-timer-in-ms` (30 s
  here). It computes a ***compensation*** for its own lateness (GC pause, clock drift) and calls
  `evict(compensationMs)`, so a long pause doesn't instantly expire everyone.

<a id="core-4"></a>

### 7.4 Register, replicate, read — sequence

```mermaid
sequenceDiagram
    autonumber
    participant C as order-service<br/>(DiscoveryClient)
    participant R1 as peer1 Jersey<br/>ApplicationResource
    participant IR as InstanceRegistry<br/>(PeerAware…Impl → Abstract…)
    participant RC as ResponseCacheImpl
    participant PN as PeerEurekaNode<br/>(target peer2)
    participant B as AcceptorExecutor +<br/>TaskExecutors (batcher)
    participant R2 as peer2<br/>PeerReplicationResource

    C->>R1: POST /eureka/apps/ORDER-SERVICE (InstanceInfo, Basic, TLS)
    R1->>IR: register(info, isReplication=false)
    IR->>IR: Spring: publish EurekaInstanceRegisteredEvent
    IR->>IR: read lock · new Lease(info, 30 s) · apply status-override rules
    IR->>IR: recentlyChangedQueue.add(ADDED) · expected renews +1 → recompute threshold
    IR->>RC: invalidate(ALL_APPS, ALL_APPS_DELTA, app, vip, svip)
    IR->>PN: replicateToPeers(Register) — every peer except self
    PN->>B: batchingDispatcher.process("register#ORDER-SERVICE/id", task, expiry = now + lease)
    R1-->>C: 204 No Content
    Note over B: waits up to 500 ms or 250 tasks,<br/>newer task with the same id replaces the queued one
    B->>R2: POST /eureka/peerreplication/batch (ReplicationList, gzip, TLS)
    R2->>R2: dispatch each item to Application/InstanceResource with isReplication=true
    R2-->>B: 200 ReplicationListResponse (per-item status)
    Note over B,R2: 503 / read timeout → Congestion: retry after 1 s<br/>connect refused → TransientError: retry after 100 ms<br/>other 4xx/5xx → PermanentError: dropped

    C->>R1: GET /eureka/apps/delta (every 30 s)
    R1->>RC: get(Key ALL_APPS_DELTA, json, full)
    alt readOnlyCacheMap hit (refreshed from readWriteCacheMap every 30 s)
        RC-->>R1: cached payload (may be up to 30 s stale)
    else miss
        RC->>IR: generatePayload → getApplicationDeltas() (write lock)
        RC-->>R1: payload, stored in both maps
    end
    R1-->>C: 200 delta + appsHashCode
```

<a id="core-5"></a>

### 7.5 Heartbeat and conflict resolution

`PUT /eureka/apps/{APP}/{id}?status=UP&lastDirtyTimestamp=…` → `InstanceResource.renewLease`:

| Registry state                                           | Response                         | Effect                                                                 |
|----------------------------------------------------------|----------------------------------|------------------------------------------------------------------------|
| no lease for the id (evicted, node restarted cold)       | `404`                            | client calls `register()` again — self-healing after a lost registration |
| lease found, client's `lastDirtyTimestamp` is ***newer*** | `404`                            | client re-registers so the node gets its newer `InstanceInfo`          |
| lease found, registry's copy is newer, call is a replication | `409 Conflict` + registry's `InstanceInfo` | the sending peer `syncInstancesIfTimestampDiffers` → registers the newer copy locally |
| lease found, registry's copy is newer, direct client call | `200`                            | nothing                                                                |
| otherwise                                                | `200`                            | `lease.renew()`, `renewsLastMin++`, replicated as `Heartbeat`          |

This is the whole consistency model: no consensus, ***last-dirty-timestamp wins***, and repair rides on the next
heartbeat (the AP choice in the root [FAQ 12.6](../README.md#faq-eureka-ap)).

<a id="core-6"></a>

### 7.6 Startup, sync and `shouldAllowAccess`

```mermaid
sequenceDiagram
    autonumber
    participant SL as EurekaServerInitializerConfiguration<br/>(SmartLifecycle)
    participant BS as EurekaServerBootstrap
    participant IR as InstanceRegistry
    participant DC as own DiscoveryClient (Jersey, TLS)
    participant PN as PeerEurekaNodes (TlsPeerEurekaNodes)

    Note over PN: earlier, at bean creation: DefaultEurekaServerContext @PostConstruct initialize()<br/>→ peerEurekaNodes.start(): resolvePeerUrls() = defaultZone minus own hostname<br/>→ createPeerEurekaNode(url) for each peer
    SL->>BS: contextInitialized()
    BS->>IR: syncUp()
    loop registry-sync-retries times while count == 0 (wait registry-sync-retry-wait-ms)
        IR->>DC: getApplications() — registry the own client fetched from a peer
        IR->>IR: register(each instance, isReplication=true)
    end
    BS->>IR: openForTraffic(aim, count)  — Spring: count 0 → 1
    IR->>IR: expected clients = count → numberOfRenewsPerMinThreshold
    IR->>IR: status UP · postInit(): renewsLastMin.start() · schedule EvictionTask
    SL->>SL: publish EurekaRegistryAvailableEvent, EurekaServerStartedEvent
```

- ***`shouldAllowAccess`*** — `GET /apps` and `/apps/delta` return `403` while
  `peerInstancesTransferEmptyOnStartup` is true and less than `wait-time-in-ms-when-sync-empty` (5 min) has passed.
  It is set false when `openForTraffic` receives `count > 0`; Spring's `InstanceRegistry` passes at least 1, which is
  why a cold, lonely node here serves reads at once.
- ***Renewal threshold refresh*** — besides the +1/−1 on every register/cancel, a timer recomputes the expected
  client count from the registry size every `renewal-threshold-update-interval-ms` (15 min).

<a id="core-7"></a>

### 7.7 Self-preservation, the formula

```text
numberOfRenewsPerMinThreshold = expectedClients × (60 / expected-client-renewal-interval-seconds) × renewal-percent-threshold
isLeaseExpirationEnabled      = !enable-self-preservation || (threshold > 0 && renewsLastMin > threshold)
evictionLimit                 = registrySize − (int)(registrySize × renewal-percent-threshold)
```

- ***This demo*** — 9 instances, 10 s heartbeats, threshold `0.5`: `9 × 6 × 0.5 = 27` renews/min needed. Healthy
  traffic is `9 × 6 = 54`/min, so killing up to 4 instances keeps expiry on; killing 5 drops the rate to 24 and
  freezes eviction (dashboard: *EMERGENCY! EUREKA MAY BE INCORRECTLY CLAIMING INSTANCES ARE UP…*).
- ***Two separate brakes*** — self-preservation stops eviction entirely when renews fall below the threshold; the
  ***eviction cap*** (`evictionLimit`, random Knuth shuffle across apps) limits each sweep even with self-preservation
  off ([Q&A 11.3](#qa-eviction-cap)). `renewsLastMin` is a `MeasuredRate` over the previous full minute, so both react
  with up to a minute's lag.
- ***Getting this wrong*** — `expected-client-renewal-interval-seconds` must equal the clients'
  `lease-renewal-interval-in-seconds` (both 10 here); with the default 30 the threshold is a third of reality and
  self-preservation never trips.

<a id="core-8"></a>

### 7.8 Response cache

| Layer               | Type                                    | Filled by                                  | Expires / refreshes                                         |
|---------------------|-----------------------------------------|--------------------------------------------|-------------------------------------------------------------|
| `readOnlyCacheMap`  | `ConcurrentHashMap<Key, Value>`         | timer copies changed keys from read-write  | every `response-cache-update-interval-ms` (30 s)            |
| `readWriteCacheMap` | Guava `LoadingCache<Key, Value>`        | `generatePayload` from the registry         | `expireAfterWrite` `response-cache-auto-expiration-in-seconds` (180 s); invalidated on every write |

- ***A `Key`*** = entity (`Application` / `VIP` / `SVIP`) + name (`ALL_APPS`, `ALL_APPS_DELTA`, `ORDER-SERVICE`) +
  type (JSON/XML) + version + accept (`full`/`compact`) + regions. Each holds the raw and the gzip payload.
- ***Writes don't reach readers immediately*** — invalidation clears only `readWriteCacheMap`; readers hit
  `readOnlyCacheMap` until the next 30 s copy. With `use-read-only-response-cache: false` reads go straight to the
  read-write cache (fresher, more payload generation under load).

<a id="core-9"></a>

### 7.9 Replication engine

```mermaid
flowchart LR
    W["replicateToPeers<br/>(per write, per peer)"] --> AQ["acceptorQueue"]
    AQ --> PT["pendingTasks: Map&lt;taskId, TaskHolder&gt;<br/>+ processingOrder deque<br/>(same id → newer replaces older)"]
    RQ["reprocessQueue<br/>(Congestion / TransientError)"] --> PT
    PT -->|"≤ 250 per batch, ≤ 500 ms wait,<br/>TrafficShaper delay after errors"| BW["batchWorkQueue"]
    BW --> T["TaskExecutors: 20 threads<br/>TaskBatchingWorker-target_&lt;host&gt;-N"]
    T --> P["ReplicationTaskProcessor<br/>→ Jersey3ReplicationClient.submitBatchUpdates"]
    P -->|"Success / PermanentError"| X(("done"))
    P -->|"Congestion: +1 s<br/>TransientError: +100 ms"| RQ
```

- ***One dispatcher pair per peer*** — `batchingDispatcher` for instance events, `nonBatchingDispatcher` for ASG
  status. The name `target_<peer host>` shows up in thread names and in the log line you see when a peer is down.
- ***Bounded and lossy by design*** — at most `max-elements-in-peer-replication-pool` (10,000) pending tasks per peer
  (overflow is counted and dropped); register/heartbeat tasks expire after one lease, others after
  `max-time-for-replication` (30 s). A lost replication is repaired by the next heartbeat's `404` → re-register.

<a id="core-10"></a>

### 7.10 Tunables (Spring property → default → here)

| `eureka.server.*` property                     | Default (Spring Cloud) | Here          | Drives                                         |
|------------------------------------------------|------------------------|---------------|------------------------------------------------|
| `enable-self-preservation`                     | `true`                 | `true`        | 10.7                                           |
| `renewal-percent-threshold`                    | `0.85`                 | `0.5` (`0.85` prod) | 10.7                                     |
| `expected-client-renewal-interval-seconds`     | `30`                   | `10`          | threshold maths                                |
| `eviction-interval-timer-in-ms`                | `60000`                | `30000`       | `EvictionTask` period                          |
| `renewal-threshold-update-interval-ms`         | `900000`               | default       | expected-client recount                        |
| `response-cache-update-interval-ms`            | `30000`                | default       | read-only cache refresh                        |
| `response-cache-auto-expiration-in-seconds`    | `180`                  | default       | read-write cache expiry                        |
| `use-read-only-response-cache`                 | `true`                 | default       | 10.8                                           |
| `retention-time-in-m-s-in-delta-queue`         | `180000`               | default       | how long a change stays in `/apps/delta`       |
| `delta-retention-timer-interval-in-ms`         | `30000`                | default       | delta-queue cleanup                            |
| `wait-time-in-ms-when-sync-empty`              | `300000`               | default       | `shouldAllowAccess` window                     |
| `registry-sync-retries` / `-retry-wait-ms`     | `0` / `30000`          | Spring raises retries when registering | `syncUp()` (7.6)      |
| `peer-node-connect-timeout-ms` / `-read-timeout-ms` | `200` / `200`     | default       | replication client                             |
| `peer-node-total-connections` / `-per-host`    | `1000` / `500`         | default       | replication pool                               |
| `max-elements-in-peer-replication-pool`        | `10000`                | default       | batcher queue bound                            |
| `max-threads-for-peer-replication`             | `20`                   | default       | batcher workers                                |
| `max-time-for-replication`                     | `30000`                | default       | non-lease task expiry                          |
| `peer-eureka-nodes-update-interval-ms`         | `600000`               | default       | peer list refresh                              |

<a id="core-11"></a>

### 7.11 Design patterns and principles in eureka-core

| Pattern / principle | Where (eureka-core 2.0.6) | What it buys |
|---|---|---|
| ***Lease*** (time-bound ownership) | `Lease<T>` + `LeaseManager<T>`; `AbstractInstanceRegistry.register/renew/cancel/evict` | liveness without a failure detector: an entry lives only while its owner keeps renewing; silence = expiry ([7.3](#core-3)) |
| ***Template Method*** | `PeerEurekaNodes.createPeerEurekaNode(url)` is abstract; `start()`/`updatePeerEurekaNodes()` fixed. `AbstractInstanceRegistry` → `PeerAwareInstanceRegistryImpl` → Spring's `InstanceRegistry` override hooks (`register`, `openForTraffic`) | Spring Cloud plugs in `RefreshablePeerEurekaNodes`, this repo `TlsPeerEurekaNodes`, without touching the peer lifecycle |
| ***Composite + Chain of Responsibility*** | `FirstMatchWinsCompositeRule(DownOrStartingRule, OverrideExistsRule, LeaseExistsRule)` over `InstanceStatusOverrideRule`; the first rule that `matches()` decides | status resolution as small, ordered, testable rules instead of one `if` ladder |
| ***Strategy*** | `InstanceStatusOverrideRule`, `ServerCodecs` / `CodecWrappers` (JSON, XML, compact), `EurekaServerConfig` implementations (`DefaultEurekaServerConfig`, Spring's `EurekaServerConfigBean`) | swap serialization or config source without changing the registry |
| ***Command*** | `ReplicationTask` / `InstanceReplicationTask` (one register/heartbeat/cancel/status action, with `execute()` and `handleFailure()`) wrapped in `TaskHolder` with an expiry | replication actions become data: queued, batched, retried, expired, overridden |
| ***Producer–consumer + batching (Acceptor/Worker)*** | `TaskDispatchers` → `AcceptorExecutor` (acceptor thread, `pendingTasks`, `reprocessQueue`) → `TaskExecutors` worker pool → `TaskProcessor` | request threads never block on peers; replication cost is amortized (≤ 250 tasks per HTTP call) ([7.9](#core-9)) |
| ***Coalescing / last-write-wins in the queue*** | `AcceptorExecutor.pendingTasks` keyed by task id (`heartbeat#APP/id`): a newer task replaces the queued one (`overriddenTasks` metric) | 10 queued heartbeats for one instance cost one HTTP item |
| ***Backpressure + adaptive retry*** | bounded queues (`max-elements-in-peer-replication-pool`, `queueOverflows`), `TrafficShaper` delays after `Congestion` (1 s) / `TransientError` (100 ms) | a slow or dead peer doesn't exhaust memory or hot-loop |
| ***Two-level cache (read-through + periodic snapshot)*** | `ResponseCacheImpl`: Guava `LoadingCache` (read-through, `generatePayload`) + `readOnlyCacheMap` refreshed by a timer | reads never touch the registry lock under load; trades up to 30 s freshness ([7.8](#core-8)) |
| ***Event log for deltas*** | `recentlyChangedQueue` + `DeltaRetentionTask` (3 min retention); `getApplicationDeltas()` | clients pull only changes, not the whole registry |
| ***Checksum reconciliation (anti-entropy lite)*** | `appsHashCode` (`UP_8_DOWN_1_`) shipped with every delta | cheap drift detection; a mismatch triggers one full fetch instead of trusting a broken delta stream |
| ***Optimistic, last-writer-wins conflict resolution*** | `lastDirtyTimestamp` compare in `AbstractInstanceRegistry.register` and `InstanceResource.validateDirtyTimestamp` (`404` / `409` + body) | no locks or consensus between nodes; the newest copy of an `InstanceInfo` wins and repair rides on heartbeats ([7.5](#core-5)) |
| ***Reader–writer lock, inverted*** | writers take the ***read*** lock, `getApplicationDeltas()` the ***write*** lock (`AbstractInstanceRegistry`) | many concurrent writers on concurrent maps; one consistent snapshot for the delta read |
| ***Circuit breaker on eviction (self-preservation)*** | `PeerAwareInstanceRegistryImpl.isLeaseExpirationEnabled()` vs `numberOfRenewsPerMinThreshold`; eviction cap + randomized victims in `evict()` | a network partition can't empty the registry; fail ***open*** (stale data) rather than closed (no data) ([7.7](#core-7)) |
| ***Self-timing compensation*** | `EvictionTask.getCompensationTimeMs()` | a GC pause or clock jump doesn't look like everyone missing heartbeats |
| ***Sliding-window metric*** | `MeasuredRate` (current/last minute buckets) for `renewsLastMin` | O(1) rate tracking driving self-preservation |
| ***Facade / context object*** | `EurekaServerContext` (`DefaultEurekaServerContext`) bundles config, codecs, registry, peer nodes; `EurekaServerContextHolder` (static holder) for code outside DI | one entry point for resources and bootstrapping |
| ***Idempotent REST operations*** | register = `POST` of the full `InstanceInfo` (replaces), renew = `PUT`, cancel = `DELETE`; replication flag `x-netflix-discovery-replication` stops echo loops | safe retries on any node, safe replays from the replication queue |

***Principles behind the code***

- ***AP over CP*** (CAP) — every node accepts writes and serves reads alone; consistency is eventual, by
  replication plus heartbeat repair. Root [FAQ 12.6](../README.md#faq-eureka-ap).
- ***Fail open, degrade gracefully*** — self-preservation, client caches, `404 → re-register`: when unsure, keep
  serving the last known state.
- ***Server is dumb, client is smart*** — the registry stores and serves; load balancing, caching, retries and
  failover live in the client (section 8). That keeps the server horizontally simple.
- ***Push work off the request path*** — replication, cache refresh, eviction and delta cleanup all run on timers or
  worker pools; a client request only touches in-memory maps.
- ***Bulkheads per peer*** — each `PeerEurekaNode` has its own dispatchers, queues and worker threads, so one slow
  peer doesn't stall replication to the others.

<a id="core-12"></a>

### 7.12 Tech stack: how Eureka and its dashboard are built

Read from the published POMs/jars (eureka 2.0.6, Spring Cloud Netflix 5.0.3). Two layers, built by two projects:

| Layer | Built with | Notes |
|---|---|---|
| ***Netflix Eureka*** (`eureka-core`, `eureka-client`, `*-jersey3`) | Java, ***Gradle*** (POMs carry Gradle module metadata) | plain Java library, no Spring |
| REST API (`/eureka/apps`, `/peerreplication`) | ***Jersey 3*** (JAX-RS, `jakarta.ws.rs`) + HK2 injection, on the Jakarta Servlet API | `ApplicationsResource`, `InstanceResource`, … ([7.1](#core-1)) |
| Serialization | ***Jackson*** (JSON; XML via `jackson-dataformat-xml` + Woodstox), ***XStream*** (legacy codecs), `CodecWrappers` | `full` vs `compact` payloads |
| HTTP client | Apache ***HttpClient 4*** via `jersey-apache-connector` (Netflix transport, peer replication) | Spring Cloud swaps clients to Apache ***HttpClient 5*** via `RestClient` ([8.1](#client-1)) |
| Caching / collections | ***Guava*** (`LoadingCache` in `ResponseCacheImpl`, override-status cache), `compactmap` | |
| Configuration | ***Archaius 1*** + Apache Commons Configuration (client side) | Spring Cloud replaces it with `@ConfigurationProperties` beans (`EurekaClientConfigBean`, `EurekaServerConfigBean`) |
| Metrics | ***Spectator*** (`SpectatorUtil`, replication/registry gauges) | |
| Cloud integration | AWS SDK v1 (EC2, Auto Scaling, Route 53, STS) | EIP/ASG/DNS binding; unused here |
| ***Spring Cloud Netflix*** (`spring-cloud-netflix-eureka-server` / `-client`) | Java, Maven, Spring Boot auto-configuration | `EurekaServerAutoConfiguration`, `EurekaClientAutoConfiguration` |
| Embedding the server | Spring MVC + the Jersey `ServletContainer` registered as a filter on `/eureka/*` | one Tomcat serves the REST API, the dashboard and actuator |
| ***Dashboard*** (`/`, `/lastn`) | Spring MVC `EurekaController` + ***FreeMarker*** templates (`templates/eureka/status.ftlh`, `navbar.ftlh`, `header.ftlh`, `lastn.ftlh`; `spring-boot-starter-freemarker`) | server-side rendered HTML, no JavaScript application |
| Dashboard styling | ***Bootstrap 5.1.3*** CSS/JS pre-bundled as `static/eureka/css/wro.css` and `js/wro.js` (wro4j-style bundles), Montserrat + Varela Round web fonts, Spring logo images | refreshed by reloading the page; nothing polls |

- ***What that means here*** — the dashboard is a page rendered from the registry on each request (so it is as
  fresh as the node's own registry, not its 30 s response cache), protected by this module's Spring Security form
  login, and served over TLS like everything else on the port ([§4](#https)). Customizing it means overriding the
  FreeMarker templates on the classpath (`templates/eureka/*.ftlh`); there is no plugin API like SBA's.

<a id="eureka-client-internals"></a>

## <span style="color:hsl(190,70%,50%)">8. 🔬 Internals: eureka-client 2.0.6</span>

Every module except this one talks to the registry through `com.netflix.discovery.DiscoveryClient` (eureka-client
2.0.6), created by Spring Cloud Netflix 5.0.2 as `CloudEurekaClient`. On the server classpath the same client runs
inside each registry node (Jersey transport) to register it and to feed `syncUp()`.

<a id="client-1"></a>

### 8.1 Design

```mermaid
classDiagram
    direction LR
    class EurekaClient {
        <<interface>>
        getApplications()
        getInstancesByVipAddress(vip, secure)
    }
    class DiscoveryClient {
        -AtomicReference~Applications~ localRegionApps
        -AtomicLong fetchRegistryGeneration
        -ThreadPoolExecutor heartbeatExecutor
        -ThreadPoolExecutor cacheRefreshExecutor
        -TimedSupervisorTask heartbeatTask
        -TimedSupervisorTask cacheRefreshTask
        -InstanceInfoReplicator instanceInfoReplicator
        -EurekaTransport eurekaTransport
        register() boolean
        renew() boolean
        fetchRegistry(forceFull) boolean
        unregister()
        #onCacheRefreshed()
    }
    class CloudEurekaClient {
        <<Spring Cloud>>
        onCacheRefreshed() → publish HeartbeatEvent
    }
    class ApplicationInfoManager {
        -InstanceInfo instanceInfo
        setInstanceStatus(status) → StatusChangeEvent
    }
    class InstanceInfoReplicator {
        -RateLimiter rateLimiter
        +start(initialDelay)
        +onDemandUpdate() boolean
        +run(): refreshInstanceInfo → register if dirty
    }
    class EurekaTransport {
        EurekaHttpClient registrationClient
        EurekaHttpClient queryClient
        ClusterResolver bootstrapResolver
    }
    class SessionedEurekaHttpClient
    class RetryableEurekaHttpClient
    class RedirectingEurekaHttpClient
    class RestClientEurekaHttpClient {
        <<Spring Cloud>>
    }
    EurekaClient <|.. DiscoveryClient
    DiscoveryClient <|-- CloudEurekaClient
    DiscoveryClient --> ApplicationInfoManager
    DiscoveryClient --> InstanceInfoReplicator
    DiscoveryClient --> EurekaTransport
    EurekaTransport --> SessionedEurekaHttpClient
    SessionedEurekaHttpClient --> RetryableEurekaHttpClient : delegate
    RetryableEurekaHttpClient --> RedirectingEurekaHttpClient : delegate (per endpoint)
    RedirectingEurekaHttpClient --> RestClientEurekaHttpClient : delegate
```

- ***Spring Cloud glue*** — `EurekaClientAutoConfiguration` builds `EurekaInstanceConfigBean` (`eureka.instance.*`),
  `EurekaClientConfigBean` (`eureka.client.*`), `ApplicationInfoManager`, `CloudEurekaClient`, and the
  `EurekaServiceRegistry` / `EurekaAutoServiceRegistration` pair. `EurekaDiscoveryClient` adapts it to Spring's
  `DiscoveryClient`, which Spring Cloud LoadBalancer (gateway, `order-service`'s `UserClient`) and SBA read — always
  from `localRegionApps` in memory, never with a network call.
- ***Transport*** — two `EurekaHttpClient` stacks: `registrationClient` (register/renew/cancel) and `queryClient`
  (fetches). Both are `Sessioned → Retryable → Redirecting → transport`:
  - `SessionedEurekaHttpClient` drops and recreates its delegate every
    `eureka.client.transport.sessioned-client-reconnect-interval-seconds` (20 min) ± up to 50 % random, so clients
    rebalance across registry nodes over time.
  - `RetryableEurekaHttpClient` tries up to 3 endpoints from the `ClusterResolver` (the `defaultZone` list, shuffled
    per client), puts a failing endpoint into a ***quarantine set*** that is skipped until it reaches
    `retryable-client-quarantine-refresh-percentage` of the candidates (default 0.66), when it is cleared.
  - `RedirectingEurekaHttpClient` follows `302` from a registry node to another endpoint.
  - The transport is `RestClientEurekaHttpClient` here (Apache HttpClient 5 with the bundle's `SSLContext`, see
    [§4](#https) and root [§9 step 6](../README.md#https)); Jersey on the registry nodes.

<a id="client-2"></a>

### 8.2 Threads and timers

| Task                            | Thread / executor                                   | Period (property, default → here)                                         | Backoff                                    |
|---------------------------------|-----------------------------------------------------|---------------------------------------------------------------------------|--------------------------------------------|
| Heartbeat `renew()`             | `DiscoveryClient-HeartbeatExecutor` via `TimedSupervisorTask` | `eureka.instance.lease-renewal-interval-in-seconds` 30 → ***10*** | doubles on timeout up to ×`heartbeat-executor-exponential-back-off-bound` (10) |
| Registry fetch `fetchRegistry()`| `DiscoveryClient-CacheRefreshExecutor` via `TimedSupervisorTask` | `eureka.client.registry-fetch-interval-seconds` 30 → default | same, `cache-refresh-executor-exponential-back-off-bound` (10) |
| Instance info replication       | `DiscoveryClient-InstanceInfoReplicator`             | `instance-info-replication-interval-seconds` 30, first after `initial-instance-info-replication-interval-seconds` 40 | rate limit for on-demand: `60 × burst(2) / 30` = 4 per minute |
| Service-URL refresh             | scheduler                                            | `eureka-service-url-poll-interval-seconds` 300                             | —                                          |

`TimedSupervisorTask` runs the task with a timeout equal to its period; on timeout or rejection it doubles the next
delay up to `period × bound`, and resets to the period after the next success — a slow registry slows clients down
instead of piling up threads.

<a id="client-3"></a>

### 8.3 Start, register, heartbeat, fetch — sequence

```mermaid
sequenceDiagram
    autonumber
    participant APP as Spring context
    participant AIM as ApplicationInfoManager
    participant DC as CloudEurekaClient<br/>(DiscoveryClient)
    participant IIR as InstanceInfoReplicator
    participant TX as Sessioned→Retryable→<br/>Redirecting→RestClient (TLS)
    participant EU as registry node

    APP->>DC: new (bean creation)
    DC->>TX: GET /eureka/apps/ (full registry)
    TX->>EU: HTTPS, Basic
    EU-->>DC: Applications → filterAndShuffle (UP only) → localRegionApps
    DC->>DC: initScheduledTasks(): heartbeat, cache refresh, InstanceInfoReplicator.start(40 s)
    APP->>AIM: EurekaAutoServiceRegistration.start() → EurekaServiceRegistry.register()<br/>setInstanceStatus(UP)
    AIM->>DC: StatusChangeEvent STARTING→UP
    DC->>IIR: onDemandUpdate() (rate-limited)
    IIR->>DC: refreshInstanceInfo() (host, lease info, health) · isDirty?
    DC->>TX: POST /eureka/apps/ORDER-SERVICE
    EU-->>DC: 204 · unsetDirty

    loop every 10 s (heartbeat)
        DC->>TX: PUT /eureka/apps/ORDER-SERVICE/{id}?status=UP&lastDirtyTimestamp=…
        alt 200
            EU-->>DC: lease renewed
        else 404 (unknown or newer client copy)
            EU-->>DC: 404
            DC->>TX: POST /eureka/apps/ORDER-SERVICE (re-register)
        end
    end

    loop every 30 s (cache refresh)
        DC->>TX: GET /eureka/apps/delta
        EU-->>DC: changes (ADDED / MODIFIED / DELETED) + appsHashCode
        DC->>DC: updateDelta() under fetchRegistryGeneration CAS
        DC->>DC: local hash "DOWN_1_UP_8_" == appsHashCode ?
        opt mismatch
            DC->>TX: GET /eureka/apps/ (full) · replace localRegionApps
        end
        DC->>APP: onCacheRefreshed() → HeartbeatEvent (SBA re-discovers on it)
    end

    APP->>DC: shutdown (@PreDestroy)
    DC->>TX: DELETE /eureka/apps/ORDER-SERVICE/{id}
```

- ***Delta + hash reconciliation*** — a delta is applied only if no other fetch raced it (`fetchRegistryGeneration`
  compare-and-set). The hash is `<STATUS>_<count>_` per status, sorted; any drift (missed delta, delta queue
  expired after 3 min, cache skew between registry nodes) triggers one full fetch. A full fetch is also forced when
  the local registry is empty, delta is disabled, or a single-VIP fetch is configured.
- ***Status is local first*** — `ApplicationInfoManager.setInstanceStatus` changes the local `InstanceInfo`, marks it
  dirty and lets `InstanceInfoReplicator` push it (immediately via on-demand, otherwise at the next 30 s run).
  `/actuator/serviceregistry` and Boot's health-check integration go through the same path.
- ***Why callers see a dead instance for minutes*** — registry eviction (~60–90 s, 10.3) + read-only cache (30 s) +
  client fetch (30 s) + LoadBalancer cache (35 s): Q&A [11.2](#qa-crash-eviction-timeline) has the full timeline.

<a id="client-4"></a>

### 8.4 Where the TLS hooks sit

| Hook                                   | Who reads it                                          | Used here                                                       |
|----------------------------------------|-------------------------------------------------------|-----------------------------------------------------------------|
| `AbstractDiscoveryClientOptionalArgs.setSSLContext` | `DiscoveryClient` → `TransportClientFactories.newTransportClientFactory(…, sslContext, hostnameVerifier)` | ✅ `EurekaClientTlsConfig` (RestClient), `EurekaTlsConfig` (Jersey) |
| `eureka.client.tls.*` (key/trust-store files) | Spring Cloud `SSLContextFactory` → same optional args | — (replaced by the SSL bundle)                                   |
| `EurekaClientHttpRequestFactorySupplier` bean | `RestClientTransportClientFactory` (builds the HttpClient 5 request factory) | — (default; receives the `SSLContext`)                 |
| `spring.http.clients.ssl.bundle`       | Boot's `RestClient.Builder` / `WebClient.Builder`      | — for Eureka (its request factory is replaced); ✅ for SBA        |

<a id="client-5"></a>

### 8.5 Design patterns and principles in eureka-client

| Pattern / principle | Where (eureka-client 2.0.6) | What it buys |
|---|---|---|
| ***Decorator*** | `EurekaHttpClientDecorator` subclasses stacked by `EurekaHttpClients.canonicalClientFactory`: `SessionedEurekaHttpClient` → `RetryableEurekaHttpClient` → `RedirectingEurekaHttpClient` → transport (+ `MetricsCollectingEurekaHttpClient` on Jersey) | session rotation, failover, redirects and metrics composed as layers around one `EurekaHttpClient` interface |
| ***Command (inside the decorator)*** | `EurekaHttpClientDecorator.RequestExecutor<R>` — each operation (`register`, `sendHeartBeat`, `getApplications`, …) is wrapped once and replayed by `execute(requestExecutor)` against the next delegate | one retry/redirect implementation for all ten operations |
| ***Factory / Abstract Factory*** | `TransportClientFactories` → `TransportClientFactory` (`newClient(endpoint)`), `EurekaHttpClientFactory`, Spring's `RestClientTransportClientFactories` / `WebClientTransportClientFactories` | transport (Jersey, RestClient, WebClient) chosen at wiring time; the TLS `SSLContext` flows through the factory ([4](#https)) |
| ***Strategy*** | `ClusterResolver` (`ZoneAffinityClusterResolver`, config/DNS resolvers), `EndpointRandomizer`, `ServerStatusEvaluator`, `HealthCheckHandler`, `BackupRegistry` | where the servers are, which status codes are retryable, how health maps to `UP/DOWN`, what to load when every server is down |
| ***Observer*** | `ApplicationInfoManager.StatusChangeListener` (status → on-demand re-register); `EurekaEventListener` + `CacheRefreshedEvent` / `StatusChangeEvent`; Spring's `CloudEurekaClient` turns refreshes into `HeartbeatEvent` (SBA listens) | registration and discovery react to state changes without polling each other |
| ***Copy-on-write snapshot*** | `AtomicReference<Applications> localRegionApps`: fetches build a new filtered/shuffled `Applications` and swap it in | lock-free reads for every `getInstances()` caller (LoadBalancer, SBA) |
| ***Optimistic concurrency (CAS generation)*** | `fetchRegistryGeneration.compareAndSet(...)` in `getAndStoreFullRegistry` / `getAndUpdateDelta` | a slow fetch can't overwrite a newer one that finished first |
| ***Supervisor with exponential backoff*** | `TimedSupervisorTask` (timeout = period, delay doubles up to `period × bound`, resets on success) | a struggling registry gets fewer calls, and client threads never pile up |
| ***Token-bucket rate limiter*** | `RateLimiter` (burst 2, 4/min) guarding `InstanceInfoReplicator.onDemandUpdate()` | flapping health can't flood the registry with re-registrations |
| ***Dirty flag + periodic flush*** | `InstanceInfo.isDirty()/setIsDirty()` with `lastDirtyTimestamp`; `InstanceInfoReplicator.run()` re-registers only when dirty | local changes are cheap; the registry sees each change once |
| ***Quarantine list (circuit-breaker-like)*** | `RetryableEurekaHttpClient.quarantineSet`, cleared at `retryable-client-quarantine-refresh-percentage` | stops retrying a dead node on every call, without ever giving up on the whole cluster |
| ***Session with jitter*** | `SessionedEurekaHttpClient.randomizeSessionDuration` (20 min ± 50 %) | clients re-spread across registry nodes over time instead of all sticking to the first one |
| ***Facade*** | `DiscoveryClient` hides transport, scheduling, caching and registration behind `EurekaClient` | apps and Spring only see `getInstancesByVipAddress` / `getApplications` |
| ***Adapter*** (Spring Cloud) | `EurekaDiscoveryClient` → Spring's `DiscoveryClient`; `EurekaServiceInstance` → `ServiceInstance` (`isSecure()`, `getUri()`) | gateway, LoadBalancer and SBA stay registry-agnostic |
| ***CQRS-style split*** | separate `registrationClient` (writes) and `queryClient` (reads), each with its own resolver and session | reads can target a different/closer node than writes |

***Principles behind the code***

- ***Client-side caching first*** — every lookup is served from memory; the network is only for the 30 s refresh and
  the heartbeat. Registry outages degrade freshness, not availability.
- ***Smart client, dumb server*** — failover, randomization, reconciliation and rate limiting all live here.
- ***Self-healing by repetition*** — heartbeats re-register on `404`, deltas reconcile by hash, sessions rotate:
  every failure mode is repaired by the next periodic action rather than by special recovery code.

<a id="tests"></a>

## <span style="color:hsl(171,80%,58%)">9. 🧪 Tests</span>

[`EurekaServerApplicationTests`](src/test/java/com/learning/discovery/eureka/EurekaServerApplicationTests.java) —
`@SpringBootTest(webEnvironment = RANDOM_PORT)` driven by `RestTestClient` against the real embedded server.

| Test                                     | Checks                                                                                                           |
|------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| `contextLoads`                           | `@EnableEurekaServer` context boots without errors                                                               |
| `dashboardIsServedAtRootWithCredentials` | `GET /` with Basic `admin:admin` → 2xx and body contains `Eureka`                                                |
| `healthEndpointIsPublicAndReportsUp`     | `GET /actuator/health` without auth → 200, `$.status == "UP"`                                                    |
| `registryApiRejectsAnonymousCallers`     | `GET /eureka/apps` without auth → 401                                                                            |
| `registryApiAcceptsBasicAuth`            | `GET /eureka/apps` with Basic → 200                                                                              |
| `heartbeatForUnknownInstanceIsBare404`   | heartbeat for an unregistered instance → bare `404` (no JSON body), see [Gotchas](#gotchas)                      |
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

## <span style="color:hsl(308,80%,58%)">10. ⚠️ Gotchas</span>

- ***Start it first*** — clients started before it log connection-refused retries and only appear once they retry
  successfully.
- ***Clients get 401 without credentials*** — a `defaultZone` of `https://localhost:8761/eureka/` (no `user:pass@`) fails
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

## <span style="color:hsl(223,80%,58%)">11. ❓ Q&A</span>

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
  within ~1.5 min ([Q2](#qa-crash-eviction-timeline)), but one network blip can wipe the registry. Acceptable on a single dev machine, not in production.

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
  the last three rows apply. The root [FAQ 12.9](../README.md#faq-worst-case-detection) stacks the client-side layers;
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
  [§11](../README.md#where-this-fits)).
- ***Monitor*** — renews per minute vs threshold, replication batch failures in the server log, `/eureka/apps`
  latency and response size, GC pauses on the nodes (a long pause looks like a mass expiry — [Q3](#qa-eviction-cap)).
