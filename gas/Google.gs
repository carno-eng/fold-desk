/**
 * Google service integrations for Fold Desk.
 * Settings live in cfg.google = { calendars:{<calId>:false,...}, writeCal:<calId>, tasks:true, starred:true, gmailAi:true }
 * Requires the advanced services "Tasks API" and "People API" (declared in appsscript.json).
 */
const G_DEF = {tasks:true, starred:true, gmailAi:false};
function gcfg_(d){ return Object.assign({}, G_DEF, (d || data_()).cfg.google || {}); }

/* ---------- router (called from doPost) ---------- */
function google_(op, a){
  switch(op){
    case "g.status": return gStatus_();
    case "g.calendars": return CalendarApp.getAllCalendars().filter(c => !/#holiday@|holiday@group\.v\.calendar/.test(c.getId()))
      .map(c => ({id:c.getId(), name:c.getName(), owned:c.isOwnedByMe(), color:c.getColor(), on:(gcfg_().calendars || {})[c.getId()] !== false, writable:c.isOwnedByMe()}));
    case "g.saveEvent": return saveEventToGoogle_(a);
    case "g.deleteEvent": return deleteGoogleEvent_(a);
    case "g.syncTasks": return syncGoogleTasks();
    case "g.gmail": return scanGmail(true);
    case "g.contacts": return searchContacts_(String(a.q || ""));
    case "g.saveContact": return saveContact_(a.person || {});
    case "g.drive": return searchDrive_(String(a.q || ""), a.mime === "sheet");
    case "g.sheet": return readSheet_(String(a.id || ""));
    case "g.syncAll": syncGoogleTasks(); scanGmail(false); importCalendar(); return gStatus_();
  }
  throw coded_("bad_request");
}
function gStatus_(){
  const g = gcfg_();
  return {cfg:g, last:{tasks:P.getProperty("G_TASKS_AT") || "", gmail:P.getProperty("G_GMAIL_AT") || "", cal:P.getProperty("G_CAL_AT") || ""},
    ai:!!P.getProperty("GEMINI_API_KEY"), me:Session.getEffectiveUser().getEmail()};
}

/* ---------- Calendar: write-through ---------- */
function saveEventToGoogle_(a){
  const ev = a.ev || {}, localId = String(a.localId || "");
  if(!ev.title || !/^\d{4}-\d{2}-\d{2}$/.test(ev.date || "")) throw coded_("bad_request");
  const tz = "Asia/Tokyo";
  const at = (d, t) => Utilities.parseDate(d + " " + t, tz, "yyyy-MM-dd HH:mm");
  let ge = ev.calId ? CalendarApp.getEventById(ev.calId) : null;
  const cal = ge ? null : (a.calendarId ? CalendarApp.getCalendarById(a.calendarId) : null) || CalendarApp.getDefaultCalendar();
  const allDay = !ev.time;
  const start = allDay ? Utilities.parseDate(ev.date, tz, "yyyy-MM-dd") : at(ev.date, ev.time);
  const end = allDay ? null : at(ev.date, ev.endTime && ev.endTime > ev.time ? ev.endTime : Utilities.formatDate(new Date(start.getTime() + 3600e3), tz, "HH:mm"));
  const opts = {description:String(ev.note || ""), location:String(ev.place || "")};
  if(ge){
    ge.setTitle(ev.title); allDay ? ge.setAllDayDate(start) : ge.setTime(start, end);
    ge.setDescription(opts.description); ge.setLocation(opts.location);
  } else {
    ge = allDay ? cal.createAllDayEvent(ev.title, start, opts) : cal.createEvent(ev.title, start, end, opts);
  }
  if(!allDay && ev.remindMin !== undefined){ ge.removeAllReminders(); if(+ev.remindMin > 0) ge.addPopupReminder(+ev.remindMin); }
  const calId = ge.getId(), id = "gc-" + hash_(calId) + "-" + ev.date.replace(/-/g, "");
  withLock_(() => {
    const d = data_(), E = d.cols.events;
    const keep = localId && E[localId] ? E[localId] : (E[id] || {});
    E[id] = Object.assign({}, keep, ev, {source:"gcal", calId, account:"個人", updatedAt:now_()});
    delete E[id].phone; delete E[id].gcalId; delete E[id].google;
    if(localId && localId !== id) delete E[localId];
    // the old dated id if the date moved
    Object.keys(E).forEach(k => { if(k !== id && E[k].calId === calId) delete E[k]; });
    save_(d);
  });
  return {id, calId};
}
function deleteGoogleEvent_(a){
  const ge = a.calId ? CalendarApp.getEventById(String(a.calId)) : null;
  if(ge) ge.deleteEvent();
  if(a.id) withLock_(() => { const d = data_(); delete d.cols.events[String(a.id)]; save_(d); });
  return {ok:true};
}

/* ---------- Google Tasks: two-way ---------- */
function taskList_(){
  let id = P.getProperty("G_TASKLIST");
  if(id){ try { Tasks.Tasklists.get(id); return id; } catch(_){} }
  const lists = (Tasks.Tasklists.list({maxResults:100}).items || []);
  const hit = lists.find(l => l.title === "Fold Desk");
  id = hit ? hit.id : Tasks.Tasklists.insert({title:"Fold Desk"}).id;
  P.setProperty("G_TASKLIST", id);
  return id;
}
function syncGoogleTasks(){
  const d0 = data_(); if(!gcfg_(d0).tasks) return {skipped:true};
  const list = taskList_();
  const remote = {}; let token;
  do {
    const r = Tasks.Tasks.list(list, {maxResults:100, showCompleted:true, showHidden:true, pageToken:token});
    (r.items || []).forEach(t => remote[t.id] = t); token = r.nextPageToken;
  } while(token);
  let n = 0;
  withLock_(() => {
    const d = data_(), T = d.cols.tasks;
    const dueOf = t => t.due ? t.due.slice(0, 10) : "";
    const toG = t => ({title:t.title || "（無題）", notes:[t.note || "", t.area ? "領域: " + t.area : ""].filter(Boolean).join("\n").slice(0, 8000),
      due:t.due ? t.due + "T00:00:00.000Z" : null, status:t.status === "done" ? "completed" : "needsAction"});
    const mapped = {};
    Object.keys(T).forEach(id => {
      const t = T[id];
      if(t.gtaskId && remote[t.gtaskId]){
        const g = remote[t.gtaskId]; mapped[g.id] = 1;
        const gAt = Date.parse(g.updated || 0), aAt = Date.parse(t.updatedAt || 0), last = Date.parse(t.gsyncAt || 0);
        if(gAt > last + 1000 && gAt >= aAt){            // changed on Google side
          t.title = g.title || t.title; t.due = dueOf(g);
          t.status = g.status === "completed" ? "done" : (t.status === "done" ? "todo" : t.status);
          t.updatedAt = now_(); t.gsyncAt = t.updatedAt; n++;
        } else if(aAt > last + 1000){                    // changed in the app
          const r = Tasks.Tasks.patch(toG(t), list, g.id); t.gsyncAt = r.updated || now_(); n++;
        }
      } else if(t.gtaskId && !remote[t.gtaskId]){         // deleted on Google
        delete t.gtaskId; t.gsyncAt = now_();
      } else if(t.status !== "done"){                      // new in the app
        const r = Tasks.Tasks.insert(toG(t), list); t.gtaskId = r.id; t.gsyncAt = r.updated || now_(); mapped[r.id] = 1; n++;
      }
    });
    Object.keys(remote).forEach(gid => {                   // new on Google
      const g = remote[gid];
      if(mapped[gid] || g.deleted || g.status === "completed" || !g.title) return;
      const id = "gt-" + gid.replace(/[^A-Za-z0-9_-]/g, "-").slice(0, 60);
      if(T[id]) return;
      T[id] = {title:g.title, due:dueOf(g), note:g.notes || "", priority:"mid", area:"", status:"todo", personIds:[], source:"gtasks", gtaskId:gid, createdAt:now_(), updatedAt:now_(), gsyncAt:g.updated || now_()};
      n++;
    });
    if(n) save_(d);
  });
  P.setProperty("G_TASKS_AT", now_());
  return {changed:n};
}

/* ---------- Gmail: starred → tasks, and (optional) AI extraction ---------- */
const FIN_RE = /(銀行|カード|証券|決済|請求|ポイント|ログイン|認証|パスワード|ワンタイム|配送|発送|ご注文|領収|receipt|invoice|verify|password|login|security alert)/i;
function scanGmail(manual){
  const d0 = data_(), g = gcfg_(d0); let n = 0;
  if(g.starred){
    const th = GmailApp.search("is:starred newer_than:60d", 0, 30);
    const starredIds = {};
    withLock_(() => {
      const d = data_(), T = d.cols.tasks;
      th.forEach(t => {
        const id = "gm-" + t.getId(); starredIds[id] = 1;
        if(T[id]) return;
        const subj = t.getFirstMessageSubject() || "（件名なし）";
        if(FIN_RE.test(subj)) return;
        T[id] = {title:"メール対応: " + subj.slice(0, 80), due:"", priority:"mid", area:"", status:"todo", note:"スターを付けたメール", personIds:[], url:"https://mail.google.com/mail/u/0/#all/" + t.getId(), source:"gmail-star", createdAt:now_(), updatedAt:now_()};
        n++;
      });
      // finished in the app → remove the star in Gmail
      Object.keys(T).forEach(id => {
        const t = T[id];
        if(t.source === "gmail-star" && t.status === "done" && !t.unstarred){
          try { const th2 = GmailApp.getThreadById(id.slice(3)); if(th2) th2.getMessages().forEach(m => m.isStarred() && m.unstar()); } catch(_){}
          t.unstarred = true; n++;
        }
      });
      if(n) save_(d);
    });
  }
  if(g.gmailAi && P.getProperty("GEMINI_API_KEY")) n += aiMail_();
  P.setProperty("G_GMAIL_AT", now_());
  return {changed:n};
}
function aiMail_(){
  const seen = JSON.parse(P.getProperty("G_MAIL_SEEN") || "{}");
  const th = GmailApp.search("newer_than:2d -category:promotions -category:social -in:chats", 0, 25).filter(t => !seen[t.getId()] && !FIN_RE.test(t.getFirstMessageSubject() || ""));
  if(!th.length) return 0;
  const me = Session.getEffectiveUser().getEmail().toLowerCase();
  const items = th.map(t => { const ms = t.getMessages(), m = ms[ms.length - 1];
    return {id:t.getId(), subject:t.getFirstMessageSubject(), from:m.getFrom(), date:Utilities.formatDate(m.getDate(), "Asia/Tokyo", "yyyy-MM-dd HH:mm"),
      lastFromMe:String(m.getFrom()).toLowerCase().indexOf(me) >= 0, body:m.getPlainBody().replace(/\s+/g, " ").slice(0, 1200)}; });
  const today = Utilities.formatDate(new Date(), "Asia/Tokyo", "yyyy-MM-dd");
  const prompt = `あなたは小学校教員の個人アシスタントです。今日は${today}。次のメールから、本人がやるべきこと・予定・返信が必要なものだけを取り出してください。
広告・お知らせ・自動通知・お金や口座に関するものは無視。児童や保護者の名前・家庭の事情・健康は書かず「保護者からの連絡」のように一般化する。
返答はJSONのみ: {"items":[{"mail":"メールid","type":"task|event|reply","title":"短く","due":"YYYY-MM-DD か 空","date":"YYYY-MM-DD","time":"HH:MM か 空","place":"","who":"相手の名前と所属"}]}
メール:
${JSON.stringify(items)}`;
  let res;
  try { res = gemini_(prompt, []); } catch(e){ console.error(e); return 0; }
  let n = 0;
  withLock_(() => {
    const d = data_();
    (res.items || []).forEach(x => {
      const src = items.find(i => i.id === x.mail); if(!src) return;
      const url = "https://mail.google.com/mail/u/0/#all/" + src.id;
      if(x.type === "task") n += applyItem_(d, {col:"tasks", id:"gm-" + src.id, doc:{title:x.title, due:x.due || "", priority:"mid", area:"", status:"todo", note:src.subject, personIds:[], url, source:"gmail", createdAt:now_()}}) ? 1 : 0;
      else if(x.type === "event" && x.date) n += applyItem_(d, {col:"events", id:"gme-" + src.id, doc:{title:x.title, date:x.date, time:x.time || "", place:x.place || "", kind:"", note:src.subject, url, source:"gmail"}}) ? 1 : 0;
      else if(x.type === "reply") n += applyItem_(d, {col:"followups", id:"fu-" + src.id, doc:{subject:x.title || src.subject, who:x.who || src.from.replace(/<.*>/, "").trim(), direction:src.lastFromMe ? "them" : "me", since:src.date.slice(0, 10), url, status:"open", threadId:src.id}}) ? 1 : 0;
    });
    if(n) save_(d);
  });
  th.forEach(t => seen[t.getId()] = Date.now());
  Object.keys(seen).forEach(k => { if(Date.now() - seen[k] > 7 * 864e5) delete seen[k]; });
  P.setProperty("G_MAIL_SEEN", JSON.stringify(seen));
  return n;
}

/* ---------- Contacts ---------- */
function searchContacts_(q){
  if(!q || q.length > 60) return [];
  try { People.People.searchContacts({query:"", readMask:"names"}); } catch(_){}   // warm-up call the API requires
  const r = People.People.searchContacts({query:q, readMask:"names,emailAddresses,phoneNumbers,organizations", pageSize:10});
  return (r.results || []).map(x => { const p = x.person || {};
    const org = (p.organizations || [])[0] || {};
    return {resourceName:p.resourceName, name:((p.names || [])[0] || {}).displayName || "", email:((p.emailAddresses || [])[0] || {}).value || "",
      phone:((p.phoneNumbers || [])[0] || {}).value || "", org:org.name || "", role:org.title || ""}; });
}
function saveContact_(p){
  if(!p.name) throw coded_("bad_request");
  const parts = String(p.name).trim().split(/\s+/);
  const body = {names:[parts.length > 1 ? {familyName:parts[0], givenName:parts.slice(1).join(" ")} : {givenName:parts[0]}],
    emailAddresses:p.email ? [{value:p.email}] : [], phoneNumbers:p.phone ? [{value:p.phone}] : [],
    organizations:(p.org || p.role) ? [{name:p.org || "", title:p.role || ""}] : [], biographies:p.about ? [{value:p.about, contentType:"TEXT_PLAIN"}] : []};
  const r = People.People.createContact(body);
  if(p.id) withLock_(() => { const d = data_(); const x = d.cols.people[p.id]; if(x){ x.contactId = r.resourceName; x.updatedAt = now_(); save_(d); } });
  return {resourceName:r.resourceName};
}

/* ---------- Drive ---------- */
function readSheet_(id){
  if(!/^[A-Za-z0-9_-]{20,80}$/.test(id)) throw coded_("bad_request");
  const sh = SpreadsheetApp.openById(id).getSheets()[0];
  const rng = sh.getDataRange();
  if(rng.getNumRows() > 400 || rng.getNumColumns() > 30) throw coded_("too_large");
  return rng.getDisplayValues();
}
function searchDrive_(q, sheetsOnly){
  q = q.replace(/['\\]/g, " ").trim();
  if(!q || q.length > 60) return [];
  const it = DriveApp.searchFiles(`title contains '${q}' and trashed = false` + (sheetsOnly ? " and mimeType = 'application/vnd.google-apps.spreadsheet'" : ""));
  const out = [];
  while(it.hasNext() && out.length < 12){
    const f = it.next();
    if(f.getName().indexOf("folddesk-") === 0 || f.getName().indexOf("asset-") === 0) continue;
    out.push({id:f.getId(), title:f.getName(), url:f.getUrl(), mime:f.getMimeType(), modified:Utilities.formatDate(f.getLastUpdated(), "Asia/Tokyo", "yyyy-MM-dd")});
  }
  return out;
}
