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

  const DONUT_MIN_PCT = 0.1;   // sous ce poids dans le patrimoine, l'actif n'est pas affiché dans l'exposition
  function donutCard() {
    const card = ui.widget('Exposition', holdings, (hold) => {
      const priced = hold.assets.filter((l) => TIO.isNum(l.value) && l.value > 0 && hold.total > 0 && l.value / hold.total * 100 >= DONUT_MIN_PCT);
      if (!priced.length) return ui.empty('Aucune position valorisée', 'La répartition apparaît dès qu\'un solde est lisible.');
      const assets = priced.filter((l) => !l.stable), stables = priced.filter((l) => l.stable);
      const segments = assets.map((l, i) => ({ label: l.assetSymbol, value: l.value, pct: l.value / hold.total * 100, color: 'var(--s' + (i % 7) + ')', group: 'assets' }))
        .concat(stables.map((l, i) => ({ label: l.assetSymbol, value: l.value, pct: l.value / hold.total * 100, color: 'color-mix(in srgb, var(--muted) ' + Math.max(35, 85 - i * 20) + '%, var(--surface))', group: 'stable' })));
      const donut = TIO.charts.donut(segments, { label: 'Répartition actifs et stablecoins', centerValue: F.pct(hold.exposurePct, 0), centerLabel: 'en actifs', valueFmt: (s) => F.usd(s.value, 0) });
      const legend = h('div', { class: 'stack', style: { flex: '1 1 180px' } }, segments.map((s) => h('div', { class: 'row' },
        h('i', { class: 'dot', style: { '--c': s.color } }), h('span', { class: 'num' }, s.label), h('span', { class: 'grow' }),
        h('span', { class: 'num muted' }, F.pct(s.value / hold.total * 100, 1)))));
      return h('div', { class: 'donut-wrap' }, donut, legend);
    });
    onSelect(card);
    return card;
  }

  function originChip(t) {
    return t.origin === 'TRADEIO' ? ui.chip('TradeIO', 'accent') : ui.chip('Manuel', 'plain');
  }
  const money = (n, quote) => (!quote || /^(USD|USDC|USDT)$/.test(quote) ? F.usd(Number(n)) : F.num(Number(n), 2) + ' ' + quote);
  // Importe d'abord les trades récents des exchanges (lecture seule, espacé de 5 min côté serveur), puis les lit.
  const syncFlag = { force: false };
  const syncThenList = () => TIO.safe(TIO.post('/api/portfolio/transactions/sync' + TIO.qs({ walletId: sel.wallet, force: syncFlag.force ? 'true' : null })), null)
    .then((r) => { syncFlag.force = false; return r; }).then(() => TIO.get('/api/portfolio/transactions' + walletQs()));
  const TX_PAGE = 10;
  const TX_MERGE_MS = 5 * 60 * 1000;
  /** Regroupe les exécutions d'un même ordre fractionné : même exchange, actif, sens, origine et cotation, chacune à moins de 5 min de la précédente. */
  function mergeFills(trades) {
    const out = [];
    const open = new Map();   // clé → groupe en cours (le plus ancien membre sert de référence au chaînage)
    trades.forEach((t) => {   // du plus récent au plus ancien
      const key = [t.exchange, t.assetSymbol, t.side, t.origin, t.quoteCurrency].join('|');
      const time = new Date(t.timestamp).getTime();
      const g = open.get(key);
      if (g && g.oldest - time <= TX_MERGE_MS) {
        g.fills.push(t); g.oldest = time;
        g.quantity += Number(t.quantity); g.notional += Number(t.notional);
      } else {
        const ng = { key, latest: t.timestamp, oldest: time, exchange: t.exchange, assetSymbol: t.assetSymbol, side: t.side, origin: t.origin,
          quoteCurrency: t.quoteCurrency, quantity: Number(t.quantity), notional: Number(t.notional), fills: [t] };
        open.set(key, ng); out.push(ng);
      }
    });
    out.forEach((g) => { g.price = g.quantity > 0 ? g.notional / g.quantity : null; });
    return out;
  }
  function txRow(g, count) {
    return h('div', { class: 'tx-row' },
      h('span', { class: 'when' }, F.dateTime(g.latest)),
      h('span', { class: 'prov' }, exchangeName(g.exchange)),
      ui.chip(g.side === 'BUY' ? '▲ Achat' : '▽ Vente', g.side === 'BUY' ? 'ok' : 'down'),
      originChip(g),
      h('span', { class: 'asset' }, ui.coin(g.assetSymbol), h('span', { class: 'num' }, g.assetSymbol)),
      count > 1 ? ui.tag(count + ' exécutions') : null,
      h('span', { class: 'grow' }),
      h('span', { class: 'num small muted nw' }, F.qty(g.quantity) + ' × ' + money(g.price, g.quoteCurrency)),
      h('span', { class: 'num nw amt' }, money(g.notional, g.quoteCurrency)),
      count > 1 ? h('span', { class: 'chev', 'aria-hidden': 'true' }, '›') : null);
  }
  function transactionsCard() {
    const card = ui.widget('Transactions', syncThenList, (trades) => {
      if (!trades.length) return ui.empty('Aucune transaction', 'Les achats et ventes réalisés sur vos exchanges, à la main ou par TradeIO, apparaissent ici.');
      // un seul fil chronologique (plus récent d'abord), tous exchanges confondus ; exécutions rapprochées fusionnées (dépliables)
      const groups = mergeFills(trades);
      const body = h('div', { class: 'tx-list' });
      const foot = h('div', { class: 'row' });
      let shown = TX_PAGE;
      const draw = () => {
        TIO.mount(body, groups.slice(0, shown).map((g) => {
          if (g.fills.length === 1) return txRow(g, 1);
          const detail = h('div', { class: 'fold-body' }, g.fills.map((f) => h('div', { class: 'tx-row tx-row--sub' },
            h('span', { class: 'when' }, F.dateTime(f.timestamp)), h('span', { class: 'grow' }),
            h('span', { class: 'num small muted nw' }, F.qty(Number(f.quantity)) + ' × ' + money(f.price, f.quoteCurrency)),
            h('span', { class: 'num nw amt' }, money(f.notional, f.quoteCurrency)))));
          return h('details', { class: 'fold' }, h('summary', null, txRow(g, g.fills.length)), detail);
        }));
        TIO.mount(foot, shown < groups.length
          ? h('button', { class: 'btn', type: 'button', onclick: () => { shown += TX_PAGE; draw(); } }, 'Afficher ' + Math.min(TX_PAGE, groups.length - shown) + ' de plus')
          : null, h('span', { class: 'muted small' }, Math.min(shown, groups.length) + ' sur ' + groups.length));
      };
      draw();
      return [body, foot];
    }, { span: true, aside: h('button', { class: 'btn', type: 'button', onclick: () => { syncFlag.force = true; card.reload(); } }, 'Actualiser') });
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
    const card = ui.widget('Cash et autonomie', () => TIO.get('/api/portfolio/autonomy' + walletQs()), (a) => {
      const days = a.days;
      return [h('div', { class: 'num value-lg' }, F.usd(a.cash, 0)),
        h('div', { class: 'row row--between' }, h('span', { class: 'muted' }, 'Autonomie'), h('span', { class: 'num' }, F.days(days))),
        TIO.isNum(days) ? ui.bar(Math.min(100, days / 365 * 100)) : null,
        ui.note(TIO.isNum(days)
          ? 'Stablecoins disponibles ; autonomie = nombre de jours d\'achats de base qu\'ils couvrent (' + F.usd(a.dailySpend, 0) + ' par jour en moyenne).'
          : 'Stablecoins disponibles. L\'autonomie se calcule dès qu\'un actif est lié avec un montant de base.')];
    });
    onSelect(card);
    return card;
  }
  function priceCard() {
    const card = ui.widget('Prix moyen vs cours', () => TIO.get('/api/portfolio/asset-performance' + walletQs()), (assets) => {
      const rows = assets.filter((a) => TIO.isNum(a.price) && TIO.isNum(a.avgBuyPrice) && a.avgBuyPrice > 0);
      if (!rows.length) return ui.empty('Pas encore d\'achat', 'Le prix moyen vient de vos achats enregistrés sur l\'exchange (manuels ou TradeIO).');
      return h('div', { class: 'pva-list' }, rows.map((a) => {
        const diff = (a.price / a.avgBuyPrice - 1) * 100;
        return h('div', { class: 'pva' },
          h('div', { class: 'pva-head' }, ui.coin(a.assetSymbol), h('span', { class: 'num pva-sym' }, a.assetSymbol),
            h('span', { class: 'num pva-vals' }, h('span', { class: TIO.sign(diff) }, F.price(a.avgBuyPrice)), ' / ' + F.price(a.price)),
            h('span', { class: 'num pva-diff ' + TIO.sign(diff) }, (diff > 0 ? '▲ ' : diff < 0 ? '▽ ' : '') + F.signedPct(diff))),
          TIO.charts.priceVsAvg(a.price, a.avgBuyPrice, { bare: true }));
      }));
    });
    onSelect(card);
    return card;
  }

  // ---------- Avancé ----------
  function freeZoneCard() {
    const card = ui.widget('Zone libre', () => Promise.all([holdings(), settings()]), ([hold, m]) => {
      const marginPct = TIO.isNum(m.settings.regulationMarginPct) ? m.settings.regulationMarginPct : m.limits.marginDefaultPct;
      const band = ui.exposureBand({ min: m.settings.exposureMin, max: m.settings.exposureMax, marginPct, value: hold.exposurePct });
      const st = band.status;
      return [st ? h('div', { class: 'row' }, ui.chip(st[0], st[1]), h('span', { class: 'muted small' }, TIO.isNum(hold.exposurePct) ? 'Exposition actuelle ' + F.pct(hold.exposurePct, 0) : '')) : h('div', { class: 'muted small' }, 'Exposition inconnue (aucun solde lisible).'),
        band,
        ui.note('Dans la marge de régulation, TradeIO module achats et ventes pour te ramener vers la zone libre. Réglable dans les Réglages.')];
    });
    onSelect(card);
    return card;
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
    const cards = [patrimoineCard(), holdingsCard(), donutCard(), priceCard(), transactionsCard(), decisionsCard(), cashCard()];
    if (adv) cards.push(freeZoneCard(), powerCard(), biasCard(), carryCard());
    TIO.mount(root, ui.pageHeader('Bonjour' + (user ? ', ' + user : '')), ui.demoBanner(), ui.alerts(), selector(), h('div', { class: 'grid grid--3' }, cards),
      adv ? null : h('p', { class: 'note' }, 'Mode Simple. ', h('a', { href: TIO.href('/app/settings') }, 'Passer en Avancé'), ' pour voir la bande d\'exposition, puissance, biais et reporté.'));
  });
})(window);
