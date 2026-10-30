/* TradeIO — graphiques SVG simples (courbe de valeur : trait dégradé s0→s3, aire en fondu). Lightweight Charts : fiche actif uniquement. */
(function (g) {
  'use strict';
  const TIO = g.TIO;
  const h = TIO.h, svg = TIO.svg;
  const charts = TIO.charts = {};
  let seq = 0;

  /**
   * Courbe de valeur. points : [{x:'2026-10-01', y:123.4}] ; les y absents sont ignorés (< 2 points ⇒ null, l'appelant affiche son état vide).
   * opts : { valueFmt, labelFmt, label, dashed:[{x,y}] (courbe secondaire pointillée) }
   */
  charts.area = function (points, opts) {
    const o = opts || {};
    const pts = (points || []).filter((p) => TIO.isNum(p.y));
    if (pts.length < 2) return null;
    const W = Math.max(280, Math.min(640, g.innerWidth - (g.innerWidth >= 900 ? 300 : 72))), H = 190, padL = 6, padR = 6, padT = 14, padB = 26;
    const ys = pts.map((p) => p.y).concat((o.dashed || []).filter((p) => TIO.isNum(p.y)).map((p) => p.y));
    let lo = Math.min(...ys), hi = Math.max(...ys);
    if (hi === lo) { hi += 1; lo -= 1; }
    const span = hi - lo; lo -= span * 0.08; hi += span * 0.08;
    const X = (i) => padL + (W - padL - padR) * (pts.length === 1 ? 0 : i / (pts.length - 1));
    const Y = (v) => padT + (H - padT - padB) * (1 - (v - lo) / (hi - lo));
    const id = 'ch' + (++seq);
    const vf = o.valueFmt || TIO.fmt.usd, lf = o.labelFmt || TIO.fmt.day;

    const wrap = h('div', { class: 'chart-wrap' });
    const el = svg('svg', { class: 'chart', viewBox: '0 0 ' + W + ' ' + H, role: 'img', tabindex: '0',
      'aria-label': (o.label || 'Courbe') + ' : de ' + vf(pts[0].y) + ' (' + lf(pts[0].x) + ') à ' + vf(pts[pts.length - 1].y) + ' (' + lf(pts[pts.length - 1].x) + ')' });
    el.appendChild(svg('defs', null,
      svg('linearGradient', { id: id + 's', gradientUnits: 'userSpaceOnUse', x1: 0, y1: H - padB, x2: 0, y2: padT },
        svg('stop', { offset: '0%', 'stop-color': 'var(--s0)' }), svg('stop', { offset: '100%', 'stop-color': 'var(--s3)' })),
      svg('linearGradient', { id: id + 'a', x1: 0, y1: 0, x2: 0, y2: 1 },
        svg('stop', { offset: '0%', 'stop-color': 'var(--s3)', 'stop-opacity': '0.30' }), svg('stop', { offset: '100%', 'stop-color': 'var(--s3)', 'stop-opacity': '0' }))));
    [lo + (hi - lo) * 0.08 / 1.16, (lo + hi) / 2, hi - (hi - lo) * 0.08 / 1.16].forEach((v) => {
      el.appendChild(svg('line', { class: 'grid-l', x1: padL, x2: W - padR, y1: Y(v), y2: Y(v) }));
    });
    const line = pts.map((p, i) => (i ? 'L' : 'M') + X(i).toFixed(1) + ' ' + Y(p.y).toFixed(1)).join(' ');
    el.appendChild(svg('path', { d: line + ' L' + X(pts.length - 1).toFixed(1) + ' ' + (H - padB) + ' L' + X(0).toFixed(1) + ' ' + (H - padB) + ' Z', fill: 'url(#' + id + 'a)' }));
    if (o.dashed && o.dashed.length > 1) {
      const dp = o.dashed.filter((p) => TIO.isNum(p.y));
      el.appendChild(svg('path', { d: dp.map((p, i) => (i ? 'L' : 'M') + (padL + (W - padL - padR) * (i / (dp.length - 1))).toFixed(1) + ' ' + Y(p.y).toFixed(1)).join(' '),
        fill: 'none', stroke: 'var(--muted)', 'stroke-width': 1.5, 'stroke-dasharray': '4 4', opacity: 0.6 }));
    }
    el.appendChild(svg('path', { d: line, fill: 'none', stroke: 'url(#' + id + 's)', 'stroke-width': 2.5, 'stroke-linejoin': 'round', 'stroke-linecap': 'round' }));
    [0, Math.floor((pts.length - 1) / 2), pts.length - 1].forEach((i, k) => {
      el.appendChild(svg('text', { class: 'axis', x: X(i), y: H - 6, 'text-anchor': k === 0 ? 'start' : k === 2 ? 'end' : 'middle' }, lf(pts[i].x)));
    });
    const cross = svg('line', { y1: padT, y2: H - padB, stroke: 'var(--muted)', 'stroke-width': 1, opacity: 0, 'stroke-dasharray': '3 3' });
    const dot = svg('circle', { r: 4.5, fill: 'var(--text)', stroke: 'var(--bg)', 'stroke-width': 2, opacity: 0 });
    el.appendChild(cross); el.appendChild(dot);
    const hit = svg('rect', { x: 0, y: 0, width: W, height: H, fill: 'transparent' });
    el.appendChild(hit);
    wrap.appendChild(el);
    const tip = TIO.ui.tooltip(wrap);

    function at(i) {
      const p = pts[i], r = el.getBoundingClientRect(), k = r.width / W;
      cross.setAttribute('x1', X(i)); cross.setAttribute('x2', X(i)); cross.setAttribute('opacity', 1);
      dot.setAttribute('cx', X(i)); dot.setAttribute('cy', Y(p.y)); dot.setAttribute('opacity', 1);
      tip.show(X(i) * k, Y(p.y) * k, [lf(p.x), vf(p.y)]);
    }
    function leave() { cross.setAttribute('opacity', 0); dot.setAttribute('opacity', 0); tip.hide(); }
    hit.addEventListener('pointermove', (e) => {
      const r = el.getBoundingClientRect();
      const f = TIO.clamp((e.clientX - r.left) / r.width * W, padL, W - padR);
      at(Math.round((f - padL) / (W - padL - padR) * (pts.length - 1)));
    });
    hit.addEventListener('pointerleave', leave);
    let kb = pts.length - 1;
    el.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowLeft') { kb = Math.max(0, kb - 1); at(kb); e.preventDefault(); }
      else if (e.key === 'ArrowRight') { kb = Math.min(pts.length - 1, kb + 1); at(kb); e.preventDefault(); }
    });
    el.addEventListener('blur', leave);
    return wrap;
  };

  /** Repère « prix moyen vs cours » : piste neutre, repère pointillé = prix moyen, point = cours. */
  charts.priceVsAvg = function (price, avg) {
    if (!TIO.isNum(price) || !TIO.isNum(avg) || avg <= 0) return null;
    const diff = (price / avg - 1) * 100;
    const lo = Math.min(price, avg) * 0.9, hi = Math.max(price, avg) * 1.1;
    const pos = (v) => TIO.clamp((v - lo) / (hi - lo), 0, 1) * 100;
    return h('div', { class: 'stack', role: 'img', 'aria-label': 'Cours ' + TIO.fmt.price(price) + ', prix moyen d\'achat ' + TIO.fmt.price(avg) },
      h('div', { class: 'rslider', style: { height: '20px' } }, h('div', { class: 'trk', style: { top: '7px' } }),
        h('div', { class: 'mark', style: { left: pos(avg) + '%', top: '1px', height: '18px' } }),
        h('div', { style: { position: 'absolute', left: pos(price) + '%', top: '3px', width: '14px', height: '14px', 'border-radius': '50%', background: 'var(--text)', transform: 'translateX(-7px)', 'box-shadow': '0 0 0 3px color-mix(in srgb, var(--accent) 40%, transparent)' } })),
      h('div', { class: 'row row--between small muted' }, h('span', null, '┆ Prix moyen ' + TIO.fmt.price(avg)), h('span', { class: TIO.sign(diff) }, (diff > 0 ? '▲ ' : diff < 0 ? '▽ ' : '') + TIO.fmt.signedPct(diff) + ' vs cours')));
  };
  /**
   * Donut de répartition. segments : [{label, value, color, group:'assets'|'stable'}] ; les parts d'un même groupe sont collées,
   * un petit espace sépare le groupe « stable » du reste. opts : { centerLabel, centerValue, valueFmt }
   */
  charts.donut = function (segments, opts) {
    const o = opts || {};
    const segs = (segments || []).filter((s) => TIO.isNum(s.value) && s.value > 0);
    const total = segs.reduce((t, s) => t + s.value, 0);
    if (!segs.length || total <= 0) return null;
    const C = 100, R = 92, r = 62;
    const order = segs.filter((s) => s.group !== 'stable').concat(segs.filter((s) => s.group === 'stable'));
    const hasGap = segs.some((s) => s.group === 'stable') && segs.some((s) => s.group !== 'stable');
    const GAP = hasGap ? 7 : 0;                       // degrés retirés à chaque frontière entre groupes
    const usable = 360 - GAP * (hasGap ? 2 : 0);
    const pt = (rad, deg) => { const a = (deg - 90) * Math.PI / 180; return [C + rad * Math.cos(a), C + rad * Math.sin(a)]; };
    const el = svg('svg', { class: 'donut', viewBox: '0 0 200 200', role: 'img', 'aria-label': o.label || 'Répartition' });
    let deg = hasGap ? GAP / 2 : 0, prevGroup = order[0].group;
    order.forEach((s) => {
      if (hasGap && s.group !== prevGroup) { deg += GAP; prevGroup = s.group; }
      const sweep = Math.min(359.99, s.value / total * usable), a0 = deg, a1 = deg + sweep;
      deg = a1;
      const [x0, y0] = pt(R, a0), [x1, y1] = pt(R, a1), [x2, y2] = pt(r, a1), [x3, y3] = pt(r, a0), big = sweep > 180 ? 1 : 0;
      el.appendChild(svg('path', { d: 'M' + x0.toFixed(2) + ' ' + y0.toFixed(2) + ' A' + R + ' ' + R + ' 0 ' + big + ' 1 ' + x1.toFixed(2) + ' ' + y1.toFixed(2)
        + ' L' + x2.toFixed(2) + ' ' + y2.toFixed(2) + ' A' + r + ' ' + r + ' 0 ' + big + ' 0 ' + x3.toFixed(2) + ' ' + y3.toFixed(2) + ' Z', fill: s.color },
        svg('title', null, s.label + ' : ' + TIO.fmt.pct(s.value / total * 100, 1))));
    });
    if (o.centerValue) {
      el.appendChild(svg('text', { class: 'd-val', x: C, y: C + 4, 'text-anchor': 'middle' }, o.centerValue));
      if (o.centerLabel) el.appendChild(svg('text', { class: 'd-lbl', x: C, y: C + 22, 'text-anchor': 'middle' }, o.centerLabel));
    }
    return el;
  };
})(window);
