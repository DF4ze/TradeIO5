/* Accueil : alertes, patrimoine + PnL, décisions du jour, prochaine passe, cash et autonomie, prix moyen vs cours (Simple) ;
   exposition, zone libre, puissance, biais, reporté (Avancé). */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, F = TIO.fmt;

  const PERIODS = [{ value: '7d', label: '7 j' }, { value: '30d', label: '30 j' }, { value: '90d', label: '90 j' }, { value: '1y', label: '1 an' }, { value: 'all', label: 'Tout' }];
  const summary = (period) => TIO.get('/api/portfolio/summary?period=' + period);
  const settings = () => TIO.getOnce('/api/user/modulation-settings');
  const liveWallet = () => TIO.getOnce('/api/rainbow-live/live-wallet');
  const link = (symbol) => TIO.href('/app/assets/' + encodeURIComponent(symbol));

  function patrimoineCard() {
    const period = { v: '30d' };
    let card;
    const aside = ui.periods(PERIODS, period.v, (v) => { period.v = v; card.reload(); });
    card = ui.widget('Patrimoine', () => summary(period.v), (s) => {
      const cur = s.current, pnl = s.pnl;
      const chart = TIO.charts.area((s.series || []).map((p) => ({ x: p.day, y: p.total })), { label: 'Patrimoine' });
      return [
        h('div', { class: 'num value-xl' }, F.usd(cur && cur.total, 0)),
        pnl ? h('div', { class: 'row' },
          h('span', { class: 'num value-md ' + TIO.sign(pnl.amount) }, (pnl.amount > 0 ? '▲ ' : pnl.amount < 0 ? '▽ ' : '') + F.signedUsd(pnl.amount, 0)),
          h('span', { class: 'num ' + TIO.sign(pnl.pct) }, F.signedPct(pnl.pct)),
          h('span', { class: 'muted small' }, 'depuis le ' + F.day(pnl.since)), ui.pnlNote())
          : h('div', { class: 'muted' }, 'Variation : ' + F.dash + ' (historique en cours de constitution)'),
        chart || ui.empty('Historique en cours de constitution', 'La courbe démarre au premier jour de passe ; avant, les valeurs affichent « — ».'),
      ];
    }, { hero: true, span: true, aside });
    return card;
  }

  function actionChip(item) {
    const s = item.snapshot;
    if (!s) return ui.chip('Pas encore de passe', 'unknown');
    if (s.status && s.status !== 'OK') return ui.chip(s.status === 'UNAVAILABLE' ? 'Données indisponibles' : 'Non tradable', s.status === 'UNAVAILABLE' ? 'unknown' : 'down');
    const t = s.liveActionType;
    if (t === 'BUY') return ui.chip('▲ Achat ' + F.usd(s.liveActionAmountUsdc), 'ok');
    if (t === 'SELL') return ui.chip('▽ Vente ' + F.qty(s.liveActionQuantity) + ' ' + item.assetSymbol, 'down');
    if (t === 'BLOCKED') return ui.chip('Bloqué · ' + TIO.blockLabel(s.blockReason), 'warn');
    return ui.chip('Rien à faire', 'plain');
  }
  function decisionsCard() {
    return ui.widget('Décisions du jour', liveWallet, (items) => {
      if (!items.length) return ui.empty('Aucun actif suivi', 'Reliez un actif et un exchange pour recevoir une décision chaque jour.',
        h('a', { class: 'btn btn--primary', href: TIO.href('/app/onboarding') }, 'Démarrer'));
      return h('div', { class: 'list' }, items.map((it) => h('a', { class: 'item card-link', href: link(it.assetSymbol), style: { 'text-decoration': 'none', color: 'inherit' } },
        ui.coin(it.assetSymbol),
        h('div', { class: 'grow' }, h('div', { class: 'num' }, it.assetSymbol), h('div', { class: 'muted small' }, it.executionEnabled ? 'Exécution activée' : 'Simulation (rien n\'est envoyé)')),
        actionChip(it))));
    }, { span: false });
  }
  function nextPassCard() {
    return ui.widget('Prochaine passe', () => TIO.getOnce('/api/rainbow-live/next-pass'), (p) => {
      if (!p || !p.enabled || !p.nextRunAt) return ui.empty('Passe désactivée', 'Aucune décision ne sera calculée tant que le planificateur est coupé.');
      return [h('div', { class: 'num value-lg' }, F.until(p.nextRunAt)), h('div', { class: 'muted' }, F.dateTime(p.nextRunAt)),
        ui.note('La passe calcule la décision du jour de chaque actif (23 h 55 UTC).')];
    });
  }
  function cashCard() {
    return ui.widget('Cash et autonomie', () => Promise.all([liveWallet(), TIO.safe(summary('30d'), null)]), ([items, sum]) => {
      const seen = new Set();
      let cash = null;
      for (const it of items) {
        const s = it.snapshot;
        if (s && TIO.isNum(s.cashUsd) && !seen.has(it.walletId)) { seen.add(it.walletId); cash = (cash || 0) + s.cashUsd; }
      }
      const days = sum && sum.autonomyDays;
      return [h('div', { class: 'num value-lg' }, F.usd(cash, 0)),
        h('div', { class: 'row row--between' }, h('span', { class: 'muted' }, 'Autonomie'), h('span', { class: 'num' }, F.days(days))),
        TIO.isNum(days) ? ui.bar(Math.min(100, days / 365 * 100)) : null,
        ui.note('Cash disponible dans les wallets suivis ; autonomie = jours d\'achats de base couverts.')];
    });
  }
  function priceCard() {
    return ui.widget('Prix moyen vs cours', () => TIO.getOnce('/api/portfolio/assets'), (assets) => {
      const rows = assets.map((a) => ({ a, chart: TIO.charts.priceVsAvg(a.price, a.avgBuyPrice) })).filter((r) => r.chart);
      if (!rows.length) return ui.empty('Pas encore d\'achat', 'Le prix moyen apparaît après le premier achat réel.');
      return rows.map((r) => h('div', { class: 'stack' }, h('div', { class: 'row' }, ui.coin(r.a.assetSymbol), h('span', { class: 'num' }, r.a.assetSymbol), h('span', { class: 'grow' }), h('span', { class: 'num' }, F.price(r.a.price))), r.chart));
    });
  }

  // ---------- Avancé ----------
  function expositionCard() {
    return ui.widget('Exposition', () => Promise.all([summary('30d'), settings()]), ([s, m]) => {
      const v = s.current && s.current.exposurePct;
      const min = m.settings.exposureMin, max = m.settings.exposureMax;
      const where = !TIO.isNum(v) ? ['Inconnue', 'unknown'] : v < min ? ['Sous la bande', 'warn'] : v > max ? ['Au-dessus de la bande', 'warn'] : ['Dans la bande', 'ok'];
      return [ui.gauge({ value: v, bandMin: min, bandMax: max }),
        h('div', { class: 'row row--between' }, ui.chip(where[0], where[1]), h('span', { class: 'muted small' }, 'Bande ' + F.pct(min, 0) + ' – ' + F.pct(max, 0)))];
    });
  }
  function latestMargin() {
    return TIO.getOnce('/api/rainbow-live/decisions').then((ds) => {
      for (const d of ds) { const m = TIO.parseMod(d.modulation); if (m && TIO.isNum(m.marginPct)) return m.marginPct; }
      return null;
    });
  }
  function freeZoneCard() {
    return ui.widget('Zone libre', () => Promise.all([summary('30d'), settings(), TIO.safe(latestMargin(), null)]), ([s, m, margin]) => {
      const min = m.settings.exposureMin, max = m.settings.exposureMax, v = s.current && s.current.exposurePct;
      if (!TIO.isNum(margin)) return [ui.bandBar({ min, max, value: v }), ui.note('Zone libre : — (marge de régulation connue après la première passe).')];
      const fmin = min + margin, fmax = max - margin;
      const inside = TIO.isNum(v) && v >= fmin && v <= fmax;
      return [h('div', { class: 'num value-md' }, F.pct(fmin, 0) + ' – ' + F.pct(fmax, 0)), ui.bandBar({ min, max, freeMin: fmin, freeMax: fmax, value: v }),
        h('div', { class: 'muted small' }, !TIO.isNum(v) ? 'Exposition inconnue' : inside ? 'Exposition en zone libre : achats et ventes sans régulation.' : 'Hors zone libre : les montants sont régulés pour revenir dans la bande.')];
    });
  }
  function powerCard() {
    return ui.widget('Puissance', settings, (m) => {
      const sl = ui.slider({ min: m.limits.powerMin, max: m.limits.powerMax, step: 0.05, value: m.settings.power, marker: 1, label: 'Puissance', disabled: true });
      return [h('div', { class: 'num value-lg' }, F.num(m.settings.power, 2) + '×'), sl.el,
        h('div', { class: 'row row--between small muted' }, h('span', null, F.num(m.limits.powerMin, 1) + '×'), h('span', null, 'repère 1×'), h('span', null, F.num(m.limits.powerMax, 1) + '×')),
        h('a', { href: TIO.href('/app/settings') }, 'Ajuster dans les réglages')];
    });
  }
  function biasCard() {
    return ui.widget('Biais achat / vente', settings, (m) => {
      const b = m.settings.bias;
      const sl = ui.slider({ min: -m.limits.biasMax, max: m.limits.biasMax, step: 0.05, value: b, marker: 0, label: 'Biais', disabled: true });
      return [h('div', { class: 'num value-lg' }, (b > 0 ? '+' : '') + F.num(b, 2)), sl.el,
        h('div', { class: 'row row--between small muted' }, h('span', null, '▽ vente'), h('span', null, 'neutre'), h('span', null, '▲ achat')),
        h('div', { class: 'muted small' }, b > 0 ? 'Plutôt achat : achats amplifiés, ventes atténuées.' : b < 0 ? 'Plutôt vente : ventes amplifiées, achats atténués.' : 'Neutre : achats et ventes à égalité.')];
    });
  }
  function carryCard() {
    return ui.widget('Reporté (carry)', () => TIO.safe(TIO.getOnce('/api/rainbow-live/execution-plans/latest'), null), (plan) => {
      if (!plan) return ui.empty('Aucun plan', 'Le reporté apparaît dès qu\'un plan d\'ordres existe.');
      const out = (plan.assets || []).filter((a) => a.carryOutUsd > 0), inn = (plan.assets || []).filter((a) => a.carryInUsd > 0);
      const total = out.reduce((t, a) => t + Number(a.carryOutUsd), 0);
      if (!out.length && !inn.length) return ui.empty('Rien de reporté', 'Les montants trop petits pour l\'exchange sont reportés au jour suivant.');
      return [h('div', { class: 'num value-lg warn' }, F.usd(total)), h('div', { class: 'muted small' }, 'à reporter au prochain jour (sous le minimum de l\'exchange)'),
        h('div', { class: 'list' }, out.concat(inn.filter((a) => !out.includes(a))).map((a) => h('div', { class: 'item' }, h('span', { class: 'carry', 'aria-hidden': 'true' }), h('span', { class: 'num' }, a.assetSymbol),
          h('span', { class: 'grow' }), h('span', { class: 'small muted' }, (a.carryInUsd > 0 ? 'repris ' + F.usd(a.carryInUsd) : '') + (a.carryOutUsd > 0 ? ' reporté ' + F.usd(a.carryOutUsd) : '')))))];
    });
  }

  TIO.start(function (root) {
    const user = document.body.dataset.user || '';
    const adv = TIO.modeAtLeast('advanced');
    const cards = [patrimoineCard(), decisionsCard(), nextPassCard(), cashCard(), priceCard()];
    if (adv) cards.push(expositionCard(), freeZoneCard(), powerCard(), biasCard(), carryCard());
    TIO.mount(root, ui.pageHeader('Bonjour' + (user ? ', ' + user : '')), ui.demoBanner(), ui.alerts(), h('div', { class: 'grid grid--3' }, cards),
      adv ? null : h('p', { class: 'note' }, 'Mode Simple. ', h('a', { href: TIO.href('/app/settings') }, 'Passer en Avancé'), ' pour voir exposition, puissance, biais et reporté.'));
  });
})(window);
