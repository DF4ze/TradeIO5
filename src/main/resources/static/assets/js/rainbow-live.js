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
    formMode: null, formPreset: null, deleteTarget: null,
    charts: [], perf: null, retry: null, updateHist: null
  };

  /* ------------------------------------------------------------------ utilitaires */

  const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
  const nf = (min, max) => new Intl.NumberFormat('fr-FR', { minimumFractionDigits: min, maximumFractionDigits: max });
  const F2 = nf(2, 2); const F8 = nf(6, 8); const FVAR = nf(0, 6);
  const usd = (v) => (isNum(v) ? F2.format(v) + ' USDC' : DASH);
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
    return loadPresets();
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
  }

  function presetRow(p) {
    const w = p.wallet;
    const last = p.lastRun;
    const toggle = el('input', { type: 'checkbox', class: 'form-check-input', role: 'switch',
      title: 'Activer / désactiver ce preset' });
    toggle.checked = p.enabled;
    toggle.addEventListener('change', () => toggleEnabled(p, toggle));
    return el('tr', { class: p.id === state.selectedId ? 'table-active' : '' },
      el('td', { text: p.name }),
      el('td', null, el('div', { class: 'form-check form-switch' }, toggle)),
      el('td', { text: p.analysisWindowMonths }),
      el('td', { text: usd(p.initialCapitalUsdc) }),
      el('td', { text: w ? usd(w.cashUsdc) + ' / ' + qty(w.positionQuantity) + ' / ' + usd(w.equityUsdc) : DASH }),
      el('td', { text: p.runCount }),
      el('td', { text: p.firstRunDay || DASH }),
      el('td', { text: last ? last.day + ' · ' + zoneLabel(last.zone) + ' · '
        + actionText(last.actionType, last.actionAmountUsdc, last.actionQuantity) : DASH }),
      el('td', { class: 'text-nowrap' },
        el('div', { class: 'btn-group btn-group-sm' },
          el('button', { type: 'button', class: 'btn btn-outline-primary', text: 'Voir', onclick: () => selectPreset(p.id) }),
          el('button', { type: 'button', class: 'btn btn-outline-secondary', text: 'Éditer', onclick: () => openForm('edit', p) }),
          el('button', { type: 'button', class: 'btn btn-outline-secondary', text: 'Dupliquer', onclick: () => openForm('duplicate', p) }),
          el('button', { type: 'button', class: 'btn btn-outline-danger', text: 'Supprimer', onclick: () => openDelete(p) }))));
  }

  /** Bascule rapide actif/inactif : PUT avec le reste inchangé. */
  async function toggleEnabled(p, input) {
    const wanted = input.checked;
    input.disabled = true;
    try {
      await api('PUT', '/presets/' + p.id, { name: p.name, enabled: wanted, analysisWindowMonths: p.analysisWindowMonths,
        tuning: p.tuning, globals: p.globals });
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
  const inputId = (f) => 'rl-f-' + f.scope + '-' + f.key;

  function buildForm() {
    const acc = $('rl-form-accordion');
    const assetField = el('div', { class: 'col-12 col-md-6 col-xl-4' },
      el('label', { class: 'form-label small', for: 'rl-f-asset', text: 'Actif' }),
      el('input', { id: 'rl-f-asset', type: 'text', class: 'form-control form-control-sm', disabled: true }));
    acc.replaceChildren(...FORM_GROUPS.map((g, i) => {
      const fields = g.fields.map(fieldNode);
      if (i === 0) { fields.unshift(assetField); }
      return el('div', { class: 'accordion-item' },
        el('h2', { class: 'accordion-header' },
          el('button', { type: 'button', class: 'accordion-button' + (i === 0 ? '' : ' collapsed'),
            'data-bs-toggle': 'collapse', 'data-bs-target': '#rl-acc-' + g.id, text: g.title })),
        el('div', { id: 'rl-acc-' + g.id, class: 'accordion-collapse collapse' + (i === 0 ? ' show' : '') },
          el('div', { class: 'accordion-body' }, el('div', { class: 'row g-3' }, fields))));
    }));
  }

  function fieldNode(f) {
    const id = inputId(f);
    let input;
    if (f.type === 'bool') {
      input = el('input', { id, type: 'checkbox', class: 'form-check-input' });
      return el('div', { class: 'col-12 col-md-6 col-xl-4' },
        el('div', { class: 'form-check form-switch mt-4' }, input,
          el('label', { class: 'form-check-label small', for: id, text: f.label })));
    }
    if (f.type === 'enum') {
      input = el('select', { id, class: 'form-select form-select-sm' },
        (state.defaults.reentryModes || []).map((m) => el('option', { value: m, text: m })));
    } else if (f.type === 'text') {
      input = el('input', { id, type: 'text', class: 'form-control form-control-sm', maxlength: 100 });
    } else {
      input = el('input', { id, type: 'number', step: f.type === 'int' ? '1' : 'any', class: 'form-control form-control-sm' });
    }
    return el('div', { class: 'col-12 col-md-6 col-xl-4' },
      el('label', { class: 'form-label small', for: id, text: f.label }), input,
      el('div', { class: 'invalid-feedback', text: f.type === 'text' ? 'Valeur requise.' : 'Nombre valide requis.' }));
  }

  const sourceOf = (f, data) => (f.scope === 'preset' ? data.preset : data[f.scope]);

  /** Remplit le formulaire depuis {preset:{...}, tuning, globals}. */
  function fillForm(data) {
    for (const g of FORM_GROUPS) {
      for (const f of g.fields) {
        const input = $(inputId(f));
        const v = sourceOf(f, data)[f.key];
        if (f.type === 'bool') { input.checked = !!v; } else { input.value = v == null ? '' : v; }
        input.classList.remove('is-invalid');
      }
    }
  }

  function formData(p) {
    return { preset: { name: p.name, enabled: p.enabled, analysisWindowMonths: p.analysisWindowMonths,
      initialCapitalUsdc: p.initialCapitalUsdc }, tuning: p.tuning, globals: p.globals };
  }

  /** mode : 'create' | 'edit' | 'duplicate' (création pré-remplie avec la config d'un preset). */
  function openForm(mode, p) {
    state.formMode = mode === 'edit' ? 'edit' : 'create';
    state.formPreset = p || null;
    const asset = p ? p.assetSymbol : state.asset;
    $('rl-form-title').textContent = mode === 'edit' ? 'Éditer « ' + p.name + ' »'
      : (mode === 'duplicate' ? 'Dupliquer « ' + p.name + ' »' : 'Nouveau preset — ' + asset);
    $('rl-form-banner').classList.toggle('d-none', mode !== 'edit');
    $('rl-form-error').classList.add('d-none');
    $('rl-f-asset').value = asset;
    if (p) {
      const d = formData(p);
      if (mode === 'duplicate') { d.preset.name = p.name + ' (copie)'; }
      fillForm(d);
    } else {
      const cfg = state.defaults.configs[asset];
      fillForm({ preset: { name: '', enabled: true, analysisWindowMonths: state.defaults.analysisWindowMonths,
        initialCapitalUsdc: state.defaults.initialCapitalUsdc }, tuning: cfg.tuning, globals: cfg.globals });
    }
    // capital initial : lecture seule en édition (l'actif l'est toujours)
    $(inputId({ scope: 'preset', key: 'initialCapitalUsdc' })).disabled = mode === 'edit';
    bootstrap.Modal.getOrCreateInstance($('rl-form-modal')).show();
  }

  /** Remplace tuning + globals par le défaut de l'actif (nom, actif, fenêtre et capital inchangés). */
  function fillWithAssetDefault() {
    const cfg = state.defaults.configs[$('rl-f-asset').value];
    if (!cfg) { return; }
    const keep = {};
    for (const f of FORM_GROUPS[0].fields) {
      const input = $(inputId(f));
      keep[f.key] = f.type === 'bool' ? input.checked : input.value;
    }
    fillForm({ preset: keep, tuning: cfg.tuning, globals: cfg.globals });
  }

  /** Lit et valide le formulaire ; marque les champs invalides, ouvre le groupe fautif. */
  function collectForm() {
    const out = { preset: {}, tuning: {}, globals: {} };
    let firstBad = null;
    for (const g of FORM_GROUPS) {
      for (const f of g.fields) {
        if (f.createOnly && state.formMode === 'edit') { continue; }
        const input = $(inputId(f));
        let v;
        let ok = true;
        if (f.type === 'bool') { v = input.checked; }
        else if (f.type === 'enum') { v = input.value; ok = !!v; }
        else if (f.type === 'text') { v = input.value.trim(); ok = v.length > 0; }
        else {
          v = input.value.trim() === '' ? NaN : Number(input.value);
          ok = Number.isFinite(v) && (f.type !== 'int' || Number.isInteger(v));
        }
        input.classList.toggle('is-invalid', !ok);
        if (!ok && !firstBad) { firstBad = { input, group: g }; }
        out[f.scope][f.key] = v;
      }
    }
    if (firstBad) {
      bootstrap.Collapse.getOrCreateInstance($('rl-acc-' + firstBad.group.id), { toggle: false }).show();
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
      if (state.formMode === 'edit') {
        saved = await api('PUT', '/presets/' + state.formPreset.id, { name: d.preset.name, enabled: d.preset.enabled,
          analysisWindowMonths: d.preset.analysisWindowMonths, tuning: d.tuning, globals: d.globals });
      } else {
        saved = await api('POST', '/presets', { assetSymbol: $('rl-f-asset').value, name: d.preset.name,
          enabled: d.preset.enabled, analysisWindowMonths: d.preset.analysisWindowMonths,
          initialCapitalUsdc: d.preset.initialCapitalUsdc, tuning: d.tuning, globals: d.globals });
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
      const perf = await api('GET', '/presets/' + id + '/performance');
      if (id !== state.selectedId) { return; }
      state.perf = perf;
      renderPerformance(panel, perf);
      if (perf.days > 0) { renderCharts(perf); }
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
      $('rl-chart-pos').textContent = '';
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
        card('Capital initial', usd(w.initialCapitalUsdc)), card('Cash', usd(w.cashUsdc)),
        card('Position', qty(w.positionQuantity)), card('Équité', usd(w.equityUsdc), signClass(w.equityUsdc - w.initialCapitalUsdc)),
        card('PnL wallet', pct(w.pnlPercent), signClass(w.pnlPercent))));
  }

  function destroyCharts() {
    state.charts.forEach((c) => { try { c.remove(); } catch (e) { /* déjà détruit */ } });
    state.charts = [];
    state.updateHist = null;
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

  function renderCharts(perf) {
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

    // --- graphique taille de position (histogramme)
    const posEl = $('rl-chart-pos');
    posEl.textContent = '';
    const chart2 = LW.createChart(posEl, baseChartOptions(LW, false));
    const hist = chart2.addSeries(LW.HistogramSeries, {});
    state.charts = [chart, chart2];
    state.updateHist = () => {
      const metric = (document.querySelector('input[name="rl-pos-metric"]:checked') || {}).value || 'currentValue';
      hist.applyOptions({ priceFormat: metric === 'position' ? { type: 'price', precision: 8, minMove: 0.00000001 } : fmt });
      hist.setData(series.map((p) => ({ time: p.day, value: p[metric],
        color: p.actionType === 'BUY' ? COLORS.buy : (p.actionType === 'SELL' ? COLORS.sell : COLORS.neutral) })));
    };
    state.updateHist();
    chart2.timeScale().fitContent();

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
      const detail = el('tr', { class: 'rl-detail-row d-none' }, el('td', { colspan: 9 }, runDetail(r)));
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
        el('thead', null, el('tr', null, ...['', 'Jour', 'Close', 'Zone', 'Action 23:55', 'Action 00:05', 'États',
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

  /* ------------------------------------------------------------------ initialisation */

  function bindEvents() {
    $('rl-alert-retry').addEventListener('click', () => { if (state.retry) { state.retry(); } });
    $('rl-new-preset').addEventListener('click', () => openForm('create', null));
    $('rl-form-submit').addEventListener('click', submitForm);
    $('rl-form-fill-default').addEventListener('click', fillWithAssetDefault);
    $('rl-delete-confirm').addEventListener('click', confirmDelete);
    $('rl-runs-apply').addEventListener('click', loadRuns);
    $('rl-runs-90').addEventListener('click', () => { $('rl-runs-from').value = todayMinus(90); $('rl-runs-to').value = ''; loadRuns(); });
    $('rl-runs-all').addEventListener('click', () => { $('rl-runs-from').value = ''; $('rl-runs-to').value = ''; loadRuns(); });
    document.querySelectorAll('input[name="rl-pos-metric"]').forEach((r) =>
      r.addEventListener('change', () => { if (state.updateHist) { state.updateHist(); } }));
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
    buildForm();
    selectAsset(state.defaults.assets[0]);
  }

  init();
})();
