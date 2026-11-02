// 无需 Android SDK：node --test android/tests/native-glass.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assets = path.join(__dirname, '../app/src/main/assets');
const css = fs.readFileSync(path.join(assets, 'native-glass.css'), 'utf8');
const script = fs.readFileSync(path.join(assets, 'native-glass.js'), 'utf8')
  .replace('"__MP_GLASS_ORIGIN__"', JSON.stringify('https://example.test'))
  .replace('"__MP_GLASS_CSS__"', JSON.stringify(css));

function fixture({ origin = 'https://example.test', frame = false, delayed = false } = {}) {
  const nodes = new Map();
  const attrs = new Map();
  let callback;
  let disconnected = false;
  const root = {
    appendChild(node) { nodes.set(node.id, node); },
    setAttribute(k, v) { attrs.set(k, v); },
  };
  const document = {
    documentElement: delayed ? null : root,
    head: null,
    createElement: () => ({}),
    getElementById: id => nodes.get(id),
  };
  const window = {};
  window.top = frame ? {} : window;
  const context = vm.createContext({
    window, document, location: { origin },
    MutationObserver: class {
      constructor(fn) { callback = fn; }
      observe() {}
      disconnect() { disconnected = true; }
    },
  });
  return {
    nodes, attrs, context,
    run() { vm.runInContext(script, context); },
    attach() { document.documentElement = root; callback(); },
    get disconnected() { return disconnected; },
  };
}

test('主 frame 精确同源安装一次，路由切换和重复执行不重复插入', () => {
  const f = fixture();
  f.run();
  f.context.location.pathname = '/dashboard';
  f.run();
  assert.equal(f.nodes.size, 1);
  assert.equal(f.attrs.get('data-mp-glass'), '1');
  assert.equal(f.nodes.get('mpilot-native-glass').textContent, css);
});
test('跨源或子 frame 不注入样式', () => {
  for (const options of [{ origin: 'https://example.test.evil' }, { frame: true }]) {
    const f = fixture(options);
    f.run();
    assert.equal(f.nodes.size, 0);
    assert.equal(f.attrs.size, 0);
  }
});
test('document-start 根节点延迟时安装并断开观察器', () => {
  const f = fixture({ delayed: true });
  f.run();
  assert.equal(f.nodes.size, 0);
  f.attach();
  assert.equal(f.nodes.size, 1);
  assert.equal(f.disconnected, true);
});
test('样式不修改业务选中属性、不轮询 DOM、不软件截图', () => {
  assert.doesNotMatch(script, /setInterval|setTimeout|requestAnimationFrame|innerHTML/);
  assert.doesNotMatch(script, /setAttribute\(["'](?:aria-selected|aria-checked|class)/);
  assert.match(css, /prefers-reduced-motion/);
  const shell = fs.readFileSync(path.join(__dirname, '../app/src/main/java/org/moviepilot/android/ui/ShellScreen.kt'), 'utf8');
  assert.doesNotMatch(shell, /WebGlassBackdrop|pageScrim/);
  assert.match(shell, /textAlign = TextAlign.Start/);
});
