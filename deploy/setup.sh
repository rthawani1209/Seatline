#!/usr/bin/env bash
# Installs or updates Seatline on one server. Run it on a Linux machine with Docker and a DNS name
# pointing at it (see deploy/README.md):
#
#   SEATLINE_DOMAIN=tickets.example.com deploy/setup.sh
#   SEATLINE_DOMAIN=auto deploy/setup.sh          no domain yet: uses <public ip>.sslip.io
#
# The first run writes deploy/.env with fresh random secrets and makes the TLS certificates in
# deploy/secrets/ (one folder per container, each holding only its own key). Later runs keep both
# and just rebuild and restart. Nothing secret is ever committed: both paths are in .gitignore.
set -euo pipefail
cd "$(dirname "$0")"

command -v docker >/dev/null || { echo "Install Docker first: https://docs.docker.com/engine/install/" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is needed to make the secrets" >&2; exit 1; }

# The server's public address, from the cloud's metadata service (AWS) or failing that from outside.
public_ip() {
  local t
  if t=$(curl -fsS -m 3 -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 60" 2>/dev/null); then
    curl -fsS -m 3 -H "X-aws-ec2-metadata-token: $t" http://169.254.169.254/latest/meta-data/public-ipv4 && return
  fi
  curl -fsS -m 5 https://checkip.amazonaws.com
}

# SEATLINE_DOMAIN=auto: no domain of your own yet, so use <ip>.sslip.io, a free name that points
# at whatever IP is in it. It's worked out again on every run, so it follows the IP if it changes.
auto_domain() {
  local ip
  ip=$(public_ip | tr -d '[:space:]')
  [[ $ip =~ ^[0-9.]+$ ]] || { echo "couldn't find this server's public IP" >&2; exit 1; }
  echo "$ip.sslip.io"
}

if [ ! -f .env ]; then
  : "${SEATLINE_DOMAIN:?set SEATLINE_DOMAIN to the site DNS name, e.g. tickets.example.com, or to auto}"
  auto=false
  if [ "$SEATLINE_DOMAIN" = auto ]; then
    auto=true
    SEATLINE_DOMAIN=$(auto_domain)
  fi
  umask 077
  cat > .env <<ENV
SEATLINE_DOMAIN=$SEATLINE_DOMAIN
SEATLINE_AUTO_DOMAIN=$auto
# this account becomes an admin once its email is confirmed
SEATLINE_ADMIN_EMAIL=${SEATLINE_ADMIN_EMAIL:-}
POSTGRES_PASSWORD=$(openssl rand -hex 24)
REDIS_PASSWORD=$(openssl rand -hex 24)
SEATLINE_JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
# Email (needed for sign-up confirmations): any SMTP service, e.g. Gmail with an app password,
# Amazon SES or Resend
SEATLINE_SMTP_HOST=${SEATLINE_SMTP_HOST:-}
SEATLINE_SMTP_PORT=${SEATLINE_SMTP_PORT:-587}
SEATLINE_SMTP_USER=${SEATLINE_SMTP_USER:-}
SEATLINE_SMTP_PASSWORD=${SEATLINE_SMTP_PASSWORD:-}
SEATLINE_MAIL_FROM=${SEATLINE_MAIL_FROM:-}
# The chaos panel (admins crash and revive ledger nodes live) is off. For a demo, uncomment both:
# SEATLINE_CHAOS=true
# SEATLINE_LEDGER_ADMIN=1=ledger-1:7000,2=ledger-2:7000,3=ledger-3:7000,4=ledger-4:7000,5=ledger-5:7000
ENV
  echo "wrote deploy/.env"
elif grep -q '^SEATLINE_AUTO_DOMAIN=true' .env; then
  domain=$(auto_domain)
  sed -i "s/^SEATLINE_DOMAIN=.*/SEATLINE_DOMAIN=$domain/" .env
fi

if [ ! -d secrets ]; then
  work=$(mktemp -d)
  trap 'rm -rf "$work"' EXIT
  bash ../scripts/certs.sh "$work" 5 825 >/dev/null
  for name in ledger-1 ledger-2 ledger-3 ledger-4 ledger-5 api; do
    mkdir -p "secrets/$name"
    cp "$work/$name.crt" "secrets/$name/node.crt"
    cp "$work/$name.key" "secrets/$name/node.key"
    cp "$work/ca.crt" "secrets/$name/ca.crt"
  done
  # the CA's key goes with $work: no new certificates can be made on this server
  chmod 700 secrets/*
  chmod 400 secrets/*/node.key
  # the containers run as uid 10001
  if [ "$(id -u)" = 0 ]; then chown -R 10001 secrets; else sudo chown -R 10001 secrets; fi
  echo "made certificates in deploy/secrets"
fi

docker compose -f docker-compose.prod.yml --env-file .env up -d --build
echo "Seatline is starting at https://$(grep ^SEATLINE_DOMAIN= .env | cut -d= -f2)"
