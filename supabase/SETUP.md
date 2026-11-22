# Setting up the shared database (Supabase, free)

About 10 minutes, once per firm. After this, everybody signs in on their own phone and
sees the same attendance, leave and approvals.

## 1. Create the project
1. Go to **supabase.com** → **Start your project** → sign in with GitHub.
2. **New project** → name it `finowise-hr`, set a database password (keep it safe),
   choose the region closest to you (e.g. *Mumbai* for India) → **Create new project**.
   Wait about 2 minutes while it starts.

## 2. Create the tables and rules
1. Left menu → **SQL Editor** → **New query**.
2. Open `supabase/schema.sql` from this project, copy **all** of it, paste it in, click **Run**.
3. It should say *Success. No rows returned*.

## 3. Sign-in settings
Left menu → **Authentication** → **Sign In / Providers** → **Email**:
- Keep **Email** enabled.
- **Confirm email**: keep it **ON** for real use (nobody can claim a colleague's account).
  For a quick test you may switch it **OFF** so accounts work without opening an email.

Free projects send a few confirmation emails an hour; for a big team, add your own SMTP
under **Authentication → Emails → SMTP Settings**, or switch confirmation off while testing.

## 4. Copy the two connection values
- **Project URL** — on the project's home page, like `https://abcdxyz.supabase.co`
- **Publishable key** — **Project Settings → API Keys**, starts with `sb_publishable_…`
  (the older **anon public** key under *Legacy API Keys*, starting with `eyJ…`, works too)

(This key is meant to be public; the rules in `schema.sql` decide what each person may do.
Never put a **secret** / **service_role** key in the app.)

## 5. Put them in the app — choose one
- **Easiest:** install the APK, choose **Share with my team**, paste the URL and key.
  Each phone does this once.
- **Built in (nobody types anything):** in GitHub → your repo → **Settings → Secrets and
  variables → Actions → New repository secret**: add `SUPABASE_URL` and `SUPABASE_ANON_KEY`.
  Then publish a new release (e.g. `v1.1.0`); that APK connects by itself.

## 6. Start the firm
1. **Admin:** open the app → **Create account** with your email → **Create the firm**
   (firm name + your name). You are the administrator.
2. **Admin:** **More → Employees → +** add each person with their **email**, role, branch,
   reporting manager and HR access. No password needed.
3. **Each employee:** install the app → **Create account** with that same email → they are
   linked to their record automatically and can punch in.

## Notes
- Free plan: 500 MB database (years of attendance for a small firm), 50,000 users.
- A free project **pauses after 7 days with no activity**. If the app says it cannot reach
  the server, open supabase.com → your project → **Restore project**. Daily use keeps it awake.
- Forgotten password: the sign-in screen's **Forgot password** emails a reset link.
- To see raw data: Supabase → **Table Editor → records**.
