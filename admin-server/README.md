# <span style="color:hsl(201,80%,58%)">admin-server</span>

## <span style="color:hsl(151,80%,58%)">Table of contents</span>

1. 🧩 [Overview](#overview)
2. 🚀 [Run and log in](#run-and-login)
3. 🔐 [Security in detail](#security)
4. 🔑 [Types of supported security](#login-options)
5. 📡 [Actuator endpoints exposed](#actuator-endpoints)
6. ⏱️ [Quartz demo job](#quartz)
7. 📝 [Logging to file](#logging)
8. ⚙️ [Configuration reference](#configuration)
9. 🧪 [Tests](#tests)
10. 🛠️ [Gotchas and troubleshooting](#gotchas)
11. ❓ [Q&A](#qa)
    - 11.1 [Does Spring Boot Admin poll, or do the clients push?](#qa-sba-poll-or-push)
    - 11.2 [If we don't use Eureka, do we have to list each client in SBA?](#qa-without-eureka)
    - 11.3 [So without Eureka it's a push from the client?](#qa-push-without-eureka)
    - 11.4 [Does Eureka save SBA from calling every instance, by replacing those calls with one call to Eureka?](#qa-eureka-saves-calls)
    - 11.5 [Can we avoid `spring-boot-admin-starter-client` in every service and rely on Eureka alone?](#qa-eureka-only)
    - 11.6 [What are the `AuditEventRepository` and `HttpExchangeRepository` beans in `ActuatorConfig` for?](#qa-actuator-repositories)
    - 11.7 [Can we use Basic auth to log in to the SBA UI but OAuth2 towards the backend services?](#qa-basic-ui-oauth-backend)
    - 11.8 🔥 [`SecurityConfig` exempts `/instances/**` and `/actuator/**` from CSRF — what does a malicious page do with a logged-in SBA session, and how narrow should the exemption be?](#qa-csrf-exemption-scope)
    - 11.9 🔥 [Someone clicks Heap Dump on a production `order-service` in SBA — what happens to that instance, its callers and the data?](#qa-heapdump-click-production)
    - 11.10 🔥 [Why is SBA a weak primary alerting system, even after you add a notifier?](#qa-sba-as-alerting)
    - 11.11 🔥🔥 [Run admin-server as two instances behind a load balancer — what breaks with this module's in-memory state, and is HA worth it at all?](#qa-sba-high-availability)
    - 11.12 🔥🔥 [SBA suddenly shows the three Postgres-backed services OFFLINE, while Eureka shows them UP and the gateway serves traffic — debug it](#qa-mass-offline-debug)

Spring Boot Admin (SBA) dashboard for the Eureka demo. Back to the project
overview: [../README.md](../README.md).

<a id="overview"></a>

## <span style="color:hsl(288,80%,58%)">1. 🧩 Overview</span>

- ***Role: a separate observer, not in the request path.*** It shows health,
  metrics, logs, environment, beans and so on for every instance registered in
  Eureka, and lets you act on them (change log levels, refresh, restart, shut down).
- ***Port `8084`***, dashboard at http://localhost:8084.
- ***Reactive stack.*** The SBA 4 server starter runs on WebFlux/Netty, so
  everything here (security, filters, tests) is the reactive variant.
- ***Finds apps through Eureka, not by self-registration.*** With
  `spring-cloud-starter-netflix-eureka-client` on the classpath, SBA uses the
  Spring Cloud `DiscoveryClient` to list every service in the registry and turns
  each one into an SBA *instance*. The services don't need the SBA client library.
- ***It registers itself too.*** admin-server is a Eureka client (`eureka.client.service-url.defaultZone` →
  `localhost:8761`), so it appears in
  its own dashboard as `ADMIN-SERVER`, next to `EUREKA-SERVER`, `USER-SERVICE`,
  `ORDER-SERVICE`, `PRODUCT-SERVICE` and `GATEWAY-SERVICE`.
- Entry point: [AdminServerApplication.java](src/main/java/com/learning/discovery/admin/AdminServerApplication.java),
  annotated with `@EnableAdminServer` + `@SpringBootApplication`.

| Component         | Version (as of 2026)                        | Where it comes from    |
|-------------------|---------------------------------------------|------------------------|
| Java              | 27                                          | `super-pom` parent     |
| Spring Boot       | 4.1.1                                       | `super-pom` parent     |
| Spring Cloud      | 2025.1.x                                    | `super-pom` BOM import |
| Spring Boot Admin | 4.1.x (`spring-boot-admin-starter-server`)  | `super-pom` BOM import |
| Quartz            | Boot-managed (`spring-boot-starter-quartz`) | Boot BOM               |

Dependencies in [pom.xml](pom.xml):

| Dependency                                                                                   | Why                                                                                                             |
|----------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------|
| `spring-boot-admin-starter-server`                                                           | the SBA server and Vue UI                                                                                       |
| `spring-cloud-starter-netflix-eureka-client`                                                 | discovery of instances + self-registration; also brings Spring Cloud Context (`restart`, `refresh`, `env` POST) |
| `spring-boot-starter-actuator`                                                               | admin-server's own actuator endpoints                                                                           |
| `spring-boot-starter-security`                                                               | login for the UI and actuator                                                                                   |
| `spring-boot-starter-quartz`                                                                 | demo job behind the SBA "Quartz" view                                                                           |
| `spring-boot-starter-test`, `spring-boot-webtestclient`, `spring-boot-starter-security-test` | integration test ([§9](#tests))                                                                                 |

- ***No Jolokia***: the pom comments it out on purpose. `jolokia-support-springboot`
  needs a servlet container, and this server is WebFlux only.

<a id="run-and-login"></a>

## <span style="color:hsl(66,80%,50%)">2. 🚀 Run and log in</span>

- ***Start `eureka-server` first.*** admin-server starts without it, but shows no
  applications until it can fetch the registry.
- From the repo root:

```bash
cd learning-spring-cloud
mvn -pl eureka-server spring-boot:run -Dspring-boot.run.profiles=peer1 &
mvn -pl eureka-server spring-boot:run -Dspring-boot.run.profiles=peer2 &   # once peer1 is up; peer3 optional
# wait for http://localhost:8761, then:
mvn -pl admin-server spring-boot:run
```

- ***IntelliJ***: run `AdminServerApplication` (its `main` is package-private
  and `static void main` with no `public`, which Java 25+ allows).
- Open http://localhost:8084. You are redirected to `/login`.
- ***Credentials: `admin` / `admin`.*** Override them with environment variables:

```bash
ACTUATOR_USER=ops ACTUATOR_PASSWORD='s3cret' mvn -pl admin-server spring-boot:run
```

- ***Use the same values for every module.*** The same user is used for the UI
  login, admin-server's own actuator, the Eureka registry URL, and the Basic
  auth SBA sends to each service. If they differ, instances show up as
  `OFFLINE` or never appear at all.

<a id="security"></a>

## <span style="color:hsl(31,80%,58%)">3. 🔐 Security in detail</span>

Everything lives in [SecurityConfig.java](src/main/java/com/learning/discovery/admin/config/SecurityConfig.java).
It is SBA's documented "secure the server" setup, rewritten for WebFlux.

### <span style="color:hsl(339,80%,58%)">3.1 Filter chain</span>

- ***A reactive `SecurityWebFilterChain`, with `@EnableWebFluxSecurity`.*** A
  servlet `SecurityFilterChain` would be silently ignored on Netty.
- ***The user store is Boot's default `ReactiveUserDetailsService`***, a
  single in-memory user built from `spring.security.user.*` (role `ACTUATOR`).
  It is wrapped in `UserDetailsRepositoryReactiveAuthenticationManager`, then in
  an auditing decorator ([§3.4](#security)).
- All paths are prefixed with `AdminServerProperties.path(...)`, so the setup
  keeps working if `spring.boot.admin.context-path` is ever set.

| Rule            | Paths                                             | Effect                                                                     |
|-----------------|---------------------------------------------------|----------------------------------------------------------------------------|
| `permitAll`     | `/assets/**`, `/variables.css`, `/login`          | the login page and its CSS/JS load before you log in                       |
| `permitAll`     | `/actuator/health/**`, `/actuator/info`, `/error` | probes and the Eureka/SBA status checks need no credentials                |
| `authenticated` | everything else                                   | UI, SBA REST API (`/applications`, `/instances`), other actuator endpoints |

| Mechanism        | Config                                                                           | Behaviour                                                                                                        |
|------------------|----------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| ***Form login*** | `loginPage(/login)`, success → `RedirectServerAuthenticationSuccessHandler("/")` | browser requests (`Accept: text/html`) without a session get a `302` to `/login`; a successful login goes to `/` |
| ***Logout***     | `logoutUrl(/logout)`, success → `/login?logout`                                  | ends the session and shows SBA's "logged out" message                                                            |
| ***HTTP Basic*** | `httpBasic(...)` with the same auditing manager                                  | non-HTML clients (curl, JSON API) get a `401` + `WWW-Authenticate: Basic`                                        |

### <span style="color:hsl(116,80%,58%)">3.2 CSRF</span>

- ***The token is kept in a cookie the JS can read:***
  `CookieServerCsrfTokenRepository.withHttpOnlyFalse()` writes `XSRF-TOKEN`;
  the SBA Vue app (axios) sends it back as the `X-XSRF-TOKEN` header.
- ***`ServerCsrfTokenRequestAttributeHandler` (plain, not XOR-masked)***, so the raw cookie value is
  accepted as both header and `_csrf` form field, which is what the SBA UI sends.
- ***Exempt paths***, which are combined with `CsrfWebFilter.DEFAULT_CSRF_MATCHER` (so GET/HEAD/OPTIONS/TRACE
  are never checked):

| Exempt path                   | Why                                                                                      |
|-------------------------------|------------------------------------------------------------------------------------------|
| `/instances`, `/instances/**` | SBA clients self-register here with a plain POST, so there's no browser session or token |
| `/actuator/**`                | tooling and SBA calls with Basic auth (e.g. `POST /actuator/refresh`)                    |

- ***`csrfCookieWebFilter` bean.*** On WebFlux the CSRF token is a deferred
  `Mono<CsrfToken>`, and the cookie is only written if something subscribes to
  it. This lowest-precedence `WebFilter` subscribes on every request, so the SPA
  always has an `XSRF-TOKEN` cookie.
- ***[CsrfControllerAdvice.java](src/main/java/com/learning/discovery/admin/config/CsrfControllerAdvice.java).***
  SBA's Thymeleaf templates (`login.html`, `sba-settings.js`) read `${_csrf}`.
  Servlet security exposes that as a request attribute on its own; WebFlux does not.
  The `@ControllerAdvice` adds the `Mono<CsrfToken>` to the model as `_csrf`. ***Without it the login form posts with no
  token and gets a 403.***

### <span style="color:hsl(254,80%,58%)">3.3 Remember-me is disabled</span>

- ***Reactive Spring Security has no `rememberMe()`*** DSL, so there is nothing
  to back the checkbox.
- `spring.boot.admin.ui.remember-me-enabled: false` hides the checkbox in the
  SBA login page, so users aren't offered a feature that doesn't work.

### <span style="color:hsl(169,80%,58%)">3.4 Audit events for logins</span>

- ***Reactive security publishes no authentication events by default*** (the
  servlet `ProviderManager` does). So Boot's `AuthenticationAuditListener` would
  never see a login.
- `auditing(...)` wraps the manager with a `DefaultAuthenticationEventPublisher`:
  `doOnNext` → `publishAuthenticationSuccess`, `doOnError(AuthenticationException)`
  → `publishAuthenticationFailure`.
- The listener turns these into `AUTHENTICATION_SUCCESS` / `AUTHENTICATION_FAILURE`
  audit events, stored in the `InMemoryAuditEventRepository` from
  [ActuatorConfig.java](src/main/java/com/learning/discovery/admin/config/ActuatorConfig.java)
  and shown in SBA's "Audit Log" view (`/actuator/auditevents`).
- This covers both form logins and Basic logins, because both use the same wrapped manager.

### <span style="color:hsl(306,80%,58%)">3.5 How SBA authenticates to each service</span>

- Every service's actuator (except health/info) requires Basic auth, so SBA has
  to send credentials when it polls them.
- ***Per-instance metadata wins.*** SBA's `BasicAuthHttpHeaderProvider` reads
  `user.name` / `user.password` from the instance metadata. With Eureka that is
  each service's `eureka.instance.metadata-map`, which is copied into the
  registry and then into SBA. admin-server publishes its own metadata, because
  SBA discovers itself as well.
- ***Fallback: `spring.boot.admin.instance-auth`*** with `enabled: true` and
  `default-user-name` / `default-password` set to `spring.security.user.*`.
  This is used for any instance without metadata credentials.
- The header goes on every SBA→instance call: status polling, the endpoint
  detection probe, and the actuator calls the UI proxies (`/instances/{id}/actuator/**`).

```mermaid
sequenceDiagram
    autonumber
    participant B as Browser
    participant SBA as admin-server :8084
    participant E as eureka-server :8761
    participant S as user-service :8081
    B ->> SBA: GET / (text/html)
    SBA -->> B: 302 /login (+ XSRF-TOKEN cookie)
    B ->> SBA: GET /login
    SBA -->> B: login.html with _csrf (CsrfControllerAdvice)
    B ->> SBA: POST /login username, password, _csrf
    SBA ->> SBA: auditing manager → AUTHENTICATION_SUCCESS event
    SBA -->> B: 302 / (SESSION cookie)
    loop every registry refresh
        SBA ->> E: GET /eureka/apps (Basic admin:admin)
        E -->> SBA: instances + metadata-map (user.name, user.password)
    end
    B ->> SBA: GET /instances/{id}/actuator/metrics (session + X-XSRF-TOKEN)
    SBA ->> S: GET /actuator/metrics (Authorization: Basic from metadata)
    S -->> SBA: 200 JSON
    SBA -->> B: 200 JSON → rendered in UI
```

<a id="login-options"></a>

## <span style="color:hsl(84,80%,58%)">4. 🔑 Types of supported security</span>

How *people* log into the dashboard. All of them must be the reactive variant,
because this is WebFlux.

| Option                                | How on WebFlux                                                                                                                                                                          | Notes                                                                                                                                             |
|---------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| ***In-memory single user (current)*** | Boot's default from `spring.security.user.*`                                                                                                                                            | fine for a demo; one shared password, no per-person audit                                                                                         |
| Multiple in-memory users              | `MapReactiveUserDetailsService` bean with `User.withUsername(..).password(bcryptEncoder.encode(..))` + a `PasswordEncoder` bean (`BCryptPasswordEncoder` / `DelegatingPasswordEncoder`) | still in config, but gives per-person audit events and roles                                                                                      |
| Database users                        | custom `ReactiveUserDetailsService` backed by R2DBC (`spring-boot-starter-data-r2dbc`)                                                                                                  | stay fully non-blocking; JPA/JDBC would block the event loop                                                                                      |
| LDAP / Active Directory               | `ReactiveAuthenticationManagerAdapter` wrapping a blocking `LdapAuthenticationProvider` / `ActiveDirectoryLdapAuthenticationProvider`                                                   | ***blocking caveat***: the adapter runs it on `Schedulers.boundedElastic()`. That works, but it isn't truly reactive                              |
| ***OAuth2 / OIDC login***             | `spring-boot-starter-oauth2-client` + `http.oauth2Login(...)`; IdP: Keycloak, Okta, Microsoft Entra ID, Google, GitHub (GitHub is OAuth2 only, no OIDC)                                 | ***recommended for production***: SSO, MFA and group→role mapping are handled by the IdP. SBA's own `/login` page is replaced by the IdP redirect |
| SAML2                                 | `saml2Login()` exists for ***servlet only***                                                                                                                                            | ***not available on WebFlux***. Use OIDC, or put a SAML-capable proxy in front                                                                    |
| X.509 client certificates (mTLS)      | `server.ssl.client-auth=need` + `http.x509(...)`                                                                                                                                        | good for machine access or locked-down admin networks; awkward in browsers                                                                        |
| Remember-me                           | —                                                                                                                                                                                       | ***not on WebFlux*** (no reactive `rememberMe()`); use longer session timeouts or IdP SSO instead                                                 |

How *SBA* authenticates to each service:

| Option                                   | How                                                                                                                                                                                                                                                                                                                                  | Notes                                                                                                  |
|------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------|
| ***Metadata Basic auth (current)***      | each service sets `eureka.instance.metadata-map.user.name/user.password`; `BasicAuthHttpHeaderProvider` sends it                                                                                                                                                                                                                     | simple, but the password sits in the Eureka registry, so anyone who can read `/eureka/apps` can see it |
| Per-service defaults                     | `spring.boot.admin.instance-auth.service-map.<service-name>.user-name/user-password`                                                                                                                                                                                                                                                 | keeps credentials out of the registry; they're configured centrally in admin-server                    |
| ***OAuth2 client credentials (bearer)*** | custom `HttpHeadersProvider` bean that gets a token through `ReactiveOAuth2AuthorizedClientManager` (client-credentials grant) and sets `Authorization: Bearer ...`; each service becomes a JWT resource server (`spring-boot-starter-oauth2-resource-server`, `http.oauth2ResourceServer(jwt())`) that requires an `actuator` scope | no shared passwords; tokens expire and can be revoked; per-service scopes                              |
| mTLS                                     | SBA's `WebClient` configured with a client key store; services require client certs on the management port                                                                                                                                                                                                                           | strongest transport identity; you have to run a certificate PKI                                        |

- ***Recommendation for production***: ***Keycloak (or any OIDC IdP) for
  dashboard login*** + ***OAuth2 client credentials*** for SBA→service calls.
  That gives one identity system, short-lived tokens, and no static passwords in
  Eureka metadata.

<a id="actuator-endpoints"></a>

## <span style="color:hsl(201,80%,58%)">5. 📡 Actuator endpoints exposed</span>

admin-server's own actuator endpoints. SBA shows them for the `ADMIN-SERVER`
instance, just as it does for the other services. All of them except
`health`/`info` need authentication.

| Endpoint                 | SBA view                                  | Backed by                                                                                                                                                                   |
|--------------------------|-------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `health`                 | Details → Health (`show-details: always`) | Boot auto-config (Eureka, disk, ping, ...)                                                                                                                                  |
| `info`                   | Details → Info                            | Boot auto-config                                                                                                                                                            |
| `metrics`                | Details → Metrics, JVM, process           | Micrometer                                                                                                                                                                  |
| `env` (GET + ***POST***) | Environment (+ "Update" form)             | `management.endpoint.env.post.enabled: true`, Spring Cloud Context                                                                                                          |
| `refresh` (POST)         | Environment → "Refresh"                   | Spring Cloud Context; `access: unrestricted`; re-binds `@ConfigurationProperties` / `@RefreshScope`                                                                         |
| `restart` (POST)         | "Restart" button                          | Spring Cloud Context; `access: unrestricted`                                                                                                                                |
| `shutdown` (POST)        | "Shutdown" button                         | `access: unrestricted`                                                                                                                                                      |
| `loggers`                | Loggers (change levels at runtime)        | Boot auto-config                                                                                                                                                            |
| `logfile`                | Logfile                                   | `logging.file.name` ([§7](#logging))                                                                                                                                        |
| `heapdump`               | Heap Dump (download)                      | `access: unrestricted`; still behind login                                                                                                                                  |
| `threaddump`             | Thread Dump                               | Boot auto-config                                                                                                                                                            |
| `configprops`            | Configuration Properties                  | Boot auto-config                                                                                                                                                            |
| `beans`                  | Beans                                     | Boot auto-config                                                                                                                                                            |
| `mappings`               | Mappings                                  | Boot auto-config (WebFlux handler mappings)                                                                                                                                 |
| `httpexchanges`          | HTTP Exchanges                            | `InMemoryHttpExchangeRepository` bean in [ActuatorConfig.java](src/main/java/com/learning/discovery/admin/config/ActuatorConfig.java), recorded by `HttpExchangesWebFilter` |
| `startup`                | Startup                                   | `BufferingApplicationStartup(2048)` set in [AdminServerApplication.java](src/main/java/com/learning/discovery/admin/AdminServerApplication.java) `main`                     |
| `auditevents`            | Audit Log                                 | `InMemoryAuditEventRepository` bean + the login auditing in [§3.4](#security)                                                                                               |
| `sbom`                   | SBOM                                      | `META-INF/sbom/application.cdx.json` written by the `cyclonedx-maven-plugin` declared in the [root pom](../pom.xml)                                                         |
| `quartz`                 | Quartz                                    | `HeartbeatJob` ([§6](#quartz))                                                                                                                                              |

- ***`startup` exists only when started through `main`.*** `@SpringBootTest`
  doesn't call `main`, so the test context has no `BufferingApplicationStartup`
  and no `startup` endpoint.
- ***`sbom` needs a Maven build.*** The CycloneDX file is generated during the
  package lifecycle. Run `mvn package` (or `verify`) once if the view is empty.
- ***Why there's no `jolokia`***: the SBA 4 server is WebFlux only and
  `jolokia-support-springboot` needs a servlet container, so the SBA "JMX" view
  isn't available for admin-server. The servlet-based services can still offer it.
- ***`heapdump` is sensitive***: a heap dump contains secrets in memory (including
  the passwords above). Keep the port private, or drop it from the exposure list.
- ***`auditevents` and `httpexchanges` need a repository bean.*** Boot doesn't create one,
  so without the two beans in
  [ActuatorConfig.java](src/main/java/com/learning/discovery/admin/config/ActuatorConfig.java)
  both endpoints and their SBA views disappear. Both stores are in memory: see [Q6](#qa-actuator-repositories).

### Health probes, info and tags

- ***Probes*** — `management.endpoint.health.probes.enabled: true` adds public `/actuator/health/liveness` and
  `/actuator/health/readiness` (SBA Details → Health groups).
- ***Info*** — `info.app.*` (name, description, `tier: infra`, environment) plus the `java`, `os`, `process`
  contributors (`management.info.*.enabled`) → SBA Details → Info.
- ***Tags*** — `eureka.instance.metadata-map.tags.environment` (`APP_ENV`, default `local`) and `tags.tier: infra` →
  badges in SBA's application list.
- Custom health indicators and metrics live in [order-service](../order-service/README.md) only.

<a id="quartz"></a>

## <span style="color:hsl(13,80%,58%)">6. ⏱️ Quartz demo job</span>

- [QuartzConfig.java](src/main/java/com/learning/discovery/admin/job/QuartzConfig.java)
  declares a durable `JobDetail` `demo.heartbeatJob` and a `SimpleTrigger`
  `demo.heartbeatTrigger` that fires ***every minute, forever***.
- [HeartbeatJob.java](src/main/java/com/learning/discovery/admin/job/HeartbeatJob.java)
  extends `QuartzJobBean` and logs `Heartbeat from admin-server (fire time ...)`.
- It exists so the SBA ***Quartz*** view has something to show: jobs, triggers,
  next fire time. The scheduler is Boot's in-memory `RAMJobStore` (no JDBC store
  is configured).

```java

@Bean
Trigger heartbeatTrigger(JobDetail heartbeatJobDetail) {
    return TriggerBuilder.newTrigger()
            .forJob(heartbeatJobDetail)
            .withIdentity("heartbeatTrigger", "demo")
            .withSchedule(SimpleScheduleBuilder.repeatMinutelyForever())
            .build();
}
```

<a id="logging"></a>

## <span style="color:hsl(229,80%,58%)">7. 📝 Logging to file</span>

- `logging.file.name: logs/${spring.application.name}.log` → `logs/admin-server.log`,
  relative to the working directory. Running from the module directory puts it
  at `admin-server/logs/`; running from the repo root puts it at `logs/`.
- ***Setting a log file is what turns on `/actuator/logfile`***, and with it the
  SBA "Logfile" view. Without a file the endpoint returns 404.
- The heartbeat line ([§6](#quartz)) appears there once a minute, which is a
  quick way to check the Logfile view works.
- [banner.txt](src/main/resources/banner.txt) prints an "ADMIN SVC" ASCII banner at startup.

<a id="configuration"></a>

## <span style="color:hsl(271,80%,58%)">8. ⚙️ Configuration reference</span>

[application.yml](src/main/resources/application.yml), annotated:

<details><summary><b>application.yml — click to view</b></summary>

```yaml
spring:
  application:
    name: admin-server                     # Eureka app name ADMIN-SERVER; log file name
  security:
    user: # one user for the UI form login AND actuator Basic auth
      name: ${ACTUATOR_USER:admin}
      password: ${ACTUATOR_PASSWORD:admin}
      roles: ACTUATOR
  boot:
    admin:
      ui:
        remember-me-enabled: false         # no reactive rememberMe(); hide the checkbox
      instance-auth: # Basic auth SBA sends to each instance's actuator
        enabled: true                      # metadata user.name/user.password wins,
        default-user-name: ${spring.security.user.name}   # these are the fallback
        default-password: ${spring.security.user.password}

server:
  port: 8084

eureka:
  client:
    service-url: # the registry itself is Basic-protected
      defaultZone: http://${spring.security.user.name}:${spring.security.user.password}@localhost:8761/eureka/
  instance:
    prefer-ip-address: true                # register by IP, not hostname
    metadata-map: # SBA discovers itself, so it needs its own credentials
      user.name: ${spring.security.user.name}
      user.password: ${spring.security.user.password}

logging:
  file:
    name: logs/${spring.application.name}.log   # enables /actuator/logfile

management:
  endpoints:
    web:
      exposure:
        include: [ health, info, heapdump, logfile, env, shutdown, restart, refresh,
                   metrics, loggers, threaddump, configprops, beans, mappings,
                   httpexchanges, startup, auditevents, sbom, quartz ]
  endpoint:
    shutdown: { access: unrestricted }
    restart: { access: unrestricted }
    refresh: { access: unrestricted }
    env: { post: { enabled: true } }
    health: { show-details: always }
    heapdump: { access: unrestricted }
```

</details>

| Environment variable | Default | Used for                                                              |
|----------------------|---------|-----------------------------------------------------------------------|
| `ACTUATOR_USER`      | `admin` | UI login, actuator Basic, Eureka URL, metadata, instance-auth default |
| `ACTUATOR_PASSWORD`  | `admin` | same as above                                                         |

<a id="tests"></a>

## <span style="color:hsl(311,80%,58%)">9. 🧪 Tests</span>

[AdminServerApplicationIT.java](src/test/java/com/learning/discovery/admin/AdminServerApplicationIT.java):
`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureWebTestClient`
(WebFlux) + `@ActiveProfiles("test")`.
[application-test.yml](src/test/resources/application-test.yml) sets
`eureka.client.enabled: false`, so no live registry is needed.

| Test                                              | What it checks                                                                                                                                                     |
|---------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `healthEndpointReportsUpWithoutCredentials`       | `GET /actuator/health` without credentials → `200`, `status=UP` (permitAll)                                                                                        |
| `uiRootWithoutLoginRedirectsToLoginPage`          | `GET /` with `Accept: text/html` → `302` to `/login` (form login entry point)                                                                                      |
| `apiWithoutCredentialsIsUnauthorized`             | `GET /applications` with `Accept: application/json` → `401` (Basic entry point)                                                                                    |
| `loginPageIsPublicAndCarriesCsrfToken`            | `GET /login` → `200`, an `XSRF-TOKEN` cookie is set, and the HTML contains `"parameterName":"_csrf"` (proves `csrfCookieWebFilter` + `CsrfControllerAdvice`)       |
| `formLoginWithCsrfTokenRedirectsToUiAndIsAudited` | POST `/login` with cookie + `_csrf` → `302` to `/`; then `/actuator/auditevents?type=AUTHENTICATION_SUCCESS` shows principal `admin` (proves the auditing wrapper) |
| `formLoginWithoutCsrfTokenIsForbidden`            | POST `/login` with no token → `403`                                                                                                                                |
| `quartzHeartbeatJobIsRegistered`                  | `GET /actuator/quartz/jobs/demo/heartbeatJob` → `200`, `name=heartbeatJob`                                                                                         |

Run:

```bash
# this module only (failsafe runs *IT classes in the verify phase)
mvn -pl admin-server verify

# single test
mvn -pl admin-server verify -Dit.test=AdminServerApplicationIT
```

<a id="gotchas"></a>

## <span style="color:hsl(46,80%,58%)">10. 🛠️ Gotchas and troubleshooting</span>

| Symptom                                               | Likely cause                                                                      | Fix                                                                                                                                                                                      |
|-------------------------------------------------------|-----------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| ***Dashboard is empty***                              | eureka-server not running or not reachable, or wrong credentials in `defaultZone` | start `eureka-server` first; check `ACTUATOR_USER`/`ACTUATOR_PASSWORD` match; wait ~30s for the registry fetch                                                                           |
| ***Instance shows `OFFLINE`***                        | SBA can't reach the registered address, or gets a 401 from its actuator           | check the service sets `prefer-ip-address: true` (hostname may not resolve) and has `metadata-map.user.name/user.password`; credentials must match that service's `spring.security.user` |
| Instance `UP` but many views missing                  | endpoint not exposed on that service                                              | add it to that service's `management.endpoints.web.exposure.include`                                                                                                                     |
| ***Login returns 403***                               | CSRF token missing: the cookie is blocked, or `CsrfControllerAdvice` was removed  | allow cookies for `localhost:8084`; keep the advice and `csrfCookieWebFilter`                                                                                                            |
| Login page loops back to `/login`                     | wrong password (check the Audit Log for `AUTHENTICATION_FAILURE`)                 | use the env-var credentials                                                                                                                                                              |
| curl to the API gets `401` instead of a redirect      | expected: non-HTML requests get the Basic challenge                               | `curl -u admin:admin http://localhost:8084/applications`                                                                                                                                 |
| Logfile view says not found                           | no `logging.file.name`, or the file was deleted                                   | keep the property; the file is recreated on the next log line                                                                                                                            |
| SBOM view empty                                       | app started without a Maven build that ran CycloneDX                              | `mvn -pl admin-server package` once                                                                                                                                                      |
| Restarted eureka-server, instances vanish             | registry is in-memory; clients re-register on their next heartbeat (~30s)         | ***restart order: eureka-server first***, then the services, then admin-server; or just wait                                                                                             |
| "Restart" on admin-server itself drops the UI session | the context restarts, and so does the in-memory session store                     | log in again                                                                                                                                                                             |

<a id="qa"></a>

## <span style="color:hsl(0,80%,58%)">11. ❓ Q&A</span>

<a id="qa-sba-poll-or-push"></a>

**Q1. Does Spring Boot Admin poll, or do the clients push?**

- ***SBA polls.*** In this repo nothing is pushed to SBA at all.
- Two separate steps:

| Step                                 | Push or pull                                     | In this repo                                                               |
|--------------------------------------|--------------------------------------------------|----------------------------------------------------------------------------|
| **Finding instances** (registration) | either SBA-client **push** or **discovery pull** | **pull from Eureka** — services have no `spring-boot-admin-starter-client` |
| **Status, info, metrics**            | **always pull** by the SBA server                | SBA calls each service's `/actuator/...`                                   |

- ***Discovery*** — admin-server is a Eureka client; each registry refresh (`registry-fetch-interval-seconds`, default
  30 s) adds new instances and drops removed ones.
- ***What the server polls*** (as of SBA 4.1, 2026):

| What                     | Endpoint           | Default | Property                                    |
|--------------------------|--------------------|---------|---------------------------------------------|
| Status (UP/DOWN/OFFLINE) | `/actuator/health` | 10 s    | `spring.boot.admin.monitor.status-interval` |
| Info panel               | `/actuator/info`   | 1 min   | `spring.boot.admin.monitor.info-interval`   |
| Request timeout          | —                  | 10 s    | `spring.boot.admin.monitor.default-timeout` |

- ***Failing instances are polled less often*** — back-off up to `status-max-backoff`.
- ***OFFLINE ≠ DOWN*** — OFFLINE: `/actuator/health` unreachable (process dead, network, auth); DOWN: the app answered
  "DOWN".
- ***Notifications fire on a status change found by polling*** — up to ~10 s plus the Eureka refresh delay.
- ***UI views fetch on demand*** — Metrics, Threads, Logfile (~1 s byte-range polling) etc. are fetched by the browser
  through `/instances/{id}/actuator/**` only while the page is open; otherwise only health/info polling runs.
- ***Consequences*** — services don't depend on SBA (SBA down = no impact on them); SBA must reach every instance (right
  IP via `prefer-ip-address`, credentials from Eureka metadata), or it shows OFFLINE; load is one `/health` call per
  instance every 10 s.

<a id="qa-without-eureka"></a>

**Q2. If we don't use Eureka, do we have to list each client in SBA?**

- ***No — only with the static-list option.*** Four ways:

| Option                                              | Where configured                                               | List every instance?    | Fits                                                          |
|-----------------------------------------------------|----------------------------------------------------------------|-------------------------|---------------------------------------------------------------|
| **SBA client** (`spring-boot-admin-starter-client`) | each app: `spring.boot.admin.client.url`                       | no — apps self-register | plain VMs, no registry, apps change often                     |
| **Static list** (`SimpleDiscoveryClient`)           | SBA server: `spring.cloud.discovery.client.simple.instances.*` | **yes**, by hand        | 2–3 fixed hosts                                               |
| **Other registry** (Kubernetes, Consul, Zookeeper)  | SBA server: matching Spring Cloud discovery starter            | no                      | Kubernetes (needs RBAC to list services/pods/endpoints)       |
| **Manual** `POST /instances`                        | SBA REST API (script/CI)                                       | per call                | one-off or non-Spring apps with actuator-compatible endpoints |

SBA-client config on each app:

```yaml
spring:
  boot:
    admin:
      client:
        url: http://localhost:8084                  # SBA server
        username: ${ACTUATOR_USER:admin}            # to log in to SBA's /instances
        password: ${ACTUATOR_PASSWORD:admin}
        instance:
          prefer-ip: true
          metadata: # SBA uses these to poll this app's actuator
            user.name: ${spring.security.user.name}
            user.password: ${spring.security.user.password}
```

Static list on the SBA server:

```yaml
spring:
  cloud:
    discovery:
      client:
        simple:
          instances:
            order-service:
              - uri: http://localhost:8082
                metadata:
                  management.context-path: /actuator
                  user.name: admin
                  user.password: admin
            user-service:
              - uri: http://localhost:8081
              - uri: http://localhost:8091          # 2nd instance
```

- ***SBA client*** — POSTs `/instances` at startup and every 10 s (keep-alive), deregisters on shutdown; if SBA is down
  the app keeps working and only logs a retry warning. SBA's security must accept that POST (here CSRF already ignores
  `/instances`).
- ***Static list*** — no change in the services, but scaling or a port change means editing SBA's config and restarting
  it.

<a id="qa-push-without-eureka"></a>

**Q3. So without Eureka it's a push from the client?**

- ***Only the registration is pushed, and only with the SBA client. Monitoring is still pulled.***

| Step                                 | With Eureka (this repo)                     | Without Eureka, SBA client                                  |
|--------------------------------------|---------------------------------------------|-------------------------------------------------------------|
| "I exist, here's my URL"             | SBA **pulls** the list from Eureka          | client **pushes** `POST /instances` at startup + every 10 s |
| Health / status                      | SBA **pulls** `/actuator/health` every 10 s | SBA **pulls** `/actuator/health` every 10 s                 |
| Info, metrics, logfile, heap dump, … | SBA **pulls** `/actuator/*`                 | SBA **pulls** `/actuator/*`                                 |

```text
client ── POST /instances {name, healthUrl, managementUrl, metadata} ──▶ SBA      (push: registration only)
client ◀── GET /actuator/health, /info, /metrics … ───────────────────── SBA      (pull: everything else)
```

- ***The push carries only name, URLs and metadata*** (e.g. credentials) — no health or metrics data.
- ***SBA must still reach each client's `/actuator`*** — the network/firewall has to allow SBA → app in both setups.
- ***Static list, Kubernetes and Consul involve no push at all.***

<a id="qa-eureka-saves-calls"></a>

**Q4. Does Eureka save SBA from calling every instance, by replacing those calls with one call to Eureka?**

- ***Partly — Eureka replaces only the discovery calls, not health polling.*** SBA still calls every instance itself.

| Traffic (N instances)        | SBA client (push)                         | Eureka discovery (this repo)                                      |
|------------------------------|-------------------------------------------|-------------------------------------------------------------------|
| Discovery / registration     | **N** `POST /instances` to SBA every 10 s | **1** registry fetch from Eureka every 30 s (delta, only changes) |
| Health polling               | **N** `GET /actuator/health` every 10 s   | **N** `GET /actuator/health` every 10 s (same)                    |
| Info polling                 | N every 1 min                             | N every 1 min (same)                                              |
| UI views (metrics, logfile…) | on demand, per instance                   | on demand, per instance (same)                                    |

- ***Why SBA doesn't just use Eureka's status*** — a Eureka heartbeat only proves the process is alive; SBA's status is
  `/actuator/health`, the aggregate of all health indicators (e.g. order-service's `db` and `ordersCache`), so it shows
  DOWN when Postgres fails while Eureka still says UP.
- ***Eureka gives SBA the URLs, not the data*** — `healthCheckUrl`, management port and metadata tell SBA where to poll;
  metrics, logfile, heap dump etc. exist only on the instance.
- ***`eureka.client.healthcheck.enabled: true` doesn't change this*** — it feeds actuator health into the Eureka UP/DOWN
  status, but SBA still polls for details.
- ***Reducing the load*** — `spring.boot.admin.monitor.status-interval: 30s` (default 10 s; alerts arrive later),
  `info-interval: 5m`. It only matters with hundreds of instances: the default is one light GET per instance every 10 s.
- ***Heartbeats aren't extra cost for SBA*** — services send them anyway so the gateway and order → user calls can find
  each other; SBA just reads the same registry.

<a id="qa-eureka-only"></a>

**Q5. Can we avoid `spring-boot-admin-starter-client` in every service and rely on Eureka alone?**

- ***Yes — this repo already does.*** None of the 6 services has the SBA client; each needs only Eureka + actuator, and
  admin-server needs only `spring-cloud-starter-netflix-eureka-client` (instances are discovered automatically, no
  per-service SBA config).

What each service needs (already in place):

| Need                | Config                                       | Why                                    |
|---------------------|----------------------------------------------|----------------------------------------|
| Eureka client       | `spring-cloud-starter-netflix-eureka-client` | to appear in the registry SBA reads    |
| Actuator exposed    | `management.endpoints.web.exposure.include`  | SBA polls these endpoints              |
| Reachable address   | `eureka.instance.prefer-ip-address: true`    | SBA calls the URL registered in Eureka |
| Credentials for SBA | `metadata-map.user.name` / `user.password`   | actuator is secured                    |
| Optional: tags      | `metadata-map.tags.*`                        | badges in SBA                          |

Eureka metadata SBA understands, if you move off the defaults:

| Metadata key                  | Needed when                                          |
|-------------------------------|------------------------------------------------------|
| `management.port`             | actuator runs on a separate `management.server.port` |
| `management.context-path`     | actuator isn't under `/actuator`                     |
| `health.path`                 | health isn't at `<context-path>/health`              |
| `user.name` / `user.password` | actuator is secured                                  |

SBA client vs Eureka only:

|                                         | SBA client                                      | Eureka only                                                                      |
|-----------------------------------------|-------------------------------------------------|----------------------------------------------------------------------------------|
| Extra dependency in every service       | yes                                             | **no**                                                                           |
| Services need SBA's URL and credentials | yes                                             | **no**                                                                           |
| New instance appears in SBA             | ≤ 10 s                                          | ≤ ~30–40 s (SBA's registry refresh + Eureka cache)                               |
| Shutdown removal                        | immediate DELETE (if auto-deregistration is on) | immediate on clean shutdown (Eureka cancel), else after lease expiry (30 s here) |
| Works without a registry                | yes                                             | no                                                                               |
| Non-Spring apps                         | possible (`POST /instances`)                    | only if they register with Eureka and expose actuator-style endpoints            |

- ***Only what's in Eureka shows up*** — a batch job or non-Spring app that doesn't register is invisible to SBA.
- ***Up to ~30 s lag for new instances*** — shorten with `eureka.client.registry-fetch-interval-seconds: 10` on
  admin-server.
- ***The registered address must be reachable from SBA*** — that's where `prefer-ip-address`, Docker networks and NAT
  matter.
- ***Verdict*** — Eureka only is the cleaner choice when Eureka already runs; keep the SBA client for apps outside the
  registry.

<a id="qa-actuator-repositories"></a>

**Q6. What are the `AuditEventRepository` and `HttpExchangeRepository` beans in `ActuatorConfig` for?**

- ***They switch on two actuator endpoints.*** Since Boot 2.2 neither repository is auto-configured; the endpoint exists
  only when a bean of that type is present.

| Bean                                                        | Endpoint                  | SBA view       | What is stored                                                                                  | Kept               |
|-------------------------------------------------------------|---------------------------|----------------|-------------------------------------------------------------------------------------------------|--------------------|
| `AuditEventRepository` (`InMemoryAuditEventRepository`)     | `/actuator/auditevents`   | Audit Log      | `AUTHENTICATION_SUCCESS` / `AUTHENTICATION_FAILURE` for UI and Basic logins ([§3.4](#security)) | last 1000 events   |
| `HttpExchangeRepository` (`InMemoryHttpExchangeRepository`) | `/actuator/httpexchanges` | HTTP Exchanges | method, URI, status, time taken, headers, recorded by `HttpExchangesWebFilter` (WebFlux)        | last 100 exchanges |

- ***Audit events need two parts*** — the repository stores them, but on WebFlux nothing publishes login events until
  `SecurityConfig.auditing(...)` wraps the authentication manager.
- ***In-memory limits*** — lost on restart, one store per instance, fixed size.
- ***Production*** — persist audit events (database, log shipping or SIEM) with your own `AuditEventRepository`; use
  tracing (Zipkin here) and access logs instead of `httpexchanges`, which also records headers that may be sensitive.

<a id="qa-basic-ui-oauth-backend"></a>

**Q7. Can we use Basic auth to log in to the SBA UI but OAuth2 towards the backend services?**

- ***Yes — they are two independent security paths.*** Keep the UI login as it is and swap only the outbound
  credentials from Basic to a bearer token.

| Path                                 | Secured by                                                       | Today                                                         |
|--------------------------------------|------------------------------------------------------------------|---------------------------------------------------------------|
| Browser → SBA UI (`:8084`)           | admin-server's own `SecurityWebFilterChain` (`SecurityConfig`)   | form/Basic login with `spring.security.user` ([§3](#security)) |
| SBA → each service's `/actuator/**`  | SBA's outbound WebClient (`instance-auth`, `BasicAuthHttpHeaderProvider`) | Basic, from Eureka metadata `user.name` / `user.password`     |

admin-server side:

1. Add `spring-boot-starter-oauth2-client` and register a `client_credentials` client:

   ```yaml
   spring:
     security:
       oauth2:
         client:
           registration:
             sba:
               provider: idp
               client-id: admin-server
               client-secret: ${SBA_CLIENT_SECRET}
               authorization-grant-type: client_credentials
               scope: actuator
           provider:
             idp:
               token-uri: http://localhost:9000/oauth2/token
   ```

2. Attach the token with a reactive `InstanceExchangeFilterFunction` (SBA 4's server is WebFlux, so not the blocking
   `HttpHeadersProvider`). The manager caches the token and fetches a new one when it expires:

   ```java
   @Bean
   ReactiveOAuth2AuthorizedClientManager authorizedClientManager(ReactiveClientRegistrationRepository registrations,
                                                                 ReactiveOAuth2AuthorizedClientService clients) {
       var manager = new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(registrations, clients);
       manager.setAuthorizedClientProvider(
               ReactiveOAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
       return manager;
   }

   @Bean
   InstanceExchangeFilterFunction bearerToken(ReactiveOAuth2AuthorizedClientManager manager) {
       return (instance, request, next) -> manager
               .authorize(OAuth2AuthorizeRequest.withClientRegistrationId("sba").principal("admin-server").build())
               .map(client -> ClientRequest.from(request)
                       .headers(h -> h.setBearerAuth(client.getAccessToken().getTokenValue()))
                       .build())
               .flatMap(next::exchange);
   }
   ```

3. Set `spring.boot.admin.instance-auth.enabled: false` and drop `user.name` / `user.password` from the Eureka
   metadata, otherwise SBA also sends a Basic `Authorization` header that competes with the bearer token.

Each backend service (user, order, product, gateway):

- Add `spring-boot-starter-oauth2-resource-server` with `spring.security.oauth2.resourceserver.jwt.issuer-uri`.
- Require `SCOPE_actuator` on the actuator filter chain.
- To migrate gradually, keep `httpBasic()` next to `oauth2ResourceServer(jwt)` on that chain until every caller has
  switched.

Things to know:

- ***An authorization server is needed*** — this repo has none; add a Spring Authorization Server module (e.g. on
  `:9000`) or run Keycloak via Docker Compose.
- ***Backends see the service, not the person*** — every call, including UI actions SBA proxies (Shutdown, Environment
  update), reaches the backend as the `admin-server` client. To carry the logged-in user's identity, the UI itself must
  use OAuth2 login with token relay, which means giving up Basic for the UI.
- ***Eureka is a third path*** — registration and heartbeats to `:8761` / `:8762` / `:8763` still use Basic via `defaultZone`;
  moving those to OAuth2 is a separate change.

<a id="qa-csrf-exemption-scope"></a>

**Q8. 🔥 `SecurityConfig` exempts `/instances/**` and `/actuator/**` from CSRF. What does a malicious page do with a
logged-in SBA session, and how narrow should the exemption be?**

- ***`/instances/**` is wider than SBA needs, and it covers the proxy.*** Every UI action on a service goes through
  `/instances/{id}/actuator/**` on admin-server ([Q1](#qa-sba-poll-or-push)), so the exemption also drops CSRF
  checks on `POST .../actuator/shutdown`, `.../env`, `.../refresh` and `.../loggers` for ***every*** instance. The only
  session check left on those requests is the session cookie.
- ***Attack*** — an engineer is logged in to `:8084`. A page they open sends
  `POST http://localhost:8084/instances/<id>/actuator/shutdown`. The browser attaches the `SESSION` cookie and SBA
  forwards the call with the Basic credentials from Eureka metadata ([§3.5](#security)). `order-service` stops.
- ***What stops it today is SameSite, not CSRF.*** The WebFlux session cookie defaults to `SameSite=Lax`, so a
  ***cross-site*** POST carries no cookie. But SameSite ignores ports: any page on `localhost:*` is ***same-site***
  (the gateway's Swagger UI on `:8080`, a dev server on `:3000`). One XSS on any of them, or a mistake on an internal
  domain shared by many apps, and SameSite does nothing.
- ***`/actuator/**` here is admin-server's own actuator***, so the same trick can call `POST /actuator/shutdown` on
  admin-server, or update its env.
- ***Narrow it to what the SBA reference config shows*** (as of SBA 4.x, 2026):

| Exempt                       | Why                                               |
|------------------------------|---------------------------------------------------|
| `POST /instances`            | SBA client self-registration (not used here)      |
| `DELETE /instances/*`        | SBA client deregistration (one path segment only) |
| `/actuator/**`               | machine callers of admin-server's own actuator    |

- ***The UI doesn't need the wider rule.*** The `XSRF-TOKEN` cookie plus the plain (non-XOR) request handler
  ([§3.2](#security)) let the SBA app send `X-XSRF-TOKEN` on proxied POSTs. After narrowing, check that changing a
  log level and the Environment form still work. Community posts that tell you to exempt `/instances/**` are usually
  working around a missing token.
- ***Defence in depth*** — add an `Origin`/`Referer` check on unsafe methods, require a separate role for the
  destructive proxied endpoints, and turn `shutdown`/`env` POST off on the services in prod (root
  [FAQ 7.10](../README.md#faq-shared-credentials-blast-radius)).

<a id="qa-heapdump-click-production"></a>

**Q9. 🔥 Someone clicks Heap Dump on a production `order-service` in SBA. What happens to that instance, its callers
and the data?**

- ***The JVM stops while the dump is written.*** `order-service` exposes `heapdump` with `access: unrestricted`.
  Boot's `HeapDumpWebEndpoint` defaults to `live=true`, which runs a ***full GC first***. HotSpot then writes the
  dump at a safepoint, so all application threads are paused for its length. That takes seconds for a small heap
  and can take tens of seconds for a multi-GB one.
- ***Knock-on effects of the pause***:
  - ***Requests*** — Tomcat threads freeze. The gateway and `order-service`'s callers see latency spikes, or timeouts
    if the pause is longer than their read timeout.
  - ***Registry*** — no heartbeats go out (one every 10 s here). If the pause goes past the 30 s lease, the instance
    can be evicted and then registers again afterwards (timeline in
    [eureka-server Q2](../eureka-server/README.md#qa-crash-eviction-timeline)).
  - ***SBA itself*** — its own health polls time out, so the instance flips to OFFLINE. Any notifier would page
    someone for an outage SBA caused.
- ***Disk*** — the endpoint writes a temp `.hprof` (about the size of the used heap) to the instance's disk, streams
  it, then deletes it. In a container that can fill ephemeral storage and get the pod evicted. A second request
  during a dump waits for a lock (about 10 s) and then gets `429`.
- ***Data*** — the file holds every live string: the shared `admin` password, the datasource password, sessions,
  cached orders. It travels through admin-server to a browser download folder. That is a data-handling incident,
  not just an ops action.
- ***Production practice***:

| Do                                                                          | Instead of                       |
|-----------------------------------------------------------------------------|----------------------------------|
| Don't expose `heapdump` in prod; take dumps with `jcmd <pid> GC.heap_dump` under a break-glass procedure | a button anyone logged in can press |
| Take the instance out of rotation first (Eureka `OUT_OF_SERVICE`), then dump | dumping a node that serves traffic |
| `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=<encrypted volume>`        | trying to catch an OOM by hand  |
| `?live=false` when you need the garbage too, or want to skip the forced GC   | the default full GC              |
| Class histogram (`jcmd <pid> GC.class_histogram`) for a first look           | a full dump for "what's big?"    |

<a id="qa-sba-as-alerting"></a>

**Q10. 🔥 Why is SBA a weak primary alerting system, even after you add a notifier?**

- ***It is one observer, polling from one place, holding state in memory.*** Nothing in this module sends alerts
  today: no notifier is configured, so a status change only shows on screen. Adding mail or chat notifiers fixes
  delivery but not these structural problems:
- ***Who watches the watcher*** — if admin-server dies or loses its route to Eureka, every alert stops silently. A
  dead SBA looks exactly like a healthy system.
- ***Wrong vantage point*** — OFFLINE means "SBA couldn't reach it". A partition between SBA and one subnet pages
  you for an outage users don't see ([Q12](#qa-mass-offline-debug)). The reverse case matters more: SBA reaches
  the instance directly, while users going through the gateway get 502s.
- ***Instance granularity, not service health*** — one of three `user-service` instances going DOWN is an alert,
  even though the service is fine. 30 % of requests failing while every `/actuator/health` says UP is no alert at
  all. SBA has no idea of error rate, latency or SLOs.
- ***State resets*** — the event store is in memory. After a restart every instance goes UNKNOWN → UP. Notifiers
  ignore that transition by default (`ignore-changes` defaults include `UNKNOWN:UP`), so a service that is already
  down when SBA starts may never alert.
- ***Detection delay*** — Eureka discovery lag plus the 10 s status interval, plus back-off on failing instances
  ([Q1](#qa-sba-poll-or-push)).
- ***Better split***:

| Job                     | Tool                                                                                      |
|-------------------------|-------------------------------------------------------------------------------------------|
| Paging                  | metrics (e.g. a Prometheus registry, not in these poms yet) + alert rules on error rate / latency SLO burn |
| Outside-in check        | a synthetic probe through the gateway, run from outside the cluster                       |
| "Is SBA itself alive?"  | the paging system probes admin-server like any other target                               |
| Drill-down after a page | SBA: env, loggers, threads, Quartz, logfile                                               |

- ***If SBA does notify*** — wrap the notifier in a reminding notifier for unresolved states and a filtering
  notifier for maintenance windows, and route OFFLINE to a lower severity than DOWN.

<a id="qa-sba-high-availability"></a>

**Q11. 🔥🔥 Run admin-server as two instances behind a load balancer. What breaks with this module's in-memory state,
and is HA worth it at all?**

***Most of the state is per instance, so two copies act as two separate SBAs that share a hostname.*** Fix the
session first, then decide whether you need shared events. Often the honest answer is "one instance, restarted
fast".

- ***What happens to each piece of state***:

| State                         | Where it lives here                                          | With 2 instances behind round-robin                                       | Fix                                                                     |
|-------------------------------|--------------------------------------------------------------|---------------------------------------------------------------------------|-------------------------------------------------------------------------|
| UI login session              | WebFlux in-memory `WebSession`                               | every other request lands on the instance that doesn't know you → back to `/login` | sticky sessions, or Spring Session on Redis (it has a reactive store) |
| CSRF token                    | `XSRF-TOKEN` cookie (`CookieServerCsrfTokenRepository`)      | ***fine***: nothing is stored on the server                               | —                                                                       |
| Status history / event store  | SBA in-memory event store                                    | each instance polls on its own and builds its own timeline; views differ for a few seconds | SBA's Hazelcast support shares the event store across nodes          |
| Notifications                 | none today                                                   | once added, ***each instance sends every alert*** → duplicates            | Hazelcast mode also keeps a "sent notifications" map to de-duplicate (check property names for your SBA version) |
| Audit log, HTTP exchanges     | `InMemory*Repository` ([Q6](#qa-actuator-repositories))      | split across nodes; who logged in where depends on the LB                 | ship audit events to a central store                                     |
| Quartz `HeartbeatJob`         | in-memory job store                                          | runs on both                                                              | harmless here; for real jobs, a clustered JDBC job store                 |

- ***Load on the services doubles*** — polling isn't split between nodes out of the box, so plan for 2 × N health
  GETs every 10 s. That is negligible at 10 instances. At thousands of instances, raise `status-interval` instead.
- ***Eureka side is fine*** — both register as `ADMIN-SERVER` and both read the same registry.
- ***Is it worth it?*** SBA is ***not in the request path*** ([§1](#overview)). When it's down, nothing fails for
  users; you lose the console and any SBA alerts.
  - ***Single instance + liveness-based auto-restart*** (Kubernetes restarts it in seconds) + paging from a separate
    system ([Q10](#qa-sba-as-alerting)) covers most teams. A restart costs a re-login and a fresh timeline.
  - ***Cluster with Hazelcast + Spring Session*** only when SBA notifications are a contractual alert path or many
    teams depend on the console. You take on a stateful Hazelcast cluster, its network config and its upgrades.
- ***Monitor*** — whatever pages you must also probe admin-server's own `/actuator/health`. SBA's view of itself
  doesn't count.

<a id="qa-mass-offline-debug"></a>

**Q12. 🔥🔥 SBA suddenly shows `order-service`, `user-service` and `product-service` OFFLINE, while the Eureka dashboard
shows them UP and the gateway serves traffic. Debug it.**

***Different observers disagree, so the cause is on SBA's path or in what SBA measures, not in the services
themselves.*** Eureka UP only proves outbound heartbeats (client → registry). SBA needs the opposite direction plus
a health response inside its timeout.

- ***First clue: which ones?*** The three OFFLINE modules are exactly the ones with Postgres. `gateway-service`,
  `eureka-server` and admin-server itself have no database and stay UP. That points at the ***health endpoint***,
  not the network.
- ***Hypotheses, most likely first***:

| # | Hypothesis | Why it fits | Check |
|---|------------|-------------|-------|
| 1 | `/actuator/health` is slow because the `db` indicator waits on Postgres or on an exhausted Hikari pool | health aggregates `db` (and `show-details: always`). OFFLINE = no answer within SBA's timeout (`spring.boot.admin.monitor.default-timeout`). Cached `GET /api/orders/{id}` keeps working, so the gateway looks fine | `curl -w '%{time_total}' -u admin:admin <instance>/actuator/health` from the admin-server host; Hikari `hikaricp.connections.pending` |
| 2 | Registered address unreachable from SBA | `prefer-ip-address: true` registers an IP. After a Docker network change or VPN reconnect, the IP may still route for the client's own heartbeats but not from SBA | `curl` the exact `healthCheckUrl` from `GET /eureka/apps/ORDER-SERVICE` on the SBA host |
| 3 | admin-server itself is stalled | a long GC pause or a blocked Netty event loop delays every poll | admin-server `jvm.gc.pause`, threaddump: `reactor-http-nio-*` threads in BLOCKED/WAITING |
| 4 | Credentials rotated | ***ruled out for status***: health is public here, so wrong credentials break other views with 401 but don't cause OFFLINE | — |

- ***Why the gateway doesn't notice #1*** — the gateway checks nothing. It routes on Eureka status, and Eureka
  doesn't use actuator health unless `eureka.client.healthcheck.enabled: true` (not set here). That's why the
  observers disagree.
- ***Fix depends on the cause***:
  - ***Slow DB health*** — bound it: a Hikari `connection-timeout` well below SBA's timeout, so the indicator fails
    fast to DOWN instead of hanging into OFFLINE. Fix the Postgres or pool problem itself. Think about pointing SBA at
    a cheaper group via Eureka metadata `health.path`; the trade-off is that SBA then stops seeing DB trouble.
  - ***Address*** — register a stable, routable address (`eureka.instance.ip-address` / hostname per network) and
    give SBA a network path to the backend subnet.
  - ***SBA stall*** — give it heap, find the blocking call in the threaddump, and don't run anything blocking on
    its event loop.
- ***Prevent the confusion*** — alert on the metric (DB pool wait, health latency), not only on SBA status. Show
  "Eureka UP but SBA OFFLINE" as its own signal: it means "the observer can't see", not "the service is down".
