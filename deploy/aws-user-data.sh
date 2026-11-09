#!/bin/bash
# Paste this into "User data" when launching an EC2 instance (Ubuntu 24.04, see deploy/README.md).
# On first boot it installs Docker, gets the code and runs deploy/setup.sh; it also runs setup.sh
# again on every boot, so the site comes back after a restart and an sslip.io name follows the IP.
# Takes 10 to 15 minutes; progress is in /var/log/seatline-setup.log.
#
# Fill in the lines below first. ADMIN_EMAIL's account becomes the site's admin once you sign up
# with it and confirm the email. The SMTP lines are for sign-up emails: for Gmail, turn on 2-Step
# Verification, make an app password at https://myaccount.google.com/apppasswords and paste it
# without the spaces. They can be filled in later in /home/ubuntu/Seatline/deploy/.env instead.

DOMAIN=auto                      # or your own domain, once its DNS points at this server
ADMIN_EMAIL=you@example.com
SMTP_HOST=smtp.gmail.com
SMTP_USER=                       # e.g. you@gmail.com
SMTP_PASSWORD=                   # the 16-letter app password
MAIL_FROM=                       # usually the same as SMTP_USER

MAIL_FROM="${MAIL_FROM:-$SMTP_USER}"
set -euxo pipefail
exec >>/var/log/seatline-setup.log 2>&1

curl -fsSL https://get.docker.com | sh
usermod -aG docker ubuntu

# 2 GB of swap, so building the images never runs the machine out of memory
if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

repo=/home/ubuntu/Seatline
[ -d "$repo" ] || sudo -u ubuntu git clone https://github.com/rthawani1209/Seatline.git "$repo"

SEATLINE_DOMAIN="$DOMAIN" SEATLINE_ADMIN_EMAIL="$ADMIN_EMAIL" \
SEATLINE_SMTP_HOST="${SMTP_USER:+$SMTP_HOST}" SEATLINE_SMTP_USER="$SMTP_USER" \
SEATLINE_SMTP_PASSWORD="$SMTP_PASSWORD" SEATLINE_MAIL_FROM="$MAIL_FROM" \
  "$repo/deploy/setup.sh"
chown ubuntu "$repo/deploy/.env"

# run setup.sh at every boot too (it keeps .env and the certificates, and only restarts)
cat > /etc/systemd/system/seatline.service <<UNIT
[Unit]
Description=Start Seatline
After=docker.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=$repo/deploy/setup.sh

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable seatline.service
echo "Seatline setup finished"
