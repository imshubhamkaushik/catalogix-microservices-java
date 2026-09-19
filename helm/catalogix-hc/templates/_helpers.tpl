{{/*
catalogix.securityContext
Identical hardening for every backend (Spring Boot / distroless) container:
non-root, read-only rootfs, no privilege escalation, all capabilities dropped.
The pod security context also uses fsGroup 1000 so the /tmp emptyDir is writable
by the non-root process. Frontend (nginx) intentionally does NOT use this
container helper — it listens on 11001 as non-root; gateway listens on 11000,
also as non-root with no extra capabilities needed (both are unprivileged
ports), with writable emptyDir mounts for nginx runtime paths under a
read-only rootfs.
*/}}
{{- define "catalogix.securityContext" -}}
securityContext:
  runAsNonRoot: true
  runAsUser: 1000
  readOnlyRootFilesystem: true
  allowPrivilegeEscalation: false
  capabilities:
    drop:
      - ALL
{{- end -}}

{{/*
catalogix.probes
Startup/readiness/liveness probes against Spring Boot Actuator.
Usage: {{ include "catalogix.probes" (dict "port" $svc.port) | nindent 10 }}
*/}}
{{- define "catalogix.probes" -}}
startupProbe:
  httpGet:
    path: /actuator/health
    port: {{ .port }}
  periodSeconds: 5
  failureThreshold: 60
  timeoutSeconds: 5
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: {{ .port }}
  initialDelaySeconds: 15
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: {{ .port }}
  initialDelaySeconds: 30
  periodSeconds: 15
  timeoutSeconds: 5
  failureThreshold: 3
{{- end -}}

{{/*
catalogix.waitForDeps
initContainers that block the main container from starting until this
service's hard dependencies (Postgres always; RabbitMQ only for the 3
services that actually publish/consume events — flagged per-service via
usesRabbitMQ in values.yaml) are reachable over TCP.

Kubernetes has no native depends_on / condition:service_healthy like
docker-compose. Without this, a fresh `helm install` races the backend
Deployments against the Postgres/RabbitMQ StatefulSets: the app pods start
almost immediately, HikariCP's 3s connection-timeout fails fast, Spring
Boot's ApplicationContext refresh fails, and the pod crash-loops with
Kubernetes' own restart backoff (which grows exponentially, up to 5
minutes between attempts) as the only recovery mechanism — noisy and slow,
even though it's all "expected" and self-resolving once the dependency
comes up. An initContainer turns that into a clean "Init:0/1" wait with no
restart-count/backoff penalty — the main container only starts once the
dependency is actually reachable.

IMPORTANT — scope: this only proves the TCP port is reachable, not that
the target is actually ready for what this service specifically needs.
Postgres being reachable doesn't mean THIS service's database exists yet
— see postgres-local.yaml's header comment on database-per-service
creation via docker-entrypoint-initdb.d only ever running once, on a
truly empty PGDATA. This shortens the "waiting for infra to come up"
window on a normal fresh install; it does not replace the app's own
startupProbe, and it will NOT save you if the Postgres init script never
completed (that's a separate failure mode — the fix there is making sure
postgres-local's own startupProbe gives it enough time to finish, plus
manually creating any database that's still missing).
Usage: {{ include "catalogix.waitForDeps" (dict "svc" $svc "root" $) | nindent 6 }}
*/}}
{{- define "catalogix.waitForDeps" -}}
initContainers:
  - name: wait-for-postgres
    image: "busybox:1.36"
    command:
      - sh
      - -c
      - |
        until nc -z -w2 {{ .root.Values.database.host }} {{ .root.Values.database.port }}; do
          echo "waiting for postgres at {{ .root.Values.database.host }}:{{ .root.Values.database.port }}..."
          sleep 2
        done
    # busybox's default image user is root (UID 0). The Pod's own
    # securityContext (runAsNonRoot: true, set once in
    # backend-deployments.yaml) applies to every container in the pod,
    # initContainers included, with no per-container override — so without
    # this block, kubelet refuses to even create the container: "container
    # has runAsNonRoot and image will run as root". That surfaces as
    # Init:CreateContainerConfigError, not a crash or a restart, since it
    # never gets far enough to run anything. UID 1000 needs no special
    # privilege to open an outbound TCP connection, so this matches every
    # other container's security posture in this chart with no exceptions.
    securityContext:
      runAsNonRoot: true
      runAsUser: 1000
      readOnlyRootFilesystem: true
      allowPrivilegeEscalation: false
      capabilities:
        drop:
          - ALL
    resources:
      requests: { cpu: "10m", memory: "16Mi" }
      limits: { cpu: "50m", memory: "32Mi" }
  {{- if .svc.usesRabbitMQ }}
  - name: wait-for-rabbitmq
    image: "busybox:1.36"
    command:
      - sh
      - -c
      - |
        until nc -z -w2 {{ .root.Values.rabbitmq.host }} {{ .root.Values.rabbitmq.port }}; do
          echo "waiting for rabbitmq at {{ .root.Values.rabbitmq.host }}:{{ .root.Values.rabbitmq.port }}..."
          sleep 2
        done
    securityContext:
      runAsNonRoot: true
      runAsUser: 1000
      readOnlyRootFilesystem: true
      allowPrivilegeEscalation: false
      capabilities:
        drop:
          - ALL
    resources:
      requests: { cpu: "10m", memory: "16Mi" }
      limits: { cpu: "50m", memory: "32Mi" }
  {{- end }}
{{- end -}}

{{/*
catalogix.commonEnv
Env vars every backend service needs regardless of what it does: its own
DB connection using ITS OWN role (not a shared master credential — see
terraform/platform-infra/modules/db-roles), the shared JWT secret, RabbitMQ
connection, and the OTLP tracing endpoint.

Secret key names here (jwt_secret, rabbitmq_user, db_user_<svc>, etc.) are
lowercase and must exactly match the AWS Secrets Manager JSON keys, because
templates/external-secrets.yaml's app-secrets ExternalSecret uses
`dataFrom.extract` — it copies every key from the AWS secret into the K8s
Secret VERBATIM (no per-key renaming), unlike the db-credentials
ExternalSecret above it, which uses an explicit `data:` list and does
rename its key (db_pass -> DB_PASSWORD). Two different ExternalSecrets in
the same file, two different naming conventions — deliberate, but easy to
get wrong when adding a new env var here; check which ExternalSecret
resource owns the key before assuming its casing.

Service-specific env (ALLOWED_ORIGINS, ADMIN_EMAILS, inter-service URLs
like CATALOG_SVC_URL) is layered on top via each service's own `extraEnv`
in values.yaml — see templates/deployments.yaml.
Usage: {{ include "catalogix.commonEnv" (dict "name" $name "svc" $svc "root" $) | nindent 12 }}
*/}}
{{- define "catalogix.commonEnv" -}}
{{- $dbKeySuffix := .name | replace "-" "_" }}
- name: SPRING_DATASOURCE_URL
  value: "jdbc:postgresql://{{ .root.Values.database.host }}:{{ .root.Values.database.port }}/{{ .svc.dbName }}"
- name: SPRING_DATASOURCE_USERNAME
  valueFrom:
    secretKeyRef:
      name: catalogix-secrets
      key: db_user_{{ $dbKeySuffix }}
- name: SPRING_DATASOURCE_PASSWORD
  valueFrom:
    secretKeyRef:
      name: catalogix-secrets
      key: db_password_{{ $dbKeySuffix }}
- name: JWT_SECRET
  valueFrom:
    secretKeyRef:
      name: catalogix-secrets
      key: jwt_secret
- name: RABBITMQ_HOST
  value: "{{ .root.Values.rabbitmq.host }}"
- name: RABBITMQ_PORT
  value: "{{ .root.Values.rabbitmq.port }}"
- name: RABBITMQ_USER
  valueFrom:
    secretKeyRef:
      name: catalogix-secrets
      key: rabbitmq_user
- name: RABBITMQ_PASSWORD
  valueFrom:
    secretKeyRef:
      name: catalogix-secrets
      key: rabbitmq_password
# The 3 services that actually talk to RabbitMQ (user-svc, checkout-svc,
# notification-svc) don't agree on one env var naming scheme in their own
# application.properties:
#   - user-svc reads RABBITMQ_USERNAME (not RABBITMQ_USER above — only
#     HOST/PORT/PASSWORD happened to match)
#   - checkout-svc and notification-svc read SPRING_RABBITMQ_HOST/PORT/
#     USERNAME/PASSWORD entirely (Spring Boot's own auto-config property
#     names), none of which match RABBITMQ_* above at all
# Every one of those properties falls back to a default (localhost/guest)
# when unset, so the app doesn't crash — it just silently authenticates
# as "guest" against the wrong host, which then fails RabbitHealthIndicator
# and fails /actuator/health, which the startupProbe polls, which
# eventually kills and restarts the pod: a CrashLoopBackOff with a much
# less obvious cause than a missing placeholder. Rather than patch three
# different property names in application code, alias them all here so
# every naming convention resolves to the same real values.
- name: RABBITMQ_USERNAME
  valueFrom:
    secretKeyRef:
      name: catalogix-secrets
      key: rabbitmq_user
- name: SPRING_RABBITMQ_HOST
  value: "{{ .root.Values.rabbitmq.host }}"
- name: SPRING_RABBITMQ_PORT
  value: "{{ .root.Values.rabbitmq.port }}"
- name: SPRING_RABBITMQ_USERNAME
  valueFrom:
    secretKeyRef:
      name: catalogix-secrets
      key: rabbitmq_user
- name: SPRING_RABBITMQ_PASSWORD
  valueFrom:
    secretKeyRef:
      name: catalogix-secrets
      key: rabbitmq_password
- name: OTLP_ENDPOINT
  value: "{{ .root.Values.global.otlpEndpoint }}"   # Tempo's OTLP HTTP receiver, monitoring namespace
{{- end -}}
