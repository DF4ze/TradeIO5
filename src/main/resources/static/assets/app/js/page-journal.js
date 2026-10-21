/* Journal : plans d'ordres et exécutions (frais réels, slippage, Fee Test, carry) et décisions, avec filtres serveur. */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, F = TIO.fmt;
  const PERIODS = [{ value: '7', label: '7 j' }, { value: '30', label: '30 j' }, { value: '90', label: '90 j' }];

  const isoDay = (offset) => new Date(Date.now() + offset * 86400000).toISOString().slice(0, 10);
  const opt = (value, label) => h('option', { value }, label);
  function select(label, options, onChange) {
    const el = h('select', null, options.map((o) => opt(o[0], o[1])));
    el.addEventListener('change', () => onChange(el.value));
    return ui.field(label, el);
  }

  function stepLine(s) {
    const done = s.status === 'FILLED' || s.status === 'PARTIAL';
    return h('div', { class: 'row small' }, ui.chip(TIO.labels.step[s.status] || s.status, TIO.statusKind(s.status) === 'unknown' ? 'unknown' : TIO.statusKind(s.status)),
      h('span', { class: 'num' }, (s.side === 'SELL' ? '▽ ' : '▲ ') + s.instId), s.quoteAmount ? h('span', null, F.usd(s.quoteAmount)) : null,
      done ? h('span', { class: 'muted' }, F.qty(Number(s.filledSz)) + ' @ ' + F.price(Number(s.avgFillPx))) : null,
      TIO.isNum(Number(s.feeAmount)) && s.feeAmount !== null ? h('span', { class: 'muted' }, 'frais ' + F.num(Number(s.feeAmount), 4) + ' ' + (s.feeCurrency || '')) : null,
      s.realSlippagePct !== null && s.realSlippagePct !== undefined ? h('span', { class: 'muted' }, 'slippage ' + F.pct(Number(s.realSlippagePct), 2)) : null,
      s.lastError ? h('span', { class: 'down' }, s.lastError) : null);
  }
  function assetLine(a) {
    const carry = Number(a.carryOutUsd) > 0 || Number(a.carryInUsd) > 0;
    return h('div', { class: 'stack', style: { 'padding-top': '8px', 'border-top': '1px solid var(--line)' } },
      h('div', { class: 'row' }, ui.coin(a.assetSymbol), h('span', { class: 'num' }, a.assetSymbol), ui.chip(TIO.labels.action[a.action] || a.action || F.dash, a.action === 'BUY' ? 'ok' : a.action === 'SELL' ? 'down' : 'plain'),
        a.outcome && a.outcome !== 'NO_ACTION' ? ui.chip(a.outcome === 'OK' ? 'Prêt' : a.outcome === 'BLOCKED' ? 'Bloqué' : 'Désactivé', TIO.statusKind(a.outcome)) : null, a.blockReason ? h('span', { class: 'warn small' }, TIO.blockLabel(a.blockReason)) : null,
        carry ? h('span', { class: 'row small' }, h('span', { class: 'carry', 'aria-hidden': 'true' }), (Number(a.carryInUsd) > 0 ? 'repris ' + F.usd(Number(a.carryInUsd)) + ' ' : '') + (Number(a.carryOutUsd) > 0 ? 'reporté ' + F.usd(Number(a.carryOutUsd)) : '')) : null),
      a.warning ? h('div', { class: 'warn small' }, a.warning) : null, (a.steps || []).map(stepLine));
  }
  function eventsBlock(plan) {
    const box = h('div', { class: 'stack' });
    const d = h('details', null, h('summary', { class: 'small' }, 'Audit (évènements)'), box);
    let loaded = false;
    d.addEventListener('toggle', () => {
      if (!d.open || loaded) return;
      loaded = true;
      ui.load(box, () => TIO.get('/api/rainbow-live/execution-plans/' + plan.id + '/events'), (page) => (page.items && page.items.length
        ? h('div', { class: 'list' }, page.items.map((e) => h('div', { class: 'item small' }, h('span', { class: 'num', style: { width: '90px' } }, F.dateTime(e.createdAt)), h('strong', null, e.type), h('span', { class: 'muted', style: { 'overflow-wrap': 'anywhere' } }, String(e.payload || '').slice(0, 300)))))
        : ui.empty('Aucun évènement')));
    });
    return d;
  }
  function planCard(plan) {
    return h('section', { class: 'card', 'aria-label': 'Plan du ' + plan.day },
      h('div', { class: 'row' }, h('span', { class: 'num value-md' }, F.day(plan.day)), ui.chip(TIO.labels.plan[plan.status] || plan.status, TIO.statusKind(plan.status)), ui.chip(plan.mode || F.dash, 'plain'),
        plan.feeTestLevel ? ui.chip(TIO.labels.fee[plan.feeTestLevel] || plan.feeTestLevel, TIO.statusKind(plan.feeTestLevel)) : null,
        h('span', { class: 'grow' }), TIO.isNum(Number(plan.totalCostPct)) && plan.totalCostPct !== null ? h('span', { class: 'muted small' }, 'coût ' + F.pct(Number(plan.totalCostPct), 2)) : null),
      plan.blockReason ? h('div', { class: 'warn small' }, TIO.blockLabel(plan.blockReason)) : null,
      plan.executionBlockReason ? h('div', { class: 'warn small' }, 'Exécution : ' + TIO.blockLabel(plan.executionBlockReason)) : null,
      plan.expiresAt ? h('div', { class: 'muted small' }, 'Expire ' + F.dateTime(plan.expiresAt)) : null,
      (plan.assets || []).map(assetLine), eventsBlock(plan));
  }
  function decisionCard(d) {
    const chip = d.action === 'BUY' ? ui.chip('▲ Achat ' + F.usd(d.amountUsdc), 'ok') : d.action === 'SELL' ? ui.chip('▽ Vente', 'down')
      : d.action === 'BLOCKED' ? ui.chip('Bloqué · ' + TIO.blockLabel(d.blockReason), 'warn') : ui.chip('Rien à faire', 'plain');
    return h('section', { class: 'card', 'aria-label': 'Décision ' + d.assetSymbol + ' ' + d.day },
      h('div', { class: 'row' }, h('span', { class: 'num' }, F.day(d.day)), ui.coin(d.assetSymbol), h('span', { class: 'num' }, d.assetSymbol), chip),
      h('details', null, h('summary', { class: 'small' }, 'Pourquoi ce montant ?'), h('div', { class: 'stack' }, TIO.explain.why(d.assetSymbol, d))));
  }

  TIO.start(function (root) {
    const f = { tab: 'plans', asset: '', wallet: '', status: '', reason: '', days: '30' };
    const list = h('div', { class: 'grid grid--2' });
    const load = () => {
      const range = { from: isoDay(-Number(f.days)), to: isoDay(0), assetSymbol: f.asset, walletId: f.wallet };
      if (f.tab === 'plans') {
        ui.load(list, () => TIO.get('/api/rainbow-live/execution-plans' + TIO.qs(Object.assign({}, range, { status: f.status, blockReason: f.reason }))),
          (plans) => (plans.length ? plans.map(planCard) : ui.empty('Aucun plan', 'Aucun plan d\'ordres ne correspond à ces filtres.')));
      } else {
        ui.load(list, () => TIO.get('/api/rainbow-live/decisions' + TIO.qs(range)),
          (ds) => {
            const rows = ds.filter((d) => (!f.reason || d.blockReason === f.reason) && (!f.status || d.action === f.status));
            return rows.length ? rows.map(decisionCard) : ui.empty('Aucune décision', 'Aucune décision ne correspond à ces filtres.');
          });
      }
    };
    const assetSel = h('div'), walletSel = h('div'), statusSel = h('div'), reasonSel = h('div');
    const paintStatus = () => TIO.mount(statusSel, select(f.tab === 'plans' ? 'Statut du plan' : 'Action', [['', 'Tous']].concat(f.tab === 'plans'
      ? Object.entries(TIO.labels.plan) : Object.entries(TIO.labels.action)), (v) => { f.status = v; load(); }));
    Promise.all([TIO.safe(TIO.get('/api/rainbow-live/bindings'), []), TIO.safe(TIO.get('/api/rainbow-live/wallets'), [])]).then(([bindings, wallets]) => {
      TIO.mount(assetSel, select('Actif', [['', 'Tous']].concat([...new Set(bindings.map((b) => b.assetSymbol))].map((s) => [s, s])), (v) => { f.asset = v; load(); }));
      TIO.mount(walletSel, select('Wallet', [['', 'Tous']].concat(wallets.map((w) => [String(w.id), w.name])), (v) => { f.wallet = v; load(); }));
    });
    TIO.mount(reasonSel, select('Raison de blocage', [['', 'Toutes']].concat(Object.entries(TIO.labels.block).filter(([k]) => k !== 'NONE')), (v) => { f.reason = v; load(); }));
    paintStatus();
    const filters = h('div', { class: 'card' }, h('div', { class: 'row row--between' },
      ui.segmented([{ value: 'plans', label: 'Plans et exécutions' }, { value: 'decisions', label: 'Décisions' }], f.tab, (v) => { f.tab = v; f.status = ''; paintStatus(); load(); }, 'Vue'),
      ui.periods(PERIODS, f.days, (v) => { f.days = v; load(); })),
      h('div', { class: 'grid grid--2' }, assetSel, walletSel, statusSel, reasonSel));
    load();
    TIO.mount(root, ui.pageHeader('Journal'), ui.demoBanner(), ui.alerts(), filters, list);
  });
})(window);
