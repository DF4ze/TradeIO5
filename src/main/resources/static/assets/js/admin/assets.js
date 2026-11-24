/* Onglet Actifs : GET /api/admin/assets (vue par actif), /blocklist (GET, POST, DELETE /{actif}), /settings (GET, PUT), recalcul
   POST /api/admin/new-asset-proposals/recompute?asset=… (calcul asynchrone, plusieurs minutes). Propositions de nouvel actif (section « Actifs à valider ») :
   GET /api/admin/new-asset-proposals, POST /{id}/validate|refuse ; la pastille de l'onglet compte les propositions PROPOSE (polling, onglet fermé compris).
   Tout texte serveur passe par textContent. */
(function () {
  'use strict';
  const core = window.AdminCore;
  if (!core) { return; }
  const { el, api, run, byId, fmtDate } = core;

  const CALIBRATION = { CALIBRE: ['calibré', 'text-bg-success'], EN_ATTENTE_VALIDATION: ['en attente de validation', 'text-bg-warning'] };
  const PROPOSAL = { PROPOSE: 'proposition à valider', REFUSE: 'proposition refusée', VALIDE: 'proposition validée' };
  const UNVERIFIED = 'volume non vérifiable';
  const REFRESH_MS = 60000;

  const fmtUsd = (v) => Number(v).toLocaleString('fr-FR', { maximumFractionDigits: 0 }) + ' $';
  const badge = (text, cls) => el('span', { class: 'badge ' + cls, text });

  /** Volume par provider ; volume nul = « volume non vérifiable » (éligible, non vérifié). */
  function volumes(a) {
    if (!a.volumes.length) { return el('span', { class: 'text-muted', text: core.DASH }); }
    return el('div', null, a.volumes.map((v) => el('div', { class: 'small' },
      el('strong', { text: v.provider + ' : ' }),
      v.verified ? fmtUsd(v.volumeUsd) : badge(UNVERIFIED, 'text-bg-secondary'))));
  }

  function calibration(a) {
    const [text, cls] = CALIBRATION[a.calibration] || [a.calibration, 'text-bg-secondary'];
    return el('div', { class: 'adm-asset-flags' }, badge(text, cls), a.proposalStatus ? badge(PROPOSAL[a.proposalStatus] || a.proposalStatus, 'text-bg-info') : null);
  }

  async function recompute(btn, asset, enabled) {
    if (!enabled) { return; }
    const lines = ['Relance le calcul de la proposition de ' + asset + ' (plusieurs minutes, en arrière-plan).',
      'La ligne de taux déjà validée n\'est PAS modifiée tant que le nouveau résultat n\'est pas validé.'];
    if (!await core.askConfirm('Recalculer ' + asset, lines, 'OK', 'Recalculer')) { return; }
    await run(btn, async () => {
      const res = await api('POST', '/api/admin/new-asset-proposals/recompute?asset=' + encodeURIComponent(asset));
      core.showAlert(res.message);
      await load();
    });
  }

  function row(a, recomputeEnabled) {
    const recomputeBtn = el('button', { type: 'button', class: 'btn btn-sm btn-outline-primary', text: a.recomputeRunning ? 'Calcul en cours…' : 'Recalculer',
      disabled: !recomputeEnabled || a.recomputeRunning || a.linkCount === 0,
      title: a.linkCount === 0 ? 'Aucun lien actif : rien à recalculer' : '', onclick: (e) => recompute(e.currentTarget, a.asset, recomputeEnabled) });
    return el('tr', null,
      el('td', null, el('strong', { text: a.asset })),
      el('td', { text: a.linkCount }), el('td', { text: a.userCount }),
      el('td', null, calibration(a)),
      el('td', null, a.blocked ? el('div', null, badge('oui', 'text-bg-danger'), a.blockReason ? el('div', { class: 'small text-muted', text: a.blockReason }) : null) : 'non'),
      el('td', null, volumes(a)),
      el('td', null, a.suspendedLinks > 0 ? badge(String(a.suspendedLinks), 'text-bg-danger') : '0'),
      el('td', null, el('div', { class: 'adm-asset-actions' }, recomputeBtn)));
  }

  /* ---- Actifs à valider (D1b) : propositions PROPOSE ; Valider / Refuser avec confirmation ; « Recalculer » = bouton de la vue par actif (un seul). */
  /* Base indicative en $ par jour pour 1 000 $ de cash (taux × 1000), jamais le taux brut. */
  const fmtRate = (v) => (Number(v) * 1000).toFixed(4);

  /** Pastille de l'onglet : nombre de propositions à valider, masquée à 0. */
  function renderPendingBadge(count) {
    const tab = byId('adm-assets-badge');
    tab.textContent = count;
    tab.classList.toggle('d-none', count === 0);
  }

  function proposalCard(p) {
    const line = (k, v) => el('div', { class: 'small' }, el('strong', { text: k + ' ' }), v == null || v === '' ? '—' : String(v));
    const rows = (p.rates || []).map((r) => el('tr', null, el('td', { text: r.profile }), el('td', { text: r.capPct + ' %' }),
      el('td', { text: '×' + Number(r.multiplier).toFixed(2) }), el('td', { text: fmtRate(r.baseRate) }), el('td', { text: r.status })));
    const rates = rows.length
      ? el('table', { class: 'table table-sm w-auto mb-2' }, el('thead', null, el('tr', null, ['Profil', 'Plafond', 'Multiplicateur', 'Base $/jour pour 1 000 $ de cash', 'Statut'].map((h) => el('th', { text: h })))), el('tbody', null, ...rows))
      : el('div', { class: 'small text-muted mb-2', text: 'Aucun taux calculé.' });
    const pending = p.status === 'PROPOSE';
    const buttons = pending ? el('div', { class: 'd-flex gap-2 mt-2' },
      el('button', { type: 'button', class: 'btn btn-sm btn-primary', text: 'Valider', disabled: !p.canValidate, title: p.validateBlockedReason || '',
        onclick: (e) => decide(e.currentTarget, p, 'validate') }),
      el('button', { type: 'button', class: 'btn btn-sm btn-outline-danger', text: 'Refuser', onclick: (e) => decide(e.currentTarget, p, 'refuse') })) : null;
    return el('div', { class: 'card card-body mb-2', id: 'adm-nap-' + p.id },
      el('div', { class: 'fw-bold', text: p.asset + ' · ' + p.status + (p.decidedBy ? ' par ' + p.decidedBy : '') }),
      p.nonTradable ? el('div', { class: 'alert alert-warning small py-1 my-1', text: 'Actif non tradable chez le provider du lien : ' + (p.warning || '') }) : null,
      line('Preset de départ :', p.presetSource + ' (Bull / Bear + Trend par défaut)'),
      line('Drapeaux :', (p.flags || []).join(', ') || 'aucun'),
      line('Historique :', (p.historyMonths == null ? '—' : p.historyMonths + ' mois') + ' · ' + (p.blockCount == null ? '—' : p.blockCount) + ' bloc(s) de 24 mois · ' + (p.switchCount == null ? '—' : p.switchCount) + ' switch(s)'),
      line('Source des bougies :', (p.historySource || '—') + ' · ' + (p.candleFirstDay || '?') + ' → ' + (p.candleLastDay || '?') + ' (' + (p.candleCount == null ? '?' : p.candleCount) + ')'),
      line('Empreinte des bougies :', p.candleChecksum),
      line('Marge k :', p.marginK),
      p.warning && !p.nonTradable ? line('Note :', p.warning) : null,
      rates,
      el('div', { class: 'alert alert-info small py-1 mb-0', text: p.presetPlanText }),
      p.validateBlockedReason && pending ? el('div', { class: 'small text-muted', text: p.validateBlockedReason }) : null,
      buttons);
  }

  async function decide(btn, p, action) {
    const lines = action === 'validate'
      ? ['Écrire la ligne de taux dédiée de ' + p.asset + ' (lue avant le repli BTC).', p.presetPlanText]
      : ['L\'actif ' + p.asset + ' reste sur le repli BTC « non calibré » ; l\'alarme est close.'];
    if (!await core.askConfirm((action === 'validate' ? 'Valider ' : 'Refuser ') + p.asset, lines, 'OK', action === 'validate' ? 'Valider' : 'Refuser')) { return; }
    await run(btn, async () => { await api('POST', '/api/admin/new-asset-proposals/' + p.id + '/' + action); await load(); });
  }

  async function loadProposals() {
    const list = await api('GET', '/api/admin/new-asset-proposals');
    renderPendingBadge(list.length);
    byId('adm-nap-list').replaceChildren(...(list.length ? list.map(proposalCard) : [el('div', { class: 'small text-muted', text: 'Aucune proposition en attente.' })]));
  }

  /* Pastille maintenue à jour même quand l'onglet n'est pas ouvert (même cadence que le Diagnostic). */
  async function pollBadge() {
    try { renderPendingBadge((await api('GET', '/api/admin/new-asset-proposals')).length); } catch (e) { /* silencieux */ }
  }

  async function loadAssets() {
    const dto = await api('GET', '/api/admin/assets');
    byId('adm-as-disabled').classList.toggle('d-none', dto.recomputeEnabled);
    byId('adm-as-body').replaceChildren(...(dto.assets.length ? dto.assets.map((a) => row(a, dto.recomputeEnabled))
      : [el('tr', null, el('td', { colspan: 8, class: 'text-muted fst-italic', text: 'Aucun actif lié.' }))]));
    byId('adm-as-vol').value = dto.minVolumeUsd;
    byId('adm-as-vol').placeholder = 'défaut ' + dto.defaultMinVolumeUsd;
  }

  async function loadBlocklist() {
    const list = await api('GET', '/api/admin/assets/blocklist');
    byId('adm-as-bl-body').replaceChildren(...(list.length ? list.map((b) => el('tr', null,
      el('td', null, el('strong', { text: b.asset })), el('td', { text: b.reason || core.DASH }), el('td', { text: b.createdBy }), el('td', { text: fmtDate(b.createdAt) }),
      el('td', null, el('button', { type: 'button', class: 'btn btn-sm btn-outline-danger', text: 'Retirer', onclick: (e) => unblock(e.currentTarget, b.asset) }))))
      : [el('tr', null, el('td', { colspan: 5, class: 'text-muted fst-italic', text: 'Liste noire vide.' }))]));
  }

  async function loadSettings() {
    const s = await api('GET', '/api/admin/assets/settings');
    byId('adm-as-vol').value = s.minVolumeUsd;
    byId('adm-as-vol-meta').textContent = 'Défaut ' + fmtUsd(s.defaultMinVolumeUsd) + (s.updatedBy ? ' · modifié ' + fmtDate(s.updatedAt) + ' par ' + s.updatedBy : '');
  }

  async function load() {
    await Promise.all([loadProposals(), loadAssets(), loadBlocklist()]);
    await loadSettings();
  }

  async function unblock(btn, asset) {
    if (!await core.askConfirm('Retirer ' + asset + ' de la liste noire', ['Les liens existants de ' + asset + ' ne sont plus suspendus ; les nouveaux liens sont de nouveau possibles.'], 'OK', 'Retirer')) { return; }
    await run(btn, async () => { await api('DELETE', '/api/admin/assets/blocklist/' + encodeURIComponent(asset)); await load(); });
  }

  byId('adm-as-bl-form').addEventListener('submit', (e) => {
    e.preventDefault();
    const asset = byId('adm-as-bl-asset').value.trim().toUpperCase();
    run(byId('adm-as-bl-add'), async () => {
      if (!await core.askConfirm('Bloquer ' + asset, ['Nouveaux liens refusés ; liens existants suspendus (aucun ordre réel) + alarme du Diagnostic.'], 'OK', 'Bloquer')) { return; }
      await api('POST', '/api/admin/assets/blocklist', { asset, reason: byId('adm-as-bl-reason').value });
      byId('adm-as-bl-asset').value = '';
      byId('adm-as-bl-reason').value = '';
      await load();
    });
  });

  byId('adm-as-vol-form').addEventListener('submit', (e) => {
    e.preventDefault();
    run(byId('adm-as-vol-save'), async () => {
      const value = byId('adm-as-vol').value;
      if (value === '' || Number(value) < 0) { throw new Error('Le volume minimum doit être positif ou nul.'); }
      await api('PUT', '/api/admin/assets/settings', { minVolumeUsd: Number(value) });
      await load();
    });
  });

  setInterval(pollBadge, REFRESH_MS);
  pollBadge();

  core.register('assets', { load: () => run(null, load), show: () => run(null, load) });
})();
