/*
 * Page "Bench grandeur nature — Rainbow DCA ATR" (userPage, fragment fragments/rainbowLive.html).
 * Contrat : API REST /api/rainbow-live (cf. docs/api/rest-endpoints.md). Tous les chiffres viennent du serveur :
 * ce fichier ne fait que de l'affichage. Entrées utilisateur/serveur insérées via textContent (jamais en HTML).
 * Graphiques : TradingView lightweight-charts v5 (vendorée, assets/vendor/lightweight-charts).
 */
(function () {
  'use strict';

  const API = '/api/rainbow-live';
  const root = document.getElementById('rainbow-live');
  if (!root) { return; }
  const $ = (id) => document.getElementById(id);

  const DASH = '—';
  const COLORS = { buy: '#198754', sell: '#dc3545', neutral: '#adb5bd', strategy: '#0d6efd', fixed: '#fd7e14',
    invested: '#6c757d', config: '#6f42c1' };

  const state = {
    defaults: null, zones: new Map(), asset: null, presets: [], selectedId: null,
    formMode: null, formKind: 'FIXED', formPreset: null, formSource: null, uiMode: 'EXPERT', deleteTarget: null,
    charts: [], perf: null, retry: null,
    live: new Map(), liveCheck: null, plan: null, exec: null, events: null
  };

  /* ------------------------------------------------------------------ utilitaires */

  const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
  const nf = (min, max) => new Intl.NumberFormat('fr-FR', { minimumFractionDigits: min, maximumFractionDigits: max });
  const F2 = nf(2, 2); const F8 = nf(6, 8); const FVAR = nf(0, 6);
  const usd = (v) => (isNum(v) ? F2.format(v) + ' USD' : DASH);
  const qty = (v) => (isNum(v) ? F8.format(v) : DASH);
  const pct = (v) => (isNum(v) ? F2.format(v) + ' %' : DASH);
  const dec = (v) => (isNum(v) ? FVAR.format(v) : DASH);
  const signClass = (v) => (isNum(v) ? (v > 0 ? 'rl-pos' : (v < 0 ? 'rl-neg' : '')) : '');
  const zoneLabel = (z) => (z == null ? DASH : (state.zones.get(z) || String(z)));
  const todayMinus = (days) => new Date(Date.now() - days * 86400000).toISOString().slice(0, 10);

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
      res = await fetch(API + path, {
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
  const emptyMsg = (text) => el('div', { class: 'text-muted fst-italic', text });

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
    $('rl-detail').classList.add('d-none');
    destroyCharts();
    state.liveCheck = null;
    return Promise.all([loadLive(), loadPresets()]);
  }

  async function loadPresets() {
    try {
      state.presets = await api('GET', '/presets?asset=' + encodeURIComponent(state.asset));
      hideAlert();
      renderPresets();
    } catch (e) {
      showAlert('Chargement des presets impossible : ' + e.message, loadPresets);
    }
  }

  function renderPresets() {
    const body = $('rl-presets-body');
    const empty = state.presets.length === 0;
    $('rl-presets-table').classList.toggle('d-none', empty);
    $('rl-presets-empty').classList.toggle('d-none', !empty);
    body.replaceChildren(...state.presets.map((p) => presetRow(p)));
    renderLive();
  }

  const isTrend = (p) => p.mode === 'TREND_MIX';
  const isLivePreset = (p) => { const l = state.live.get(p.assetSymbol); return !!l && l.presetId === p.id; };

  /** « Bear · DOWN » (badge de régime coloré) ; tiret si non applicable. */
  function setTrendCell(set, regime) {
    if (!set && !regime) { return DASH; }
    return el('span', { class: 'text-nowrap' }, el('span', { class: 'me-1', text: set || DASH }),
      regime ? el('span', { class: 'badge rl-tag-' + regime.toLowerCase(), text: regime }) : null);
  }

  function presetRow(p) {
    const w = p.wallet;
    const last = p.lastRun;
    const toggle = el('input', { type: 'checkbox', class: 'form-check-input', role: 'switch',
      title: 'Activer / désactiver ce preset' });
    toggle.checked = p.enabled;
    toggle.addEventListener('change', () => toggleEnabled(p, toggle));
    return el('tr', { class: p.id === state.selectedId ? 'table-active' : '' },
      el('td', null, el('span', { text: p.name }), isLivePreset(p) ? el('span', { class: 'badge rl-badge-live ms-1', text: 'LIVE',
        title: 'Preset live : lié à un wallet réel (action recommandée, jamais exécutée)' }) : null),
      el('td', null, el('span', { class: 'badge ' + (isTrend(p) ? 'bg-primary' : 'bg-secondary'), text: isTrend(p) ? 'Trend Mix' : 'Fixe' }),
        p.followsStrategy ? el('span', { class: 'badge bg-dark ms-1', text: 'Stratégie · rév. ' + p.strategyRevision,
          title: 'Suit la stratégie System (révision ' + p.strategyRevision + ') : seule son activation est modifiable ; '
            + 'dupliquer pour te détacher' }) : el('span', { class: 'badge bg-light text-dark ms-1', text: 'Détaché',
          title: 'Preset propre à ton compte : ne reçoit pas les mises à jour de System' })),
      el('td', null, el('div', { class: 'form-check form-switch' }, toggle)),
      el('td', { text: p.analysisWindowMonths }),
      el('td', { text: usd(p.initialCapitalUsdc) }),
      el('td', { text: w ? usd(w.cashUsd) + ' / ' + qty(w.positionQuantity) + ' / ' + usd(w.equityUsdc) : DASH }),
      el('td', { text: p.runCount }),
      el('td', { text: p.firstRunDay || DASH }),
      el('td', null, setTrendCell(last && last.activeSet, last && last.trendRegime)),
      el('td', { text: last ? last.day + ' · ' + zoneLabel(last.zone) + ' · '
        + actionText(last.actionType, last.actionAmountUsdc, last.actionQuantity) : DASH }),
      el('td', { class: 'text-nowrap' },
        el('div', { class: 'btn-group btn-group-sm' },
          el('button', { type: 'button', class: 'btn btn-outline-primary', text: 'Voir', onclick: () => selectPreset(p.id) }),
          el('button', { type: 'button', class: 'btn btn-outline-secondary', text: 'Éditer', disabled: p.followsStrategy,
            title: p.followsStrategy ? 'Suit la stratégie System : non modifiable (dupliquer pour te détacher)' : null, onclick: () => openForm('edit', p) }),
          el('button', { type: 'button', class: 'btn btn-outline-secondary', text: 'Dupliquer', onclick: () => openForm('duplicate', p) }),
          el('button', { type: 'button', class: 'btn btn-outline-danger', text: 'Supprimer', disabled: p.followsStrategy,
            title: p.followsStrategy ? 'Suit la stratégie System : non supprimable' : null, onclick: () => openDelete(p) }))));
  }

  /** Bascule rapide actif/inactif (seule modification permise sur un preset qui suit une stratégie Actif). */
  async function toggleEnabled(p, input) {
    const wanted = input.checked;
    input.disabled = true;
    try {
      await api('PATCH', '/presets/' + p.id + '/enabled', { enabled: wanted });
      hideAlert();
      await loadPresets();
    } catch (e) {
      input.checked = !wanted;
      input.disabled = false;
      showAlert('Modification impossible : ' + e.message, null);
    }
  }

  /* ------------------------------------------------------------------ formulaire (accordéon) */

  /** Schéma unique du formulaire : tous les paramètres du preset (noms = champs JSON de l'API). */
  const FORM_GROUPS = [
    { id: 'general', title: 'Général', fields: [
      { scope: 'preset', key: 'name', label: 'Nom', type: 'text' },
      { scope: 'preset', key: 'enabled', label: 'Preset actif (joué à chaque passe)', type: 'bool' },
      { scope: 'preset', key: 'analysisWindowMonths', label: "Fenêtre d'analyse (mois)", type: 'int' },
      { scope: 'preset', key: 'initialCapitalUsdc', label: 'Capital initial (USDC)', type: 'number', createOnly: true }] },
    { id: 'bounds', title: 'Bornes ATR', fields: [
      { scope: 'tuning', key: 'smaPeriod', label: 'Période SMA', type: 'int' },
      { scope: 'tuning', key: 'atrPeriod', label: 'Période ATR', type: 'int' },
      { scope: 'tuning', key: 'atrMultDown2', label: 'Mult. ATR borne extrême bas (down2)', type: 'number' },
      { scope: 'tuning', key: 'atrMultDown1', label: 'Mult. ATR borne basse (down1)', type: 'number' },
      { scope: 'tuning', key: 'atrMultUp1', label: 'Mult. ATR borne haute 1 (up1)', type: 'number' },
      { scope: 'tuning', key: 'atrMultUp2', label: 'Mult. ATR borne haute 2 (up2)', type: 'number' },
      { scope: 'tuning', key: 'atrMultUp3', label: 'Mult. ATR borne extrême haut (up3)', type: 'number' }] },
    { id: 'mechanism', title: 'Mécanisme achat/vente', fields: [
      { scope: 'tuning', key: 'buyReentryMode', label: "Sortie de l'armement achat", type: 'enum' },
      { scope: 'tuning', key: 'sellReentryMode', label: "Sortie de l'armement vente", type: 'enum' },
      { scope: 'tuning', key: 'trailingStopBuyPct', label: 'Trailing stop achat (%)', type: 'number' },
      { scope: 'tuning', key: 'trailingStopSellPct', label: 'Trailing stop vente (%)', type: 'number' },
      { scope: 'tuning', key: 'fixedDelayDays', label: 'Délai fixe (jours)', type: 'int' },
      { scope: 'tuning', key: 'sellFraction', label: 'Fraction vendue (0–1)', type: 'number' }] },
    { id: 'cooldown', title: 'Cooldown & verrous', fields: [
      { scope: 'tuning', key: 'cooldownDays', label: 'Cooldown après vente (jours)', type: 'int' },
      { scope: 'tuning', key: 'allowSellDuringCooldown', label: 'Armement de vente autorisé pendant le cooldown', type: 'bool' },
      { scope: 'tuning', key: 'cooldownAfterSellOn', label: 'Poser un cooldown après une vente', type: 'bool' },
      { scope: 'tuning', key: 'blockBuyAfterSellUntilDown2', label: 'Bloquer les achats après vente jusqu’à un nouveau passage sous down2', type: 'bool' }] },
    { id: 'ath', title: 'Modulation ATH', fields: [
      { scope: 'globals', key: 'athOn', label: 'Modulation ATH active', type: 'bool' },
      { scope: 'globals', key: 'athRefDdBuyPct', label: 'Drawdown de référence achat (%)', type: 'number' },
      { scope: 'globals', key: 'athRefDdSellPct', label: 'Drawdown de référence vente (%)', type: 'number' },
      { scope: 'globals', key: 'athBuyMin', label: 'Facteur achat min', type: 'number' },
      { scope: 'globals', key: 'athBuyMax', label: 'Facteur achat max', type: 'number' },
      { scope: 'globals', key: 'athSellMax', label: 'Facteur vente max', type: 'number' },
      { scope: 'globals', key: 'athSellMin', label: 'Facteur vente min', type: 'number' }] },
    { id: 'moon', title: 'To the moon', fields: [
      { scope: 'globals', key: 'moonOn', label: 'Mode To the moon actif', type: 'bool' },
      { scope: 'globals', key: 'moonReservePct', label: 'Réserve conservée (%)', type: 'number' },
      { scope: 'globals', key: 'moonReserveRatchet', label: 'Réserve en cliquet (ratchet)', type: 'bool' },
      { scope: 'globals', key: 'moonTrailingStopPct', label: 'Trailing stop moon (%)', type: 'number' },
      { scope: 'globals', key: 'moonStopSellPct', label: 'Vente au stop moon (%)', type: 'number' }] },
    { id: 'multipliers', title: 'Multiplicateurs & montant', fields: [
      { scope: 'globals', key: 'multX2', label: 'Multiplicateur zone ×2', type: 'number' },
      { scope: 'globals', key: 'multX1', label: 'Multiplicateur zone ×1', type: 'number' },
      { scope: 'globals', key: 'multX0_5', label: 'Multiplicateur zone ×0,5', type: 'number' },
      { scope: 'globals', key: 'multTriggered', label: 'Multiplicateur achat déclenché', type: 'number' },
      { scope: 'globals', key: 'baseAmount', label: 'Montant de base par achat (USDC)', type: 'number' }] }
  ];
  /** Groupes propres au Trend Mix (scope 'trend' = Params du Trend Mix, 'top' = racine de trendConfig). */
  const TREND_GROUP = { id: 'trend', title: 'Trend Mix (régression + SMA/ATR)', fields: [
    { scope: 'trend', key: 'shortWindow', label: 'Régression courte (j)', type: 'int' },
    { scope: 'trend', key: 'mediumWindow', label: 'Régression moyenne (j)', type: 'int' },
    { scope: 'trend', key: 'longWindow', label: 'Régression longue (j)', type: 'int' },
    { scope: 'trend', key: 'slopeScale', label: 'Échelle de pente', type: 'number' },
    { scope: 'trend', key: 'enter', label: 'Seuil ENTER (entrée en tendance)', type: 'number' },
    { scope: 'trend', key: 'exit', label: 'Seuil EXIT (sortie de tendance)', type: 'number' },
    { scope: 'trend', key: 'confirm', label: 'Confirmation (jours)', type: 'int' },
    { scope: 'trend', key: 'smaPeriod', label: 'Période SMA', type: 'int' },
    { scope: 'trend', key: 'atrPeriod', label: 'Période ATR', type: 'int' },
    { scope: 'trend', key: 'atrMultiplier', label: 'Mult. ATR autour de la SMA', type: 'number' },
    { scope: 'trend', key: 'wickDown', label: 'Mèche basse prise en compte', type: 'bool' },
    { scope: 'trend', key: 'wickUp', label: 'Mèche haute prise en compte', type: 'bool' },
    { scope: 'top', key: 'rangeMapping', label: 'Régime RANGE ⇒ jeu', type: 'range' }] };
  const SET_GROUPS = FORM_GROUPS.slice(1); // bornes, mécanisme, cooldown, ATH, moon, multiplicateurs
  const inputId = (f) => 'rl-f-' + f.scope.replace(':', '-') + '-' + f.key;
  const RANGE_LABELS = { KEEP_PREVIOUS: 'Garder le jeu précédent', TO_BEAR: 'Bear', TO_BULL: 'Bull' };

  /** Groupes du formulaire selon le type : FIXED = schéma historique ; TREND_MIX = général + Trend + tableau Bear|Bull. */
  const groupsFor = (kind) => (kind === 'TREND_MIX' ? [FORM_GROUPS[0], TREND_GROUP] : FORM_GROUPS);
  const setFields = (set) => SET_GROUPS.flatMap((g) => g.fields.map((f) => Object.assign({}, f, { scope: set + ':' + f.scope })));
  const allFields = (kind) => groupsFor(kind).flatMap((g) => g.fields)
    .concat(kind === 'TREND_MIX' ? setFields('bear').concat(setFields('bull')) : []);

  function buildForm(kind) {
    const acc = $('rl-form-accordion');
    const assetField = el('div', { class: 'col-12 col-md-6 col-xl-4' },
      el('label', { class: 'form-label small', for: 'rl-f-asset', text: 'Actif' }),
      el('input', { id: 'rl-f-asset', type: 'text', class: 'form-control form-control-sm', disabled: true }));
    const item = (g, i, body) => el('div', { class: 'accordion-item' },
      el('h2', { class: 'accordion-header' },
        el('button', { type: 'button', class: 'accordion-button' + (i === 0 ? '' : ' collapsed'),
          'data-bs-toggle': 'collapse', 'data-bs-target': '#rl-acc-' + g.id, text: g.title })),
      el('div', { id: 'rl-acc-' + g.id, class: 'accordion-collapse collapse' + (i === 0 ? ' show' : '') },
        el('div', { class: 'accordion-body' }, body)));
    const items = groupsFor(kind).map((g, i) => {
      const fields = g.fields.map(fieldNode);
      if (i === 0) { fields.unshift(assetField); }
      return item(g, i, el('div', { class: 'row g-3' }, fields));
    });
    if (kind === 'TREND_MIX') {
      SET_GROUPS.forEach((g, i) => items.push(item(
        { id: 'set-' + g.id, title: 'Jeux Bear | Bull — ' + g.title }, 1 + i, setsTable(g))));
    }
    acc.replaceChildren(...items);
  }

  /** Tableau paramètre × (Bear, Bull) ; ligne en ambre quand les deux jeux diffèrent. */
  function setsTable(g) {
    const rows = g.fields.map((f) => {
      const fb = Object.assign({}, f, { scope: 'bear:' + f.scope });
      const fu = Object.assign({}, f, { scope: 'bull:' + f.scope });
      const tr = el('tr', null, el('td', { text: f.label }),
        el('td', null, control(fb, inputId(fb))), el('td', null, control(fu, inputId(fu))));
      const refresh = () => tr.classList.toggle('rl-diff', readControl(fb) !== readControl(fu));
      tr.addEventListener('input', refresh); tr.addEventListener('change', refresh);
      tr.refreshDiff = refresh;
      return tr;
    });
    return el('table', { class: 'table table-sm align-middle rl-sets-table mb-0' },
      el('thead', null, el('tr', null, el('th', { text: 'Paramètre' }),
        el('th', null, el('span', { class: 'badge rl-tag-down', text: 'Bear' })),
        el('th', null, el('span', { class: 'badge rl-tag-up', text: 'Bull' })))),
      el('tbody', null, rows));
  }

  function readControl(f) {
    const input = $(inputId(f));
    return f.type === 'bool' ? String(input.checked) : input.value;
  }

  /** Contrôle de saisie seul (sans libellé). */
  function control(f, id) {
    if (f.type === 'bool') { return el('input', { id, type: 'checkbox', class: 'form-check-input' }); }
    if (f.type === 'enum') {
      return el('select', { id, class: 'form-select form-select-sm' },
        (state.defaults.reentryModes || []).map((m) => el('option', { value: m, text: m })));
    }
    if (f.type === 'range') {
      return el('select', { id, class: 'form-select form-select-sm' },
        (state.defaults.rangeMappings || []).map((m) => el('option', { value: m, text: RANGE_LABELS[m] || m })));
    }
    if (f.type === 'text') { return el('input', { id, type: 'text', class: 'form-control form-control-sm', maxlength: 100 }); }
    return el('input', { id, type: 'number', step: f.type === 'int' ? '1' : 'any', class: 'form-control form-control-sm' });
  }

  function fieldNode(f) {
    const id = inputId(f);
    const input = control(f, id);
    if (f.type === 'bool') {
      return el('div', { class: 'col-12 col-md-6 col-xl-4' },
        el('div', { class: 'form-check form-switch mt-4' }, input,
          el('label', { class: 'form-check-label small', for: id, text: f.label })));
    }
    return el('div', { class: 'col-12 col-md-6 col-xl-4' },
      el('label', { class: 'form-label small', for: id, text: f.label }), input,
      el('div', { class: 'invalid-feedback', text: f.type === 'text' ? 'Valeur requise.' : 'Nombre valide requis.' }));
  }

  /** Lecture/écriture dans {preset, tuning, globals, trend, top, bear:{tuning,globals}, bull:{...}} via le scope « a:b ». */
  function scopeObj(data, scope, create) {
    let o = data;
    for (const part of scope.split(':')) {
      if (o[part] == null) { if (!create) { return {}; } o[part] = {}; }
      o = o[part];
    }
    return o;
  }

  /** Remplit le formulaire (kind courant) depuis {preset, tuning, globals, trend, top, bear, bull}. */
  function fillForm(data) {
    for (const f of allFields(state.formKind)) {
      const input = $(inputId(f));
      const v = scopeObj(data, f.scope, false)[f.key];
      if (f.type === 'bool') { input.checked = !!v; } else { input.value = v == null ? '' : v; }
      input.classList.remove('is-invalid');
    }
    document.querySelectorAll('#rl-form-accordion tr').forEach((tr) => { if (tr.refreshDiff) { tr.refreshDiff(); } });
  }

  function formData(p) {
    const d = { preset: { name: p.name, enabled: p.enabled, analysisWindowMonths: p.analysisWindowMonths,
      initialCapitalUsdc: p.initialCapitalUsdc }, tuning: p.tuning, globals: p.globals };
    return isTrend(p) ? Object.assign(d, trendData(p.trendConfig)) : d;
  }

  const trendData = (tc) => ({ trend: tc.trend, top: { rangeMapping: tc.rangeMapping }, bear: tc.bear, bull: tc.bull });

  /** mode : 'create' | 'edit' | 'duplicate' (création pré-remplie avec la config d'un preset). */
  function openForm(mode, p, kind) {
    state.formMode = mode === 'edit' ? 'edit' : 'create';
    state.formPreset = p || null;
    state.formSource = mode === 'duplicate' ? p : null;
    state.formKind = p ? (isTrend(p) ? 'TREND_MIX' : 'FIXED') : (kind || 'FIXED');
    buildForm(state.formKind);
    const asset = p ? p.assetSymbol : state.asset;
    const label = state.formKind === 'TREND_MIX' ? 'Trend Mix' : 'preset fixe';
    $('rl-form-title').textContent = mode === 'edit' ? 'Éditer « ' + p.name + ' »'
      : (mode === 'duplicate' ? 'Dupliquer « ' + p.name + ' »' : 'Nouveau ' + label + ' — ' + asset);
    $('rl-form-banner').classList.toggle('d-none', mode !== 'edit');
    $('rl-form-detach-warning').classList.toggle('d-none', !(mode === 'duplicate' && p.followsStrategy));
    $('rl-form-error').classList.add('d-none');
    $('rl-f-asset').value = asset;
    if (p) {
      const d = formData(p);
      if (mode === 'duplicate') {
        const prefix = state.defaults.systemPrefix;
        d.preset.name = (p.followsStrategy && p.name.startsWith(prefix) ? p.name.slice(prefix.length) : p.name) + ' (copie)';
      }
      fillForm(d);
    } else {
      const cfg = state.defaults.configs[asset];
      const base = { preset: { name: state.formKind === 'TREND_MIX' ? 'Trend Mix' : '',
        enabled: true, analysisWindowMonths: state.defaults.analysisWindowMonths,
        initialCapitalUsdc: state.defaults.initialCapitalUsdc }, tuning: cfg.tuning, globals: cfg.globals };
      fillForm(state.formKind === 'TREND_MIX'
        ? Object.assign(base, trendData(state.defaults.trendDefaults[asset])) : base);
    }
    // capital initial : lecture seule en édition (l'actif l'est toujours)
    $(inputId({ scope: 'preset', key: 'initialCapitalUsdc' })).disabled = mode === 'edit';
    bootstrap.Modal.getOrCreateInstance($('rl-form-modal')).show();
  }

  /** Remplace tuning + globals par le défaut de l'actif (nom, actif, fenêtre et capital inchangés). */
  function fillWithAssetDefault() {
    const asset = $('rl-f-asset').value;
    const cfg = state.defaults.configs[asset];
    if (!cfg) { return; }
    const keep = {};
    for (const f of FORM_GROUPS[0].fields) {
      const input = $(inputId(f));
      keep[f.key] = f.type === 'bool' ? input.checked : input.value;
    }
    const data = { preset: keep, tuning: cfg.tuning, globals: cfg.globals };
    fillForm(state.formKind === 'TREND_MIX'
      ? Object.assign(data, trendData(state.defaults.trendDefaults[asset])) : data);
  }

  /** Lit et valide le formulaire ; marque les champs invalides, ouvre le groupe fautif. */
  function collectForm() {
    const out = { preset: {}, tuning: {}, globals: {}, trend: {}, top: {}, bear: {}, bull: {} };
    let firstBad = null;
    const groupOf = new Map();
    groupsFor(state.formKind).forEach((g) => g.fields.forEach((f) => groupOf.set(f, g.id)));
    SET_GROUPS.forEach((g) => g.fields.forEach((f0) => ['bear', 'bull'].forEach((set) =>
      groupOf.set(set + ':' + f0.scope + ':' + f0.key, 'set-' + g.id))));
    for (const f of allFields(state.formKind)) {
      if (f.createOnly && state.formMode === 'edit') { continue; }
      const input = $(inputId(f));
      let v;
      let ok = true;
      if (f.type === 'bool') { v = input.checked; }
      else if (f.type === 'enum' || f.type === 'range') { v = input.value; ok = !!v; }
      else if (f.type === 'text') { v = input.value.trim(); ok = v.length > 0; }
      else {
        v = input.value.trim() === '' ? NaN : Number(input.value);
        ok = Number.isFinite(v) && (f.type !== 'int' || Number.isInteger(v));
      }
      input.classList.toggle('is-invalid', !ok);
      if (!ok && !firstBad) {
        firstBad = { input, group: groupOf.get(f) || groupOf.get(f.scope + ':' + f.key) };
      }
      scopeObj(out, f.scope, true)[f.key] = v;
    }
    if (firstBad) {
      if (firstBad.group) {
        bootstrap.Collapse.getOrCreateInstance($('rl-acc-' + firstBad.group), { toggle: false }).show();
      }
      firstBad.input.focus();
      return null;
    }
    return out;
  }

  async function submitForm() {
    const errBox = $('rl-form-error');
    errBox.classList.add('d-none');
    const d = collectForm();
    if (!d) {
      errBox.textContent = 'Certains champs sont invalides ou vides.';
      errBox.classList.remove('d-none');
      return;
    }
    const btn = $('rl-form-submit');
    btn.disabled = true;
    try {
      let saved;
      const trend = state.formKind === 'TREND_MIX';
      const specific = trend
        ? { trendConfig: { trend: d.trend, rangeMapping: d.top.rangeMapping, bear: d.bear, bull: d.bull } }
        : { tuning: d.tuning, globals: d.globals };
      if (state.formMode === 'edit') {
        saved = await api('PUT', '/presets/' + state.formPreset.id, Object.assign({ name: d.preset.name,
          enabled: d.preset.enabled, analysisWindowMonths: d.preset.analysisWindowMonths }, specific));
      } else {
        saved = await api('POST', '/presets', Object.assign({ assetSymbol: $('rl-f-asset').value, name: d.preset.name,
          enabled: d.preset.enabled, analysisWindowMonths: d.preset.analysisWindowMonths,
          initialCapitalUsdc: d.preset.initialCapitalUsdc, mode: state.formKind,
          duplicatedFromId: state.formSource ? state.formSource.id : null }, specific));
      }
      bootstrap.Modal.getOrCreateInstance($('rl-form-modal')).hide();
      hideAlert();
      await loadPresets();
      if (state.formMode === 'create' || state.selectedId === saved.id) { selectPreset(saved.id); }
    } catch (e) {
      errBox.textContent = e.message;
      errBox.classList.remove('d-none');
    } finally {
      btn.disabled = false;
    }
  }

  /* ------------------------------------------------------------------ suppression */

  function openDelete(p) {
    state.deleteTarget = p;
    $('rl-delete-name').textContent = p.name;
    $('rl-delete-error').classList.add('d-none');
    bootstrap.Modal.getOrCreateInstance($('rl-delete-modal')).show();
  }

  async function confirmDelete() {
    const p = state.deleteTarget;
    if (!p) { return; }
    const btn = $('rl-delete-confirm');
    btn.disabled = true;
    try {
      await api('DELETE', '/presets/' + p.id);
      bootstrap.Modal.getOrCreateInstance($('rl-delete-modal')).hide();
      if (state.selectedId === p.id) {
        state.selectedId = null;
        $('rl-detail').classList.add('d-none');
        destroyCharts();
      }
      hideAlert();
      await loadPresets();
    } catch (e) {
      const box = $('rl-delete-error');
      box.textContent = e.message;
      box.classList.remove('d-none');
    } finally {
      btn.disabled = false;
    }
  }

  /* ------------------------------------------------------------------ wallet réel (preset live) */

  const LIVE_STATUS = {
    OK: ['bg-success', 'Lecture OK'],
    STALE: ['bg-warning text-dark', 'Ancien'],
    UNAVAILABLE: ['bg-danger', 'Indisponible'],
    NOT_TRADABLE: ['bg-warning text-dark', 'Non tradable']
  };
  const BLOCK_TEXT = { INSUFFICIENT_CASH: 'liquidité insuffisante', UNAVAILABLE: 'données indisponibles',
    NOT_TRADABLE_WITHOUT_FIAT: 'actif non tradable sans monnaie fiat' };
  const CHECK_TEXT = {
    OK: 'OK', WALLET_DISABLED: 'Wallet désactivé', CREDENTIAL_INVALID: 'Clé API invalide ou rejetée',
    PROVIDER_UNSUPPORTED: 'Exchange non pris en charge', BALANCE_UNAVAILABLE: 'Soldes illisibles',
    TRADABLE_VIA_BRIDGE: 'Tradable via passerelle USD', NOT_TRADABLE_WITHOUT_FIAT: 'Non tradable sans monnaie fiat',
    INSTRUMENT_UNAVAILABLE: 'Paire non vérifiable'
  };

  /** Binding + dernier snapshot de chaque actif (lecture base uniquement, aucun appel exchange). Échec ⇒ pas de bloc live. */
  async function loadLive() {
    try {
      const list = await api('GET', '/live-wallet');
      state.live = new Map(list.map((l) => [l.assetSymbol, l]));
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

  /** Dernier plan d'ordres (simulation) en base ; 204 ou erreur ⇒ pas de plan (le reste de la page n'en dépend pas). */
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

  /** Plan d'ordres de l'actif sélectionné (simulation : rien n'est envoyé à l'exchange). */
  function planView(l) {
    const p = state.plan;
    const sent = !!p && EXECUTED_STATUS.includes(p.status);
    const head = el('div', { class: 'd-flex flex-wrap align-items-center gap-2 mb-2' },
      el('h6', { class: 'mb-0', text: sent ? 'Plan d\'exécution (réel)' : 'Plan d\'exécution (simulation)' }),
      el('span', { class: 'badge bg-light text-dark border', text: sent ? 'ordres envoyés' : 'non envoyé' }));
    if (!p) {
      return el('div', { class: 'mt-3 border-top pt-2' }, head, el('div', { class: 'text-muted fst-italic', text: 'Aucun plan enregistré.' }));
    }
    const st = PLAN_STATUS[p.status] || ['bg-secondary', p.status];
    const expiry = p.status === 'EXPIRED' ? 'expiré' : 'valable jusqu\'à ' + new Date(p.expiresAt).toLocaleTimeString('fr-FR');
    return el('div', { class: 'mt-3 border-top pt-2' }, head,
      el('div', { class: 'small mb-2' },
        el('span', { class: 'badge ' + st[0] + ' me-2', text: st[1] }),
        el('span', { text: 'passe ' + p.day + ' ' + (p.pass === 'T0005' ? '00:05' : '23:55') + ' UTC · ' + expiry
          + ' (un plan n\'est qu\'une photo, l\'exécution re-devise)' }),
        p.executionBlockReason ? el('div', { class: 'text-warning-emphasis', text: 'Blocages : ' + p.executionBlockReason }) : null),
      planAssetView(p.assets.find((a) => a.assetSymbol === l.assetSymbol), l));
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
    } else if (l.firstLiveArmed) {
      first = el('button', { type: 'button', class: 'btn btn-sm btn-warning', id: 'rl-confirm-first',
        text: 'Confirmer le 1er ordre réel', onclick: () => confirmFirstLive(l) });
    } else {
      first = el('span', { class: 'text-muted', text: '1er ordre réel : en attente d\'armement par l\'administrateur' });
    }
    return el('div', { class: 'mt-3 border-top pt-2', id: 'rl-exec-panel' },
      el('h6', { class: 'mb-2', text: 'Exécution réelle' }),
      el('div', { class: 'mb-2' }, kill, mode),
      el('div', { class: 'form-check form-switch mb-2' }, sw,
        el('label', { class: 'form-check-label', for: 'rl-exec-switch', text: 'Exécution activée pour ' + l.assetSymbol })),
      el('div', { class: 'small mb-2' }, first),
      el('div', { id: 'rl-exec-msg', class: 'small text-danger mb-1' }),
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
      const m = $('rl-exec-msg'); if (m) { m.textContent = e.message; }
      return;
    }
    await loadLive();
  }

  async function confirmFirstLive(l) {
    try {
      await api('POST', '/bindings/' + l.bindingId + '/confirm-first-live');
    } catch (e) {
      const m = $('rl-exec-msg'); if (m) { m.textContent = e.message; }
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
      l.tradability === 'NOT_TRADABLE_WITHOUT_FIAT' && l.tradabilityMessage
        ? el('div', { class: 'alert alert-warning py-1 px-2 mb-2', role: 'alert', text: l.tradabilityMessage }) : null,
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
          + (s.fetchedAt ? new Date(s.fetchedAt).toLocaleString('fr-FR') : DASH) + ' · passe ' + s.day + ' ' + (s.pass === 'T0005' ? '00:05' : '23:55') + ' UTC'
          + (s.status === 'STALE' ? ' · ancien' : '') })),
      el('div', { class: 'row g-2' },
        card('Cash USD', usd(s.cashUsd), '', cashDetail(s.cashByMember)),
        card('Position réelle ' + l.assetSymbol, qty(s.positionQty)),
        card('Position tradable (' + pct(l.bagPercent) + ')', qty(s.tradableQty), '', 'Part de la position réelle offerte à la stratégie'),
        card('Cash déjà réservé', usd(s.cashReserved), '', 'Consommé par les actifs servis avant (cash commun)')),
      liveAction(s));
  }

  /** Détail du cash par membre du groupe USD (infobulle), vide si absent. */
  function cashDetail(byMember) {
    return byMember ? Object.keys(byMember).map((k) => k + ' ' + F2.format(byMember[k])).join(' · ') : '';
  }

  function liveCheckView() {
    const c = state.liveCheck;
    if (!c) { return null; }
    return el('div', { class: 'mt-2 small' },
      el('span', { class: 'badge ' + (c.ok ? 'bg-success' : 'bg-danger') + ' me-2', text: c.ok ? 'BindingCheck OK' : 'BindingCheck KO' }),
      el('span', { text: (CHECK_TEXT[c.status] || c.status) + (c.message && c.message !== 'OK' ? ' — ' + c.message : '') }));
  }

  function renderLive() {
    const panel = $('rl-live-panel');
    const l = state.live.get(state.asset);
    panel.classList.toggle('d-none', !l);
    if (!l) { panel.replaceChildren(); return; }

    const select = el('select', { class: 'form-select form-select-sm', id: 'rl-live-preset', 'aria-label': 'Preset live' });
    state.presets.filter((p) => p.assetSymbol === l.assetSymbol).forEach((p) => {
      const o = el('option', { value: String(p.id), text: p.name });
      o.selected = p.id === l.presetId;
      select.append(o);
    });
    if (!select.options.length) {
      const o = el('option', { value: String(l.presetId), text: l.presetName }); o.selected = true; select.append(o);
    }
    const bag = el('input', { type: 'number', class: 'form-control form-control-sm', id: 'rl-live-bag', min: '0', max: '100',
      step: 'any', 'aria-label': 'bagPercent' });
    bag.value = String(l.bagPercent);

    panel.replaceChildren(
      el('div', { class: 'd-flex flex-wrap justify-content-between align-items-center gap-2 mb-2' },
        el('h5', { class: 'mb-0' }, el('span', { class: 'badge rl-badge-live me-2', text: 'LIVE' }), 'Wallet réel — ' + l.assetSymbol),
        el('span', { class: 'small text-muted', text: state.exec && state.exec.liveExecution
          ? 'Exécution réelle déverrouillée : des ordres peuvent être envoyés selon les verrous ci-dessous. Le wallet fictif des presets reste une simulation.'
          : 'Aucun ordre n\'est passé (exécution réelle verrouillée). Le wallet fictif des presets reste une simulation.' })),
      liveSnapshotView(l),
      el('div', { class: 'row g-2 align-items-end mt-2' },
        el('div', { class: 'col-12 col-md-5' }, el('label', { class: 'form-label small mb-0', for: 'rl-live-preset', text: 'Preset live' }), select),
        el('div', { class: 'col-6 col-md-2' }, el('label', { class: 'form-label small mb-0', for: 'rl-live-bag', text: 'bagPercent (0–100)' }), bag),
        el('div', { class: 'col-auto' },
          el('button', { type: 'button', class: 'btn btn-sm btn-primary', text: 'Enregistrer', onclick: () => saveLive(l, select, bag) }),
          el('button', { type: 'button', class: 'btn btn-sm btn-outline-secondary ms-1', text: 'Vérifier la disponibilité',
            title: 'Relit les soldes en lecture seule chez l\'exchange', onclick: () => checkLive(l) }))),
      el('div', { id: 'rl-live-msg', class: 'small text-danger mt-1' }),
      liveCheckView(),
      planView(l),
      executionView(l));
  }

  /** Bascule du preset live et/ou bagPercent via PUT /bindings/{id} ; pris en compte à la passe suivante. */
  async function saveLive(l, select, bag) {
    const msg = $('rl-live-msg');
    const bagPercent = Number(bag.value);
    if (bag.value === '' || !Number.isFinite(bagPercent) || bagPercent < 0 || bagPercent > 100) {
      msg.textContent = 'bagPercent doit être compris entre 0 et 100.';
      return;
    }
    try {
      const res = await api('PUT', '/bindings/' + l.bindingId, { presetId: Number(select.value), bagPercent });
      state.liveCheck = res.check;
      await loadLive();
      await loadPresets();
    } catch (e) {
      msg.textContent = 'Enregistrement impossible : ' + e.message;
    }
  }

  async function checkLive(l) {
    try {
      state.liveCheck = await api('GET', '/bindings/' + l.bindingId + '/check');
      renderLive();
    } catch (e) {
      $('rl-live-msg').textContent = 'Vérification impossible : ' + e.message;
    }
  }

  /* ------------------------------------------------------------------ détail d'un preset */

  function selectPreset(id) {
    const p = state.presets.find((x) => x.id === id);
    if (!p) { return; }
    state.selectedId = id;
    renderPresets();
    $('rl-detail-title').textContent = '« ' + p.name + ' » — ' + p.assetSymbol;
    $('rl-detail').classList.remove('d-none');
    $('rl-runs-from').value = todayMinus(90);
    $('rl-runs-to').value = '';
    ['rl-perf-panel', 'rl-runs-panel', 'rl-delta-panel'].forEach((pid) =>
      $(pid).replaceChildren(emptyMsg('Chargement…')));
    loadPerformance(); loadRuns(); loadDelta();
    $('rl-detail').scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  const currentPreset = () => state.presets.find((x) => x.id === state.selectedId);

  /* -------- performance + graphiques */

  async function loadPerformance() {
    const id = state.selectedId;
    const panel = $('rl-perf-panel');
    try {
      const [perf, runs] = await Promise.all([api('GET', '/presets/' + id + '/performance'),
        api('GET', '/presets/' + id + '/runs').catch(() => [])]);
      if (id !== state.selectedId) { return; }
      state.perf = perf;
      renderPerformance(panel, perf);
      if (perf.days > 0) { renderCharts(perf, runs); }
    } catch (e) {
      if (id !== state.selectedId) { return; }
      destroyCharts();
      panelError(panel, e, loadPerformance);
    }
  }

  function renderPerformance(panel, perf) {
    if (perf.days === 0) {
      destroyCharts();
      panel.replaceChildren(emptyMsg('Aucun run encore : les passes 23:55/00:05 UTC alimenteront cette vue.'));
      $('rl-chart-perf').textContent = '';
      $('rl-chart-multi').textContent = '';
      $('rl-multi-legend').textContent = '';
      $('rl-chart-legend').textContent = '';
      return;
    }
    const m = perf.metrics;
    const w = perf.wallet;
    const fixedHelp = 'DCA fixe de référence : achat du montant de base (baseAmount du jour) au close de chaque jour ayant un run 23:55, '
      + 'depuis le 1er run, sans combler les jours manquants.';
    const walletHelp = 'La performance reflète le wallet mock : les actions fictives sont plafonnées par son cash et sa position.';
    panel.replaceChildren(
      el('div', { class: 'small text-muted mb-1', text: 'Du ' + perf.firstDay + ' au ' + perf.lastDay + ' — ' + perf.days + ' jour(s) avec run 23:55' }),
      el('div', { class: 'fw-semibold mt-2' }, 'Stratégie ', el('span', { class: 'rl-help', title: walletHelp, text: 'ⓘ' })),
      el('div', { class: 'row g-2' },
        card('Investi', usd(m.invested)), card('Vendu', usd(m.saleProceeds)), card('Restant (valeur)', usd(m.currentValue)),
        card('Gain réalisé', usd(m.realizedGain), signClass(m.realizedGain)),
        card('Gain potentiel', usd(m.potentialGain), signClass(m.potentialGain)),
        card('Gain total', usd(m.totalGain), signClass(m.totalGain)),
        card('PnL', pct(m.pnlPercent), signClass(m.pnlPercent)),
        card('Réalisé', pct(m.realizedPercent), signClass(m.realizedPercent)),
        card('Potentiel', pct(m.potentialPercent), signClass(m.potentialPercent)),
        card('Position', qty(m.position)), card('Coût de revient', usd(m.costBasis)), card('Dernier close', usd(m.lastClose))),
      el('div', { class: 'fw-semibold mt-3' }, 'DCA fixe de référence ', el('span', { class: 'rl-help', title: fixedHelp, text: 'ⓘ' })),
      el('div', { class: 'row g-2' },
        card('Investi', usd(m.fixedInvested)), card('Valeur', usd(m.fixedValue)),
        card('Gain', usd(m.fixedGain), signClass(m.fixedGain)), card('PnL', pct(m.fixedPnlPercent), signClass(m.fixedPnlPercent)),
        card('Écart stratégie − DCA fixe', usd(m.outperformanceGain), signClass(m.outperformanceGain)),
        card('Écart (points de PnL)', isNum(m.outperformancePoints) ? F2.format(m.outperformancePoints) + ' pts' : DASH,
          signClass(m.outperformancePoints))),
      el('div', { class: 'fw-semibold mt-3' }, 'Wallet mock ', el('span', { class: 'rl-help', title: walletHelp, text: 'ⓘ' })),
      el('div', { class: 'row g-2' },
        card('Capital initial', usd(w.initialCapitalUsdc)), card('Cash', usd(w.cashUsd)),
        card('Position', qty(w.positionQuantity)), card('Équité', usd(w.equityUsdc), signClass(w.equityUsdc - w.initialCapitalUsdc)),
        card('PnL wallet', pct(w.pnlPercent), signClass(w.pnlPercent))));
  }

  function destroyCharts() {
    state.charts.forEach((c) => { try { c.remove(); } catch (e) { /* déjà détruit */ } });
    state.charts = [];
    $('rl-chart-tooltip').classList.add('d-none');
  }

  const chartDay = (t) => (typeof t === 'string' ? t : (t && t.year
    ? t.year + '-' + String(t.month).padStart(2, '0') + '-' + String(t.day).padStart(2, '0') : null));

  function baseChartOptions(LW, attribution) {
    return {
      autoSize: true,
      layout: { background: { type: LW.ColorType.Solid, color: '#ffffff' }, textColor: '#333', attributionLogo: attribution },
      grid: { vertLines: { color: '#f1f3f5' }, horzLines: { color: '#f1f3f5' } },
      rightPriceScale: { borderVisible: false },
      timeScale: { borderVisible: false }
    };
  }

  const legendItem = (color, text) => el('span', { class: 'me-3' },
    el('span', { style: 'display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:4px;background:' + color }), text);

  function renderCharts(perf, runs) {
    destroyCharts();
    const errBox = $('rl-chart-error');
    errBox.classList.add('d-none');
    const LW = window.LightweightCharts;
    if (!LW) {
      errBox.textContent = 'Bibliothèque de graphiques non chargée.';
      errBox.classList.remove('d-none');
      return;
    }
    const series = perf.series;
    const days = new Set(series.map((p) => p.day));
    const snap = (day) => { const hit = series.find((p) => p.day >= day); return hit ? hit.day : null; };

    // --- graphique performance
    const perfEl = $('rl-chart-perf');
    perfEl.textContent = '';
    const chart = LW.createChart(perfEl, baseChartOptions(LW, true));
    const fmt = { type: 'price', precision: 2, minMove: 0.01 };
    const sStrat = chart.addSeries(LW.LineSeries, { color: COLORS.strategy, lineWidth: 2, priceFormat: fmt, title: 'Stratégie' });
    const sFixed = chart.addSeries(LW.LineSeries, { color: COLORS.fixed, lineWidth: 2, priceFormat: fmt, title: 'DCA fixe' });
    const sInv = chart.addSeries(LW.LineSeries, { color: COLORS.invested, lineWidth: 1, lineStyle: LW.LineStyle.Dashed,
      priceFormat: fmt, title: 'Investi' });
    sStrat.setData(series.map((p) => ({ time: p.day, value: p.saleProceeds + p.currentValue })));
    sFixed.setData(series.map((p) => ({ time: p.day, value: p.fixedValue })));
    sInv.setData(series.map((p) => ({ time: p.day, value: p.invested })));

    const byDay = new Map(series.map((p) => [p.day, { point: p, trades: [], configs: [] }]));
    const markers = [];
    perf.markers.forEach((t) => {
      if (!days.has(t.day)) { return; }
      byDay.get(t.day).trades.push(t);
      markers.push(t.type === 'BUY'
        ? { time: t.day, position: 'belowBar', color: COLORS.buy, shape: 'arrowUp' }
        : { time: t.day, position: 'aboveBar', color: COLORS.sell, shape: 'arrowDown' });
    });
    perf.configMarkers.forEach((c) => {
      const day = snap(c.day);
      if (!day) { return; }
      byDay.get(day).configs.push(c);
      markers.push({ time: day, position: 'aboveBar', color: COLORS.config, shape: 'circle', text: 'cfg' });
    });
    markers.sort((a, b) => (a.time < b.time ? -1 : (a.time > b.time ? 1 : 0)));
    LW.createSeriesMarkers(sStrat, markers);
    chart.timeScale().fitContent();

    const tip = $('rl-chart-tooltip');
    chart.subscribeCrosshairMove((param) => {
      const day = param && param.time ? chartDay(param.time) : null;
      const info = day ? byDay.get(day) : null;
      if (!info || !param.point) { tip.classList.add('d-none'); return; }
      const p = info.point;
      const lines = [el('div', { class: 'fw-semibold', text: day }),
        el('div', { text: 'Stratégie : ' + usd(p.saleProceeds + p.currentValue) }),
        el('div', { text: 'DCA fixe : ' + usd(p.fixedValue) }),
        el('div', { text: 'Investi : ' + usd(p.invested) })];
      info.trades.forEach((t) => lines.push(el('div', { class: t.type === 'BUY' ? 'rl-pos' : 'rl-neg',
        text: actionText(t.type, t.amountUsdc, t.quantity) + ' @ ' + usd(t.price) })));
      info.configs.forEach((c) => lines.push(el('div', { text: 'Config modifiée : '
        + (c.changedParams.length ? c.changedParams.join(', ') : DASH) })));
      tip.replaceChildren(...lines);
      tip.classList.remove('d-none');
    });

    // --- graphique multi-panneaux : prix + tags, PnL, expositions
    const chart2 = renderMultiChart(LW, perf, runs || [], fmt);
    state.charts = [chart, chart2];

    // plages visibles synchronisées (mêmes jours sur les deux graphiques)
    let syncing = false;
    const link = (from, to) => from.timeScale().subscribeVisibleLogicalRangeChange((r) => {
      if (syncing || !r) { return; }
      syncing = true;
      to.timeScale().setVisibleLogicalRange(r);
      syncing = false;
    });
    link(chart, chart2); link(chart2, chart);

    $('rl-chart-legend').replaceChildren(
      legendItem(COLORS.strategy, 'Stratégie (vendu + restant)'), legendItem(COLORS.fixed, 'DCA fixe (valeur)'),
      legendItem(COLORS.invested, 'Investi cumulé'), legendItem(COLORS.buy, '▲ achat'), legendItem(COLORS.sell, '▼ vente'),
      legendItem(COLORS.config, '● config modifiée'));
  }

  const pctText = (v) => (isNum(v) ? F2.format(v * 100) + ' %' : DASH);
  const compact = (v) => (isNum(v) ? nf(0, v >= 1000 ? 0 : 2).format(v) : DASH);

  /** 3 panneaux : close + SMA/bornes + tags d'ordres ; PnL ; expositions. Données = /performance + /runs (rien recalculé côté moteur). */
  function renderMultiChart(LW, perf, runs, fmt) {
    const host = $('rl-chart-multi');
    host.textContent = '';
    const chart = LW.createChart(host, baseChartOptions(LW, false));
    const series = perf.series;
    const L = (color, width, style, pane, title) => chart.addSeries(LW.LineSeries, { color, lineWidth: width,
      lineStyle: style || 0, priceFormat: fmt, priceLineVisible: false, lastValueVisible: false,
      crosshairMarkerVisible: false, title: title || '' }, pane);
    const dashed = LW.LineStyle.Dashed;

    // panneau 0 : prix, SMA, bornes
    const sClose = L('#212529', 2, 0, 0, 'Close');
    sClose.setData(series.map((p) => ({ time: p.day, value: p.close })));
    const blockOf = new Map(runs.map((r) => [r.day, r.pass2355 || r.pass0005]));
    const band = (key, color, width) => {
      const data = runs.map((r) => ({ time: r.day, value: (r.pass2355 || r.pass0005 || {})[key] }))
        .filter((d) => isNum(d.value));
      if (data.length) { L(color, width || 1, 0, 0).setData(data); }
    };
    band('sma', '#9ca3af'); band('boundDown2', '#198754', 2); band('boundDown1', '#86efac');
    band('boundUp1', '#fca5a5'); band('boundUp2', '#f87171'); band('boundUp3', '#dc3545', 2);
    const tags = perf.markers.map((t) => {
      const b = blockOf.get(t.day) || {};
      const factor = t.type === 'BUY' ? b.buyFactor : b.sellFactor;
      const text = (t.type === 'BUY' ? '▲ ' : '▼ ') + compact(t.price)
        + (isNum(factor) ? ' ×' + F2.format(factor) : '')
        + (isNum(b.athDistance) ? ' · ATH −' + pctText(b.athDistance) : '');
      return t.type === 'BUY'
        ? { time: t.day, position: 'belowBar', color: COLORS.buy, shape: 'arrowUp', text }
        : { time: t.day, position: 'aboveBar', color: COLORS.sell, shape: 'arrowDown', text };
    }).sort((a, b) => (a.time < b.time ? -1 : (a.time > b.time ? 1 : 0)));
    LW.createSeriesMarkers(sClose, tags);

    // panneau 1 : PnL (réalisé = vendu − coût des parts vendues ; potentiel = valeur − coût de revient)
    const realized = (p) => p.saleProceeds - (p.invested - p.costBasis);
    L('#198754', 2, 0, 1, 'réalisé').setData(series.map((p) => ({ time: p.day, value: realized(p) })));
    L('#0d6efd', 2, 0, 1, 'potentiel').setData(series.map((p) => ({ time: p.day, value: p.currentValue - p.costBasis })));
    L('#212529', 2, 0, 1, 'total').setData(series.map((p) => ({ time: p.day, value: realized(p) + p.currentValue - p.costBasis })));
    L('#9ca3af', 1, dashed, 1, 'DCA fixe').setData(series.map((p) => ({ time: p.day, value: p.fixedValue - p.fixedInvested })));

    // panneau 2 : expositions du wallet mock
    const cap = perf.wallet ? perf.wallet.initialCapitalUsdc : null;
    L('#212529', 2, 0, 2, 'wallet total').setData(series.map((p) => ({ time: p.day, value: p.walletEquity })));
    L('#0d6efd', 2, 0, 2, 'en actif').setData(series.map((p) => ({ time: p.day, value: p.currentValue })));
    L('#198754', 2, 0, 2, 'en stable').setData(series.map((p) => ({ time: p.day, value: p.walletEquity - p.currentValue })));
    if (isNum(cap)) { L('#9ca3af', 1, dashed, 2, 'capital initial').setData(series.map((p) => ({ time: p.day, value: cap }))); }

    const panes = chart.panes();
    [5, 2, 2].forEach((f, i) => { if (panes[i]) { panes[i].setStretchFactor(f); } });
    chart.timeScale().fitContent();

    $('rl-multi-legend').replaceChildren(
      legendItem('#212529', 'Close'), legendItem('#9ca3af', 'SMA'), legendItem('#198754', 'bornes basses / réalisé / stable'),
      legendItem('#dc3545', 'bornes hautes'), legendItem('#0d6efd', 'potentiel / en actif'),
      legendItem(COLORS.buy, '▲ achat (prix ×facteur ATH · distance ATH)'), legendItem(COLORS.sell, '▼ vente'));
    return chart;
  }

  /* -------- runs */

  async function loadRuns() {
    const id = state.selectedId;
    const panel = $('rl-runs-panel');
    const q = new URLSearchParams();
    if ($('rl-runs-from').value) { q.set('from', $('rl-runs-from').value); }
    if ($('rl-runs-to').value) { q.set('to', $('rl-runs-to').value); }
    try {
      const runs = await api('GET', '/presets/' + id + '/runs' + (q.toString() ? '?' + q : ''));
      if (id !== state.selectedId) { return; }
      renderRuns(panel, runs);
    } catch (e) {
      if (id !== state.selectedId) { return; }
      panelError(panel, e, loadRuns);
    }
  }

  const refBlock = (r) => r.pass2355 || r.pass0005;
  const BLOCK_FIELDS = [['sma', 'SMA'], ['atr', 'ATR'], ['boundDown2', 'Borne extrême bas'], ['boundDown1', 'Borne basse'],
    ['boundUp1', 'Borne haute 1'], ['boundUp2', 'Borne haute 2'], ['boundUp3', 'Borne extrême haut'],
    ['athDistance', 'Distance ATH'], ['buyFactor', 'Facteur achat'], ['sellFactor', 'Facteur vente'],
    ['moonReserveQty', 'Réserve moon (qté)']];

  function renderRuns(panel, runs) {
    if (runs.length === 0) {
      const p = currentPreset();
      panel.replaceChildren(emptyMsg(p && p.runCount === 0
        ? 'Aucun run encore : les passes 23:55/00:05 UTC alimenteront cette vue.' : 'Aucun run sur cette période.'));
      return;
    }
    const rows = [];
    [...runs].reverse().forEach((r) => {
      const b = refBlock(r);
      const detail = el('tr', { class: 'rl-detail-row d-none' }, el('td', { colspan: 10 }, runDetail(r)));
      const btn = el('button', { type: 'button', class: 'btn btn-sm btn-link p-0', text: '▸', title: 'Détails (bornes, facteurs)' });
      btn.addEventListener('click', () => {
        const open = detail.classList.toggle('d-none') === false;
        btn.textContent = open ? '▾' : '▸';
      });
      rows.push(el('tr', null,
        el('td', null, btn),
        el('td', { class: 'text-nowrap', text: r.day }),
        el('td', { text: b ? usd(b.close) : DASH }),
        el('td', { text: b ? zoneLabel(b.zone) : DASH }),
        el('td', { text: r.pass2355 ? actionText(r.pass2355.actionType, r.pass2355.actionAmountUsdc, r.pass2355.actionQuantity) : DASH }),
        el('td', { text: r.pass0005 ? actionText(r.pass0005.actionType, r.pass0005.actionAmountUsdc, r.pass0005.actionQuantity) : DASH }),
        el('td', null, setTrendCell(b && b.activeSet, b && b.trendRegime)),
        el('td', null, stateBadges(b)),
        el('td', { text: r.pass2355 ? usd(r.pass2355.cashAfter) + ' / ' + qty(r.pass2355.positionAfter) : DASH }),
        el('td', null,
          r.deltaActionDiffers ? el('span', { class: 'badge bg-warning text-dark me-1', text: '⚠ delta',
            title: "L'action diffère entre la passe 23:55 et la passe 00:05" }) : null,
          r.configChanged ? el('span', { class: 'badge bg-info text-dark', text: 'config modifiée',
            title: r.changedParams.length ? 'Paramètres modifiés : ' + r.changedParams.join(', ') : 'Configuration modifiée' }) : null)));
      rows.push(detail);
    });
    panel.replaceChildren(
      el('div', { class: 'small text-muted mb-1', text: runs.length + ' jour(s), le plus récent en premier. Action 23:55 = action fictive retenue.' }),
      el('div', { class: 'rl-runs-scroll' }, el('table', { class: 'table table-sm align-middle mb-0' },
        el('thead', null, el('tr', null, ...['', 'Jour', 'Close', 'Zone', 'Action 23:55', 'Action 00:05', 'Jeu · Trend', 'États',
          'Cash / position après', ''].map((h) => el('th', { text: h })))),
        el('tbody', null, rows))));
  }

  function stateBadges(b) {
    if (!b) { return DASH; }
    const tags = [];
    if (b.moonMode) { tags.push('moon'); }
    if (b.buyArmed) { tags.push('armé achat'); }
    if (b.sellArmed) { tags.push('armé vente'); }
    if (b.buyLocked) { tags.push('verrou achat'); }
    if (isNum(b.cooldownRemaining) && b.cooldownRemaining > 0) { tags.push('cooldown ' + b.cooldownRemaining + ' j'); }
    return tags.length ? tags.map((t) => el('span', { class: 'badge bg-secondary me-1', text: t })) : DASH;
  }

  function runDetail(r) {
    const head = el('tr', null, el('th', { text: '' }), el('th', { text: '23:55' }), el('th', { text: '00:05' }));
    const rows = BLOCK_FIELDS.map(([k, label]) => el('tr', null, el('td', { text: label }),
      el('td', { text: r.pass2355 ? dec(r.pass2355[k]) : DASH }), el('td', { text: r.pass0005 ? dec(r.pass0005[k]) : DASH })));
    return el('table', { class: 'table table-sm mb-0 w-auto' }, el('thead', null, head), el('tbody', null, rows));
  }

  /* -------- delta */

  async function loadDelta() {
    const id = state.selectedId;
    const panel = $('rl-delta-panel');
    try {
      const d = await api('GET', '/presets/' + id + '/delta');
      if (id !== state.selectedId) { return; }
      renderDelta(panel, d);
    } catch (e) {
      if (id !== state.selectedId) { return; }
      panelError(panel, e, loadDelta);
    }
  }

  const table = (heads, rows) => el('table', { class: 'table table-sm w-auto' },
    el('thead', null, el('tr', null, ...heads.map((h) => el('th', { text: h })))), el('tbody', null, rows));

  function renderDelta(panel, d) {
    if (d.daysCompared === 0) {
      panel.replaceChildren(emptyMsg('Aucun jour avec les deux passes (23:55 et 00:05) pour le moment : '
        + d.daysIgnored + ' jour(s) ignoré(s).'));
      return;
    }
    const share = d.daysActionDiffers / d.daysCompared * 100;
    const indicators = Object.entries(d.indicators || {}).map(([name, s]) => el('tr', null, el('td', { text: name }),
      el('td', { text: dec(s.meanAbs) }), el('td', { text: dec(s.maxAbs) }), el('td', { text: pct(s.meanPct) }), el('td', { text: pct(s.maxPct) })));
    panel.replaceChildren(
      el('div', { class: 'row g-2' },
        card('Jours comparés', String(d.daysCompared)),
        card('Jours ignorés', String(d.daysIgnored), '', "Jours sans l'une des deux passes"),
        card("Jours où l'action diffère", d.daysActionDiffers + ' (' + pct(share) + ')'),
        card('Divergences de zone', String(d.zoneDivergences.length)),
        card("Divergences d'états", String(d.stateDivergences.length))),
      el('div', { class: 'fw-semibold mt-3', text: 'Écart 00:05 − 23:55 par indicateur' }),
      table(['Indicateur', 'Moyenne abs.', 'Max abs.', 'Moyenne %', 'Max %'], indicators),
      d.actionDifferences.length ? el('div', null, el('div', { class: 'fw-semibold', text: 'Actions différentes' }),
        table(['Jour', 'Action 23:55', 'Action 00:05'], d.actionDifferences.map((a) => el('tr', null, el('td', { text: a.day }),
          el('td', { text: actionText(a.pass2355.type, a.pass2355.amountUsdc, a.pass2355.quantity) }),
          el('td', { text: actionText(a.pass0005.type, a.pass0005.amountUsdc, a.pass0005.quantity) }))))) : null,
      d.zoneDivergences.length ? el('div', null, el('div', { class: 'fw-semibold', text: 'Zones différentes' }),
        table(['Jour', 'Zone 23:55', 'Zone 00:05'], d.zoneDivergences.map((z) => el('tr', null, el('td', { text: z.day }),
          el('td', { text: zoneLabel(z.zone2355) }), el('td', { text: zoneLabel(z.zone0005) }))))) : null,
      d.stateDivergences.length ? el('div', null, el('div', { class: 'fw-semibold', text: 'États différents' }),
        table(['Jour', 'Champs'], d.stateDivergences.map((s) => el('tr', null, el('td', { text: s.day }),
          el('td', { text: s.fields.join(', ') }))))) : null,
      el('div', { class: 'small text-muted', text: 'Hypothèse à vérifier : un delta minime signifie des effets de bord quasi nuls. '
        + 'Les chiffres ci-dessus sont fournis tels quels, sans conclusion.' }));
  }

  /* ------------------------------------------------------------------ mode d'affichage (par utilisateur) */

  const UI_MODES = [['AUTO', 'Auto'], ['SIMPLE', 'Simple'], ['ADVANCED', 'Avancé'], ['EXPERT', 'Expert']];
  const UI_MODE_READY = new Set(['EXPERT']);

  function renderUiMode() {
    $('rl-ui-mode').replaceChildren(...UI_MODES.map(([code, label]) => el('button', {
      type: 'button', class: 'btn ' + (code === state.uiMode ? 'btn-secondary' : 'btn-outline-secondary'),
      disabled: !UI_MODE_READY.has(code), text: label,
      title: UI_MODE_READY.has(code) ? 'Tous les paramètres' : 'Bientôt disponible',
      onclick: () => setUiMode(code) })));
  }

  async function setUiMode(code) {
    try {
      state.uiMode = (await api('PUT', '/ui-mode', { mode: code })).mode;
      renderUiMode();
    } catch (e) { showAlert('Changement de mode impossible : ' + e.message, null); }
  }

  async function loadUiMode() {
    try { state.uiMode = (await api('GET', '/ui-mode')).mode; } catch (e) { state.uiMode = 'EXPERT'; }
    renderUiMode();
  }

  /* ------------------------------------------------------------------ initialisation */

  function bindEvents() {
    $('rl-alert-retry').addEventListener('click', () => { if (state.retry) { state.retry(); } });
    $('rl-new-preset').addEventListener('click', () => openForm('create', null, 'FIXED'));
    $('rl-new-trend').addEventListener('click', () => openForm('create', null, 'TREND_MIX'));
    $('rl-form-submit').addEventListener('click', submitForm);
    $('rl-form-fill-default').addEventListener('click', fillWithAssetDefault);
    $('rl-delete-confirm').addEventListener('click', confirmDelete);
    $('rl-runs-apply').addEventListener('click', loadRuns);
    $('rl-runs-90').addEventListener('click', () => { $('rl-runs-from').value = todayMinus(90); $('rl-runs-to').value = ''; loadRuns(); });
    $('rl-runs-all').addEventListener('click', () => { $('rl-runs-from').value = ''; $('rl-runs-to').value = ''; loadRuns(); });
  }

  async function init() {
    bindEvents();
    try {
      state.defaults = await api('GET', '/defaults');
    } catch (e) {
      showAlert('Chargement de la configuration impossible : ' + e.message, init);
      return;
    }
    hideAlert();
    state.defaults.zones.forEach((z) => state.zones.set(z.code, z.label));
    buildTabs();
    loadUiMode();
    selectAsset(state.defaults.assets[0]);
  }

  init();
})();
