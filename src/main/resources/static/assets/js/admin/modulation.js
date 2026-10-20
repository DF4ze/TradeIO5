/* Onglet Modulation : GET/PUT /api/admin/rainbow-modulation. Un champ par paramètre (défaut + bornes absolues affichés). */
(function () {
  'use strict';
  const core = window.AdminCore;
  if (!core) { return; }
  const { el, api, run, byId } = core;

  /* [clé API, libellé, aide, pas] — ordre d'affichage. */
  const FIELDS = [
    ['regulationMarginPct', 'Marge de régulation (% de la largeur de bande)', 'Zone où la modulation monte en puissance près des bornes', '1'],
    ['sellBoostMax', 'Boost de vente max (×)', 'Multiplicateur max de la vente près de la borne haute', '0.1'],
    ['biasMax', 'Biais max', 'Amplitude max du biais acheteur/vendeur réglable par l\'utilisateur', '0.05'],
    ['powerMin', 'Puissance min (×)', 'Doit rester ≤ 1', '0.05'],
    ['powerMax', 'Puissance max (×)', 'Doit rester ≥ 1', '0.05'],
    ['minBandWidthPts', 'Largeur mini de bande (pts)', 'Une bande plus étroite est élargie autour de son centre', '1'],
    ['modulationProductMax', 'Produit max des facteurs (×)', 'Plafond du produit bande × puissance × biais, par sens', '0.1'],
    ['autonomyDays', 'Autonomie cible (jours)', 'Sert à proposer le montant de base à l\'onboarding', '1']
  ];

  let params = {};

  function field([key, label, help, step]) {
    const p = params[key] || {};
    const input = el('input', { type: 'number', class: 'form-control', id: 'adm-mod-' + key, step,
      min: p.min, max: p.max, required: true, value: p.value });
    return el('div', { class: 'col-12 col-md-6 col-lg-4' },
      el('label', { class: 'form-label', for: 'adm-mod-' + key, text: label }), input,
      el('div', { class: 'form-text', text: help + ' — défaut ' + p.defaultValue + ', bornes [' + p.min + ' ; ' + p.max + ']' }));
  }

  function render(dto) {
    params = dto.parameters;
    byId('adm-mod-fields').replaceChildren(...FIELDS.map(field));
    byId('adm-mod-meta').textContent = dto.updatedBy ? 'Dernière modification : ' + core.fmtDate(dto.updatedAt) + ' par ' + dto.updatedBy : '';
  }

  async function load() { render(await api('GET', '/api/admin/rainbow-modulation')); }

  function body(useDefaults) {
    const out = {};
    FIELDS.forEach(([key]) => { out[key] = useDefaults ? params[key].defaultValue : Number(byId('adm-mod-' + key).value); });
    return out;
  }

  byId('adm-mod-form').addEventListener('submit', (e) => {
    e.preventDefault();
    run(byId('adm-mod-save'), async () => render(await api('PUT', '/api/admin/rainbow-modulation', body(false))));
  });
  byId('adm-mod-defaults').addEventListener('click', (e) => {
    run(e.currentTarget, async () => render(await api('PUT', '/api/admin/rainbow-modulation', body(true))));
  });

  core.register('modulation', { load: () => run(null, load), show: () => run(null, load) });
})();
