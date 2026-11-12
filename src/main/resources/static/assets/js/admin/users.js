/*
 * Onglet Utilisateurs : création (POST /api/admin/users, mot de passe généré affiché une seule fois), statut d'accès VPS
 * (GET /api/admin/users/access), clé publique, réessayer, package client (zip), (dés)activation, suppression, réconciliation.
 * Aucun secret conservé côté page au-delà de l'affichage unique du mot de passe généré.
 */
(function () {
  'use strict';
  const core = window.AdminCore;
  if (!core) { return; }
  const { el, api, run, byId } = core;

  const BASE = '/api/admin/users';
  const STATUS = {
    NONE: ['secondary', 'aucun'],
    PENDING_KEY: ['warning', 'clé attendue'],
    ACTIVE: ['success', 'actif'],
    DISABLED: ['dark', 'désactivé'],
    ERROR: ['danger', 'erreur']
  };
  const badge = (status) => {
    const [cls, label] = STATUS[status] || ['secondary', status];
    return el('span', { class: 'badge text-bg-' + cls, text: label });
  };

  /* ------------------------------------------------------------------ dialogues */

  /** Modale de saisie de la clé publique (textarea) ; résout la clé ou null. */
  function askKey(username) {
    return new Promise((resolve) => {
      const area = el('textarea', { class: 'form-control font-monospace small', rows: 4, placeholder: 'ssh-ed25519 AAAA… [commentaire]', autocomplete: 'off' });
      const ok = el('button', { type: 'button', class: 'btn btn-primary', text: 'Créer le compte VPS' });
      const cancel = el('button', { type: 'button', class: 'btn btn-outline-secondary', text: 'Annuler' });
      const dlg = el('dialog', { class: 'p-3 border rounded' },
        el('h6', { text: 'Clé publique de ' + username }),
        el('div', { class: 'small text-muted mb-2', text: 'Une seule ligne ssh-ed25519, telle que affichée par le script client de l\'ami.' }),
        area, el('div', { class: 'd-flex gap-2 mt-3' }, ok, cancel));
      const done = (v) => { dlg.close(); dlg.remove(); resolve(v); };
      ok.addEventListener('click', () => done(area.value.trim()));
      cancel.addEventListener('click', () => done(null));
      dlg.addEventListener('cancel', () => { dlg.remove(); resolve(null); });
      document.body.append(dlg);
      dlg.showModal();
      area.focus();
    });
  }

  /** Téléchargement authentifié (cookie) : les erreurs {"error"} s'affichent dans le bandeau au lieu d'ouvrir du JSON. */
  async function download(path, fallbackName) {
    const res = await fetch(path, { credentials: 'same-origin' });
    if (!res.ok) {
      let msg = 'Erreur HTTP ' + res.status;
      try { const d = await res.json(); if (d && d.error) { msg = d.error; } } catch (e) { /* corps non JSON */ }
      throw new Error(msg);
    }
    const match = /filename="?([^";]+)"?/.exec(res.headers.get('Content-Disposition') || '');
    const url = URL.createObjectURL(await res.blob());
    const a = el('a', { href: url, download: match ? match[1] : fallbackName });
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  }

  /* ------------------------------------------------------------------ tableau */

  function actions(u) {
    const box = el('div', { class: 'd-flex flex-wrap gap-1' });
    const protectedAccount = u.username === core.username || u.username === 'System';
    const add = (label, cls, fn) => {
      const b = el('button', { type: 'button', class: 'btn btn-sm btn-outline-' + cls, text: label });
      b.addEventListener('click', () => run(b, fn));
      box.append(b);
    };
    if (u.sshStatus === 'NONE' || u.sshStatus === 'PENDING_KEY' || u.sshStatus === 'ERROR') {
      add(u.sshStatus === 'NONE' ? 'Donner l\'accès VPS' : 'Coller la clé', 'primary', async () => {
        const key = await askKey(u.username);
        if (key) { await api('POST', BASE + '/' + u.id + '/ssh-key', { publicKey: key }); await load(); }
      });
    }
    if (u.sshStatus === 'ERROR') {
      add('Réessayer', 'warning', async () => { await api('POST', BASE + '/' + u.id + '/ssh/sync'); await load(); });
    }
    if (u.sshStatus !== 'NONE') {
      add('Package client', 'secondary', () => download(BASE + '/' + u.id + '/ssh/client-package', 'tradeio-client.zip'));
    }
    if (!protectedAccount) {
      add(u.enabled ? 'Désactiver' : 'Activer', u.enabled ? 'warning' : 'success', async () => {
        await api('PUT', BASE + '/' + u.id + '/enabled', { enabled: !u.enabled });
        await load();
      });
      add('Supprimer', 'danger', async () => {
        const ok = await core.askConfirm('Supprimer ' + u.username,
          ['Supprime le compte TradeIO et ses données, et le compte VPS (tio_' + u.username + ') s\'il existe.', 'Irréversible.'],
          u.username, 'Supprimer');
        if (ok) { await api('DELETE', BASE + '/' + u.id); await load(); }
      });
    }
    return box;
  }

  function row(u) {
    return el('tr', null,
      el('td', { text: u.id }), el('td', { text: u.username }),
      el('td', { text: u.enabled ? 'oui' : 'non' }),
      el('td', null, badge(u.sshStatus)),
      el('td', { class: 'small', text: u.sshMessage || core.DASH }),
      el('td', { class: 'small', text: core.fmtDate(u.sshUpdatedAt) }),
      el('td', null, actions(u)));
  }

  function render(rows) {
    const head = ['#', 'Utilisateur', 'Actif', 'Accès VPS', 'Message', 'Mis à jour', 'Actions'];
    byId('adm-usr-table').replaceChildren(el('table', { class: 'table table-sm align-middle' },
      el('thead', null, el('tr', null, head.map((h) => el('th', { text: h })))),
      el('tbody', null, rows.map(row))));
  }

  async function load() { render(await api('GET', BASE + '/access')); }

  /* ------------------------------------------------------------------ création */

  function showCreated(c) {
    const box = byId('adm-usr-created');
    const pwd = el('code', { text: c.generatedPassword });
    const lines = [
      el('div', null, 'Compte créé : ', el('strong', { text: c.username }), ' — mot de passe : ', pwd,
        ' (affiché une seule fois, à transmettre à l\'ami).')
    ];
    if (c.sshStatus === 'PENDING_KEY') {
      lines.push(el('div', { class: 'small mt-1', text: 'Étapes VPS : 1) télécharger le « Package client » de la ligne ci-dessous et l\'envoyer à l\'ami ; '
        + '2) il lance le script, qui affiche sa clé publique ; 3) la coller ici avec « Coller la clé » ; 4) il relance le script : tunnel + navigateur.' }));
    }
    box.replaceChildren(...lines);
    box.classList.remove('d-none');
  }

  byId('adm-usr-form').addEventListener('submit', (e) => {
    e.preventDefault();
    const input = byId('adm-usr-name');
    run(byId('adm-usr-create'), async () => {
      const created = await api('POST', BASE, { username: input.value.trim(), sshAccess: byId('adm-usr-ssh').checked });
      input.value = '';
      showCreated(created);
      await load();
    });
  });

  /* ------------------------------------------------------------------ réconciliation */

  const list = (title, items) => el('div', null, el('strong', { text: title + ' : ' }), items.length ? items.join(', ') : 'aucun');

  byId('adm-usr-reconcile').addEventListener('click', (e) => {
    run(e.currentTarget, async () => {
      const r = await api('GET', BASE + '/ssh/reconcile');
      const box = byId('adm-usr-reco');
      box.replaceChildren(
        list('Comptes VPS orphelins (sans utilisateur TradeIO)', r.orphanVpsAccounts),
        list('Utilisateurs sans compte VPS', r.usersMissingOnVps),
        list('Statuts divergents', r.statusMismatches),
        list('Clé en attente', r.pendingKey));
      box.classList.remove('d-none');
    });
  });

  core.register('users', { load: () => run(null, load), show: () => run(null, load) });
})();
