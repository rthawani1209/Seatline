#!/bin/bash
# Paste this into "User data" when launching an EC2 instance (Ubuntu 24.04, see deploy/README.md).
# It installs a small "seatline" service that, at every boot, installs Docker and gets the code if
# they're missing and then runs deploy/setup.sh. If a step fails (say apt is busy with updates on
# the first boot) the service tries again a minute later, so nothing needs doing by hand.
# The first start takes 10 to 15 minutes; progress is in /var/log/seatline-setup.log.
#
# Fill in the two lines below first. ADMIN_EMAIL's account becomes the site's admin once you sign
# up with it and then set its password with "Forgot password?" (see deploy/README.md, step 3).
# The email (SMTP) password doesn't go here: AWS keeps user data readable from the instance and the
# console, so it goes in /home/ubuntu/Seatline/deploy/.env once the server is up (README step 5).

DOMAIN=auto                      # or your own domain, once its DNS points at this server
ADMIN_EMAIL=you@example.com

set -euo pipefail
umask 077

# the settings, readable by root only; used on the first run (%q keeps odd characters intact)
printf 'SEATLINE_DOMAIN=%q\nSEATLINE_ADMIN_EMAIL=%q\n' "$DOMAIN" "$ADMIN_EMAIL" > /etc/seatline.env

# wait for apt instead of failing when unattended-upgrades holds its lock
echo 'DPkg::Lock::Timeout "600";' > /etc/apt/apt.conf.d/90seatline

cat > /usr/local/sbin/seatline-start <<'SCRIPT'
#!/bin/bash
# Installs what's missing and starts Seatline. Safe to run again: setup.sh keeps .env and the
# certificates once they exist.
set -euo pipefail
exec >>/var/log/seatline-setup.log 2>&1
echo "== $(date -u) starting"
if ! command -v docker >/dev/null; then
  curl -fsSL https://get.docker.com | sh
  usermod -aG docker ubuntu
fi
# 2 GB of swap, so building the images never runs the machine out of memory
if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
repo=/home/ubuntu/Seatline
[ -d "$repo/.git" ] || { rm -rf "$repo"; sudo -u ubuntu git clone https://github.com/rthawani1209/Seatline.git "$repo"; }
set -a
. /etc/seatline.env
set +a
"$repo/deploy/setup.sh"
chown ubuntu "$repo/deploy/.env"
echo "Seatline setup finished"
SCRIPT
chmod 755 /usr/local/sbin/seatline-start
touch /var/log/seatline-setup.log
chmod 644 /var/log/seatline-setup.log

cat > /etc/systemd/system/seatline.service <<'UNIT'
[Unit]
Description=Start Seatline
After=docker.service network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/seatline-start
Restart=on-failure
RestartSec=60
TimeoutStartSec=40min

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable seatline.service
# start it without waiting, so the first boot carries on
systemctl start --no-block seatline.service
