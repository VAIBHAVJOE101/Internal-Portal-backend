# DevOps Portal – Backend

Spring Boot 4 (Java 25, Gradle/Groovy) backend for the internal Platform & DevOps portal. It works as a
backend-for-frontend (BFF): the browser holds only a session cookie, and all integration credentials stay on the server.

| Module | What it does |
|---|---|
| `inventory` | Dynamic inventory pages with user-defined columns (JSON records validated against the schema), CSV import/export, expiry tracking, and **system pages** that other modules consume |
| `kafka` | Multi-cluster Kafka operations. Clusters come from the **Kafka Instances** inventory page (broker IPs plus Connect/sink IPs). Topic create/alter-config/add-partitions/purge/delete, consumer groups and lag, offset reset, Kafka Connect connectors (create/update/delete/restart/pause/resume), raw Connect REST console, and a health job with alerts |
| `appkafka` | Application Kafka Portal: reads API Gateway routes from Cosmos DB (configurable field paths) and does single or bulk Kafka remaps with dry-run preview and ETag concurrency |
| `boards` | Azure Boards: current sprint, board columns, and work item updates (state, assignee, start/end, remaining work) |
| `github` | Workflow runs (re-run/cancel) and team membership (add/remove/invite), using the signed-in user's OAuth token |
| `connectivity` | DNS / TCP / HTTP / TLS checks from the pod, saved targets with **interval or cron schedules** (ShedLock across replicas), history, and alerts |
| `alerts` | Deduplicated alerts that auto-resolve, an SSE stream for the UI, and an optional Teams/Slack webhook |
| `audit` | Before/after audit trail for every mutating operation |
| `settings` | AES-256-GCM encrypted integration credentials, with a connection test per integration |

## Roles

Login uses **GitHub OAuth**:

- Active member of the `devops_team` team (`GITHUB_ADMIN_TEAM`) gets **ADMIN**.
- Any other active member of `GITHUB_ORG` gets **READER**.
- Users outside the org are rejected.

READER can call every `GET` endpoint. All mutations require ADMIN.

## Run locally

Mock mode is self-contained: H2 in memory, simulated Kafka, Azure Boards, GitHub and Cosmos DB, and demo users `admin/admin` and `reader/reader`.

```bash
./gradlew bootRun --args='--spring.profiles.active=mock'
```

Real mode uses PostgreSQL plus a local Kafka and Kafka Connect from docker-compose:

```bash
docker compose up -d
export GITHUB_OAUTH_CLIENT_ID=... GITHUB_OAUTH_CLIENT_SECRET=... GITHUB_ORG=your-org
./gradlew bootRun          # profile "local"
```

- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: `/actuator/health`

### GitHub OAuth app

Create the app under the org (Settings → Developer settings → OAuth Apps):

- **Homepage URL:** `https://portal.example.com`
- **Callback URL:** `https://portal.example.com/login/oauth2/code/github`. For local development use `http://localhost:5173/login/oauth2/code/github`, because the Vite dev server proxies to the backend.

Requested scopes are `read:user, read:org, repo, workflow, admin:org`. Change them with `GITHUB_OAUTH_SCOPES`. Team changes still need the user to be an org owner or team maintainer. If the org restricts OAuth app access, approve the app for the org.

## Configuration

| Env var | Purpose |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL |
| `PORTAL_MODE` | `real` (default) or `mock` |
| `PORTAL_ENCRYPTION_KEY` | Base64 32-byte key for encrypting secrets at rest (`openssl rand -base64 32`) |
| `GITHUB_OAUTH_CLIENT_ID` / `_SECRET`, `GITHUB_ORG`, `GITHUB_ADMIN_TEAM` | Login and role mapping |
| `GITHUB_TOKEN`, `AZDO_ORG`, `AZDO_PROJECT`, `AZDO_TEAM`, `AZDO_PAT`, `COSMOS_*`, `ALERT_WEBHOOK_URL` | Optional defaults. Values saved in **Settings** take precedence |
| `PORTAL_COOKIE_SECURE` | `true` behind TLS |

Kafka SASL credentials are stored under **Settings → Kafka credentials** and referenced from the `credentialRef` column of each Kafka instance.

## Database

Flyway migrations live in `src/main/resources/db/migration`. JSON values are stored as text through the `${json}` placeholder, so the same scripts run on PostgreSQL and H2. Inventory filtering and sorting happen in memory, which is appropriate for inventory-sized data (thousands of rows per page).

## Tests

```bash
./gradlew test     # Spring Boot + MockMvc tests against the mock profile
```

## Deploy to Kubernetes

```bash
docker build -t ghcr.io/your-org/devops-portal-backend:0.1.0 .
kubectl create namespace devops-portal            # or apply the frontend overlay first
kubectl -n devops-portal create secret generic devops-portal-backend --from-env-file=backend.env
kubectl apply -k k8s/overlays/prod
```

`k8s/base/secret.example.yaml` lists the keys the Secret needs. The deployment runs as non-root with a read-only root filesystem and liveness/readiness probes, and includes an HPA and a PDB. Scheduled jobs use ShedLock, so any number of replicas is safe. The NetworkPolicy keeps egress open so connectivity tests can reach external endpoints; tighten it per environment if needed.
