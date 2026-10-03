#!/usr/bin/env bash
# Regression test for the gateway's real-client-IP handling behind the AWS ALB.
#
#   ./gateway/test-realip.sh        (needs: nginx with http_realip_module, python3, curl)
#
# Simulates the ALB by connecting from 127.0.0.2 (loopback is a whole /8 on Linux,
# so no root needed) and adding that address to the trusted list in a TEMP COPY
# of nginx.conf only. Proves three things:
#   1. the real client (last X-Forwarded-For entry the ALB appended) becomes X-Real-IP
#   2. a client-forged X-Forwarded-For prefix is ignored
#   3. two clients behind the same ALB node get separate rate-limit buckets
set -euo pipefail
cd "$(dirname "$0")"
T=$(mktemp -d); trap 'kill $(cat "$T/nginx.pid" 2>/dev/null) $ECHO_PID 2>/dev/null || true; rm -rf "$T"' EXIT

# Upstream names in nginx.conf must resolve at config load; map them all to a local echo server.
# (nginx resolves via /etc/hosts; if you cannot edit it, run this in a container.)
for h in user-svc cart-svc catalog-svc checkout-svc notification-svc frontend; do
  getent hosts "$h" >/dev/null || { echo "SKIP: add '127.0.0.1 $h' to /etc/hosts (or run in a throwaway container)"; exit 2; }
done

python3 - >/dev/null 2>&1 <<'PY' &
import http.server, json
class H(http.server.BaseHTTPRequestHandler):
    def do(self):
        b = json.dumps({"x_real_ip": self.headers.get("X-Real-IP")}).encode()
        self.send_response(200); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    do_GET = do_POST = do
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("127.0.0.1", 11010), H).serve_forever()
PY
ECHO_PID=$!

sed 's|real_ip_recursive on;|real_ip_recursive on;\n    set_real_ip_from 127.0.0.2;|' nginx.conf > "$T/gw.conf"
cat > "$T/nginx.conf" <<CONF
pid $T/nginx.pid; error_log $T/err.log; worker_processes 1;
events { worker_connections 256; }
http { access_log off; client_body_temp_path $T/b; proxy_temp_path $T/p; fastcgi_temp_path $T/f; uwsgi_temp_path $T/u; scgi_temp_path $T/s;
  include $T/gw.conf; }
CONF
nginx -c "$T/nginx.conf"; sleep 1

fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS  $1"; else echo "FAIL  $1 (expected '$3', got '$2')"; fail=1; fi; }
get_ip() { curl -s --interface 127.0.0.2 "$@" localhost:11000/api/users/me | python3 -c 'import sys,json;print(json.load(sys.stdin)["x_real_ip"])'; }

check "real client taken from ALB-appended XFF entry" \
  "$(get_ip -H 'X-Forwarded-For: 203.0.113.9')" "203.0.113.9"
check "forged XFF prefix is ignored" \
  "$(get_ip -H 'X-Forwarded-For: 6.6.6.6, 203.0.113.9')" "203.0.113.9"
check "XFF from an UNtrusted peer is ignored" \
  "$(curl -s -H 'X-Forwarded-For: 6.6.6.6' localhost:11000/api/users/me | python3 -c 'import sys,json;print(json.load(sys.stdin)["x_real_ip"])')" "127.0.0.1"

# Flood from client A in parallel (so the burst allowance is exhausted instantly and
# cannot refill while we wait), then immediately probe as client B.
seq 1 80 | xargs -P 40 -I{} curl -s -o /dev/null --interface 127.0.0.2 \
  -H 'X-Forwarded-For: 203.0.113.10' -X POST localhost:11000/api/users/login
check "a different client is NOT throttled by the abusive one" \
  "$(curl -s -o /dev/null -w '%{http_code}' --interface 127.0.0.2 -H 'X-Forwarded-For: 198.51.100.7' -X POST localhost:11000/api/users/login)" "200"
exit $fail
