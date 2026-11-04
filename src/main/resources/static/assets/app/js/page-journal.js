/* Journal : une carte repliable par jour (le plus récent déployé), une ligne par lien (actif × exchange) avec la décision, l'état de la
 * stratégie (attente d'un trailing-stop, verrou jusqu'à Down2, cooldown...), le montant prévu et l'alerte. Le détail (ordres, frais,
 * pourquoi ce montant, audit) s'ouvre dans une info-bulle (PC) ou une feuille en bas d'écran (mobile). Filtres côté navigateur. */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, F = TIO.fmt;
  const PERIODS = [{ value: '7', label: '7 j' }, { value: '30', label: '30 j' }, { value: '90', label: '90 j' }];
  const ZONES = ['Extrême bas', 'Zone X2', 'Zone X1', 'Zone X0,5', "Pas d'achat", 'Extrême haut'];
  const STATE_KINDS = [['MOON_ACTIVE', 'To the moon actif'], ['SELL_TRAILING', 'Trailing-stop de vente'], ['COOLDOWN', 'Cooldown'],
    ['LOCKED_UNTIL_DOWN2', 'Attente sous Down2'], ['BUY_TRAILING', "Trailing-stop d'achat"], ['WATCHING', 'En surveillance']];
  const WAITING = new Set(['MOON_ACTIVE', 'SELL_TRAILING', 'COOLDOWN', 'LOCKED_UNTIL_DOWN2', 'BUY_TRAILING']);

  const isoDay = (offset) => new Date(Date.now() + offset * 86400000).toISOString().slice(0, 10);
  const num = (v) => (v === null || v === undefined || v === '' ? null : Number(v));
  const plural = (n, one, many) => n + ' ' + (n > 1 ? many : one);

  /** Libellé, détail chiffré et couleur de l'état de la machine (nul si la passe du jour n'a pas été jouée). */
  function stateText(st) {
    if (!st) return null;
    const px = (v) => (TIO.isNum(num(v)) ? F.price(num(v)) : F.dash);
    const armed = TIO.isNum(num(st.armedDays)) && st.armedDays > 0 ? ' · armé depuis ' + F.days(st.armedDays) : '';
    switch (st.kind) {
      case 'MOON_ACTIVE': return { tone: 'accent', label: 'To the moon actif', detail: 'plus haut ' + px(st.referencePrice) + ' · stop à ' + px(st.triggerPrice) };
      case 'SELL_TRAILING': return { tone: 'warn', label: 'Trailing-stop de vente actif', detail: 'plus haut ' + px(st.referencePrice) + ' · stop à ' + px(st.triggerPrice) + armed };
      case 'COOLDOWN': return { tone: 'accent', label: 'Cooldown', detail: 'reprise dans ' + F.days(st.cooldownDays) + (st.buyLocked ? ' · puis attente sous Down2 (' + px(st.down2) + ')' : '') };
      case 'LOCKED_UNTIL_DOWN2': return { tone: 'warn', label: 'Vendu · attente sous Down2', detail: 'clôture ' + px(st.close) + ' · Down2 ' + px(st.down2) };
      case 'BUY_TRAILING': return { tone: 'info', label: "Attente trailing-stop d'achat", detail: 'plus bas ' + px(st.referencePrice) + ' · déclenche à ' + px(st.triggerPrice) + armed };
      default: return { tone: 'muted', label: 'En surveillance', detail: (ZONES[st.zone] || 'Zone inconnue') + ' · clôture ' + px(st.close) };
    }
  }

  // ---------- fusion décisions + plans + liens : jour -> lignes ----------
  function buildDays(plans, decisions, bindings) {
    const byWallet = new Map(), byId = new Map();
    bindings.forEach((b) => { byId.set(b.id, b); byWallet.set(b.assetSymbol + '|' + b.walletId, b); });
    const days = new Map();
    const dayOf = (d) => { if (!days.has(d)) days.set(d, { day: d, plan: null, rows: new Map() }); return days.get(d); };
    const rowOf = (day, symbol, walletId) => {
      const key = symbol + '|' + walletId;
      if (!day.rows.has(key)) {
        const b = byWallet.get(key);
        day.rows.set(key, { symbol, walletId, exchange: b ? b.exchange : null, wallet: b ? b.walletName : null, decision: null, planAsset: null, plan: null });
      }
      return day.rows.get(key);
    };
    decisions.forEach((d) => {
      const day = dayOf(d.day), row = rowOf(day, d.assetSymbol, d.walletId);
      row.decision = d;
      const b = byId.get(d.bindingId);
      if (b) { row.exchange = b.exchange; row.wallet = b.walletName; }
    });
    plans.forEach((p) => {
      const day = dayOf(p.day);
      if (!day.plan) {
        day.plan = p;
        (p.assets || []).forEach((a) => { const r = rowOf(day, a.assetSymbol, a.walletId); r.planAsset = a; r.plan = p; });
      }
    });
    return [...days.values()].sort((a, b) => (a.day < b.day ? 1 : -1)).map((d) => {
      d.rows = [...d.rows.values()].sort((a, b) => (a.symbol === b.symbol ? String(a.exchange).localeCompare(String(b.exchange)) : a.symbol.localeCompare(b.symbol)));
      d.rows.forEach(enrich);
      return d;
    });
  }
  function enrich(r) {
    const pa = r.planAsset, d = r.decision;
    r.action = (pa && pa.action) || (d && d.action) || 'NONE';
    r.state = d ? d.strategyState : null;
    const fee = pa && pa.feeTestLevel;
    const reason = (pa && pa.blockReason && pa.blockReason !== 'NONE') ? pa.blockReason : (d && d.blockReason && d.blockReason !== 'NONE') ? d.blockReason : null;
    r.alert = fee === 'RED' ? { kind: 'down', label: TIO.labels.fee.RED } : fee === 'WARNING' ? { kind: 'warn', label: TIO.labels.fee.WARNING }
      : reason ? { kind: 'down', label: TIO.blockLabel(reason) } : pa && pa.warning ? { kind: 'warn', label: 'Avertissement' } : null;
    const step = pa && (pa.steps || [])[0];
    r.amount = d && num(d.amountUsdc) > 0 ? num(d.amountUsdc) : step && num(step.quoteAmount) > 0 ? num(step.quoteAmount) : null;
  }
  function summary(day) {
    const rows = day.rows, count = (f) => rows.filter(f).length;
    const buys = count((r) => r.action === 'BUY'), sells = count((r) => r.action === 'SELL'), blocked = count((r) => r.action === 'BLOCKED');
    const waiting = count((r) => r.action === 'NONE' && r.state && WAITING.has(r.state.kind));
    const idle = count((r) => r.action === 'NONE' && !(r.state && WAITING.has(r.state.kind)));
    const alerts = count((r) => r.alert);
    return [buys ? ui.chip(plural(buys, 'achat', 'achats'), 'ok') : null, sells ? ui.chip(plural(sells, 'vente', 'ventes'), 'down') : null,
      blocked ? ui.chip(plural(blocked, 'bloqué', 'bloqués'), 'warn') : null, waiting ? ui.chip(waiting + ' en attente', 'accent') : null,
      idle ? ui.chip(idle + ' rien à faire', 'plain') : null, alerts ? ui.chip(plural(alerts, 'alerte', 'alertes'), 'down') : null];
  }

  // ---------- détail d'une ligne ----------
  function stepLine(s) {
    const done = s.status === 'FILLED' || s.status === 'PARTIAL';
    const amount = num(s.quoteAmount) > 0 ? h('span', null, F.usd(num(s.quoteAmount))) : num(s.sz) > 0 ? h('span', null, F.qty(num(s.sz)))
      : s.rank > 1 ? h('span', { class: 'muted' }, 'selon la jambe précédente') : null;
    return h('div', { class: 'row small' }, ui.chip(TIO.labels.step[s.status] || s.status, TIO.statusKind(s.status)),
      h('span', { class: 'num' }, (s.side === 'SELL' ? '▽ ' : '▲ ') + s.instId), amount,
      done ? h('span', { class: 'muted' }, F.qty(Number(s.filledSz)) + ' @ ' + F.price(Number(s.avgFillPx))) : null,
      TIO.isNum(Number(s.feeAmount)) && s.feeAmount !== null ? h('span', { class: 'muted' }, 'frais ' + F.num(Number(s.feeAmount), 4) + ' ' + (s.feeCurrency || '')) : null,
      s.realSlippagePct !== null && s.realSlippagePct !== undefined ? h('span', { class: 'muted' }, 'slippage ' + F.pct(Number(s.realSlippagePct), 2)) : null,
      s.lastError ? h('span', { class: 'down' }, s.lastError) : null);
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
  const ficheHref = (row) => TIO.href('/app/assets/' + encodeURIComponent(row.symbol) + (row.walletId ? '?wallet=' + encodeURIComponent(row.walletId) : ''));
  function detailNode(row, day) {
    const pa = row.planAsset, st = stateText(row.state), plan = row.plan;
    return h('div', { class: 'stack' },
      h('div', { class: 'row' }, ui.coin(row.symbol), h('strong', null, row.symbol), row.exchange ? ui.tag(row.exchange) : null, h('span', { class: 'muted small' }, F.dayLong(day.day))),
      h('div', null, h('a', { class: 'btn', href: ficheHref(row) }, 'Voir sur la courbe →')),
      st ? h('div', { class: 'small' }, h('strong', null, st.label), h('div', { class: 'muted' }, st.detail)) : null,
      row.alert ? h('div', { class: 'warn small' }, row.alert.label + (pa && TIO.isNum(num(pa.costPct)) ? ' · coût du chemin ' + F.pct(num(pa.costPct), 2) : '')) : null,
      pa && pa.warning ? h('div', { class: 'warn small' }, pa.warning) : null,
      pa && pa.steps && pa.steps.length ? h('div', { class: 'stack' }, h('strong', { class: 'small' }, 'Ordres'), pa.steps.map(stepLine)) : null,
      row.decision && (row.decision.action === 'BUY' || row.decision.action === 'SELL') ? h('details', null, h('summary', { class: 'small' }, 'Pourquoi ce montant ?'), h('div', { class: 'stack' }, TIO.explain.why(row.symbol, row.decision))) : null,
      plan ? eventsBlock(plan) : null);
  }

  // ---------- rendu ----------
  function rowNode(row, day) {
    const st = stateText(row.state);
    const decisionChip = row.action === 'BUY' ? ui.chip('▲ Achat', 'ok') : row.action === 'SELL' ? ui.chip('▽ Vente', 'down')
      : row.action === 'BLOCKED' ? ui.chip('Bloqué', 'warn') : ui.chip('Rien à faire', 'plain');
    const out = row.planAsset && row.planAsset.outcome;
    const outChip = out === 'OK' ? ui.chip('Prêt', 'ok') : out === 'DISABLED' ? ui.chip('Désactivé', 'warn') : null;
    const info = h('button', { class: 'btn jr-info', type: 'button', 'aria-label': 'Détail ' + row.symbol + (row.exchange ? ' ' + row.exchange : '') }, 'ⓘ');
    info.addEventListener('click', () => TIO.ui.bubble(info, detailNode(row, day), 'Détail ' + row.symbol));
    return h('div', { class: 'jr-row' },
      h('div', { class: 'jr-asset' }, h('a', { class: 'jr-link', href: ficheHref(row), title: 'Voir ' + row.symbol + ' sur la courbe', style: { display: 'inline-flex', alignItems: 'center', gap: '8px', color: 'inherit', textDecoration: 'none' } }, ui.coin(row.symbol), h('strong', null, row.symbol)), row.exchange ? ui.tag(row.exchange) : h('span', { class: 'muted small' }, row.wallet || F.dash)),
      h('div', { class: 'jr-dec' }, decisionChip, outChip),
      h('div', { class: 'jr-state' }, st ? h('span', { class: 'jr-dot jr-dot--' + st.tone, 'aria-hidden': 'true' }) : null,
        st ? h('div', null, h('strong', null, st.label), h('div', { class: 'muted small' }, st.detail)) : h('span', { class: 'muted' }, F.dash)),
      h('div', { class: 'jr-amt num' }, row.amount !== null ? F.usd(row.amount) : h('span', { class: 'muted' }, F.dash)),
      h('div', { class: 'jr-alert' }, row.alert ? ui.chip(row.alert.label, row.alert.kind) : null, info));
  }
  function dayNode(day, rows, open, onToggle) {
    const p = day.plan;
    const head = h('summary', { class: 'jr-day-head' }, h('span', { class: 'chev', 'aria-hidden': 'true' }, '▸'), h('span', { class: 'num value-md' }, F.day(day.day)),
      p ? ui.chip(TIO.labels.plan[p.status] || p.status, TIO.statusKind(p.status)) : null, p && p.mode ? ui.chip(p.mode, 'plain') : null,
      h('span', { class: 'jr-sum' }, summary(day)), h('span', { class: 'grow' }),
      p && TIO.isNum(num(p.totalCostPct)) ? h('span', { class: 'muted small' }, 'coût ' + F.pct(num(p.totalCostPct), 2)) : null);
    const body = h('div', { class: 'fold-body jr-body' },
      p && p.expiresAt ? h('div', { class: 'muted small' }, (p.status === 'EXPIRED' ? 'Expiré ' : 'Expire ') + F.dateTime(p.expiresAt)) : null,
      p && p.blockReason ? h('div', { class: 'warn small' }, TIO.blockLabel(p.blockReason)) : null,
      rows.length ? h('div', { class: 'jr-rows' }, rows.map((r) => rowNode(r, day))) : h('div', { class: 'muted small' }, 'Aucune ligne ne correspond aux filtres.'));
    const d = h('details', { class: 'fold jr-day card', open: open ? true : null }, head, body);
    d.addEventListener('toggle', () => onToggle(day.day, d.open));
    return d;
  }

  TIO.start(function (root) {
    const f = { days: '30', asset: '', provider: '', state: '', alertsOnly: false, hideIdle: false };
    const opened = new Map();
    let model = [];
    const list = h('div', { class: 'stack' });
    const selects = h('div', { class: 'jr-selects' });
    const select = (label, options, key) => {
      const el = h('select', null, options.map((o) => h('option', { value: o[0] }, o[1])));
      el.addEventListener('change', () => { f[key] = el.value; paint(); });
      return ui.field(label, el);
    };
    const paintSelects = (bindings) => TIO.mount(selects,
      select('Actif', [['', 'Tous']].concat([...new Set(bindings.map((b) => b.assetSymbol))].map((s) => [s, s])), 'asset'),
      select('Provider', [['', 'Tous']].concat([...new Set(bindings.map((b) => b.exchange).filter(Boolean))].map((s) => [s, s])), 'provider'),
      select('État', [['', 'Tous']].concat(STATE_KINDS), 'state'));
    function paint() {
      TIO.ui.closeBubble();
      const none = !f.asset && !f.provider && !f.state && !f.alertsOnly && !f.hideIdle;
      const keep = (r) => (!f.asset || r.symbol === f.asset) && (!f.provider || r.exchange === f.provider) && (!f.state || (r.state && r.state.kind === f.state))
        && (!f.alertsOnly || r.alert) && (!f.hideIdle || r.action !== 'NONE' || r.alert);
      const shown = model.map((d, i) => ({ d, i, rows: d.rows.filter(keep) })).filter((x) => none || x.rows.length);
      TIO.mount(list, shown.length ? shown.map((x) => dayNode(x.d, x.rows, opened.has(x.d.day) ? opened.get(x.d.day) : x.i === 0, (k, v) => opened.set(k, v)))
        : ui.empty('Aucune ligne', 'Rien ne correspond à ces filtres sur la période.'));
    }
    const box = h('div');
    const load = () => ui.load(box, () => {
      const range = TIO.qs({ from: isoDay(-Number(f.days)), to: isoDay(0) });
      return Promise.all([TIO.get('/api/rainbow-live/execution-plans' + range), TIO.get('/api/rainbow-live/decisions' + range),
        TIO.safe(TIO.get('/api/rainbow-live/bindings'), [])]).then(([plans, decisions, bindings]) => {
        model = buildDays(plans, decisions, bindings);
        paintSelects(bindings);
        return model;
      });
    }, () => { paint(); return list; });
    const toggle = (label, key) => {
      const b = h('button', { class: 'pchip', type: 'button', 'aria-pressed': String(f[key]) }, label);
      b.addEventListener('click', () => { f[key] = !f[key]; b.setAttribute('aria-pressed', String(f[key])); paint(); });
      return b;
    };
    const filters = h('div', { class: 'card stack' }, h('div', { class: 'row row--between' },
      h('div', { class: 'chips' }, toggle('Alertes seulement', 'alertsOnly'), toggle('Masquer « Rien à faire »', 'hideIdle')),
      ui.periods(PERIODS, f.days, (v) => { f.days = v; load(); })), selects);
    load();
    TIO.mount(root, ui.pageHeader('Journal'), ui.demoBanner(), ui.alerts(), filters, box);
  });
})(window);
