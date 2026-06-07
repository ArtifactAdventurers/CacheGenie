"use strict";

const SVGNS = "http://www.w3.org/2000/svg";
const NODEW = 200, NODEH = 46, COLW = 270, VGAP = 22;

const state = { focusId: null, scope: "all", up: 1, down: 2 };
const positions = {};   // id -> {x, y, node}
let view = { x: 0, y: 0, w: 1000, h: 700 };

const $ = (sel) => document.querySelector(sel);
const svg = $("#graph");
const gEdges = $("#edges");
const gNodes = $("#nodes");

// ----------------------------------------------------------------- API helpers

async function api(path) {
    const res = await fetch(path);
    if (!res.ok) throw new Error(path + " -> " + res.status);
    return res.json();
}

function coord(a) {
    let s = a.gid + ":" + a.aid + ":" + a.version;
    if (a.classifier) s += ":" + a.classifier;
    return s;
}

function scopeClass(scope) {
    const s = (scope || "").toLowerCase();
    return ["compile", "runtime", "provided", "test"].includes(s) ? "scope-" + s : "";
}

function trunc(s, max) {
    return s.length > max ? s.slice(0, max - 1) + "…" : s;
}

function debounce(fn, ms) {
    let t;
    return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
}

// ---------------------------------------------------------------------- search

const searchInput = $("#search");
const results = $("#results");

const runSearch = debounce(async () => {
    const q = searchInput.value.trim();
    if (!q) { hideResults(); return; }
    try {
        const rows = await api("/api/search?limit=50&q=" + encodeURIComponent(q));
        renderResults(rows);
    } catch (e) { console.error(e); }
}, 180);

searchInput.addEventListener("input", runSearch);
searchInput.addEventListener("focus", () => { if (results.children.length) results.classList.remove("hidden"); });
document.addEventListener("click", (e) => {
    if (!e.target.closest(".search")) hideResults();
});

function hideResults() { results.classList.add("hidden"); }

function renderResults(rows) {
    results.innerHTML = "";
    if (!rows.length) {
        results.innerHTML = '<div class="row muted">No matches</div>';
    } else {
        for (const a of rows) {
            const row = document.createElement("div");
            row.className = "row";
            row.innerHTML = `<span class="ga">${a.gid}:${a.aid}</span><span class="ver">${a.version}</span>`;
            row.addEventListener("click", () => { hideResults(); focusArtifact(a.id); });
            results.appendChild(row);
        }
    }
    results.classList.remove("hidden");
}

// ----------------------------------------------------------------- controls

$("#scope").addEventListener("change", (e) => { state.scope = e.target.value; reload(); });
$("#up").addEventListener("change", (e) => { state.up = clampInt(e.target.value, 0, 6); reload(); });
$("#down").addEventListener("change", (e) => { state.down = clampInt(e.target.value, 0, 6); reload(); });

function clampInt(v, min, max) {
    let n = parseInt(v, 10);
    if (isNaN(n)) n = min;
    return Math.max(min, Math.min(max, n));
}

function reload() { if (state.focusId != null) focusArtifact(state.focusId, true); }

// ------------------------------------------------------------------- focusing

async function focusArtifact(id, keepView) {
    state.focusId = id;
    const scope = "&scope=" + encodeURIComponent(state.scope);
    try {
        const [graph, detail] = await Promise.all([
            api(`/api/graph?id=${id}&up=${state.up}&down=${state.down}${scope}`),
            api(`/api/artifact?id=${id}${scope}`)
        ]);
        $("#hint").classList.add("hidden");
        renderGraph(graph, keepView);
        renderDetail(detail);
    } catch (e) { console.error(e); }
}

// ----------------------------------------------------------------- graph view

function renderGraph(graph, keepView) {
    gEdges.innerHTML = "";
    gNodes.innerHTML = "";
    for (const k in positions) delete positions[k];

    // Group nodes by signed depth into columns.
    const byDepth = new Map();
    for (const n of graph.nodes) {
        if (!byDepth.has(n.depth)) byDepth.set(n.depth, []);
        byDepth.get(n.depth).push(n);
    }
    const depths = [...byDepth.keys()].sort((a, b) => a - b);

    depths.forEach((d, colIdx) => {
        const list = byDepth.get(d).sort((a, b) => (a.aid + a.version).localeCompare(b.aid + b.version));
        const colHeight = list.length * (NODEH + VGAP);
        list.forEach((n, i) => {
            positions[n.id] = { x: colIdx * COLW, y: i * (NODEH + VGAP) - colHeight / 2, node: n };
        });
    });

    for (const e of graph.edges) drawEdge(e);
    for (const id in positions) drawNode(positions[id], graph.focus);

    if (!keepView) fitView();
}

function drawEdge(e) {
    const a = positions[e.from], b = positions[e.to];
    if (!a || !b) return;
    const x1 = a.x + NODEW, y1 = a.y + NODEH / 2;
    const x2 = b.x, y2 = b.y + NODEH / 2;
    const mx = (x1 + x2) / 2;
    const path = document.createElementNS(SVGNS, "path");
    path.setAttribute("d", `M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`);
    path.setAttribute("class", "edge " + scopeClass(e.scope));
    path.setAttribute("marker-end", "url(#arrow)");
    gEdges.appendChild(path);
}

function drawNode(pos, focusId) {
    const n = pos.node;
    const g = document.createElementNS(SVGNS, "g");
    let cls = "node";
    if (n.id === focusId) cls += " focus";
    else if (n.depth > 0) cls += " dep";
    else cls += " dependent";
    g.setAttribute("class", cls);
    g.setAttribute("transform", `translate(${pos.x},${pos.y})`);

    const rect = document.createElementNS(SVGNS, "rect");
    rect.setAttribute("width", NODEW);
    rect.setAttribute("height", NODEH);
    rect.setAttribute("rx", 8);
    g.appendChild(rect);

    const aid = document.createElementNS(SVGNS, "text");
    aid.setAttribute("class", "aid");
    aid.setAttribute("x", 10); aid.setAttribute("y", 19);
    aid.textContent = trunc(n.aid, 26);
    g.appendChild(aid);

    const ver = document.createElementNS(SVGNS, "text");
    ver.setAttribute("class", "ver");
    ver.setAttribute("x", 10); ver.setAttribute("y", 36);
    ver.textContent = trunc(n.version + "  ·  " + n.gid, 30);
    g.appendChild(ver);

    const title = document.createElementNS(SVGNS, "title");
    title.textContent = coord(n);
    g.appendChild(title);

    g.addEventListener("click", () => focusArtifact(n.id));
    gNodes.appendChild(g);
}

// ------------------------------------------------------------- pan & zoom

function applyView() {
    svg.setAttribute("viewBox", `${view.x} ${view.y} ${view.w} ${view.h}`);
}

function fitView() {
    const ids = Object.keys(positions);
    if (!ids.length) { view = { x: -500, y: -350, w: 1000, h: 700 }; applyView(); return; }
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const id of ids) {
        const p = positions[id];
        minX = Math.min(minX, p.x); minY = Math.min(minY, p.y);
        maxX = Math.max(maxX, p.x + NODEW); maxY = Math.max(maxY, p.y + NODEH);
    }
    const pad = 80;
    minX -= pad; minY -= pad; maxX += pad; maxY += pad;
    const rect = svg.getBoundingClientRect();
    const aspect = rect.width / rect.height || 1.4;
    let w = maxX - minX, h = maxY - minY;
    if (w / h < aspect) w = h * aspect; else h = w / aspect;
    view = { x: minX, y: minY, w, h };
    applyView();
}

svg.addEventListener("wheel", (e) => {
    e.preventDefault();
    const rect = svg.getBoundingClientRect();
    const px = view.x + (e.clientX - rect.left) / rect.width * view.w;
    const py = view.y + (e.clientY - rect.top) / rect.height * view.h;
    const factor = e.deltaY > 0 ? 1.1 : 0.9;
    view.w *= factor; view.h *= factor;
    view.x = px - (e.clientX - rect.left) / rect.width * view.w;
    view.y = py - (e.clientY - rect.top) / rect.height * view.h;
    applyView();
}, { passive: false });

let pan = null;
svg.addEventListener("mousedown", (e) => {
    if (e.target.closest(".node")) return;
    pan = { x: e.clientX, y: e.clientY, vx: view.x, vy: view.y };
    svg.classList.add("panning");
});
window.addEventListener("mousemove", (e) => {
    if (!pan) return;
    const rect = svg.getBoundingClientRect();
    view.x = pan.vx - (e.clientX - pan.x) * (view.w / rect.width);
    view.y = pan.vy - (e.clientY - pan.y) * (view.h / rect.height);
    applyView();
});
window.addEventListener("mouseup", () => { pan = null; svg.classList.remove("panning"); });

// --------------------------------------------------------------- detail panel

function chip(a, badgeText, badgeClass) {
    const div = document.createElement("div");
    div.className = "chip";
    const badge = badgeText
        ? `<span class="badge ${badgeClass || ""}">${badgeText}</span>` : "";
    div.innerHTML =
        `<span class="label"><span class="ga">${a.aid}</span>` +
        `<span class="ver">${a.version} · ${a.gid}</span></span>${badge}`;
    div.title = coord(a);
    div.addEventListener("click", () => focusArtifact(a.id));
    return div;
}

function section(title, count) {
    const sec = document.createElement("div");
    sec.className = "section";
    const h = document.createElement("h3");
    h.innerHTML = `${title} <span class="count">${count}</span>`;
    sec.appendChild(h);
    return sec;
}

function renderDetail(data) {
    const a = data.artifact;
    const body = $("#detail-body");
    body.innerHTML = "";

    const head = document.createElement("div");
    head.innerHTML = `<h2>${a.aid}</h2><div class="coord">${coord(a)}</div>`;
    body.appendChild(head);

    const deps = section("Dependencies", data.dependencies.length);
    if (!data.dependencies.length) deps.innerHTML += '<div class="muted">none</div>';
    for (const d of data.dependencies) deps.appendChild(chip(d, d.scope || "·", scopeClass(d.scope)));
    body.appendChild(deps);

    const rev = section("Dependents", data.dependents.length);
    if (!data.dependents.length) rev.innerHTML += '<div class="muted">none in graph</div>';
    for (const d of data.dependents) rev.appendChild(chip(d, d.scope || "·", scopeClass(d.scope)));
    body.appendChild(rev);

    body.appendChild(buildTransitive(a.id));
}

function buildTransitive(id) {
    const sec = document.createElement("div");
    sec.className = "section";
    sec.innerHTML = `<h3>Transitive closure</h3>`;
    const controls = document.createElement("div");
    controls.className = "tcontrols";
    controls.innerHTML =
        `<select class="t-dir"><option value="forward">dependencies</option>` +
        `<option value="reverse">dependents</option></select>` +
        `<label>depth <input class="t-depth" type="number" min="1" max="25" value="6"/></label>` +
        `<button class="btn t-run">Run</button>`;
    sec.appendChild(controls);
    const out = document.createElement("div");
    out.className = "t-out";
    sec.appendChild(out);

    controls.querySelector(".t-run").addEventListener("click", async () => {
        const dir = controls.querySelector(".t-dir").value;
        const depth = clampInt(controls.querySelector(".t-depth").value, 1, 25);
        out.innerHTML = '<div class="muted">running…</div>';
        try {
            const rows = await api(
                `/api/transitive?id=${id}&dir=${dir}&depth=${depth}&scope=${encodeURIComponent(state.scope)}`);
            out.innerHTML = "";
            if (!rows.length) { out.innerHTML = '<div class="muted">none</div>'; return; }
            const head = document.createElement("div");
            head.className = "muted";
            head.style.margin = "4px 0 8px";
            head.textContent = rows.length + " artifacts";
            out.appendChild(head);
            for (const r of rows) out.appendChild(chip(r, "d" + r.depth));
        } catch (e) { out.innerHTML = '<div class="muted">error</div>'; console.error(e); }
    });
    return sec;
}

// ------------------------------------------------------------------- startup

async function loadStats() {
    try {
        const s = await api("/api/stats");
        const el = $("#stats");
        const scopeRows = (s.scopes || [])
            .map(r => `<div class="stat"><span>${r.scope}</span><span>${r.count}</span></div>`).join("");
        el.innerHTML =
            `<div class="section"><h3>Graph database</h3>` +
            `<div class="stat"><span>Artifacts</span><span class="big">${s.artifacts}</span></div>` +
            `<div class="stat"><span>Dependency links</span><span class="big">${s.dependencies}</span></div>` +
            `</div><div class="section"><h3>Links by scope</h3>${scopeRows}</div>` +
            `<div class="muted">Search above, then click any node to refocus the graph.</div>`;
    } catch (e) { console.error(e); }
}

applyView();
loadStats();
