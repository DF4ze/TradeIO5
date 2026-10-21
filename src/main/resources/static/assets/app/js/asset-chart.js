/* Graphique de la fiche actif (TradingView Lightweight Charts v4) : cours, bandes Rainbow (figées ou simulées), achats/ventes, prix moyen,
   mesure Shift-clic (% et durée). Couleurs lues dans les variables CSS de la charte (jamais en dur). */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h;
  const LW = g.LightweightCharts;

  const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  function rgba(hex, a) {
    const m = /^#([0-9a-f]{6})$/i.exec(hex.trim());
    if (!m) return hex;
    const n = parseInt(m[1], 16);
    return 'rgba(' + (n >> 16) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + a + ')';
  }
  function dayOf(t) {
    if (typeof t === 'string') return t;
    if (typeof t === 'number') return new Date(t * 1000).toISOString().slice(0, 10);
    if (t && t.year) return t.year + '-' + String(t.month).padStart(2, '0') + '-' + String(t.day).padStart(2, '0');
    return null;
  }
  const dayDiff = (a, b) => Math.round((Date.parse(b + 'T00:00:00Z') - Date.parse(a + 'T00:00:00Z')) / 86400000);

  /** Primitive : zones de dégradé léger entre les bornes de bande ; bornes en pointillé quand les bandes sont simulées. */
  class BandsPrimitive {
    constructor(chart, series, points, simulated) {
      this.chart = chart; this.series = series; this.points = points; this.simulated = simulated;
      const self = this;
      this.view = {
        zOrder: () => 'bottom',
        renderer: () => ({ draw: (target) => self.draw(target) }),
      };
    }
    attached() {}
    detached() {}
    updateAllViews() {}
    paneViews() { return [this.view]; }
    draw(target) {
      const ts = this.chart.timeScale();
      const keys = ['boundDown2', 'boundDown1', 'boundUp1', 'boundUp2', 'boundUp3'];
      const cols = ['--s6', '--s5', '--s4', '--s3', '--s2', '--s1'].map((v) => css(v));
      target.useMediaCoordinateSpace(({ context: ctx }) => {
        const xs = [], ys = keys.map(() => []), lowY = [], highY = [];
        for (const p of this.points) {
          const x = ts.timeToCoordinate(p.day);
          if (x === null || keys.some((k) => !TIO.isNum(p[k]))) continue;
          xs.push(x);
          keys.forEach((k, i) => ys[i].push(this.series.priceToCoordinate(p[k])));
          lowY.push(this.series.priceToCoordinate(p.boundDown2 - (p.atr || 0)));
          highY.push(this.series.priceToCoordinate(p.boundUp3 + (p.atr || 0)));
        }
        if (xs.length < 2 || ys.some((col) => col.some((y) => y === null)) || lowY.includes(null) || highY.includes(null)) return;
        const edge = (i, idx) => ys[i][idx];
        const lo = (idx) => lowY[idx];
        const hi = (idx) => highY[idx];
        const region = (k) => {
          const top = k === 5 ? (i) => hi(i) : (i) => edge(k, i);
          const bottom = k === 0 ? (i) => lo(i) : (i) => edge(k - 1, i);
          ctx.beginPath();
          xs.forEach((x, i) => (i ? ctx.lineTo(x, top(i)) : ctx.moveTo(x, top(i))));
          for (let i = xs.length - 1; i >= 0; i--) ctx.lineTo(xs[i], bottom(i));
          ctx.closePath();
          ctx.fillStyle = rgba(cols[k], k === 2 ? 0.07 : 0.11);
          ctx.fill();
        };
        for (let k = 0; k < 6; k++) region(k);
        ctx.lineWidth = 1;
        if (this.simulated) ctx.setLineDash([5, 4]);
        keys.forEach((_, i) => {
          ctx.beginPath();
          xs.forEach((x, idx) => (idx ? ctx.lineTo(x, ys[i][idx]) : ctx.moveTo(x, ys[i][idx])));
          ctx.strokeStyle = rgba(cols[i + 1] || cols[i], 0.55);
          ctx.stroke();
        });
        ctx.setLineDash([]);
      });
    }
  }

  /**
   * Monte le graphique dans `container`. Retourne { update(bands, trades, avgBuy), destroy() }.
   * bands : RainbowBandsDto ; trades : BindingTradesDto.
   */
  TIO.assetChart = function (container) {
    if (!LW) { TIO.mount(container, TIO.ui.error(new Error('Bibliothèque de graphique indisponible'))); return null; }
    const colors = { text: css('--text'), muted: css('--muted'), line: css('--line'), up: css('--up'), down: css('--down'), accent: css('--accent') };
    const chart = LW.createChart(container, {
      width: container.clientWidth, height: container.clientHeight || 340, autoSize: false,
      layout: { background: { type: 'solid', color: 'transparent' }, textColor: colors.muted, fontFamily: 'Manrope, system-ui, sans-serif', fontSize: 11 },
      grid: { vertLines: { color: colors.line }, horzLines: { color: colors.line } },
      rightPriceScale: { borderColor: colors.line }, timeScale: { borderColor: colors.line, timeVisible: false },
      crosshair: { mode: LW.CrosshairMode.Normal },
    });
    const price = chart.addLineSeries({ color: colors.text, lineWidth: 2, priceLineVisible: false, lastValueVisible: true });
    const measureSeries = chart.addLineSeries({ color: colors.accent, lineWidth: 2, lineStyle: LW.LineStyle.Dashed, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false });
    let primitive = null, avgLine = null;

    const ro = new ResizeObserver(() => chart.applyOptions({ width: container.clientWidth }));
    ro.observe(container);

    // ---- mesure Shift-clic : deux clics = % et durée ; troisième clic ou Échap = effacer ----
    const badge = h('div', { class: 'tip hidden', style: { left: '50%', top: '12px', transform: 'translateX(-50%)' }, role: 'status' });
    container.style.position = 'relative';
    container.appendChild(badge);
    let anchor = null;
    function point(e) {
      const r = container.getBoundingClientRect();
      const t = chart.timeScale().coordinateToTime(e.clientX - r.left), p = price.coordinateToPrice(e.clientY - r.top);
      const day = dayOf(t);
      return day && TIO.isNum(p) ? { day, price: p } : null;
    }
    function clearMeasure() { anchor = null; measureSeries.setData([]); badge.classList.add('hidden'); }
    container.addEventListener('click', (e) => {
      if (!e.shiftKey) return;
      const pt = point(e);
      if (!pt) return;
      if (!anchor) { anchor = pt; measureSeries.setData([{ time: pt.day, value: pt.price }]); badge.classList.add('hidden'); return; }
      const pair = [anchor, pt].sort((a, b) => (a.day < b.day ? -1 : a.day > b.day ? 1 : 0));
      if (pair[0].day === pair[1].day) { clearMeasure(); return; }
      measureSeries.setData(pair.map((q) => ({ time: q.day, value: q.price })));
      const pct = (pair[1].price / pair[0].price - 1) * 100;
      TIO.mount(badge, h('div', { class: 'num ' + TIO.sign(pct) }, (pct > 0 ? '▲ ' : '▽ ') + TIO.fmt.signedPct(pct)), h('div', { class: 'muted' }, dayDiff(pair[0].day, pair[1].day) + ' j'));
      badge.classList.remove('hidden');
      anchor = null;
    });
    container.addEventListener('keydown', (e) => { if (e.key === 'Escape') clearMeasure(); });
    container.addEventListener('dblclick', clearMeasure);

    return {
      update(bands, trades, avgBuy) {
        clearMeasure();
        const pts = (bands && bands.points) || [];
        price.setData(pts.filter((p) => TIO.isNum(p.close)).map((p) => ({ time: p.day, value: p.close })));
        if (primitive) { price.detachPrimitive(primitive); primitive = null; }
        if (pts.length) { primitive = new BandsPrimitive(chart, price, pts, !!(bands && bands.simulated)); price.attachPrimitive(primitive); }
        if (avgLine) { price.removePriceLine(avgLine); avgLine = null; }
        if (TIO.isNum(avgBuy)) avgLine = price.createPriceLine({ price: avgBuy, color: colors.muted, lineWidth: 1, lineStyle: LW.LineStyle.Dotted, axisLabelVisible: true, title: 'Prix moyen' });
        const first = pts.length ? pts[0].day : null;
        const markers = ((trades && trades.trades) || []).map((t) => ({ day: String(t.timestamp).slice(0, 10), side: t.side }))
          .filter((t) => !first || t.day >= first)
          .sort((a, b) => (a.day < b.day ? -1 : a.day > b.day ? 1 : 0))
          .map((t) => (t.side === 'SELL'
            ? { time: t.day, position: 'aboveBar', color: colors.down, shape: 'arrowDown', text: '' }
            : { time: t.day, position: 'belowBar', color: colors.up, shape: 'arrowUp', text: '' }));
        price.setMarkers(markers);
        chart.timeScale().fitContent();
      },
      destroy() { ro.disconnect(); chart.remove(); },
    };
  };
})(window);
