# Deploying Seatline

Seatline runs on one Linux server with Docker: five ledger nodes, the API, the web app, Postgres,
Redis, and Caddy in front for HTTPS. `setup.sh` does the work; these are the steps around it, using
AWS EC2 as the example. Any Linux server with Docker works the same way.

What you need:

- a server with **8 GB of memory** and 2 CPUs (on AWS, a `t3.large`). The five ledger nodes and
  the API are capped at 640 MB and 1 GB (`mem_limit` in `docker-compose.prod.yml`), about 4.2 GB in
  all; the rest is for Postgres, Redis, the web app, the OS and building the images.
- a domain name you can add a DNS record to.
- an SMTP account for sign-up and password-reset emails (Amazon SES works; so does Resend or any
  other).

Payments are still the test stand-in: only the test cards on the checkout page go through and no
real money moves. Real card payments need a Stripe account wired into `backend/.../payment/`.

## 1. Start a server

In the EC2 console, **Launch instance**:

- **AMI**: Ubuntu Server 24.04 LTS (64-bit x86).
- **Instance type**: `t3.large`.
- **Key pair**: create one and keep the `.pem` file; it's how you SSH in.
- **Network settings → security group**: allow SSH (22) from *My IP* only, and HTTP (80) and
  HTTPS (443) from anywhere. Caddy needs port 80 to get the certificate.
- **Storage**: 30 GB gp3.

Then **Elastic IPs → Allocate**, and associate it with the instance, so the address survives a
restart.

## 2. Point the domain at it

At your DNS provider add an **A record**, e.g. `tickets.example.com → <the Elastic IP>`. Wait until
`dig +short tickets.example.com` prints the IP; Caddy can't get a certificate before then.

## 3. Install Docker and get the code

```
ssh -i seatline.pem ubuntu@tickets.example.com
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu && exit     # log in again so the group applies
ssh -i seatline.pem ubuntu@tickets.example.com
git clone https://github.com/rthawani1209/Seatline.git && cd Seatline
```

## 4. Run setup

```
SEATLINE_DOMAIN=tickets.example.com deploy/setup.sh
```

The first run:

1. writes `deploy/.env` with random passwords for Postgres and Redis and a random token-signing
   key (file mode 600);
2. makes the ledger's TLS certificates in `deploy/secrets/`, one folder per container holding only
   its own key. The CA's private key is thrown away, so no new certificates can be made on the
   server;
3. builds the images and starts everything.

Both `deploy/.env` and `deploy/secrets/` are git-ignored. Keep a copy of `.env` somewhere safe:
without it the database password and signed-in sessions are lost.

The first build takes several minutes. Then open `https://tickets.example.com`.

## 5. Email

Fill in the `SEATLINE_SMTP_*` lines and `SEATLINE_MAIL_FROM` in `deploy/.env` and run
`deploy/setup.sh` again. With Amazon SES:

- verify the domain (or a sender address) under **SES → Identities**;
- create SMTP credentials under **SES → SMTP settings**; the host is
  `email-smtp.<region>.amazonaws.com`, port 587;
- a new SES account is in the *sandbox* and only sends to verified addresses. Request production
  access before real fans sign up.

Until SMTP is set, emails wait in the `outbox_emails` table and nothing is sent; they go out once
it's configured.

## 6. The first admin

Every new account is a fan. Make your own account an admin once, straight in the database:

```
cd deploy
docker compose -f docker-compose.prod.yml --env-file .env exec postgres \
  psql -U seatline -c "update users set role = 'ADMIN' where email = 'you@example.com'"
```

Sign in again. From then on the **Admin** page makes organizers and door staff, and organizers add
door staff to each event's door team on the event page.

## Updating

```
cd ~/Seatline && git pull && deploy/setup.sh
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
