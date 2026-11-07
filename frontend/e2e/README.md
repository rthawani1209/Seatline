# Browser tests

These drive the real app in Chromium (desktop and a Pixel 7) against the whole stack, so start it first:

```sh
docker compose up -d --build          # from the repo root
cd frontend && npm ci && npx playwright install chromium
npm run e2e
```

The tests make their own accounts and events. They read confirmation codes from the API's email
outbox and set roles directly in Postgres, so they need the database on `localhost:5432`
(override with `E2E_DATABASE_URL`). Point them at another URL with `E2E_BASE_URL`.

The chaos test crashes and revives ledger nodes, so the tests run one at a time.
