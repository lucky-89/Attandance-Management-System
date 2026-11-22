#!/usr/bin/env bash
# Runs supabase/schema.sql and its checks against a throwaway local Postgres database.
# Needs a local Postgres you can reach as the "postgres" user (e.g. apt install postgresql).
set -e
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"; cp "$here"/*.sql "$here"/../schema.sql "$tmp"/; chmod -R a+rX "$tmp"
DB=finowise_hr_test
su postgres -c "dropdb --if-exists $DB; createdb $DB"
su postgres -c "psql -q -d $DB -v ON_ERROR_STOP=1 -f $tmp/fake_auth.sql -f $tmp/schema.sql" >/dev/null
su postgres -c "psql -q -d $DB -f $tmp/test_schema.sql" 2>&1 | tee "$tmp/out.txt"
echo "------"; echo "PASS $(grep -c '^PASS' "$tmp/out.txt")  FAIL $(grep -c '^FAIL' "$tmp/out.txt" || true)"
grep -q '^FAIL' "$tmp/out.txt" && exit 1 || exit 0
