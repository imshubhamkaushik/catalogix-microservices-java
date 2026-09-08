{{/*
catalogix.securityContext
Identical hardening for every backend (Spring Boot / distroless) container:
non-root, read-only rootfs, no privilege escalation, all capabilities dropped.
The pod security context also uses fsGroup 1000 so the /tmp emptyDir is writable
by the non-root process. Frontend-svc (nginx) intentionally does NOT use this
container helper — it listens on 11000 as non-root; gateway listens on port 80
and has only NET_BIND_SERVICE, with writable emptyDir mounts for nginx runtime
paths under a read-only rootfs.
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
  failureThreshold: 30
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
- name: OTLP_ENDPOINT
  value: "{{ .root.Values.global.otlpEndpoint }}"   # Tempo's OTLP HTTP receiver, monitoring namespace
{{- end -}}
