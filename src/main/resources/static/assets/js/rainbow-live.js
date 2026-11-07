/*
 * Page "Affectations — Rainbow DCA ATR" (userPage, fragment fragments/rainbowLive.html).
 * Contrat : API REST /api/rainbow-live (cf. docs/api/rest-endpoints.md). Tous les chiffres viennent du serveur :
 * ce fichier ne fait que de l'affichage. Entrées utilisateur/serveur insérées via textContent (jamais en HTML).
 */
(function () {
  'use strict';

  const API = '/api/rainbow-live';
  const root = document.getElementById('rainbow-live');
  if (!root) { return; }
  const $ = (id) => document.getElementById(id);

  const DASH = '—';

  const state = {
    defaults: null, zones: new Map(), asset: null, affectations: [], selectedId: null,
    retry: null,
    live: new Map(), liveCheck: null, routes: null, plan: null, exec: null, events: null
  };

  /* ------------------------------------------------------------------ utilitaires */

  const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
  const nf = (min, max) => new Intl.NumberFormat('fr-FR', { minimumFractionDigits: min, maximumFractionDigits: max });
  const F2 = nf(2, 2); const F8 = nf(6, 8); const FVAR = nf(0, 6);
  const usd = (v) => (isNum(v) ? F2.format(v) + ' USD' : DASH);
  const qty = (v) => (isNum(v) ? F8.format(v) : DASH);
  const pct = (v) => (isNum(v) ? F2.format(v) + ' %' : DASH);
  const dec = (v) => (isNum(v) ? FVAR.format(v) : DASH);
  const zoneLabel = (z) => (z == null ? DASH : (state.zones.get(z) || String(z)));

  /** Création de nœud DOM ; les enfants texte passent par createTextNode (pas d'injection HTML). */
  function el(tag, props, ...children) {
    const n = document.createElement(tag);
    if (props) {
      for (const [k, v] of Object.entries(props)) {
        if (v == null || v === false) { continue; }
        if (k === 'class') { n.className = v; }
        else if (k === 'text') { n.textContent = v; }
        else if (k.startsWith('on')) { n.addEventListener(k.slice(2), v); }
        else { n.setAttribute(k, v === true ? '' : v); }
      }
    }
    for (const c of children.flat()) {
      if (c == null || c === false) { continue; }
      n.append(c.nodeType ? c : document.createTextNode(String(c)));
    }
    return n;
  }

  function actionText(type, amount, quantity) {
    if (type === 'BUY') { return 'Achat ' + usd(amount) + ' (' + qty(quantity) + ')'; }
    if (type === 'SELL') { return 'Vente ' + usd(amount) + ' (' + qty(quantity) + ')'; }
    return 'Aucune';
  }

  class ApiError extends Error {
    constructor(status, message) { super(message); this.status = status; }
  }

  /** Appel same-origin (cookie JWT). 401/403 ⇒ redirection /login ; erreurs serveur {"error": "..."} ⇒ ApiError. */
  async function api(method, path, body) {
    let res;
    try {
      res = await fetch(path.startsWith('/api/') ? path : API + path, {
        method, credentials: 'same-origin', redirect: 'follow',
        headers: body === undefined ? { Accept: 'application/json' }
          : { Accept: 'application/json', 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body)
      });
    } catch (e) {
      throw new ApiError(0, 'Erreur réseau : serveur injoignable.');
    }
    if (res.status === 401 || res.status === 403 || (res.redirected && /\/login/.test(res.url))) {
      window.location.href = '/login';
      throw new ApiError(res.status || 401, 'Session expirée, redirection vers la page de connexion.');
    }
    const text = await res.text();
    let data = null;
    if (text) { try { data = JSON.parse(text); } catch (e) { data = null; } }
    if (!res.ok) {
      throw new ApiError(res.status, (data && data.error) ? data.error : 'Erreur HTTP ' + res.status);
    }
    if (res.status !== 204 && data === null) {
      throw new ApiError(res.status, 'Réponse inattendue du serveur.');
    }
    return data;
  }

  function showAlert(message, retry) {
    $('rl-alert-text').textContent = message;
    state.retry = retry || null;
    $('rl-alert-retry').classList.toggle('d-none', !retry);
    $('rl-alert').classList.remove('d-none');
  }
  function hideAlert() { $('rl-alert').classList.add('d-none'); state.retry = null; }

  function panelError(container, err, retry) {
    container.replaceChildren(el('div', { class: 'alert alert-warning py-2' },
      el('span', { text: err.message }),
      retry ? el('button', { type: 'button', class: 'btn btn-sm btn-outline-secondary ms-2', text: 'Réessayer', onclick: retry }) : null));
  }

  function card(label, value, cls, title) {
    return el('div', { class: 'col-6 col-md-4 col-xl-3' },
      el('div', { class: 'rl-card', title: title || null },
        el('div', { class: 'rl-label', text: label }),
        el('div', { class: 'rl-value ' + (cls || ''), text: value })));
  }

  /* ------------------------------------------------------------------ onglets & presets */

  function buildTabs() {
    const ul = $('rl-asset-tabs');
    ul.replaceChildren(...state.defaults.assets.map((asset) => el('li', { class: 'nav-item', role: 'presentation' },
      el('button', { type: 'button', class: 'nav-link', 'data-asset': asset, role: 'tab', text: asset,
        onclick: () => selectAsset(asset) }))));
  }

  function selectAsset(asset) {
    state.asset = asset;
    state.selectedId = null;
    document.querySelectorAll('#rl-asset-tabs .nav-link').forEach((b) =>
      b.classList.toggle('active', b.dataset.asset === asset));
    $('rl-asset-label').textContent = asset;
    state.liveCheck = null;
    state.routes = null;
    return Promise.all([loadLive(), loadAffectations()]);
  }

  async function loadAffectations() {
    try {
      state.affectations = await api('GET', '/affectations?asset=' + encodeURIComponent(state.asset));
      hideAlert();
      renderAffectations();
    } catch (e) {
      showAlert('Chargement des Affectations impossible : ' + e.message, loadAffectations);
    }
  }

  function renderAffectations() {
    const body = $('rl-presets-body');
    const empty = state.affectations.length === 0;
    $('rl-presets-table').classList.toggle('d-none', empty);
    $('rl-presets-empty').classList.toggle('d-none', !empty);
    body.replaceChildren(...state.affectations.map((a) => affectationRow(a)));
    renderLive();
  }

  /** Liens (bindings) actifs d'un actif ; plusieurs si l'actif est suivi sur plusieurs providers. */
  const livesOf = (asset) => state.live.get(asset) || [];
  const affectationLinks = (a) => livesOf(a.assetSymbol).filter((l) => l.affectationId === a.id);
  const isLiveAffectation = (a) => affectationLinks(a).length > 0;

  /** « Bear · DOWN » (badge de régime coloré) ; tiret si non applicable. */
  function setTrendCell(set, regime) {
    if (!set && !regime) { return DASH; }
    return el('span', { class: 'text-nowrap' }, el('span', { class: 'me-1', text: set || DASH }),
      regime ? el('span', { class: 'badge rl-tag-' + regime.toLowerCase(), text: regime }) : null);
  }

  const presetRef = (r) => (r ? r.name + (r.origin === 'SYSTEM' ? ' (système)' : '') : DASH);

  function affectationRow(a) {
    const last = a.lastRun;
    const toggle = el('input', { type: 'checkbox', class: 'form-check-input', role: 'switch', disabled: !a.complete,
      title: a.complete ? 'Activer / désactiver cette Affectation' : 'Affectation incomplète : presets manquants' });
    toggle.checked = a.enabled;
    toggle.addEventListener('change', () => toggleEnabled(a, toggle));
    return el('tr', { class: a.id === state.selectedId ? 'table-active' : '' },
      el('td', null, el('span', { text: a.assetSymbol }), isLiveAffectation(a) ? el('span', { class: 'badge rl-badge-live ms-1', text: 'LIVE',
        title: 'Lié à ' + affectationLinks(a).map((l) => l.walletName + ' (' + (l.exchange || DASH) + ')').join(', ') }) : null),
      el('td', null, el('div', { class: 'small' }, 'Trend : ' + presetRef(a.trendPreset)),
        el('div', { class: 'small' }, 'Up : ' + presetRef(a.rainbowUp)), el('div', { class: 'small' }, 'Down : ' + presetRef(a.rainbowDown))),
      el('td', null, el('div', { class: 'form-check form-switch' }, toggle)),
      el('td', { text: a.analysisWindowMonths }),
      el('td', { text: a.runCount }),
      el('td', { text: a.firstRunDay || DASH }),
      el('td', null, setTrendCell(last && last.activeSet, last && last.trendRegime)),
      el('td', { text: last ? last.day + ' · ' + zoneLabel(last.zone) + ' · '
        + actionText(last.actionType, last.actionAmountUsdc, last.actionQuantity) : DASH }));
  }

  /** Bascule rapide actif/inactif de l'Affectation (jouée ou non à chaque passe quotidienne). */
  async function toggleEnabled(a, input) {
    const wanted = input.checked;
    input.disabled = true;
    try {
      await api('PATCH', '/affectations/' + a.id + '/enabled', { enabled: wanted });
      hideAlert();
      await loadAffectations();
    } catch (e) {
      input.checked = !wanted;
      input.disabled = false;
      showAlert('Modification impossible : ' + e.message, null);
    }
  }

  /* ------------------------------------------------------------------ wallet réel (Affectation live) */

  const LIVE_STATUS = {
    OK: ['bg-success', 'Lecture OK'],
    UNAVAILABLE: ['bg-danger', 'Indisponible'],
    NOT_TRADABLE: ['bg-warning text-dark', 'Non tradable']
  };
  const BLOCK_TEXT = { INSUFFICIENT_CASH: 'liquidité insuffisante', UNAVAILABLE: 'données indisponibles',
    NOT_TRADABLE_WITHOUT_FIAT: 'actif non tradable sans monnaie fiat' };
  const CHECK_TEXT = {
    OK: 'OK', WALLET_DISABLED: 'Wallet désactivé', CREDENTIAL_INVALID: 'Clé API invalide ou rejetée',
    PROVIDER_UNSUPPORTED: 'Exchange non pris en charge', BALANCE_UNAVAILABLE: 'Soldes illisibles',
    TRADABLE_VIA_INTERMEDIARY: 'Tradable via intermédiaire (ex. USDC > BTC > PAXG)', NOT_TRADABLE_WITHOUT_FIAT: 'Non tradable sans monnaie fiat',
    INSTRUMENT_UNAVAILABLE: 'Paire non vérifiable'
  };

  /** Binding + dernier snapshot de chaque actif (lecture base uniquement, aucun appel exchange). Échec ⇒ pas de bloc live. */
  async function loadLive() {
    try {
      const list = await api('GET', '/live-wallet');
      state.live = new Map();
      list.forEach((l) => { state.live.set(l.assetSymbol, livesOf(l.assetSymbol).concat(l)); });
    } catch (e) {
      state.live = new Map();
    }
    await loadPlan();
    renderLive();
  }

  const PLAN_STATUS = { PLANNED: ['bg-primary', 'Planifié'], BLOCKED: ['bg-danger', 'Bloqué'], EXPIRED: ['bg-secondary', 'Expiré'],
    DISABLED: ['bg-secondary', 'Exécution non activée'], EXECUTING: ['bg-info text-dark', 'En cours'],
    EXECUTED: ['bg-success', 'Exécuté'], PARTIAL: ['bg-warning text-dark', 'Partiel'], FAILED: ['bg-danger', 'Échec'],
    CANCELLED: ['bg-secondary', 'Annulé'] };
  const STEP_STATUS = { PLANNED: 'Planifiée', SUBMITTED: 'Envoyée', FILLED: 'Remplie', PARTIAL: 'Partielle', REJECTED: 'Rejetée',
    CANCELED: 'Annulée', UNKNOWN: 'Inconnue (à réconcilier)' };
  const EXECUTED_STATUS = ['EXECUTING', 'EXECUTED', 'PARTIAL', 'FAILED'];
  const FEE_LEVEL = { GREEN: ['bg-success', 'Fee Test vert'], WARNING: ['bg-warning text-dark', 'Fee Test orange'],
    RED: ['bg-danger', 'Fee Test rouge'] };
  const PLAN_REASON = { NO_PATH: 'aucun chemin chiffrable', BELOW_MIN: 'montant sous le minimum de l\'exchange',
INSUFFICIENT_FUNDS_AFTER_FEES: 'solde insuffisant après frais',
    NOT_TRADABLE_WITHOUT_FIAT: 'non tradable sans monnaie fiat', READING_UNAVAILABLE: 'données indisponibles' };

  /** Dernier plan d'ordres en base ; 204 ou erreur ⇒ pas de plan (le reste de la page n'en dépend pas). */
  async function loadPlan() {
    try {
      state.plan = await api('GET', '/execution-plans/latest');
    } catch (e) {
      state.plan = null;
    }
    state.events = null;
    try {
      state.exec = await api('GET', '/execution-state');
    } catch (e) {
      state.exec = null;
    }
  }

  function planSteps(steps) {
    const head = el('tr', null, ['#', 'Paire', 'Sens', 'Taille', 'Prix plafond', 'Montant', 'Frais', 'Spread', 'Slippage', 'Statut', 'Rempli',
      'Prix moyen', 'Frais réels', 'Slippage réel']
      .map((h) => el('th', { text: h })));
    const rows = steps.map((st) => el('tr', null,
      el('td', { text: String(st.rank) }), el('td', { text: st.instId }), el('td', { text: st.side === 'BUY' ? 'Achat' : 'Vente' }),
      el('td', { text: qty(Number(st.sz)) }), el('td', { text: dec(Number(st.px)) }), el('td', { text: dec(Number(st.quoteAmount)) }),
      el('td', { text: pct(Number(st.feePct)) }), el('td', { text: pct(Number(st.spreadPct)) }),
      el('td', { text: st.estimation === 'TICKER' ? 'n.c.' : pct(Number(st.slippagePct)) }),
      el('td', { text: STEP_STATUS[st.status] || st.status || DASH, title: st.lastError || '' }),
      el('td', { text: st.filledSz == null ? DASH : qty(Number(st.filledSz)) }),
      el('td', { text: st.avgFillPx == null ? DASH : dec(Number(st.avgFillPx)) }),
      el('td', { text: st.feeAmount == null ? DASH : dec(Number(st.feeAmount)) + ' ' + (st.feeCurrency || '') }),
      el('td', { text: st.realSlippagePct == null ? DASH : pct(Number(st.realSlippagePct)) })));
    return el('div', { class: 'table-responsive' },
      el('table', { class: 'table table-sm mb-1' }, el('thead', null, head), el('tbody', null, rows)));
  }

  function planAssetView(a, l) {
    if (!a) { return el('div', { class: 'text-muted', text: 'Aucune étape pour ' + l.assetSymbol + ' dans ce plan.' }); }
    if (a.outcome === 'DISABLED') { return el('div', { class: 'text-muted', text: 'Exécution non activée pour ce binding : aucune étape.' }); }
    if (a.outcome === 'NO_ACTION') { return el('div', { class: 'text-muted', text: 'Aucune action ce jour-là.' }); }
    const lvl = FEE_LEVEL[a.feeTestLevel];
    const cost = a.costPct == null ? null : el('div', { class: 'mb-1' },
      el('span', { text: 'Coût du chemin : ' + pct(Number(a.costPct)) + ' ' }),
      lvl ? el('span', { class: 'badge ' + lvl[0], text: lvl[1] }) : null);
    if (a.outcome === 'BLOCKED') {
      return el('div', null, cost, el('div', { class: 'alert alert-warning py-1 px-2 mb-0',
        text: 'Plan bloqué : ' + (PLAN_REASON[a.blockReason] || a.blockReason) + '.' }));
    }
    return el('div', null, cost, planSteps(a.steps),
      a.warning ? el('div', { class: 'small text-warning-emphasis', text: a.warning }) : null);
  }

  /** Plan d'ordres de l'actif sélectionné (dry-run : rien n'est envoyé à l'exchange). */
  function planView(l) {
    const p = state.plan;
    const sent = !!p && EXECUTED_STATUS.includes(p.status);
    const head = el('div', { class: 'd-flex flex-wrap align-items-center gap-2 mb-2' },
      el('h6', { class: 'mb-0', text: sent ? 'Plan d\'exécution (réel)' : 'Plan d\'exécution (dry-run)' }),
      el('span', { class: 'badge bg-light text-dark border', text: sent ? 'ordres envoyés' : 'non envoyé' }));
    if (!p) {
      return el('div', { class: 'mt-3 border-top pt-2' }, head, el('div', { class: 'text-muted fst-italic', text: 'Aucun plan enregistré.' }));
    }
    const st = PLAN_STATUS[p.status] || ['bg-secondary', p.status];
    const expiry = p.status === 'EXPIRED' ? 'expiré' : 'valable jusqu\'à ' + new Date(p.expiresAt).toLocaleTimeString('fr-FR');
    return el('div', { class: 'mt-3 border-top pt-2' }, head,
      el('div', { class: 'small mb-2' },
        el('span', { class: 'badge ' + st[0] + ' me-2', text: st[1] }),
        el('span', { text: 'jour ' + p.day + ' (23:55 UTC) · ' + expiry
          + ' (un plan n\'est qu\'une photo, l\'exécution re-devise)' }),
        p.executionBlockReason ? el('div', { class: 'text-warning-emphasis', text: 'Blocages : ' + p.executionBlockReason }) : null),
      planAssetView(p.assets.find((a) => a.assetSymbol === l.assetSymbol && a.walletId === l.walletId), l));
  }

  /** Interrupteurs et état d'exécution (texte seul) : kill switch (affichage), exécution du binding, 1er ordre réel, audit. */
  function executionView(l) {
    const x = state.exec;
    const kill = !x ? null : el('span', { class: 'badge ' + (x.killSwitch ? 'bg-danger' : 'bg-success') + ' me-2',
      text: x.killSwitch ? 'Kill switch engagé' : 'Kill switch levé' });
    const mode = !x ? null : el('span', { class: 'badge bg-light text-dark border me-2',
      text: 'Mode ' + x.mode + (x.liveExecution ? '' : ' · exécution réelle verrouillée') });
    const sw = el('input', { type: 'checkbox', class: 'form-check-input', role: 'switch', id: 'rl-exec-switch',
      onchange: (ev) => setExecution(l, ev.target.checked) });
    sw.checked = !!l.executionEnabled;
    let first;
    if (l.firstLiveConfirmed) {
      first = el('span', { class: 'badge bg-success', text: '1er ordre réel validé' });
    } else if (l.executionEnabled) {
      first = el('button', { type: 'button', class: 'btn btn-sm btn-warning', id: 'rl-confirm-first',
        text: 'Confirmer le 1er ordre réel', onclick: () => confirmFirstLive(l) });
    } else {
      first = el('span', { class: 'text-muted', text: '1er ordre réel : activez l\'exécution pour le valider' });
    }
    return el('div', { class: 'mt-3 border-top pt-2', id: 'rl-exec-panel' },
      el('h6', { class: 'mb-2', text: 'Exécution réelle' }),
      el('div', { class: 'mb-2' }, kill, mode),
      el('div', { class: 'form-check form-switch mb-2' }, sw,
        el('label', { class: 'form-check-label', for: 'rl-exec-switch', text: 'Exécution activée pour ' + l.assetSymbol })),
      el('div', { class: 'small mb-2' }, first),
      el('div', { id: 'rl-exec-msg-' + l.bindingId, class: 'small text-danger mb-1' }),
      auditView());
  }

  /** Audit du dernier plan : liste paginée en lecture seule (aucune action). */
  function auditView() {
    const btn = el('button', { type: 'button', class: 'btn btn-sm btn-outline-secondary', id: 'rl-audit-load',
      text: 'Audit du plan', disabled: !state.plan, onclick: () => loadEvents(0) });
    const ev = state.events;
    if (!ev) { return el('div', null, btn); }
    const rows = ev.items.map((e) => el('tr', null,
      el('td', { text: new Date(e.createdAt).toLocaleString('fr-FR') }), el('td', { text: e.type }),
      el('td', { text: e.clOrdId || DASH }), el('td', { class: 'text-break', text: e.payload || '' })));
    const pages = Math.max(1, Math.ceil(ev.totalElements / ev.size));
    return el('div', null, btn,
      el('div', { class: 'table-responsive mt-2' }, el('table', { class: 'table table-sm mb-1', id: 'rl-audit-table' },
        el('thead', null, el('tr', null, ['Date', 'Évènement', 'Ordre', 'Détail'].map((h) => el('th', { text: h })))),
        el('tbody', null, rows.length ? rows : el('tr', null, el('td', { colspan: '4', class: 'text-muted', text: 'Aucun évènement.' }))))),
      el('div', { class: 'd-flex align-items-center gap-2 small' },
        el('button', { type: 'button', class: 'btn btn-sm btn-outline-secondary', text: 'Précédent', disabled: ev.page <= 0,
          onclick: () => loadEvents(ev.page - 1) }),
        el('span', { text: 'Page ' + (ev.page + 1) + ' / ' + pages + ' · ' + ev.totalElements + ' évènement(s)' }),
        el('button', { type: 'button', class: 'btn btn-sm btn-outline-secondary', text: 'Suivant', disabled: ev.page + 1 >= pages,
          onclick: () => loadEvents(ev.page + 1) })));
  }

  async function loadEvents(page) {
    if (!state.plan) { return; }
    try {
      state.events = await api('GET', '/execution-plans/' + state.plan.id + '/events?page=' + page + '&size=20');
    } catch (e) {
      state.events = null;
    }
    renderLive();
  }

  async function setExecution(l, enabled) {
    try {
      await api('PUT', '/bindings/' + l.bindingId + '/execution', { enabled });
    } catch (e) {
      await loadLive();
      const m = $('rl-exec-msg-' + l.bindingId); if (m) { m.textContent = e.message; }
      return;
    }
    await loadLive();
  }

  async function confirmFirstLive(l) {
    try {
      await api('POST', '/bindings/' + l.bindingId + '/confirm-first-live');
    } catch (e) {
      const m = $('rl-exec-msg-' + l.bindingId); if (m) { m.textContent = e.message; }
      return;
    }
    await loadLive();
  }

  function liveAction(s) {
    if (s.status !== 'OK') {
      return el('div', { class: 'alert alert-secondary py-1 mb-0 mt-2', text: 'Données indisponibles, aucune action.' });
    }
    if (s.liveActionType === 'BLOCKED') {
      return el('div', { class: 'alert alert-warning py-1 mb-0 mt-2',
        text: 'Achat bloqué : ' + (BLOCK_TEXT[s.blockReason] || 'liquidité insuffisante') + '.' });
    }
    return el('div', { class: 'mt-2' }, el('span', { class: 'fw-semibold', text: 'Action réelle : ' }),
      el('span', { text: actionText(s.liveActionType, s.liveActionAmountUsdc, s.liveActionQuantity) }),
      el('span', { class: 'badge bg-light text-dark border ms-2', text: 'recommandée, non exécutée' }));
  }

  /** Avertissement (texte seul) quand l'exécution est bloquée sur ce couple (actif, exchange). */
  function liveSnapshotView(l) {
    return el('div', null,
      l.tradabilityMessage
        ? el('div', { class: 'alert ' + (l.tradability === 'NOT_TRADABLE_WITHOUT_FIAT' ? 'alert-warning' : 'alert-info') + ' py-1 px-2 mb-2',
          role: 'alert', text: l.tradabilityMessage }) : null,
      transitView(l),
      liveSnapshotBody(l));
  }

  function liveSnapshotBody(l) {
    const s = l.snapshot;
    if (!s) {
      return el('div', { class: 'text-muted fst-italic', text: 'Aucune passe live enregistrée pour ce preset.' });
    }
    const st = LIVE_STATUS[s.status] || ['bg-secondary', s.status];
    return el('div', null,
      el('div', { class: 'rl-live-meta mb-2' },
        el('span', { class: 'badge ' + st[0] + ' me-2', text: st[1] }),
        el('span', { text: 'Wallet réel : ' + l.walletName + ' (' + (l.exchange || DASH) + ') · lu le '
          + (s.fetchedAt ? new Date(s.fetchedAt).toLocaleString('fr-FR') : DASH) + ' · jour ' + s.day + ' (23:55 UTC)' })),
      el('div', { class: 'row g-2' },
        card('Cash USD', usd(s.cashUsd), '', cashDetail(s.cashByMember)),
        card('Position réelle ' + l.assetSymbol, qty(s.positionQty)),
        card('Position tradable (' + pct(l.bagPercent) + ')', qty(s.tradableQty), '', 'Part de la position réelle offerte à la stratégie'),
        card('Cash déjà réservé', usd(s.cashReserved), '', 'Consommé par les actifs servis avant (cash commun)')),
      liveAction(s), whyAmountView(s));
  }

  /* ------------------------------------------------------------------ modulation utilisateur */

  const MOD_PATH = '/api/user/modulation-settings';
  const mod = { limits: null };
  const modInput = (k) => $('rl-mod-' + k);

  /** Jauge 0–100 % : bande, marges de régulation (illustration à 25 % de la largeur, valeur admin par défaut) et repère d'exposition. */
  function bandGauge(min, max, exposure) {
    const seg = (left, width, cls) => el('div', { class: 'rl-mod-seg ' + cls,
      style: 'left:' + left + '%;width:' + Math.max(0, width) + '%' });
    const margin = Math.min(25, (max - min) / 4);
    const nodes = [seg(0, min, 'rl-mod-out'), seg(min, max - min, 'rl-mod-in'), seg(max, 100 - max, 'rl-mod-out')];
    if (max < 100 && margin > 0) { nodes.push(seg(max - margin, margin, 'rl-mod-margin')); }
    if (min > 0 && margin > 0) { nodes.push(seg(min, margin, 'rl-mod-margin')); }
    if (isNum(exposure)) { nodes.push(el('div', { class: 'rl-mod-mark', style: 'left:' + exposure + '%', title: 'Exposition actuelle ' + pct(exposure) })); }
    return nodes;
  }

  /** Dernière exposition connue : journal de modulation des liens (exposureAfter, sinon exposureBefore). */
  function lastExposure() {
    let found = null;
    for (const links of state.live.values()) {
      for (const l of links) {
        const j = parseModulation(l.snapshot);
        const v = j && (isNum(j.exposureAfter) ? j.exposureAfter : j.exposureBefore);
        if (isNum(v)) { found = v; }
      }
    }
    return found;
  }

  function renderMod() {
    const min = Number(modInput('min').value); const max = Number(modInput('max').value);
    modInput('min-v').textContent = min; modInput('max-v').textContent = max;
    modInput('power-v').textContent = F2.format(Number(modInput('power').value));
    modInput('bias-v').textContent = F2.format(Number(modInput('bias').value));
    const exposure = lastExposure();
    $('rl-mod-gauge').replaceChildren(...bandGauge(min, max, exposure));
    $('rl-mod-exposure').textContent = isNum(exposure)
      ? 'Exposition actuelle : ' + pct(exposure) + (exposure > max ? ' — au-dessus de la bande (achats freinés, ventes renforcées).'
        : exposure < min ? ' — sous la bande (achats renforcés, ventes freinées).' : ' — dans la bande.')
      : 'Exposition actuelle : inconnue (prix indisponible ou aucune passe) — la bande ne module pas, puissance et biais restent appliqués.';
    const width = max - min; const minW = mod.limits.minBandWidthPts;
    $('rl-mod-msg').textContent = width < minW ? 'Bande plus étroite que ' + minW + ' pts : elle sera élargie autour de son centre.' : '';
    $('rl-mod-msg').className = 'small text-warning';
  }

  function fillMod(dto) {
    mod.limits = dto.limits;
    const s = dto.settings; const lim = dto.limits;
    modInput('min').value = s.exposureMin; modInput('max').value = s.exposureMax;
    Object.assign(modInput('power'), { min: lim.powerMin, max: lim.powerMax, value: s.power });
    Object.assign(modInput('bias'), { min: -lim.biasMax, max: lim.biasMax, value: s.bias });
    renderMod();
  }

  async function loadMod() {
    try {
      fillMod(await api('GET', MOD_PATH));
      $('rl-mod-panel').classList.remove('d-none');
    } catch (e) {
      $('rl-mod-panel').classList.add('d-none');
    }
  }

  async function saveMod(neutral) {
    const body = neutral ? { exposureMin: 0, exposureMax: 100, power: 1, bias: 0 } : {
      exposureMin: Number(modInput('min').value), exposureMax: Number(modInput('max').value),
      power: Number(modInput('power').value), bias: Number(modInput('bias').value) };
    try {
      fillMod(await api('PUT', MOD_PATH, body));
      $('rl-mod-msg').textContent = 'Enregistré.'; $('rl-mod-msg').className = 'small text-success';
    } catch (e) {
      $('rl-mod-msg').textContent = e.message; $('rl-mod-msg').className = 'small text-danger';
    }
  }

  function bindMod() {
    ['min', 'max', 'power', 'bias'].forEach((k) => modInput(k).addEventListener('input', () => { if (mod.limits) { renderMod(); } }));
    $('rl-mod-save').addEventListener('click', () => saveMod(false));
    $('rl-mod-neutral').addEventListener('click', () => saveMod(true));
  }

  /* ------------------------------------------------------------------ onboarding */

  const LINK_TEXT = { LINKED: ['text-success', 'lié (inactif)'], ALREADY_LINKED: ['text-muted', 'déjà lié'],
    NOT_LINKED: ['text-danger', 'non lié'] };
  const onbWallet = () => Number($('rl-onb-wallet').value);

  async function loadOnboarding() {
    try {
      const wallets = await api('GET', '/wallets');
      $('rl-onb-wallet').replaceChildren(...wallets.map((w) => el('option', { value: w.id,
        text: w.name + (w.exchange ? ' (' + w.exchange + ')' : '') })));
      $('rl-onb-panel').classList.toggle('d-none', !wallets.length);
    } catch (e) {
      $('rl-onb-panel').classList.add('d-none');
    }
  }

  function onbMessage(text, cls) { $('rl-onb-msg').textContent = text; $('rl-onb-msg').className = 'small mb-2 ' + (cls || ''); }

  async function onbLink() {
    onbMessage('Vérification des actifs…', 'text-muted');
    try {
      const results = await api('POST', '/onboarding/link', { walletId: onbWallet() });
      $('rl-onb-links').replaceChildren(...results.map((r) => {
        const t = LINK_TEXT[r.outcome];
        return el('li', null, el('span', { class: 'fw-semibold', text: r.assetSymbol + ' : ' }),
          el('span', { class: t[0], text: t[1] }),
          r.affectationName ? el('span', { class: 'text-muted', text: ' — ' + r.affectationName }) : null,
          r.message ? el('span', { text: ' — ' + r.message }) : null);
      }));
      onbMessage('Liens à vérifier puis activer un par un dans le panneau de chaque actif.', 'text-muted');
      await loadLive();
    } catch (e) { onbMessage(e.message, 'text-danger'); }
  }

  /** Jours tenus par le cash alloué au montant actuel, dans un jour de zone « moyenne » du preset. */
  function autonomyText(p) {
    const base = isNum(p.currentBaseAmount) ? p.currentBaseAmount : p.presetBaseAmount;
    if (!isNum(p.cashAllocated) || !isNum(base) || base <= 0 || !isNum(p.averageMultiplier) || p.averageMultiplier <= 0) { return DASH; }
    return '~' + Math.floor(p.cashAllocated / (base * p.averageMultiplier)) + ' jours';
  }

  async function applyBase(p, button) {
    button.disabled = true;
    try {
      await api('PUT', '/bindings/' + p.bindingId, { baseAmount: p.proposedBaseAmount });
      onbMessage(p.assetSymbol + ' : montant de base ' + usd(p.proposedBaseAmount) + ' appliqué (prochaine passe).', 'text-success');
      await onbPropose();
    } catch (e) { onbMessage(e.message, 'text-danger'); button.disabled = false; }
  }

  function proposalRow(p) {
    const apply = el('button', { type: 'button', class: 'btn btn-sm btn-outline-success', text: 'Appliquer',
      disabled: !isNum(p.proposedBaseAmount) });
    apply.addEventListener('click', () => applyBase(p, apply));
    const notes = [p.belowMinimum ? 'sous le minimum de l\'exchange' : null,
      p.feeTestLevel && p.feeTestLevel !== 'GREEN' ? 'Fee Test ' + p.feeTestLevel : null, p.warning].filter(Boolean);
    return [el('tr', null, el('td', { text: p.assetSymbol }), el('td', { text: usd(p.cashAllocated) }),
      el('td', { text: usd(isNum(p.currentBaseAmount) ? p.currentBaseAmount : p.presetBaseAmount)
        + (isNum(p.currentBaseAmount) ? '' : ' (preset)') }),
      el('td', { text: usd(p.proposedBaseAmount) }),
      el('td', { text: isNum(p.proposedBaseAmount) ? p.autonomyDays + ' jours' : DASH }),
      el('td', { text: autonomyText(p) }), el('td', null, apply)),
      notes.length ? el('tr', null, el('td', { colspan: 7, class: 'small text-warning', text: notes.join(' · ') })) : null];
  }

  /** Durée choisie en jours (jours ou mois de 30 j) ; vide => défaut admin. */
  function onbDays() {
    const v = Number($('rl-onb-duration').value);
    return v > 0 ? Math.round(v * Number($('rl-onb-unit').value)) : null;
  }

  /** Défaut admin en placeholder (en mois si multiple de 30) et bornes en infobulle. */
  function onbRange(a) {
    const input = $('rl-onb-duration');
    const months = a.defaultDays % 30 === 0;
    input.placeholder = String(months ? a.defaultDays / 30 : a.defaultDays) + (months ? ' mois' : ' j');
    input.title = 'Entre ' + a.minDays + ' et ' + a.maxDays + ' jours (défaut ' + a.defaultDays + ')';
  }

  async function onbPropose() {
    onbMessage('Lecture des soldes et devis…', 'text-muted');
    try {
      const days = onbDays();
      const dto = await api('GET', '/onboarding/proposals?walletId=' + onbWallet() + (days ? '&days=' + days : ''));
      onbRange(dto.autonomy);
      $('rl-onb-body').replaceChildren(...dto.proposals.flatMap(proposalRow).filter(Boolean));
      $('rl-onb-table-wrap').classList.toggle('d-none', !dto.proposals.length);
      $('rl-onb-band').textContent = 'Bande proposée : ' + dto.suggestedBand.exposureMin + ' – ' + dto.suggestedBand.exposureMax
        + ' % (neutre, aucune modulation de bande) ; à ajuster dans le bloc Modulation. Proposé = cash alloué / (' + (days || dto.autonomy.defaultDays) + ' jours × '
        + 'multiplicateur moyen des zones), cash réparti à parts égales entre les liens du wallet.';
      onbMessage(dto.proposals.length ? '' : 'Aucun lien sur ce wallet : lie d\'abord tes actifs.', 'text-muted');
    } catch (e) { onbMessage(e.message, 'text-danger'); }
  }

  function bindOnboarding() {
    $('rl-onb-link').addEventListener('click', onbLink);
    $('rl-onb-propose').addEventListener('click', onbPropose);
    ['rl-onb-duration', 'rl-onb-unit', 'rl-onb-wallet'].forEach((id) => $(id).addEventListener('change', () => {
      if (!$('rl-onb-table-wrap').classList.contains('d-none')) { onbPropose(); }
    }));
  }

  /* ------------------------------------------------------------------ « pourquoi ce montant » */

  function parseModulation(snapshot) {
    if (!snapshot || !snapshot.modulation) { return null; }
    try { return JSON.parse(snapshot.modulation); } catch (e) { return null; }
  }

  /** Détail repliable du calcul de la quantité : signal -> puissance/biais -> bande -> plafonds -> montant final. */
  function whyAmountView(s) {
    const j = parseModulation(s);
    if (!j) { return null; }
    const x = (v) => '×' + dec(v);
    const rows = [
      ['Signal Rainbow', j.signalBuyUsd > 0 ? 'achat ' + usd(j.signalBuyUsd) : j.signalSellQty > 0 ? 'vente ' + qty(j.signalSellQty) : 'aucune action'],
      ['Puissance · biais', 'puissance ' + x(j.power) + ', biais ' + dec(j.bias) + ' => achat ' + x(j.sideBuy) + ' / vente ' + x(j.sideSell)],
      ['Bande ' + dec(j.bandMin) + '–' + dec(j.bandMax) + ' %', j.exposureStatus === 'UNKNOWN' ? 'exposition inconnue : bande sans effet'
        : 'exposition ' + pct(j.exposureBefore) + ' => achat ' + x(j.bandBuy) + ' / vente ' + x(j.bandSell)],
      ['Plafonds', (j.productCapped ? 'produit des facteurs plafonné à ' + x(j.productMax) : 'produit des facteurs OK')
        + (j.hardCap ? ' · plafond dur de bande atteint' : '')],
      ['Après modulation', j.modulatedBuyUsd > 0 ? 'achat ' + usd(j.modulatedBuyUsd) : j.modulatedSellQty > 0 ? 'vente ' + qty(j.modulatedSellQty) : 'rien']
    ];
    if (j.finalAmountUsd != null || j.finalQuantity != null) {
      rows.push(['Montant final (après cash/position)', j.action === 'SELL' ? 'vente ' + qty(j.finalQuantity)
        : j.action === 'BUY' ? 'achat ' + usd(j.finalAmountUsd) : 'aucune action']);
    }
    if (isNum(j.exposureAfter)) { rows.push(['Exposition après', pct(j.exposureAfter)]); }
    return el('details', { class: 'mt-2 small' }, el('summary', { text: 'Pourquoi ce montant ?' }),
      el('table', { class: 'table table-sm mb-0' }, el('tbody', null,
        rows.map((r) => el('tr', null, el('th', { class: 'fw-normal text-muted', text: r[0] }), el('td', { text: r[1] }))))));
  }

  /** Détail du cash par membre du groupe USD (infobulle), vide si absent. */
  function cashDetail(byMember) {
    return byMember ? Object.keys(byMember).map((k) => k + ' ' + F2.format(byMember[k])).join(' · ') : '';
  }

  /** Stock en transit actif (intermédiaire dont la jambe 2 reste à terminer) : terminé au prochain plan, jamais compensé. */
  function transitView(l) {
    if (!l.transit || !l.transit.length) { return null; }
    return el('div', { class: 'alert alert-warning py-1 px-2 mb-2', role: 'alert' },
      el('strong', { text: 'Stock en transit : ' }),
      l.transit.map((t) => qty(Number(t.quantity)) + ' ' + t.currency + (t.finishSide === 'SELL' ? ' (à vendre en stable)' : ' (jambe 2 à terminer sur ' + l.assetSymbol + ')')).join(' · '),
      el('span', { class: 'ms-1', text: '— le prochain plan termine cette jambe avant tout nouvel ordre ; aucun ordre compensatoire.' }));
  }

  /** Routes d'achat chiffrées (lecture seule) côte à côte : coût, Fee Test, avertissements ; la route liée au lien est marquée. */
  function routesView(l) {
    const r = state.routes;
    if (!r || r.bindingId !== l.bindingId) { return null; }
    if (r.error) { return el('div', { class: 'small text-danger mt-2', text: r.error }); }
    const q = r.quote;
    if (!q.routes || !q.routes.length) {
      return el('div', { class: 'small text-muted mt-2', text: 'Aucune route chiffrée (' + q.status + ').' });
    }
    const head = el('tr', null, ['Route', 'Jambes', 'Coût', 'Fee Test', 'Statut', 'Avertissements'].map((h) => el('th', { text: h })));
    const rows = q.routes.map((rt) => {
      const lvl = FEE_LEVEL[rt.feeTestLevel];
      return el('tr', null,
        el('td', null, el('span', { text: rt.routeKey.replace(/>/g, ' → ') }),
          rt.routeKey === l.routeKey ? el('span', { class: 'badge bg-primary ms-1', text: 'liée' }) : null,
          rt.chosen ? null : el('span', { class: 'badge bg-secondary ms-1', text: 'non choisie' })),
        el('td', { text: rt.legs.map((g) => (g.side === 'BUY' ? 'Achat ' : 'Vente ') + g.instId).join(' ; ') }),
        el('td', { text: rt.totalCostPct == null ? DASH : pct(Number(rt.totalCostPct)) }),
        el('td', null, lvl ? el('span', { class: 'badge ' + lvl[0], text: lvl[1] }) : DASH),
        el('td', { text: rt.status }),
        el('td', { class: 'small', text: (rt.warnings || []).join(' ; ') }));
    });
    return el('div', { class: 'mt-2' },
      el('div', { class: 'small text-muted', text: 'Devis pour ' + usd(Number(q.amount)) + ' — lecture seule, aucun ordre.' }),
      el('div', { class: 'table-responsive' }, el('table', { class: 'table table-sm mb-1' }, el('thead', null, head), el('tbody', null, rows))),
      q.warning ? el('div', { class: 'small text-warning-emphasis', text: q.warning }) : null);
  }

  async function quoteRoutes(l) {
    const amount = l.snapshot && l.snapshot.liveActionAmountUsdc > 0 ? l.snapshot.liveActionAmountUsdc : 10;
    try {
      state.routes = { bindingId: l.bindingId, quote: await api('GET', '/bindings/' + l.bindingId + '/path-quote?amount=' + amount) };
    } catch (e) {
      state.routes = { bindingId: l.bindingId, error: 'Devis impossible : ' + e.message };
    }
    renderLive();
  }

  function liveCheckView(l) {
    const c = state.liveCheck;
    if (!c || c.bindingId !== l.bindingId) { return null; }
    return el('div', { class: 'mt-2 small' },
      el('span', { class: 'badge ' + (c.ok ? 'bg-success' : 'bg-danger') + ' me-2', text: c.ok ? 'BindingCheck OK' : 'BindingCheck KO' }),
      el('span', { text: (CHECK_TEXT[c.status] || c.status) + (c.message && c.message !== 'OK' ? ' — ' + c.message : '') }));
  }

  function renderLive() {
    const panel = $('rl-live-panel');
    const links = livesOf(state.asset);
    panel.classList.toggle('d-none', !links.length);
    panel.replaceChildren(...links.map(liveSection));
    if (mod.limits) { renderMod(); }
  }

  /** Un bloc par lien (actif, wallet/provider) : snapshot, preset lié, plan et exécution de ce lien. */
  function liveSection(l) {
    const bag = el('input', { type: 'number', class: 'form-control form-control-sm', id: 'rl-live-bag-' + l.bindingId, min: '0', max: '100',
      step: 'any', 'aria-label': 'bagPercent' });
    bag.value = String(l.bagPercent);

    return el('div', { class: 'mb-3' },
      el('div', { class: 'd-flex flex-wrap justify-content-between align-items-center gap-2 mb-2' },
        el('h5', { class: 'mb-0' }, el('span', { class: 'badge rl-badge-live me-2', text: 'LIVE' }), 'Wallet réel — ' + l.assetSymbol + ' · ' + l.walletName + ' (' + (l.exchange || DASH) + ')'),
        el('span', { class: 'small text-muted', text: state.exec && state.exec.liveExecution
          ? 'Exécution réelle déverrouillée : des ordres peuvent être envoyés selon les verrous ci-dessous.'
          : 'Aucun ordre n\'est passé (exécution réelle verrouillée).' })),
      liveSnapshotView(l),
      el('div', { class: 'row g-2 align-items-end mt-2' },
        el('div', { class: 'col-12 col-md-5' }, el('div', { class: 'form-label small mb-0', text: 'Affectation live' }),
          el('div', { class: 'small', text: l.affectationName })),
        el('div', { class: 'col-6 col-md-2' }, el('label', { class: 'form-label small mb-0', for: 'rl-live-bag-' + l.bindingId, text: 'bagPercent (0–100)' }), bag),
        el('div', { class: 'col-auto' },
          el('button', { type: 'button', class: 'btn btn-sm btn-primary', text: 'Enregistrer', onclick: () => saveLive(l, bag) }),
          el('button', { type: 'button', class: 'btn btn-sm btn-outline-secondary ms-1', text: 'Vérifier la disponibilité',
            title: 'Relit les soldes en lecture seule chez l\'exchange', onclick: () => checkLive(l) }),
          el('button', { type: 'button', class: 'btn btn-sm btn-outline-secondary ms-1', text: 'Comparer les routes',
            title: 'Chiffre les routes d\'achat (frais réels, carnet) en lecture seule', onclick: () => quoteRoutes(l) }))),
      el('div', { id: 'rl-live-msg-' + l.bindingId, class: 'small text-danger mt-1' }),
      liveCheckView(l),
      routesView(l),
      planView(l),
      executionView(l));
  }

  /** bagPercent du lien via PUT /bindings/{id} ; pris en compte à la passe suivante. */
  async function saveLive(l, bag) {
    const msg = $('rl-live-msg-' + l.bindingId);
    const bagPercent = Number(bag.value);
    if (bag.value === '' || !Number.isFinite(bagPercent) || bagPercent < 0 || bagPercent > 100) {
      msg.textContent = 'bagPercent doit être compris entre 0 et 100.';
      return;
    }
    try {
      const res = await api('PUT', '/bindings/' + l.bindingId, { bagPercent });
      state.liveCheck = Object.assign({ bindingId: l.bindingId }, res.check);
      await loadLive();
      await loadAffectations();
    } catch (e) {
      msg.textContent = 'Enregistrement impossible : ' + e.message;
    }
  }

  async function checkLive(l) {
    try {
      state.liveCheck = Object.assign({ bindingId: l.bindingId }, await api('GET', '/bindings/' + l.bindingId + '/check'));
      renderLive();
    } catch (e) {
      $('rl-live-msg-' + l.bindingId).textContent = 'Vérification impossible : ' + e.message;
    }
  }

  /* ------------------------------------------------------------------ initialisation */

  function bindEvents() {
    $('rl-alert-retry').addEventListener('click', () => { if (state.retry) { state.retry(); } });
  }

  async function init() {
    bindEvents();
    bindMod();
    loadMod();
    bindOnboarding();
    loadOnboarding();
    try {
      state.defaults = await api('GET', '/defaults');
    } catch (e) {
      showAlert('Chargement de la configuration impossible : ' + e.message, init);
      return;
    }
    hideAlert();
    state.defaults.zones.forEach((z) => state.zones.set(z.code, z.label));
    buildTabs();
    selectAsset(state.defaults.assets[0]);
  }

  init();
})();
