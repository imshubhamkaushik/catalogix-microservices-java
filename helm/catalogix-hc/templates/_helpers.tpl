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
    image: "busybox:1.36.1"
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
    image: "busybox:1.36.1"
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
terraform/platform-infra/modules/db-roles) and the shared JWT secret.
RabbitMQ variables are added only when the service opts in with
`usesRabbitMQ: true`; this keeps the broker dependency explicit.

Secret key names here (jwt_secret, rabbitmq_user, db_user_<svc>, etc.) are
lowercase and must exactly match the AWS Secrets Manager JSON keys, because
templates/external-secrets.yaml's single ExternalSecret uses
`dataFrom.extract` — it copies every key from the AWS secret into the K8s
Secret VERBATIM (no per-key renaming). (The RDS master password is
deliberately NOT synced into the cluster: no service uses it.)

Service-specific env (inter-service URLs like CATALOG_SVC_URL, seed settings)
is layered on top via each service's own `extraEnv` in values.yaml — see
templates/backend-deployments.yaml.
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
{{- if .svc.usesRabbitMQ }}
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
# user-svc and notification-svc use the generic RABBITMQ_* aliases; checkout-svc
# uses Spring Boot's SPRING_RABBITMQ_* names. Keep both forms here so the three
# broker users share one secret and the non-broker services never receive it.
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
{{- end }}
# Connection pool per pod. Total connections = pods x pool, and every pod of
# every service shares one RDS instance whose max_connections is small on
# micro/small classes — size this together with hpa.maxReplicas.
# Per-service override: backendServices.<svc>.dbPoolSize.
- name: SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE
  value: {{ .svc.dbPoolSize | default .root.Values.database.maxPoolSize | default 5 | quote }}
{{- end -}}

{{/*
catalogix.lifecycle
Graceful shutdown for rolling updates. When a pod is deleted, Kubernetes removes
it from Service endpoints / the ALB target group AND sends SIGTERM at roughly
the same time — but the removal takes a few seconds to propagate, so requests
keep arriving at a pod that is already shutting down. A short preStop sleep
keeps the pod serving until the routing tables have caught up; the Spring
services then drain in-flight requests (server.shutdown=graceful) and nginx
gets SIGQUIT from its image's STOPSIGNAL.

Uses the native `sleep` handler (no shell/binary needed, so it also works in the
distroless Java images). Needs Kubernetes >= 1.30.
terminationGracePeriodSeconds must cover preStop + drain time — see the
`terminationGracePeriodSeconds` line in each Deployment.
Usage: {{ include "catalogix.lifecycle" $ | nindent 10 }}
*/}}
{{- define "catalogix.lifecycle" -}}
lifecycle:
  preStop:
    sleep:
      seconds: {{ .Values.global.preStopSleepSeconds | default 10 }}
{{- end -}}

{{/*
catalogix.publicBaseUrl
The externally visible base URL of the site (used for links in emails).
Single source of truth, in priority order:
  1. ingress.publicUrl      — explicit override (e.g. the raw ALB URL on a first deploy)
  2. ingress.host           — https:// if a TLS certificate is configured, else http://
  3. empty                  — unknown; the application falls back to its own default
*/}}
{{- define "catalogix.publicBaseUrl" -}}
{{- if .Values.ingress.publicUrl -}}
{{- .Values.ingress.publicUrl | trimSuffix "/" -}}
{{- else if .Values.ingress.host -}}
{{- if .Values.ingress.tlsCertArn -}}https{{- else -}}http{{- end -}}://{{ .Values.ingress.host }}
{{- end -}}
{{- end -}}

{{/*
catalogix.cookieSecure
"true" exactly when the ALB terminates TLS (a certificate ARN is configured).
A Secure cookie over plain HTTP is silently dropped by the browser, so this must
follow the listener configuration — deriving it here removes the manual
"remember to flip REFRESH_COOKIE_SECURE" step.
*/}}
{{- define "catalogix.cookieSecure" -}}
{{- if .Values.ingress.tlsCertArn -}}true{{- else -}}false{{- end -}}
{{- end -}}
