/**
 * Fold Desk — school account (Google Apps Script, run inside the SCHOOL Google account)
 *
 * What this script can do (and nothing else):
 *   - read the calendars of this school account (you choose which ones in the app)
 *   - add an event to this school account's calendar when you ask the app to
 * It cannot read mail, contacts or Drive. Nothing is copied into your personal Google account:
 * the app fetches the events directly and keeps them only on the phone.
 *
 * Setup (once, in the school account):
 *   1. Run setup()  → authorize (calendar + "send mail to yourself" for the pairing link)
 *   2. Deploy > New deployment > Web app (Execute as: Me / Who has access: Anyone)
 *      If "Anyone" is not offered, your school's administrator does not allow it — see the app's guide.
 *   3. Script Properties: WEBAPP_URL = the …/exec URL of the deployment
 *   4. Run pair()   → a link is mailed to this school address. Open it on the phone where Fold Desk is connected, then delete the mail.
 *   Leaked link / lost phone?  Run rotateToken().
 */

const APP_URL = "https://carno-eng.github.io/fold-desk/";
const P = PropertiesService.getScriptProperties();
const MAX_DAYS = 120;

function setup(){
  if(!P.getProperty("TOKEN")) P.setProperty("TOKEN", newToken_());
  CalendarApp.getDefaultCalendar();
  Logger.log("OK。次に「デプロイ → 新しいデプロイ → ウェブアプリ（自分として実行 / 全員）」を行い、URL をスクリプト プロパティ WEBAPP_URL に保存してから pair() を実行してください。");
}
function pair(){
  const url = (P.getProperty("WEBAPP_URL") || ScriptApp.getService().getUrl() || "").trim();
  if(!/^https:\/\/script\.google\.com\/(a\/macros\/[^/]+\/|macros\/)s\/[A-Za-z0-9_-]+\/exec$/.test(url))
    throw new Error("ウェブアプリの URL（…/exec）をスクリプト プロパティ WEBAPP_URL に保存してから、もう一度 pair() を実行してください。今の値: " + url);
  if(!P.getProperty("TOKEN")) P.setProperty("TOKEN", newToken_());
  const payload = Utilities.base64EncodeWebSafe(JSON.stringify({u:url, t:P.getProperty("TOKEN")}), Utilities.Charset.UTF_8).replace(/=+$/, "");
  const link = APP_URL + "#school=" + payload;
  const me = Session.getEffectiveUser().getEmail();
  MailApp.sendEmail({to:me, subject:"Fold Desk 学校アカウントの接続リンク（使ったら削除）",
    body:"Fold Desk を入れたスマホでこのリンクを開くと、学校のカレンダーが表示されるようになります。\n\n" + link + "\n\n接続できたら、このメールはすぐに削除してください（ゴミ箱からも）。",
    htmlBody:"<p>Fold Desk を入れたスマホでこのボタンを開くと、学校のカレンダーが表示されるようになります。</p><p><a href=\"" + link + "\" style=\"display:inline-block;padding:12px 18px;border-radius:12px;background:#0A62F5;color:#fff;text-decoration:none;font-weight:bold\">学校のアカウントを接続</a></p><p>接続できたら、このメールはすぐに削除してください（ゴミ箱からも）。漏れたかもしれない時は <b>rotateToken()</b> を実行してください。</p>"});
  Logger.log("接続リンクを " + me + " に送りました。");
}
function rotateToken(){
  P.setProperty("TOKEN", newToken_());
  CacheService.getScriptCache().remove("fails");
  Logger.log("合言葉を変更しました。古い接続は無効です。pair() で接続し直してください。");
}
function newToken_(){
  let s = ""; for(let i = 0; i < 4; i++) s += Utilities.getUuid();
  const dig = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, s + Date.now() + Math.random());
  return Utilities.base64EncodeWebSafe(dig).replace(/=+$/, "") + Utilities.getUuid().replace(/-/g, "");
}

function doGet(){ return ContentService.createTextOutput(""); }
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
      case "ping": return out({ok:true, v:1, kind:"school"});
      case "calendars": return out({ok:true, result:calendars_()});
      case "setCalendars": return out({ok:true, result:setCalendars_(req.off || {})});
      case "events": return out({ok:true, result:events_(+req.days || 60)});
      case "add": return out({ok:true, result:add_(req.ev || {})});
      default: return out({ok:false, code:"bad_request"});
    }
  } catch(err){
    return out({ok:false, code:"server", error:String((err && err.message) || err).slice(0, 200)});
  }
}
function safeEq_(a, b){
  if(!a || !b || a.length !== b.length) return false;
  let r = 0; for(let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

const off_ = () => { try { return JSON.parse(P.getProperty("OFF") || "{}"); } catch(_){ return {}; } };
function calendars_(){
  const off = off_();
  return CalendarApp.getAllCalendars().map(c => ({id:c.getId(), name:c.getName(), color:c.getColor(), owned:c.isOwnedByMe(), on:off[c.getId()] !== true}));
}
function setCalendars_(o){
  const off = {}; Object.keys(o).forEach(k => { if(o[k] === true && k.length < 300) off[k] = true; });
  P.setProperty("OFF", JSON.stringify(off));
  return calendars_();
}
const hm_ = d => Utilities.formatDate(d, "Asia/Tokyo", "HH:mm");
const ymd_ = d => Utilities.formatDate(d, "Asia/Tokyo", "yyyy-MM-dd");
const HOLI = /(休業|休校|振替休日|振休|代休|開校記念日|創立記念日|臨時休業|学校休業日|夏季休業|冬季休業|春季休業)/;
function events_(days){
  days = Math.max(1, Math.min(MAX_DAYS, days));
  const off = off_(), now = new Date(), from = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 7), to = new Date(from.getTime() + (days + 7) * 864e5);
  const out = [];
  CalendarApp.getAllCalendars().forEach(c => {
    if(off[c.getId()] === true) return;
    const holidayCal = /holiday@group\.v\.calendar\.google\.com$/.test(c.getId());
    c.getEvents(from, to).forEach(ev => {
      if(out.length >= 600) return;
      const st = ev.getStartTime(), en = ev.getEndTime(), all = ev.isAllDayEvent(), title = ev.getTitle() || "（無題）";
      const id = Utilities.base64EncodeWebSafe(Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_1, c.getId() + "|" + ev.getId() + "|" + st.getTime())).slice(0, 20);
      const base = {id, title:title.slice(0, 120), place:(ev.getLocation() || "").slice(0, 80), calendar:c.getName().slice(0, 40), kind:(all && (holidayCal || HOLI.test(title))) ? "休み" : ""};
      if(all){
        // multi-day all-day events: one entry per day (end date is exclusive)
        for(let d = new Date(st); d < en && out.length < 600; d = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1))
          out.push({...base, id:base.id + ymd_(d).replace(/-/g, "").slice(4), date:ymd_(d), time:"", endTime:""});
      } else out.push({...base, date:ymd_(st), time:hm_(st), endTime:ymd_(en) === ymd_(st) ? hm_(en) : ""});
    });
  });
  return {at:Date.now(), events:out};
}
function add_(ev){
  const title = String(ev.title || "").trim().slice(0, 200);
  if(!title || !/^\d{4}-\d{2}-\d{2}$/.test(ev.date || "")) throw new Error("bad event");
  const cal = CalendarApp.getDefaultCalendar();
  const opt = {location:String(ev.place || "").slice(0, 200), description:String(ev.note || "").slice(0, 2000)};
  const [y, m, d] = ev.date.split("-").map(Number);
  let e;
  if(/^\d{2}:\d{2}$/.test(ev.time || "")){
    const [h, mi] = ev.time.split(":").map(Number);
    const st = new Date(y, m - 1, d, h, mi);
    let en = new Date(st.getTime() + 60 * 6e4);
    if(/^\d{2}:\d{2}$/.test(ev.endTime || "")){ const [h2, m2] = ev.endTime.split(":").map(Number); const x = new Date(y, m - 1, d, h2, m2); if(x > st) en = x; }
    e = cal.createEvent(title, st, en, opt);
    const r = +ev.remindMin; if(r > 0){ e.removeAllReminders(); e.addPopupReminder(Math.min(r, 1440)); }
  } else e = cal.createAllDayEvent(title, new Date(y, m - 1, d), opt);
  return {ok:true};
}
