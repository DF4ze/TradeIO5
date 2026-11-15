/* Espace Expert · Simulateur : graphiques synchronisés du rejeu libre (aucune logique métier : tout vient du résultat serveur).
   Panneaux : wallet vs DCA fixe · exposition · prix de l'actif choisi (ordres, phases) · piste d'épisodes colorée par PnL. */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, F = TIO.fmt;
  const LW = g.LightweightCharts;

  const css = (n, d) => (g.getComputedStyle(g.document.body).getPropertyValue(n) || '').trim() || d;
  const times = (d) => d.map((x) => String(x).slice(0, 10));

  const PROFILE_LABELS = { PRUDENT: 'Prudent', BALANCED: 'Équilibré', DYNAMIC: 'Dynamique', FULL: 'Full' };
  const pctTxt = (v) => F.pct(v, 1);

  /** Texte du verdict du profil choisi (verdicts calculés par le serveur ; aucun recalcul ici). */
  function verdictText(res, profile) {
    const dd = res && res.drawdown; if (!dd) return '';
    const m = dd.strategyMeasure;
    const head = 'Drawdown max ' + pctTxt(m.maxPct) + (m.troughDay ? ' le ' + F.dayLong(m.troughDay) : '');
    const v = profile && dd.verdicts.find((x) => x.profile === profile);
    if (!v) return head + ' · choisissez un profil pour comparer au plafond';
    return head + ', plafond ' + PROFILE_LABELS[v.profile] + ' ' + pctTxt(v.capPct) + ' : ' + (v.respected ? 'respecté' : 'dépassé le ' + F.dayLong(v.firstBreachDay) + ' (jusqu\'à ' + F.num(v.maxOverrunPts, 1) + ' pts au-delà)');
  }

  /** @returns {{update(res), selectAsset(sym), highlight(from,to), clearHighlight(), zoomTo(from,to), onRange(cb), onEpisodeHover(cb), onEpisodeClick(cb), setEpisodes(list), assetSymbol()}} */
  TIO.simChart = function (container) {
    const st = { profile: null, profileCb: null, res: null, asset: null, charts: [], series: {}, days: [], episodes: [], hl: null, rangeCb: null, hoverCb: null, clickCb: null, busy: false };
    const sel = h('select', { 'aria-label': 'Actif affiché' });
    const box = h('div', { class: 'xsim' });
    const panels = {};
    const mk = (key, title, height) => {
      const body = h('div', { class: 'xsim__body', style: { height: height + 'px' } });
      panels[key] = body;
      return h('div', { class: 'xsim__panel' }, h('div', { class: 'xsim__title small muted' }, title), body);
    };
    const track = h('div', { class: 'xsim__track', role: 'img', 'aria-label': 'Épisodes colorés par PnL' });
    const pricePanel = mk('price', 'Prix · ordres', 260);
    const priceTitle = pricePanel.firstChild;
    priceTitle.appendChild(sel);
    const profSel = h('select', { 'aria-label': 'Profil de plafond' });
    const ddInfo = h('span', { class: 'xsim__info' });
    const ddPanel = mk('dd', 'Drawdown (%) · signalement seul, aucun effet sur les ordres ', 140);
    ddPanel.firstChild.append(profSel, ddInfo);
    box.append(mk('wallet', 'Wallet stratégie vs DCA fixe (USDC)', 200), ddPanel, mk('expo', 'Exposition (%)', 120), pricePanel,
      h('div', { class: 'xsim__panel' }, h('div', { class: 'xsim__title small muted' }, 'Épisodes (couleur = PnL)'), track));
    TIO.mount(container, box);

    function destroy() { st.charts.forEach((c) => { try { c.remove(); } catch (e) { /* ignoré */ } }); st.charts = []; st.series = {}; }
    function baseOpts(el) {
      return { width: el.clientWidth || 600, height: el.clientHeight || 200, layout: { background: { color: 'transparent' }, textColor: css('--muted', '#8a94a6') },
        grid: { vertLines: { color: 'rgba(128,128,128,.12)' }, horzLines: { color: 'rgba(128,128,128,.12)' } }, rightPriceScale: { minimumWidth: 70 },
        timeScale: { borderVisible: false, rightOffset: 2 }, crosshair: { mode: 0 } };
    }
    function line(chart, color, title, w) { return chart.addLineSeries({ color, lineWidth: w || 2, title, priceLineVisible: false, lastValueVisible: true }); }
    const data = (days, arr) => days.map((d, i) => (arr[i] === null || arr[i] === undefined ? { time: d } : { time: d, value: Number(arr[i]) }));

    function build() {
      destroy();
      const r = st.res; if (!r || !LW) return;
      const s = r.series, days = times(s.d); st.days = days;
      const up = css('--up', '#26a69a'), down = css('--down', '#ef5350'), accent = css('--accent', '#4c8dff'), muted = css('--muted', '#8a94a6');
      const cW = LW.createChart(panels.wallet, baseOpts(panels.wallet)), cE = LW.createChart(panels.expo, baseOpts(panels.expo)), cP = LW.createChart(panels.price, baseOpts(panels.price));
      const cD = LW.createChart(panels.dd, baseOpts(panels.dd));
      st.charts = [cW, cE, cP, cD];
      const dd = r.drawdown;
      if (dd) {
        st.ddBase = cD.addBaselineSeries({ baseValue: { type: 'price', price: -100 }, topLineColor: accent, topFillColor1: 'rgba(76,141,255,.20)', topFillColor2: 'rgba(76,141,255,.02)',
          bottomLineColor: down, bottomFillColor1: 'rgba(239,83,80,.50)', bottomFillColor2: 'rgba(239,83,80,.15)', lineWidth: 2, priceLineVisible: false, title: 'Stratégie' });
        st.ddBase.setData(data(days, dd.strategy));
        line(cD, muted, 'DCA fixe', 1).setData(data(days, dd.fixed));
        st.ddCap = cD.addLineSeries({ color: down, lineWidth: 1, lineStyle: 2, priceLineVisible: false, lastValueVisible: true, title: 'Plafond' });
        paintCap();
        const showInfo = (p) => {
          const i = p && p.time ? days.indexOf(String(p.time)) : -1;
          ddInfo.textContent = i < 0 ? '' : ' · ' + F.dayLong(days[i]) + ' : stratégie ' + pctTxt(dd.strategy[i]) + ' · DCA fixe ' + pctTxt(dd.fixed[i]);
        };
        cD.subscribeCrosshairMove(showInfo); cW.subscribeCrosshairMove(showInfo);
      }
      line(cW, accent, 'Stratégie').setData(data(days, s.wallet));
      line(cW, muted, 'DCA fixe', 1).setData(data(days, s.fixed));
      const exp = days.map((d, i) => ({ time: d, value: 100 - Number(s.cashPct[i] || 0) }));
      cE.addAreaSeries({ lineColor: accent, topColor: 'rgba(76,141,255,.35)', bottomColor: 'rgba(76,141,255,.02)', lineWidth: 2, priceLineVisible: false }).setData(exp);
      cE.priceScale('right').applyOptions({ autoScale: false, scaleMargins: { top: 0.05, bottom: 0.05 } });
      cE.addLineSeries({ color: 'transparent', lineWidth: 1, priceLineVisible: false, lastValueVisible: false }).setData([{ time: days[0], value: 0 }, { time: days[days.length - 1], value: 100 }]);
      st.series = { price: cP.addCandlestickSeries({ upColor: up, downColor: down, wickUpColor: up, wickDownColor: down, borderVisible: false }) };
      drawAsset();
      // synchronisation des échelles de temps + curseur
      let lock = false;
      st.charts.forEach((c, i) => c.timeScale().subscribeVisibleLogicalRangeChange((range) => {
        if (lock || !range) return;
        lock = true; st.charts.forEach((o, j) => { if (j !== i) o.timeScale().setVisibleLogicalRange(range); }); lock = false;
        drawTrack(); emitRange();
      }));
      const mainSeries = [cW, cE, cP];
      st.charts.forEach((c, i) => c.subscribeCrosshairMove((p) => {
        if (!p.time || typeof c.setCrosshairPosition !== 'function') { st.charts.forEach((o, j) => { if (j !== i && o.clearCrosshairPosition) o.clearCrosshairPosition(); }); return; }
        st.charts.forEach((o, j) => { if (j !== i && o.setCrosshairPosition) { const ser = j === 2 ? st.series.price : null; if (ser) o.setCrosshairPosition(NaN, p.time, ser); } });
      }));
      void mainSeries;
      cW.timeScale().fitContent();
      g.setTimeout(drawTrack, 50);
    }

    /** Plafond du profil choisi : ligne horizontale + zone colorée au-delà ; sans profil, ni l'un ni l'autre. */
    function paintCap() {
      const dd = st.res && st.res.drawdown; if (!dd || !st.ddBase) return;
      const v = st.profile && dd.verdicts.find((x) => x.profile === st.profile);
      st.ddBase.applyOptions({ baseValue: { type: 'price', price: v ? v.capPct : -100 } });
      st.ddCap.setData(v ? st.days.map((d) => ({ time: d, value: v.capPct })) : []);
      st.ddCap.applyOptions({ title: v ? 'Plafond ' + PROFILE_LABELS[v.profile] : 'Plafond' });
    }

    function drawAsset() {
      const r = st.res; if (!r || !st.series.price) return;
      const a = r.assets.find((x) => x.asset === st.asset) || r.assets[0]; if (!a) return;
      st.asset = a.asset;
      const days = st.days;
      st.series.price.setData(days.map((d, i) => (a.c[i] === null || a.c[i] === undefined ? { time: d } : { time: d, open: +a.o[i], high: +a.h[i], low: +a.l[i], close: +a.c[i] })));
      const up = css('--up', '#26a69a'), down = css('--down', '#ef5350');
      const idx = new Map(days.map((d, i) => [d, i]));
      const markers = (a.orders || []).filter((o) => idx.has(String(o.day).slice(0, 10))).map((o) => {
        const buy = o.side === 'BUY', ok = o.outcome === 'OK';
        return { time: String(o.day).slice(0, 10), position: buy ? 'belowBar' : 'aboveBar', shape: buy ? 'arrowUp' : 'arrowDown',
          color: ok ? (buy ? up : down) : '#9aa0a6', text: ok ? '' : (o.outcome === 'BELOW_MIN' ? 'min' : 'bloqué') };
      }).sort((x, y) => (x.time < y.time ? -1 : 1));
      st.series.price.setMarkers(markers);
      drawTrack();
    }

    const pnlColor = (p, max) => {
      const k = Math.min(1, Math.abs(p) / (max || 1)), a = (0.25 + 0.65 * k).toFixed(2);
      return p >= 0 ? 'rgba(38,166,154,' + a + ')' : 'rgba(239,83,80,' + a + ')';
    };
    function xOf(day) {
      const ts = st.charts[0] && st.charts[0].timeScale(); if (!ts) return null;
      const d = String(day).slice(0, 10);
      let i = st.days.indexOf(d);
      if (i < 0) { i = st.days.findIndex((x) => x >= d); if (i < 0) i = st.days.length - 1; }
      return ts.timeToCoordinate(st.days[i]);
    }
    function drawTrack() {
      TIO.mount(track);
      if (!st.charts.length) return;
      const w = track.clientWidth;
      const list = st.episodes.filter((e) => !st.asset || e.asset === st.asset);
      const max = Math.max(1e-9, ...list.map((e) => Math.abs(e.pnlTotal)));
      list.forEach((e) => {
        const x0 = xOf(e.from), x1 = xOf(e.to);
        if (x0 === null || x1 === null) return;
        const left = Math.min(x0, x1), width = Math.max(2, Math.abs(x1 - x0) + 3);
        if (left + width < 0 || left > w) return;
        const seg = h('div', { class: 'xsim__seg' + (st.hl === e ? ' is-hl' : ''), style: { left: left + 'px', width: width + 'px', background: pnlColor(e.pnlTotal, max) },
          title: e.asset + ' · ' + e.regime + ' · ' + e.zoneLabel + ' · ' + F.dayLong(e.from) + ' → ' + F.dayLong(e.to) + ' · PnL ' + F.usd(e.pnlTotal, 2) });
        seg.addEventListener('mouseenter', () => st.hoverCb && st.hoverCb(e));
        seg.addEventListener('mouseleave', () => st.hoverCb && st.hoverCb(null));
        seg.addEventListener('click', () => st.clickCb && st.clickCb(e));
        track.appendChild(seg);
      });
    }
    function visibleDays() {
      const ts = st.charts[0] && st.charts[0].timeScale(); if (!ts || !st.days.length) return null;
      const r = ts.getVisibleLogicalRange(); if (!r) return null;
      const a = Math.max(0, Math.ceil(r.from)), b = Math.min(st.days.length - 1, Math.floor(r.to));
      return b >= a ? { from: st.days[a], to: st.days[b] } : null;
    }
    function emitRange() { const v = visibleDays(); if (v && st.rangeCb) st.rangeCb(v); }

    profSel.addEventListener('change', () => { st.profile = profSel.value || null; paintCap(); if (st.profileCb) st.profileCb(st.profile); });
    sel.addEventListener('change', () => { st.asset = sel.value; drawAsset(); });
    g.addEventListener('resize', () => st.charts.forEach((c, i) => { const el = panels[['wallet', 'expo', 'price', 'dd'][i]]; if (el) c.applyOptions({ width: el.clientWidth }); drawTrack(); }));

    return {
      update(res) {
        st.res = res; st.episodes = (res.episodes && res.episodes.episodes) || [];
        const keep = st.asset && res.assets.some((a) => a.asset === st.asset) ? st.asset : (res.assets[0] ? res.assets[0].asset : null);
        st.asset = keep;
        TIO.mount(sel, res.assets.map((a) => h('option', { value: a.asset }, a.asset)));
        if (keep) sel.value = keep;
        const dd = res.drawdown;
        ddPanel.hidden = !dd;
        st.profile = dd ? dd.defaultProfile || null : null;
        TIO.mount(profSel, [h('option', { value: '' }, 'Profil : aucun')].concat((dd ? dd.verdicts : []).map((v) => h('option', { value: v.profile }, PROFILE_LABELS[v.profile] + ' ' + pctTxt(v.capPct)))));
        profSel.value = st.profile || '';
        build();
      },
      selectAsset(sym) { st.asset = sym; sel.value = sym; drawAsset(); },
      assetSymbol: () => st.asset,
      setEpisodes(list) { st.episodes = list || []; drawTrack(); },
      highlight(e) { st.hl = e; drawTrack(); },
      zoomTo(from, to) {
        if (!st.charts.length) return;
        const a = Math.max(0, st.days.findIndex((d) => d >= String(from).slice(0, 10)));
        let b = st.days.findIndex((d) => d > String(to).slice(0, 10)); b = b < 0 ? st.days.length - 1 : b - 1;
        const pad = Math.max(3, Math.round((b - a) * 0.15));
        st.charts[0].timeScale().setVisibleLogicalRange({ from: a - pad, to: b + pad });
      },
      onRange(cb) { st.rangeCb = cb; },
      profile: () => st.profile,
      onProfile(cb) { st.profileCb = cb; },
      onEpisodeHover(cb) { st.hoverCb = cb; },
      onEpisodeClick(cb) { st.clickCb = cb; },
      visible: visibleDays,
      destroy,
    };
  };
  TIO.simChart.verdictText = verdictText;
})(window);
