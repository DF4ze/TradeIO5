/**
 * Compteur d'alarmes Docteur sur l'entrée « Administration » du menu (admin, toutes pages).
 * Lecture : GET /api/admin/doctor, champ menuCount = alarmes ouvertes non acquittées (activeCrit + activeWarn) où chaque proposition de nouvel actif à
 * valider compte pour 1 point (son alarme n'est pas comptée en plus ; la proposition reste comptée une fois l'alarme acquittée). Rouge si CRIT, sinon orange.
 * Masqué à 0 et en mode démo (?demo=1).
 */
(() => {
  'use strict';
  const badge = document.getElementById('tio-admin-count');
  if (!badge || /(^|[?&])demo=1/.test(location.search)) { return; }
  const POLL_MS = 60000;

  async function refresh() {
    try {
      const res = await fetch('/api/admin/doctor', { credentials: 'same-origin', headers: { Accept: 'application/json' } });
      if (!res.ok) { return; }
      const r = await res.json();
      const n = r.menuCount != null ? r.menuCount : (r.activeCrit || 0) + (r.activeWarn || 0);
      badge.hidden = n === 0;
      badge.dataset.sev = r.activeCrit > 0 ? 'crit' : 'warn';
      badge.textContent = n > 99 ? '99+' : String(n);
      badge.setAttribute('aria-label', n + ' alarme(s) ou actif(s) à valider');
    } catch (e) { /* silencieux */ }
  }

  document.addEventListener('visibilitychange', () => { if (!document.hidden) { refresh(); } });
  refresh();
  setInterval(refresh, POLL_MS);
})();
