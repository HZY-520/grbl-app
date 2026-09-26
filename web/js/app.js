/**
 * 应用入口：读取界面选项 → 调用处理管线 → 更新预览 / 统计 / G 代码
 */

import { buildJob } from './core/pipeline.js';
import { Preview } from './render/preview.js';

const $ = (id) => document.getElementById(id);

const state = {
  tab: 'text',
  imageEl: null,
  imageName: '',
  svgSource: '',
  svgName: '',
  job: null,
  playing: false,
  raf: null
};

let preview = null;
let genToken = 0;
let regenTimer = null;

/* ------------------------------------------------------------------ 读取选项 */

const num = (id, def) => {
  const v = parseFloat($(id).value);
  return Number.isFinite(v) ? v : def;
};
const int = (id, def) => {
  const v = parseInt($(id).value, 10);
  return Number.isFinite(v) ? v : def;
};
const bool = (id) => !!$(id).checked;
const val = (id) => $(id).value;

function readOptions() {
  return {
    // 尺寸与定位
    width: Math.max(1, num('optWidth', 100)),
    height: Math.max(1, num('optHeight', 100)),
    lockAspect: bool('optLockAspect'),
    anchor: val('optAnchor'),
    offsetX: num('optOffsetX', 0),
    offsetY: num('optOffsetY', 0),
    // 激光与速度
    laserMode: val('optLaserMode'),
    maxS: Math.max(1, int('optMaxS', 1000)),
    power: num('optPower', 80),
    minPower: num('optMinPower', 10),
    feed: Math.max(1, num('optFeed', 1000)),
    travelFeed: Math.max(1, num('optTravelFeed', 3000)),
    passes: Math.max(1, int('optPasses', 1)),
    levels: Math.min(256, Math.max(2, int('optLevels', 32))),
    // 填充
    fillSpacing: Math.max(0.02, num('optFillSpacing', 0.2)),
    fillAngle: num('optFillAngle', 0),
    fillCross: bool('optFillCross'),
    // 路径优化
    order: val('optOrder'),
    chain: num('optChain', 0.05),
    simplify: num('optSimplify', 0.05),
    merge: num('optMerge', 0.02),
    allowReverse: bool('optAllowReverse'),
    unify: bool('optUnify'),
    compact: bool('optCompact'),
    s0: bool('optS0'),
    frame: bool('optFrame'),
    home: bool('optHome'),
    // 输出
    filename: val('optFilename') || 'laser-job',
    unit: val('optUnit'),
    header: val('optHeader'),
    footer: val('optFooter'),
    // 文字
    text: val('textValue'),
    textFont: val('textFont'),
    textSize: Math.max(0.5, num('textSize', 20)),
    textMode: val('textMode'),
    textWeight: int('textWeight', 700),
    textItalic: bool('textItalic'),
    textAlign: val('textAlign'),
    textWrap: num('textWrap', 0),
    textSpacing: num('textSpacing', 0),
    textLineHeight: num('textLineHeight', 110),
    // 图片
    imgMode: val('imgMode'),
    imgDither: val('imgDither'),
    imgDitherStrength: num('imgDitherStrength', 100),
    imgSpacing: Math.max(0.02, num('imgSpacing', 0.15)),
    imgAngle: num('imgAngle', 0),
    imgSerpentine: bool('imgSerpentine'),
    imgBright: num('imgBright', 0),
    imgContrast: num('imgContrast', 0),
    imgGamma: num('imgGamma', 1),
    imgThreshold: num('imgThreshold', 128),
    imgInvert: bool('imgInvert'),
    // SVG
    svgExtract: val('svgExtract'),
    svgTolerance: Math.max(0.01, num('svgTolerance', 0.1))
  };
}

function buildInput() {
  if (state.tab === 'text') return { type: 'text', text: val('textValue') };
  if (state.tab === 'image') return { type: 'image', imageEl: state.imageEl };
  return { type: 'svg', svgText: state.svgSource };
}

/* ------------------------------------------------------------------ 生成流程 */

function setBusy(on) {
  $('busy').classList.toggle('is-on', on);
  $('btnGenerate').disabled = on;
}

function showHint(text) {
  const el = $('overlayHint');
  el.textContent = text;
  el.hidden = false;
}

function scheduleGenerate(delay = 260) {
  clearTimeout(regenTimer);
  regenTimer = setTimeout(() => generate(), delay);
}

async function generate() {
  const token = ++genToken;

  // 输入缺失时给出提示而不是报错
  if (state.tab === 'image' && !state.imageEl) {
    preview.setJob(null);
    state.job = null;
    clearStats();
    setExportEnabled(false);
    showHint('请先选择一张图片');
    return;
  }
  if (state.tab === 'svg' && !state.svgSource.trim()) {
    preview.setJob(null);
    state.job = null;
    clearStats();
    setExportEnabled(false);
    showHint('请上传或粘贴 SVG 内容');
    return;
  }

  setBusy(true);
  // 让浏览器有机会绘制 loading 状态
  await new Promise((r) => setTimeout(r, 20));
  if (token !== genToken) return;

  try {
    const opts = readOptions();
    const job = buildJob(buildInput(), opts);
    if (token !== genToken) return;
    state.job = job;
    applyJob(job, opts);
  } catch (err) {
    state.job = null;
    preview.setJob(null);
    clearStats();
    setExportEnabled(false);
    showHint(err && err.message ? err.message : '生成失败');
    toast(err && err.message ? err.message : '生成失败');
  } finally {
    if (token === genToken) setBusy(false);
  }
}

function applyJob(job, opts) {
  $('overlayHint').hidden = true;
  if (opts.lockAspect) $('optHeight').value = job.stats.height.toFixed(2);

  preview.setJob(job);
  renderStats(job.stats);
  renderGcode(job.gcode.lines);
  setExportEnabled(true);
  stopPlay();
  $('playProgress').value = '1000';
  preview.setProgress(1);
}

function setExportEnabled(on) {
  $('btnDownload').disabled = !on;
  $('btnCopy').disabled = !on;
}

function clearStats() {
  $('stats').innerHTML = '';
  $('gcodeView').textContent = '';
  $('gcodeLines').textContent = '0 行';
}

function cell(label, value) {
  const d = document.createElement('div');
  d.className = 'stat';
  const s = document.createElement('span');
  s.textContent = label;
  const b = document.createElement('b');
  b.textContent = value;
  d.append(s, b);
  return d;
}

function renderStats(s) {
  const wrap = $('stats');
  wrap.innerHTML = '';
  const mm = (v) => (v >= 1000 ? `${(v / 1000).toFixed(2)} m` : `${v.toFixed(1)} mm`);
  const rows = [
    ['路径段数', s.rawStrokes !== s.strokes ? `${s.strokes}（原 ${s.rawStrokes}）` : String(s.strokes)],
    ['雕刻尺寸', `${s.width.toFixed(1)} × ${s.height.toFixed(1)} mm`],
    ['雕刻长度', mm(s.markLength)],
    ['空移长度', mm(s.travelLength)],
    ['连续衔接', `${s.chainCount} 处`],
    ['预计用时', s.estText],
    ['G 代码', `${s.lines} 行`],
    ['文件大小', s.sizeText],
    ['功率 S', `${s.powerMinS} – ${s.powerMaxS}`]
  ];
  for (const [k, v] of rows) wrap.appendChild(cell(k, v));
}

const GCODE_PREVIEW_LINES = 400;

function renderGcode(lines) {
  const shown = lines.slice(0, GCODE_PREVIEW_LINES);
  const suffix = lines.length > GCODE_PREVIEW_LINES ? `\n… 其余 ${lines.length - GCODE_PREVIEW_LINES} 行请下载查看` : '';
  $('gcodeView').textContent = shown.join('\n') + suffix;
  $('gcodeLines').textContent = `${lines.length} 行 · ${(new Blob([$('gcodeView').textContent]).size / 1024).toFixed(1)} KB 预览`;
}

/* ------------------------------------------------------------------ 导出 */

function safeName() {
  return (val('optFilename') || 'laser-job').replace(/[\\/:*?"<>|]+/g, '_').slice(0, 60);
}

function downloadGcode() {
  if (!state.job) return;
  const blob = new Blob([state.job.gcode.text], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${safeName()}.gcode`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 3000);
  toast('开始下载 G 代码');
}

async function copyGcode() {
  if (!state.job) return;
  const text = state.job.gcode.text;
  try {
    await navigator.clipboard.writeText(text);
    toast('G 代码已复制到剪贴板');
  } catch {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try {
      document.execCommand('copy');
      toast('G 代码已复制到剪贴板');
    } catch {
      toast('复制失败，请使用下载功能');
    }
    ta.remove();
  }
}

/* ------------------------------------------------------------------ 模拟雕刻 */

function stopPlay() {
  state.playing = false;
  if (state.raf) cancelAnimationFrame(state.raf);
  state.raf = null;
  $('btnPlay').textContent = '▶';
}

function togglePlay() {
  if (state.playing) {
    stopPlay();
    return;
  }
  if (!state.job) {
    toast('请先生成 G 代码');
    return;
  }
  state.playing = true;
  $('btnPlay').textContent = '❚❚';
  const duration = 3500;
  const t0 = performance.now();
  preview.setProgress(0);
  const step = (t) => {
    if (!state.playing) return;
    const p = Math.min(1, (t - t0) / duration);
    preview.setProgress(p);
    $('playProgress').value = String(Math.round(p * 1000));
    if (p < 1) state.raf = requestAnimationFrame(step);
    else stopPlay();
  };
  state.raf = requestAnimationFrame(step);
}

/* ------------------------------------------------------------------ 上传处理 */

function loadImageFile(file) {
  if (!file) return;
  if (!file.type.startsWith('image/')) {
    toast('请选择图片文件');
    return;
  }
  const url = URL.createObjectURL(file);
  const img = new Image();
  img.onload = () => {
    state.imageEl = img;
    state.imageName = file.name;
    $('imageThumb').src = url;
    $('imageDrop').classList.add('has-file');
    $('imageMeta').textContent = `${file.name} · ${img.naturalWidth}×${img.naturalHeight} px`;
    generate();
  };
  img.onerror = () => toast('图片加载失败');
  img.src = url;
}

function setSvgSource(text, name) {
  state.svgSource = text;
  state.svgName = name || 'pasted';
  $('svgText').value = text;
  $('svgDrop').classList.add('has-file');
  const img = $('svgThumb');
  img.removeAttribute('src');
  img.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(text)}`;
  $('svgMeta').textContent = name || '已粘贴源码';
}

function loadSvgFile(file) {
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    setSvgSource(String(reader.result), file.name);
    generate();
  };
  reader.onerror = () => toast('SVG 读取失败');
  reader.readAsText(file);
}

/** 绑定「点击 / 拖拽」上传 */
function bindDrop(dropId, inputId, handler) {
  const drop = $(dropId);
  const input = $(inputId);
  drop.addEventListener('click', () => input.click());
  drop.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      input.click();
    }
  });
  input.addEventListener('change', () => {
    handler(input.files && input.files[0]);
    input.value = '';
  });
  ['dragenter', 'dragover'].forEach((ev) =>
    drop.addEventListener(ev, (e) => {
      e.preventDefault();
      drop.classList.add('is-over');
    })
  );
  ['dragleave', 'drop'].forEach((ev) =>
    drop.addEventListener(ev, (e) => {
      e.preventDefault();
      drop.classList.remove('is-over');
    })
  );
  drop.addEventListener('drop', (e) => {
    const file = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
    handler(file);
  });
}

/* ------------------------------------------------------------------ 其它 UI */

let toastTimer = null;
function toast(msg) {
  const el = $('toast');
  el.textContent = msg;
  el.classList.add('is-on');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('is-on'), 2600);
}

function switchTab(tab) {
  state.tab = tab;
  document.querySelectorAll('.tab').forEach((b) => b.classList.toggle('is-active', b.dataset.tab === tab));
  document.querySelectorAll('.pane').forEach((p) => p.classList.toggle('is-hidden', p.dataset.pane !== tab));
  generate();
}

function syncDerivedUI() {
  // 实心填充提示
  $('textFillHint').hidden = val('textMode') !== 'fill';
  // 抖动选项仅在抖动模式显示
  const ditherGroup = document.querySelector('[data-when-mode="dither"]');
  if (ditherGroup) ditherGroup.classList.toggle('is-hidden', val('imgMode') !== 'dither');
  // 功率标签
  const pct = num('optPower', 80);
  const maxS = num('optMaxS', 1000);
  $('optPowerVal').textContent = `${Math.round(pct)}%`;
  $('optPowerS').textContent = String(Math.round((maxS * pct) / 100));
  $('optMinPowerVal').textContent = `${Math.round(num('optMinPower', 10))}%`;
  // 图像调整滑块数值
  $('imgBrightVal').textContent = String(Math.round(num('imgBright', 0)));
  $('imgContrastVal').textContent = String(Math.round(num('imgContrast', 0)));
  $('imgGammaVal').textContent = num('imgGamma', 1).toFixed(1);
  $('imgThresholdVal').textContent = String(Math.round(num('imgThreshold', 128)));
  // 锁定宽高比时高度不可手改
  $('optHeight').readOnly = bool('optLockAspect');
  $('optHeight').classList.toggle('muted-input', bool('optLockAspect'));
}

function bindZoomLabel() {
  const label = $('zoomLabel');
  const update = () => {
    const base = preview.fitScale || preview.view.scale;
    label.textContent = `${Math.round((preview.view.scale / base) * 100)}%`;
  };
  preview.onChange = update;
  update();
}

/** 需要触发重新生成的控件 */
const REGEN_IDS = [
  'textValue', 'textFont', 'textSize', 'textMode', 'textWeight', 'textItalic',
  'textAlign', 'textWrap', 'textSpacing', 'textLineHeight',
  'imgMode', 'imgDither', 'imgDitherStrength', 'imgSpacing', 'imgAngle', 'imgSerpentine',
  'imgBright', 'imgContrast', 'imgGamma', 'imgThreshold', 'imgInvert',
  'svgText', 'svgExtract', 'svgTolerance',
  'optWidth', 'optHeight', 'optLockAspect', 'optAnchor', 'optOffsetX', 'optOffsetY',
  'optLaserMode', 'optMaxS', 'optPower', 'optMinPower', 'optFeed', 'optTravelFeed',
  'optPasses', 'optLevels', 'optFillSpacing', 'optFillAngle', 'optFillCross',
  'optOrder', 'optChain', 'optSimplify', 'optMerge',
  'optAllowReverse', 'optUnify', 'optCompact', 'optS0', 'optFrame', 'optHome',
  'optUnit'
];

function init() {
  preview = new Preview($('preview'), $('canvasWrap'));
  bindZoomLabel();

  // 主题
  if (localStorage.getItem('lg-theme') === 'light') document.body.classList.add('theme-light');
  $('btnTheme').addEventListener('click', () => {
    const light = document.body.classList.toggle('theme-light');
    localStorage.setItem('lg-theme', light ? 'light' : 'dark');
    preview.render();
  });

  // 帮助
  $('btnHelp').addEventListener('click', () => $('helpModal').classList.remove('is-hidden'));
  $('btnHelpClose').addEventListener('click', () => $('helpModal').classList.add('is-hidden'));
  $('helpModal').addEventListener('click', (e) => {
    if (e.target === $('helpModal')) $('helpModal').classList.add('is-hidden');
  });

  // 标签页
  document.querySelectorAll('.tab').forEach((btn) =>
    btn.addEventListener('click', () => switchTab(btn.dataset.tab))
  );

  // 上传
  bindDrop('imageDrop', 'imageFile', loadImageFile);
  bindDrop('svgDrop', 'svgFile', loadSvgFile);
  $('svgText').addEventListener('input', () => {
    if (val('svgText') !== state.svgSource) {
      setSvgSource(val('svgText'), 'pasted');
    }
    scheduleGenerate(500);
  });

  // 参数变更 → 自动重新生成
  for (const id of REGEN_IDS) {
    const el = $(id);
    if (!el) continue;
    const evt = el.tagName === 'SELECT' || el.type === 'checkbox' ? 'change' : 'input';
    el.addEventListener(evt, () => {
      syncDerivedUI();
      scheduleGenerate(el.type === 'range' ? 120 : 260);
    });
  }
  // 数值框失焦时也同步一次（避免输入中间态）
  document.querySelectorAll('input[type="number"]').forEach((el) =>
    el.addEventListener('change', () => {
      syncDerivedUI();
      scheduleGenerate(80);
    })
  );

  // 操作按钮
  $('btnGenerate').addEventListener('click', () => {
    generate();
    if (window.matchMedia('(max-width: 900px)').matches) {
      window.scrollTo({ top: 0, behavior: 'smooth' });
    }
  });
  $('btnDownload').addEventListener('click', downloadGcode);
  $('btnCopy').addEventListener('click', copyGcode);

  // 预览工具条
  $('btnZoomIn').addEventListener('click', () => preview.zoomBy(1.25));
  $('btnZoomOut').addEventListener('click', () => preview.zoomBy(1 / 1.25));
  $('btnZoomFit').addEventListener('click', () => preview.fit());
  $('tglTravel').addEventListener('change', () => preview.setFlags({ showTravel: $('tglTravel').checked }));
  $('tglPower').addEventListener('change', () => preview.setFlags({ colorByPower: $('tglPower').checked }));
  $('tglGrid').addEventListener('change', () => preview.setFlags({ showGrid: $('tglGrid').checked }));
  $('btnPlay').addEventListener('click', togglePlay);
  $('playProgress').addEventListener('input', () => {
    stopPlay();
    preview.setProgress(num('playProgress', 1000) / 1000);
  });

  syncDerivedUI();
  generate();
}

init();