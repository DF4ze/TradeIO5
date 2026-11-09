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
  const DEFAULTS = { bands: true, markers: true, inactive: true, phases: true, panels: { bag: true, pnl: true, expo: false, score: true, sub: false, raw: true, sma: true }, ols: true };

  const css = (n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
  function rgba(hex, a) {
    const m = /^#([0-9a-f]{6})$/i.exec(hex.trim());
    if (!m) return hex;
    const n = parseInt(m[1], 16);
    return 'rgba(' + (n >> 16) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + a + ')';
  }
  function loadOpts(kind) {
    try {
      const raw = g.localStorage.getItem('tio.expert.chart.' + kind);
      if (raw) { const o = JSON.parse(raw); return Object.assign({}, DEFAULTS, o, { panels: Object.assign({}, DEFAULTS.panels, o.panels) }); }
    } catch (e) { /* stockage indisponible : valeurs par défaut */ }
    return Object.assign({}, DEFAULTS, { panels: Object.assign({}, DEFAULTS.panels) });
  }
  function saveOpts(kind, o) { try { g.localStorage.setItem('tio.expert.chart.' + kind, JSON.stringify(o)); } catch (e) { /* ignoré */ } }

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
    const toolbar = h('div', { class: 'row xc-bar' });
    const legend = h('div', { class: 'row small muted xc-legend' });
    const panelsBox = h('div', { class: 'xc-panels' });
    const detail = h('div', { class: 'xc-detail' });
    const credit = h('div', { class: 'small muted xc-credit' }, 'Graphiques : ', h('a', { href: 'https://www.tradingview.com/', target: '_blank', rel: 'noopener' }, 'TradingView Lightweight Charts™'));
    TIO.mount(container, toolbar, legend, panelsBox, detail, credit);

    const baseOptions = () => ({
      autoSize: true,
      layout: { attributionLogo: false, background: { type: 'solid', color: 'transparent' }, textColor: C.muted, fontFamily: 'Manrope, system-ui, sans-serif', fontSize: 11 },
      grid: { vertLines: { color: C.line }, horzLines: { color: C.line } },
      rightPriceScale: { borderColor: C.line, minimumWidth: 64 }, timeScale: { borderColor: C.line, timeVisible: false, rightOffset: 2 },
      crosshair: { mode: LW.CrosshairMode.Normal },
    });
    const panels = {};
    function panel(name, title, height) {
      const body = h('div', { class: 'xc-body', style: { height: height + 'px' } });
      const box = h('section', { class: 'xc-panel', 'aria-label': title }, h('div', { class: 'xc-title small muted' }, title), body);
      panelsBox.appendChild(box);
      const chart = LW.createChart(body, baseOptions());
      panels[name] = { name, box, body, chart, series: {} };
      return panels[name];
    }
    /** Piste de régime (comme les 2 barres du bas du pine) : une barre colorée par bougie, sans échelle lisible. */
    function track(name, title) {
      const p = panel(name, title, 22);
      p.chart.applyOptions({ timeScale: { visible: false }, rightPriceScale: { borderVisible: false, minimumWidth: 64 }, localization: { priceFormatter: () => '' },
        crosshair: { horzLine: { visible: false, labelVisible: false } } });
      p.series.bar = p.chart.addHistogramSeries({ priceLineVisible: false, lastValueVisible: false, priceFormat: { type: 'volume' },
        autoscaleInfoProvider: () => ({ priceRange: { minValue: 0, maxValue: 1 } }) });
      return p;
    }
    const price = panel('price', 'Cours', 360), score = panel('score', 'Score de régression [−1 ; +1] (seuils ENTER / EXIT)', 130),
      rawT = track('rawT', 'Régime de régression · brut (avant confirmation)'), regT = track('regT', 'Régime de régression · appliqué'),
      smaT = track('smaT', 'Régime SMA (prix vs SMA ± k × ATR)'), bag = panel('bag', 'Taille du bag (position) et réserve moon', 150),
      pnl = panel('pnl', 'PnL (réalisé, potentiel, total, DCA fixe)', 170), expo = panel('expo', 'Exposition (actif / stable / total)', 160);
    const line = (p, color, w, style, title) => p.chart.addLineSeries({ color, lineWidth: w || 1, lineStyle: style || 0, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false, title: title || '' });
    const S = price.series;
    S.candle = price.chart.addCandlestickSeries({ upColor: C.up, downColor: C.down, borderVisible: false, wickUpColor: C.up, wickDownColor: C.down, priceLineVisible: false });
    const bandSpec = [['sma', C.muted, 1, 0], ['down2', C.s3, 2, 0], ['down1', rgba(C.s3, 0.65), 1, 0], ['up1', rgba(C.s1, 0.7), 1, 0], ['up2', rgba(C.s0, 0.8), 1, 0], ['up3', C.s0, 2, 0], ['ath', C.warn, 1, 2]];
    bandSpec.forEach(([k, c, w, st]) => { S[k] = line(price, c, w, st); });
    const WIN_COLORS = ['#2a78d6', '#eb6834', '#6250d6'];
    score.chart.applyOptions({ rightPriceScale: { minimumWidth: 64 } });
    score.series.hist = score.chart.addHistogramSeries({ priceLineVisible: false, lastValueVisible: false, priceFormat: { type: 'price', precision: 2, minMove: 0.01 },
      autoscaleInfoProvider: () => ({ priceRange: { minValue: -scoreRange, maxValue: scoreRange } }) });
    let scoreRange = 1;
    score.series.sub = WIN_COLORS.map((c) => line(score, c, 1, 0));
    let scoreLines = [];
    // Trend seul : SMA et bandes SMA ± k × ATR
    const trendBandSpec = [['tSma', C.muted, 2, 0], ['tUp', rgba(C.up, 0.8), 1, 0], ['tDn', rgba(C.down, 0.8), 1, 0]];
    trendBandSpec.forEach(([k, c, w, st]) => { S[k] = line(price, c, w, st); });
    bag.series.pos = line(bag, C.accent, 2, 0, 'bag'); bag.series.reserve = line(bag, C.warn, 1, 2, 'réserve moon');
    pnl.series.realized = line(pnl, C.up, 2, 0, 'réalisé'); pnl.series.potential = line(pnl, C.sim, 2, 0, 'potentiel');
    pnl.series.total = line(pnl, C.text, 2, 0, 'total'); pnl.series.fixed = line(pnl, C.muted, 1, 2, 'DCA fixe');
    expo.series.asset = line(expo, C.accent, 2, 0, 'en actif'); expo.series.stable = line(expo, C.up, 2, 0, 'en stable');
    expo.series.total = line(expo, C.text, 2, 0, 'total'); expo.series.capital = line(expo, C.muted, 1, 2, 'capital');

    let data = null, shade = null, byDay = {}, olsIdx = null, olsState = null;
    const olsPrim = new OlsPrimitive(price.chart, S.candle, () => (data && data.cols.tSma && state.ols ? olsState : null));
    S.candle.attachPrimitive(olsPrim);
    // ---- synchronisation des échelles de temps et du curseur ----
    const all = Object.values(panels);
    let syncing = false;
    all.forEach((p) => {
      p.chart.timeScale().subscribeVisibleLogicalRangeChange((r) => {
        if (syncing || !r) return;
        syncing = true;
        all.forEach((q) => { if (q !== p && !q.box.hidden) q.chart.timeScale().setVisibleLogicalRange(r); });
        syncing = false;
      });
      p.chart.subscribeCrosshairMove((ev) => {
        if (!ev.time) return;
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
    /** Interrupteurs groupés par catégorie ; un groupe disparaît si aucun de ses interrupteurs ne s'applique au graphique courant. */
    function buildToolbar() {
      const grp = (title, ...items) => h('div', { class: 'xc-group' }, h('span', { class: 'xc-gtitle small muted' }, title), ...items);
      TIO.mount(toolbar,
        grp('Prix',
          sw('Bornes ATR / ATH · SMA et bandes', () => state.bands, (v) => { state.bands = v; }, 'band'),
          sw('Marqueurs', () => state.markers, (v) => { state.markers = v; }),
          sw('Phases UP / DOWN', () => state.phases, (v) => { state.phases = v; }),
          sw('Zones inactives grisées', () => state.inactive, (v) => { state.inactive = v; })),
        grp('Trend',
          sw('Score de régression', () => state.panels.score, (v) => { state.panels.score = v; }, 'tb'),
          sw('Sous-scores 14 / 30 / 60', () => state.panels.sub, (v) => { state.panels.sub = v; }, 'tb'),
          sw('Brut vs appliqué', () => state.panels.raw, (v) => { state.panels.raw = v; }, 'tb'),
          sw('Régime SMA', () => state.panels.sma, (v) => { state.panels.sma = v; }, 'tb'),
          sw('Droites de régression au survol', () => state.ols, (v) => { state.ols = v; }, 'tb')),
        grp('Rainbow',
          sw('Bag', () => state.panels.bag, (v) => { state.panels.bag = v; }, 'rb'),
          sw('PnL', () => state.panels.pnl, (v) => { state.panels.pnl = v; }, 'rb'),
          sw('Exposition', () => state.panels.expo, (v) => { state.panels.expo = v; }, 'wallet')));
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
      bandSpec.forEach(([k]) => S[k].applyOptions({ visible: state.bands && hasRb }));
      trendBandSpec.forEach(([k]) => S[k].applyOptions({ visible: state.bands && hasTb }));
      S.candle.setMarkers(state.markers ? markers() : []);
      if (shade) { shade.opts.inactive = state.inactive; shade.opts.phases = state.phases; price.chart.timeScale().applyOptions({}); }
      bag.box.hidden = !(hasRb && state.panels.bag);
      pnl.box.hidden = !(hasRb && state.panels.pnl);
      expo.box.hidden = !(hasWallet && state.panels.expo);
      const P = state.panels;
      score.box.hidden = !(hasTb && P.score);
      rawT.box.hidden = !(hasTb && P.raw);
      regT.box.hidden = !(hasTb && (P.score || P.raw));
      smaT.box.hidden = !(hasTb && P.sma);
      score.series.sub.forEach((q) => q.applyOptions({ visible: hasTb && P.sub }));
      drawOls(olsIdx);
      const has = { band: hasRb || hasTb, rb: hasRb, wallet: hasWallet, tb: hasTb };
      toolbar.querySelectorAll('label[data-need]').forEach((l) => { const nd = l.dataset.need; l.hidden = !!nd && !has[nd]; });
      toolbar.querySelectorAll('.xc-group').forEach((gr) => { gr.hidden = ![...gr.querySelectorAll('label')].some((l) => !l.hidden); });
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
          row('Position (bag)', F.num(cl('pos'), 6) + ' (réserve moon ' + F.num(cl('reserve'), 6) + ')'),
          row('Wallet actif / stable / total', data.wallet ? F.num(data.wallet.asset[i], 0) + ' / ' + F.num(stable, 0) + ' / ' + F.num(data.wallet.total[i], 0) : '—'),
          row('Investi / vendu', F.num(cl('invested'), 0) + ' / ' + F.num(cl('proceeds'), 0)),
          row('Réalisé', h('span', { class: TIO.sign(cl('realized')) }, F.num(cl('realized'), 0))), row('Potentiel', h('span', { class: TIO.sign(cl('potential')) }, F.num(cl('potential'), 0))),
          row('Total', h('span', { class: TIO.sign(cl('total')) }, F.num(cl('total'), 0))), row('DCA fixe (gain)', h('span', { class: TIO.sign(cl('fixedGain')) }, F.num(cl('fixedGain'), 0))));
      }
      TIO.mount(detail, h('div', { class: 'small muted' }, 'Détail du jour (survol ou clic)'), h('div', { class: 'scroll-x' }, h('table', { class: 'tbl' }, h('tbody', null, rows))));
    }

    buildToolbar();
    return {
      update(dto) {
        data = dto && dto.d && dto.d.length ? dto : null;
        if (shade) { S.candle.detachPrimitive(shade); shade = null; }
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
          if (ttl) ttl.textContent = 'Score de régression · seuils ENTER ±' + F.num(tp.enter, 2) + ' (tirets), EXIT ±' + F.num(tp.exit, 2) + ' (pointillés)';
        } else { ['tSma', 'tUp', 'tDn'].forEach((k) => S[k].setData([])); regT.series.bar.setData([]); rawT.series.bar.setData([]); smaT.series.bar.setData([]); score.series.hist.setData([]); score.series.sub.forEach((q) => q.setData([])); }
        if (c.pos) {
          bag.series.pos.setData(series(c.pos)); bag.series.reserve.setData(series(c.reserve));
          pnl.series.realized.setData(series(c.realized)); pnl.series.potential.setData(series(c.potential)); pnl.series.total.setData(series(c.total)); pnl.series.fixed.setData(series(c.fixedGain));
        }
        if (data.wallet) {
          expo.series.asset.setData(series(data.wallet.asset)); expo.series.stable.setData(series(data.wallet.stable)); expo.series.total.setData(series(data.wallet.total));
          expo.series.capital.setData(data.d.map((t) => ({ time: t, value: data.wallet.capital })));
        }
        shade = new ShadePrimitive(price.chart, data.d, data.trend, data.active, { off: rgba(C.muted, 0.16), up: rgba(C.up, 0.07), down: rgba(C.down, 0.07) });
        S.candle.attachPrimitive(shade);
        buildLegend();
        all.forEach((p) => p.chart.timeScale().fitContent());
        apply();
        showDay(data.d.length - 1);
        drawOls(data.d.length - 1);
      },
      destroy() { all.forEach((p) => p.chart.remove()); },
    };
  };
})(window);
