/* Onglet Taux de base : GET /api/admin/base-rates, PUT /{actif} (4 taux), POST /{actif}/defaults, PUT /margin-k.
   Saisie en $ par jour pour 1 000 USDC de cash ; conversion en taux interne (par jour / cash) ici uniquement, le taux brut n'est jamais affiché. */
(function () {
  'use strict';

  const CASH = 1000;
  const PROFILES = [['prudent', 'Prudent'], ['balanced', 'Équilibré'], ['dynamic', 'Dynamique'], ['full', 'Full']];

  /** Taux interne (par jour / cash) -> $ par jour pour 1 000 $ de cash. */
  function toDollars(rate) { return Number((Number(rate) * CASH).toFixed(6)); }
  /** $ par jour pour 1 000 $ de cash -> taux interne, arrondi à l'échelle de la base (8 décimales). */
  function toRate(dollars) { return Number((Number(dollars) / CASH).toFixed(8)); }
  /** % du cash dépensé par jour pour un montant saisi en $ pour 1 000 $. */
  function pctPerDay(dollars) { return Number(dollars) / CASH * 100; }
  /** Jours pour dépenser tout le cash au montant de base (multiplicateur ×1) ; null si le montant n'est pas > 0. */
  function autonomyDays(dollars) { return Number(dollars) > 0 ? Math.round(CASH / Number(dollars)) : null; }

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { toDollars, toRate, pctPerDay, autonomyDays, CASH };
  }
  const core = typeof window !== 'undefined' ? window.AdminCore : null;
  if (!core) { return; }
  const { el, api, run, byId } = core;

  let data = { assets: [], marginK: {}, rateMin: 0, rateMax: 0 };
  let currentK = null;

  const fmtPct = (v) => Number(v).toLocaleString('fr-FR', { maximumFractionDigits: 4 });
  const fmtDays = (d) => (d == null ? '—' : d.toLocaleString('fr-FR'));
  const current = () => data.assets.find((a) => a.asset === byId('adm-br-asset').value);
  const input = (key) => byId('adm-br-in-' + key);

  function refreshRow(key) {
    const dollars = Number(input(key).value);
    byId('adm-br-pct-' + key).textContent = input(key).value === '' ? '—' : fmtPct(pctPerDay(dollars));
    byId('adm-br-days-' + key).textContent = input(key).value === '' ? '—' : fmtDays(autonomyDays(dollars));
  }

  function renderAsset() {
    const a = current();
    if (!a) { return; }
    const banner = byId('adm-br-banner');
    banner.classList.toggle('d-none', a.calibre);
    banner.textContent = a.calibre ? '' : 'Niveau non calibré : valeurs de repli (' + (a.fallbackAsset || 'BTC') + ') pour ' + a.asset
      + '. Enregistrer crée la ligne dédiée de ' + a.asset + '.';
    byId('adm-br-body').replaceChildren(...PROFILES.map(([key, label]) => el('tr', null,
      el('td', { text: label }),
      el('td', null, el('input', { type: 'number', class: 'form-control form-control-sm', id: 'adm-br-in-' + key, step: '0.01',
        min: toDollars(data.rateMin), max: toDollars(data.rateMax), required: true, value: toDollars(a[key]),
        'aria-label': label + ' : $ par jour pour 1 000 $ de cash', oninput: () => refreshRow(key) })),
      el('td', { id: 'adm-br-pct-' + key }), el('td', { id: 'adm-br-days-' + key }))));
    PROFILES.forEach(([key]) => refreshRow(key));
    byId('adm-br-defaults').textContent = 'Remettre les défauts de l\'actif';
    byId('adm-br-meta').textContent = a.dedicated ? 'Ligne dédiée enregistrée.' : (a.calibre ? 'Valeurs de la table de calage.' : 'Pas de ligne dédiée.');
  }

  function render(dto, keepAsset) {
    data = { assets: dto.assets, marginK: dto.marginK, rateMin: dto.rateMin, rateMax: dto.rateMax };
    currentK = dto.marginK.value;
    const select = byId('adm-br-asset');
    const wanted = keepAsset || select.value;
    select.replaceChildren(...data.assets.map((a) => el('option', { value: a.asset, text: a.asset + (a.calibre ? '' : ' (non calibré)') })));
    if (data.assets.some((a) => a.asset === wanted)) { select.value = wanted; }
    const k = byId('adm-br-k');
    k.min = dto.marginK.min; k.max = dto.marginK.max; k.value = dto.marginK.value;
    byId('adm-br-k-range').textContent = 'Défaut ' + dto.marginK.defaultValue + ' · bornes absolues [' + dto.marginK.min + ' ; ' + dto.marginK.max + ']';
    renderAsset();
  }

  async function load(keepAsset) {
    render(await api('GET', '/api/admin/base-rates'), keepAsset);
  }

  function body() {
    const out = {};
    PROFILES.forEach(([key]) => { out[key] = toRate(input(key).value); });
    return out;
  }

  byId('adm-br-asset').addEventListener('change', renderAsset);
  byId('adm-br-form').addEventListener('submit', (e) => {
    e.preventDefault();
    run(byId('adm-br-save'), async () => {
      const asset = byId('adm-br-asset').value;
      const k = Number(byId('adm-br-k').value);
      if (k !== Number(currentK)) { await api('PUT', '/api/admin/base-rates/margin-k', { marginK: k }); }
      await api('PUT', '/api/admin/base-rates/' + encodeURIComponent(asset), body());
      await load(asset);
    });
  });
  byId('adm-br-defaults').addEventListener('click', (e) => {
    const a = current();
    if (!a) { return; }
    const dedicatedOnly = a.asset !== 'BTC' && a.asset !== 'ETH';
    const lines = dedicatedOnly
      ? ['La ligne dédiée de ' + a.asset + ' est supprimée : l\'actif retourne sur les valeurs de repli (BTC), non calibrées.']
      : ['Les 4 montants de ' + a.asset + ' reviennent à la table de calage.'];
    run(e.currentTarget, async () => {
      if (!await core.askConfirm('Remettre les défauts de ' + a.asset, lines, 'OK', 'Remettre')) { return; }
      await api('POST', '/api/admin/base-rates/' + encodeURIComponent(a.asset) + '/defaults');
      await load(a.asset);
    });
  });

  core.register('baseRates', { load: () => run(null, () => load()), show: () => run(null, () => load()) });
})();
