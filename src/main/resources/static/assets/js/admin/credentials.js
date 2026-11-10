/* Onglet Credentials : tableau masqué (GET /api/admin/credentials) + saisie/rotation pour n'importe quel utilisateur (POST /api/admin/credentials). Aucun secret ré-affiché. */
(function () {
  'use strict';
  const core = window.AdminCore;
  if (!core) { return; }
  const { el, api, run, byId } = core;

  const MASTER_KEY_MESSAGE = 'TRADEIO_MASTER_KEY absente du processus : aucune clé TRADE ne peut être enregistrée ni lue '
    + '(aucun repli en clair). La poser dans l\'environnement du processus de l\'application, puis le relancer.';

  /* Droits de clé à cocher côté exchange (jamais de retrait) et restriction IP, rappelés selon l'exchange choisi. */
  const PROVIDER_HINTS = {
    OKX: 'OKX : droit « Trade » au comptant uniquement (jamais « Withdraw »), passphrase exigée, restriction IP sur l\'IP du VPS.',
    KRAKEN: 'Kraken : cocher Query Funds, Create & Modify Orders, Cancel & Close Orders, Query Open Orders & Trades, Query Closed Orders & Trades '
      + '— jamais Withdraw Funds ni Deposit. Pas de passphrase. Restreindre la clé à l\'IP du VPS.',
    ONE_TRADING: 'One Trading : clé API seule (pas de secret ni de passphrase). Portée Read pour la lecture, Trade pour les ordres '
      + '— jamais Withdraw. Restreindre la clé à l\'IP du VPS.',
    BITVAVO: 'Bitvavo : clé API + secret (pas de passphrase). Droits « Voir » pour la lecture, « Trader » pour les ordres '
      + '— jamais « Retirer ». Restreindre la clé à l\'IP du VPS.',
    REVOLUT_X: 'Revolut X : de préférence via « Génération de clé » ci-dessous. Saisie manuelle : clé API (champ Clé API) + clé privée Ed25519 PEM (champ secret), '
      + 'sans passphrase. Droit lecture pour la portée Lecture, trading pour Trade, jamais de retrait ; restreindre à l\'IP du VPS.',
    GATE_EU: 'Gate EU : clé API + secret (pas de passphrase). Droits spot lecture / trading uniquement, jamais de retrait. '
      + 'Restreindre la clé à l\'IP du VPS.'
  };

  const ORDER_PROVIDERS = ['OKX', 'KRAKEN', 'ONE_TRADING', 'BITVAVO', 'REVOLUT_X', 'GATE_EU'];
  const KEY_ONLY_PROVIDERS = ['ONE_TRADING'];
  const PASSPHRASE_PROVIDERS = ['OKX'];
  const DATA_HINT = 'Provider de données (ex. COINALYZE, TWELVE_DATA, FINNHUB pour System) : clé API seule suffit, secret facultatif. Portée Lecture.';
  let users = [];

  /* Providers à clé générée par le serveur (GET /api/credentials/keypair/providers) : flux générer -> clé publique -> clé API, pour le compte connecté. */
  let keyPairProviders = [];
  const keyPairScope = () => byId('adm-cred-scope').value;

  async function renderKeyPair() {
    const provider = providerValue();
    const panel = byId('adm-keypair');
    const supported = !isFree() && keyPairProviders.some((p) => p.provider === provider);
    panel.classList.toggle('d-none', !supported);
    if (!supported) { return; }
    const base = '/api/credentials/keypair/' + provider + '/' + keyPairScope();
    const body = byId('adm-keypair-body');
    const generate = el('button', { type: 'button', class: 'btn btn-outline-primary', text: 'Générer la clé' });
    generate.addEventListener('click', () => run(generate, async () => { drawPending(await api('POST', base)); }));
    function drawPending(p) {
      const pub = el('textarea', { class: 'form-control font-monospace small', rows: 5, readonly: 'readonly' });
      pub.value = p.publicKeyPem;
      const apiKey = el('input', { type: 'password', class: 'form-control', autocomplete: 'off', placeholder: 'Clé API fournie par l\'exchange' });
      const ok = el('button', { type: 'button', class: 'btn btn-primary', text: 'Valider et enregistrer' });
      ok.addEventListener('click', () => run(ok, async () => {
        const saved = await api('POST', base + '/promote', { apiKey: apiKey.value });
        apiKey.value = '';
        byId('adm-cred-status').textContent = 'Clé générée enregistrée (credential #' + saved.id + ', ' + saved.provider + ' ' + saved.scope + ').';
        await load();
      }));
      const cancel = el('button', { type: 'button', class: 'btn btn-outline-secondary', text: 'Annuler' });
      cancel.addEventListener('click', () => run(cancel, async () => { await api('DELETE', base); body.replaceChildren(generate); }));
      body.replaceChildren(el('div', { class: 'alert alert-info small mb-0', text: p.hint }), pub,
        el('div', { class: 'small text-muted', text: 'Empreinte SHA-256 ' + p.fingerprint + ' — valable jusqu\'au ' + core.fmtDate(p.expiresAt) }),
        apiKey, el('div', { class: 'd-flex gap-2' }, ok, generate, cancel));
      generate.textContent = 'Régénérer';
    }
    body.replaceChildren(generate);
    generate.textContent = 'Générer la clé';
    try { drawPending(await api('GET', base)); } catch (e) { if (e.status !== 404) { throw e; } }
  }

  const isFree = () => byId('adm-cred-free').checked;
  const providerValue = () => (isFree() ? byId('adm-cred-provider-free').value.trim().toUpperCase() : byId('adm-cred-provider').value);

  /* Adapte le formulaire au provider : passphrase (OKX seul), secret obligatoire pour les exchanges d'ordres, portée figée à Lecture pour un provider de données. */
  function onProviderChange() {
    const provider = providerValue();
    const order = ORDER_PROVIDERS.includes(provider);
    const withPassphrase = PASSPHRASE_PROVIDERS.includes(provider);
    byId('adm-cred-provider').classList.toggle('d-none', isFree());
    byId('adm-cred-provider-free').classList.toggle('d-none', !isFree());
    byId('adm-cred-pass-group').classList.toggle('d-none', !withPassphrase);
    byId('adm-cred-pass').required = withPassphrase;
    if (!withPassphrase) { byId('adm-cred-pass').value = ''; }
    const keyOnly = KEY_ONLY_PROVIDERS.includes(provider);
    byId('adm-cred-secret').required = order && !keyOnly;
    byId('adm-cred-secret').closest('.col-12').classList.toggle('d-none', keyOnly);
    if (keyOnly) { byId('adm-cred-secret').value = ''; }
    const scope = byId('adm-cred-scope');
    if (provider && !order) { scope.value = 'READ'; }
    scope.disabled = !order;
    byId('adm-cred-hint').textContent = order ? (PROVIDER_HINTS[provider] || '') : (provider ? DATA_HINT : '');
    renderKeyPair().catch((e) => { byId('adm-cred-status').textContent = e.message; });
  }

  function renderUsers() {
    const select = byId('adm-cred-user');
    const current = select.value;
    select.replaceChildren(...users.map((u) => el('option', { value: u.id, text: u.username + ' (#' + u.id + ')' })));
    const me = users.find((u) => u.username === core.username);
    select.value = users.some((u) => String(u.id) === current) ? current : (me ? me.id : (users[0] ? users[0].id : ''));
  }

  /* Liste des exchanges d'ordres = providers déclarés et actifs (onglet Providers) ; OKX / Kraken par défaut si rien n'est lisible. */
  function renderProviders(declared) {
    const select = byId('adm-cred-provider');
    const current = select.value;
    const codes = declared.filter((p) => p.enabled && ORDER_PROVIDERS.includes(p.code)).map((p) => p.code);
    const list = codes.length ? codes : ['OKX', 'KRAKEN'];
    select.replaceChildren(...list.map((c) => el('option', { value: c, text: c })));
    if (list.includes(current)) { select.value = current; }
    onProviderChange();
  }

  function showMasterWarning(state) {
    const box = byId('adm-master-warning');
    box.textContent = MASTER_KEY_MESSAGE;
    box.classList.toggle('d-none', !state || state.masterKeyConfigured);
  }

  function hintText(c) {
    if (c.apiKeyHint == null) { return core.DASH; }
    return c.scope === 'TRADE' ? c.apiKeyHint : '…' + c.apiKeyHint;
  }

  function row(c) {
    const toggle = el('button', { type: 'button', class: 'btn btn-sm btn-outline-secondary',
      text: c.enabled ? 'Désactiver' : 'Activer' });
    toggle.addEventListener('click', () => run(toggle, async () => {
      await api('PUT', '/api/admin/credentials/' + c.id + '/enabled', { enabled: !c.enabled });
      await load();
    }));
    return el('tr', null,
      el('td', { text: c.id }), el('td', { text: c.username }), el('td', { text: c.provider }),
      el('td', null, el('span', { class: 'badge text-bg-' + (c.scope === 'TRADE' ? 'danger' : 'secondary'), text: c.scope })),
      el('td', { text: c.enabled ? 'oui' : 'non' }), el('td', { text: core.fmtDate(c.createdAt) }),
      el('td', { text: hintText(c) }), el('td', null, toggle));
  }

  async function load() {
    const [list, userList, providers, keyPairs] = await Promise.all([api('GET', '/api/admin/credentials'), api('GET', '/api/admin/users'),
      api('GET', '/api/admin/providers'), api('GET', '/api/credentials/keypair/providers')]);
    users = userList;
    keyPairProviders = keyPairs;
    renderProviders(providers.providers);
    renderUsers();
    byId('adm-cred-body').replaceChildren(...(list.length ? list.map(row)
      : [el('tr', null, el('td', { colspan: 8 }, core.empty('Aucune credential enregistrée.')))]));
    showMasterWarning(core.state);
  }

  function clearSecrets() {
    ['adm-cred-apikey', 'adm-cred-secret', 'adm-cred-pass'].forEach((id) => { byId(id).value = ''; });
  }

  async function submit(event) {
    event.preventDefault();
    const status = byId('adm-cred-status');
    status.textContent = '';
    const provider = providerValue();
    const order = ORDER_PROVIDERS.includes(provider);
    const body = {
      userId: Number(byId('adm-cred-user').value), provider, scope: order ? byId('adm-cred-scope').value : 'READ',
      apiKey: byId('adm-cred-apikey').value, secretKey: byId('adm-cred-secret').value || null,
      passphrase: PASSPHRASE_PROVIDERS.includes(provider) ? byId('adm-cred-pass').value : null,
      enabled: byId('adm-cred-enabled').checked
    };
    clearSecrets();
    await run(byId('adm-cred-submit'), async () => {
      try {
        const saved = await api('POST', '/api/admin/credentials', body);
        status.textContent = 'Enregistrée pour ' + saved.username + ' (credential #' + saved.id + ', ' + saved.provider + ' ' + saved.scope + ').';
        await load();
      } catch (e) {
        if (e.status === 409) { showMasterWarning({ masterKeyConfigured: false }); }
        throw e;
      } finally {
        body.apiKey = body.secretKey = body.passphrase = null;
        await core.refreshState().catch(() => { /* bandeau rafraîchi au prochain tour */ });
      }
    });
  }

  byId('adm-cred-form').addEventListener('submit', submit);
  byId('adm-cred-provider').addEventListener('change', onProviderChange);
  byId('adm-cred-provider-free').addEventListener('input', onProviderChange);
  byId('adm-cred-free').addEventListener('change', onProviderChange);
  byId('adm-cred-scope').addEventListener('change', onProviderChange);
  onProviderChange();
  core.onState(showMasterWarning);
  core.register('credentials', { load: () => run(null, load), show: () => run(null, load) });
})();
