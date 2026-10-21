/* Onglets de /admin sans Bootstrap : active le bouton et le panneau, puis émet 'shown.bs.tab' (contrat attendu par admin-core.js). */
(function () {
  'use strict';
  const buttons = Array.from(document.querySelectorAll('[data-adm-tab]'));
  function show(btn) {
    buttons.forEach((b) => { const on = b === btn; b.classList.toggle('active', on); b.setAttribute('aria-selected', String(on)); });
    document.querySelectorAll('.tab-pane').forEach((p) => p.classList.toggle('active', '#' + p.id === btn.dataset.bsTarget));
    btn.dispatchEvent(new Event('shown.bs.tab'));
  }
  buttons.forEach((b) => b.addEventListener('click', () => show(b)));
})();
