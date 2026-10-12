/*
 * Onglet Console : procédure d'exécution OKX en 7 étapes (plan -> armer -> confirmation propriétaire -> lever le kill
 * switch -> exécuter -> réconcilier -> réengagement). Chaque étape = bouton + état + préconditions grisées. Le résultat
 * {status, blocked, sent} est traduit par la table unique de AdminCore (explainBlocks).
 */
(function () {
  'use strict';
  const core = window.AdminCore;
  if (!core) { return; }
  const { el, api, run, byId } = core;

  const EXEC = '/api/admin/execution';
  const IN_FLIGHT_STEPS = ['SUBMITTED', 'UNKNOWN'];
  const local = { plans: [], bindings: [], selectedId: null };

  const selectedPlan = () => local.plans.find((p) => p.id === local.selectedId) || null;
  const kill = () => (core.state ? core.state.killSwitch : true);

  /** Contexte de la procédure pour le plan sélectionné. */
  function context() {
    const plan = selectedPlan();
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

  function renderPlanSelect() {
    const select = byId('adm-plan-select');
    if (!local.plans.length) {
      select.replaceChildren(el('option', { value: '', text: 'Aucun plan sur la période — utiliser « Planifier »' }));
      local.selectedId = null;
      return;
    }
    if (!selectedPlan()) { local.selectedId = local.plans[0].id; }
    select.replaceChildren(...local.plans.map((p) => el('option', { value: p.id, text: planLabel(p), selected: p.id === local.selectedId })));
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
    if (!plan) { box.replaceChildren(core.empty('Aucun plan — étape 1 : planifier.')); return; }
    const head = el('div', { class: 'small' },
      el('strong', { text: 'Plan #' + plan.id + ' : ' }), plan.status + ' · coût ' + (plan.totalCostPct == null ? core.DASH : plan.totalCostPct + ' %')
      + ' · Fee Test ' + (plan.feeTestLevel || core.DASH) + ' · expire ' + core.fmtDate(plan.expiresAt));
    const assets = plan.assets.map((a) => el('div', { class: 'small mt-1' },
      el('strong', { text: a.assetSymbol }), ' ' + a.action + ' → ' + a.outcome + (a.blockReason ? ' (' + a.blockReason + ')' : ''),
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
    renderPlanSelect();
    renderSummary();
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
    local.plans = await api('GET', EXEC + '/plans');
  }
  async function loadBindings() {
    local.bindings = await api('GET', '/api/admin/bindings');
  }
  async function reload() {
    await Promise.all([loadPlans(), loadBindings(), core.refreshState()]);
    render();
  }

  /* ------------------------------------------------------------------ actions */

  async function plan() {
    const day = byId('adm-plan-day').value;
    const query = day ? '?day=' + encodeURIComponent(day) : '';
    const s = await api('POST', EXEC + '/plan' + query);
    const box = byId('adm-result');
    box.className = 'border rounded p-2 small';
    box.textContent = 'Plan ' + s.day + ' : ' + s.planned + ' planifié(s), ' + s.blocked + ' bloqué(s), '
      + s.disabled + ' désactivé(s), ' + s.unchanged + ' inchangé(s), ' + s.skipped + ' ignoré(s), ' + s.errors + ' erreur(s).';
    local.selectedId = null;
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
    local.selectedId = null;
    await reload();
  }

  /** Passe Rainbow live manuelle (signal + snapshot live par lien) : prérequis du plan. Aucun ordre. */
  async function livePass() {
    const day = byId('adm-plan-day').value;
    const query = day ? '?day=' + encodeURIComponent(day) : '';
    const s = await api('POST', '/api/admin/rainbow-live/run' + query);
    const box = byId('adm-result');
    box.className = 'border rounded p-2 small';
    box.textContent = 'Passe live ' + s.day + ' : ' + s.processed + ' traité(s), ' + s.skipped + ' ignoré(s), '
      + s.errors + ' erreur(s).';
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
  }

  function bind(id, action) {
    const btn = byId(id);
    btn.addEventListener('click', () => run(btn, action));
  }

  bind('adm-btn-pass', livePass);
  bind('adm-btn-plan', plan);
  bind('adm-btn-reset', resetPlan);
  bind('adm-btn-lift', () => setKillSwitch(false));
  bind('adm-btn-run', runPlan);
  bind('adm-btn-reconcile', runPlan);
  bind('adm-btn-engage', () => setKillSwitch(true));
  bind('adm-plans-reload', reload);
  byId('adm-plan-select').addEventListener('change', (e) => { local.selectedId = Number(e.target.value) || null; render(); });
  core.onState(() => { if (local.plans.length || local.bindings.length) { render(); } });

  core.register('console', { load: () => run(null, reload), show: () => run(null, reload) });
})();
