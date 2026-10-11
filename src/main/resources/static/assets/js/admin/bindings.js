/*
 * Onglet Bindings : supervision de tous les bindings (GET /api/admin/bindings, bouton Armer) + formulaire du propriétaire
 * pour ses propres bindings (API /api/rainbow-live/bindings, scopée à l'utilisateur connecté), boutons Vérifier / Armer.
 */
(function () {
  'use strict';
  const core = window.AdminCore;
  if (!core) { return; }
  const { el, api, run, byId } = core;

  const BINDINGS = '/api/rainbow-live/bindings';
  const local = { rows: [], wallets: [], editing: null };

  const firstLive = (b) => (b.firstLiveArmed && b.firstLiveConfirmed ? 'validé'
    : (b.firstLiveArmed ? 'armé, confirmation attendue' : 'non armé'));
  const mine = (b) => b.username === core.username;

  /* ------------------------------------------------------------------ tableau */

  function row(b) {
    const arm = el('button', { type: 'button', class: 'btn btn-sm btn-outline-warning', text: 'Armer',
      disabled: b.firstLiveArmed });
    arm.addEventListener('click', () => run(arm, async () => {
      await api('POST', '/api/admin/execution/bindings/' + b.id + '/arm');
      await load();
    }));
    const check = el('button', { type: 'button', class: 'btn btn-sm btn-outline-secondary', text: 'Vérifier',
      disabled: !mine(b), title: mine(b) ? null : 'Seul le propriétaire peut revérifier son binding' });
    check.addEventListener('click', () => run(check, async () => {
      const r = await api('GET', BINDINGS + '/' + b.id + '/check');
      byId('adm-bind-check').textContent = b.assetSymbol + ' (#' + b.id + ') : ' + r.status + ' — ' + r.message;
      await load();
    }));
    const edit = el('button', { type: 'button', class: 'btn btn-sm btn-outline-primary', text: 'Éditer', disabled: !mine(b) });
    edit.addEventListener('click', () => startEdit(b));
    return el('tr', null,
      el('td', { text: b.id }), el('td', { text: b.username }), el('td', { text: b.assetSymbol }),
      el('td', { text: b.exchange || core.DASH }), el('td', { text: b.walletName }), el('td', { text: b.presetName }),
      el('td', { text: b.bagPercent }), el('td', { text: b.priority }),
      el('td', { text: b.executionEnabled ? 'activée' : 'éteinte' }), el('td', { text: b.tradability || core.DASH }),
      el('td', { text: firstLive(b) }),
      el('td', null, el('div', { class: 'd-flex gap-1' }, edit, check, arm)));
  }

  function renderTable() {
    byId('adm-bind-body').replaceChildren(...(local.rows.length ? local.rows.map(row)
      : [el('tr', null, el('td', { colspan: 12 }, core.empty('Aucun binding — en créer un avec le formulaire ci-dessous.')))]));
  }

  /* ------------------------------------------------------------------ formulaire propriétaire */

  function fillSelect(select, items, label, selected) {
    select.replaceChildren(...items.map((i) => el('option', { value: i.id, text: label(i), selected: String(i.id) === String(selected) })));
  }

  const ORIGIN = { SYSTEM: 'système', CUSTOM: 'custom' };

  /* Presets proposés = candidats de l'actif pour le wallet choisi (stratégie · origine). */
  async function renderPresets(selected) {
    const asset = byId('adm-bind-asset').value;
    const walletId = byId('adm-bind-wallet').value;
    const select = byId('adm-bind-preset');
    if (!asset || !walletId) { select.replaceChildren(); return; }
    const list = await api('GET', BINDINGS + '/candidates?walletId=' + encodeURIComponent(walletId)
      + '&asset=' + encodeURIComponent(asset));
    fillSelect(select, list, (c) => c.name + ' · ' + c.mode + ' · ' + ORIGIN[c.origin]
      + (c.alreadyLinkedBindingId && !(local.editing && local.editing.id === c.alreadyLinkedBindingId)
        ? ' (déjà lié)' : ''), selected);
  }

  function resetForm() {
    local.editing = null;
    byId('adm-bind-form-title').textContent = 'Mon binding';
    byId('adm-bind-submit').textContent = 'Créer';
    byId('adm-bind-cancel').classList.add('d-none');
    byId('adm-bind-asset').disabled = false;
    byId('adm-bind-bag').value = 100;
    byId('adm-bind-prio').value = '';
    byId('adm-bind-enabled').checked = false;
    run(null, () => renderPresets(null));
  }

  function startEdit(b) {
    local.editing = b;
    byId('adm-bind-form-title').textContent = 'Modifier mon binding ' + b.assetSymbol + ' (#' + b.id + ')';
    byId('adm-bind-submit').textContent = 'Enregistrer';
    byId('adm-bind-cancel').classList.remove('d-none');
    byId('adm-bind-asset').value = b.assetSymbol;
    byId('adm-bind-asset').disabled = true;
    byId('adm-bind-wallet').value = b.walletId;
    run(null, () => renderPresets(b.presetId));
    byId('adm-bind-bag').value = b.bagPercent;
    byId('adm-bind-prio').value = b.priority;
    byId('adm-bind-enabled').checked = b.executionEnabled;
  }

  async function submit(event) {
    event.preventDefault();
    const status = byId('adm-bind-status');
    status.textContent = '';
    const presetId = Number(byId('adm-bind-preset').value);
    const walletId = Number(byId('adm-bind-wallet').value);
    const bag = byId('adm-bind-bag').value;
    const prio = byId('adm-bind-prio').value;
    const wantEnabled = byId('adm-bind-enabled').checked;
    await run(byId('adm-bind-submit'), async () => {
      const body = { presetId, walletId, bagPercent: bag === '' ? null : Number(bag), priority: prio === '' ? null : Number(prio) };
      const saved = local.editing
        ? await api('PUT', BINDINGS + '/' + local.editing.id, body)
        : await api('POST', BINDINGS, Object.assign({ assetSymbol: byId('adm-bind-asset').value }, body));
      const id = saved.binding.id;
      if (saved.binding.executionEnabled !== wantEnabled) {
        await api('PUT', BINDINGS + '/' + id + '/execution', { enabled: wantEnabled });
      }
      status.textContent = 'Binding #' + id + ' enregistré — vérification : ' + saved.check.status + ' — ' + saved.check.message;
      resetForm();
      await load();
    });
  }

  /* ------------------------------------------------------------------ chargement */

  async function load() {
    const [rows, wallets] = await Promise.all([api('GET', '/api/admin/bindings'),
      api('GET', '/api/rainbow-live/wallets')]);
    local.rows = rows;
    local.wallets = wallets;
    renderTable();
    fillSelect(byId('adm-bind-wallet'), wallets, (w) => w.name + (w.exchange ? ' (' + w.exchange + ')' : ''), null);
    if (!wallets.length) { byId('adm-bind-status').textContent = 'Aucun wallet : en créer un avant de lier un binding.'; }
    if (local.editing) {
      const fresh = rows.find((r) => r.id === local.editing.id);
      if (fresh) { startEdit(fresh); } else { resetForm(); }
    } else {
      await renderPresets(null);
    }
  }

  byId('adm-bind-form').addEventListener('submit', submit);
  byId('adm-bind-asset').addEventListener('change', () => run(null, () => renderPresets(null)));
  byId('adm-bind-wallet').addEventListener('change', () => run(null, () => renderPresets(null)));
  byId('adm-bind-cancel').addEventListener('click', resetForm);
  core.register('bindings', { load: () => run(null, load), show: () => run(null, load) });
})();
