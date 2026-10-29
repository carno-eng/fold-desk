/**
 * Fold Desk backend (Google Apps Script)
 * - Data lives only in the owner's Google Drive ("Fold Desk データ" folder).
 * - Every request must carry the secret token created by setup(). Wrong tokens are counted and lock the API for 10 minutes.
 * - The Gemini API key is kept in Script Properties and never sent to the app.
 *
 * Owner setup (once):
 *   1. Script Properties: GEMINI_API_KEY = <key from aistudio.google.com>   (optional, for AI features)
 *   2. Run setup()  → authorize
 *   3. Deploy > New deployment > Web app (Execute as: Me / Who has access: Anyone)
 *   4. Run pair()   → a pairing link is mailed to you. Open it on the phone, then delete the mail.
 *   Lost phone / leaked link?  Run rotateToken() — the old token stops working immediately.
 */

const APP_URL = "https://carno-eng.github.io/fold-desk/";           // GitHub Pages URL of the app (filled in by deploy)
const FOLDER_NAME = "Fold Desk データ";
const DATA_NAME = "folddesk-data.json";
const COLS = ["tasks","events","people","orgs","memos","logs","progress","followups","briefs","overrides"];
const CFG_KEYS = ["timetable","days","prefs","school","google"];
const ID_RE = /^[A-Za-z0-9_\-.~:@+]{1,200}$/;
const MAX_DOC = 200 * 1024;
const P = PropertiesService.getScriptProperties();

/* ===================== owner functions (run from the editor) ===================== */
function setup(){
  if(!P.getProperty("TOKEN")) P.setProperty("TOKEN", newToken_());
  folder_(); data_();
  processInbox();
  importCalendar();
  installTriggers_();
  Logger.log("OK. 次に「デプロイ → 新しいデプロイ → ウェブアプリ」を行い、そのあと pair() を実行してください。");
}
function pair(){
  const url = (P.getProperty("WEBAPP_URL") || ScriptApp.getService().getUrl() || "").trim();
  if(!/^https:\/\/script\.google\.com\/macros\/s\/[A-Za-z0-9_-]+\/exec$/.test(url))
    throw new Error("ウェブアプリの URL（…/exec）が取れませんでした。「デプロイ → デプロイを管理」でウェブアプリの URL をコピーし、「プロジェクトの設定 → スクリプト プロパティ」に WEBAPP_URL という名前で保存してから、もう一度 pair() を実行してください。今の値: " + url);
  if(APP_URL.indexOf("http") !== 0) throw new Error("APP_URL が未設定です。");
  const payload = Utilities.base64EncodeWebSafe(JSON.stringify({u:url, t:P.getProperty("TOKEN")}), Utilities.Charset.UTF_8).replace(/=+$/, "");
  const link = APP_URL + "#pair=" + payload;
  const me = Session.getEffectiveUser().getEmail();
  MailApp.sendEmail({to:me, subject:"Fold Desk 接続リンク（使ったら削除）",
    body:"スマホでこのリンクを開くと Fold Desk が接続されます。\n\n" + link +
      "\n\n接続できたら、このメールはすぐに削除してください（ゴミ箱からも）。\nリンクを知っている人は誰でもデータを読めます。漏れたかもしれない時は Apps Script で rotateToken() を実行してください。",
    htmlBody:"<p>スマホでこのボタンを開くと Fold Desk が接続されます。</p><p><a href=\"" + link + "\" style=\"display:inline-block;padding:12px 18px;border-radius:12px;background:#0A62F5;color:#fff;text-decoration:none;font-weight:bold\">Fold Desk に接続</a></p>" +
      "<p>接続できたら、このメールはすぐに削除してください（ゴミ箱からも）。<br>リンクを知っている人は誰でもデータを読めます。漏れたかもしれない時は Apps Script で <b>rotateToken()</b> を実行してください。</p>"});
  Logger.log("接続リンクを " + me + " に送りました。");
}
function rotateToken(){
  P.setProperty("TOKEN", newToken_());
  CacheService.getScriptCache().remove("fails");
  Logger.log("合言葉を変更しました。古い接続は無効です。pair() を実行して接続し直してください。");
}
function newToken_(){
  let s = "";
  for(let i = 0; i < 4; i++) s += Utilities.getUuid();
  const dig = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, s + Date.now() + Math.random());
  return Utilities.base64EncodeWebSafe(dig).replace(/=+$/, "") + Utilities.getUuid().replace(/-/g, "");
}
function installTriggers_(){
  ScriptApp.getProjectTriggers().forEach(t => ScriptApp.deleteTrigger(t));
  ScriptApp.newTrigger("tick").timeBased().everyMinutes(10).create();
  ScriptApp.newTrigger("importCalendar").timeBased().everyHours(1).create();
  ScriptApp.newTrigger("scanGmail").timeBased().everyHours(1).create();
  ScriptApp.newTrigger("backup").timeBased().atHour(3).everyDays(1).create();
}

/* ===================== HTTP ===================== */
function doGet(){ return ContentService.createTextOutput(""); }   // reveals nothing
function doPost(e){
  const out = o => ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
  const cache = CacheService.getScriptCache();
  if(+(cache.get("fails") || 0) >= 10) return out({ok:false, code:"locked"});
  let req;
  try { req = JSON.parse(e.postData.contents); } catch(_){ return out({ok:false, code:"bad_request"}); }
  if(!safeEq_(String(req.token || ""), P.getProperty("TOKEN") || "")){
    cache.put("fails", String(+(cache.get("fails") || 0) + 1), 600);
    Utilities.sleep(800);
    return out({ok:false, code:"auth"});
  }
  try {
    switch(req.op){
      case "ping": return out({ok:true, v:1});
      case "pull": return out(pull_(req));
      case "batch": return out(batch_(req.ops || []));
      case "ai": return out({ok:true, result:gemini_(String(req.prompt || ""), req.images || [])});
      case "cal": return out({ok:true, result:cal_(req.tool, req.args || {})});
      case "asset": return out(asset_(String(req.name || "")));
      case "putAsset": return out(putAsset_(String(req.name || ""), String(req.mime || ""), String(req.b64 || "")));
      case "sync": processInbox(); try { syncGoogleTasks(); } catch(_){} importCalendar(); return out(pull_({rev:0}));
      default:
        if(/^g\.[A-Za-z]+$/.test(String(req.op))) return out({ok:true, result:google_(req.op, req.args || {})});
        return out({ok:false, code:"bad_request"});
    }
  } catch(err){
    return out({ok:false, code:(err && err.code) || "server", error:String((err && err.message) || err).slice(0, 200)});
  }
}
function safeEq_(a, b){
  if(!a || !b || a.length !== b.length) return false;
  let r = 0; for(let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}
const coded_ = (code, msg) => { const e = new Error(msg || code); e.code = code; return e; };

/* ===================== storage ===================== */
function folder_(){
  const id = P.getProperty("FOLDER_ID");
  if(id){ try { return DriveApp.getFolderById(id); } catch(_){} }
  const it = DriveApp.getFoldersByName(FOLDER_NAME);
  const f = it.hasNext() ? it.next() : DriveApp.createFolder(FOLDER_NAME);
  P.setProperty("FOLDER_ID", f.getId());
  return f;
}
function dataFile_(){
  const id = P.getProperty("DATA_ID");
  if(id){ try { const f = DriveApp.getFileById(id); if(!f.isTrashed()) return f; } catch(_){} }
  const it = folder_().getFilesByName(DATA_NAME);
  const f = it.hasNext() ? it.next() : folder_().createFile(DATA_NAME, JSON.stringify(empty_()), "application/json");
  P.setProperty("DATA_ID", f.getId());
  return f;
}
const empty_ = () => ({rev:1, cols:{}, cfg:{}});
function data_(){
  const d = JSON.parse(dataFile_().getBlob().getDataAsString() || "{}");
  d.cols = d.cols || {}; d.cfg = d.cfg || {}; d.rev = d.rev || 1;
  COLS.forEach(c => d.cols[c] = d.cols[c] || {});
  return d;
}
function save_(d){ d.rev = (d.rev || 1) + 1; dataFile_().setContent(JSON.stringify(d)); P.setProperty("REV", String(d.rev)); }
function withLock_(fn){
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try { return fn(); } finally { lock.releaseLock(); }
}

function pull_(req){
  const last = +(P.getProperty("INBOX_AT") || 0);
  if(Date.now() - last > 3 * 60000) processInbox();
  const rev = +(P.getProperty("REV") || 0);
  if(req.rev && rev && +req.rev === rev) return {ok:true, changed:false, rev};
  const d = data_();
  return {ok:true, changed:true, rev:d.rev, data:{cols:d.cols, cfg:d.cfg}};
}
function batch_(ops){
  if(!Array.isArray(ops) || ops.length > 100) throw coded_("bad_request");
  return withLock_(() => {
    const d = data_();
    ops.forEach(op => {
      if(op.op === "cfg"){
        if(CFG_KEYS.indexOf(op.key) < 0) throw coded_("bad_request", "cfg key");
        checkSize_(op.value); d.cfg[op.key] = op.value; return;
      }
      if(COLS.indexOf(op.col) < 0 || !ID_RE.test(String(op.id || ""))) throw coded_("bad_request", "col/id");
      if(op.op === "put"){ if(typeof op.doc !== "object" || !op.doc) throw coded_("bad_request"); checkSize_(op.doc); d.cols[op.col][op.id] = op.doc; }
      else if(op.op === "del") delete d.cols[op.col][op.id];
      else throw coded_("bad_request", "op");
    });
    save_(d);
    return {ok:true, rev:d.rev};
  });
}
function checkSize_(v){ if(JSON.stringify(v).length > MAX_DOC) throw coded_("too_large"); }

function tick(){ processInbox(); try { syncGoogleTasks(); } catch(e){ console.error("tasks: " + e); } }
function backup(){
  const f = folder_();
  const it = f.getFoldersByName("backups");
  const b = it.hasNext() ? it.next() : f.createFolder("backups");
  const stamp = Utilities.formatDate(new Date(), "Asia/Tokyo", "yyyyMMdd");
  dataFile_().makeCopy("backup-" + stamp + ".json", b);
  const files = []; const fi = b.getFiles(); while(fi.hasNext()) files.push(fi.next());
  files.sort((x, y) => y.getDateCreated() - x.getDateCreated()).slice(14).forEach(x => x.setTrashed(true));
}

/* ===================== inbox (written by Claude's morning job, or the one-time migration) ===================== */
function processInbox(){
  P.setProperty("INBOX_AT", String(Date.now()));
  const files = [];
  const it = folder_().getFiles();
  while(it.hasNext()){ const f = it.next(); if(f.getName().indexOf("inbox-") === 0) files.push(f); }
  if(!files.length) return 0;
  files.sort((a, b) => a.getDateCreated() - b.getDateCreated());
  return withLock_(() => {
    const d = data_(); let n = 0;
    files.forEach(f => {
      try {
        const j = JSON.parse(f.getBlob().getDataAsString());
        if(j.seed) n += seed_(d, j.seed, !!j.force);
        (j.items || []).forEach(it2 => { if(applyItem_(d, it2)) n++; });
      } catch(err){ console.error("inbox " + f.getName() + ": " + err); }
      f.setTrashed(true);
    });
    if(n) save_(d);
    return n;
  });
}
function seed_(d, s, force){
  const has = COLS.some(c => Object.keys(d.cols[c]).length);
  if(has && !force) return 0;
  COLS.forEach(c => d.cols[c] = (s.cols && s.cols[c]) || {});
  Object.keys(d.cols.events).forEach(k => { if(k.indexOf("gc-") === 0) delete d.cols.events[k]; });   // re-imported by importCalendar()
  d.cfg = s.cfg || {};
  return 1;
}
const now_ = () => new Date().toISOString();
function applyItem_(d, it){
  const col = it.col, mode = it.mode || "upsert", doc = it.doc || {};
  if(COLS.indexOf(col) < 0) return false;
  const C = d.cols[col];
  if(doc.orgName){ doc.orgId = orgId_(d, doc.orgName); delete doc.orgName; }
  if(doc.personEmail){ const p = personByEmail_(d, doc.personEmail); if(p) doc.personId = p; delete doc.personEmail; }
  if(mode === "person"){
    const pid = personByEmail_(d, doc.email);
    if(pid){ const p = C[pid]; if(doc.lastContact && (!p.lastContact || doc.lastContact > p.lastContact)){ p.lastContact = doc.lastContact; p.updatedAt = now_(); return true; } return false; }
    const id = it.id && ID_RE.test(it.id) ? it.id : "p-" + hash_(doc.email || doc.name || now_());
    C[id] = Object.assign({ties:[]}, doc, {updatedAt:now_()}); return true;
  }
  const id = String(it.id || "");
  if(!ID_RE.test(id)) return false;
  checkSize_(doc);
  const cur = C[id];
  if(mode === "set"){ C[id] = Object.assign({}, doc, {updatedAt:now_()}); return true; }
  if(mode === "add"){ if(cur) return false; C[id] = Object.assign({}, doc, {updatedAt:now_()}); return true; }
  if(mode === "merge"){ if(!cur || cur.status === "done") return false; Object.assign(cur, doc, {updatedAt:now_()}); return true; }
  // upsert: never resurrect or overwrite something the owner finished
  if(cur){ if(cur.status === "done") return false; Object.keys(doc).forEach(k => { if(cur[k] === undefined || cur[k] === "") cur[k] = doc[k]; }); return false; }
  C[id] = Object.assign({}, doc, {updatedAt:now_()}); return true;
}
function personByEmail_(d, email){
  if(!email) return null; email = String(email).toLowerCase();
  const P2 = d.cols.people;
  return Object.keys(P2).find(k => String(P2[k].email || "").toLowerCase() === email) || null;
}
function orgId_(d, name){
  const O = d.cols.orgs;
  const hit = Object.keys(O).find(k => O[k].name === name);
  if(hit) return hit;
  const id = "org-" + hash_(name);
  O[id] = {name, kind:/学校|学園|小学|中学|高校|大学|学院/.test(name) ? "学校" : /株式会社|合同会社|有限会社|Inc|Co\./i.test(name) ? "企業" : "", updatedAt:now_()};
  return id;
}
function hash_(s){
  return Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_1, String(s)).map(b => ((b + 256) % 256).toString(16).padStart(2, "0")).join("").slice(0, 12);
}

/* ===================== Google Calendar ===================== */
function importCalendar(){
  const tz = "Asia/Tokyo";
  const from = new Date(); from.setHours(0, 0, 0, 0);
  const to = new Date(from.getTime() + 31 * 864e5);
  const fmt = (x, f) => Utilities.formatDate(x, tz, f);
  const seen = {}, docs = {}, off = (gcfg_().calendars || {});
  CalendarApp.getAllCalendars().forEach(cal => {
    const cid = cal.getId();
    if(/#holiday@|holiday@group\.v\.calendar/.test(cid) || off[cid] === false) return;
    const account = /family/.test(cid) ? "家族" : (/@gmail\.com$/.test(cid) || cal.isOwnedByMe()) ? "個人" : "学校";
    cal.getEvents(from, to).forEach(ev => {
      const desc = ev.getDescription() || "";
      if(desc.indexOf("[FoldDesk]") >= 0) return;
      const st = ev.getStartTime(), en = ev.getEndTime();
      const id = "gc-" + hash_(ev.getId()) + "-" + fmt(st, "yyyyMMdd");
      if(seen[id]) return; seen[id] = 1;
      const allDay = ev.isAllDayEvent();
      docs[id] = {title:ev.getTitle() || "（無題）", date:fmt(st, "yyyy-MM-dd"), time:allDay ? "" : fmt(st, "HH:mm"), endTime:allDay ? "" : fmt(en, "HH:mm"),
        place:ev.getLocation() || "", note:desc.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim().slice(0, 300), source:"gcal", account, calId:ev.getId(), calendar:cal.getName()};
    });
  });
  withLock_(() => {
    const d = data_(), E = d.cols.events; let changed = false;
    const today = fmt(from, "yyyy-MM-dd"), last = fmt(to, "yyyy-MM-dd");
    Object.keys(docs).forEach(id => {
      const cur = E[id], nd = docs[id];
      if(!cur){ E[id] = Object.assign(nd, {kind:"", updatedAt:now_()}); changed = true; return; }
      ["title","date","time","endTime","place","calId","calendar","account"].forEach(k => { if(cur[k] !== nd[k]){ cur[k] = nd[k]; changed = true; } });
    });
    Object.keys(E).forEach(id => {   // removed on Google Calendar → remove here (only inside the window we just looked at)
      const e = E[id];
      if(id.indexOf("gc-") === 0 && e.source === "gcal" && e.date >= today && e.date <= last && !docs[id]){ delete E[id]; changed = true; }
    });
    if(changed) save_(d);
  });
  P.setProperty("G_CAL_AT", now_());
}
/* reminders the app asks for: create / update / delete events on the default calendar */
function cal_(tool, a){
  const reminders = ev => { if(!a.overrideReminders) return; ev.removeAllReminders(); a.overrideReminders.forEach(r => ev.addPopupReminder(Math.max(0, Math.min(40320, +r.minutes || 0)))); };
  if(tool === "create_event"){
    const ev = CalendarApp.getDefaultCalendar().createEvent(String(a.summary || "Fold Desk"), new Date(a.startTime), new Date(a.endTime), {description:String(a.description || ""), location:String(a.location || "")});
    reminders(ev);
    return {id:ev.getId()};
  }
  const ev = a.eventId ? CalendarApp.getEventById(String(a.eventId)) : null;
  if(!ev) throw coded_("not_found");
  if(tool === "update_event"){
    if(a.summary) ev.setTitle(String(a.summary));
    if(a.startTime && a.endTime) ev.setTime(new Date(a.startTime), new Date(a.endTime));
    if(a.description !== undefined) ev.setDescription(String(a.description));
    if(a.location !== undefined) ev.setLocation(String(a.location));
    reminders(ev);
    return {id:ev.getId()};
  }
  if(tool === "delete_event"){ ev.deleteEvent(); return {id:a.eventId}; }
  throw coded_("bad_request");
}

/* ===================== AI (Gemini) ===================== */
function gemini_(prompt, images){
  const key = P.getProperty("GEMINI_API_KEY");
  if(!key) throw coded_("no_key");
  if(!prompt || prompt.length > 20000) throw coded_("bad_request");
  const model = P.getProperty("GEMINI_MODEL") || "gemini-flash-latest";
  const parts = [{text:prompt}];
  (images || []).slice(0, 2).forEach(b64 => { if(typeof b64 === "string" && b64.length < 4e6) parts.push({inline_data:{mime_type:"image/jpeg", data:b64}}); });
  const res = UrlFetchApp.fetch("https://generativelanguage.googleapis.com/v1beta/models/" + encodeURIComponent(model) + ":generateContent", {
    method:"post", contentType:"application/json", muteHttpExceptions:true, headers:{"x-goog-api-key":key},
    payload:JSON.stringify({contents:[{role:"user", parts}], generationConfig:{responseMimeType:"application/json", temperature:0.2}})
  });
  const code = res.getResponseCode(), text = res.getContentText();
  if(code === 429) throw coded_("rate_limited");
  if(code >= 400) throw coded_("ai_error", text.slice(0, 160));
  const j = JSON.parse(text);
  const out = ((((j.candidates || [])[0] || {}).content || {}).parts || []).map(p => p.text || "").join("");
  return JSON.parse(out.replace(/^```(?:json)?\s*|\s*```$/g, ""));
}

/* ===================== private assets (kept in Drive, not on GitHub) ===================== */
function putAsset_(name, mime, b64){
  if(!/^[a-z0-9-]{1,40}$/.test(name) || !/^image\/(webp|png)$/.test(mime) || !b64 || b64.length > 4e6) throw coded_("bad_request");
  const f = folder_(), it = f.getFiles();
  while(it.hasNext()){ const x = it.next(); if(x.getName().indexOf("asset-" + name + ".") === 0) x.setTrashed(true); }
  f.createFile(Utilities.newBlob(Utilities.base64Decode(b64), mime, "asset-" + name + "." + mime.split("/")[1]));
  return {ok:true};
}
function asset_(name){
  if(!/^[a-z0-9-]{1,40}$/.test(name)) throw coded_("bad_request");
  const it = folder_().getFiles();
  while(it.hasNext()){
    const f = it.next();
    if(f.getName().indexOf("asset-" + name + ".") === 0){ const b = f.getBlob(); return {ok:true, mime:b.getContentType(), b64:Utilities.base64Encode(b.getBytes())}; }
  }
  return {ok:false, code:"not_found"};
}
