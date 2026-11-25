#!/usr/bin/env bash
# Deurbel thuisserver installeren op een Raspberry Pi (Raspberry Pi OS 64-bit).
# Gebruik:  curl -fsSL https://github.com/TK800m/TestMe/releases/download/deurbel-latest/install.sh | bash
# Er worden geen vragen gesteld: de deurbel koppel je daarna in de app.
set -euo pipefail
BASE="https://github.com/TK800m/TestMe/releases/download/deurbel-latest"
DIR="$HOME/deurbel"
step() { printf '\n\033[1;33m▶ %s\033[0m\n' "$1"; }
ok()   { printf '\033[1;32m✔ %s\033[0m\n' "$1"; }

step "Stap 1 van 3 – Docker (programma om de server te draaien)"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sudo sh
fi
ok "Docker staat klaar"

step "Stap 2 van 3 – Deurbel-server starten (duurt een paar minuten)"
mkdir -p "$DIR/data"
cd "$DIR"
for f in Dockerfile docker-compose.yml deurbel-server.jar; do
  curl -fsSL -o "$f" "$BASE/$f"
done
mkdir -p go2rtc
if [ ! -f go2rtc/go2rtc.yaml ]; then
  cat > go2rtc/go2rtc.yaml <<'YAML'
api:
  listen: "127.0.0.1:1984"
  origin: "*"
rtsp:
  listen: ""
webrtc:
  listen: ":8555"
streams: {}
YAML
fi
sudo docker compose pull --quiet go2rtc
sudo docker compose up -d --build
ok "Server draait (start ook vanzelf na een stroomstoring)"

step "Stap 3 van 3 – Veilige toegang voor jullie telefoons (Tailscale)"
if ! command -v tailscale >/dev/null 2>&1; then
  curl -fsSL https://tailscale.com/install.sh | sh
fi
if ! tailscale status >/dev/null 2>&1; then
  echo
  echo "Log nu in bij Tailscale: scan de QR-code met je telefoon of open de link."
  echo "Gebruik hetzelfde account als in de Tailscale-app op je telefoon."
  sudo tailscale up --hostname=deurbel --qr
fi
echo
echo "Krijg je hieronder een link om HTTPS aan te zetten? Open die en klik op 'Enable'."
sudo tailscale serve --bg --https=443 http://127.0.0.1:8080
sudo tailscale serve --bg --https=8443 http://127.0.0.1:1984

NAME=$(tailscale status --json | python3 -c 'import sys,json;print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')
URL="https://$NAME"
command -v qrencode >/dev/null 2>&1 || sudo apt-get install -y -qq qrencode >/dev/null

printf '\n\033[1;32m══════════════  KLAAR!  ══════════════\033[0m\n\n'
qrencode -t ansiutf8 "$URL"
echo
echo "  Scan deze code met je telefoon (zet eerst de Tailscale-app aan),"
echo "  of open:  $URL"
echo
echo "  In de app vul je alleen nog het wachtwoord van je Tapo-account in."
echo
