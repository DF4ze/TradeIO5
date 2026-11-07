/* Onglet Diagnostic : GET /api/admin/doctor (rapport), /alarms, /jobs ; POST /run, /alarms/{id}/ack|snooze. Observateur uniquement. */
(function () {
  'use strict';
  const core = window.AdminCore;
  if (!core) { return; }
  const { el, api, run, byId } = core;

  const JOBS_PAGE_SIZE = 15;
  const REFRESH_MS = 60000;
  const SNOOZE_HOURS = [1, 24, 168];
  let jobsPage = 0;
  let timer = null;

  const SEV_CLASS = { OK: 'success', WARN: 'warning', CRIT: 'danger', UNKNOWN: 'secondary' };
  const sev = (s) => el('span', { class: 'badge text-bg-' + (SEV_CLASS[s] || 'secondary'), text: s });
  const JOB_CLASS = { OK: 'success', PARTIAL: 'warning', FAILED: 'danger' };

  function renderBadge(report) {
    const worst = byId('adm-doctor-worst');
    worst.className = 'badge text-bg-' + (SEV_CLASS[report.worst] || 'secondary');
    worst.textContent = report.worst;
    byId('adm-doctor-runat').textContent = report.runAt ? 'Dernière passe : ' + core.fmtDate(report.runAt) + ' UTC · ' + report.activeCrit + ' CRIT, ' + report.activeWarn + ' WARN ouvertes' : 'Aucune passe';
    const tab = byId('adm-doctor-badge');
    const n = report.activeCrit + report.activeWarn;
    tab.classList.toggle('d-none', n === 0);
    tab.className = 'badge text-bg-' + (report.activeCrit > 0 ? 'danger' : 'warning') + (n === 0 ? ' d-none' : '');
    tab.textContent = n;
  }

  function renderChecks(report) {
    const families = new Map();
    (report.results || []).forEach((r) => {
      if (!families.has(r.family)) { families.set(r.family, []); }
      families.get(r.family).push(r);
    });
    const box = byId('adm-doctor-checks');
    if (!families.size) { box.replaceChildren(core.empty('Aucun résultat (première passe en attente).')); return; }
    box.replaceChildren(...[...families.entries()].map(([fam, rows]) => el('div', null,
      el('div', { class: 'dr-fam fw-bold text-uppercase text-muted', text: fam }),
      el('div', { class: 'dr-checks' }, rows.map((r) => el('div', { class: 'dr-check' },
        el('div', { class: 'd-flex align-items-center gap-2' }, sev(r.status),
          el('span', { class: 'fw-semibold', text: r.label }),
          r.subject && r.subject !== '-' ? el('span', { class: 'text-muted small', text: r.subject }) : null),
        r.message ? el('div', { class: 'dr-msg text-muted', text: r.message }) : null))))));
  }

  async function act(button, path, body) {
    await run(button, async () => { await api('POST', path, body); await load(); });
  }

  function alarmActions(a) {
    if (a.state === 'RESOLVED') { return null; }
    const wrap = el('div', { class: 'dr-actions' });
    if (a.state === 'ACTIVE') {
      const ack = el('button', { type: 'button', class: 'btn btn-outline-primary', text: 'Acquitter' });
      ack.addEventListener('click', () => {
        const comment = window.prompt('Commentaire (facultatif)', '');
        if (comment === null) { return; }
        act(ack, '/api/admin/doctor/alarms/' + a.id + '/ack', { comment });
      });
      wrap.append(ack);
    }
    wrap.append(el('span', { class: 'text-muted small ms-1', text: 'Reporter' }));
    SNOOZE_HOURS.forEach((h) => {
      const b = el('button', { type: 'button', class: 'btn btn-outline-secondary', text: h < 24 ? h + ' h' : (h / 24) + ' j' });
      b.addEventListener('click', () => act(b, '/api/admin/doctor/alarms/' + a.id + '/snooze', { hours: h, comment: '' }));
      wrap.append(b);
    });
    return wrap;
  }

  function alarmCard(a) {
    const range = core.fmtDate(a.firstSeen) + ' → ' + core.fmtDate(a.lastSeen) + ' UTC';
    let state = a.state === 'ACKED' ? 'Acquittée' : (a.state === 'RESOLVED' ? 'Résolue' : 'Active');
    if (a.state === 'ACKED') {
      state += (a.ackedBy ? ' par ' + a.ackedBy : '') + (a.snoozeUntil ? ' · reportée jusqu\'au ' + core.fmtDate(a.snoozeUntil) : '');
    }
    return el('div', { class: 'dr-alarm sev-' + a.severity + ' st-' + a.state },
      el('div', { class: 'dr-main' },
        el('div', { class: 'd-flex align-items-center gap-2 flex-wrap' }, sev(a.severity),
          el('span', { class: 'dr-title', text: a.checkId + ' · ' + a.subject }),
          el('span', { class: 'badge text-bg-light border', text: state })),
        el('div', { class: 'dr-msg', text: a.message || '' }),
        el('div', { class: 'dr-meta', text: range + ' · ' + a.occurrences + ' occurrence(s)' + (a.ackComment ? ' · « ' + a.ackComment + ' »' : '') })),
      alarmActions(a));
  }

  async function loadAlarms() {
    const all = byId('adm-doctor-showresolved').checked;
    const list = await api('GET', '/api/admin/doctor/alarms' + (all ? '?state=ALL' : ''));
    byId('adm-doctor-alarms').replaceChildren(...(list.length ? list.map(alarmCard)
      : [core.empty('Aucune alarme ouverte.')]));
  }

  async function loadJobs() {
    const d = await api('GET', '/api/admin/doctor/jobs?page=' + jobsPage + '&size=' + JOBS_PAGE_SIZE);
    byId('adm-doctor-jobs').replaceChildren(...(d.items.length ? d.items.map((j) => el('tr', null,
      el('td', { text: core.fmtDate(j.startedAt) }), el('td', { text: j.jobName }), el('td', { text: j.trigger }),
      el('td', null, el('span', { class: 'badge text-bg-' + (JOB_CLASS[j.status] || 'secondary'), text: j.status })),
      el('td', { text: j.durationMs + ' ms' }), el('td', { text: j.errors }),
      el('td', { class: 'small', text: j.errorMessage || j.summary || '' })))
      : [el('tr', null, el('td', { colspan: 7 }, core.empty('Aucun job journalisé.')))]));
    const pages = Math.max(1, Math.ceil(d.total / d.size));
    byId('adm-doctor-jobs-page').textContent = 'Page ' + (d.page + 1) + ' / ' + pages;
    byId('adm-doctor-jobs-prev').disabled = d.page <= 0;
    byId('adm-doctor-jobs-next').disabled = d.page + 1 >= pages;
  }

  async function load() {
    const report = await api('GET', '/api/admin/doctor');
    renderBadge(report);
    renderChecks(report);
    await Promise.all([loadAlarms(), loadJobs()]);
  }

  const reload = () => run(null, load);

  byId('adm-doctor-run').addEventListener('click', (e) => run(e.currentTarget, async () => {
    const report = await api('POST', '/api/admin/doctor/run');
    renderBadge(report);
    renderChecks(report);
    await Promise.all([loadAlarms(), loadJobs()]);
  }));
  byId('adm-doctor-showresolved').addEventListener('change', () => run(null, loadAlarms));
  byId('adm-doctor-jobs-prev').addEventListener('click', () => { jobsPage = Math.max(0, jobsPage - 1); run(null, loadJobs); });
  byId('adm-doctor-jobs-next').addEventListener('click', () => { jobsPage += 1; run(null, loadJobs); });

  /* Badge d'onglet maintenu à jour même quand l'onglet n'est pas ouvert. */
  async function pollBadge() {
    try { renderBadge(await api('GET', '/api/admin/doctor')); } catch (e) { /* silencieux */ }
  }
  timer = setInterval(pollBadge, REFRESH_MS);
  pollBadge();

  core.register('doctor', { load: reload, show: reload });
})();
