const $ = id => document.getElementById(id);
const set = (id, v) => { $(id).textContent = v; };
const cap = s => s ? s.charAt(0).toUpperCase() + s.slice(1) : "";
let patientFormTouched = false;
let lastPatientJson = "";
let lastState = null;

const COLORS = { Excellent: "#1e8e4e", Good: "#1f5fbf", Average: "#d98a00", Poor: "#c8102e", Offline: "#9aa3ae" };
const POINTS = { Excellent: 100, Good: 80, Average: 60, Poor: 30 };

function fmtTime(s) {
  s = Math.max(0, Math.floor(s));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
  return (h ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(r).padStart(2, "0");
}
const dash = v => (v === undefined || v === null || v === "") ? "-" : v;

async function refresh() {
  try {
    const res = await fetch("/api/state", { cache: "no-store" });
    lastState = await res.json();
    render(lastState);
  } catch (e) {
    $("conn").className = "pill off";
    $("conn").textContent = "Server unreachable";
  }
}

/* ---------- device health (ESP32) ---------- */
function deviceChecks(s) {
  const checks = [];
  // 1. data freshness
  if (s.ageMs < 0) checks.push({ name: "Data freshness", val: "-", rating: "N/A" });
  else {
    const r = s.ageMs < 1200 ? "Excellent" : s.ageMs < 2000 ? "Good" : s.ageMs < 3000 ? "Average" : "Poor";
    checks.push({ name: "Data freshness", val: (s.ageMs / 1000).toFixed(1) + " s ago", rating: r });
  }
  // 2. data stream rate (device sends 2 packets / second)
  {
    const r = s.pktRate >= 1.8 ? "Excellent" : s.pktRate >= 1.4 ? "Good" : s.pktRate >= 0.8 ? "Average" : "Poor";
    checks.push({ name: "Data stream", val: s.pktRate.toFixed(1) + " /s", rating: (s.ageMs < 0 || !s.online) ? "N/A" : r });
  }
  // 3. pressure sensor (BMP180)
  {
    const t = s.temp, hasBase = s.baselineHpa > 0;
    const valid = t > -10 && t < 60 && (!hasBase || (s.baselineHpa > 850 && s.baselineHpa < 1085));
    let r = "Poor";
    if (valid) r = t <= 38 ? "Excellent" : t <= 42 ? "Good" : t <= 48 ? "Average" : "Poor";
    checks.push({ name: "Pressure sensor", val: t.toFixed(1) + " \u00B0C", rating: (s.ageMs < 0 || !s.online) ? "N/A" : r });
  }
  // 4. stability (unexpected restarts)
  {
    const r = s.restarts === 0 ? "Excellent" : s.restarts === 1 ? "Good" : s.restarts === 2 ? "Average" : "Poor";
    checks.push({ name: "Stability", val: s.restarts + (s.restarts === 1 ? " restart" : " restarts"), rating: (s.ageMs < 0 || !s.online) ? "N/A" : r });
  }
  return checks;
}

function healthOf(s, checks) {
  if (!s.online) return { label: "Offline", score: null };
  const rated = checks.filter(c => POINTS[c.rating]);
  if (!rated.length) return { label: "Offline", score: null };
  const score = Math.round(rated.reduce((a, c) => a + POINTS[c.rating], 0) / rated.length);
  const label = score >= 90 ? "Excellent" : score >= 70 ? "Good" : score >= 50 ? "Average" : "Poor";
  return { label, score };
}

function renderHealth(s) {
  const checks = deviceChecks(s), h = healthOf(s, checks);
  const ring = $("h-ring");
  ring.style.setProperty("--p", h.score === null ? 0 : h.score);
  ring.style.setProperty("--c", COLORS[h.label]);
  set("h-score", h.score === null ? "-" : h.score);
  const lb = $("h-label");
  lb.textContent = h.label;
  lb.className = "big-badge " + (h.label === "Offline" ? "na" : h.label.toLowerCase());

  const ul = $("h-checks");
  ul.innerHTML = "";
  checks.forEach(c => {
    const li = document.createElement("li");
    const nm = document.createElement("span"); nm.className = "nm"; nm.textContent = c.name;
    const val = document.createElement("span"); val.className = "val"; val.textContent = c.val;
    const b = document.createElement("span");
    b.className = "badge " + (c.rating === "N/A" ? "na" : c.rating.toLowerCase());
    b.textContent = c.rating;
    li.append(nm, val, b); ul.appendChild(li);
  });
  return { checks, h };
}

/* ---------- alerts ---------- */
function fillAlerts(id, items) {
  const ul = $(id);
  ul.innerHTML = "";
  items.forEach(([lvl, text]) => {
    const li = document.createElement("li");
    li.className = lvl; li.textContent = text; ul.appendChild(li);
  });
}

function renderAlerts(s, health) {
  const q = s.session, clin = [], dev = [];

  if (s.online && !s.cmdEnabled) clin.push(["warn", "Therapy is paused - the valve is forced closed."]);
  if (q.weakAttempts >= 3) clin.push(["warn", q.weakAttempts + " breaths stayed below the threshold. Consider a more sensitive mode (Light)."]);
  const target = parseFloat(s.patient.duration);
  if (target > 0 && q.seconds >= target * 60) clin.push(["ok", "Prescribed session duration reached."]);
  if (!s.patient.id) clin.push(["warn", "No patient selected. Save patient details so logs are linked to a patient ID."]);
  if (!clin.length) clin.push(["ok", "No active clinical alerts."]);
  fillAlerts("alerts-clin", clin);

  if (!s.online) dev.push(["bad", "Device is offline. Check ESP32 power, WiFi and the server address in the firmware."]);
  else {
    health.checks.filter(c => c.rating === "Poor").forEach(c => dev.push(["bad", c.name + " is poor (" + c.val + ")."]));
    health.checks.filter(c => c.rating === "Average").forEach(c => dev.push(["warn", c.name + " is average (" + c.val + ")."]));
    if (s.temp > 40) dev.push(["warn", "Sensor temperature is high (" + s.temp.toFixed(1) + " \u00B0C)."]);
    if (!dev.length) dev.push(["ok", "All device checks passed. Device is healthy."]);
  }
  fillAlerts("alerts-dev", dev);
}

/* ---------- main render ---------- */
function render(s) {
  const q = s.session, p = s.patient;

  $("conn").className = "pill " + (s.online ? "on" : "off");
  $("conn").textContent = s.online ? "Device online" : "Device offline";

  // patient banner
  set("pb-name", dash(p.name) === "-" ? "Not entered" : p.name);
  set("pb-id", dash(p.id));
  set("pb-ag", (dash(p.age) === "-" ? "-" : p.age + " y") + (p.gender ? " / " + p.gender : ""));
  set("pb-wt", p.weight ? p.weight + " kg" : "-");
  set("pb-cond", dash(p.condition));
  set("pb-med", dash(p.medication));
  set("pb-dose", dash(p.dose));
  set("pb-doc", dash(p.doctor));

  // KPI tiles
  const pct = s.threshold ? (s.pressure / s.threshold) * 100 : 0;
  set("k-pressure", Math.round(s.pressure));
  set("k-pct", Math.round(pct) + "% of threshold");
  set("k-thr", s.threshold);
  set("k-mode", cap(s.mode) + " mode");
  set("k-valve", s.valve ? "OPEN" : "CLOSED");
  $("kpi-valve").classList.toggle("open", !!s.valve);
  let status = "Ready - waiting for inhalation";
  if (!s.online) status = "Device offline";
  else if (!s.cmdEnabled) status = "Therapy paused";
  else if (s.valve) status = "Inhaling - releasing medicine";
  set("k-status", status);
  set("k-bpm", q.breathsPerMin.toFixed(1));
  set("k-saved", q.medicineSavedPct.toFixed(0));
  set("k-inh", q.inhalations);
  set("k-weak", q.weakAttempts + " weak attempt" + (q.weakAttempts === 1 ? "" : "s"));

  // breath-strength meter (marker at the threshold = 66.7%)
  $("m-fill").style.width = Math.min(100, (pct / 150) * 100) + "%";
  $("m-fill").style.background = pct >= 100 ? "var(--green)" : "var(--blue)";
  set("m-txt", Math.round(pct) + "%");

  // controls
  document.querySelectorAll(".modes button").forEach(b => b.classList.toggle("active", b.dataset.mode === s.mode));
  if (document.activeElement !== $("enabled")) $("enabled").checked = s.cmdEnabled;

  // session progress
  set("pr-time", fmtTime(q.seconds));
  const target = parseFloat(p.duration);
  if (target > 0) {
    const tp = Math.min(100, (q.seconds / (target * 60)) * 100);
    $("pr-fill").style.width = tp + "%";
    set("pr-target", "of " + target + " min target");
    set("pr-note", tp >= 100 ? "Prescribed duration reached." : Math.round(tp) + "% of the prescribed session completed.");
  } else {
    $("pr-fill").style.width = "0%";
    set("pr-target", "No target set");
    set("pr-note", "Enter a session target in patient details to track progress.");
  }
  const ms = q.modeSeconds, tot = ms.light + ms.moderate + ms.heavy;
  for (const m of ["light", "moderate", "heavy"]) {
    $("mt-" + m).style.width = (tot ? (ms[m] / tot) * 100 : 0) + "%";
    set("mt-" + m + "-t", fmtTime(ms[m]));
  }

  // analytics: breath performance
  set("s-peak", Math.round(q.peak));
  set("s-avgpeak", Math.round(q.avgPeak));
  set("s-margin", s.threshold ? Math.round((q.peak / s.threshold) * 100) + "%" : "-");
  set("s-bpm", q.breathsPerMin.toFixed(1));
  set("s-avg", q.avgInhaleSeconds.toFixed(1));
  set("s-last", q.lastInhaleSeconds.toFixed(1));
  set("s-long", q.longestInhaleSeconds.toFixed(1));
  set("s-weak", q.weakAttempts);
  // analytics: medicine delivery
  set("s-time", fmtTime(q.seconds));
  set("s-inh", q.inhalations);
  set("s-open", q.valveOpenSeconds.toFixed(0));
  set("s-duty", q.valveOpenPct.toFixed(0) + "%");
  set("s-saved", q.medicineSavedPct.toFixed(0) + "%");
  set("s-int", q.inhalations ? (q.seconds / q.inhalations).toFixed(1) : "-");

  // device information (no WiFi details - technical only)
  set("d-id", s.device);
  set("d-conn", s.online ? "Connected" : "Not connected");
  set("d-temp", s.temp.toFixed(1) + " \u00B0C");
  set("d-base", s.baselineHpa > 0 ? s.baselineHpa.toFixed(1) + " hPa" : "-");
  set("d-up", fmtTime(s.uptime));
  set("d-rs", s.restarts);
  set("d-pk", s.packets);
  set("d-last", s.ageMs < 0 ? "-" : (s.ageMs < 1500 ? "just now" : (s.ageMs / 1000).toFixed(0) + " s ago"));

  const health = renderHealth(s);
  renderAlerts(s, health);
  renderLog(s.events);

  // patient form (fill from server unless the user is typing)
  const pj = JSON.stringify(p);
  if (!patientFormTouched && pj !== lastPatientJson) {
    lastPatientJson = pj;
    const f = $("patientForm");
    for (const k of Object.keys(p)) if (f.elements[k]) f.elements[k].value = p[k];
  }
  drawChart(s);
}

function renderLog(events) {
  const body = $("log-body");
  body.innerHTML = "";
  if (!events.length) {
    const tr = document.createElement("tr"), td = document.createElement("td");
    td.colSpan = 6; td.className = "empty"; td.textContent = "No inhalations recorded yet";
    tr.appendChild(td); body.appendChild(tr); return;
  }
  const total = lastState ? lastState.session.inhalations : events.length;
  events.slice(0, 12).forEach((e, i) => {
    const [start, dur, peak, mode, open] = e;
    const tr = document.createElement("tr");
    [total - i, new Date(start).toLocaleTimeString(), cap(mode), Math.round(peak), dur.toFixed(1)].forEach(v => {
      const td = document.createElement("td"); td.textContent = v; tr.appendChild(td);
    });
    const td = document.createElement("td"), tag = document.createElement("span");
    tag.className = "tag" + (open ? " live" : ""); tag.textContent = open ? "Active" : "Delivered";
    td.appendChild(tag); tr.appendChild(td); body.appendChild(tr);
  });
}

/* ---------- compact waveform ---------- */
function drawChart(s) {
  const c = $("chart"), dpr = window.devicePixelRatio || 1;
  const W = c.clientWidth, H = 140;
  if (c.width !== Math.round(W * dpr) || c.height !== H * dpr) { c.width = Math.round(W * dpr); c.height = H * dpr; }
  const g = c.getContext("2d");
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.clearRect(0, 0, W, H);
  const padL = 34, padB = 16, padT = 6, padR = 6;
  const h = s.history, n = h.length;
  const maxY = Math.max(1000, s.threshold * 1.3, ...h.map(p => p[1]));
  const X = i => padL + (n <= 1 ? 0 : i * (W - padL - padR) / (n - 1));
  const Y = v => H - padB - (v / maxY) * (H - padB - padT);

  g.font = "10px Segoe UI, system-ui, sans-serif";
  g.fillStyle = "#6a7380"; g.strokeStyle = "#e6e8ec"; g.lineWidth = 1;
  [0, 0.5, 1].forEach(f => {
    const v = Math.round(maxY * f / 100) * 100;
    g.beginPath(); g.moveTo(padL, Y(v)); g.lineTo(W - padR, Y(v)); g.stroke();
    g.fillText(v, 4, Y(v) + 3);
  });
  if (n < 2) { g.fillText("Waiting for data...", padL + 8, H / 2); return; }

  g.fillStyle = "rgba(30,142,78,0.20)";
  for (let i = 0; i < n - 1; i++) if (h[i][2] === 1) g.fillRect(X(i), padT, X(i + 1) - X(i) + 1, H - padB - padT);

  g.strokeStyle = "#c8102e"; g.lineWidth = 1; g.setLineDash([5, 3]); g.beginPath();
  g.moveTo(padL, Y(s.threshold)); g.lineTo(W - padR, Y(s.threshold)); g.stroke(); g.setLineDash([]);

  g.strokeStyle = "#1f5fbf"; g.lineWidth = 1.6; g.beginPath();
  h.forEach((p, i) => i ? g.lineTo(X(i), Y(p[1])) : g.moveTo(X(i), Y(p[1])));
  g.stroke();
}

/* ---------- actions ---------- */
async function post(url, body) {
  const r = await fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  return r.json();
}
document.querySelectorAll(".modes button").forEach(b => b.addEventListener("click", async () => {
  await post("/api/command", { mode: b.dataset.mode, enabled: $("enabled").checked });
  refresh();
}));
$("enabled").addEventListener("change", async () => {
  const active = document.querySelector(".modes button.active");
  await post("/api/command", { mode: active ? active.dataset.mode : "moderate", enabled: $("enabled").checked });
  refresh();
});
$("reset").addEventListener("click", async () => { await post("/api/session/reset", {}); refresh(); });

$("patientForm").addEventListener("input", () => { patientFormTouched = true; set("saved", ""); });
$("patientForm").addEventListener("submit", async e => {
  e.preventDefault();
  await post("/api/patient", Object.fromEntries(new FormData(e.target).entries()));
  patientFormTouched = false; lastPatientJson = "";
  set("saved", "Saved \u2713");
  refresh();
});

setInterval(() => { $("clock").textContent = new Date().toLocaleString(); }, 1000);
$("clock").textContent = new Date().toLocaleString();
window.addEventListener("resize", () => { if (lastState) drawChart(lastState); });
refresh();
setInterval(refresh, 500);
