/* Fiche actif : graphique (cours, bandes Rainbow, achats/ventes, prix moyen), frise de décision, « Pourquoi ce montant ? ». */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, F = TIO.fmt;
  const PERIODS = [{ value: '7d', label: '7 j' }, { value: '30d', label: '30 j' }, { value: '90d', label: '90 j' }, { value: '1y', label: '1 an' }, { value: 'all', label: 'Tout' }];

  const { pipeline, why } = TIO.explain;

  function decisionRow(d, selected, onPick) {
    const t = d.action;
    const chip = t === 'BUY' ? ui.chip('▲ Achat ' + F.usd(d.amountUsdc), 'ok') : t === 'SELL' ? ui.chip('▽ Vente', 'down')
      : t === 'BLOCKED' ? ui.chip('Bloqué · ' + TIO.blockLabel(d.blockReason), 'warn') : ui.chip('Rien à faire', 'plain');
    return h('button', { class: 'item', type: 'button', 'aria-pressed': String(selected), style: { background: 'transparent', border: '0', 'border-top': '1px solid var(--line)', width: '100%', 'text-align': 'left', cursor: 'pointer', color: 'inherit' }, onclick: onPick },
      h('span', { class: 'num', style: { width: '64px' } }, F.day(d.day)), chip, h('span', { class: 'grow' }), selected ? h('span', { class: 'chip chip--accent chip--plain' }, 'Détail ci-dessous') : null);
  }

  async function loadAll(sym) {
    const bindings = await TIO.get('/api/rainbow-live/bindings');
    const b = bindings.find((x) => x.assetSymbol === sym);
    if (!b) return { b: null };
    const [perf, decisions, plans, live, trades] = await Promise.all([
      TIO.safe(TIO.get('/api/portfolio/assets'), []),
      TIO.safe(TIO.get('/api/rainbow-live/decisions' + TIO.qs({ assetSymbol: sym })), []),
      TIO.safe(TIO.get('/api/rainbow-live/execution-plans'), []),
      TIO.safe(TIO.get('/api/rainbow-live/live-wallet'), []),
      TIO.safe(TIO.get('/api/rainbow-live/bindings/' + b.id + '/transactions'), null),
    ]);
    return { b, perf: perf.find((p) => p.assetSymbol === sym) || null, decisions, plans, live: live.find((l) => l.assetSymbol === sym) || null, trades };
  }

  function render(sym, data) {
    const { b, perf, decisions, plans, trades } = data;
    if (!b) return ui.empty('Actif non suivi', sym + ' n\'est relié à aucun exchange.', h('a', { class: 'btn btn--primary', href: TIO.href('/app/onboarding') }, 'Démarrer'));

    const state = { period: '90d', simulated: false, selected: decisions[0] || null, chart: null };
    const planFor = (d) => (d ? plans.find((p) => p.day === d.day) || null : null);

    // --- en-tête ---
    const headCard = ui.card(null, h('div', { class: 'row' }, ui.coin(sym), h('div', { class: 'grow' }, h('div', { class: 'num value-lg' }, sym), h('div', { class: 'muted small' }, b.walletName + (b.exchange ? ' · ' + b.exchange : ''))),
      ui.statusChip(b.tradability, null)),
      h('div', { class: 'stats' }, ui.stat('Cours', F.price(perf && perf.price)), ui.stat('Position', F.qty(perf && perf.quantity)), ui.stat('Valeur', F.usd(perf && perf.value, 0)),
        ui.stat('PnL', h('span', { class: TIO.sign(perf && perf.pnl) }, perf ? F.signedUsd(perf.pnl, 0) + ' (' + F.signedPct(perf.pnlPct) + ')' : F.dash)), ui.stat('Prix moyen', F.price(perf && perf.avgBuyPrice))),
      TIO.charts.priceVsAvg(perf && perf.price, perf && perf.avgBuyPrice), ui.cardOpts({ hero: true, span: true }));

    // --- graphique ---
    const chartBox = h('div', { id: 'asset-chart', tabindex: '0', 'aria-label': 'Graphique du cours ' + sym });
    const status = h('div', { class: 'row small muted' });
    const refreshBands = async () => {
      TIO.mount(status, 'Chargement…');
      try {
        const bands = await TIO.get('/api/rainbow-live/bindings/' + b.id + '/bands' + TIO.qs({ period: state.period, simulated: state.simulated ? 'true' : null }));
        if (state.chart) state.chart.update(bands, trades, perf && perf.avgBuyPrice);
        TIO.mount(table, h('div', { class: 'scroll-x' }, h('table', { class: 'tbl' }, h('thead', null, h('tr', null, ['Jour', 'Cours', 'Moyenne', 'Zone'].map((t) => h('th', null, t)))),
          h('tbody', null, (bands.points || []).slice(-30).reverse().map((p) => h('tr', null, h('td', null, F.dayLong(p.day)), h('td', null, F.price(p.close)), h('td', null, F.price(p.sma)), h('td', null, TIO.isNum(p.zone) ? String(p.zone) : F.dash)))))));
        TIO.mount(status, bands.points && bands.points.length ? [ui.chip(state.simulated ? 'Bandes simulées (recalculées, non figées)' : 'Bandes figées (calculées à chaque passe)', state.simulated ? 'warn' : 'plain'),
          h('span', null, '▲ achat · ▽ vente · ┆ prix moyen · Shift-clic ×2 : mesurer')] : 'Aucune donnée de bandes sur cette période.');
      } catch (e) { TIO.mount(status, ui.error(e, refreshBands)); }
    };
    const sim = ui.switchEl({ label: 'Afficher les bandes simulées', checked: false, onChange: (on) => { state.simulated = on; refreshBands(); } });
    const table = h('div');
    const chartCard = ui.card('Cours et bandes Rainbow',
      h('div', { class: 'row row--between' }, ui.periods(PERIODS, state.period, (v) => { state.period = v; refreshBands(); }),
        h('label', { class: 'row small' }, sim.el, h('span', null, 'Bandes simulées'))), chartBox, status, h('details', null, h('summary', { class: 'small' }, 'Données du graphique (tableau)'), table), ui.cardOpts({ span: true }));

    // --- décisions / frise / pourquoi ---
    const pipeBox = h('div', { class: 'stack' }), whyBox = h('div', { class: 'stack' }), listBox = h('div', { class: 'list' });
    const paint = () => {
      const d = state.selected;
      TIO.mount(listBox, decisions.slice(0, 10).map((x) => decisionRow(x, x === d, () => { state.selected = x; paint(); })));
      TIO.mount(pipeBox, d ? [h('div', { class: 'muted small' }, 'Décision du ' + F.dayLong(d.day)), pipeline(sym, d, planFor(d))] : ui.empty('Aucune décision', 'La première décision arrive après la prochaine passe.'));
      TIO.mount(whyBox, d ? why(sym, d) : ui.empty('Aucune décision'));
    };
    paint();

    const grid = h('div', { class: 'grid grid--2 span-all' },
      ui.card('Dernières décisions', listBox, decisions.length ? null : ui.empty('Aucune décision', 'Elles apparaissent après la première passe.')),
      ui.card('Pourquoi ce montant ?', whyBox),
      ui.card('Frise de décision', pipeBox, ui.cardOpts({ span: true })));

    g.requestAnimationFrame(() => { state.chart = TIO.assetChart(chartBox); refreshBands(); });
    return [headCard, chartCard, grid,
      TIO.modeAtLeast('expert') ? h('p', { class: 'note span-all' }, 'Stratégie et presets de cet actif : ', h('a', { href: '/user' }, 'espace Expert')) : null];
  }

  TIO.start(function (root) {
    const sym = document.body.dataset.symbol;
    const box = h('div', { class: 'grid' });
    ui.load(box, () => loadAll(sym), (data) => render(sym, data));
    TIO.mount(root, ui.pageHeader(sym), ui.demoBanner(), h('a', { href: TIO.href('/app/assets'), class: 'small' }, '← Mes actifs'), box);
  });
})(window);
