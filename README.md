# Finowise HR — Android

A standalone Android app built from the **HR module of FINOWISE BOSS** (the Attendance,
Leave, Incentive and Appraisal sections of the web CRM). It does **not** use the BOSS
server. Two modes, chosen on first launch:

- **Share with my team** — everybody signs in on their own phone; the firm's data lives in your own
  free **Supabase** project (see [`supabase/SETUP.md`](supabase/SETUP.md)). The database enforces the
  rules the BOSS server enforced: append-only attendance stamped by the server's clock, the punch
  window, one leave per date, only the approver decides, a decided comp-off is final, and each person
  reads only what they may.
- **This device only** — one phone or tablet holds everything, no internet needed.

Records keep the BOSS JSON shapes (`team`, `hrConfig`, `hrAttendance`, `hrLeave`, `hrCompOff`,
`hrRegularizations`, `hrIncentive`, `hrAppraisals`).

**Install:** download the APK from the repository's **Releases** page on your phone (Android 8.0+), open it and allow "Install unknown apps". On first launch you set up
the firm and become its administrator, then add your team under More › Employees.

## What the app does

| Area | Features (ported rule-for-rule from BOSS) |
|---|---|
| **Punch clock** | Punch In once a day from 08:45, Punch Out until 20:45 (both configurable). Punching out again replaces the earlier punch-out and keeps the old one. GPS is read once per punch. If location is required and missing, the punch is refused; if optional, the punch records why there is no location. A coordinate is never invented. |
| **Attendance** | Day status: present, punched in, incomplete, absent, leave, holiday, weekly off or Sunday. Lateness is shown only once an office start time and grace period are set. Attendance % = eligible time (punch-in to the latest punch-out, plus approved regularized periods, clipped to the punch window, overlaps counted once) ÷ a full day (9 h by default), capped at 100%. Days worked count in proportion to that %. Includes a 45-day history and a day detail with punches and map links. |
| **Regularization** | Ask HR to add time periods to a day, with a live preview of the new %. HR approves (the % is recomputed) or rejects (a reason is required). |
| **Leave** | Sick, Casual and configured types. Full Day, or First or Second Half. Category is Paid, Unpaid or Comp-Off. Requests route to the branch head for your branch, then any branch head, then your reporting manager, then HR. Rules: one active request per date; balances are checked on request and again on approval; a decline needs a reason. You can cancel all of a leave or chosen days; the days still wanted become new requests linked to the original. Sundays, weekly offs and holidays are never charged. Delegating pending tasks notifies the delegate on approval. |
| **Annual leave & comp-off** | 12 paid days a year by default. Working a Sunday, weekly off or holiday creates a comp-off record: 9 h earns a day, 4.5 h half a day. An approver verifies it before it is credited, and a decided record cannot change. The leave ledger shows each entry with running balances. |
| **HR views** | Dashboard tiles (present, punched in, on leave, not in), a month board grid per person (exports to CSV), everyone's leave balances (filters, CSV export), and each person's history. |
| **Approvals** | One inbox for leave, comp-off and regularization. |
| **Incentive** | The firm's workbook (*RE Incentive Template – Ver 2*) ported exactly: product, lead-source and meeting multipliers; seven streams with baseline, slab, rate and payout; non-sales task points. Monthly sheets: draft, then finalise (frozen snapshot), then reopen. The grid is editable by an admin. |
| **Appraisal** | The BOSS appraisal engine, unchanged in behaviour. Five Cs: the four judged Cs (admin only) plus Contribution KPIs (reporting manager). Company growth sets the pool, which gives the increment %, the amount and the new CTC. A finalised appraisal is never restated. |
| **Access** | Five grants per person: Attendance (on by default), Incentive, Appraisal, see everybody's HR records, and Administer HR. "Admin" role holds all five. |
| **Reminders** | Notifications for "you have not punched in" (after 09:10) and "you are still punched in" (after 19:00). They repeat until done and stay quiet on leave days, weekly offs and holidays. |
| **Admin** | Employees (roles, branch, department, manager, CTC, grants, passwords), HR Settings, audit log, JSON backup and restore. |

### What changed from BOSS because there is no server

- **Server rules run in Supabase.** BOSS's attendance, leave and comp-off guards (`server.js`) are Postgres triggers and row-level security in `supabase/schema.sql` (tested by `supabase/test/`), and also run in the app (`core/…/Guards.kt`).
- **Emails are replaced by in-app notifications.** Leave requests and decisions, regularizations, comp-off and delegation all notify.
- **Incentive and Appraisal inputs are entered by hand.** BOSS read some figures from its CRM stores (meetings, the task bank, the Business tracker, AUM imports). With no CRM here, sheet lines, task counts and KPI % are typed in. Helpers convert achieved/target into % and opening/closing totals into growth %.
- **Active/idle time is not measured.** BOSS's browser idle-tracking has no honest phone equivalent. Worked time is still exact from the punches.
- **Sign-in:** Supabase Auth in shared mode (people create their own account with the email HR added); PBKDF2-hashed local passwords in device-only mode.

## Project layout

```
core/   pure Kotlin (JVM) — all HR rules, testable without Android
  model/Models.kt        records (BOSS JSON shapes)
  HrRules.kt             read-side rules: punch window, day status, %, leave, balances, ledger, month board, reminders
  HrService.kt           every change: punches, leave, comp-off, regularization, employees, settings, backup
  Guards.kt              BOSS server's guards
  incentive/             workbook calculation, sheets, grid config
  appraisal/             appraisal engine + integration (roles, permissions, finalise)
app/    Android (Jetpack Compose, Material 3)
docs/boss-hr-extract/    the HR code extracted from BOSS (frontend section, appraisal engine, server guards, test suites)
dist/                    built APKs and screenshots
```

## Build and test

Needs JDK 17+ and the Android SDK (platform 35). Put `sdk.dir=…` in `local.properties`.

```bash
./gradlew :core:test                 # 45 tests: rules, sync, and oracle tests vs BOSS's own JS
bash supabase/test/run.sh            # 65 database checks against a local Postgres
./gradlew :app:testDebugUnitTest     # renders every main screen on the JVM (Robolectric) → app/build/screens/
./gradlew :app:assembleRelease       # app/build/outputs/apk/release/app-release.apk
```

The oracle tests compare the Kotlin ports with BOSS's JavaScript, run in Node on the same inputs:

- **Incentive:** 31 calculations, 21 category cases, 66 band edges, config edits and the sheet lifecycle.
- **Appraisal:** 961 engine cases.

The release APK is signed with the debug key so it installs directly. Sign it with your own keystore before publishing to the Play Store.

## Releasing a test build (free, GitHub Actions)

Every push to `main` runs the tests and builds the APK; it can be downloaded from the run's **Artifacts**.
To publish a release that testers can install from, tag a version and push the tag:

```bash
git tag v1.0.1
git push origin v1.0.1
```

The workflow (`.github/workflows/release.yml`) attaches `FinowiseHR-1.0.1.apk` to a new GitHub Release.
