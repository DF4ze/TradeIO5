/* Onglet Providers : liste (GET /api/admin/providers), déclaration (POST) et modification (PUT) nom / URL / activation. Aucun secret. */
(function () {
  'use strict';
  const core = window.AdminCore;
  if (!core) { return; }
  const { el, api, run, byId } = core;

  /* Modèle d'URL / nom proposés à la sélection d'un code. */
  const PRESETS = { ONE_TRADING: { name: 'One Trading', url: 'https://api.onetrading.com/fast/v1' },
    BITVAVO: { name: 'Bitvavo', url: 'https://api.bitvavo.com/v2' },
    REVOLUT_X: { name: 'Revolut X', url: 'https://revx.revolut.com/api/1.0' },
    GATE_EU: { name: 'Gate EU', url: 'https://api.gateeu.com/api/v4' } };
  let editing = null;

  function setMode(provider) {
    editing = provider;
    const code = byId('adm-prov-code');
    byId('adm-prov-form-title').textContent = provider ? 'Modifier ' + provider.code : 'Déclarer un provider';
    byId('adm-prov-submit').textContent = provider ? 'Enregistrer' : 'Déclarer';
    byId('adm-prov-cancel').classList.toggle('d-none', !provider);
    code.disabled = !!provider;
    if (provider) {
      code.replaceChildren(el('option', { value: provider.code, text: provider.code }));
      byId('adm-prov-name').value = provider.name;
      byId('adm-prov-url').value = provider.apiBaseUrl || '';
      byId('adm-prov-enabled').checked = provider.enabled;
    }
  }

  function applyPreset() {
    const preset = PRESETS[byId('adm-prov-code').value];
    if (editing || !preset) { return; }
    byId('adm-prov-name').value = preset.name;
    byId('adm-prov-url').value = preset.url;
  }

  function row(p) {
    const edit = el('button', { type: 'button', class: 'btn btn-sm btn-outline-primary me-1', text: 'Modifier' });
    edit.addEventListener('click', () => { setMode(p); byId('adm-prov-name').focus(); });
    const toggle = el('button', { type: 'button', class: 'btn btn-sm btn-outline-secondary',
      text: p.enabled ? 'Désactiver' : 'Activer' });
    toggle.addEventListener('click', () => run(toggle, async () => {
      await api('PUT', '/api/admin/providers/' + p.id, { name: p.name, apiBaseUrl: p.apiBaseUrl, enabled: !p.enabled });
      await load();
    }));
    return el('tr', null,
      el('td', { text: p.id }), el('td', { text: p.code }), el('td', { text: p.name }),
      el('td', { text: p.apiBaseUrl || core.DASH }), el('td', { text: p.enabled ? 'oui' : 'non' }),
      el('td', { text: core.fmtDate(p.createdAt) }), el('td', null, edit, toggle));
  }

  async function load() {
    const data = await api('GET', '/api/admin/providers');
    byId('adm-prov-body').replaceChildren(...(data.providers.length ? data.providers.map(row)
      : [el('tr', null, el('td', { colspan: 7 }, core.empty('Aucun provider déclaré.')))]));
    if (!editing) {
      const select = byId('adm-prov-code');
      select.replaceChildren(...data.available.map((c) => el('option', { value: c, text: c })));
      byId('adm-prov-submit').disabled = data.available.length === 0;
      applyPreset();
    }
  }

  async function submit(event) {
    event.preventDefault();
    const status = byId('adm-prov-status');
    status.textContent = '';
    const body = { name: byId('adm-prov-name').value, apiBaseUrl: byId('adm-prov-url').value,
      enabled: byId('adm-prov-enabled').checked };
    await run(byId('adm-prov-submit'), async () => {
      if (editing) {
        const saved = await api('PUT', '/api/admin/providers/' + editing.id, body);
        status.textContent = saved.code + ' mis à jour.';
        setMode(null);
      } else {
        const saved = await api('POST', '/api/admin/providers', Object.assign({ code: byId('adm-prov-code').value }, body));
        status.textContent = saved.code + ' déclaré : saisir sa clé dans l\'onglet Credentials.';
        byId('adm-prov-name').value = '';
        byId('adm-prov-url').value = '';
      }
      await load();
    });
  }

  byId('adm-prov-form').addEventListener('submit', submit);
  byId('adm-prov-code').addEventListener('change', applyPreset);
  byId('adm-prov-cancel').addEventListener('click', () => { setMode(null); byId('adm-prov-form').reset(); run(null, load); });
  core.register('providers', { load: () => run(null, load), show: () => run(null, load) });
})();
