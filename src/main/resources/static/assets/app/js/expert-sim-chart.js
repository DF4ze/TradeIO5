/* Espace Expert · Simulateur : graphiques wallet du rejeu libre (le prix, les bornes, les ordres, le bag, le PnL et l'exposition par actif viennent de expert-chart.js).
   Panneaux : wallet vs DCA fixe · drawdown · piste d'épisodes colorée par PnL (vignette de zone au survol). */
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
    const box = h('div', { class: 'xsim' });
    const panels = {};
    const mk = (key, title, height) => {
      const body = h('div', { class: 'xsim__body', style: { height: height + 'px' } });
      panels[key] = body;
      return h('div', { class: 'xsim__panel' }, h('div', { class: 'xsim__title small muted' }, title), body);
    };
    const tip = h('div', { class: 'xsim__tip', hidden: true, role: 'tooltip' });
    const track = h('div', { class: 'xsim__track', role: 'img', 'aria-label': 'Épisodes colorés par PnL' });
    const profSel = h('select', { 'aria-label': 'Profil de plafond' });
    const ddInfo = h('span', { class: 'xsim__info' });
    const ddPanel = mk('dd', 'Drawdown (%) · signalement seul, aucun effet sur les ordres ', 140);
    ddPanel.firstChild.append(profSel, ddInfo);
    box.append(mk('wallet', 'Wallet stratégie vs DCA fixe (USDC)', 200), ddPanel,
      h('div', { class: 'xsim__panel xsim__panel--track' }, h('div', { class: 'xsim__title small muted' }, 'Épisodes (couleur = PnL · survol = détail de la zone)'), track, tip));
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
      const cW = LW.createChart(panels.wallet, baseOpts(panels.wallet)), cD = LW.createChart(panels.dd, baseOpts(panels.dd));
      st.charts = [cW, cD];
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
      // synchronisation des échelles de temps + curseur
      let lock = false;
      st.charts.forEach((c, i) => c.timeScale().subscribeVisibleLogicalRangeChange((range) => {
        if (lock || !range) return;
        lock = true; st.charts.forEach((o, j) => { if (j !== i) o.timeScale().setVisibleLogicalRange(range); }); lock = false;
        drawTrack(); emitRange();
      }));
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

    /** L'actif affiché filtre la piste d'épisodes (le prix et les ordres sont dans le graphique Rainbow, hors de ce composant). */
    function drawAsset() {
      const r = st.res; if (!r) return;
      const a = r.assets.find((x) => x.asset === st.asset) || r.assets[0]; if (!a) return;
      st.asset = a.asset;
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
    // ---- vignette d'un épisode au survol (tout vient du serveur : EpisodeDto ; seuls les cours début / fin lisent la série du panneau prix)
    const usd = (v) => F.usd(v, Math.abs(v) >= 1000 ? 0 : 2);
    const signedUsd = (v) => (v > 0 ? '+' : '') + usd(v);
    const tipRow = (label, value, cls) => h('tr', null, h('td', { class: 'muted' }, label), h('td', { class: 'num' + (cls ? ' ' + cls : '') }, value));
    function priceAt(asset, day) {
      const a = st.res && st.res.assets.find((x) => x.asset === asset); if (!a) return null;
      const d = String(day).slice(0, 10);
      let i = st.days.indexOf(d); if (i < 0) i = st.days.findIndex((x) => x >= d);
      return i >= 0 && a.c[i] !== null && a.c[i] !== undefined ? Number(a.c[i]) : null;
    }
    function tipContent(e) {
      const p0 = priceAt(e.asset, e.from), p1 = priceAt(e.asset, e.to);
      const mv = p0 && p1 ? (p1 / p0 - 1) * 100 : null;
      const q = (v) => F.num(v, v >= 1 ? 4 : 6);
      const g = e.gain || {};
      return [h('div', { class: 'xsim__tiphead' }, h('strong', null, e.asset), ' · ', e.regime, ' · zone ', h('strong', null, e.zoneLabel)),
        h('div', { class: 'small muted' }, F.dayLong(e.from) + ' → ' + F.dayLong(e.to) + ' · ' + e.days + ' j'),
        h('table', { class: 'tbl xsim__tiptbl' }, h('tbody', null,
          p0 && p1 ? tipRow('Cours début → fin', F.price(p0) + ' → ' + F.price(p1) + (mv === null ? '' : ' (' + (mv > 0 ? '+' : '') + F.num(mv, 1) + ' %)')) : null,
          tipRow('Acheté', usd(e.boughtUsdc) + ' · ' + q(e.boughtQty)),
          tipRow('Vendu', usd(e.soldUsdc) + ' · ' + q(e.soldQty)),
          tipRow('Net investi', signedUsd(e.boughtUsdc - e.soldUsdc)),
          e.avgCostEnd === null || e.avgCostEnd === undefined ? null : tipRow('Prix de revient fin', F.price(e.avgCostEnd)),
          tipRow('PnL de la zone', signedUsd(e.pnlTotal), TIO.sign(e.pnlTotal)),
          tipRow('  dont réalisé', signedUsd(e.pnlRealized), TIO.sign(e.pnlRealized)),
          tipRow('  dont latent', signedUsd(e.pnlLatent), TIO.sign(e.pnlLatent)),
          e.exposureStartPct === null || e.exposureStartPct === undefined ? null : tipRow('Exposition début → fin', F.num(e.exposureStartPct, 1) + ' % → ' + (e.exposureEndPct === null || e.exposureEndPct === undefined ? '—' : F.num(e.exposureEndPct, 1) + ' %')),
          tipRow('Gain vs DCA fixe', signedUsd(g.total || 0), TIO.sign(g.total || 0)),
          tipRow('  exposition / timing / frais', signedUsd(g.exposure || 0) + ' / ' + signedUsd(g.timing || 0) + ' / ' + signedUsd(g.fees || 0))))];
    }
    function showTip(e, ev) {
      TIO.mount(tip, tipContent(e)); tip.hidden = false;
      const box = tip.parentNode.getBoundingClientRect(), r = ev.currentTarget.getBoundingClientRect();
      const w = tip.offsetWidth, x = r.left + r.width / 2 - box.left - w / 2;
      tip.style.left = Math.max(0, Math.min(box.width - w, x)) + 'px';
      tip.style.bottom = (box.bottom - r.top + 6) + 'px';
    }
    const hideTip = () => { tip.hidden = true; };

    function drawTrack() {
      TIO.mount(track);
      hideTip();
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
          'aria-label': e.asset + ' · ' + e.regime + ' · ' + e.zoneLabel + ' · ' + F.dayLong(e.from) + ' → ' + F.dayLong(e.to) + ' · PnL ' + F.usd(e.pnlTotal, 2) });
        seg.addEventListener('mouseenter', (ev) => { showTip(e, ev); if (st.hoverCb) st.hoverCb(e); });
        seg.addEventListener('mouseleave', () => { hideTip(); if (st.hoverCb) st.hoverCb(null); });
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
    g.addEventListener('resize', () => st.charts.forEach((c, i) => { const el = panels[['wallet', 'dd'][i]]; if (el) c.applyOptions({ width: el.clientWidth }); drawTrack(); }));

    return {
      update(res) {
        st.res = res; st.episodes = (res.episodes && res.episodes.episodes) || [];
        const keep = st.asset && res.assets.some((a) => a.asset === st.asset) ? st.asset : (res.assets[0] ? res.assets[0].asset : null);
        st.asset = keep;
        const dd = res.drawdown;
        ddPanel.hidden = !dd;
        st.profile = dd ? dd.defaultProfile || null : null;
        TIO.mount(profSel, [h('option', { value: '' }, 'Profil : aucun')].concat((dd ? dd.verdicts : []).map((v) => h('option', { value: v.profile }, PROFILE_LABELS[v.profile] + ' ' + pctTxt(v.capPct)))));
        profSel.value = st.profile || '';
        build();
      },
      selectAsset(sym) { st.asset = sym; drawAsset(); },
      mainChart: () => st.charts[0] || null,
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
