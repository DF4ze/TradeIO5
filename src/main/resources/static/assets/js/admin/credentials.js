/* Onglet Credentials : tableau masqué (GET /api/admin/credentials) + saisie/rotation par le propriétaire. Aucun secret ré-affiché. */
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
      + '— jamais Withdraw Funds ni Deposit. Pas de passphrase. Restreindre la clé à l\'IP du VPS.'
  };

  function onProviderChange() {
    const provider = byId('adm-cred-provider').value;
    const withPassphrase = provider !== 'KRAKEN';
    byId('adm-cred-pass-group').classList.toggle('d-none', !withPassphrase);
    byId('adm-cred-pass').required = withPassphrase;
    if (!withPassphrase) { byId('adm-cred-pass').value = ''; }
    byId('adm-cred-hint').textContent = PROVIDER_HINTS[provider] || '';
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
    const list = await api('GET', '/api/admin/credentials');
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
    const body = {
      provider: byId('adm-cred-provider').value, apiKey: byId('adm-cred-apikey').value,
      secretKey: byId('adm-cred-secret').value,
      passphrase: byId('adm-cred-provider').value === 'KRAKEN' ? null : byId('adm-cred-pass').value,
      enabled: byId('adm-cred-enabled').checked
    };
    clearSecrets();
    await run(byId('adm-cred-submit'), async () => {
      try {
        const saved = await api('POST', '/api/credentials/trade', body);
        status.textContent = 'Enregistrée (credential #' + saved.id + ', ' + saved.provider + ' ' + saved.scope + ').';
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
  onProviderChange();
  core.onState(showMasterWarning);
  core.register('credentials', { load: () => run(null, load), show: () => run(null, load) });
})();
