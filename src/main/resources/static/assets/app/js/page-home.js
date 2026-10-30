/* Accueil : sélecteur de portefeuille (un ou tous), patrimoine + courbe reconstruite, détail par actif, exposition (donut actifs / stables),
   transactions par exchange, décisions du jour groupées par actif, cash et autonomie, prix moyen vs cours (Simple) ;
   zone libre, puissance, biais, reporté (Avancé). */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, F = TIO.fmt;

  const PERIODS = [{ value: '7d', label: '7 j' }, { value: '30d', label: '30 j' }, { value: '90d', label: '90 j' }, { value: '1y', label: '1 an' }, { value: 'all', label: 'Tout' }];
  const summary = (period) => TIO.get('/api/portfolio/summary?period=' + period);
  const settings = () => TIO.getOnce('/api/user/modulation-settings');
  const liveWallet = () => TIO.getOnce('/api/rainbow-live/live-wallet');
  const link = (symbol) => TIO.href('/app/assets/' + encodeURIComponent(symbol));

  // ---------- portefeuille sélectionné (null = tous) ----------
  const sel = { wallet: null, listeners: [] };
  const EXCHANGE = { OKX: 'OKX', KRAKEN: 'Kraken', BINANCE: 'Binance' };
  const exchangeName = (c) => (c ? (EXCHANGE[c] || c) : F.dash);
  const walletQs = () => TIO.qs({ walletId: sel.wallet });
  const holdings = () => TIO.getOnce('/api/portfolio/holdings' + walletQs());
  const history = (period) => TIO.getOnce('/api/portfolio/history' + TIO.qs({ walletId: sel.wallet, period }));
  const onSelect = (card) => sel.listeners.push(card);

  function selector() {
    const box = h('div', { class: 'toolbar' });
    TIO.safe(TIO.getOnce('/api/rainbow-live/wallets'), []).then((wallets) => {
      if (wallets.length < 2) return;
      const options = [{ value: '', label: 'Tous les portefeuilles' }].concat(wallets.map((w) => ({ value: String(w.id), label: w.name })));
      box.appendChild(h('span', { class: 'lbl' }, 'Portefeuille'));
      box.appendChild(ui.pchips(options, '', (v) => { sel.wallet = v === '' ? null : Number(v); sel.listeners.forEach((c) => c.reload()); }, 'Portefeuille'));
    });
    return box;
  }

  function unavailableNote(list) {
    return list && list.length ? ui.note('Soldes indisponibles : ' + list.map((u) => u.walletName + ' (' + u.reason + ')').join(', ') + '. Ils ne sont pas comptés.') : null;
  }

  function patrimoineCard() {
    const period = { v: '30d' };
    let card;
    const aside = ui.periods(PERIODS, period.v, (v) => { period.v = v; card.reload(); });
    card = ui.widget('Patrimoine', () => Promise.all([holdings(), TIO.safe(history(period.v), null)]), ([hold, hist]) => {
      const pnl = hist && hist.pnl;
      const chart = hist ? TIO.charts.area((hist.series || []).map((p) => ({ x: p.day, y: p.total })), { label: 'Patrimoine' }) : null;
      return [
        h('div', { class: 'num value-xl' }, F.usd(hold.total, 0)),
        pnl ? h('div', { class: 'row' },
          h('span', { class: 'num value-md ' + TIO.sign(pnl.amount) }, (pnl.amount > 0 ? '▲ ' : pnl.amount < 0 ? '▽ ' : '') + F.signedUsd(pnl.amount, 0)),
          h('span', { class: 'num ' + TIO.sign(pnl.pct) }, F.signedPct(pnl.pct)),
          h('span', { class: 'muted small' }, 'depuis le ' + F.day(pnl.since)), ui.pnlNote())
          : h('div', { class: 'muted' }, 'Variation : ' + F.dash + ' (historique indisponible)'),
        chart || ui.empty('Historique indisponible', 'Aucun trade enregistré ni cours disponible sur cette période.'),
        chart ? ui.note('Courbe estimée : soldes actuels remontés à rebours à partir des trades enregistrés, valorisés au cours du jour. Les dépôts et retraits ne sont pas pris en compte.') : null,
        hold.partial ? ui.note('Certains actifs n\'ont pas de cours : ils ne sont pas comptés dans le total.') : null,
        unavailableNote(hold.unavailable),
      ];
    }, { hero: true, span: true, aside });
    onSelect(card);
    return card;
  }

  function holdingRow(line, total) {
    const multi = (line.parts || []).length > 1;
    const head = h('div', { class: 'hold-row' }, ui.coin(line.assetSymbol),
      h('div', { class: 'grow' }, h('div', { class: 'num' }, line.assetSymbol), h('div', { class: 'muted small' }, F.qty(line.quantity) + (TIO.isNum(line.price) ? ' × ' + F.price(line.price) : ' · cours indisponible'))),
      h('div', { class: 'r' }, h('div', { class: 'num' }, F.usd(line.value)), h('div', { class: 'muted small' }, F.pct(line.sharePct, 1))),
      multi ? h('span', { class: 'chev', 'aria-hidden': 'true' }, '›') : null);
    if (!multi) return head;
    const body = h('div', { class: 'fold-body' }, line.parts.map((p) => h('div', { class: 'hold-row' },
      h('div', { class: 'grow' }, h('span', { class: 'muted small' }, exchangeName(p.exchange) + ' · ' + p.walletName)),
      h('div', { class: 'r num small' }, F.qty(p.quantity) + ' · ' + F.usd(p.value)))));
    const d = h('details', { class: 'fold' }, h('summary', null, head), body);
    return d;
  }
  function holdingsCard() {
    const card = ui.widget('Détail par actif', holdings, (hold) => {
      const assets = hold.assets.filter((l) => !l.stable), stables = hold.assets.filter((l) => l.stable);
      if (!hold.assets.length) return ui.empty('Aucun solde', 'Aucun solde lisible sur ce portefeuille. Vérifiez la clé de lecture dans les réglages.',
        h('a', { class: 'btn', href: TIO.href('/app/settings') }, 'Réglages'));
      const rows = assets.map((l) => holdingRow(l, hold.total));
      if (stables.length) {
        const stableValue = stables.reduce((t, l) => t + (l.value || 0), 0);
        const sum = h('div', { class: 'hold-row' }, h('span', { class: 'coin', 'aria-hidden': 'true' }, '$'),
          h('div', { class: 'grow' }, h('div', { class: 'num' }, 'Stablecoins'), h('div', { class: 'muted small' }, stables.map((l) => l.assetSymbol).join(', '))),
          h('div', { class: 'r' }, h('div', { class: 'num' }, F.usd(stableValue)), h('div', { class: 'muted small' }, F.pct(hold.total > 0 ? stableValue / hold.total * 100 : null, 1))),
          stables.length > 1 ? h('span', { class: 'chev', 'aria-hidden': 'true' }, '›') : null);
        rows.push(stables.length > 1 ? h('details', { class: 'fold' }, h('summary', null, sum),
          h('div', { class: 'fold-body' }, stables.map((l) => h('div', { class: 'hold-row' }, h('div', { class: 'grow num small' }, l.assetSymbol), h('div', { class: 'r num small' }, F.usd(l.value)))))) : sum);
      }
      return [h('div', null, rows), unavailableNote(hold.unavailable)];
    });
    onSelect(card);
    return card;
  }

  function donutCard() {
    const card = ui.widget('Exposition', () => Promise.all([holdings(), TIO.safe(settings(), null)]), ([hold, m]) => {
      const priced = hold.assets.filter((l) => TIO.isNum(l.value) && l.value > 0);
      if (!priced.length) return ui.empty('Aucune position valorisée', 'La répartition apparaît dès qu\'un solde est lisible.');
      const assets = priced.filter((l) => !l.stable), stables = priced.filter((l) => l.stable);
      const segments = assets.map((l, i) => ({ label: l.assetSymbol, value: l.value, color: 'var(--s' + (i % 7) + ')', group: 'assets' }))
        .concat(stables.map((l, i) => ({ label: l.assetSymbol, value: l.value, color: 'color-mix(in srgb, var(--muted) ' + Math.max(35, 85 - i * 20) + '%, var(--surface))', group: 'stable' })));
      const donut = TIO.charts.donut(segments, { label: 'Répartition actifs et stablecoins', centerValue: F.pct(hold.exposurePct, 0), centerLabel: 'en actifs' });
      const legend = h('div', { class: 'stack', style: { flex: '1 1 180px' } }, segments.map((s) => h('div', { class: 'row' },
        h('i', { class: 'dot', style: { '--c': s.color } }), h('span', { class: 'num' }, s.label), h('span', { class: 'grow' }),
        h('span', { class: 'num muted' }, F.pct(s.value / hold.total * 100, 1)))));
      const band = m && TIO.modeAtLeast('advanced') && TIO.isNum(hold.exposurePct)
        ? [ui.bandBar({ min: m.settings.exposureMin, max: m.settings.exposureMax, value: hold.exposurePct }),
          h('div', { class: 'muted small' }, 'Bande ' + F.pct(m.settings.exposureMin, 0) + ' – ' + F.pct(m.settings.exposureMax, 0))] : null;
      return [h('div', { class: 'donut-wrap' }, donut, legend), band];
    });
    onSelect(card);
    return card;
  }

  function transactionsCard() {
    const card = ui.widget('Transactions', () => TIO.get('/api/portfolio/transactions' + walletQs()), (trades) => {
      if (!trades.length) return ui.empty('Aucune transaction', 'Les achats et ventes réalisés sur vos exchanges apparaissent ici.');
      const groups = new Map();
      trades.forEach((t) => { const k = t.exchange || ''; if (!groups.has(k)) groups.set(k, []); groups.get(k).push(t); });
      return [...groups.entries()].map(([ex, list]) => {
        const body = h('div', null);
        const more = h('button', { class: 'btn', type: 'button' }, 'Tout afficher (' + list.length + ')');
        const draw = (all) => {
          TIO.mount(body, (all ? list : list.slice(0, 6)).map((t) => h('div', { class: 'tx-row' },
            h('span', { class: 'when' }, F.dateTime(t.timestamp)),
            ui.chip(t.side === 'BUY' ? '▲ Achat' : '▽ Vente', t.side === 'BUY' ? 'ok' : 'down'),
            ui.coin(t.assetSymbol), h('span', { class: 'num' }, t.assetSymbol),
            h('span', { class: 'grow' }),
            h('span', { class: 'num small muted' }, F.qty(t.quantity) + ' × ' + F.price(Number(t.price))),
            h('span', { class: 'num' }, F.usd(Number(t.notional))))));
          if (!all && list.length > 6) body.appendChild(h('div', { class: 'row' }, more));
        };
        more.addEventListener('click', () => draw(true));
        draw(false);
        return h('div', { class: 'tx-group' }, h('div', { class: 'tx-head' }, exchangeName(ex), ui.tag(list.length + ' trade' + (list.length > 1 ? 's' : ''))), body);
      });
    });
    onSelect(card);
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
  /** Pastille agrégée d'un actif joué sur plusieurs exchanges : somme si tous achètent / vendent, sinon état commun ou « Mixte ». */
  function groupChip(items) {
    const types = items.map((it) => (it.snapshot ? (it.snapshot.status && it.snapshot.status !== 'OK' ? 'STATUS' : it.snapshot.liveActionType) : 'NOPASS'));
    const all = (t) => types.every((x) => x === t);
    if (all('BUY')) return ui.chip('▲ Achat ' + F.usd(items.reduce((t, it) => t + (it.snapshot.liveActionAmountUsdc || 0), 0)), 'ok');
    if (all('SELL')) return ui.chip('▽ Vente ' + F.qty(items.reduce((t, it) => t + (it.snapshot.liveActionQuantity || 0), 0)) + ' ' + items[0].assetSymbol, 'down');
    if (all('NONE')) return ui.chip('Rien à faire', 'plain');
    if (all('BLOCKED')) return ui.chip('Bloqué', 'warn');
    if (all('NOPASS')) return ui.chip('Pas encore de passe', 'unknown');
    return ui.chip('Mixte', 'plain');
  }
  function decisionRow(it, label) {
    return h('a', { class: 'item card-link', href: link(it.assetSymbol), style: { 'text-decoration': 'none', color: 'inherit' } },
      label ? h('div', { class: 'grow' }, h('div', { class: 'small' }, label), h('div', { class: 'muted small' }, it.executionEnabled ? 'Exécution activée' : 'Simulation (rien n\'est envoyé)'))
        : [ui.coin(it.assetSymbol), h('div', { class: 'grow' }, h('div', { class: 'num' }, it.assetSymbol), h('div', { class: 'muted small' }, exchangeName(it.exchange) + ' · ' + (it.executionEnabled ? 'Exécution activée' : 'Simulation (rien n\'est envoyé)')))],
      actionChip(it));
  }
  function decisionsCard() {
    return ui.widget('Décisions du jour', liveWallet, (items) => {
      if (!items.length) return ui.empty('Aucun actif suivi', 'Reliez un actif et un exchange pour recevoir une décision chaque jour.',
        h('a', { class: 'btn btn--primary', href: TIO.href('/app/onboarding') }, 'Démarrer'));
      const byAsset = new Map();
      items.forEach((it) => { if (!byAsset.has(it.assetSymbol)) byAsset.set(it.assetSymbol, []); byAsset.get(it.assetSymbol).push(it); });
      return h('div', { class: 'list' }, [...byAsset.entries()].map(([symbol, list]) => {
        if (list.length === 1) return decisionRow(list[0]);
        const head = h('div', { class: 'item' }, ui.coin(symbol),
          h('div', { class: 'grow' }, h('div', { class: 'num' }, symbol), h('div', { class: 'muted small' }, list.length + ' exchanges')),
          groupChip(list), h('span', { class: 'chev', 'aria-hidden': 'true' }, '›'));
        return h('details', { class: 'fold' }, h('summary', null, head),
          h('div', { class: 'fold-body' }, h('div', { class: 'list' }, list.map((it) => decisionRow(it, exchangeName(it.exchange) + ' · ' + it.walletName)))));
      }));
    }, { span: false });
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
    const cards = [patrimoineCard(), holdingsCard(), donutCard(), transactionsCard(), decisionsCard(), cashCard(), priceCard()];
    if (adv) cards.push(freeZoneCard(), powerCard(), biasCard(), carryCard());
    TIO.mount(root, ui.pageHeader('Bonjour' + (user ? ', ' + user : '')), ui.demoBanner(), ui.alerts(), selector(), h('div', { class: 'grid grid--3' }, cards),
      adv ? null : h('p', { class: 'note' }, 'Mode Simple. ', h('a', { href: TIO.href('/app/settings') }, 'Passer en Avancé'), ' pour voir la bande d\'exposition, puissance, biais et reporté.'));
  });
})(window);
