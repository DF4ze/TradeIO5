/* Espace Expert : graphique de simulation (Lightweight Charts v4), dérivé du gabarit de rejeu Java (tools/rainbow-replay/replay-template.html).
   Alimenté par un ChartDto (d, trend, active, cols, wallet, sets) : prix + bornes ATR / ATH, marqueurs, phases UP / DOWN, zones inactives grisées,
   puis panneaux taille du bag, PnL, exposition (graphiques empilés, échelles de temps synchronisées). Couleurs lues dans la charte (variables CSS). */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, F = TIO.fmt;
  const LW = g.LightweightCharts;
  const ZONES = ['Extrême bas', 'X2', 'X1', 'X0,5', "Pas d'achat", 'Extrême haut'];
  const TRENDS = ['—', 'UP', 'DOWN'];
  const STATES = ['—', 'UP', 'DOWN', 'RANGE'];
  const DEFAULTS = { bands: true, markers: true, phases: true, fixed: true, ath: true, panels: { bag: true, pnl: true, expo: true, score: true, sub: true, raw: true, sma: true }, ols: true };

  const SIDE_MIN = 72; // largeur minimale commune des échelles de prix (px)
  const css = (n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
  function rgba(hex, a) {
    const m = /^#([0-9a-f]{6})$/i.exec(hex.trim());
    if (!m) return hex;
    const n = parseInt(m[1], 16);
    return 'rgba(' + (n >> 16) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + a + ')';
  }
  function loadOpts(kind) {
    try {
      const raw = g.localStorage.getItem('tio.expert.chart.v2.' + kind);
      if (raw) { const o = JSON.parse(raw); return Object.assign({}, DEFAULTS, o, { panels: Object.assign({}, DEFAULTS.panels, o.panels) }); }
    } catch (e) { /* stockage indisponible : valeurs par défaut */ }
    return Object.assign({}, DEFAULTS, { panels: Object.assign({}, DEFAULTS.panels) });
  }
  function saveOpts(kind, o) { try { g.localStorage.setItem('tio.expert.chart.v2.' + kind, JSON.stringify(o)); } catch (e) { /* ignoré */ } }

  /** Fond : zones inactives grisées (hors régime) puis phases UP / DOWN en teinte légère. */
  class ShadePrimitive {
    constructor(chart, days, trend, active, colors) {
      this.chart = chart; this.days = days; this.trend = trend; this.active = active; this.colors = colors;
      this.opts = { inactive: true, phases: true };
      const self = this;
      this.view = { zOrder: () => 'bottom', renderer: () => ({ draw: (target) => self.draw(target) }) };
    }
    attached() {}
    detached() {}
    updateAllViews() {}
    paneViews() { return [this.view]; }
    draw(target) {
      const ts = this.chart.timeScale();
      const n = this.days.length;
      if (!n) return;
      const key = (i) => (!this.active[i] ? 'off' : this.trend[i] === 1 ? 'up' : this.trend[i] === 2 ? 'down' : 'none');
      target.useMediaCoordinateSpace(({ context: ctx, mediaSize }) => {
        let start = 0;
        for (let i = 1; i <= n; i++) {
          if (i < n && key(i) === key(start)) continue;
          const k = key(start);
          const fill = k === 'off' ? (this.opts.inactive ? this.colors.off : null) : k === 'up' ? (this.opts.phases ? this.colors.up : null) : k === 'down' ? (this.opts.phases ? this.colors.down : null) : null;
          if (fill) {
            const x0 = ts.timeToCoordinate(this.days[start]);
            const x1 = i < n ? ts.timeToCoordinate(this.days[i]) : ts.timeToCoordinate(this.days[n - 1]);
            if (x0 !== null && x1 !== null) {
              ctx.fillStyle = fill;
              ctx.fillRect(Math.min(x0, x1), 0, Math.max(1, Math.abs(x1 - x0) + (i === n ? 4 : 0)), mediaSize.height);
            }
          }
          start = i;
        }
      });
    }
  }

  /** Droites OLS de la bougie survolée, dessinées sur le canvas (aucun setData : pas de réentrance ni d'effet sur l'échelle de prix). */
  class OlsPrimitive {
    constructor(chart, series, getState) {
      this.chart = chart; this.series = series; this.getState = getState; this.requestUpdate = null;
      const self = this;
      this.view = { zOrder: () => 'top', renderer: () => ({ draw: (target) => self.draw(target) }) };
    }
    attached(param) { this.requestUpdate = param.requestUpdate; }
    detached() { this.requestUpdate = null; }
    updateAllViews() {}
    paneViews() { return [this.view]; }
    draw(target) {
      const st = this.getState();
      if (!st || !st.lines.length) return;
      const ts = this.chart.timeScale();
      target.useMediaCoordinateSpace(({ context: ctx }) => {
        ctx.lineWidth = 2; ctx.lineCap = 'round';
        st.lines.forEach((l) => {
          const x0 = ts.timeToCoordinate(l.t0), x1 = ts.timeToCoordinate(l.t1), y0 = this.series.priceToCoordinate(l.v0), y1 = this.series.priceToCoordinate(l.v1);
          if (x0 === null || x1 === null || y0 === null || y1 === null) return;
          ctx.strokeStyle = l.color; ctx.beginPath(); ctx.moveTo(x0, y0); ctx.lineTo(x1, y1); ctx.stroke();
        });
      });
    }
  }


  /** Outils de lecture sur le graphique de prix : mesure (Shift+clic) et totaux achats / ventes d'une zone (Ctrl+glisser). Dessin canvas pur. */
  class MeasurePrimitive {
    constructor(chart, series, getState) {
      this.chart = chart; this.series = series; this.getState = getState; this.requestUpdate = null;
      const self = this;
      this.view = { zOrder: () => 'top', renderer: () => ({ draw: (target) => self.draw(target) }) };
    }
    attached(param) { this.requestUpdate = param.requestUpdate; }
    detached() { this.requestUpdate = null; }
    updateAllViews() {}
    paneViews() { return [this.view]; }
    draw(target) {
      const st = this.getState();
      if (!st || (!st.measure && !st.zone)) return;
      const ts = this.chart.timeScale();
      target.useMediaCoordinateSpace(({ context: ctx, mediaSize }) => {
        const label = (lines, x, y, color) => {
          ctx.font = '600 11px Manrope, system-ui, sans-serif';
          const w = Math.max(...lines.map((l) => ctx.measureText(l).width)) + 16, hgt = lines.length * 16 + 10;
          const bx = Math.max(4, Math.min(mediaSize.width - w - 4, x)), by = Math.max(4, Math.min(mediaSize.height - hgt - 4, y));
          ctx.fillStyle = 'rgba(15,18,28,0.92)'; ctx.strokeStyle = color; ctx.lineWidth = 1;
          ctx.beginPath(); ctx.roundRect(bx, by, w, hgt, 6); ctx.fill(); ctx.stroke();
          ctx.fillStyle = '#e8ecf6'; ctx.textBaseline = 'middle';
          lines.forEach((l, k) => ctx.fillText(l, bx + 8, by + 5 + 8 + k * 16));
        };
        const z = st.zone;
        if (z) {
          const xa = ts.timeToCoordinate(st.days[z.i0]), xb = ts.timeToCoordinate(st.days[z.i1]);
          if (xa !== null && xb !== null) {
            const x0 = Math.min(xa, xb), w = Math.max(2, Math.abs(xb - xa));
            ctx.fillStyle = st.colors.zone; ctx.fillRect(x0, 0, w, mediaSize.height);
            ctx.strokeStyle = st.colors.zoneLine; ctx.lineWidth = 1; ctx.setLineDash([4, 3]);
            ctx.strokeRect(x0 + 0.5, 0.5, w, mediaSize.height - 1); ctx.setLineDash([]);
            label(z.lines, x0 + w + 8, 8, st.colors.zoneLine);
          }
        }
        const m = st.measure;
        if (m) {
          const xa = ts.timeToCoordinate(st.days[m.i0]), xb = ts.timeToCoordinate(st.days[m.i1]), ya = this.series.priceToCoordinate(m.p0), yb = this.series.priceToCoordinate(m.p1);
          if (xa !== null && xb !== null && ya !== null && yb !== null) {
            const col = m.p1 >= m.p0 ? st.colors.up : st.colors.down;
            ctx.fillStyle = col.fill; ctx.fillRect(Math.min(xa, xb), Math.min(ya, yb), Math.abs(xb - xa), Math.abs(yb - ya));
            ctx.strokeStyle = col.line; ctx.lineWidth = 1; ctx.strokeRect(Math.min(xa, xb) + 0.5, Math.min(ya, yb) + 0.5, Math.abs(xb - xa), Math.abs(yb - ya));
            ctx.beginPath(); ctx.moveTo(xa, ya); ctx.lineTo(xb, yb); ctx.stroke();
            label(m.lines, Math.max(xa, xb) + 8, Math.min(ya, yb), col.line);
          }
        }
      });
    }
  }

  /**
   * Monte le graphique dans `container`. opts : { kind } (clé de mémorisation des options d'affichage).
   * Retourne { update(chartDto), destroy() }.
   */
  TIO.expertChart = function (container, opts) {
    const kind = (opts && opts.kind) || 'rainbow';
    if (!LW) { TIO.mount(container, TIO.ui.error(new Error('Bibliothèque de graphique indisponible'))); return null; }
    const state = loadOpts(kind);
    const C = { text: css('--text'), muted: css('--muted'), line: css('--line'), up: css('--up'), down: css('--down'), warn: css('--warn'), sim: css('--sim'), accent: css('--accent'),
      s0: css('--s0'), s1: css('--s1'), s3: css('--s3'), s4: css('--s4') };
    const legend = h('div', { class: 'row small muted xc-legend' });
    const panelsBox = h('div', { class: 'xc-panels' });
    const detail = h('div', { class: 'xc-detail' });
    const credit = h('div', { class: 'small muted xc-credit' }, 'Graphiques : ', h('a', { href: 'https://www.tradingview.com/', target: '_blank', rel: 'noopener' }, 'TradingView Lightweight Charts™'));
    TIO.mount(container, legend, panelsBox, detail, credit);

    const baseOptions = () => ({
      autoSize: true,
      layout: { attributionLogo: false, background: { type: 'solid', color: 'transparent' }, textColor: C.muted, fontFamily: 'Manrope, system-ui, sans-serif', fontSize: 11 },
      grid: { vertLines: { color: C.line }, horzLines: { color: C.line } },
      // mêmes largeurs d'échelles gauche / droite sur tous les graphiques : les zones de tracé (donc le temps, les zones grises) restent alignées
      leftPriceScale: { visible: true, borderVisible: false, minimumWidth: SIDE_MIN },
      rightPriceScale: { borderColor: C.line, minimumWidth: SIDE_MIN }, timeScale: { borderColor: C.line, timeVisible: false, rightOffset: 2 },
      crosshair: { mode: LW.CrosshairMode.Normal },
    });
    const panels = {};
    function panel(name, title, height) {
      const hkey = 'tio.expert.chart.v2.h.' + kind + '.' + name;
      let saved = null;
      try { saved = Number(g.localStorage.getItem(hkey)); } catch (e) { /* ignoré */ }
      const body = h('div', { class: 'xc-body', style: { height: (saved >= 60 && saved <= 1200 ? saved : height) + 'px' } });
      const grip = h('div', { class: 'xc-resize', role: 'separator', 'aria-orientation': 'horizontal', tabindex: '0', title: 'Glisser pour agrandir / réduire (double-clic : taille par défaut)' }, h('i'));
      const setH = (v) => { body.style.height = Math.max(60, Math.min(1200, Math.round(v))) + 'px'; };
      const persist = () => { try { g.localStorage.setItem(hkey, String(body.offsetHeight)); } catch (e) { /* ignoré */ } };
      grip.addEventListener('pointerdown', (e) => {
        e.preventDefault(); grip.setPointerCapture(e.pointerId); grip.classList.add('xc-resize--drag');
        const y0 = e.clientY, h0 = body.offsetHeight;
        const move = (ev) => setH(h0 + ev.clientY - y0);
        const up = () => { grip.classList.remove('xc-resize--drag'); grip.removeEventListener('pointermove', move); grip.removeEventListener('pointerup', up); grip.removeEventListener('pointercancel', up); persist(); };
        grip.addEventListener('pointermove', move); grip.addEventListener('pointerup', up); grip.addEventListener('pointercancel', up);
      });
      grip.addEventListener('dblclick', () => { setH(height); try { g.localStorage.removeItem(hkey); } catch (e) { /* ignoré */ } });
      grip.addEventListener('keydown', (e) => {
        if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
        e.preventDefault(); setH(body.offsetHeight + (e.key === 'ArrowDown' ? 40 : -40)); persist();
      });
      const box = h('section', { class: 'xc-panel', 'aria-label': title }, h('div', { class: 'xc-title small muted' }, title), body, grip);
      panelsBox.appendChild(box);
      if (name === 'price') box.insertBefore(h('div', { class: 'small muted xc-hint' }, 'Shift+clic = mesurer (2 clics) · Ctrl+glisser = totaux achats / ventes de la zone · clic ou Échap = effacer'), grip);
      const chart = LW.createChart(body, baseOptions());
      panels[name] = { name, box, body, chart, series: {} };
      return panels[name];
    }
    /** Piste de régime (comme les 2 barres du bas du pine) : une barre colorée par bougie, sans échelle lisible. */
    function track(name, title) {
      const p = panel(name, title, 22);
      p.box.querySelector('.xc-resize').remove();
      p.chart.applyOptions({ timeScale: { visible: false }, rightPriceScale: { borderVisible: false, minimumWidth: SIDE_MIN }, localization: { priceFormatter: () => '' },
        crosshair: { horzLine: { visible: false, labelVisible: false } } });
      p.series.bar = p.chart.addHistogramSeries({ priceLineVisible: false, lastValueVisible: false, priceFormat: { type: 'volume' },
        autoscaleInfoProvider: () => ({ priceRange: { minValue: 0, maxValue: 1 } }) });
      return p;
    }
    const price = panel('price', 'Cours', 360), score = panel('score', 'Score de régression [−1 ; +1] (seuils ENTER / EXIT)', 130),
      rawT = track('rawT', 'Régime de régression · brut (avant confirmation)'), regT = track('regT', 'Régime de régression · appliqué'),
      smaT = track('smaT', 'Régime SMA (prix vs SMA ± k × ATR)'), bag = panel('bag', 'Taille du bag : quantité d’actif (axe droit, unités de l’actif), réserve moon, valeur en $ (axe gauche)', 150),
      pnl = panel('pnl', 'PnL ($ à gauche, % du capital à droite)', 170), expo = panel('expo', 'Exposition ($ à gauche, % du capital à droite)', 160);
    (function scoreInfo() {
      const ttl = score.box.querySelector('.xc-title'), txt = h('span', { class: 'xc-ttext' }, ttl.textContent), inf = TIO.ui.info();
      inf.set([
        'Chaque barre = score de régression du jour : pente des clôtures sur 3 fenêtres (14 / 30 / 60 j), pondérée par la qualité de la droite (r²), moyennée. > 0 : le prix monte de façon cohérente, < 0 : il baisse, ≈ 0 : bruit / plat. Proche de ±1 : pente forte et propre.',
        'ENTER (tirets) : le score doit franchir ce seuil pour passer en UP (vert) ou DOWN (rouge). EXIT (pointillés) : tant que le score ne repasse pas ce seuil, on reste dans la tendance ; en dessous, retour en RANGE (gris).',
        'Deux seuils volontairement différents (hystérésis) : entrée exigeante, sortie tolérante, donc peu d’allers-retours quand le score oscille. Ensuite, le régime brut subit la confirmation (jours consécutifs) puis se combine à la SMA.',
      ]);
      inf.querySelector('.info-pop').style.cssText = 'left:auto;right:0';
      ttl.classList.add('xc-title--row');
      TIO.mount(ttl, [txt, inf]);
    })();
    const line = (p, color, w, style, title, scaleId) => p.chart.addLineSeries(Object.assign({ color, lineWidth: w || 1, lineStyle: style || 0, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false, title: title || '' },
      scaleId ? { priceScaleId: scaleId, priceFormat: { type: 'price', precision: 0, minMove: 1 } } : {}));
    // PnL et Exposition : $ à gauche (courbes), % du capital à droite (jumelles invisibles : mêmes valeurs × 100 / capital, pour que l'échelle de droite suive celle de gauche)
    const pctFmt = { type: 'custom', minMove: 0.1, formatter: (v) => v.toFixed(Math.abs(v) < 10 ? 1 : 0) + ' %' };
    const twin = (p, key) => { p.series[key + 'Pct'] = p.chart.addLineSeries({ color: 'rgba(0,0,0,0)', lineWidth: 1, priceScaleId: 'right', priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false, priceFormat: pctFmt }); };
    const S = price.series;
    S.candle = price.chart.addCandlestickSeries({ upColor: C.up, downColor: C.down, borderVisible: false, wickUpColor: C.up, wickDownColor: C.down, priceLineVisible: false });
    const bandSpec = [['sma', C.muted, 1, 0], ['down2', C.s3, 2, 0], ['down1', rgba(C.s3, 0.65), 1, 0], ['up1', rgba(C.s1, 0.7), 1, 0], ['up2', rgba(C.s0, 0.8), 1, 0], ['up3', C.s0, 2, 0], ['ath', C.warn, 1, 2]];
    bandSpec.forEach(([k, c, w, st]) => { S[k] = line(price, c, w, st); });
    const WIN_COLORS = ['#2a78d6', '#eb6834', '#6250d6'];
    score.series.hist = score.chart.addHistogramSeries({ priceLineVisible: false, lastValueVisible: false, priceFormat: { type: 'price', precision: 2, minMove: 0.01 },
      autoscaleInfoProvider: () => ({ priceRange: { minValue: -scoreRange, maxValue: scoreRange } }) });
    let scoreRange = 1;
    score.series.sub = WIN_COLORS.map((c) => line(score, c, 1, 0));
    let scoreLines = [];
    // Trend seul : SMA et bandes SMA ± k × ATR
    const trendBandSpec = [['tSma', C.muted, 2, 0], ['tUp', rgba(C.up, 0.8), 1, 0], ['tDn', rgba(C.down, 0.8), 1, 0]];
    trendBandSpec.forEach(([k, c, w, st]) => { S[k] = line(price, c, w, st); });
    bag.chart.applyOptions({ leftPriceScale: { visible: true, borderVisible: true, borderColor: C.line, minimumWidth: SIDE_MIN } });
    bag.series.value = bag.chart.addLineSeries({ priceScaleId: 'left', color: C.up, lineWidth: 1, lineStyle: 0, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false, title: 'valeur $', priceFormat: { type: 'price', precision: 0, minMove: 1 } });
    bag.series.pos = line(bag, C.accent, 2, 0, 'bag'); bag.series.reserve = line(bag, C.warn, 1, 2, 'réserve moon');
    pnl.series.realized = line(pnl, C.up, 2, 0, 'réalisé', 'left'); pnl.series.potential = line(pnl, C.sim, 2, 0, 'potentiel', 'left');
    pnl.series.total = line(pnl, C.text, 2, 0, 'total', 'left'); pnl.series.fixed = line(pnl, C.muted, 1, 2, 'DCA fixe', 'left');
    expo.series.asset = line(expo, C.accent, 2, 0, 'en actif', 'left'); expo.series.stable = line(expo, C.up, 2, 0, 'en stable', 'left');
    expo.series.total = line(expo, C.text, 2, 0, 'total', 'left'); expo.series.capital = line(expo, C.muted, 1, 2, 'capital', 'left');
    ['realized', 'potential', 'total', 'fixed'].forEach((k) => twin(pnl, k));
    pnl.series.total.createPriceLine({ price: 0, color: C.muted, lineWidth: 1, lineStyle: 1, axisLabelVisible: false, title: '' }); // repère du 0
    ['asset', 'stable', 'total', 'capital'].forEach((k) => twin(expo, k));

    let data = null, shade = null, panelShades = [], byDay = {}, olsIdx = null, olsState = null;
    const olsPrim = new OlsPrimitive(price.chart, S.candle, () => (data && data.cols.tSma && state.ols ? olsState : null));
    S.candle.attachPrimitive(olsPrim);

    // ---- outils de lecture : Shift+clic = mesure (clic de départ, puis clic de fin) ; Ctrl+glisser = totaux achats / ventes de la zone ; clic simple ou Échap = effacer
    const tools = { measure: null, zone: null };
    const toolPrim = new MeasurePrimitive(price.chart, S.candle, () => (data ? { measure: tools.measure, zone: tools.zone, days: data.d,
      colors: { up: { fill: rgba(C.up, 0.14), line: C.up }, down: { fill: rgba(C.down, 0.14), line: C.down }, zone: rgba(C.accent, 0.12), zoneLine: C.accent } } : null));
    S.candle.attachPrimitive(toolPrim);
    const repaintTools = () => { if (toolPrim.requestUpdate) toolPrim.requestUpdate(); };
    const clearTools = () => { if (tools.measure || tools.zone) { tools.measure = null; tools.zone = null; repaintTools(); } };
    const paneXY = (e) => {
      const r = price.body.getBoundingClientRect(), ts = price.chart.timeScale();
      const x = e.clientX - r.left - price.chart.priceScale('left').width(), y = e.clientY - r.top;
      const lg = ts.coordinateToLogical(x), n = data ? data.d.length : 0;
      return n && lg !== null ? { i: Math.max(0, Math.min(n - 1, Math.round(lg))), p: S.candle.coordinateToPrice(y) } : null;
    };
    const fx = (v, dg) => F.num(v, dg == null ? 0 : dg);
    const signed = (v, dg) => (v > 0 ? '+' : '') + fx(v, dg);
    function measureOf(a, b) {
      const [i0, i1] = a.i <= b.i ? [a.i, b.i] : [b.i, a.i];
      const pa = a.i <= b.i ? a.p : b.p, pb = a.i <= b.i ? b.p : a.p;
      const days = Math.round((Date.parse(data.d[i1]) - Date.parse(data.d[i0])) / 86400000);
      const pct = pa ? (pb - pa) / pa * 100 : 0;
      return { i0, i1, p0: pa, p1: pb, lines: [signed(pct, 2) + ' %  (' + signed(pb - pa, 0) + ')', (i1 - i0) + ' barres · ' + days + ' j', fx(pa, 0) + ' -> ' + fx(pb, 0)] };
    }
    function zoneOf(a, b) {
      const i0 = Math.min(a, b), i1 = Math.max(a, b), c = data.cols, sumOf = (k, f) => { let t = 0, n = 0; for (let i = i0; i <= i1; i++) if (f(i)) { t += c[k][i] || 0; n++; } return [t, n]; };
      const days = Math.round((Date.parse(data.d[i1]) - Date.parse(data.d[i0])) / 86400000);
      const pct = c.c[i0] ? (c.c[i1] - c.c[i0]) / c.c[i0] * 100 : 0;
      const lines = [F.dayLong(data.d[i0]) + ' -> ' + F.dayLong(data.d[i1]), (i1 - i0 + 1) + ' barres · ' + days + ' j · prix ' + signed(pct, 2) + ' %'];
      if (c.buyAmt) {
        const [ba, bn] = sumOf('buyAmt', (i) => c.buyKind[i] > 0), [bq] = sumOf('buyQty', (i) => c.buyKind[i] > 0);
        const [sp, sn] = sumOf('sellProc', (i) => c.sellKind[i] > 0), [sq] = sumOf('sellQty', (i) => c.sellKind[i] > 0);
        lines.push('Achats : ' + bn + ' · ' + fx(ba, 0) + ' $ · ' + fx(bq, 6) + ' qté', 'Ventes : ' + sn + ' · ' + fx(sp, 0) + ' $ · ' + fx(sq, 6) + ' qté', 'Net investi : ' + signed(ba - sp, 0) + ' $');
      }
      return { i0, i1, lines };
    }
    price.body.addEventListener('mousedown', (e) => {
      if (e.button !== 0 || !data) return;
      const shift = e.shiftKey, ctrl = e.ctrlKey || e.metaKey;
      if (!shift && !ctrl) { clearTools(); return; }
      const a = paneXY(e);
      if (!a) return;
      e.preventDefault(); e.stopPropagation();
      if (ctrl) {
        tools.measure = null; tools.zone = zoneOf(a.i, a.i); repaintTools();
        const move = (ev) => { const b = paneXY(ev); if (b) { tools.zone = zoneOf(a.i, b.i); repaintTools(); } };
        const up = () => { g.removeEventListener('mousemove', move); g.removeEventListener('mouseup', up); };
        g.addEventListener('mousemove', move); g.addEventListener('mouseup', up);
        return;
      }
      tools.zone = null;
      if (tools.measure && tools.measure.live) { tools.measure.live = false; repaintTools(); return; }
      tools.measure = Object.assign(measureOf(a, a), { live: true, a }); repaintTools();
    }, true);
    price.body.addEventListener('mousemove', (e) => {
      if (!tools.measure || !tools.measure.live) return;
      const b = paneXY(e);
      if (b) { tools.measure = Object.assign(measureOf(tools.measure.a, b), { live: true, a: tools.measure.a }); repaintTools(); }
    });
    const onKey = (e) => { if (e.key === 'Escape') clearTools(); };
    g.addEventListener('keydown', onKey);
    // ---- synchronisation des échelles de temps et du curseur ----
    const all = Object.values(panels);
    // si un libellé d'échelle dépasse la largeur commune, on élargit toutes les échelles à ce maximum (jamais de rétrécissement)
    let sideL = SIDE_MIN, sideR = SIDE_MIN, alignTimer = null;
    function alignScales() {
      let l = sideL, r = sideR;
      all.forEach((p) => {
        if (p.box.hidden || p.box.classList.contains('xc-off')) return;
        l = Math.max(l, p.chart.priceScale('left').width()); r = Math.max(r, p.chart.priceScale('right').width());
      });
      if (l === sideL && r === sideR) return;
      sideL = l; sideR = r;
      all.forEach((p) => p.chart.applyOptions({ leftPriceScale: { minimumWidth: sideL }, rightPriceScale: { minimumWidth: sideR } }));
    }
    // ligne verticale du curseur reproduite sur tous les graphiques ; la ligne horizontale (prix) reste sur le graphique survolé seul
    const anchorOf = (p) => p.series.candle || p.series.hist || p.series.bar || p.series.pos || p.series.total;
    // seul le graphique réellement survolé pilote les autres : les événements de crosshair émis (en différé) par setCrosshairPosition
    // sur les graphiques suiveurs ne doivent pas repartir en sens inverse (sinon l'horizontale du graphique survolé saute)
    let crossSrc = null, crossBusy = false, hoverSrc = null;
    function syncCrosshair(src, ev) {
      if (crossBusy || src !== hoverSrc) return;
      crossBusy = true;
      try {
        if (crossSrc !== src) {
          crossSrc = src;
          all.forEach((q) => { if (!/T$/.test(q.name)) q.chart.applyOptions({ crosshair: { horzLine: { visible: q === src, labelVisible: q === src } } }); });
        }
        all.forEach((q) => {
          if (q === src || q.box.hidden || q.box.classList.contains('xc-off')) return;
          const a = anchorOf(q);
          if (ev.time && a) {
            // lightweight-charts 4.2.3 : pas de timeToIndex ; seule la verticale compte, l'ordonnée (dernier point de la série) est un support
            const dt = a.data(), d = dt.length ? dt[dt.length - 1] : null;
            const v = d ? (d.value !== undefined ? d.value : d.close) : 0;
            q.chart.setCrosshairPosition(Number.isFinite(v) ? v : 0, ev.time, a);
          } else q.chart.clearCrosshairPosition();
        });
      } finally { crossBusy = false; }
    }
    const scheduleAlign = () => { clearTimeout(alignTimer); alignTimer = setTimeout(alignScales, 120); };
    let syncing = false;
    all.forEach((p) => {
      p.box.addEventListener('pointerenter', () => { hoverSrc = p; });
      p.box.addEventListener('pointerleave', () => {
        if (hoverSrc === p) hoverSrc = null;
        crossSrc = null;
        all.forEach((q) => { if (q !== p) q.chart.clearCrosshairPosition(); });
      });
      p.chart.timeScale().subscribeVisibleLogicalRangeChange((r) => {
        if (syncing || !r) return;
        scheduleAlign();
        syncing = true;
        all.forEach((q) => { if (q !== p && !q.box.hidden) q.chart.timeScale().setVisibleLogicalRange(r); });
        syncing = false;
      });
      p.chart.subscribeCrosshairMove((ev) => {
        syncCrosshair(p, ev);
        if (!ev.time || p !== hoverSrc) return;
        const i = byDay[typeof ev.time === 'string' ? ev.time : ''];
        if (i !== undefined) { showDay(i); drawOls(i); }
      });
      p.chart.subscribeClick((ev) => { if (ev.time && typeof ev.time === 'string' && byDay[ev.time] !== undefined) { showDay(byDay[ev.time]); drawOls(byDay[ev.time]); } });
    });

    // ---- options d'affichage (mémorisées) ----
    function sw(label, get, set, need) {
      const s = TIO.ui.switchEl({ label, checked: get(), onChange: (on) => { set(on); saveOpts(kind, state); apply(); } });
      return h('label', { class: 'row small', 'data-need': need || '' }, s.el, h('span', null, label));
    }
    /** Interrupteurs placés dans l'en-tête de chaque graphique (à droite du titre). Un graphique désactivé garde son en-tête (pour le réactiver) et masque son corps. */
    function buildToolbar() {
      const place = (pn, ...items) => {
        const ttl = pn.box.querySelector('.xc-title');
        ttl.classList.add('xc-title--row');
        ttl.appendChild(h('span', { class: 'xc-sws' }, ...items));
      };
      place(price,
        sw('SMA et Bandes', () => state.bands, (v) => { state.bands = v; }, 'band'),
        sw('Ligne ATH', () => state.ath, (v) => { state.ath = v; }, 'rb'),
        sw('Marqueurs', () => state.markers, (v) => { state.markers = v; }),
        sw('Phases UP / DOWN', () => state.phases, (v) => { state.phases = v; }),
        sw('Droites de régression au survol', () => state.ols, (v) => { state.ols = v; }, 'tb'));
      place(score,
        sw('Sous-scores 14 / 30 / 60', () => state.panels.sub, (v) => { state.panels.sub = v; }, 'tb'),
        sw('Afficher', () => state.panels.score, (v) => { state.panels.score = v; }, 'tb'));
      place(rawT, sw('Afficher', () => state.panels.raw, (v) => { state.panels.raw = v; }, 'tb'));
      place(smaT, sw('Afficher', () => state.panels.sma, (v) => { state.panels.sma = v; }, 'tb'));
      place(bag, sw('Afficher', () => state.panels.bag, (v) => { state.panels.bag = v; }, 'rb'));
      place(pnl,
        sw('Courbe DCA fixe (budget égal)', () => state.fixed, (v) => { state.fixed = v; }, 'rb'),
        sw('Afficher', () => state.panels.pnl, (v) => { state.panels.pnl = v; }, 'rb'));
      place(expo, sw('Afficher', () => state.panels.expo, (v) => { state.panels.expo = v; }, 'wallet'));
    }
    function dot(color, label) { return h('span', { class: 'row' }, h('i', { class: 'xc-dot', style: { background: color } }), label); }
    function buildLegend() {
      const rb = data && data.cols && data.cols.sma;
      const items = [];
      if (rb) items.push(dot(C.muted, 'SMA'), dot(C.s3, 'DOWN2 / DOWN1'), dot(C.s1, 'UP1'), dot(C.s0, 'UP2 / UP3'), dot(C.warn, 'ATH'),
        dot(C.up, 'achat zone'), dot(C.sim, 'achat déclenché'), dot(C.down, 'vente'), dot(C.warn, 'To the moon'), dot(C.s1, 'stop moon'));
      if (data && data.cols && data.cols.tSma) items.push(dot(C.muted, 'SMA'), dot(C.up, 'SMA + k × ATR'), dot(C.down, 'SMA − k × ATR'), dot(C.up, 'régime UP'), dot(C.down, 'régime DOWN'), dot(rgba(C.muted, 0.6), 'RANGE / indécis'),
        ...(data.trendParams && data.trendParams.shortWindow ? [dot(WIN_COLORS[0], 'droite ' + data.trendParams.shortWindow + ' j'), dot(WIN_COLORS[1], 'droite ' + data.trendParams.mediumWindow + ' j'), dot(WIN_COLORS[2], 'droite ' + data.trendParams.longWindow + ' j')] : []));
      items.push(dot(rgba(C.up, 0.5), 'phase UP'), dot(rgba(C.down, 0.5), 'phase DOWN'), dot(rgba(C.muted, 0.6), 'inactif (hors régime)'));
      if (data && data.sets && data.sets.length > 1) data.sets.forEach((s, i) => items.push(h('span', null, (i ? 'Bull' : 'Bear') + ' : ' + s.name)));
      TIO.mount(legend, items);
    }

    function markers() {
      const d = data, cols = d.cols, out = [];
      const f = (x, dg) => F.num(x, dg == null ? 0 : dg);
      for (let i = 0; i < d.d.length; i++) {
        const t = d.d[i];
        if (cols.set && i > 0 && cols.set[i] !== cols.set[i - 1]) out.push({ time: t, position: 'belowBar', shape: 'square', color: cols.set[i] ? C.up : C.down, text: cols.set[i] ? 'Bull' : 'Bear', size: 0.6 });
        if (!cols.set && i > 0 && d.trend[i] !== d.trend[i - 1] && d.trend[i]) out.push({ time: t, position: 'belowBar', shape: 'square', color: d.trend[i] === 1 ? C.up : C.down, text: TRENDS[d.trend[i]], size: 0.6 });
        if (!cols.buyKind) continue;
        if (cols.moonEntry[i]) out.push({ time: t, position: 'aboveBar', shape: 'arrowDown', color: C.warn, text: 'MOON' });
        if (cols.sellKind[i] === 2) out.push({ time: t, position: 'aboveBar', shape: 'arrowDown', color: C.s1, text: 'STOP MOON -' + f(cols.sellProc[i]) });
        else if (cols.sellKind[i] === 1) out.push({ time: t, position: 'aboveBar', shape: 'arrowDown', color: C.down, text: '-' + f(cols.sellProc[i]) });
        if (cols.buyKind[i] === 2) out.push({ time: t, position: 'belowBar', shape: 'arrowUp', color: C.sim, text: '+' + f(cols.buyAmt[i]) });
        else if (cols.buyKind[i] === 1) out.push({ time: t, position: 'belowBar', shape: 'arrowUp', color: C.up, text: '+' + f(cols.buyAmt[i]) });
      }
      return out;
    }

    const series = (arr) => data.d.map((t, i) => ({ time: t, value: arr[i] })).filter((p) => p.value !== null && p.value !== undefined);

    function apply() {
      if (!data) return;
      const cols = data.cols, hasRb = !!cols.sma, hasWallet = !!data.wallet, hasTb = !!cols.tSma;
      bandSpec.forEach(([k]) => S[k].applyOptions({ visible: state.bands && hasRb && (k !== 'ath' || state.ath) }));
      trendBandSpec.forEach(([k]) => S[k].applyOptions({ visible: state.bands && hasTb }));
      S.candle.setMarkers(state.markers ? markers() : []);
      if (shade) { shade.opts.phases = state.phases; price.chart.timeScale().applyOptions({}); }
      panelShades.forEach((x) => { x.prim.opts.phases = false; });
      const P = state.panels;
      // panneau indisponible => masqué ; désactivé => en-tête seul (xc-off)
      const show = (pn, avail, on) => { pn.box.hidden = !avail; pn.box.classList.toggle('xc-off', !on); };
      show(bag, hasRb, P.bag); show(pnl, hasRb, P.pnl); show(expo, hasWallet, P.expo);
      show(score, hasTb, P.score); show(rawT, hasTb, P.raw); show(smaT, hasTb, P.sma);
      regT.box.hidden = !(hasTb && (P.score || P.raw));
      score.series.sub.forEach((q) => q.applyOptions({ visible: hasTb && P.sub }));
      pnl.series.fixed.applyOptions({ visible: !!state.fixed }); pnl.series.fixedPct.applyOptions({ visible: !!state.fixed });
      drawOls(olsIdx);
      const has = { band: hasRb || hasTb, rb: hasRb, wallet: hasWallet, tb: hasTb };
      panelsBox.querySelectorAll('label[data-need]').forEach((l) => { const nd = l.dataset.need; l.hidden = !!nd && !has[nd]; });
      all.forEach((p) => { if (!p.box.hidden) p.chart.applyOptions({}); });
      // forcer le redessin des primitives
      const r = price.chart.timeScale().getVisibleLogicalRange();
      if (r) price.chart.timeScale().setVisibleLogicalRange(r);
    }

    /** 3 droites OLS (fenêtres courte / moyenne / longue) de la bougie i, calculées côté serveur ; début ramené au 1er jour tracé si la fenêtre le déborde. */
    function drawOls(i) {
      olsIdx = i;
      olsState = { lines: [] };
      if (data && data.cols.tSma && state.ols && i !== null && i !== undefined) {
        const c = data.cols, tp = data.trendParams || {}, wins = [tp.shortWindow, tp.mediumWindow, tp.longWindow];
        wins.forEach((w, k) => {
          const a = c['tOlsS' + k][i], b = c['tOlsE' + k][i];
          if (a === null || b === null || !w || w < 2) return;
          const j = i - w + 1, slope = (b - a) / (w - 1);
          olsState.lines.push({ t0: data.d[Math.max(0, j)], t1: data.d[i], v0: j < 0 ? a + slope * -j : a, v1: b, color: WIN_COLORS[k] });
        });
      }
      if (olsPrim.requestUpdate) olsPrim.requestUpdate();
    }
    function row(label, value) { return h('tr', null, h('td', { class: 'muted' }, label), h('td', { class: 'num' }, value)); }
    function showDay(i) {
      if (!data || i === undefined) return;
      const c = data.cols, d = data.d, cl = (k) => (c[k] ? c[k][i] : null);
      const rows = [row('Jour', F.dayLong(d[i])), row('Régime Trend', TRENDS[data.trend[i]] + (data.active[i] ? '' : ' · inactif')), row('Clôture', F.price(cl('c')))];
      if (c.tSma) {
        const fx = (v) => (v === null || v === undefined ? '—' : F.num(v, 2));
        rows.push(row('Régression (appliqué / brut)', STATES[cl('tReg')] + ' / ' + STATES[cl('tRaw')]), row('Score de régression', fx(cl('tScore'))),
          row('Sous-scores 14 / 30 / 60', fx(cl('tSub0')) + ' / ' + fx(cl('tSub1')) + ' / ' + fx(cl('tSub2'))), row('SMA (état)', STATES[cl('tSmaReg')]), row('SMA', F.num(cl('tSma'), 2)),
          row('Bande basse / haute', F.num(cl('tDn'), 2) + ' / ' + F.num(cl('tUp'), 2)));
      }
      if (c.sma) {
        const set = data.sets && data.sets[cl('set')];
        const sig = [];
        if (cl('buyKind')) sig.push((cl('buyKind') === 2 ? 'ACHAT déclenché ' : 'ACHAT zone ') + '+' + F.num(cl('buyAmt'), 2) + ' (×' + F.num(cl('multZone'), 2) + ' zone × ' + F.num(cl('buyFac'), 2) + ' ATH)');
        if (cl('sellKind')) sig.push((cl('sellKind') === 2 ? 'STOP MOON ' : 'VENTE ') + F.num(cl('sellFrac') * 100, 1) + ' % = ' + F.num(cl('sellProc'), 2));
        if (cl('sellArmNow')) sig.push('armement vente');
        if (cl('buyArmNow')) sig.push('armement achat');
        if (cl('moonEntry')) sig.push('entrée To the moon');
        const flags = [cl('buyArmed') && 'achat armé', cl('sellArmed') && 'vente armée', cl('locked') && 'achat verrouillé', cl('cooldown') > 0 && ('délai ' + cl('cooldown') + ' j'), cl('moon') && 'MOON'].filter(Boolean).join(', ') || '—';
        const stable = data.wallet ? data.wallet.stable[i] : null;
        rows.push(row('Jeu actif', set ? set.name : '—'), row('Zone', ZONES[cl('zone')] || '—'), row('SMA / ATR', F.num(cl('sma'), 2) + ' / ' + F.num(cl('atr'), 2)),
          row('DOWN2 / DOWN1', F.num(cl('down2'), 2) + ' / ' + F.num(cl('down1'), 2)), row('UP1 / UP2 / UP3', F.num(cl('up1'), 2) + ' / ' + F.num(cl('up2'), 2) + ' / ' + F.num(cl('up3'), 2)),
          row('ATH / distance', F.num(cl('ath'), 2) + ' / -' + F.num(cl('athDist') * 100, 1) + ' %'), row('Facteurs ATH achat / vente', F.num(cl('buyFac'), 2) + ' / ' + F.num(cl('sellFac'), 2)),
          row('Signaux', sig.length ? sig.join(' · ') : '—'), row('États', flags),
          row('Position (bag)', F.num(cl('pos'), 6) + ' ≈ ' + F.num(cl('bagValue'), 0) + ' $ (réserve moon ' + F.num(cl('reserve'), 6) + ')'),
          row('Wallet actif / stable / total', data.wallet ? F.num(data.wallet.asset[i], 0) + ' / ' + F.num(stable, 0) + ' / ' + F.num(data.wallet.total[i], 0) : '—'),
          row('Investi / vendu', F.num(cl('invested'), 0) + ' / ' + F.num(cl('proceeds'), 0)),
          row('Réalisé', h('span', { class: TIO.sign(cl('realized')) }, F.num(cl('realized'), 0))), row('Potentiel', h('span', { class: TIO.sign(cl('potential')) }, F.num(cl('potential'), 0))),
          row('Total', h('span', { class: TIO.sign(cl('total')) }, F.num(cl('total'), 0))), row('DCA fixe, budget égal (gain)', h('span', { class: TIO.sign(cl('fixedGainEq')) }, F.num(cl('fixedGainEq'), 0))));
      }
      TIO.mount(detail, h('div', { class: 'small muted' }, 'Détail du jour (survol ou clic)'), h('div', { class: 'scroll-x' }, h('table', { class: 'tbl' }, h('tbody', null, rows))));
    }

    buildToolbar();
    return {
      update(dto) {
        data = dto && dto.d && dto.d.length ? dto : null;
        tools.measure = null; tools.zone = null;
        if (shade) { S.candle.detachPrimitive(shade); shade = null; }
        panelShades.forEach((x) => x.series.detachPrimitive(x.prim)); panelShades = [];
        if (!data) { all.forEach((p) => { p.box.hidden = true; }); TIO.mount(detail); TIO.mount(legend); return; }
        price.box.hidden = false;
        const c = data.cols;
        byDay = {}; data.d.forEach((t, i) => { byDay[t] = i; });
        S.candle.setData(data.d.map((t, i) => ({ time: t, open: c.o[i], high: c.h[i], low: c.l[i], close: c.c[i] })));
        bandSpec.forEach(([k]) => { if (c[k]) S[k].setData(series(c[k])); else S[k].setData([]); });
        if (c.tSma) {
          ['tSma', 'tUp', 'tDn'].forEach((k) => S[k].setData(series(c[k])));
          const bars = (arr) => data.d.map((t, i) => ({ time: t, value: 1, color: arr[i] === 1 ? rgba(C.up, 0.85) : arr[i] === 2 ? rgba(C.down, 0.85) : arr[i] === 3 ? rgba(C.muted, 0.45) : 'rgba(0,0,0,0)' }));
          regT.series.bar.setData(bars(c.tReg)); rawT.series.bar.setData(bars(c.tRaw)); smaT.series.bar.setData(bars(c.tSmaReg));
          score.series.hist.setData(data.d.map((t, i) => (c.tScore[i] === null ? { time: t } : { time: t, value: c.tScore[i], color: c.tReg[i] === 1 ? rgba(C.up, 0.85) : c.tReg[i] === 2 ? rgba(C.down, 0.85) : rgba(C.muted, 0.55) })));
          score.series.sub.forEach((q, k) => q.setData(series(c['tSub' + k])));
          scoreLines.forEach((l) => score.series.hist.removePriceLine(l)); scoreLines = [];
          const tp = data.trendParams || {};
          [[tp.enter, 'ENTER', 2], [-tp.enter, '', 2], [tp.exit, 'EXIT', 1], [-tp.exit, '', 1]].forEach(([v, t, st]) => {
            if (Number.isFinite(v)) scoreLines.push(score.series.hist.createPriceLine({ price: v, color: C.muted, lineWidth: 1, lineStyle: st, axisLabelVisible: false, title: '' }));
          });
          const maxAbs = c.tScore.reduce((m, v) => (v === null ? m : Math.max(m, Math.abs(v))), 0);
          scoreRange = Math.min(1, Math.max(0.2, Math.ceil(Math.max((tp.enter || 0) * 1.4, maxAbs * 1.08) * 20) / 20));
          const ttl = score.box.querySelector('.xc-title');
          if (ttl) (ttl.querySelector('.xc-ttext') || ttl).textContent = 'Score de régression · seuils ENTER ±' + F.num(tp.enter, 2) + ' (tirets), EXIT ±' + F.num(tp.exit, 2) + ' (pointillés)';
        } else { ['tSma', 'tUp', 'tDn'].forEach((k) => S[k].setData([])); regT.series.bar.setData([]); rawT.series.bar.setData([]); smaT.series.bar.setData([]); score.series.hist.setData([]); score.series.sub.forEach((q) => q.setData([])); }
        if (c.pos) {
          bag.series.pos.setData(series(c.pos)); bag.series.reserve.setData(series(c.reserve)); bag.series.value.setData(series(c.bagValue));
          // l'axe du bag est en unités de l'actif (ex. 0,0034 BTC) : 2 décimales fixes l'affichaient « 0.00 »
          const bagMax = Math.max(0, ...c.pos.filter(Number.isFinite), ...c.reserve.filter(Number.isFinite));
          const prec = bagMax >= 100 ? 0 : bagMax >= 1 ? 2 : bagMax >= 0.01 ? 4 : bagMax >= 0.0001 ? 6 : 8;
          const pf = { type: 'price', precision: prec, minMove: Math.pow(10, -prec) };
          bag.series.pos.applyOptions({ priceFormat: pf }); bag.series.reserve.applyOptions({ priceFormat: pf });
          pnl.series.realized.setData(series(c.realized)); pnl.series.potential.setData(series(c.potential)); pnl.series.total.setData(series(c.total));
          // % du capital (capital du wallet, sinon plus haut montant investi) : même facteur sur toutes les courbes
          const capPnl = (data.wallet && data.wallet.capital) || Math.max(0, ...c.invested.filter(Number.isFinite));
          // DCA fixe à budget égal : gain × (capital Rainbow / total investi par le DCA fixe), comparable au Rainbow en $ comme en %
          const fixedTotal = Math.max(0, ...c.fixedInvested.filter(Number.isFinite));
          const eq = fixedTotal > 0 && capPnl > 0 ? capPnl / fixedTotal : 1;
          c.fixedGainEq = c.fixedGain.map((v) => (v === null || v === undefined ? null : v * eq));
          pnl.series.fixed.setData(series(c.fixedGainEq));
          const kp = capPnl > 0 ? 100 / capPnl : 0;
          const pct = (arr) => series(arr.map((v) => (v === null || v === undefined ? null : v * kp)));
          [['realized', 'realized'], ['potential', 'potential'], ['total', 'total'], ['fixed', 'fixedGainEq']].forEach(([k, col]) => pnl.series[k + 'Pct'].setData(kp ? pct(c[col]) : []));
        }
        if (data.wallet) {
          expo.series.asset.setData(series(data.wallet.asset)); expo.series.stable.setData(series(data.wallet.stable)); expo.series.total.setData(series(data.wallet.total));
          expo.series.capital.setData(data.d.map((t) => ({ time: t, value: data.wallet.capital })));
          const ke = data.wallet.capital > 0 ? 100 / data.wallet.capital : 0;
          const pe = (arr) => series(arr.map((v) => (v === null || v === undefined ? null : v * ke)));
          [['asset', data.wallet.asset], ['stable', data.wallet.stable], ['total', data.wallet.total]].forEach(([k, arr]) => expo.series[k + 'Pct'].setData(ke ? pe(arr) : []));
          expo.series.capitalPct.setData(ke ? data.d.map((t) => ({ time: t, value: 100 })) : []);
        }
        shade = new ShadePrimitive(price.chart, data.d, data.trend, data.active, { off: rgba(C.muted, 0.16), up: rgba(C.up, 0.07), down: rgba(C.down, 0.07) });
        S.candle.attachPrimitive(shade);
        // zones hors régime testé grisées aussi sur Bag / PnL / Exposition (la vue Affectation n'en a aucune : rien n'est dessiné)
        [[bag, bag.series.pos], [pnl, pnl.series.total], [expo, expo.series.total]].forEach(([pn, sr]) => {
          const prim = new ShadePrimitive(pn.chart, data.d, data.trend, data.active, { off: rgba(C.muted, 0.16), up: null, down: null });
          prim.opts.phases = false;
          sr.attachPrimitive(prim);
          panelShades.push({ series: sr, prim });
        });
        buildLegend();
        all.forEach((p) => p.chart.timeScale().fitContent());
        apply();
        scheduleAlign();
        showDay(data.d.length - 1);
        drawOls(data.d.length - 1);
      },
      destroy() { g.removeEventListener('keydown', onKey); all.forEach((p) => p.chart.remove()); },
    };
  };
})(window);
