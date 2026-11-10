/* Onglet Testeur d'ordre : POST /api/admin/execution/order-test/validate (validation seule, aucun ordre créé) + liste des paires du catalogue. */
(function () {
  'use strict';
  const core = window.AdminCore;
  if (!core) { return; }
  const { el, api, run, byId, DASH } = core;

  const CATALOG_DEBOUNCE_MS = 300;
  let instruments = new Map();
  let credentials = [];
  let timer = null;

  const text = (v) => (v == null ? DASH : String(v));

  /* Exchanges proposés d'office ; complétés par ceux de l'état d'exécution et des clés Trade déjà saisies. */
  const BASE_PROVIDERS = ['KRAKEN', 'OKX'];
  const PROVIDER_LABELS = { KRAKEN: 'Kraken', OKX: 'OKX' };
  /* Ce que la validation d'une route contrôle réellement, par exchange. */
  const ROUTE_NOTES = {
    KRAKEN: 'Kraken contrôle aussi le solde : la jambe 2 est refusée « fonds insuffisants » tant que l\'intermédiaire (ex. BTC) n\'est pas détenu, c\'est attendu.',
    OKX: 'OKX : contrôles locaux seuls (paire, tailles, tick, plafonds), pas de contrôle de solde côté exchange : la jambe 2 est jugée comme une jambe isolée.'
  };
  const GENERIC_ROUTE_NOTE = 'Selon l\'exchange, le solde de l\'intermédiaire est contrôlé ou non ; un refus « fonds insuffisants » sur la jambe 2 est attendu tant que la jambe 1 n\'est pas exécutée.';

  function limitsText(i) {
    if (!i) { return ''; }
    return 'Limites : taille min ' + text(i.minSz) + ' · coût min ' + text(i.costMin) + ' · lot ' + text(i.lotSz)
      + ' · tick ' + text(i.tickSz) + ' · devise de frais ' + text(i.feeCurrency) + ' · état ' + text(i.state);
  }

  function showLimits() {
    byId('adm-ot-limits').textContent = limitsText(instruments.get(byId('adm-ot-inst').value.trim().toUpperCase()));
  }

  async function loadCatalog() {
    const provider = byId('adm-ot-provider').value;
    const q = byId('adm-ot-inst').value.trim();
    const list = await api('GET', '/api/admin/catalog/instruments?provider=' + encodeURIComponent(provider)
      + '&q=' + encodeURIComponent(q));
    instruments = new Map(list.map((i) => [i.instId, i]));
    byId('adm-ot-inst-list').replaceChildren(...list.map((i) => el('option', { value: i.instId })));
    showLimits();
    renderRouteHeader();
  }

  function renderUsers() {
    const provider = byId('adm-ot-provider').value;
    const owners = credentials.filter((c) => c.scope === 'TRADE' && c.provider === provider);
    const options = owners.length
      ? owners.map((c) => el('option', { value: c.userId, text: c.username + ' (#' + c.userId + ')' + (c.enabled ? '' : ' — désactivée') }))
      : [el('option', { value: '', text: 'Aucune clé Trade pour cet exchange' })];
    byId('adm-ot-user').replaceChildren(...options);
  }

  function renderProviders() {
    const select = byId('adm-ot-provider');
    const current = select.value;
    const known = new Set(BASE_PROVIDERS);
    ((core.state && core.state.orderProviders) || []).forEach((p) => known.add(p));
    credentials.filter((c) => c.scope === 'TRADE').forEach((c) => known.add(c.provider));
    select.replaceChildren(...Array.from(known).map((p) => el('option', { value: p, text: PROVIDER_LABELS[p] || p })));
    select.value = known.has(current) ? current : BASE_PROVIDERS[0];
  }

  /** Bloc route : exchange courant, rappel de ce qui est contrôlé, datalist des paires de la jambe 2. */
  function renderRouteHeader() {
    const provider = byId('adm-ot-provider').value;
    byId('adm-ot-r2-provider').textContent = PROVIDER_LABELS[provider] || provider;
    byId('adm-ot-r2-note').textContent = ROUTE_NOTES[provider] || GENERIC_ROUTE_NOTE;
    byId('adm-ot-r2-inst-list').replaceChildren(...Array.from(instruments.keys()).map((id) => el('option', { value: id })));
  }

  async function loadAll() {
    credentials = await api('GET', '/api/admin/credentials');
    renderProviders();
    renderUsers();
    await loadCatalog();
  }

  function onProviderChange() {
    renderUsers();
    renderRouteHeader();
    run(null, loadCatalog);
  }

  function onRouteSizeModeChange() {
    byId('adm-ot-r2-size-label').textContent = byId('adm-ot-r2-sizemode').value === 'sz' ? 'Taille (base)' : 'Montant (cotation)';
  }

  function onSizeModeChange() {
    byId('adm-ot-size-label').textContent = byId('adm-ot-sizemode').value === 'sz' ? 'Taille (base)' : 'Montant (cotation)';
  }

  /** Libellé du champ prix selon le sens : BUY = plafond, SELL = plancher (une seule valeur numérique). */
  function onSideChange() {
    byId('adm-ot-px-label').textContent = byId('adm-ot-side').value === 'BUY'
      ? 'Plafond de prix (BUY) : prix max accepté' : 'Plancher de prix (SELL) : prix min accepté';
  }

  function onInstInput() {
    showLimits();
    clearTimeout(timer);
    timer = setTimeout(() => run(null, loadCatalog), CATALOG_DEBOUNCE_MS);
  }

  function checkRow(c) {
    return el('li', null, el('span', { class: 'badge text-bg-' + (c.ok ? 'success' : 'danger') + ' me-2', text: c.ok ? '✔' : '✘' }),
      el('strong', { text: c.name + ' ' }), c.detail || '');
  }

  function renderResult(r) {
    byId('adm-ot-result').classList.remove('d-none');
    const verdict = byId('adm-ot-verdict');
    let cls = 'alert-danger';
    let lines;
    if (r.blockedBy) {
      const e = core.explain(r.blockedBy);
      lines = [el('strong', { text: 'Bloqué (aucun appel à l\'exchange) : ' }), r.blockedBy + ' — ' + e.text + ' → ' + e.action];
      cls = 'alert-warning';
    } else if (r.accepted) {
      cls = 'alert-success';
      lines = [el('strong', { text: 'Accepté. ' }), r.message || ''];
    } else {
      const e = r.exchangeCode ? core.explain(r.exchangeCode) : null;
      lines = [el('strong', { text: 'Refusé. ' }), e ? r.exchangeCode + ' — ' + e.text + ' → ' + e.action : (r.message || '')];
    }
    verdict.className = 'alert ' + cls;
    verdict.replaceChildren(...lines);
    byId('adm-ot-checks').replaceChildren(...(r.localChecks.length ? r.localChecks.map(checkRow) : [el('li', null, core.empty('Aucun contrôle.'))]));
    const n = r.normalizedRequest;
    byId('adm-ot-normalized').replaceChildren(...(n
      ? [el('div', { text: n.side + ' ' + n.instId + ' ' + n.ordType }), el('div', { text: 'taille ' + text(n.sz) + ' · prix limite ' + text(n.px)
        + ' · coût ≈ ' + text(n.cost) })]
      : [core.empty('Requête non constructible.')]));
    byId('adm-ot-raw').textContent = r.rawFiltered || DASH;
    if (r.limits) { byId('adm-ot-limits').textContent = limitsText(Object.assign({}, instruments.get(n && n.instId), r.limits)); }
  }

  /* ------------------------------------------------------------------ ordre réel (switch rouge) */

  const REAL_CONFIRMATION = 'ENVOYER';
  let lastSent = null;

  function onRealToggle() {
    const real = byId('adm-ot-real').checked;
    byId('adm-ot-real-box').classList.toggle('d-none', !real);
    byId('adm-ot-real-box').classList.toggle('d-flex', real);
    const btn = byId('adm-ot-submit');
    btn.className = 'btn ' + (real ? 'btn-danger' : 'btn-primary');
    btn.textContent = real ? 'Envoyer l\'ordre RÉEL' : 'Valider';
    byId('adm-ot-hint').textContent = real ? 'ATTENTION : un vrai ordre sera envoyé.' : 'Aucun ordre n\'est créé.';
    byId('adm-ot-hint').className = 'small ms-2 ' + (real ? 'text-danger fw-bold' : 'text-muted');
    if (!real) { byId('adm-ot-confirm').value = ''; }
  }

  function renderSent(r) {
    byId('adm-ot-sent').classList.remove('d-none');
    byId('adm-ot-sent-text').replaceChildren(
      el('div', null, el('strong', { text: r.status + ' ' }), r.message || ''),
      el('div', { text: 'clOrdId ' + text(r.clOrdId) + ' · ordId ' + text(r.ordId) + ' · état ' + text(r.phase)
        + ' · rempli ' + text(r.filledSz) + ' · prix moyen ' + text(r.avgPx) }));
    byId('adm-ot-sent-fills').replaceChildren(...(r.fills || []).map((f) => el('li', {
      text: f.sz + ' @ ' + f.px + ' · frais ' + text(f.fee) + ' ' + text(f.feeCcy) + ' · trade ' + f.tradeId })));
    byId('adm-ot-reconcile').classList.toggle('d-none', !r.clOrdId);
  }

  async function sendReal(body) {
    if (byId('adm-ot-confirm').value.trim() !== REAL_CONFIRMATION) {
      core.showAlert('Ordre réel : saisir ' + REAL_CONFIRMATION + ' pour confirmer.');
      return;
    }
    await run(byId('adm-ot-submit'), async () => {
      const r = await api('POST', '/api/admin/execution/order-test/real', { order: body, confirm: byId('adm-ot-confirm').value.trim() });
      if (r.validation) { renderResult(r.validation); }
      renderSent(r);
      lastSent = r.clOrdId ? { userId: body.userId, provider: body.provider, instId: body.instId, clOrdId: r.clOrdId } : null;
      byId('adm-ot-real').checked = false;
      onRealToggle();
      await core.refreshState();
    });
  }

  async function reconcile() {
    if (!lastSent) { return; }
    await run(byId('adm-ot-reconcile'), async () => {
      renderSent(await api('POST', '/api/admin/execution/order-test/reconcile', lastSent));
    });
  }

  /** Prix limite auto depuis le carnet public (ask BUY / bid SELL ± tolérance du sélecteur) ; renseigne `input` et le retourne. */
  async function autoPrice(provider, instId, side, input) {
    if (!instId) { throw new Error('Prix auto : renseigner d\'abord la paire.'); }
    const q = new URLSearchParams({ provider, instId, side, tolerancePct: byId('adm-ot-tol').value });
    const r = await api('GET', '/api/admin/execution/order-test/price-suggestion?' + q);
    input.value = String(r.px);
    input.title = 'Auto : ' + r.bookPx + ' ± ' + r.tolerancePct + ' % (' + r.estimation + ')';
    return String(r.px);
  }

  async function onAutoClick() {
    await run(byId('adm-ot-px-auto'), async () => {
      await autoPrice(byId('adm-ot-provider').value, byId('adm-ot-inst').value.trim().toUpperCase(),
        byId('adm-ot-side').value, byId('adm-ot-px'));
    });
  }

  async function submit(event) {
    event.preventDefault();
    const mode = byId('adm-ot-sizemode').value;
    if (!byId('adm-ot-px').value.trim()) {
      try {
        await autoPrice(byId('adm-ot-provider').value, byId('adm-ot-inst').value.trim().toUpperCase(),
          byId('adm-ot-side').value, byId('adm-ot-px'));
      } catch (e) { core.showAlert(e.message); return; }
    }
    const body = {
      userId: byId('adm-ot-user').value ? Number(byId('adm-ot-user').value) : null,
      provider: byId('adm-ot-provider').value, instId: byId('adm-ot-inst').value.trim().toUpperCase(),
      side: byId('adm-ot-side').value, px: byId('adm-ot-px').value.trim().replace(',', '.')
    };
    body[mode] = byId('adm-ot-size').value.trim().replace(',', '.');
    const numeric = /^\d+(\.\d+)?$/;
    if (!numeric.test(body.px) || !numeric.test(body[mode])) {
      core.showAlert('Prix et taille/montant : un seul nombre (ex. 84000), sans « / » ni texte.');
      return;
    }
    if (byId('adm-ot-real').checked) {
      await sendReal(body);
      return;
    }
    await run(byId('adm-ot-submit'), async () => {
      renderResult(await api('POST', '/api/admin/execution/order-test/validate', body));
    });
  }

  /** Valide la jambe 1 (formulaire) puis la jambe 2 : POST /validate-route, aucun envoi. */
  async function validateRoute() {
    const numeric = /^\d+(\.\d+)?$/;
    const mode = byId('adm-ot-sizemode').value;
    try {
      if (!byId('adm-ot-px').value.trim()) {
        await autoPrice(byId('adm-ot-provider').value, byId('adm-ot-inst').value.trim().toUpperCase(),
          byId('adm-ot-side').value, byId('adm-ot-px'));
      }
      if (!byId('adm-ot-r2-px').value.trim()) {
        await autoPrice(byId('adm-ot-provider').value, byId('adm-ot-r2-inst').value.trim().toUpperCase(),
          byId('adm-ot-r2-side').value, byId('adm-ot-r2-px'));
      }
    } catch (e) { core.showAlert(e.message); return; }
    const leg1 = {
      userId: byId('adm-ot-user').value ? Number(byId('adm-ot-user').value) : null,
      provider: byId('adm-ot-provider').value, instId: byId('adm-ot-inst').value.trim().toUpperCase(),
      side: byId('adm-ot-side').value, px: byId('adm-ot-px').value.trim().replace(',', '.')
    };
    leg1[mode] = byId('adm-ot-size').value.trim().replace(',', '.');
    const mode2 = byId('adm-ot-r2-sizemode').value;
    const leg2 = {
      userId: leg1.userId, provider: leg1.provider, instId: byId('adm-ot-r2-inst').value.trim().toUpperCase(),
      side: byId('adm-ot-r2-side').value, px: byId('adm-ot-r2-px').value.trim().replace(',', '.')
    };
    leg2[mode2] = byId('adm-ot-r2-sz').value.trim().replace(',', '.');
    if (!leg2.instId || !numeric.test(leg1.px) || !numeric.test(leg1[mode]) || !numeric.test(leg2[mode2]) || !numeric.test(leg2.px)) {
      core.showAlert('Route : renseigner les deux jambes (nombres simples, sans « / » ni texte).');
      return;
    }
    await run(byId('adm-ot-route'), async () => {
      const r = await api('POST', '/api/admin/execution/order-test/validate-route', { legs: [leg1, leg2] });
      const rows = r.legs.map((leg, i) => el('li', null,
        el('span', { class: 'badge text-bg-' + (leg.accepted ? 'success' : 'danger') + ' me-2', text: leg.accepted ? '✔' : '✘' }),
        el('strong', { text: 'Jambe ' + (i + 1) + ' ' }),
        leg.blockedBy ? 'Bloquée (aucun appel exchange) : ' + leg.blockedBy : (leg.accepted ? 'acceptée. ' : 'refusée. ') + (leg.message || '')));
      if (r.note) { rows.push(el('li', { class: 'text-warning-emphasis', text: r.note })); }
      byId('adm-ot-route-result').replaceChildren(...rows);
    });
  }

  byId('adm-ot-route').addEventListener('click', validateRoute);
  byId('adm-ot-px-auto').addEventListener('click', onAutoClick);
  byId('adm-ot-form').addEventListener('submit', submit);
  byId('adm-ot-provider').addEventListener('change', onProviderChange);
  byId('adm-ot-sizemode').addEventListener('change', onSizeModeChange);
  byId('adm-ot-r2-sizemode').addEventListener('change', onRouteSizeModeChange);
  byId('adm-ot-side').addEventListener('change', onSideChange);
  byId('adm-ot-real').addEventListener('change', onRealToggle);
  byId('adm-ot-reconcile').addEventListener('click', reconcile);
  onSideChange();
  byId('adm-ot-inst').addEventListener('input', onInstInput);
  core.register('orderTester', { load: () => run(null, loadAll), show: () => run(null, loadAll) });
})();
