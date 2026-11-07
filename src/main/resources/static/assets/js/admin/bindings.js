/*
 * Onglet Bindings : supervision de tous les bindings (GET /api/admin/bindings, bouton Armer) + formulaire de création / édition
 * pour n'importe quel utilisateur choisi dans la liste (API /api/admin/bindings/users/{userId}, mêmes règles que côté propriétaire),
 * boutons Éditer / Vérifier / Armer.
 */
(function () {
  'use strict';
  const core = window.AdminCore;
  if (!core) { return; }
  const { el, api, run, byId } = core;

  const forUser = (userId) => '/api/admin/bindings/users/' + userId;
  const local = { rows: [], wallets: [], users: [], editing: null };
  const targetUser = () => byId('adm-bind-user').value;

  const firstLive = (b) => (b.firstLiveArmed && b.firstLiveConfirmed ? 'validé'
    : (b.firstLiveArmed ? 'armé, confirmation attendue' : 'non armé'));

  /* ------------------------------------------------------------------ tableau */

  function row(b) {
    const arm = el('button', { type: 'button', class: 'btn btn-sm btn-outline-warning', text: 'Armer',
      disabled: b.firstLiveArmed });
    arm.addEventListener('click', () => run(arm, async () => {
      await api('POST', '/api/admin/execution/bindings/' + b.id + '/arm');
      await load();
    }));
    const check = el('button', { type: 'button', class: 'btn btn-sm btn-outline-secondary', text: 'Vérifier' });
    check.addEventListener('click', () => run(check, async () => {
      const r = await api('GET', forUser(b.userId) + '/' + b.id + '/check');
      byId('adm-bind-check').textContent = b.assetSymbol + ' (#' + b.id + ') : ' + r.status + ' — ' + r.message;
      await load();
    }));
    const edit = el('button', { type: 'button', class: 'btn btn-sm btn-outline-primary', text: 'Éditer' });
    edit.addEventListener('click', () => run(null, () => startEdit(b)));
    return el('tr', null,
      el('td', { text: b.id }), el('td', { text: b.username }), el('td', { text: b.assetSymbol }),
      el('td', { text: b.exchange || core.DASH }), el('td', { text: b.walletName }), el('td', { text: b.affectationName }),
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
    const list = await api('GET', forUser(targetUser()) + '/candidates?walletId=' + encodeURIComponent(walletId)
      + '&asset=' + encodeURIComponent(asset));
    fillSelect(select, list, (c) => c.name + ' · ' + c.mode + ' · ' + ORIGIN[c.origin]
      + (c.alreadyLinkedBindingId && !(local.editing && local.editing.id === c.alreadyLinkedBindingId)
        ? ' (déjà lié)' : ''), selected);
  }

  function defaultUserId() {
    const me = local.users.find((u) => u.username === core.username);
    return me ? me.id : (local.users[0] ? local.users[0].id : '');
  }

  function renderUsers(selected) {
    const select = byId('adm-bind-user');
    select.replaceChildren(...local.users.map((u) => el('option', { value: u.id, text: u.username + ' (#' + u.id + ')' })));
    select.value = selected != null ? selected : defaultUserId();
  }

  /* Wallets de l'utilisateur ciblé (les liens ne portent que sur ses propres wallets). */
  async function loadWallets(selectedWalletId) {
    const uid = targetUser();
    local.wallets = uid ? await api('GET', forUser(uid) + '/wallets') : [];
    fillSelect(byId('adm-bind-wallet'), local.wallets, (w) => w.name + (w.exchange ? ' (' + w.exchange + ')' : ''), selectedWalletId);
    byId('adm-bind-status').textContent = local.wallets.length ? '' : 'Aucun wallet pour cet utilisateur : il doit en créer un avant de lier un binding.';
  }

  function whoLabel() {
    const u = local.users.find((x) => String(x.id) === String(targetUser()));
    return u ? u.username : '—';
  }

  async function resetForm() {
    local.editing = null;
    byId('adm-bind-form-title').textContent = 'Créer un binding pour ' + whoLabel();
    byId('adm-bind-submit').textContent = 'Créer';
    byId('adm-bind-cancel').classList.add('d-none');
    byId('adm-bind-asset').disabled = false;
    byId('adm-bind-user').disabled = false;
    byId('adm-bind-bag').value = 100;
    byId('adm-bind-prio').value = '';
    byId('adm-bind-enabled').checked = false;
    await renderPresets(null);
  }

  async function startEdit(b) {
    local.editing = b;
    renderUsers(b.userId);
    byId('adm-bind-user').disabled = true;
    await loadWallets(b.walletId);
    byId('adm-bind-form-title').textContent = 'Modifier le binding ' + b.assetSymbol + ' (#' + b.id + ') de ' + b.username;
    byId('adm-bind-submit').textContent = 'Enregistrer';
    byId('adm-bind-cancel').classList.remove('d-none');
    byId('adm-bind-asset').value = b.assetSymbol;
    byId('adm-bind-asset').disabled = true;
    byId('adm-bind-wallet').value = b.walletId;
    await renderPresets(b.affectationId);
    byId('adm-bind-bag').value = b.bagPercent;
    byId('adm-bind-prio').value = b.priority;
    byId('adm-bind-enabled').checked = b.executionEnabled;
  }

  async function submit(event) {
    event.preventDefault();
    const status = byId('adm-bind-status');
    status.textContent = '';
    const affectationId = Number(byId('adm-bind-preset').value);
    const walletId = Number(byId('adm-bind-wallet').value);
    const bag = byId('adm-bind-bag').value;
    const prio = byId('adm-bind-prio').value;
    const wantEnabled = byId('adm-bind-enabled').checked;
    await run(byId('adm-bind-submit'), async () => {
      const body = { affectationId, walletId, bagPercent: bag === '' ? null : Number(bag), priority: prio === '' ? null : Number(prio) };
      const uid = local.editing ? local.editing.userId : targetUser();
      const saved = local.editing
        ? await api('PUT', forUser(uid) + '/' + local.editing.id, body)
        : await api('POST', forUser(uid), Object.assign({ assetSymbol: byId('adm-bind-asset').value }, body));
      const id = saved.binding.id;
      if (saved.binding.executionEnabled !== wantEnabled) {
        await api('PUT', forUser(uid) + '/' + id + '/execution', { enabled: wantEnabled });
      }
      status.textContent = 'Binding #' + id + ' enregistré — vérification : ' + saved.check.status + ' — ' + saved.check.message;
      await resetForm();
      await load();
    });
  }

  /* ------------------------------------------------------------------ chargement */

  async function load() {
    const [rows, users] = await Promise.all([api('GET', '/api/admin/bindings'), api('GET', '/api/admin/users')]);
    local.rows = rows;
    local.users = users;
    renderTable();
    if (local.editing) {
      const fresh = rows.find((r) => r.id === local.editing.id);
      if (fresh) { await startEdit(fresh); return; }
      local.editing = null;
    }
    renderUsers(byId('adm-bind-user').value || null);
    await loadWallets(null);
    await resetForm();
  }

  byId('adm-bind-form').addEventListener('submit', submit);
  byId('adm-bind-asset').addEventListener('change', () => run(null, () => renderPresets(null)));
  byId('adm-bind-wallet').addEventListener('change', () => run(null, () => renderPresets(null)));
  byId('adm-bind-cancel').addEventListener('click', () => run(null, async () => { renderUsers(null); await loadWallets(null); await resetForm(); }));
  byId('adm-bind-user').addEventListener('change', () => run(null, async () => { await loadWallets(null); await resetForm(); }));
  core.register('bindings', { load: () => run(null, load), show: () => run(null, load) });
})();
