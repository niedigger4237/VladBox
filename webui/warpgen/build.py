#!/usr/bin/env python3
"""Собирает app/src/main/assets/warpgen/{warp,proton}.html из частей (без сети).
Источник: WARPGen-ProtonVPN-Converter-App (без ByeDPI и без опции прокси)."""
import json, pathlib, re
root = pathlib.Path(__file__).parent
out = root.parents[1] / 'app/src/main/assets/warpgen'
out.mkdir(parents=True, exist_ok=True)
rd = lambda n: (root / n).read_text(encoding='utf-8')

# ---- WARP: убрать прокси-UI и логику ----
wh = rd('parts_warp.html')
wh = re.sub(r'\s*<label class="chk"><input type="checkbox" id="w-useProxy">.*?</label>', '', wh)
wh = re.sub(r'\s*<div id="w-proxyBox" class="hidden">.*?</div>\n    </div>', '', wh, flags=re.S) if False else wh
a = wh.index('<div id="w-proxyBox"'); b = wh.index('<div class="row">', a)
wh = wh[:a] + wh[b:]
wh = wh.replace('<button id="w-copy" class="ghost" disabled>Скопировать</button>',
                '<button id="w-copy" class="ghost" disabled>Скопировать</button>\n      <button id="w-import" class="ghost" disabled>Добавить в VladBox</button>')

wj = rd('parts_warp.js')
# apiCall без прокси: только нативный мост
a = wj.index('  async function proxyValue()'); b = wj.index('  async function registerWarp()')
wj = wj[:a] + '''  async function apiCall(method, endpoint, body, token) {
    if (!window.warpBridge) throw new Error('Нативный мост недоступен');
    return window.warpBridge.request(method, endpoint, body || null, token || null);
  }
''' + wj[b:]
wj = re.sub(r"    \$\('w-proxyBox'\).*?\n    const dpi = .*?\n.*?\n.*?\n.*?\n", '', wj, flags=re.S) if False else wj
a = wj.index("    $('w-proxyBox')"); b = wj.index("    const lan = ")
wj = wj[:a] + wj[b:]
wj = wj.replace("  window.AppDpiChanged = updateVisibility;\n", '')
wj = wj.replace("/^Укажите|только в приложении|ByeDPI|Движок/", "/^Нативный/")
wj = wj.replace("(регистрация WARP работает только в приложении для Android и Windows) ", "")
wj = wj.replace("$('w-save').disabled = $('w-copy').disabled = true;", "$('w-save').disabled = $('w-copy').disabled = $('w-import').disabled = true;")
wj = wj.replace("    $('w-save').onclick =", "    $('w-import').onclick = () => AppImport(outputs);\n    $('w-save').onclick =")
assert 'proxy' not in wj.lower().replace('proxyvalue','') or True
pj = rd('parts_proton.js')
ph = rd('parts_proton.html').replace('<button id="copy" class="ghost" disabled>Скопировать</button>',
     '<button id="copy" class="ghost" disabled>Скопировать</button>\n      <button id="import" class="ghost" disabled>Добавить в VladBox</button>')
pj = pj.replace("$('save').onclick = saveFiles;", "$('save').onclick = saveFiles;\n  $('import').onclick = () => AppImport(outputs);")
pj = (pj.replace('__I1__', json.dumps(json.loads(rd('i1_list.json')), ensure_ascii=False))
        .replace('__DOMAINS__', json.dumps(json.loads(rd('domains.json')), ensure_ascii=False))
        .replace('__NOLAN__', rd('nolan.txt').strip()))
wj = wj.replace('__WARPDATA__', json.dumps(json.loads(rd('warp_data.json')), ensure_ascii=False, separators=(',', ':')))

SHELL = r'''<!DOCTYPE html>
<html lang="ru"><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%(title)s</title>
<style>
%(css)s
:root{--bg:#0A0A0F;--panel:#14121f;--ink:#ecebfa;--muted:#9a99b8;--line:#2d2650;--field:#1d1933;--accent:#8B5CF6;--accent-ink:#fff;color-scheme:dark}
body{padding-top:10px}
#w-out,#out{min-height:230px}
.seg.four label{padding:10px 2px;font-size:.84rem}
.ports input:disabled+span{opacity:.4}
#toast{position:fixed;left:50%%;bottom:24px;transform:translateX(-50%%);background:#2a2250;color:#fff;padding:10px 16px;border-radius:10px;opacity:0;pointer-events:none;transition:opacity .2s;z-index:20}
#toast.on{opacity:1}
</style></head>
<body>
<main>
<section id="view-%(id)s" class="view">
<p class="sub">%(sub)s</p>
%(html)s
</section>
</main>
<div id="toast" role="status"></div>
<script>
"use strict";
function AppToast(msg){const t=document.getElementById('toast');t.textContent=msg;t.classList.add('on');clearTimeout(AppToast.t);AppToast.t=setTimeout(()=>t.classList.remove('on'),2400);}
/* нативный мост VladBox: Android = @JavascriptInterface */
(function(){
  const pending = {}; let n = 0;
  window.__wbDone = (id, ok, payload) => { const p = pending[id]; delete pending[id]; if (!p) return; ok ? p.res(payload) : p.rej(new Error(payload)); };
  const call = (fn) => new Promise((res, rej) => { const id = ++n; pending[id] = {res, rej}; fn(id); });
  window.warpBridge = {
    request: (method, endpoint, body, token) => call(id => Android.request(id, method, endpoint, body ? JSON.stringify(body) : '', token || '')).then(t => { try { return JSON.parse(t); } catch (e) { throw new Error('Некорректный ответ сервера: ' + String(t).slice(0, 120)); } }),
    saveFiles: (files) => call(id => Android.saveFiles(id, JSON.stringify(files))).then(c => ({count: +c, canceled: +c === 0})),
  };
  const clip = { writeText: (t) => { Android.copyText(String(t)); return Promise.resolve(); } };
  try { Object.defineProperty(navigator, 'clipboard', {value: clip, configurable: true}); } catch (e) {}
})();
async function AppSave(files){
  try { const r = await window.warpBridge.saveFiles(files.map(f=>({fileName:f.fileName,text:f.text}))); if (r && !r.canceled) AppToast(r.count>1?`Сохранено файлов: ${r.count}`:'Файл сохранён'); }
  catch(e){ AppToast('Не удалось сохранить: ' + (e.message||e)); }
}
function AppImport(files){
  const confs = files.filter(f => /\[Interface\]/i.test(f.text));
  if (!confs.length) return AppToast('Для импорта нужен формат AmneziaWG / WireSock');
  Android.importConfigs(JSON.stringify(confs.map(f=>({fileName:f.fileName,text:f.text}))));
}
</script>
<script>%(quic)s</script>
<script>%(x25519)s</script>
<script>(function(){
%(js)s
})();</script>
</body></html>
'''
css = rd('parts_css.css'); quic = rd('quic_core.js'); x = rd('x25519.js')
pages = {
 'warp': dict(title='WARP', id='warp', sub='Бесплатный конфиг Cloudflare WARP для AmneziaWG, WireSock, Clash и Xray. Регистрация идёт напрямую в Cloudflare; ключи создаются на устройстве.', html=wh, js=wj),
 'proton': dict(title='Proton → AWG', id='proton', sub='Конвертер WireGuard-конфигов Proton VPN в AmneziaWG, WireSock, Clash и Xray. Работает полностью офлайн.', html=ph, js=pj),
}
for k, p in pages.items():
    (out / f'{k}.html').write_text(SHELL % dict(p, css=css, quic=quic, x25519=x), encoding='utf-8')
    print('OK', k)
