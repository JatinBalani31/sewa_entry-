# Deploying Sewa Entry

This sets up: a persistent PostgreSQL database, the app running as a Docker
container on Fly.io, and a GitHub Actions pipeline that tests every push and
deploys automatically on merge to `main`.

Everything here is a **one-time setup** you run yourself — it needs an
interactive login to Fly and GitHub, which can't be done on your behalf. Once
it's done, every future deploy is just `git push`.

---

## 0. Prerequisites

- A [Fly.io](https://fly.io) account (the free allowance covers this app —
  it's small and only busy two days a week).
- A GitHub repository for this project. If you haven't made one yet: create an
  empty repo on github.com, then come back here.

---

## 1. Install flyctl and log in

```bash
# macOS / Linux
curl -L https://fly.io/install.sh | sh

# Windows (PowerShell)
powershell -Command "iwr https://fly.io/install.ps1 -useb | iex"
```

```bash
fly auth login
```

This opens a browser to sign in or create a Fly account.

---

## 2. Create the app

From the project root (where `fly.toml` already lives):

```bash
fly apps create sewa-entry
```

Fly app names are global — if `sewa-entry` is taken, pick another name, then
open `fly.toml` and change the `app = "sewa-entry"` line at the top to match.

---

## 3. Create the database and attach it

```bash
fly postgres create --name sewa-entry-db --region bom --vm-size shared-cpu-1x --volume-size 1
```

Pick a region close to where the app itself will run (`bom` = Mumbai, matching
`fly.toml`'s `primary_region` — change both together if your sangat is
elsewhere).

```bash
fly postgres attach sewa-entry-db --app sewa-entry
```

This sets a `DATABASE_URL` secret on the app automatically — nothing to copy
by hand.

---

## 4. Set the remaining secrets

```bash
fly secrets set --app sewa-entry JWT_SECRET="$(openssl rand -base64 48)"
```

Then the app-specific settings — adjust to your sangat's actual schedule and
timezone:

```bash
fly secrets set --app sewa-entry \
  REPORT_TIMEZONE="Asia/Kolkata" \
  SEWA_DAYS="0,3" \
  TIME_ADJUST_TOLERANCE_MINUTES="120" \
  BOOTSTRAP_ADMIN_ID="ADMIN1" \
  BOOTSTRAP_ADMIN_NAME="Your Name" \
  BOOTSTRAP_ADMIN_PASSWORD="choose-a-real-password-here"
```

`BOOTSTRAP_ADMIN_PASSWORD` is what you'll actually sign in with the first
time — the app creates exactly one administrator the first time it boots
against an empty database (not the fake demo roster `npm run seed` makes;
that's for trying the app out locally, not for production). Once you've
signed in, change that password from the console and set it to something you
won't need to remember from a deploy script.

If you skip this step the app still boots — it falls back to
`ADMIN1` / `sewa@admin1` — but change it immediately if you do, since that
default is now written in this file.

---

## 5. First deploy, by hand

Do the very first deploy directly, so you can watch it and fix anything before
wiring up automatic deploys:

```bash
fly deploy --remote-only
```

Fly builds the Docker image on its own remote builder — you don't need Docker
installed locally. When it finishes:

```bash
fly open
```

Sign in with the administrator credentials from step 4.

---

## 6. Connect GitHub Actions

Create a deploy token scoped to this one app:

```bash
fly tokens create deploy --app sewa-entry
```

Copy the printed token (it starts with `FlyV1 ...`). In your GitHub repo:

**Settings → Secrets and variables → Actions → New repository secret**

- Name: `FLY_API_TOKEN`
- Value: the token you just copied

---

## 7. Push

```bash
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin <your-github-repo-url>
git push -u origin main
```

From here on: every push to `main` runs the test suite
(`.github/workflows/ci.yml`) and, if it passes, deploys to Fly
(`.github/workflows/deploy.yml`). A pull request runs the same tests as a
check but never deploys — only a merge to `main` does.

Watch a deploy from the command line if you want to:

```bash
fly logs --app sewa-entry
```

---

## Everyday operations

| Task | Command |
|---|---|
| Tail production logs | `fly logs --app sewa-entry` |
| Open a shell in the running container | `fly ssh console --app sewa-entry` |
| Open a local tunnel to the production database | `fly proxy 5433:5432 --app sewa-entry-db` |
| Scale to zero between sewa days (saves cost) | set `auto_stop_machines = true` and `min_machines_running = 0` in `fly.toml`, then `fly deploy` |
| Roll back to the previous release | `fly releases --app sewa-entry` to list, then `fly deploy --image <previous image>` |
| Change a secret | `fly secrets set --app sewa-entry KEY=value` (triggers a restart) |

---

## What NOT to do

- Don't put real secrets in `fly.toml` or `docker-compose.yml` — both are
  committed to the repo. `fly secrets set` and `.env` (git-ignored) are the
  only places credentials belong.
- Don't run `npm run seed` against the production database — it's full of
  fake demo Sewadars and is meant for trying the app out locally, where the
  in-memory database resets on every restart anyway.
- Don't remove the `POSTGRES_PORT` mapping from `docker-compose.yml` only in
  your head — if you ever run this stack on a VPS instead of Fly, actually
  delete that line so the database isn't reachable from outside the container
  network.
