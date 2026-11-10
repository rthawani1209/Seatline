# Deploying Seatline

Seatline runs on one Linux server with Docker: five ledger nodes, the API, the web app, Postgres,
Redis, and Caddy in front for HTTPS. `setup.sh` does the work. These are the steps around it, using
AWS EC2 as the example; any Linux server with Docker works the same way.

What you need:

- a server with **8 GB of memory** and 2 CPUs. On AWS that's an `m7i-flex.large`, which the Free
  plan allows. The five ledger nodes and the API are capped at 640 MB and 1 GB (`mem_limit` in
  `docker-compose.prod.yml`), about 4.2 GB in all. The rest is for Postgres, Redis, the web app, the
  OS and building the images. A 4 GB machine is too small.
- a name for the site. Without a domain, `SEATLINE_DOMAIN=auto` uses `<the server's IP>.sslip.io`,
  a free name that points at the IP inside it. Some home and school networks block sslip.io; a
  domain of your own (the GitHub Student Pack includes one) avoids that.
- an SMTP account for sign-up and password-reset emails. A Gmail app password works and is free,
  and so do Amazon SES and Resend.

Payments are still the test stand-in: only the test cards on the checkout page go through and no
real money moves. Real card payments need a Stripe account wired into `backend/.../payment/`.

## 1. Start a server (AWS console, about 5 minutes of clicking)

1. Sign in to the AWS console, pick your region at the top right (e.g. *US West (Oregon)*), open
   **EC2** and click **Launch instance**.
2. **Name**: `seatline`.
3. **Application and OS Images**: *Ubuntu*, then **Ubuntu Server 24.04 LTS**, 64-bit (x86).
4. **Instance type**: `m7i-flex.large`.
5. **Key pair**: *Create new key pair*, name it `seatline`, and keep the `.pem` file it downloads.
6. **Network settings**: keep *Allow SSH traffic from Anywhere* (the in-browser terminal, EC2
   Instance Connect, comes in that way; signing in still needs a key, never a password), and tick
   **Allow HTTPS traffic from the internet** and **Allow HTTP traffic from the internet**. Caddy
   needs port 80 to get the certificate.
7. **Configure storage**: 30 GiB, gp3.
8. **Advanced details → User data**: paste all of [`aws-user-data.sh`](aws-user-data.sh), then fill
   in your email as `ADMIN_EMAIL` at the top. The email password is added after launch (step 5),
   because anything in user data can be read back from the instance.
9. **Launch instance**.

**Keep the address (optional, recommended).** Under **Elastic IPs → Allocate Elastic IP address →
Allocate**, then **Actions → Associate**, pick the `seatline` instance, then on the instance
**Instance state → Reboot**. Without this the IP, and so the sslip.io address, changes whenever the
instance is stopped and started. The site follows the new IP by itself, but old links stop working.

## 2. Wait, then open the site

The first boot installs Docker, gets the code and builds everything: 10 to 15 minutes. The site is
at `https://<the instance's Public IPv4 address>.sslip.io` (the IP is on the instance's page).

To watch progress, connect to the instance (on its page, **Connect → EC2 Instance Connect →
Connect** opens a terminal in the browser) and run `tail -f /var/log/seatline-setup.log`. It ends
with "Seatline setup finished".

## 3. The first admin

This needs email, so do step 5 first (or use the terminal way below). Sign up on the site with the `ADMIN_EMAIL` address and confirm the email. Then sign out, click
**Forgot password?** on the sign-in page, and set your password from the emailed link. That makes
the account the admin. (Only a reset link counts: it proves the inbox is yours even if someone else
signed up with your address first, and it signs them out.) It only works while the site has no
admin yet. From then on the **Admin** page makes organizers and door staff, and organizers add door
staff to each event's door team on the event page.

Without email set up yet, confirm the account by hand in the instance's terminal:

```
cd ~/Seatline/deploy
sudo docker compose -f docker-compose.prod.yml --env-file .env exec postgres \
  psql -U seatline -c "update users set email_verified = true, role = 'ADMIN' where email = 'you@example.com'"
```

## 4. Using your own domain later

Add an **A record** at your DNS provider, e.g. `tickets.example.com → <the Elastic IP>`. Wait until
`dig +short tickets.example.com` prints the IP, then on the server set
`SEATLINE_DOMAIN=tickets.example.com` and `SEATLINE_AUTO_DOMAIN=false` in `deploy/.env` and run
`sudo deploy/setup.sh`.

**Without the user-data script** (another cloud, or by hand): install Docker
(`curl -fsSL https://get.docker.com | sudo sh`), clone the repo, and run
`SEATLINE_DOMAIN=tickets.example.com deploy/setup.sh` (or `SEATLINE_DOMAIN=auto`). The first run:

1. writes `deploy/.env` with random passwords for Postgres and Redis and a random token-signing
   key (file mode 600);
2. makes the ledger's TLS certificates in `deploy/secrets/`, one folder per container holding only
   its own key. The CA's private key is thrown away, so no new certificates can be made on the
   server;
3. builds the images and starts everything.

Both `deploy/.env` and `deploy/secrets/` are git-ignored. Keep a copy of `.env` somewhere safe:
without it the database password and signed-in sessions are lost.

## 5. Email

Fill in the `SEATLINE_SMTP_*` lines and `SEATLINE_MAIL_FROM` in `deploy/.env` (in the instance's
terminal: `nano ~/Seatline/deploy/.env`) and run `sudo ~/Seatline/deploy/setup.sh`.

- **Gmail** (free, fine for a demo): turn on 2-Step Verification, make an app password at
  https://myaccount.google.com/apppasswords, then host `smtp.gmail.com`, port 587, your Gmail
  address as user and as the from address, and the app password without its spaces.
- **Amazon SES**: verify the domain (or a sender address) under **SES → Identities**, and create
  SMTP credentials under **SES → SMTP settings**. The host is `email-smtp.<region>.amazonaws.com`,
  port 587. A new SES account is in the *sandbox* and only sends to verified addresses, so request
  production access before real fans sign up.

Until SMTP is set, emails wait in the `outbox_emails` table and nothing is sent; they go out once
it's configured.

## Updating

```
cd ~/Seatline && git pull && sudo deploy/setup.sh
```

`setup.sh` keeps the existing `.env` and certificates and only rebuilds and restarts. Database
changes run by themselves when the API starts (Flyway).

## Backups

The ledger is copied across five nodes, but they all live on this one server, so back up the disk
or at least the database. A nightly dump to S3, for example (needs the AWS CLI and an instance role
that can write to the bucket):

```
0 3 * * * cd ~/Seatline/deploy && docker compose -f docker-compose.prod.yml --env-file .env exec -T postgres pg_dump -U seatline seatline | gzip | aws s3 cp - s3://your-bucket/seatline-$(date +\%F).sql.gz
```

EBS snapshots (**EC2 → Lifecycle Manager**) cover the ledger volumes as well.

## The chaos demo

The admin panel that crashes and revives ledger nodes live is off in production. To turn it on for
a demo, uncomment `SEATLINE_CHAOS` and `SEATLINE_LEDGER_ADMIN` in `deploy/.env` and run `setup.sh`.
Turn it off again afterwards.

## Checking on it

```
cd ~/Seatline/deploy
docker compose -f docker-compose.prod.yml --env-file .env ps
docker compose -f docker-compose.prod.yml --env-file .env logs -f api
```
