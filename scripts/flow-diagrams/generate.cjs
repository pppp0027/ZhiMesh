const fs = require('fs');
const path = require('path');
const diagrams = require('./diagrams.cjs');

const OUT_DIR = path.resolve(__dirname, '../../outputs/flow-diagrams');
const VIEW_W = 1720;
const SIDE = 34;
const LANE_TOP = 96;
const ROW_H = 142;
const NODE_H = 90;

const COLORS = {
  start: { stroke: '#22d3ee', fill: '#083344', glow: '#22d3ee' },
  end: { stroke: '#22d3ee', fill: '#083344', glow: '#22d3ee' },
  manual: { stroke: '#34d399', fill: '#052e2b', glow: '#34d399' },
  process: { stroke: '#a78bfa', fill: '#221447', glow: '#a78bfa' },
  integration: { stroke: '#fbbf24', fill: '#3c2505', glow: '#fbbf24' },
  storage: { stroke: '#f59e0b', fill: '#351d08', glow: '#f59e0b' },
  decision: { stroke: '#fb7185', fill: '#3b0b1b', glow: '#fb7185' },
  note: { stroke: '#64748b', fill: '#111827', glow: '#64748b' }
};

function esc(value = '') {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}

function splitText(value, maxUnits) {
  if (!value) return [];
  const source = String(value);
  const explicit = source.split('\n');
  const result = [];
  for (const raw of explicit) {
    let line = '';
    let units = 0;
    for (const ch of raw) {
      const cost = /[\u0000-\u00ff]/.test(ch) ? 0.58 : 1;
      if (line && units + cost > maxUnits) {
        result.push(line);
        line = ch;
        units = cost;
      } else {
        line += ch;
        units += cost;
      }
    }
    if (line) result.push(line);
  }
  return result;
}

function textBlock(lines, x, y, opts = {}) {
  const { size = 12, weight = 500, fill = '#cbd5e1', anchor = 'middle', lineHeight = 17, klass = '' } = opts;
  return `<text x="${x}" y="${y}" text-anchor="${anchor}" font-size="${size}" font-weight="${weight}" fill="${fill}" class="${klass}">${lines.map((line, i) => `<tspan x="${x}" dy="${i ? lineHeight : 0}">${esc(line)}</tspan>`).join('')}</text>`;
}

function renderNode(step, geom) {
  const { x, y, w, h } = geom;
  const c = COLORS[step.type] || COLORS.process;
  const titleMax = Math.max(9, Math.floor(w / 15));
  const detailMax = Math.max(11, Math.floor(w / 12));
  let titleLines = splitText(step.title, titleMax).slice(0, 2);
  let detailLines = splitText(step.detail || '', detailMax).slice(0, 3);
  const totalLines = titleLines.length + detailLines.length;
  if (totalLines > 4) detailLines = detailLines.slice(0, Math.max(1, 4 - titleLines.length));
  const contentH = titleLines.length * 18 + detailLines.length * 15 + (detailLines.length ? 7 : 0);
  const startY = y + h / 2 - contentH / 2 + 13;
  const number = step.number ? `<circle cx="${x + 17}" cy="${y + 17}" r="11" fill="${c.stroke}" opacity=".95"/><text x="${x + 17}" y="${y + 21}" text-anchor="middle" font-size="9" font-weight="800" fill="#020617">${esc(step.number)}</text>` : '';
  let shape;
  if (step.type === 'decision') {
    const cx = x + w / 2;
    const cy = y + h / 2;
    shape = `<path d="M ${cx} ${y} L ${x + w} ${cy} L ${cx} ${y + h} L ${x} ${cy} Z" fill="${c.fill}" stroke="${c.stroke}" stroke-width="2" filter="url(#softGlow)"/>`;
  } else if (step.type === 'storage') {
    shape = `<path d="M ${x} ${y + 10} Q ${x} ${y} ${x + w / 2} ${y} Q ${x + w} ${y} ${x + w} ${y + 10} L ${x + w} ${y + h - 10} Q ${x + w} ${y + h} ${x + w / 2} ${y + h} Q ${x} ${y + h} ${x} ${y + h - 10} Z" fill="${c.fill}" stroke="${c.stroke}" stroke-width="2"/>`;
    shape += `<path d="M ${x} ${y + 10} Q ${x} ${y + 20} ${x + w / 2} ${y + 20} Q ${x + w} ${y + 20} ${x + w} ${y + 10}" fill="none" stroke="${c.stroke}" stroke-width="1" opacity=".7"/>`;
  } else {
    const dash = step.type === 'note' ? 'stroke-dasharray="6 5"' : '';
    shape = `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="13" fill="${c.fill}" stroke="${c.stroke}" stroke-width="2" ${dash} filter="url(#softGlow)"/>`;
  }
  const title = textBlock(titleLines, x + w / 2, startY, { size: 13, weight: 700, fill: '#f8fafc', lineHeight: 18 });
  const detailY = startY + titleLines.length * 18 + 4;
  const detail = detailLines.length ? textBlock(detailLines, x + w / 2, detailY, { size: 10.5, weight: 500, fill: '#aebdd0', lineHeight: 15 }) : '';
  return `<g id="node-${esc(step.id)}" data-node="${esc(step.id)}">${shape}${number}${title}${detail}</g>`;
}

function edgePath(from, to, edge, laneWidth) {
  const forward = to.y >= from.y + from.h;
  if (forward && edge.side) {
    const goRight = edge.side === 'right';
    const offset = Math.min(52, laneWidth * .2);
    const sx = goRight ? from.x + from.w : from.x;
    const sy = from.y + from.h;
    const tx = goRight ? to.x + to.w : to.x;
    const ty = to.y + to.h / 2;
    const gx = goRight ? Math.max(sx, tx) + offset : Math.min(sx, tx) - offset;
    return { d: `M ${sx} ${sy} L ${gx} ${sy} L ${gx} ${ty} L ${tx} ${ty}`, lx: gx, ly: (sy + ty) / 2 };
  }
  if (forward) {
    const sx = from.x + from.w / 2;
    const sy = from.y + from.h;
    const tx = to.x + to.w / 2;
    const ty = to.y;
    if (Math.abs(sx - tx) < 2) return { d: `M ${sx} ${sy} L ${tx} ${ty}`, lx: sx, ly: (sy + ty) / 2 };
    const mid = sy + Math.max(18, (ty - sy) / 2);
    return { d: `M ${sx} ${sy} L ${sx} ${mid} L ${tx} ${mid} L ${tx} ${ty}`, lx: (sx + tx) / 2, ly: mid };
  }
  if (Math.abs(to.y - from.y) < 5) {
    const leftToRight = to.x > from.x;
    const sx = leftToRight ? from.x + from.w : from.x;
    const tx = leftToRight ? to.x : to.x + to.w;
    const sy = from.y + from.h / 2;
    const ty = to.y + to.h / 2;
    return { d: `M ${sx} ${sy} L ${tx} ${ty}`, lx: (sx + tx) / 2, ly: sy };
  }
  const goRight = edge.side !== 'left';
  const offset = Math.min(46, laneWidth * .18);
  const sx = goRight ? from.x + from.w : from.x;
  const sy = from.y + from.h / 2;
  const tx = goRight ? to.x + to.w : to.x;
  const ty = to.y + to.h / 2;
  const gx = goRight ? Math.max(sx, tx) + offset : Math.min(sx, tx) - offset;
  return { d: `M ${sx} ${sy} L ${gx} ${sy} L ${gx} ${ty} L ${tx} ${ty}`, lx: gx, ly: (sy + ty) / 2 };
}

function renderEdge(edge, geoms, laneWidth) {
  const from = geoms.get(edge.from);
  const to = geoms.get(edge.to);
  if (!from || !to) throw new Error(`Unknown edge endpoint ${edge.from} -> ${edge.to}`);
  const p = edgePath(from, to, edge, laneWidth);
  const kind = edge.kind || 'main';
  const style = {
    main: { color: '#67e8f9', dash: '', marker: 'arrow-cyan', width: 2 },
    async: { color: '#94a3b8', dash: '8 7', marker: 'arrow-slate', width: 1.7 },
    trace: { color: '#a78bfa', dash: '2 6', marker: 'arrow-violet', width: 1.7 },
    error: { color: '#fb7185', dash: '7 5', marker: 'arrow-rose', width: 2 },
    success: { color: '#34d399', dash: '', marker: 'arrow-green', width: 2 }
  }[kind];
  let label = '';
  if (edge.label) {
    const w = Math.min(170, Math.max(34, splitText(edge.label, 20)[0].length * 11 + 16));
    label = `<g><rect x="${p.lx - w / 2}" y="${p.ly - 12}" width="${w}" height="21" rx="10" fill="#0f172a" stroke="${style.color}" stroke-width="1"/><text x="${p.lx}" y="${p.ly + 3}" text-anchor="middle" font-size="9.5" font-weight="700" fill="${style.color}">${esc(edge.label)}</text></g>`;
  }
  return `<g class="edge edge-${kind}"><path d="${p.d}" fill="none" stroke="${style.color}" stroke-width="${style.width}" stroke-dasharray="${style.dash}" stroke-linecap="round" stroke-linejoin="round" marker-end="url(#${style.marker})" opacity=".9"/>${label}</g>`;
}

function renderSvg(diagram) {
  const lanes = diagram.lanes;
  const innerW = VIEW_W - SIDE * 2;
  const laneW = innerW / lanes.length;
  const nodeW = Math.min(laneW - 30, lanes.length >= 6 ? 236 : 320);
  const maxRow = Math.max(...diagram.steps.map(s => s.row));
  const svgH = LANE_TOP + (maxRow + 1) * ROW_H + 48;
  const geoms = new Map();
  for (const step of diagram.steps) {
    const laneX = SIDE + step.lane * laneW;
    const w = step.width ? Math.min(laneW * step.width - 24, laneW * 2 - 24) : nodeW;
    const x = laneX + (laneW - w) / 2;
    const y = LANE_TOP + step.row * ROW_H + 40;
    geoms.set(step.id, { x, y, w, h: NODE_H, lane: step.lane });
  }
  const defs = `<defs>
    <filter id="softGlow" x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation="2.4" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>
    <pattern id="grid" width="24" height="24" patternUnits="userSpaceOnUse"><path d="M 24 0 L 0 0 0 24" fill="none" stroke="#1e293b" stroke-width=".55" opacity=".42"/></pattern>
    <marker id="arrow-cyan" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 Z" fill="#67e8f9"/></marker>
    <marker id="arrow-slate" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 Z" fill="#94a3b8"/></marker>
    <marker id="arrow-violet" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 Z" fill="#a78bfa"/></marker>
    <marker id="arrow-rose" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 Z" fill="#fb7185"/></marker>
    <marker id="arrow-green" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 Z" fill="#34d399"/></marker>
  </defs>`;
  const phaseBands = (diagram.phases || []).map((phase, i) => {
    const next = (diagram.phases || [])[i + 1];
    const y = LANE_TOP + phase.row * ROW_H + 2;
    const h = (next ? (next.row - phase.row) * ROW_H : (maxRow - phase.row + 1) * ROW_H) - 3;
    return `<g><rect x="${SIDE}" y="${y}" width="${innerW}" height="${h}" rx="16" fill="${phase.color || '#0f172a'}" opacity=".15" stroke="${phase.stroke || '#334155'}" stroke-width="1" stroke-dasharray="5 7"/><rect x="${SIDE + 10}" y="${y + 8}" width="${Math.max(120, phase.title.length * 17)}" height="25" rx="12" fill="#0f172a" stroke="${phase.stroke || '#475569'}"/><text x="${SIDE + 22}" y="${y + 25}" font-size="11" font-weight="800" fill="${phase.stroke || '#94a3b8'}">${esc(phase.title)}</text></g>`;
  }).join('');
  const laneShapes = lanes.map((lane, i) => {
    const x = SIDE + i * laneW;
    const center = x + laneW / 2;
    return `<g><rect x="${x + 5}" y="8" width="${laneW - 10}" height="70" rx="14" fill="#0b1220" stroke="${lane.color || '#334155'}" stroke-width="1.4"/><text x="${center}" y="35" text-anchor="middle" font-size="12" font-weight="800" fill="${lane.color || '#cbd5e1'}">${esc(lane.name)}</text><text x="${center}" y="56" text-anchor="middle" font-size="9.5" fill="#718096">${esc(lane.role || '')}</text><line x1="${x + laneW}" y1="${LANE_TOP - 8}" x2="${x + laneW}" y2="${svgH - 24}" stroke="#253248" stroke-width="1" stroke-dasharray="4 8"/></g>`;
  }).join('');
  const edges = diagram.edges.map(e => renderEdge(e, geoms, laneW)).join('');
  const nodes = diagram.steps.map(s => renderNode(s, geoms.get(s.id))).join('');
  return `<svg viewBox="0 0 ${VIEW_W} ${svgH}" role="img" aria-labelledby="svg-title svg-desc" style="min-width:${VIEW_W}px">
    <title id="svg-title">${esc(diagram.title)}</title><desc id="svg-desc">${esc(diagram.subtitle)}</desc>
    ${defs}<rect width="${VIEW_W}" height="${svgH}" rx="18" fill="#07101f"/><rect width="${VIEW_W}" height="${svgH}" rx="18" fill="url(#grid)"/>
    ${phaseBands}${laneShapes}${edges}${nodes}
  </svg>`;
}

function renderCard(card) {
  const items = (card.items || []).map(item => `<li>${esc(item)}</li>`).join('');
  return `<article class="info-card ${card.tone || ''}"><div class="card-kicker">${esc(card.kicker || 'INFO')}</div><h3>${esc(card.title)}</h3>${card.text ? `<p>${esc(card.text)}</p>` : ''}${items ? `<ul>${items}</ul>` : ''}</article>`;
}

function renderHtml(diagram) {
  const svg = renderSvg(diagram);
  const cards = diagram.cards.map(renderCard).join('');
  const tags = diagram.tags.map(tag => `<span>${esc(tag)}</span>`).join('');
  const fileBase = diagram.file.replace('.html', '');
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${esc(diagram.code)} · ${esc(diagram.title)}</title>
  <link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500;600;700;800&display=swap" rel="stylesheet">
  <script src="https://cdn.jsdelivr.net/npm/html2canvas@1.4.1/dist/html2canvas.min.js" integrity="sha384-ZZ1pncU3bQe8y31yfZdMFdSpttDoPmOZg2wguVK9almUodir1PghgT0eY7Mrty8H" crossorigin="anonymous"></script>
  <script src="https://cdn.jsdelivr.net/npm/jspdf@2.5.2/dist/jspdf.umd.min.js" integrity="sha384-en/ztfPSRkGfME4KIm05joYXynqzUgbsG5nMrj/xEFAHXkeZfO3yMK8QQ+mP7p1/" crossorigin="anonymous"></script>
  <style>
    *{box-sizing:border-box;margin:0;padding:0}html{background:#020617}body{min-height:100vh;padding:32px;color:#e2e8f0;background:radial-gradient(circle at 18% 0%,rgba(34,211,238,.08),transparent 30%),radial-gradient(circle at 82% 4%,rgba(167,139,250,.08),transparent 28%),#020617;font-family:'JetBrains Mono','Microsoft YaHei',monospace}.container{width:${VIEW_W + 48}px;max-width:${VIEW_W + 48}px;margin:0 auto;padding:24px;border:1px solid #1e293b;border-radius:24px;background:rgba(5,12,26,.94);box-shadow:0 28px 80px rgba(0,0,0,.35)}.header{padding:10px 10px 24px}.eyebrow{display:flex;align-items:center;gap:10px;color:#67e8f9;font-size:12px;font-weight:800;letter-spacing:.16em}.pulse{width:9px;height:9px;border-radius:50%;background:#22d3ee;box-shadow:0 0 16px #22d3ee}.header h1{margin-top:14px;font-size:32px;line-height:1.25;letter-spacing:-.045em;color:#f8fafc}.subtitle{max-width:1250px;margin-top:10px;color:#94a3b8;font-size:13px;line-height:1.8}.tags{display:flex;flex-wrap:wrap;gap:8px;margin-top:16px}.tags span{padding:7px 11px;border:1px solid #334155;border-radius:999px;background:#0f172a;color:#cbd5e1;font-size:10px}.legend{display:flex;flex-wrap:wrap;gap:18px;padding:14px 18px;margin:0 0 18px;border:1px solid #1e293b;border-radius:14px;background:#081120;color:#94a3b8;font-size:10px}.legend b{color:#e2e8f0}.swatch{display:inline-block;width:18px;height:3px;margin-right:7px;vertical-align:middle;border-radius:3px}.diagram-scroll{overflow-x:auto;border:1px solid #162237;border-radius:20px;background:#07101f}.diagram-scroll svg{display:block;width:100%;min-width:${VIEW_W}px;height:auto}.cards{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin-top:18px}.info-card{min-height:166px;padding:18px;border:1px solid #26364d;border-top:3px solid #a78bfa;border-radius:15px;background:linear-gradient(145deg,rgba(15,23,42,.98),rgba(8,17,32,.98))}.info-card.boundary{border-top-color:#fb7185}.info-card.source{border-top-color:#fbbf24}.info-card.data{border-top-color:#22d3ee}.card-kicker{color:#64748b;font-size:9px;font-weight:800;letter-spacing:.14em}.info-card h3{margin-top:8px;color:#f8fafc;font-size:13px}.info-card p,.info-card li{color:#aab8ca;font-size:10.5px;line-height:1.65}.info-card p{margin-top:10px}.info-card ul{margin:10px 0 0 18px}.footer{display:flex;justify-content:space-between;gap:20px;margin-top:18px;padding:14px 8px 2px;border-top:1px solid #1e293b;color:#64748b;font-size:9.5px}.toolbar{position:fixed;right:20px;bottom:20px;z-index:20;display:flex;flex-direction:column;align-items:flex-end;gap:8px}.tool-toggle,.tool-btn{border:1px solid #334155;border-radius:10px;background:#0f172a;color:#e2e8f0;font:600 11px 'JetBrains Mono';cursor:pointer;box-shadow:0 8px 25px rgba(0,0,0,.3)}.tool-toggle{padding:10px 13px}.tool-actions{display:none;flex-direction:column;gap:7px;padding:9px;border:1px solid #334155;border-radius:12px;background:#07101f}.toolbar.open .tool-actions{display:flex}.tool-btn{min-width:130px;padding:9px 12px;text-align:left}.tool-btn:hover{border-color:#22d3ee;color:#67e8f9}@media(max-width:1000px){body{padding:12px}.container{margin:0}.cards{grid-template-columns:1fr}.header h1{font-size:25px}}
  </style>
</head>
<body>
  <main class="container" id="report-container">
    <header class="header"><div class="eyebrow"><span class="pulse"></span>${esc(diagram.project)} · ${esc(diagram.code)}</div><h1>${esc(diagram.title)}</h1><p class="subtitle">${esc(diagram.subtitle)}</p><div class="tags">${tags}</div></header>
    <section class="legend"><span><i class="swatch" style="background:#22d3ee"></i><b>开始 / 完成</b></span><span><i class="swatch" style="background:#34d399"></i>用户动作</span><span><i class="swatch" style="background:#a78bfa"></i>内部处理</span><span><i class="swatch" style="background:#fbbf24"></i>API / 存储 / 外部系统</span><span><i class="swatch" style="background:#fb7185"></i>决策 / 异常</span><span><i class="swatch" style="background:repeating-linear-gradient(90deg,#94a3b8 0 7px,transparent 7px 12px)"></i>异步 / 补偿 / 回退 / 循环</span></section>
    <section class="diagram-scroll">${svg}</section>
    <section class="cards">${cards}</section>
    <footer class="footer"><span>源码基线：2026-08-17 当前工作区</span><span>Process Atlas · ${esc(diagram.code)} · 自包含 HTML + 内联 SVG</span></footer>
  </main>
  <aside class="toolbar" id="toolbar"><button class="tool-toggle" onclick="document.getElementById('toolbar').classList.toggle('open')">EXPORT</button><div class="tool-actions"><button class="tool-btn" onclick="copyAsImage(this)">Copy PNG</button><button class="tool-btn" onclick="downloadPNG(this)">Download PNG</button><button class="tool-btn" onclick="downloadPDF(this)">Download PDF</button></div></aside>
  <script>
    async function copyAsImage(btn){const orig=btn.textContent;try{const el=document.getElementById('report-container');const r=el.getBoundingClientRect();const pad=32;const canvas=await html2canvas(document.body,{backgroundColor:'#020617',scale:2,useCORS:true,ignoreElements:e=>e.classList&&e.classList.contains('toolbar'),x:r.left+window.scrollX-pad,y:r.top+window.scrollY-pad,width:r.width+pad*2,height:r.height+pad*2});const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/png'));await navigator.clipboard.write([new ClipboardItem({'image/png':blob})]);btn.textContent='Copied';}catch(e){btn.textContent='Failed';console.error(e)}setTimeout(()=>btn.textContent=orig,1600)}
    async function downloadPNG(btn){const orig=btn.textContent;btn.textContent='Working';try{const el=document.getElementById('report-container');const r=el.getBoundingClientRect();const pad=32;const canvas=await html2canvas(document.body,{backgroundColor:'#020617',scale:2,useCORS:true,ignoreElements:e=>e.classList&&e.classList.contains('toolbar'),x:r.left+window.scrollX-pad,y:r.top+window.scrollY-pad,width:r.width+pad*2,height:r.height+pad*2});const link=document.createElement('a');link.download='${esc(fileBase)}.png';link.href=canvas.toDataURL('image/png');link.click();btn.textContent='Done';}catch(e){btn.textContent='Failed';console.error(e)}setTimeout(()=>btn.textContent=orig,1600)}
    async function downloadPDF(btn){const orig=btn.textContent;btn.textContent='Working';try{const el=document.getElementById('report-container');const r=el.getBoundingClientRect();const pad=32;const canvas=await html2canvas(document.body,{backgroundColor:'#020617',scale:2,useCORS:true,ignoreElements:e=>e.classList&&e.classList.contains('toolbar'),x:r.left+window.scrollX-pad,y:r.top+window.scrollY-pad,width:r.width+pad*2,height:r.height+pad*2});const imgData=canvas.toDataURL('image/png');const{jsPDF}=window.jspdf;const orientation=canvas.width>canvas.height?'landscape':'portrait';const pdf=new jsPDF({orientation,unit:'px',format:[canvas.width,canvas.height],hotfixes:['px_scaling']});pdf.addImage(imgData,'PNG',0,0,canvas.width,canvas.height);pdf.save('${esc(fileBase)}.pdf');btn.textContent='Done';}catch(e){btn.textContent='Failed';console.error(e)}setTimeout(()=>btn.textContent=orig,1600)}
  </script>
</body></html>`;
}

function renderIndex(diagrams) {
  const rows = diagrams.map(d => `<a class="item" href="./${esc(d.file)}"><span class="code">${esc(d.code)}</span><span><strong>${esc(d.title)}</strong><small>${esc(d.subtitle)}</small></span><em>OPEN →</em></a>`).join('');
  return `<!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>项目流程图册</title><link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;600;700;800&display=swap" rel="stylesheet"><style>*{box-sizing:border-box}body{margin:0;min-height:100vh;padding:48px;background:radial-gradient(circle at 15% 0,rgba(34,211,238,.09),transparent 32%),#020617;color:#e2e8f0;font-family:'JetBrains Mono','Microsoft YaHei',monospace}main{max-width:1160px;margin:auto}header{margin-bottom:28px}label{color:#22d3ee;font-size:11px;font-weight:800;letter-spacing:.17em}h1{margin:12px 0 8px;font-size:38px;letter-spacing:-.05em}p{color:#94a3b8;line-height:1.7}.grid{display:grid;gap:10px}.item{display:grid;grid-template-columns:100px 1fr 80px;align-items:center;gap:18px;padding:18px 20px;border:1px solid #1e293b;border-radius:14px;background:#081120;color:inherit;text-decoration:none;transition:.2s}.item:hover{transform:translateY(-2px);border-color:#22d3ee;background:#0c1728}.code{color:#67e8f9;font-size:12px;font-weight:800}.item strong{display:block;font-size:14px}.item small{display:block;margin-top:6px;color:#7e8da3;font-size:10px;line-height:1.55}.item em{color:#a78bfa;font-size:10px;font-style:normal}@media(max-width:700px){body{padding:22px}.item{grid-template-columns:72px 1fr}.item em{display:none}}</style></head><body><main><header><label>PROCESS ATLAS · 2026-08-17</label><h1>项目流程图册</h1><p>ZhiMesh 6 张核心流程 + 以牌惠友 4 张业务流程。所有图采用统一视觉语义，并可独立导出 PNG / PDF。</p></header><section class="grid">${rows}</section></main></body></html>`;
}

fs.mkdirSync(OUT_DIR, { recursive: true });
for (const diagram of diagrams) {
  fs.writeFileSync(path.join(OUT_DIR, diagram.file), renderHtml(diagram), 'utf8');
}
fs.writeFileSync(path.join(OUT_DIR, 'index.html'), renderIndex(diagrams), 'utf8');
console.log(`Generated ${diagrams.length} diagrams in ${OUT_DIR}`);
