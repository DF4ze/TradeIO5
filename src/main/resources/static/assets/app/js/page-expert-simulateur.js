/* Espace Expert · onglet Simulateur : rejeu libre (type FULL) des liens du wallet sur bougies D1.
   Formulaire (wallet, capital, bande/puissance/biais/marge, frais, plage, liens), graphiques synchronisés, tableau repliable
   (épisodes de la plage visible, agrégat zone × régime, répartition du gain) recalculé côté serveur sur la plage visible. */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, F = TIO.fmt, X = TIO.expert;
  const API = '/api/expert';

  TIO.start(function (root) {
    if (TIO.demo) { TIO.mount(root, ui.pageHeader('Espace Expert'), ui.empty('Pas de démo', "L'Espace Expert n'a pas de mode démo.")); return; }
    const st = { def: null, walletId: null, cash: '', min: '', max: '', power: '', bias: '', margin: '', costs: true, links: {}, range: { mode: '24m', from: null, to: null }, res: null, lastKey: null, job: null, rangeSeq: 0, rangeTimer: null, rangeBusy: false, rangePending: null };
    const formBox = h('div', { class: 'stack' }), statusBox = h('div', { class: 'stack' }), resBox = h('div', { class: 'stack' }), rangeBox = h('div');
    const chartBox = h('div', { class: 'xchart' }), tableBox = h('div', { class: 'stack' });
    const btn = h('button', { type: 'button', class: 'btn btn--primary' }, 'Simuler');
    const cancel = h('button', { type: 'button', class: 'btn', hidden: true }, 'Annuler');
    const stale = h('span', { class: 'chip chip--warn', hidden: true }, 'Résultat périmé : entrées modifiées depuis la simulation');
    let chart = null;

    const num = (v) => (v === '' || v === null || v === undefined || isNaN(Number(v)) ? null : Number(v));
    function body() {
      const rp = X.rangeParams(st.range);
      const full = { walletId: st.walletId, from: rp.from, to: rp.to, cash0: num(st.cash), exposureMin: num(st.min), exposureMax: num(st.max), power: num(st.power),
        bias: num(st.bias), regulationMarginPct: num(st.margin), withCosts: st.costs,
        links: Object.keys(st.links).map((id) => ({ bindingId: Number(id), enabled: st.links[id].enabled, baseAmount: num(st.links[id].baseAmount), bagPercent: num(st.links[id].bagPercent) })) };
      return { type: 'FULL', full };
    }
    const key = () => JSON.stringify(body());
    const touch = () => { stale.hidden = !(st.res && key() !== st.lastKey); };
    const anyLink = () => Object.values(st.links).some((l) => l.enabled);

    // ------------------------------------------------------------ formulaire
    function field(label, k, ph, hint, step) {
      const i = h('input', { type: 'number', step: step || 'any', placeholder: ph, value: st[k], 'aria-label': label });
      i.addEventListener('input', () => { st[k] = i.value; touch(); });
      return ui.field(label, i, hint);
    }
    function renderForm() {
      const d = st.def, m = d.modulation, lim = d.limits;
      const wsel = h('select', { 'aria-label': 'Wallet' }, d.wallets.map((w) => h('option', { value: String(w.walletId) }, w.name + (w.exchange ? ' · ' + w.exchange : ''))));
      wsel.value = String(st.walletId);
      wsel.addEventListener('change', () => { st.walletId = Number(wsel.value); load(); });
      const real = d.wallet ? F.usd(d.wallet.total, 0) : null;
      const costs = h('input', { type: 'checkbox', checked: st.costs });
      costs.addEventListener('change', () => { st.costs = costs.checked; touch(); });
      const links = d.links.map((l) => {
        const s = st.links[l.bindingId];
        const on = h('input', { type: 'checkbox', checked: s.enabled, disabled: !l.usable });
        on.addEventListener('change', () => { s.enabled = on.checked; btn.disabled = !anyLink() || !!st.job; touch(); });
        const base = h('input', { type: 'number', step: 'any', value: s.baseAmount, placeholder: 'base', 'aria-label': 'Montant de base ' + l.asset });
        base.addEventListener('input', () => { s.baseAmount = base.value; touch(); });
        const bag = h('input', { type: 'number', step: 'any', value: s.bagPercent, placeholder: 'bag %', 'aria-label': 'Bag % ' + l.asset });
        bag.addEventListener('input', () => { s.bagPercent = bag.value; touch(); });
        return h('tr', null, h('td', null, on), h('td', null, l.asset, h('div', { class: 'small muted' }, (l.affectationName || '—') + ' · ' + (l.source || '—'))),
          h('td', { class: 'num' }, l.priority), h('td', null, base), h('td', null, bag),
          h('td', { class: 'small muted' }, l.usable ? '' : (l.unusableReason || 'inutilisable')));
      });
      TIO.mount(formBox,
        h('div', { class: 'xgrid' },
          ui.field('Wallet', wsel, d.wallet ? 'Valeur réelle : ' + real + (d.wallet.asOf ? ' (au ' + F.dayLong(d.wallet.asOf) + ')' : '') : 'Valeur réelle inconnue'),
          field('Capital de départ (USDC)', 'cash', d.wallet ? String(Math.round(d.wallet.total * 100) / 100) : '', 'Vide = valeur du wallet réel. Départ 100 % USDC, position initiale nulle.'),
          field('Exposition min (%)', 'min', String(m.exposureMin), 'Bande du wallet'), field('Exposition max (%)', 'max', String(m.exposureMax)),
          field('Puissance', 'power', String(m.power), lim ? 'Entre ' + lim.powerMin + ' et ' + lim.powerMax : null),
          field('Biais', 'bias', String(m.bias), lim ? '± ' + lim.biasMax : null),
          field('Marge de régulation (%)', 'margin', m.regulationMarginPct === null || m.regulationMarginPct === undefined ? String(lim ? lim.regulationMarginPct : '') : String(m.regulationMarginPct))),
        h('label', { class: 'row' }, costs, h('span', null, 'Avec frais'), h('span', { class: 'small muted' }, '(estimation : frais/minimums du provider, non garantis)')),
        h('div', { class: 'scroll-x' }, h('table', { class: 'tbl' }, h('thead', null, h('tr', null, ['Actif', 'Lien', 'Prio', 'Montant de base', 'Bag %', ''].map((t) => h('th', null, t)))), h('tbody', null, links))));
      btn.disabled = !anyLink() || !!st.job;
    }

    // ------------------------------------------------------------ simulation
    function setIdle() { btn.disabled = !anyLink(); cancel.hidden = true; }
    function run() {
      if (st.job || !anyLink()) return;
      const b = body(), k = JSON.stringify(b);
      btn.disabled = true; cancel.hidden = false;
      const prog = h('div', { class: 'row small muted' }); TIO.mount(statusBox, prog);
      st.job = X.simulate(b, {
        onProgress: (t, pct) => TIO.mount(prog, t + ' ' + pct + ' %', ui.bar(pct)),
        onDone: (res) => { st.job = null; setIdle(); TIO.mount(statusBox); st.res = res; st.lastKey = k; stale.hidden = true; btn.textContent = 'Relancer'; showResult(res);
          X.memory.set('FULL', st.walletId, '', { range: st.range, cash: st.cash, min: st.min, max: st.max, power: st.power, bias: st.bias, margin: st.margin, costs: st.costs, links: st.links }); },
        onError: (e) => { st.job = null; setIdle(); TIO.mount(statusBox, h('div', { class: 'banner banner--down', role: 'alert' }, X.errorText(e))); },
      });
    }
    btn.addEventListener('click', run);
    cancel.addEventListener('click', () => { if (st.job) { st.job.cancel(); st.job = null; } setIdle(); TIO.mount(statusBox, h('div', { class: 'small muted' }, 'Simulation annulée.')); });

    function showResult(res) {
      const m = res.meta;
      const ddChip = h('span', { class: 'chip', role: 'status' });
      TIO.mount(resBox,
        h('div', { class: 'small muted' }, F.dayLong(m.from) + ' → ' + F.dayLong(m.to) + ' · capital ' + F.usd(m.cash0, 0) + ' · ' + m.links.map((l) => l.asset + ' (' + l.usedSource + ', ' + l.candles + ' bougies)').join(' · ')),
        res.drawdown ? h('div', { class: 'row' }, ddChip) : null,
        (m.warnings || []).map((w) => h('div', { class: 'banner banner--warn' }, w)),
        h('div', { class: 'scroll-x' }, h('table', { class: 'tbl' }, h('tbody', null, (res.lines || []).map((l) => h('tr', null, h('td', { class: 'muted' }, l.label),
          h('td', { class: 'num' }, l.value === null || l.value === undefined ? (l.text || F.dash) : (l.unit === '$' ? F.usd(l.value, Math.abs(l.value) >= 1000 ? 0 : 2) : l.unit === '%' ? F.pct(l.value, 1) : F.num(l.value, 2)) + (l.text ? ' · ' + l.text : ''))))))));
      if (!chart) chart = TIO.simChart(chartBox);
      chart.update(res);
      chart.onRange(onVisibleRange);
      const paintVerdict = () => {
        const p = chart.profile(), v = p && res.drawdown && res.drawdown.verdicts.find((x) => x.profile === p);
        ddChip.className = 'chip' + (v ? (v.respected ? ' chip--up' : ' chip--down') : '');
        ddChip.textContent = TIO.simChart.verdictText(res, p) + ' (signalement seul, aucun effet sur les ordres)';
      };
      chart.onProfile(paintVerdict); paintVerdict();
      chart.onEpisodeHover((e) => { chart.highlight(e); markRow(e); });
      chart.onEpisodeClick((e) => { chart.selectAsset(e.asset); chart.zoomTo(e.from, e.to); });
      renderReport(res.episodes, null);
    }

    // ------------------------------------------------------------ tableau : épisodes / agrégat / gain
    const rows = new Map();
    function markRow(e) { rows.forEach((tr, ep) => tr.classList.toggle('is-hl', ep === e)); }
    const usd = (v) => F.usd(v, Math.abs(v) >= 1000 ? 0 : 2);
    const gainCells = (gn) => [gn.exposure, gn.timing, gn.fees, gn.total].map((v) => h('td', { class: 'num ' + TIO.sign(v) }, usd(v)));
    function renderReport(rep, state) {
      rows.clear();
      const stateEl = state ? (state.kind === 'error' ? h('div', { class: 'banner banner--down', role: 'alert' }, state.text) : h('div', { class: 'small muted' }, state.text)) : null;
      if (!rep) { TIO.mount(tableBox, stateEl); return; }
      const eps = rep.episodes || [];
      const epTable = eps.length ? h('div', { class: 'scroll-x' }, h('table', { class: 'tbl' },
        h('thead', null, h('tr', null, ['Actif', 'Régime', 'Zone', 'Début', 'Fin', 'Jours', 'Acheté', 'Vendu', 'PnL réalisé', 'PnL latent', 'PnL total'].map((t) => h('th', null, t)))),
        h('tbody', null, eps.map((e) => {
          const tr = h('tr', { class: 'xsim__row' }, h('td', null, e.asset), h('td', null, e.regime), h('td', null, e.zoneLabel), h('td', null, F.dayLong(e.from)), h('td', null, F.dayLong(e.to)), h('td', { class: 'num' }, e.days),
            h('td', { class: 'num' }, usd(e.boughtUsdc)), h('td', { class: 'num' }, usd(e.soldUsdc)), h('td', { class: 'num ' + TIO.sign(e.pnlRealized) }, usd(e.pnlRealized)),
            h('td', { class: 'num ' + TIO.sign(e.pnlLatent) }, usd(e.pnlLatent)), h('td', { class: 'num ' + TIO.sign(e.pnlTotal) }, usd(e.pnlTotal)));
          tr.addEventListener('mouseenter', () => chart && chart.highlight(e)); tr.addEventListener('mouseleave', () => chart && chart.highlight(null));
          tr.addEventListener('click', () => chart && (chart.selectAsset(e.asset), chart.zoomTo(e.from, e.to)));
          rows.set(e, tr); return tr;
        })))) : h('div', { class: 'small muted' }, 'Aucun épisode sur cette plage.');
      const agg = (rep.aggregates || []).length ? h('div', { class: 'scroll-x' }, h('table', { class: 'tbl' },
        h('thead', null, h('tr', null, ['Régime', 'Zone', 'Épisodes', 'Jours', 'Acheté', 'Vendu', 'PnL total', 'Gain total'].map((t) => h('th', null, t)))),
        h('tbody', null, rep.aggregates.map((a) => h('tr', null, h('td', null, a.regime), h('td', null, a.zoneLabel), h('td', { class: 'num' }, a.episodes), h('td', { class: 'num' }, a.days),
          h('td', { class: 'num' }, usd(a.boughtUsdc)), h('td', { class: 'num' }, usd(a.soldUsdc)), h('td', { class: 'num ' + TIO.sign(a.pnlTotal) }, usd(a.pnlTotal)),
          h('td', { class: 'num ' + TIO.sign(a.gain.total) }, usd(a.gain.total))))))) : null;
      const gain = rep.gain ? h('div', { class: 'scroll-x' }, h('table', { class: 'tbl' },
        h('thead', null, h('tr', null, ['', 'Exposition', 'Timing', 'Frais', 'Total'].map((t) => h('th', null, t)))),
        h('tbody', null, h('tr', null, h('td', null, h('strong', null, 'Total')), gainCells(rep.gain.total)),
          (rep.gain.assets || []).map((a) => h('tr', null, h('td', null, a.asset), gainCells(a.gain)))))) : null;
      TIO.mount(tableBox, stateEl, h('div', { class: 'small muted' }, F.dayLong(rep.from) + ' → ' + F.dayLong(rep.to) + ' · PnL total ' + usd(rep.pnlTotal)),
        epTable, agg ? h('h4', null, 'Agrégat zone × régime') : null, agg, gain ? h('h4', null, 'Répartition du gain (stratégie − DCA fixe)') : null, gain,
        (rep.notes || []).map((n) => h('div', { class: 'small muted' }, n)));
    }

    // recalcul serveur sur la plage visible : rebond ~400 ms, une requête à la fois, la dernière gagne
    function onVisibleRange(v) {
      if (!st.res || !st.res.episodes) return;
      g.clearTimeout(st.rangeTimer);
      st.rangeTimer = g.setTimeout(() => fetchRange(v), 400);
    }
    async function fetchRange(v) {
      if (st.rangeBusy) { st.rangePending = v; return; }
      st.rangeBusy = true;
      const id = st.res.episodes.resultId;
      renderReport(st.report || st.res.episodes, { kind: 'info', text: 'Recalcul sur la plage visible…' });
      try {
        const rep = await X.req('GET', API + '/simulations/episodes/' + encodeURIComponent(id) + '?from=' + v.from + '&to=' + v.to);
        st.report = rep;
        renderReport(rep, null);
        if (chart) chart.setEpisodes(rep.episodes);
      } catch (e) {
        const gone = e && e.status === 404;
        renderReport(st.report || st.res.episodes, { kind: 'error', text: gone ? 'Résultat expiré : relancez la simulation.' : X.errorText(e) });
      } finally {
        st.rangeBusy = false;
        if (st.rangePending) { const p = st.rangePending; st.rangePending = null; fetchRange(p); }
      }
    }

    // ------------------------------------------------------------ chargement des défauts
    async function load() {
      TIO.mount(formBox, ui.loading(3));
      try {
        const d = await X.req('GET', API + '/simulations/full/defaults' + (st.walletId ? '?walletId=' + st.walletId : ''));
        st.def = d; st.walletId = d.walletId; st.withCosts = d.withCosts;
        const mem = X.memory.get('FULL', st.walletId, '');
        st.links = {};
        d.links.forEach((l) => {
          const old = mem && mem.links && mem.links[l.bindingId];
          st.links[l.bindingId] = { enabled: old ? old.enabled && l.usable : l.usable, baseAmount: old ? old.baseAmount : '', bagPercent: old ? old.bagPercent : '' };
        });
        if (mem) { st.range = mem.range || st.range; ['cash', 'min', 'max', 'power', 'bias', 'margin'].forEach((k) => { st[k] = mem[k] || ''; }); st.costs = mem.costs !== false; btn.textContent = 'Relancer'; }
        else { st.costs = d.withCosts; btn.textContent = 'Simuler'; }
        renderForm();
        TIO.mount(rangeBox, X.rangeControl(st.range, touch));
      } catch (e) { TIO.mount(formBox, ui.error(e, load)); }
    }

    root.classList.add('tio-main--wide');
    TIO.mount(root, ui.pageHeader('Espace Expert', 'Simulateur'), X.mobileNotice(), X.tabs('simulateur'),
      h('div', { class: 'grid' }, ui.card('Paramètres', formBox, rangeBox, ui.cardOpts({ span: true })),
        ui.card('Simulation', h('div', { class: 'row' }, btn, cancel, stale), statusBox, resBox, chartBox, ui.cardOpts({ span: true })),
        h('details', { class: 'card card--span xsim__details' }, h('summary', { class: 'small' }, 'Épisodes · agrégat · répartition du gain'), tableBox)));
    load();
  });
})(window);
