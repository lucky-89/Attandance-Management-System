// Extracted from BOSS server.js: the HR guards and calendar helpers.
/* AN ATTENDANCE ROW IS WRITTEN ONCE AND NOT EDITED.

   Everything else in BOSS is a whole-array PUT, which means the browser
   decides what the store holds. For attendance that is not good enough:
   a punch is a claim about where somebody was and when, and the one
   thing it must not be is adjustable afterwards by whoever has the page
   open. Nothing in the UI offers to edit one, but "the UI does not offer
   it" is not the same as "it cannot happen".

   So this is append-only, checked here rather than in the browser. A
   write may add a day, add a session to a day, and close an open session
   by setting its punch-out and that punch-out's location. It may not
   change a punch-in, change a punch-out that is already set, change or
   add a location to a punch already recorded, drop a session, or drop a
   day. Anything else comes back 409 naming what it tried to move.

   Since the punch clock allows Punch Out to be pressed again, a
   punch-out may also MOVE — but only later, and only with the one it
   replaces appended to that session's outHistory, so the earlier punch
   is still on the record. And a day has one punch-in: a second session
   cannot be added to a day that already has one.

   One exception, deliberate and narrow: an empty array is allowed
   through, because Reset System wipes every store and an administrator
   clearing the system is not the thing this is guarding against. What it
   guards against is a quiet edit to one timestamp among hundreds. */
const ATT_FIXED = ['in', 'inBy', 'inLoc', 'out', 'outBy', 'outLoc'];
const ATT_OUT = ['out', 'outBy', 'outLoc'];
// A later punch-out replacing an earlier one, with the earlier one kept.
function attendanceOutMoved(a, b) {
  if (!a.out || !b.out || b.out === a.out) return false;
  if (!(new Date(b.out).getTime() > new Date(a.out).getTime())) return false;
  const oldH = Array.isArray(a.outHistory) ? a.outHistory : [];
  const newH = Array.isArray(b.outHistory) ? b.outHistory : [];
  if (newH.length !== oldH.length + 1) return false;
  if (JSON.stringify(newH.slice(0, oldH.length)) !== JSON.stringify(oldH)) return false;
  const kept = newH[newH.length - 1] || {};
  return ATT_OUT.every((k) => JSON.stringify(kept[k] === undefined ? null : kept[k]) === JSON.stringify(a[k] === undefined ? null : a[k]));
}
function attendanceGuard(before, after) {
  let old;
  try { old = JSON.parse(before || '[]'); } catch (e) { return null; }
  let next;
  try { next = JSON.parse(after); } catch (e) { return 'that is not valid JSON'; }
  if (!Array.isArray(old) || !Array.isArray(next)) return null;
  if (!next.length) return null;                       // a full wipe: see above
  const byId = new Map(old.filter((r) => r && r.id).map((r) => [r.id, r]));
  const seen = new Set();
  for (const row of next) {
    if (!row || !row.id) continue;
    seen.add(row.id);
    const was = byId.get(row.id);
    if (!was) {                                        // a new day
      if (Array.isArray(row.sessions) && row.sessions.length > 1)
        return 'attendance ' + row.id + ' tried to start a day with more than one punch-in';
      continue;
    }
    if (row.userId !== was.userId || row.date !== was.date)
      return 'attendance ' + row.id + ' tried to move to another person or another date';
    const oldS = Array.isArray(was.sessions) ? was.sessions : [];
    const newS = Array.isArray(row.sessions) ? row.sessions : [];
    if (newS.length < oldS.length)
      return 'attendance ' + row.id + ' tried to drop a session';
    if (newS.length > oldS.length && oldS.length >= 1)
      return 'attendance ' + row.id + ' already has a punch-in for that day';
    for (let i = 0; i < oldS.length; i++) {
      const a = oldS[i] || {}, b = newS[i] || {};
      const moved = attendanceOutMoved(a, b);
      if (!moved && JSON.stringify(a.outHistory || []) !== JSON.stringify(b.outHistory || []))
        return 'attendance ' + row.id + ' tried to change an earlier punch-out';
      for (const k of ATT_FIXED) {
        const had = a[k] !== undefined && a[k] !== null;
        if (!had) continue;                            // not set yet: may be set now
        if (moved && ATT_OUT.includes(k)) continue;    // a later punch-out, the earlier one kept
        if (JSON.stringify(a[k]) !== JSON.stringify(b[k]))
          return 'attendance ' + row.id + ' tried to change a recorded ' + k;
      }
    }
  }
  for (const r of old) if (r && r.id && !seen.has(r.id))
    return 'attendance ' + r.id + ' was dropped';
  return null;
}
}

/* WHEN A PUNCH IS ALLOWED, decided here and not only by a greyed-out button.
   A new punch in has to land inside the punch clock's hours on the firm's
   clock, on a day there is attendance, by somebody not on an approved
   full-day leave, and be stamped with the time it actually happened. A punch
   out has to land before the clock closes. Rows for other days (a restore,
   an import) are not judged: only a punch made today is a punch made now. */
function attendanceRulesGuard(before, after) {
  let old, next;
  try { old = JSON.parse(before || '[]'); next = JSON.parse(after); } catch (e) { return null; }
  if (!Array.isArray(old) || !Array.isArray(next)) return null;
  const now = bizNow();
  const cfg = hrConfigNow();
  const from = minsOf(cfg.day.from), until = minsOf(cfg.day.until);
  const byId = new Map(old.filter((r) => r && r.id).map((r) => [r.id, r]));
  for (const row of next) {
    if (!row || !row.id || row.date !== now.date) continue;
    const was = byId.get(row.id) || {};
    const oldS = Array.isArray(was.sessions) ? was.sessions : [];
    const newS = Array.isArray(row.sessions) ? row.sessions : [];
    if (newS.length > oldS.length) {
      const closed = cfg.compOffEnabled ? '' : hrClosedWhy(row.date, cfg);
      if (closed) return 'You cannot punch in today. ' + closed;
      const leave = hrApprovedLeave(row.userId, row.date);
      if (leave && !hrLeaveIsHalf(leave, cfg))
        return 'You cannot punch in today: you are on approved leave' + (leave.type ? ' (' + leave.type + ')' : '') + '.';
      if (now.mins < from) return 'Punch In opens at ' + cfg.day.from + '.';
      if (now.mins >= until) return 'The punch clock closed at ' + cfg.day.until + ' today.';
      const at = new Date((newS[newS.length - 1] || {}).in).getTime();
      // (The test suites lay down days punched in hours ago to drive; they run with CRM_ATTENDANCE_ANY_DAY=1.)
      if (process.env.CRM_ATTENDANCE_ANY_DAY !== '1' && (!isFinite(at) || Math.abs(Date.now() - at) > 10 * 60000))
        return 'A punch in has to carry the time it was made.';
    }
    for (let i = 0; i < Math.min(oldS.length, newS.length); i++) {
      const a = oldS[i] || {}, b = newS[i] || {};
      if (b.out && b.out !== a.out) {
        if (!cfg.compOffEnabled && hrClosedWhy(row.date, cfg)) return 'You cannot punch out today. ' + hrClosedWhy(row.date, cfg);
        if (now.mins >= until) return 'Punch Out closed at ' + cfg.day.until + '. Ask for a regularization if your last punch out is wrong.';
      }
    }
  }
  return null;
}

/* A service task's Due Date cannot be earlier than its Assigned Date. Only a
   task this write adds, or whose dates it changes, is judged: an old row
   with odd dates does not block somebody else's edit. */
// Depth in the reporting tree (parentUserId), the same measure the page uses
// for "your level and below". A ring counts as top level.
function teamDepths(team) {
  const by = {};
  team.forEach((t) => { if (t && t.id) by[t.id] = t; });
  const depth = {};
  Object.keys(by).forEach((start) => {
    if (depth[start] !== undefined) return;
    const path = []; const walked = new Set();
    let cur = start;
    while (cur && by[cur] && !walked.has(cur) && depth[cur] === undefined) { walked.add(cur); path.push(cur); cur = by[cur].parentUserId; }
    if (cur && walked.has(cur)) { path.forEach((id) => { depth[id] = 0; }); return; }
    let d = (cur && depth[cur] !== undefined) ? depth[cur] : -1;
    for (let i = path.length - 1; i >= 0; i--) depth[path[i]] = ++d;
  });
  return depth;
}
// May this member give work to that one? Administrators and people who
// administer users may give it to anybody; everyone else to their own level
// of the reporting tree or below, never upwards.
function mayAssignTask(member, toId, team) {
  if (!member || !toId || toId === member.id) return true;
  const f = member.adminFlags || {};
  if (member.role === 'Admin' || f.manageUsers || f.managePermissions) return true;
  const d = teamDepths(team);
  return (d[toId] === undefined ? 0 : d[toId]) >= (d[member.id] === undefined ? 0 : d[member.id]);
}
function tasksGuard(before, after, member, fromForm) {
  let old, next;
  try { old = JSON.parse(before || '[]'); next = JSON.parse(after); } catch (e) { return null; }
  if (!Array.isArray(next)) return null;
  const byId = new Map((Array.isArray(old) ? old : []).filter((r) => r && r.id).map((r) => [r.id, r]));
  const team = storeList('team');
  const today = bizNow().date;
  for (const t of next) {
    if (!t || t.deletedAt) continue;
    const was = byId.get(t.id);
    if (member && t.assignTo && (!was || was.assignTo !== t.assignTo) && !mayAssignTask(member, t.assignTo, team)) {
      const to = team.find((x) => x && x.id === t.assignTo);
      return 'You can assign or reassign a task only to people at your own level or below you in the reporting line. '
        + ((to && to.name) || t.assignTo) + ' is above you' + (t.id ? ' (' + t.id + ')' : '') + '.';
    }
    if (fromForm && t.assignedDate && (!was || was.assignedDate !== t.assignedDate)
        && String(t.assignedDate).slice(0, 10) < today)
      return 'Assigned Date cannot be in the past. Please select today or a later date.';
  }
  for (const t of next) {
    if (!t || t.deletedAt || !t.assignedDate || !t.dueDate) continue;
    const was = byId.get(t.id);
    if (was && was.assignedDate === t.assignedDate && was.dueDate === t.dueDate) continue;
    if (String(t.dueDate).slice(0, 10) < String(t.assignedDate).slice(0, 10))
      return 'Due Date cannot be earlier than Assigned Date. Please select a valid Due Date.'
        + (t.id ? ' (' + t.id + (t.taskType ? ', ' + t.taskType : '') + ')' : '');
  }
  return null;
}

// Leave: one active request per person per date. A new or changed request
// that is Pending or Approved must not overlap another Pending or Approved
// request of the same person. Rows already on the server count even when the
// page sending this write never saw them, so two requests sent at the same
// moment cannot both land. Untouched older rows are left as they are.
function leaveGuard(before, after) {
  let old, next;
  try { old = JSON.parse(before || '[]'); next = JSON.parse(after); } catch (e) { return null; }
  if (!Array.isArray(next)) return null;
  const ACTIVE = ['Pending', 'Approved'];
  const day = (v) => String(v || '').slice(0, 10);
  const byId = new Map((Array.isArray(old) ? old : []).filter((r) => r && r.id).map((r) => [r.id, r]));
  const all = new Map(byId);
  next.forEach((r) => { if (r && r.id) all.set(r.id, r); });
  /* Cancelling: the request must say which dates went, those dates must be
     its own, and whatever is kept is written as requests that sit inside
     the original and on none of the cancelled dates. */
  const CANCELLED = ['Cancelled', 'Partially Cancelled'];
  const datesOf = (r) => {
    const out = [], d = new Date(day(r.from) + 'T00:00:00Z'), end = day(r.to || r.from);
    for (let i = 0; i < 400 && !isNaN(d) && d.toISOString().slice(0, 10) <= end; i++) {
      out.push(d.toISOString().slice(0, 10)); d.setUTCDate(d.getUTCDate() + 1);
    }
    return out;
  };
  for (const r of next) {
    if (!r || !r.id) continue;
    const was = byId.get(r.id);
    if (was && ACTIVE.indexOf(was.status) >= 0 && CANCELLED.indexOf(r.status) >= 0) {
      const c = r.cancellation || {};
      const own = datesOf(was);
      if (!Array.isArray(c.dates) || !c.dates.length) return 'A cancelled leave must say which dates were cancelled.';
      if (c.dates.some((d) => own.indexOf(day(d)) < 0)) return 'Only dates inside the leave itself can be cancelled.';
      const kept = own.filter((d) => c.dates.map(day).indexOf(d) < 0);
      if ((r.status === 'Cancelled') !== (kept.length === 0))
        return r.status === 'Cancelled' ? 'Some dates of this leave are still kept, so it is Partially Cancelled, not Cancelled.'
          : 'Every date of this leave was cancelled, so it is Cancelled, not Partially Cancelled.';
    }
    if (!was && r.remainderOf && ACTIVE.indexOf(r.status) >= 0) {
      const o = all.get(r.remainderOf);
      if (!o || CANCELLED.indexOf(o.status) < 0) return 'Kept dates can only follow a cancellation of the leave they belong to.';
      const own = datesOf(o), gone = ((o.cancellation || {}).dates || []).map(day);
      if (datesOf(r).some((d) => own.indexOf(d) < 0 || gone.indexOf(d) >= 0))
        return 'The kept dates must lie inside the original leave and not on a cancelled date.';
    }
  }
  for (const r of next) {
    if (!r || !r.id || ACTIVE.indexOf(r.status) < 0 || !r.from) continue;
    const was = byId.get(r.id);
    if (was && was.status === r.status && day(was.from) === day(r.from) && day(was.to) === day(r.to)) continue;
    const a1 = day(r.from), a2 = day(r.to || r.from);
    for (const o of all.values()) {
      if (!o || o.id === r.id || o.userId !== r.userId || ACTIVE.indexOf(o.status) < 0 || !o.from) continue;
      const b1 = day(o.from), b2 = day(o.to || o.from);
      if (a1 <= b2 && b1 <= a2)
        return 'Leave is already ' + (o.status === 'Approved' ? 'approved' : 'requested') + ' for ' + b1
          + (b2 !== b1 ? ' to ' + b2 : '') + ', which overlaps ' + a1 + (a2 !== a1 ? ' to ' + a2 : '')
          + '. Choose dates that do not overlap, or cancel the earlier request first.';
    }
  }
  return null;
}

// Comp-off: one record per person per extra working day, so the same day
// can never be credited twice, and a decided record keeps the credit it was
// decided at. Untouched older rows are left as they are.
function compOffGuard(before, after) {
  let old, next;
  try { old = JSON.parse(before || '[]'); next = JSON.parse(after); } catch (e) { return null; }
  if (!Array.isArray(next)) return null;
  const byId = new Map((Array.isArray(old) ? old : []).filter((r) => r && r.id).map((r) => [r.id, r]));
  const seen = new Map();
  for (const r of next) {
    if (!r || !r.id || !r.userId || !r.date) continue;
    const k = r.userId + '|' + String(r.date).slice(0, 10);
    if (seen.has(k)) return 'Comp-off for ' + String(r.date).slice(0, 10) + ' is already recorded. One day can be credited only once.';
    seen.set(k, r.id);
    const was = byId.get(r.id);
    if (was && ['Approved', 'Rejected'].indexOf(was.status) >= 0
        && (was.status !== r.status || Number(was.credit) !== Number(r.credit)))
      return 'This comp-off was already ' + was.status.toLowerCase() + ' and cannot be changed.';
  }
  return null;
}

// PUT /api/storage/:key  body: { value: "..." } -> { key, value }
/* A SAVE NEVER DROPS A RECORD IT DID NOT MEAN TO.

   The browser saves a store by sending the whole list it holds. A tab a
   moment behind — a colleague created a lead a minute ago and this tab has
   not read it yet — sent a list without that lead, and replacing the store
   with it deleted the lead. A read that failed was worse: the tab took the
   store for empty, and its next save sent back almost nothing. That is how
   records "deleted themselves".

   So a list of records is merged, not replaced: a record the server holds
   that is missing from what was sent is kept, unless the browser says it
   removed it (body.removed — the ids it had read and has taken out: a
   purge, a merge, a lead converted to a client). A reset or a restore,
   which really do mean "this and nothing else", says so with
   X-CRM-Replace. Edits to the same record are still the newest save's. */
/* =====================================================================
   SAVING WITHOUT OVERWRITING ANYBODY (changes 14-11-2026)

   A browser used to send a whole store — every lead, every client — on
   every save, and the server took its copy of every record. A tab that had
   missed an update (asleep in the background, its live connection dropped)
   then put its old copies back over newer ones the moment it saved
   anything at all: a lead qualified a minute earlier went back to "Ready
   for Qualification", an FQ result that had come in vanished.

   Two rules now, on the side that cannot be bypassed:

   1. A patch. A browser sends only what it changed — the records it added,
      and for each record it edited only the fields it edited — and the
      server applies those fields to the record as it holds it now. Fields
      nobody touched stay as they are, whoever changed them last. Two people
      editing different fields of one record both keep their change.

   2. A stamp. Every record carries _mt, set here whenever the record
      changes. A whole-store write (a tab still running the old page, a
      reset) whose copy of a record is older than the stored one does not
      replace it: the stored record is kept, and the tab is sent it.
   ===================================================================== */
/* ---- THE BUILD A PAGE WAS LOADED FROM (changes 16-11-2026) ----------
   A tab opened before an update keeps running the code it was opened
   with, and an old page saves the old way: whole lists, its own copies of
   every record. Left alone, one such tab — on a desk nobody has touched
   since last week — puts old stages and old values back over everybody's
   newer work every time it saves anything. So every page says which build
   it came from, and a save from any other build is refused: the page keeps
   its work, says so, and asks to be reloaded. */
const BUILD_ID = (() => {
  const h = crypto.createHash('sha1');
  ['server.js', 'public/index.html', 'public/client-import.js', 'public/multi-asset.js', 'public/client-merge.js', 'public/import-compare.js']
    .forEach((f) => { try { h.update(fs.readFileSync(path.join(__dirname, f))); } catch (e) { /* a file that is not there is not part of the build */ } });
  return h.digest('hex').slice(0, 12);
})();
function buildRefusal(req) {
  if (process.env.CRM_BUILD_GATE === 'off') return null;
  const b = req.get('X-CRM-Build') || '';
  if (b === BUILD_ID) return null;
  return { error: 'outdated', reload: true, build: BUILD_ID,
    message: 'BOSS has been updated since this page was opened. Reload the page to keep saving — what you changed is kept and is sent once it has reloaded.' };
}

/* ---- EVERY CHANGE, KEPT (changes 16-11-2026) ---------------------------
   Every record a save changes, adds or removes is written to a history file
   next to the database — who, when, and for each field what it was and
   what it became; a removed record in full. Nothing in it is ever
/* ---- the attendance calendar, read the way the page reads it ---- */
function hrConfigNow() {
  const c = storeObj('hrConfig');
  const day = Object.assign({ punchInFrom: '08:45', punchOutUntil: '20:45' }, c.day || {});
  const r = Object.assign({ enabled: true, inEmailAt: '09:15', outNotifyAt: '18:00', outEmailAt: '19:00' }, c.reminders || {});
  return {
    day: { from: hhmmOk(day.punchInFrom) ? day.punchInFrom : '08:45', until: hhmmOk(day.punchOutUntil) ? day.punchOutUntil : '20:45' },
    weeklyOff: Array.isArray(c.weeklyOff) ? c.weeklyOff : [],
    holidays: Array.isArray(c.holidays) ? c.holidays : [],
    leaveTypes: Array.isArray(c.leaveTypes) ? c.leaveTypes : [],
    // With comp-off on, a Sunday, weekly off or holiday can be worked and punched.
    compOffEnabled: ((c.annualLeave || {}).compOffEnabled) !== false,
    reminders: {
      enabled: r.enabled !== false,
      inAt: hhmmOk(r.inEmailAt) ? r.inEmailAt : '09:15',
      outNotifyAt: hhmmOk(r.outNotifyAt) ? r.outNotifyAt : '18:00',
      outEmailAt: hhmmOk(r.outEmailAt) ? r.outEmailAt : '19:00',
    },
  };
}
function dowOf(date) { const d = new Date(date + 'T12:00:00Z'); return isNaN(d) ? -1 : d.getUTCDay(); }
/* Why there is no attendance on this date, or ''. */
function hrClosedWhy(date, cfg) {
  cfg = cfg || hrConfigNow();
  if (process.env.CRM_ATTENDANCE_ANY_DAY === '1') return '';
  if (dowOf(date) === 0) return 'There is no attendance on Sundays.';
  const h = cfg.holidays.find((x) => x && x.date === date);
  if (h) return 'It is a holiday' + (h.name ? ' — ' + h.name : '') + '.';
  const names = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  if (cfg.weeklyOff.indexOf(names[dowOf(date)]) >= 0) return 'It is a weekly off.';
  return '';
}
function hrApprovedLeave(userId, date) {
  return storeList('hrLeave').find((l) => l && l.userId === userId && l.status === 'Approved'
    && String(l.from || '') <= date && date <= String(l.to || l.from || '')) || null;
}
function hrLeaveIsHalf(leave, cfg) {
  if (!leave) return false;
  if (leave.unit) return leave.unit === 'half';
  const t = (cfg || hrConfigNow()).leaveTypes.find((x) => (typeof x === 'string' ? x : (x && x.name)) === leave.type);
  return !!(t && typeof t === 'object' && t.unit === 'half');
}
function hrDayRecord(userId, date) {
  return storeList('hrAttendance').find((r) => r && r.userId === userId && r.date === date) || null;
}
function hrOpen(rec) { return rec && Array.isArray(rec.sessions) ? rec.sessions.find((s) => s && s.in && !s.out) || null : null; }
function hrEverIn(rec) { return !!(rec && Array.isArray(rec.sessions) && rec.sessions.some((s) => s && s.in)); }

/* Everybody who keeps attendance: the active team. */
function activeMembers() {
  return storeList('team').filter((t) => t && t.id && !t.deletedAt
    && !/inactive|left|disabled|deactivated/i.test(String(t.status || 'Active')));
}
function memberEmail(m) {
  if (m.email && ADDRESS_RE.test(String(m.email))) return String(m.email).trim();
  try { const u = findUserByMember.get(m.id); if (u && ADDRESS_RE.test(u.email)) return u.email; } catch (e) { /* no account */ }
  return '';
}
