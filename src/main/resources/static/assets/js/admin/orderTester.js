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
  }

  function renderUsers() {
    const provider = byId('adm-ot-provider').value;
    const owners = credentials.filter((c) => c.scope === 'TRADE' && c.provider === provider);
    const options = owners.length
      ? owners.map((c) => el('option', { value: c.userId, text: c.username + ' (#' + c.userId + ')' + (c.enabled ? '' : ' — désactivée') }))
      : [el('option', { value: '', text: 'Aucune clé Trade pour cet exchange' })];
    byId('adm-ot-user').replaceChildren(...options);
  }

  async function loadAll() {
    credentials = await api('GET', '/api/admin/credentials');
    renderUsers();
    await loadCatalog();
  }

  function onProviderChange() {
    renderUsers();
    run(null, loadCatalog);
  }

  function onSizeModeChange() {
    byId('adm-ot-size-label').textContent = byId('adm-ot-sizemode').value === 'sz' ? 'Taille (base)' : 'Montant (cotation)';
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

  async function submit(event) {
    event.preventDefault();
    const mode = byId('adm-ot-sizemode').value;
    const body = {
      userId: byId('adm-ot-user').value ? Number(byId('adm-ot-user').value) : null,
      provider: byId('adm-ot-provider').value, instId: byId('adm-ot-inst').value.trim().toUpperCase(),
      side: byId('adm-ot-side').value, px: byId('adm-ot-px').value.trim()
    };
    body[mode] = byId('adm-ot-size').value.trim();
    await run(byId('adm-ot-submit'), async () => {
      renderResult(await api('POST', '/api/admin/execution/order-test/validate', body));
    });
  }

  byId('adm-ot-form').addEventListener('submit', submit);
  byId('adm-ot-provider').addEventListener('change', onProviderChange);
  byId('adm-ot-sizemode').addEventListener('change', onSizeModeChange);
  byId('adm-ot-inst').addEventListener('input', onInstInput);
  core.register('orderTester', { load: () => run(null, loadAll), show: () => run(null, loadAll) });
})();
