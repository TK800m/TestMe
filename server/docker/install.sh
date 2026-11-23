#!/usr/bin/env bash
# Installatie van de Deurbel thuisserver op een Raspberry Pi (Raspberry Pi OS 64-bit).
# Gebruik:  curl -fsSL https://github.com/TK800m/TestMe/releases/download/deurbel-latest/install.sh | bash
set -euo pipefail
BASE="https://github.com/TK800m/TestMe/releases/download/deurbel-latest"
DIR="$HOME/deurbel"

echo "== Deurbel thuisserver installeren =="
if ! command -v docker >/dev/null 2>&1; then
  echo "-- Docker installeren"
  curl -fsSL https://get.docker.com | sudo sh
fi

mkdir -p "$DIR/data" && cd "$DIR"
for f in Dockerfile docker-compose.yml deurbel-server.jar; do
  curl -fsSL -o "$f" "$BASE/$f"
done

if [ ! -f .env ]; then
  read -rp "IP-adres van de deurbel (bijv. 192.168.1.50): " IP < /dev/tty
  read -rsp "Wachtwoord van je TP-Link/Tapo-account: " PW < /dev/tty; echo
  umask 077
  printf 'TAPO_IP=%s\nTAPO_PASSWORD=%s\n' "$IP" "$PW" > .env
  ENC=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=""))' "$PW")
  SHA=$(printf '%s' "$PW" | sha256sum | cut -d' ' -f1 | tr a-f A-F)
  cat > go2rtc.yaml <<YAML
api:
  listen: "127.0.0.1:1984"
  origin: "*"
rtsp:
  listen: ""
webrtc:
  listen: ":8555"
streams:
  deurbel:
    - tapo://${ENC}@${IP}
    - tapo://admin:${SHA}@${IP}
YAML
  umask 022
fi

echo "-- Server starten (eerste keer duurt dit enkele minuten)"
sudo docker compose up -d --build

if ! command -v tailscale >/dev/null 2>&1; then
  echo "-- Tailscale installeren (veilige toegang vanaf jullie telefoons, ook buitenshuis)"
  curl -fsSL https://tailscale.com/install.sh | sh
fi
sudo tailscale up || true
sudo tailscale serve --bg --https=443 http://127.0.0.1:8080
sudo tailscale serve --bg --https=8443 http://127.0.0.1:1984

NAME=$(tailscale status --json | python3 -c 'import sys,json;print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')
echo
echo "== Klaar! =="
echo "Open op beide telefoons (met de Tailscale-app aan):  https://$NAME"
echo "iPhone: Safari > Deel > Zet op beginscherm, open de app en tik in Instellingen op 'Meldingen aanzetten'."
echo "Android: Chrome > menu > Toevoegen aan startscherm, daarna hetzelfde."
