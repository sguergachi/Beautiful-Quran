// Run with node test_editor.cjs; Python's suite also checks both math implementations.
const assert = require("node:assert/strict");
const fs = require("node:fs"), vm = require("node:vm"), cp = require("node:child_process");
const fixture = JSON.parse(cp.execFileSync("python3", ["-c", "import json,spec,phone; print(json.dumps(dict(defaults=spec.DEFAULTS,props=spec.PROPS,themes=spec.THEMES,aspects=spec.ASPECTS,g=phone.geometry(1080,2400))))"], { cwd: __dirname }));
const nodes = new Map(), listeners = new Map();
const context = new Proxy({}, { get: () => () => {} });
function node() {
  return { getContext: () => context, classList: { add() {}, remove() {}, toggle() {}, contains: () => false }, pause() {}, play: () => Promise.resolve(),
    querySelector: () => node(), querySelectorAll: () => [], setAttribute() {}, append() {}, appendChild() {}, remove() {},
    _l: {}, addEventListener(n, f) { (this._l[n] = this._l[n] || []).push(f); }, removeEventListener(n, f) { this._l[n] = (this._l[n] || []).filter((g) => g !== f); },
    setPointerCapture() {}, releasePointerCapture() {}, hasPointerCapture: () => true,
    getBoundingClientRect: () => ({ left: 0, width: 1080 }), style: { setProperty() {} }, currentTime: 0, src: "", readyState: 4 };
}
const sandbox = vm.createContext({ console, performance, setTimeout: () => 1, clearTimeout() {}, setInterval: () => 1, clearInterval() {}, requestAnimationFrame: () => 1,
  addEventListener: (n, f) => listeners.set(n, f), removeEventListener: (n) => listeners.delete(n),
  document: { querySelector: (s) => { if (!nodes.has(s)) nodes.set(s, node()); return nodes.get(s); }, querySelectorAll: () => [], createElement: node,
    body: node(), documentElement: node() },
  localStorage: { setItem() {}, getItem: () => null, removeItem() {} }, confirm: () => true });
const run = (src) => vm.runInContext(src, sandbox);
run(fs.readFileSync(__dirname + "/editor/editor.js", "utf8").split("// ------------------------------------------------------------------ wiring")[0]);
sandbox.fixture = fixture;
run(`st.meta = fixture; draw = () => {}; buildTimeline = () => {}; buildPanes = () => {}; renderList = () => {}; updateActions = () => {}; histButtons = () => {}; note = () => {};
function reset(raw = {}) {
  st.raw = merge(fixture.defaults, raw); st.spec = resolve(st.raw); st.t = 0; st.geo = { W: 1080, H: 1080, g: fixture.g, frame: 'porcelain', clip: null, audio: null };
  st.plate = { w: 312, h: 312, k: 8/36, blur: 36 }; st.tl = null; st.pps = 60; st.undo = []; st.redo = []; st.coalesce = null;
  st.sel = null; st.lastTakeKey = takeKey(st.spec); st.saved = snapshot(); st.session++; st.saveChain = Promise.resolve();
}
reset();`);
if (process.argv.includes("--parity")) {
  sandbox.cases = JSON.parse(fs.readFileSync(0, "utf8"));
  const results = run(`cases.map((c) => {
    reset(c.spec); st.geo.W = c.W; st.geo.H = c.H; st.plate = c.plate;
    const S = stateAt(c.t);
    return { state: S, phone: phoneMatrix(S), bg: bgMatrix(S), source: sourceTime(c.t)[0], spans: videoTimes(c.a, c.b) };
  })`);
  process.stdout.write(JSON.stringify(results));
} else {
  (async () => {
    run(`reset(); mutate(() => st.raw.props.zoom = 2); undo();`);
    assert.equal(run("st.redo.length"), 1);
    run("mutate(() => {});");
    assert.equal(run("st.redo.length"), 1, "no-op edits preserve redo");
    run("redo();"); assert.equal(run("st.spec.props.zoom"), 2);
    run(`reset(); setProp('zoom', 2); seek(1); setProp('zoom', 3);`);
    assert.equal(run("st.undo.length"), 2, "seeking ends slider coalescing");
    run(`reset({tracks:{zoom:[{t:1,v:2},{t:3,v:4}]}}); st.t=1;`);
    assert.equal(run("snapT(1.05, [{prop:'zoom',t:1}], true)"), 1.05, "drag cannot snap to its own key or the playhead it moves");
    assert.equal(run("snapT(2.95, [{prop:'zoom',t:1}], true)"), 3, "drag snaps to another key");
    run("st.sel={type:'key',prop:'zoom',t:1}; copyKey(); seek(2); pasteKey();");
    assert.equal(run("st.raw.tracks.zoom[1].v"), 2);
    assert.equal(run("st.raw.tracks.zoom[1].t"), 2);
    run("undo();"); assert.equal(run("st.raw.tracks.zoom.length"), 2);
    // several keys move together, copy/paste keeps their spacing, deleting them all restores a static value
    run(`reset({tracks:{zoom:[{t:1,v:2},{t:3,v:4}], rotate:[{t:2,v:5}]}}); selectKeys([{prop:'zoom',t:1},{prop:'rotate',t:2}]); moveSelected(0.5);`);
    assert.deepEqual(run("JSON.stringify([st.raw.tracks.zoom.map(k=>k.t), st.raw.tracks.rotate.map(k=>k.t)])"), "[[1.5,3],[2.5]]");
    run("copyKey(); seek(5); pasteKey();");
    assert.deepEqual(run("JSON.stringify([st.raw.tracks.zoom.map(k=>k.t), st.raw.tracks.rotate.map(k=>k.t)])"), "[[1.5,3,5],[2.5,6]]");
    run("selectKeys([{prop:'rotate',t:2.5},{prop:'rotate',t:6}]); deleteSelection();");
    assert.equal(run("st.raw.tracks.rotate === undefined && st.raw.props.rotate"), 5);
    run("selectKeys([{prop:'zoom',t:1.5}]); moveSelected(1.5);"); assert.equal(run("st.raw.tracks.zoom[0].t"), 1.5, "a nudge cannot land on another key");
    assert.equal(run("parseTime('0:12.5') + parseTime('3')"), 15.5);
    run(`reset({edit:[{in:5,out:5.05},{in:8,out:9}]}); st.tl={take:4};`);
    sandbox.event = { button: 0, pointerId: 1, clientX: 0, preventDefault() {}, stopPropagation() {}, target: { classList: { contains: (x) => x === "r" } } };
    run("dragPiece(event, 0);");
    run("els.scroll._l.pointermove[0]")({ pointerId: 1, clientX: -100, altKey: true }); run("els.scroll._l.pointerup[0]")({ pointerId: 1 });
    assert.ok(run("st.raw.edit[0].out > st.raw.edit[0].in"), "trim beyond take stays positive");
    run("reset(); st.playing=true; seek(7);"); assert.equal(run("st.t0"), 7, "playback clock follows seek");
    run("reset(); st.name='first'; st.raw.title='before undo';");
    const requests = []; let release;
    sandbox.fetch = (path, opts) => new Promise((resolve) => {
      requests.push([path, JSON.parse(opts.body).title]);
      release = () => resolve({ ok: true, json: async () => ({ status: {} }) });
    });
    const first = run("save()"); await new Promise(setImmediate);
    run("st.raw.title='after undo'"); const second = run("save()"); await new Promise(setImmediate);
    assert.equal(requests.length, 1, "PUTs are serialised"); release(); await first; await new Promise(setImmediate);
    assert.equal(requests.length, 2); release(); assert.equal(await second, true);
    assert.deepEqual(requests, [["/api/spec/first", "before undo"], ["/api/spec/first", "after undo"]]);
    run("st.raw.title='last edit'"); const outgoing = run("save()"); await new Promise(setImmediate);
    run("st.name='second'; st.session++; st.saved='second snapshot'"); release(); await outgoing;
    assert.equal(run("st.saved"), "second snapshot", "stale save cannot update the new video's state");
    assert.equal(requests[2][0], "/api/spec/first", "save retains its original target");
    console.log("editor regressions passed");
  })().catch((e) => { console.error(e); process.exitCode = 1; });
}
