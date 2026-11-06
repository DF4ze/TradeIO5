/*
 * Onglet Console : filtres (jour / user / statut), tableau de plans de tous les users, actions groupées côté serveur
 * (reset / armer / exécuter / réconcilier : éligibilité calculée UNIQUEMENT par le serveur, prévisualisation dryRun) et, dans
 * le détail d'un plan, la procédure individuelle en 7 étapes (plan -> armer -> confirmation propriétaire -> lever le kill
 * switch -> exécuter -> réconcilier -> réengagement). Motifs traduits par les tables uniques de AdminCore.
 */
(function () {
  'use strict';
  const core = window.AdminCore;
  if (!core) { return; }
  const { el, api, run, byId } = core;

  const EXEC = '/api/admin/execution';
  const IN_FLIGHT_STEPS = ['SUBMITTED', 'UNKNOWN'];
  const BULK = EXEC + '/plans/bulk/';
  const STATUSES = ['PLANNED', 'BLOCKED', 'EXPIRED', 'DISABLED', 'EXECUTING', 'EXECUTED', 'PARTIAL', 'FAILED', 'CANCELLED'];
  const PROBLEM_STATUSES = ['EXPIRED', 'BLOCKED'];
  const RUN_CONFIRM = 'EXECUTER';
  const todayUtc = () => new Date().toISOString().slice(0, 10);
  const local = { plans: [], bindings: [], users: [], selectedId: null, status: '', checked: new Set(), lastBulk: null };

  const selectedPlan = () => local.plans.find((p) => p.id === local.selectedId) || null;
  const visiblePlans = () => local.plans.filter((p) => !local.status || p.status === local.status);
  const kill = () => (core.state ? core.state.killSwitch : true);

  /** Contexte de la procédure pour le plan sélectionné. */
  function context(plan = selectedPlan()) {
    if (!plan) { return { plan: null, bindings: [], active: false, inFlight: false, armed: false, confirmed: false }; }
    const withSteps = new Set(plan.assets.filter((a) => a.steps.length > 0).map((a) => a.assetSymbol));
    const bindings = local.bindings.filter((b) => b.userId === plan.userId && withSteps.has(b.assetSymbol));
    return {
      plan, bindings,
      active: plan.status === 'PLANNED' || plan.status === 'EXECUTING',
      inFlight: plan.status === 'EXECUTING' || plan.assets.some((a) => a.steps.some((s) => IN_FLIGHT_STEPS.includes(s.status))),
      armed: bindings.length > 0 && bindings.every((b) => b.firstLiveArmed),
      confirmed: bindings.length > 0 && bindings.every((b) => b.firstLiveConfirmed)
    };
  }

  /* ------------------------------------------------------------------ rendu */

  const planLabel = (p) => '#' + p.id + ' · ' + p.day + ' r' + p.revision + ' · ' + p.username + ' · ' + p.status;

  /* ------------------------------------------------------------------ filtres, chips, tableau */

  const dayFilter = () => (byId('adm-f-alldays').checked ? '' : byId('adm-f-day').value);

  function renderFilters() {
    const userSel = byId('adm-f-user');
    const current = userSel.value;
    userSel.replaceChildren(el('option', { value: '', text: 'Tous' }),
      ...local.users.map((u) => el('option', { value: u.id, text: u.username })));
    userSel.value = current;
    const statusSel = byId('adm-f-status');
    statusSel.replaceChildren(el('option', { value: '', text: 'Tous' }), ...STATUSES.map((st) => el('option', { value: st, text: st })));
    statusSel.value = local.status;
  }

  function renderChips() {
    const counts = new Map();
    local.plans.forEach((p) => counts.set(p.status, (counts.get(p.status) || 0) + 1));
    const chip = (status, label, n) => {
      const b = el('button', { type: 'button', class: 'btn btn-sm ' + (local.status === status ? 'btn-primary' : 'btn-outline-secondary'),
        text: label + ' ' + n });
      b.addEventListener('click', () => { local.status = status; render(); });
      return b;
    };
    byId('adm-chips').replaceChildren(chip('', 'Tous', local.plans.length),
      ...STATUSES.filter((st) => counts.has(st)).map((st) => chip(st, st, counts.get(st))));
  }

  function planRow(p) {
    const ctx = context(p);
    const withSteps = p.assets.filter((a) => a.steps.length > 0);
    const steps = p.assets.reduce((n, a) => n + a.steps.length, 0);
    const box = el('input', { type: 'checkbox', 'aria-label': 'Sélectionner le plan ' + p.id, checked: local.checked.has(p.id) });
    box.addEventListener('change', () => { if (box.checked) { local.checked.add(p.id); } else { local.checked.delete(p.id); } renderBulkBar(); });
    const open = el('button', { type: 'button', class: 'btn btn-sm btn-outline-secondary', text: local.selectedId === p.id ? '▾' : '▸',
      'aria-label': 'Détail du plan ' + p.id });
    open.addEventListener('click', () => { local.selectedId = local.selectedId === p.id ? null : p.id; render(); });
    const problem = p.executionBlockReason || p.blockReason || '';
    return el('tr', null, el('td', null, box), el('td', { text: p.day }), el('td', { text: p.username }), el('td', { text: 'r' + p.revision }),
      el('td', { text: p.status }), el('td', { text: withSteps.length + ' / ' + steps }),
      el('td', { text: ctx.bindings.length ? (ctx.armed ? '✔' : '✘') : core.DASH }),
      el('td', { text: ctx.bindings.length ? (ctx.confirmed ? '✔' : '✘') : core.DASH }),
      el('td', { text: problem }), el('td', null, open));
  }

  function renderTable() {
    const plans = visiblePlans();
    byId('adm-plans-body').replaceChildren(...(plans.length ? plans.map(planRow)
      : [el('tr', null, el('td', { colspan: 10 }, core.empty('Aucun plan pour ces filtres — « Lancer la passe » puis « Planifier tous ».')))]));
    byId('adm-plans-count').textContent = plans.length + ' plan(s) affiché(s) sur ' + local.plans.length;
    byId('adm-sel-all').checked = plans.length > 0 && plans.every((p) => local.checked.has(p.id));
  }

  function renderBulkBar() {
    const ids = [...local.checked];
    const users = new Set(local.plans.filter((p) => local.checked.has(p.id)).map((p) => p.userId));
    byId('adm-bulk-bar').classList.toggle('d-none', ids.length === 0 && !local.lastBulk);
    byId('adm-bulk-count').textContent = ids.length + ' sélectionné(s) · ' + users.size + ' user(s)';
    ['reset', 'arm', 'run', 'reconcile'].forEach((a) => { byId('adm-bulk-' + a).disabled = ids.length === 0; });
  }

  function renderDetail() {
    const plan = selectedPlan();
    byId('adm-plan-detail').classList.toggle('d-none', !plan);
    if (!plan) { return; }
    byId('adm-detail-title').textContent = planLabel(plan);
    renderSummary();
  }

  function stepRow(s) {
    return el('tr', null, el('td', { text: s.rank }), el('td', { text: s.instId }), el('td', { text: s.side }),
      el('td', { text: s.sz == null ? core.DASH : s.sz }), el('td', { text: s.px == null ? core.DASH : s.px }),
      el('td', { text: s.quoteAmount == null ? core.DASH : s.quoteAmount }),
      el('td', { text: s.status }), el('td', { text: s.filledSz == null ? core.DASH : s.filledSz }),
      el('td', { text: s.avgFillPx == null ? core.DASH : s.avgFillPx }),
      el('td', { text: s.feeAmount == null ? core.DASH : s.feeAmount + ' ' + (s.feeCurrency || '') }),
      el('td', { text: s.lastError || '' }));
  }

  function renderSummary() {
    const box = byId('adm-plan-summary');
    const plan = selectedPlan();
    if (!plan) { box.replaceChildren(); return; }
    const head = el('div', { class: 'small' },
      el('strong', { text: 'Plan #' + plan.id + ' : ' }), plan.status + ' · coût ' + (plan.totalCostPct == null ? core.DASH : plan.totalCostPct + ' %')
      + ' · Fee Test ' + (plan.feeTestLevel || core.DASH) + ' · expire ' + core.fmtDate(plan.expiresAt));
    const exchangeOf = (a) => (local.bindings.find((b) => b.walletId === a.walletId && b.userId === plan.userId) || {}).exchange;
    const assets = plan.assets.map((a) => el('div', { class: 'small mt-1' },
      el('strong', { text: a.assetSymbol }), el('span', { class: 'text-muted', text: ' @' + (exchangeOf(a) || core.DASH) }), ' ' + a.action + ' → ' + a.outcome + (a.blockReason ? ' (' + a.blockReason + ')' : ''),
      a.steps.length ? el('table', { class: 'table table-sm mb-1' },
        el('thead', null, el('tr', null, ['#', 'Paire', 'Sens', 'Taille', 'Prix plafond', 'Montant', 'Statut', 'Rempli', 'Prix moy.', 'Frais', 'Erreur'].map((h) => el('th', { text: h })))),
        el('tbody', null, a.steps.map(stepRow))) : null));
    const st = core.state;
    const caps = st ? el('div', { class: 'small text-muted mt-1', text: 'Plafond par ordre = ' + st.maxOrderMultiplier
      + ' × baseAmount du preset (max absolu ' + st.absoluteMaxOrderUsd + ' USD) · plafond jour = ' + st.maxDayMultiplier
      + ' × somme des baseAmount (max absolu ' + st.absoluteMaxDayUsd + ' USD). Seules les étapes en USDC (colonne Montant) comptent.' }) : null;
    box.replaceChildren(head, ...assets, caps, ...core.explainBlocks(plan.blockReason), ...core.explainBlocks(plan.executionBlockReason));
  }

  function setState(n, text, cls) {
    const node = byId('adm-state-' + n);
    node.textContent = text;
    node.className = 'small ' + (cls || '');
  }

  function renderArmList(ctx) {
    const box = byId('adm-arm-list');
    if (!ctx.bindings.length) { box.replaceChildren(core.empty(ctx.plan ? 'Aucun binding de cet utilisateur avec des étapes.' : '')); return; }
    box.replaceChildren(...ctx.bindings.map((b) => {
      const btn = el('button', { type: 'button', class: 'btn btn-sm btn-outline-warning ms-2', text: 'Armer',
        disabled: !ctx.active || b.firstLiveArmed });
      btn.addEventListener('click', () => run(btn, async () => { await api('POST', EXEC + '/bindings/' + b.id + '/arm'); await reload(); }));
      return el('div', { class: 'small' }, b.assetSymbol + ' (binding #' + b.id + ', ' + (b.exchange || core.DASH) + ') — '
        + (b.firstLiveArmed ? (b.firstLiveConfirmed ? 'armé et confirmé' : 'armé, confirmation attendue') : 'non armé'), btn);
    }));
  }

  function render() {
    renderFilters();
    renderChips();
    renderTable();
    renderBulkBar();
    renderDetail();
    byId('adm-ks-console').textContent = kill() ? 'engagé' : 'levé';
    byId('adm-ks-console').className = kill() ? 'text-success' : 'text-danger';
    byId('adm-btn-lift-global').disabled = !kill();
    byId('adm-btn-engage-global').disabled = kill();
    const ctx = context();
    const plan = ctx.plan;
    setState(1, plan ? 'plan #' + plan.id + ' ' + plan.status : 'aucun plan', plan ? 'text-success' : 'text-muted');
    renderArmList(ctx);
    setState(2, !plan ? '—' : (ctx.bindings.length === 0 ? 'aucun binding à armer' : (ctx.armed ? '✔ armé' : 'à armer')), ctx.armed ? 'text-success' : 'text-muted');
    setState(3, ctx.confirmed ? '✔ confirmé par le propriétaire' : (ctx.armed ? '⏳ en attente du propriétaire' : '— (armer d\'abord)'),
      ctx.confirmed ? 'text-success' : 'text-muted');
    byId('adm-btn-reset').disabled = !(plan && ['PLANNED', 'BLOCKED', 'EXPIRED', 'DISABLED', 'CANCELLED'].includes(plan.status)
      && plan.assets.every((a) => a.steps.every((s) => s.status === 'PLANNED')));
    byId('adm-btn-lift').disabled = !(ctx.active && ctx.armed && ctx.confirmed && kill());
    setState(4, kill() ? 'engagé' : 'levé', kill() ? 'text-success' : 'text-danger');
    byId('adm-btn-run').disabled = !(plan && plan.status === 'PLANNED');
    setState(5, plan && plan.status === 'PLANNED' ? (kill() ? 'kill switch engagé : le run sera refusé et expliqué' : 'prêt') : '—', 'text-muted');
    byId('adm-btn-reconcile').disabled = !ctx.inFlight;
    setState(6, ctx.inFlight ? 'à réconcilier (étapes SUBMITTED / UNKNOWN ou plan EXECUTING)' : '—', ctx.inFlight ? 'text-warning' : 'text-muted');
    byId('adm-btn-engage').disabled = kill();
    setState(7, kill() ? 'engagé' : 'levé — à rengager', kill() ? 'text-success' : 'text-danger');
  }

  function renderResult(r, extra) {
    const box = byId('adm-result');
    box.className = 'border rounded p-2 small';
    box.replaceChildren(
      el('div', null, el('strong', { text: 'Plan #' + r.planId + ' : ' }), 'statut ' + r.status + ' · ordres envoyés ' + r.sent),
      ...(Object.keys(r.blocked).length ? core.explainBlocks(r.blocked)
        : (selectedPlan() && selectedPlan().executionBlockReason ? core.explainBlocks(selectedPlan().executionBlockReason)
          : [el('div', { class: 'text-success', text: 'Aucun blocage.' })])),
      extra ? el('div', { class: 'text-muted', text: extra }) : null);
  }

  /* ------------------------------------------------------------------ données */

  async function loadPlans() {
    const params = new URLSearchParams();
    const day = dayFilter();
    if (day) { params.set('from', day); params.set('to', day); }
    if (byId('adm-f-user').value) { params.set('userId', byId('adm-f-user').value); }
    const query = params.toString();
    local.plans = await api('GET', EXEC + '/plans' + (query ? '?' + query : ''));
    const ids = new Set(local.plans.map((p) => p.id));
    local.checked = new Set([...local.checked].filter((id) => ids.has(id)));
    if (!ids.has(local.selectedId)) { local.selectedId = null; }
  }
  async function loadBindings() {
    local.bindings = await api('GET', '/api/admin/bindings');
  }
  async function loadUsers() {
    if (!local.users.length) { local.users = await api('GET', '/api/admin/users'); }
  }
  async function reload() {
    await Promise.all([loadPlans(), loadBindings(), loadUsers(), core.refreshState()]);
    render();
  }

  /* ------------------------------------------------------------------ actions */

  async function plan() {
    const day = dayFilter();
    const query = day ? '?day=' + encodeURIComponent(day) : '';
    const s = await api('POST', EXEC + '/plan' + query);
    const box = byId('adm-result');
    box.className = 'border rounded p-2 small';
    box.textContent = 'Plan ' + s.day + ' : ' + s.planned + ' planifié(s), ' + s.blocked + ' bloqué(s), '
      + s.disabled + ' désactivé(s), ' + s.unchanged + ' inchangé(s), ' + s.skipped + ' ignoré(s), ' + s.errors + ' erreur(s).';
    await reload();
  }

  /** Remise à zéro du plan sélectionné (bloqué / expiré / désactivé, aucun ordre parti) : nouvelle révision. */
  async function resetPlan() {
    const p = selectedPlan();
    const s = await api('POST', EXEC + '/plans/' + p.id + '/reset');
    const box = byId('adm-result');
    box.className = 'border rounded p-2 small';
    box.textContent = 'Plan #' + p.id + ' réinitialisé : ' + s.planned + ' planifié(s), ' + s.blocked + ' bloqué(s), '
      + s.disabled + ' désactivé(s), ' + s.skipped + ' ignoré(s).';
    await reload();
    // la révision réinitialisée est remplacée par un nouveau plan (autre id) : le détail suit la nouvelle révision
    const next = local.plans.find((x) => x.userId === p.userId && x.day === p.day);
    if (next) { local.selectedId = next.id; render(); }
  }

  /** Passe Rainbow live manuelle (signal + snapshot live par lien) : prérequis du plan. Aucun ordre. */
  async function livePass() {
    const day = dayFilter();
    const query = day ? '?day=' + encodeURIComponent(day) : '';
    const s = await api('POST', '/api/admin/rainbow-live/run' + query);
    const box = byId('adm-result');
    box.className = 'border rounded p-2 small';
    box.textContent = 'Passe live ' + s.day + ' : ' + s.processed + ' traité(s), ' + s.skipped + ' ignoré(s), '
      + s.errors + ' erreur(s)' + (s.totalMs == null ? '' : ' · ' + s.totalMs + ' ms (chargement ' + s.loadMs + ', signal ' + s.signalMs
      + ', liens ' + s.linksMs + ')') + '.';
  }

  /** Resynchronisation forcée de l'état moteur Trend Mix jusqu'à la veille (rejeu, aucun run ni ordre). */
  async function resync() {
    const s = await api('POST', '/api/admin/rainbow-live/resync');
    const box = byId('adm-result');
    box.className = 'border rounded p-2 small';
    box.textContent = 'Resync jusqu\'au ' + s.toDay + ' : ' + s.resynced + ' resynchronisé(s), ' + s.upToDate
      + ' à jour, ' + s.skipped + ' ignoré(s), ' + s.errors + ' erreur(s).';
  }

  async function runPlan() {
    const p = selectedPlan();
    const wasLifted = !kill();
    const r = await api('POST', EXEC + '/run?planId=' + p.id);
    await reload();
    renderResult(r, wasLifted ? 'Kill switch rengagé automatiquement par le serveur.' : null);
  }

  async function setKillSwitch(engaged) {
    await api('PUT', EXEC + '/kill-switch', { engaged });
    await reload();
    document.dispatchEvent(new CustomEvent('tio:killswitch-changed'));
  }

  /* ------------------------------------------------------------------ actions groupées (serveur = seule source d'éligibilité) */

  const bulkBox = () => byId('adm-bulk-panel');

  function renderBulkResult(r) {
    local.lastBulk = r;
    const failed = r.items.filter((i) => i.outcome === 'FAILED').map((i) => i.planId);
    const retry = el('button', { type: 'button', class: 'btn btn-sm btn-outline-danger', text: 'Relancer les erreurs', disabled: failed.length === 0 });
    retry.addEventListener('click', () => { local.checked = new Set(failed); local.lastBulk = null; bulkBox().replaceChildren(); render(); });
    bulkBox().replaceChildren(
      el('div', null, el('strong', { text: r.action + (r.dryRun ? ' (prévisualisation)' : '') + ' — lot ' + r.batchId + ' : ' }),
        r.done + ' fait(s), ' + r.skipped + ' sauté(s), ' + r.failed + ' échec(s) · ' + r.durationMs + ' ms'),
      core.table(['Plan', 'User', 'Résultat', 'Raison'], r.items.map((i) => [String(i.planId), i.username || core.DASH, i.outcome,
        (i.reason ? core.bulkReason(i.reason) : '') + (i.detail ? ' ' + i.detail : '')])),
      r.dryRun ? null : retry);
  }

  /** Prévisualisation (dryRun) puis, après confirmation, appel réel. Rien n'est calculé côté JS. */
  async function bulk(action) {
    const planIds = [...local.checked];
    const path = BULK + action;
    const preview = await api('POST', path, { planIds, dryRun: true });
    const eligible = preview.items.filter((i) => i.outcome === 'ELIGIBLE');
    const skipped = preview.items.filter((i) => i.outcome !== 'ELIGIBLE');
    if (!eligible.length) { renderBulkResult(preview); return; }
    let confirm = null;
    if (action === 'run') {
      const orders = eligible.reduce((n, i) => n + (i.orders || 0), 0);
      const total = eligible.reduce((n, i) => n + Number(i.quoteTotal || 0), 0);
      const users = new Set(eligible.map((i) => i.username));
      const ok = await core.askConfirm('Exécuter ' + eligible.length + ' plan(s) en LIVE',
        [eligible.length + ' plans, ' + orders + ' ordres, total ' + total.toFixed(2) + ' USDC, ' + users.size + ' user(s) : ' + [...users].join(', '),
          skipped.length ? skipped.length + ' plan(s) inéligible(s) ignoré(s).' : 'Tous les plans sont éligibles.'], RUN_CONFIRM, 'Exécuter');
      if (!ok) { return; }
      confirm = RUN_CONFIRM;
    } else {
      renderBulkResult(preview);
      const go = await new Promise((resolve) => {
        const yes = el('button', { type: 'button', class: 'btn btn-sm btn-primary', text: 'Confirmer : ' + eligible.length + ' / ' + planIds.length + ' éligibles' });
        const no = el('button', { type: 'button', class: 'btn btn-sm btn-outline-secondary ms-1', text: 'Annuler' });
        yes.addEventListener('click', () => resolve(true));
        no.addEventListener('click', () => resolve(false));
        bulkBox().append(el('div', { class: 'mt-1' }, yes, no));
      });
      if (!go) { bulkBox().replaceChildren(); return; }
    }
    const result = await api('POST', path, { planIds, dryRun: false, confirm });
    await reload();
    renderBulkResult(result);
    renderBulkBar();
  }

  ['reset', 'arm', 'run', 'reconcile'].forEach((a) => {
    const btn = byId('adm-bulk-' + a);
    btn.addEventListener('click', () => exclusive(btn, () => bulk(a), 'Lot en cours : la Console est verrouillée jusqu\'à la fin (un lot peut durer 30 s et plus)…'));
  });

  function shiftDay(delta) {
    const d = new Date((byId('adm-f-day').value || todayUtc()) + 'T00:00:00Z');
    d.setUTCDate(d.getUTCDate() + delta);
    byId('adm-f-day').value = d.toISOString().slice(0, 10);
    return run(null, reload);
  }

  byId('adm-f-day').value = todayUtc();
  byId('adm-f-prev').addEventListener('click', () => shiftDay(-1));
  byId('adm-f-next').addEventListener('click', () => shiftDay(1));
  byId('adm-f-day').addEventListener('change', () => run(null, reload));
  byId('adm-f-alldays').addEventListener('change', () => run(null, reload));
  byId('adm-f-user').addEventListener('change', () => run(null, reload));
  byId('adm-f-status').addEventListener('change', (e) => { local.status = e.target.value; render(); });
  byId('adm-sel-all').addEventListener('change', (e) => {
    visiblePlans().forEach((p) => { if (e.target.checked) { local.checked.add(p.id); } else { local.checked.delete(p.id); } });
    render();
  });
  byId('adm-sel-problem').addEventListener('click', () => {
    visiblePlans().filter((p) => PROBLEM_STATUSES.includes(p.status)).forEach((p) => local.checked.add(p.id));
    render();
  });
  byId('adm-detail-close').addEventListener('click', () => { local.selectedId = null; render(); });

  /**
   * Action exclusive : toute la Console est verrouillée tant qu'elle dure (un lot peut prendre 30 s et plus : un second clic
   * lancerait une action concurrente sur une vue périmée). En cas d'erreur le tableau est rechargé, la vue ayant pu changer
   * (ex. plan remplacé par une nouvelle révision) avant que l'erreur ne soit affichée.
   */
  let busy = false;
  function exclusive(button, action, note) {
    if (busy) { return Promise.resolve(); }
    busy = true;
    const locked = [...byId('adm-pane-console').querySelectorAll('button, select, input')].filter((c) => !c.disabled);
    locked.forEach((c) => { c.disabled = true; });
    core.showAlert(note || 'Action en cours : la Console est verrouillée jusqu\'à la fin (elle peut durer 30 s et plus)…');
    return run(button, async () => {
      try {
        return await action();
      } catch (e) {
        await reload().catch(() => { /* l'erreur d'origine prime */ });
        throw e;
      } finally {
        locked.forEach((c) => { c.disabled = false; });
        busy = false;
        core.hideAlert();
        render();
      }
    });
  }

  function bind(id, action) {
    const btn = byId(id);
    btn.addEventListener('click', () => exclusive(btn, action));
  }

  bind('adm-btn-pass', livePass);
  bind('adm-btn-resync', resync);
  bind('adm-btn-plan', plan);
  bind('adm-btn-reset', resetPlan);
  bind('adm-btn-lift', () => setKillSwitch(false));
  bind('adm-btn-lift-global', () => setKillSwitch(false));
  bind('adm-btn-engage-global', () => setKillSwitch(true));
  bind('adm-btn-run', runPlan);
  bind('adm-btn-reconcile', runPlan);
  bind('adm-btn-engage', () => setKillSwitch(true));
  bind('adm-plans-reload', reload);
  // pas de rendu pendant une action exclusive : il ré-activerait des contrôles verrouillés alors que l'action tourne encore
  core.onState(() => { if (!busy && (local.plans.length || local.bindings.length)) { render(); } });

  core.register('console', { load: () => run(null, reload), show: () => run(null, reload) });
})();
