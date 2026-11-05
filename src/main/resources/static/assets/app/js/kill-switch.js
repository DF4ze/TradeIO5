/**
 * Bouton « Kill switch » du menu (admin, toutes pages). Levé : gros bouton rouge qui l'engage en un clic (sans confirmation,
 * c'est le sens sûr). Engagé : simple indicateur. Lecture : GET /api/admin/execution/state ; action : PUT …/kill-switch.
 * Désactivé en mode démo (?demo=1) : la démo ne touche jamais au kill switch.
 */
(() => {
  'use strict';
  const btn = document.getElementById('tio-ks');
  if (!btn || /(^|[?&])demo=1/.test(location.search)) { if (btn) { btn.hidden = true; } return; }
  const label = btn.querySelector('.ks-main');
  const sub = btn.querySelector('.ks-sub');
  const POLL_MS = 10000;
  let engaged = null;

  function render(state, text, subText) {
    btn.dataset.state = state;
    btn.disabled = state !== 'lifted';
    label.textContent = text;
    sub.textContent = subText;
  }

  function apply(killSwitch) {
    engaged = killSwitch;
    if (killSwitch) { render('engaged', 'Kill switch engagé', 'Aucun ordre réel ne part'); }
    else { render('lifted', 'ENGAGER LE KILL SWITCH', 'Levé : ordres réels possibles'); }
    document.dispatchEvent(new CustomEvent('tio:killswitch', { detail: { killSwitch } }));
  }

  async function call(method, path, body) {
    const res = await fetch(path, {
      method, credentials: 'same-origin',
      headers: body === undefined ? { Accept: 'application/json' } : { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    if (!res.ok) { throw new Error('HTTP ' + res.status); }
    return res.json();
  }

  async function refresh() {
    try { apply((await call('GET', '/api/admin/execution/state')).killSwitch); }
    catch (e) { render('unknown', 'Kill switch : état inconnu', 'Lecture impossible'); }
  }

  btn.addEventListener('click', async () => {
    if (engaged !== false) { return; }
    btn.disabled = true;
    try { apply((await call('PUT', '/api/admin/execution/kill-switch', { engaged: true })).killSwitch); }
    catch (e) { render('lifted', 'ÉCHEC — RÉESSAYER', 'Kill switch toujours levé'); }
  });

  /* l'admin (bandeau, console) change aussi l'état : se resynchroniser sans attendre le prochain tour */
  document.addEventListener('tio:killswitch-changed', refresh);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) { refresh(); } });
  refresh();
  setInterval(refresh, POLL_MS);
})();
