#!/usr/bin/env bash
set -euo pipefail
# Existing Nginx/HTTP-01 webroot only. Never modifies a firewall or an existing site.
HOST='' WEBROOT='' OWNER="${SUDO_USER:-ubuntu}" PORT=8443
PUBLIC_ROOT='/srv/linguareader-packs/public'
SERVICE_ROOT='/opt/linguareader-packs'
BOOTSTRAP=0 CHECK=0
usage() { echo 'sudo bash install-ubuntu.sh --host IPv4-or-DNS --existing-webroot /served/acme/root [--owner ubuntu] [--bootstrap-certbot] [--check]'; }
while (($#)); do
  case "$1" in
    --host) HOST="${2:?missing host}"; shift 2;;
    --existing-webroot) WEBROOT="${2:?missing webroot}"; shift 2;;
    --owner) OWNER="${2:?missing owner}"; shift 2;;
    --public-root) PUBLIC_ROOT="${2:?missing public root}"; shift 2;;
    --service-root) SERVICE_ROOT="${2:?missing service root}"; shift 2;;
    --port) PORT="${2:?missing port}"; shift 2;;
    --bootstrap-certbot) BOOTSTRAP=1; shift;;
    --check) CHECK=1; shift;;
    --help) usage; exit 0;;
    *) echo "Unknown option: $1" >&2;usage >&2;exit 2;;
  esac
done
[[ $EUID -eq 0 ]] || { echo 'Use sudo for the read-only preflight/apply'; exit 1; }
[[ -n "$HOST" && -n "$WEBROOT" ]] || { usage >&2;exit 2; }
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
python3 "$SCRIPT_DIR/install_config.py" --host "$HOST" --port "$PORT" --public-root "$PUBLIC_ROOT" --service-root "$SERVICE_ROOT" --check-owned >/dev/null
# Validate paths before using them in generated command lines; no credentials are accepted here.
python3 - "$WEBROOT" <<'PY'
import os,sys
p=sys.argv[1];assert p.startswith('/') and p==os.path.normpath(p), 'Use a normalized webroot'
PY
[[ "$WEBROOT" =~ ^/[A-Za-z0-9_./-]+$ && "$WEBROOT" != *'/../'* && -d "$WEBROOT" ]] || { echo 'An existing public HTTP-01 webroot is required';exit 1; }
[[ "$OWNER" =~ ^[a-z_][a-z0-9_-]*\$?$ ]] || { echo 'Invalid owner';exit 1; }
id "$OWNER" >/dev/null
for command in nginx python3 curl openssl ss systemctl systemd-analyze; do command -v "$command" >/dev/null || { echo "Missing prerequisite: $command (not installed automatically)";exit 1; }; done
nginx -t
SITE='/etc/nginx/sites-available/linguareader-packs'
LISTENERS="$(ss -ltnpH "sport = :$PORT")"
if [[ -n "$LISTENERS" ]]; then
  [[ -f "$SITE" ]] && grep -qF '# Managed by LinguaReader packs v1' "$SITE" && [[ "$LISTENERS" == *'"nginx"'* ]] || { echo "$PORT belongs to an existing service; unchanged";exit 1; }
fi
[[ -d /etc/nginx/sites-available && -d /etc/nginx/sites-enabled ]] || { echo 'Ubuntu Nginx sites layout required';exit 1; }
python3 - "$SERVICE_ROOT" "$PUBLIC_ROOT" <<'PY'
from pathlib import Path
import sys
for name in sys.argv[1:]:
 p=Path(name)
 for current in [p,*p.parents]:
  assert not current.is_symlink(), 'Managed roots cannot traverse symlinks'
PY
if [[ -e "$SERVICE_ROOT" && ! -f "$SERVICE_ROOT/.managed-by-linguareader-packs" ]]; then echo 'Service directory is not owned by this installer';exit 1;fi
if [[ -f "$SERVICE_ROOT/install.json" ]]; then
  python3 - "$SERVICE_ROOT/install.json" "$HOST" "$PORT" "$PUBLIC_ROOT" <<'PY'
import json,sys
v=json.load(open(sys.argv[1]));assert v['host']==sys.argv[2] and v['port']==int(sys.argv[3]) and v['publicRoot']==sys.argv[4], 'Existing deployment has a different target; inspect manually'
PY
fi
if ((CHECK)); then echo 'Read-only preflight passed; ACME, TLS and activation remain untested';exit 0;fi
# No apt/nginx package install or upgrade, no port 80 configuration and no service stop.
install -d -o root -g root -m 700 "$SERVICE_ROOT"
printf '%s\n' '# Managed by LinguaReader packs v1' > "$SERVICE_ROOT/.managed-by-linguareader-packs"
CERTBOT="$SERVICE_ROOT/certbot/bin/certbot"
if [[ ! -x "$CERTBOT" ]]; then
  ((BOOTSTRAP)) || { echo 'Use --bootstrap-certbot to create the isolated Certbot environment';exit 1; }
  python3 -m venv "$SERVICE_ROOT/certbot"
  "$SERVICE_ROOT/certbot/bin/python" -m pip install 'certbot>=5.4,<6'
fi
VERSION_OUTPUT="$("$CERTBOT" --version)"
printf '%s\n' "$VERSION_OUTPUT"
python3 - "$VERSION_OUTPUT" <<'PY'
import re,sys
m=re.search(r'(\d+)\.(\d+)',sys.argv[1]);assert m and tuple(map(int,m.groups()))>=(5,4), 'Certbot 5.4+ required'
PY
HELP="$($CERTBOT --help all)"
[[ "$HELP" == *'--ip-address'* && "$HELP" == *'--preferred-profile'* ]] || { echo 'Certbot 5.4+ with webroot IP support is required';exit 1; }
TOKEN="linguareader-packs-$(python3 -c 'import secrets;print(secrets.token_hex(16))')"
CHALLENGE="$WEBROOT/.well-known/acme-challenge/$TOKEN"
trap 'rm -f -- "$CHALLENGE"' EXIT
install -d -m 755 "$WEBROOT/.well-known/acme-challenge"
printf '%s' "$TOKEN" > "$CHALLENGE"
chmod 644 "$CHALLENGE"
ACTUAL="$(curl --fail --silent --show-error --connect-timeout 5 --max-time 20 --noproxy '*' "http://$HOST/.well-known/acme-challenge/$TOKEN")"
[[ "$ACTUAL" == "$TOKEN" ]] || { echo 'Public HTTP-01 webroot proof failed';exit 1; }
IDENTIFIER=(--ip-address "$HOST")
if ! python3 - "$HOST" <<'PY'
import ipaddress,sys
ipaddress.ip_address(sys.argv[1])
PY
then IDENTIFIER=(-d "$HOST");fi
"$CERTBOT" certonly --non-interactive --agree-tos --register-unsafely-without-email \
  --preferred-profile shortlived --webroot --webroot-path "$WEBROOT" \
  --cert-name linguareader-packs --config-dir "$SERVICE_ROOT/letsencrypt" \
  --work-dir "$SERVICE_ROOT/certbot-work" --logs-dir "$SERVICE_ROOT/certbot-logs" \
  --keep-until-expiring "${IDENTIFIER[@]}"
CERT="$SERVICE_ROOT/letsencrypt/live/linguareader-packs/fullchain.pem"
openssl x509 -in "$CERT" -noout -checkend 86400
if [[ "${IDENTIFIER[0]}" == '--ip-address' ]]; then openssl x509 -in "$CERT" -noout -checkip "$HOST";else openssl x509 -in "$CERT" -noout -checkhost "$HOST";fi
install -d -o "$OWNER" -g www-data -m 755 "$PUBLIC_ROOT" "$PUBLIC_ROOT/packs" "$PUBLIC_ROOT/packs/files"
python3 "$SCRIPT_DIR/install_config.py" --host "$HOST" --port "$PORT" --public-root "$PUBLIC_ROOT" --service-root "$SERVICE_ROOT"
echo 'Dedicated TLS/renewal configured; catalogue activation and external/phone verification are separate.'
echo "https://$HOST:$PORT/packs/index.json"
