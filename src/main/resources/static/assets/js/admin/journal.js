/* Onglet Journal : liste paginée en lecture seule de GET /api/admin/actions. */
(function () {
  'use strict';
  const core = window.AdminCore;
  if (!core) { return; }
  const { el, api, run, byId } = core;

  const PAGE_SIZE = 20;
  let page = 0;
  let total = 0;

  function row(a) {
    return el('tr', null,
      el('td', { text: core.fmtDate(a.createdAt) }),
      el('td', { text: a.actor }),
      el('td', { text: a.action }),
      el('td', { text: a.target || core.DASH }),
      el('td', null, el('span', { class: 'badge text-bg-' + (a.outcome === 'OK' ? 'success' : (a.outcome === 'REFUSED' ? 'warning' : 'danger')), text: a.outcome })),
      el('td', null, el('code', { class: 'small', text: a.payload || '' })));
  }

  async function load() {
    const data = await api('GET', '/api/admin/actions?page=' + page + '&size=' + PAGE_SIZE);
    total = data.totalElements;
    const body = byId('adm-journal-body');
    body.replaceChildren(...(data.items.length ? data.items.map(row)
      : [el('tr', null, el('td', { colspan: 6 }, core.empty('Aucune action enregistrée.')))]));
    const pages = Math.max(1, Math.ceil(total / data.size));
    byId('adm-journal-page').textContent = 'Page ' + (data.page + 1) + ' / ' + pages + ' · ' + total + ' action(s)';
    byId('adm-journal-prev').disabled = data.page <= 0;
    byId('adm-journal-next').disabled = data.page + 1 >= pages;
  }

  const reload = () => run(null, load);

  byId('adm-journal-prev').addEventListener('click', () => { page = Math.max(0, page - 1); reload(); });
  byId('adm-journal-next').addEventListener('click', () => { page += 1; reload(); });
  byId('adm-journal-reload').addEventListener('click', reload);

  core.register('journal', { load: reload, show: reload });
})();
