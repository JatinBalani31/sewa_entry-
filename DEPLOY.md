# Deploying Sewa Entry

This sets up: a free, always-reachable PostgreSQL database (Neon), the app
running as a Docker container on Render's free tier, and a GitHub Actions
pipeline that tests every push and deploys automatically on merge to `main`.

**No credit card required anywhere in this path.** (That's specifically why
it's Render + Neon rather than Fly.io — Fly now requires a card on file even
for free-tier usage.)

Steps 1–5 below are one-time setup you do yourself, since they need an
interactive sign-in to Neon, Render and GitHub. Once done, every future
deploy is just `git push`.

**Trade-off to know going in:** Render's free web service spins down after
15 minutes with no traffic. The next request after that wakes it back up,
which takes roughly 30–50 seconds. For a tool used twice a week for a few
hours at a time, that's a non-issue — just don't expect it to feel instant on
the very first tap of the day.

---

## 0. Prerequisites

- A [Neon](https://neon.tech) account (free, no card).
- A [Render](https://render.com) account (free, no card — sign up with GitHub
  to make step 2 one click).
- The GitHub repo already has the code pushed
  ([JatinBalani31/sewa_entry-](https://github.com/JatinBalani31/sewa_entry-)).

---

## 1. Create the database on Neon

1. Sign up / sign in at [neon.tech](https://neon.tech).
2. **Create a project.** Pick a region close to your sangat — Singapore
   (`ap-southeast-1`) is the closest free-tier option to India.
3. Neon shows a connection string immediately. Copy it — it looks like:

   ```
   postgresql://<user>:<password>@ep-something.ap-southeast-1.aws.neon.tech/neondb?sslmode=require
   ```

   Keep the `?sslmode=require` on the end. The app already knows how to use
   it (`server/src/db/index.js` enables TLS automatically for any connection
   string containing it).

That's the entire database setup. Neon's free tier auto-suspends the compute
after inactivity and resumes it in well under a second on the next query —
you won't notice it.

---

## 2. Create the web service on Render

1. Sign in at [render.com](https://render.com).
2. **New +** → **Blueprint**.
3. Connect your GitHub account if you haven't, and select the
   `sewa_entry-` repository. Render reads `render.yaml` from the repo root
   and shows one service: `sewa-entry`.
4. Click **Apply**. Render creates the service. Its first deploy attempt will
   fail — that's expected, the required secrets aren't set yet. Continue to
   step 3.

---

## 3. Set the secrets

In the Render dashboard, open the **sewa-entry** service → **Environment**,
and add:

| Key | Value |
|---|---|
| `DATABASE_URL` | The connection string from step 1 |
| `JWT_SECRET` | A long random string — generate one with `openssl rand -base64 48`, or any password generator |
| `BOOTSTRAP_ADMIN_ID` | e.g. `ADMIN1` |
| `BOOTSTRAP_ADMIN_NAME` | Your name |
| `BOOTSTRAP_ADMIN_PASSWORD` | A real password you'll actually use to sign in the first time |

`BOOTSTRAP_ADMIN_PASSWORD` matters: the app creates exactly **one**
administrator the first time it boots against an empty database — not the
fake demo roster `npm run seed` makes locally (that's for trying the app out,
not production). Sign in with it once, then change it from the console and
forget the value in this table.

---

## 4. First deploy, by hand

Back on the service page: **Manual Deploy** → **Deploy latest commit**.

Watch the **Logs** tab. You're looking for:

```
[boot] No Sewadars found — created the first administrator.
[boot]   Sign in as: ADMIN1 / <your password>
```

Then open the service's URL (shown at the top of the Render dashboard,
`https://sewa-entry-xxxx.onrender.com`) and sign in.

---

## 5. Connect GitHub Actions

1. On the service page: **Settings** → **Deploy Hook**. Copy the URL shown
   there (it's a one-off webhook, not an API key — safe to treat like a
   normal secret, but it can't read anything, only trigger a deploy).
2. In your GitHub repo: **Settings → Secrets and variables → Actions → New
   repository secret**
   - Name: `RENDER_DEPLOY_HOOK_URL`
   - Value: the URL you just copied

From here on: every push to `main` runs the test suite
(`.github/workflows/ci.yml`) and, only if it passes, calls that hook
(`.github/workflows/deploy.yml`), which tells Render to deploy. A pull
request runs the same tests as a check but never deploys — only a merge to
`main` does. This is also why `autoDeploy: false` is set in `render.yaml` —
without it, Render would deploy every push itself, immediately, skipping the
test gate entirely.

```bash
git add .
git commit -m "Your change"
git push
```

Watch a deploy from Render's **Logs** tab, or from GitHub's **Actions** tab
on the repo.

---

## Everyday operations

| Task | Where |
|---|---|
| Tail production logs | Render dashboard → sewa-entry → **Logs** |
| Open a shell in the running container | Render dashboard → sewa-entry → **Shell** |
| Browse the database directly | Neon dashboard → your project → **SQL Editor**, or any Postgres client with the connection string from step 1 |
| Change a secret | Render dashboard → **Environment** → edit the value (triggers a restart) |
| Roll back to a previous deploy | Render dashboard → sewa-entry → **Events** → find the earlier deploy → **Rollback to this deploy** |
| Force a deploy without a code change | Render dashboard → **Manual Deploy** |

---

## What NOT to do

- Don't put real secrets in `render.yaml` or `docker-compose.yml` — both are
  committed to the repo. Render's **Environment** tab (`sync: false` in
  `render.yaml`) and `.env` (git-ignored) are the only places credentials
  belong.
- Don't run `npm run seed` against the production database — it's full of
  fake demo Sewadars and is meant for trying the app out locally, where the
  in-memory database resets on every restart anyway.
- Don't re-enable `autoDeploy: true` in `render.yaml` unless you're also
  fine with untested code reaching production on every push — that setting
  is what makes the GitHub Actions test gate meaningful.
- Don't remove the `POSTGRES_PORT` mapping from `docker-compose.yml` only in
  your head — that file is for local development / a self-hosted VPS, not
  this Render+Neon path; if you ever do run it on a VPS, actually delete that
  line so the database isn't reachable from outside the container network.

---

## If Render or Neon ever require a card too

Platforms change their policies (this document exists because Fly.io just
did). If that happens here, the app itself doesn't need to change — only
`render.yaml` / `fly.toml`-equivalent config and this document would. The
app already reads `DATABASE_URL` and `PORT` from the environment and has no
platform-specific code in it.
