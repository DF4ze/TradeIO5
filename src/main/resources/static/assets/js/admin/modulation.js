/* Onglet Modulation : GET/PUT /api/admin/rainbow-modulation. Un champ par paramètre (défaut + bornes absolues affichés). */
(function () {
  'use strict';
  const core = window.AdminCore;
  if (!core) { return; }
  const { el, api, run, byId } = core;

  /* Un réglage = sa clé API, son libellé, son pas, et le détail de son impact (calcul : signal × puissance·(1 ± biais) × facteur de bande,
     produit plafonné, puis plafond dur de bande — cf. LiveModulation). Tout changement s'applique tout de suite à tous les utilisateurs. */
  const FIELDS = [
    { key: 'regulationMarginPct', label: 'Marge de régulation (% de la largeur de bande)', step: '1',
      what: 'Largeur, à l\'intérieur de chaque borne de la bande d\'exposition, de la zone où le moteur freine ce qui rapproche de la borne. Au centre (zone libre), rien n\'est modifié. Valeur par défaut des utilisateurs qui n\'ont pas réglé leur propre marge.',
      up: 'Le freinage commence plus tôt et plus progressivement ; la zone libre rétrécit.',
      down: 'Zone libre plus large ; le freinage est tardif et plus brutal près des bornes.',
      example: 'Bande 5–95 % (largeur 90 pts) avec 25 % : le freinage démarre à 72,5 % d\'exposition et les achats tombent à 0 à 95 %.' },
    { key: 'sellBoostMax', label: 'Boost d\'accentuation max (×)', step: '0.1',
      what: 'Multiplicateur maximal appliqué quand l\'exposition atteint une borne : les ventes sont amplifiées près de la borne haute, et les achats près de la borne basse (effet symétrique, malgré le mot « vente » du nom technique). Ne s\'applique qu\'à ce qui éloigne de la borne.',
      up: 'Le rééquilibrage vers le centre de la bande est plus rapide et plus fort (plus d\'ordres, de plus gros montants).',
      down: 'Rééquilibrage plus lent. À 1, aucune accentuation : seul le freinage reste.',
      example: 'Exposition à la borne haute, le Rainbow veut vendre 1 BTC, boost 2 : 2 BTC demandés (avant plafond du produit).' },
    { key: 'biasMax', label: 'Biais max', step: '0.05',
      what: 'Limite du curseur « biais » de l\'utilisateur (le biais par défaut est 0). Un biais b multiplie les achats par (1 + b) et les ventes par (1 − b). Ce réglage ne change rien tant que l\'utilisateur ne touche pas son curseur.',
      up: 'L\'utilisateur peut pousser plus fort vers l\'achat ou la vente ; ses réglages existants ne bougent pas.',
      down: 'Curseur bridé ; les biais déjà réglés au-delà de la nouvelle limite sont ramenés à la limite. À 0, le biais est désactivé pour tous.',
      example: 'Biais 0,5 : achats +50 %, ventes −50 %. Biais −0,5 : l\'inverse.' },
    { key: 'powerMin', label: 'Puissance min (×)', step: '0.05',
      what: 'Plancher du curseur « puissance » de l\'utilisateur, qui multiplie achats ET ventes. Doit rester ≤ 1 (1 = neutre).',
      up: 'Les utilisateurs ne peuvent plus être aussi prudents ; ceux qui sont en dessous sont remontés au plancher.',
      down: 'Ils peuvent réduire davantage leur activité (0 = plus aucun ordre).',
      example: 'Plancher 0,25 : un utilisateur au minimum n\'envoie plus que le quart des montants du Rainbow.' },
    { key: 'powerMax', label: 'Puissance max (×)', step: '0.05',
      what: 'Plafond du curseur « puissance » de l\'utilisateur. Doit rester ≥ 1. Plus le plafond est haut, plus un utilisateur peut engager d\'argent par ordre.',
      up: 'Ordres potentiellement plus gros, en réel aussi ; ceux dont le réglage reste valide ne changent pas.',
      down: 'Les utilisateurs au-dessus du nouveau plafond sont ramenés à ce plafond dès le prochain calcul.',
      example: 'Plafond 2 : un utilisateur au maximum double tous ses achats et toutes ses ventes.' },
    { key: 'minBandWidthPts', label: 'Largeur mini de bande (pts)', step: '1',
      what: 'Garde-fou : une bande d\'exposition plus étroite que cette valeur est élargie automatiquement autour de son centre. Évite une bande quasi nulle où les ordres seraient bridés en permanence.',
      up: 'Les bandes étroites sont élargies davantage : la modulation devient moins contraignante pour ces utilisateurs.',
      down: 'Bandes très serrées autorisées : exposition tenue de près, mais ordres plus souvent bloqués.',
      example: 'Minimum 10 : un utilisateur qui demande 48–52 % obtient 45–55 %.' },
    { key: 'modulationProductMax', label: 'Produit max des facteurs (×)', step: '0.1',
      what: 'Plafond du multiplicateur total d\'un ordre, par sens : puissance × biais × facteur de bande. C\'est le garde-fou contre l\'empilement de réglages qui gonflerait un ordre bien au-delà du signal Rainbow.',
      up: 'Un ordre modulé peut dépasser davantage le montant du signal : plus de risque, en réel aussi.',
      down: 'Ordres plus proches du signal ; peut annuler l\'effet des curseurs et du boost si trop bas.',
      example: 'Puissance 2 × boost 2 = ×4 demandé, plafonné à ×2,5 : l\'ordre vaut 2,5 fois le signal, pas 4.' },
    { key: 'autonomyDays', label: 'Autonomie cible (jours)', step: '1',
      what: 'N\'influence PAS les ordres. Sert uniquement à proposer le montant de base à l\'onboarding : cash du wallet ÷ nombre de liens ÷ (jours × multiplicateur moyen du preset).',
      up: 'Montant de base proposé plus petit : le cash est étalé sur plus longtemps.',
      down: 'Montant de base proposé plus grand : le cash dure moins longtemps.',
      example: '90 jours avec 3 000 USD de cash, 3 liens et multiplicateur moyen 1 : environ 11 USD de base par lien et par jour.' },
    { key: 'drawdownCapPrudentPct', label: 'Plafond de drawdown · Prudent (%)', step: '1',
      what: 'N\'influence PAS les ordres. Perte maximale tolérée du wallet (depuis son plus haut) pour le profil Prudent : le simulateur signale un dépassement. Valeur négative.',
      up: 'Plafond moins large (plus proche de 0) : le profil est plus souvent signalé comme dépassé.',
      down: 'Plafond plus large : plus de baisse tolérée. Doit rester ≥ au plafond Équilibré (Prudent le plus serré).',
      example: '−15 % : un wallet qui passe de 1 000 à 840 USDC depuis son plus haut dépasse le plafond.' },
    { key: 'drawdownCapBalancedPct', label: 'Plafond de drawdown · Équilibré (%)', step: '1',
      what: 'Plafond de drawdown du profil Équilibré (signalement du simulateur uniquement). Valeur négative.',
      up: 'Plafond plus serré : plus de dépassements signalés.', down: 'Plafond plus large. Doit rester entre Prudent et Dynamique.',
      example: '−25 % : 1 000 → 750 USDC est exactement au plafond (respecté).' },
    { key: 'drawdownCapDynamicPct', label: 'Plafond de drawdown · Dynamique (%)', step: '1',
      what: 'Plafond de drawdown du profil Dynamique (signalement du simulateur uniquement). Valeur négative.',
      up: 'Plafond plus serré : plus de dépassements signalés.', down: 'Plafond plus large. Doit rester entre Équilibré et Full.',
      example: '−40 % : 1 000 → 600 USDC est exactement au plafond.' },
    { key: 'drawdownCapFullPct', label: 'Plafond de drawdown · Full (%)', step: '1',
      what: 'Plafond de drawdown du profil Full (signalement du simulateur uniquement). Valeur négative, le plus large des quatre.',
      up: 'Plafond plus serré : plus de dépassements signalés.', down: 'Plafond plus large (min −100 %). Doit rester ≤ Dynamique.',
      example: '−60 % : 1 000 → 400 USDC est exactement au plafond.' },
    { key: 'baseRateBtcPrudent', label: 'Taux de base · BTC · Prudent (/jour/cash)', step: '0.00001',
      what: 'N\'influence PAS les ordres. Montant de base proposé à l\'onboarding = ce taux × cash alloué au lien (profil Prudent, actif BTC). Proposition indicative : l\'utilisateur peut la modifier. Pour un actif sans ligne propre (PAXG, nouveaux), la ligne BTC sert de repli, signalée « niveau non calibré ».',
      up: 'Montant de base proposé plus grand : exposition et drawdown attendus plus élevés (cible de risque calibrée sur l\'historique, pas une garantie).',
      down: 'Montant de base proposé plus petit : exposition et drawdown attendus plus faibles. Doit rester ≥ au taux du profil précédent (Prudent ≤ Équilibré ≤ Dynamique ≤ Full).',
      example: 'Défaut 2,774e-4 : avec 1 000 USDC alloués au lien, base proposée = 1 000 × taux par jour.' },
    { key: 'baseRateBtcBalanced', label: 'Taux de base · BTC · Équilibré (/jour/cash)', step: '0.00001',
      what: 'N\'influence PAS les ordres. Montant de base proposé à l\'onboarding = ce taux × cash alloué au lien (profil Équilibré, actif BTC). Proposition indicative : l\'utilisateur peut la modifier. Pour un actif sans ligne propre (PAXG, nouveaux), la ligne BTC sert de repli, signalée « niveau non calibré ».',
      up: 'Montant de base proposé plus grand : exposition et drawdown attendus plus élevés (cible de risque calibrée sur l\'historique, pas une garantie).',
      down: 'Montant de base proposé plus petit : exposition et drawdown attendus plus faibles. Doit rester ≥ au taux du profil précédent (Prudent ≤ Équilibré ≤ Dynamique ≤ Full).',
      example: 'Défaut 4,736e-4 : avec 1 000 USDC alloués au lien, base proposée = 1 000 × taux par jour.' },
    { key: 'baseRateBtcDynamic', label: 'Taux de base · BTC · Dynamique (/jour/cash)', step: '0.00001',
      what: 'N\'influence PAS les ordres. Montant de base proposé à l\'onboarding = ce taux × cash alloué au lien (profil Dynamique, actif BTC). Proposition indicative : l\'utilisateur peut la modifier. Pour un actif sans ligne propre (PAXG, nouveaux), la ligne BTC sert de repli, signalée « niveau non calibré ».',
      up: 'Montant de base proposé plus grand : exposition et drawdown attendus plus élevés (cible de risque calibrée sur l\'historique, pas une garantie).',
      down: 'Montant de base proposé plus petit : exposition et drawdown attendus plus faibles. Doit rester ≥ au taux du profil précédent (Prudent ≤ Équilibré ≤ Dynamique ≤ Full).',
      example: 'Défaut 7,712e-4 : avec 1 000 USDC alloués au lien, base proposée = 1 000 × taux par jour.' },
    { key: 'baseRateBtcFull', label: 'Taux de base · BTC · Full (/jour/cash)', step: '0.00001',
      what: 'N\'influence PAS les ordres. Montant de base proposé à l\'onboarding = ce taux × cash alloué au lien (profil Full, actif BTC). Proposition indicative : l\'utilisateur peut la modifier. Pour un actif sans ligne propre (PAXG, nouveaux), la ligne BTC sert de repli, signalée « niveau non calibré ».',
      up: 'Montant de base proposé plus grand : exposition et drawdown attendus plus élevés (cible de risque calibrée sur l\'historique, pas une garantie).',
      down: 'Montant de base proposé plus petit : exposition et drawdown attendus plus faibles. Doit rester ≥ au taux du profil précédent (Prudent ≤ Équilibré ≤ Dynamique ≤ Full).',
      example: 'Défaut 1,423e-3 : avec 1 000 USDC alloués au lien, base proposée = 1 000 × taux par jour.' },
    { key: 'baseRateEthPrudent', label: 'Taux de base · ETH · Prudent (/jour/cash)', step: '0.00001',
      what: 'N\'influence PAS les ordres. Montant de base proposé à l\'onboarding = ce taux × cash alloué au lien (profil Prudent, actif ETH). Proposition indicative : l\'utilisateur peut la modifier. Pour un actif sans ligne propre (PAXG, nouveaux), la ligne BTC sert de repli, signalée « niveau non calibré ».',
      up: 'Montant de base proposé plus grand : exposition et drawdown attendus plus élevés (cible de risque calibrée sur l\'historique, pas une garantie).',
      down: 'Montant de base proposé plus petit : exposition et drawdown attendus plus faibles. Doit rester ≥ au taux du profil précédent (Prudent ≤ Équilibré ≤ Dynamique ≤ Full).',
      example: 'Défaut 3,804e-4 : avec 1 000 USDC alloués au lien, base proposée = 1 000 × taux par jour.' },
    { key: 'baseRateEthBalanced', label: 'Taux de base · ETH · Équilibré (/jour/cash)', step: '0.00001',
      what: 'N\'influence PAS les ordres. Montant de base proposé à l\'onboarding = ce taux × cash alloué au lien (profil Équilibré, actif ETH). Proposition indicative : l\'utilisateur peut la modifier. Pour un actif sans ligne propre (PAXG, nouveaux), la ligne BTC sert de repli, signalée « niveau non calibré ».',
      up: 'Montant de base proposé plus grand : exposition et drawdown attendus plus élevés (cible de risque calibrée sur l\'historique, pas une garantie).',
      down: 'Montant de base proposé plus petit : exposition et drawdown attendus plus faibles. Doit rester ≥ au taux du profil précédent (Prudent ≤ Équilibré ≤ Dynamique ≤ Full).',
      example: 'Défaut 6,349e-4 : avec 1 000 USDC alloués au lien, base proposée = 1 000 × taux par jour.' },
    { key: 'baseRateEthDynamic', label: 'Taux de base · ETH · Dynamique (/jour/cash)', step: '0.00001',
      what: 'N\'influence PAS les ordres. Montant de base proposé à l\'onboarding = ce taux × cash alloué au lien (profil Dynamique, actif ETH). Proposition indicative : l\'utilisateur peut la modifier. Pour un actif sans ligne propre (PAXG, nouveaux), la ligne BTC sert de repli, signalée « niveau non calibré ».',
      up: 'Montant de base proposé plus grand : exposition et drawdown attendus plus élevés (cible de risque calibrée sur l\'historique, pas une garantie).',
      down: 'Montant de base proposé plus petit : exposition et drawdown attendus plus faibles. Doit rester ≥ au taux du profil précédent (Prudent ≤ Équilibré ≤ Dynamique ≤ Full).',
      example: 'Défaut 1,019e-3 : avec 1 000 USDC alloués au lien, base proposée = 1 000 × taux par jour.' },
    { key: 'baseRateEthFull', label: 'Taux de base · ETH · Full (/jour/cash)', step: '0.00001',
      what: 'N\'influence PAS les ordres. Montant de base proposé à l\'onboarding = ce taux × cash alloué au lien (profil Full, actif ETH). Proposition indicative : l\'utilisateur peut la modifier. Pour un actif sans ligne propre (PAXG, nouveaux), la ligne BTC sert de repli, signalée « niveau non calibré ».',
      up: 'Montant de base proposé plus grand : exposition et drawdown attendus plus élevés (cible de risque calibrée sur l\'historique, pas une garantie).',
      down: 'Montant de base proposé plus petit : exposition et drawdown attendus plus faibles. Doit rester ≥ au taux du profil précédent (Prudent ≤ Équilibré ≤ Dynamique ≤ Full).',
      example: 'Défaut 1,533e-3 : avec 1 000 USDC alloués au lien, base proposée = 1 000 × taux par jour.' },
    { key: 'baseRateMarginK', label: 'Marge k du calage des taux (information)', step: '0.05',
      what: 'Information seulement, non utilisée par le calcul : marge de sécurité avec laquelle les taux ci-dessus ont été calés (plafond de calage = plafond de drawdown / k). Si vous modifiez les taux, mettez k à jour pour garder la trace.',
      up: 'Sans effet.', down: 'Sans effet.',
      example: 'k = 1,1 : un plafond de −15 % a été calé à −13,6 %.' }
  ];

  let params = {};

  function help(f, p) {
    const line = (title, text) => el('p', { class: 'adm-mod-line' }, el('strong', { text: title + ' ' }), text);
    return el('div', { class: 'adm-mod-help' },
      el('p', { class: 'adm-mod-line', text: f.what }),
      line('▲ Si vous augmentez :', f.up),
      line('▼ Si vous diminuez :', f.down),
      line('Exemple :', f.example),
      el('p', { class: 'adm-mod-range', text: 'Défaut ' + p.defaultValue + ' · bornes absolues [' + p.min + ' ; ' + p.max + ']' }));
  }

  function field(f) {
    const p = params[f.key] || {};
    const input = el('input', { type: 'number', class: 'form-control', id: 'adm-mod-' + f.key, step: f.step,
      min: p.min, max: p.max, required: true, value: p.value });
    return el('div', { class: 'col-12 col-lg-6 adm-mod-field' },
      el('label', { class: 'form-label', for: 'adm-mod-' + f.key, text: f.label }), input, help(f, p));
  }

  function render(dto) {
    params = dto.parameters;
    byId('adm-mod-fields').replaceChildren(...FIELDS.map(field));
    byId('adm-mod-meta').textContent = dto.updatedBy ? 'Dernière modification : ' + core.fmtDate(dto.updatedAt) + ' par ' + dto.updatedBy : '';
  }

  async function load() {
    render(await api('GET', '/api/admin/rainbow-modulation'));
    await loadProposals();
  }

  /* ---- Actifs à valider (D1b lot 2) : GET /api/admin/new-asset-proposals, POST /{id}/validate|refuse */
  const fmtRate = (v) => Number(v).toExponential(3);

  function proposalCard(p) {
    const line = (k, v) => el('div', { class: 'small' }, el('strong', { text: k + ' ' }), v == null || v === '' ? '—' : String(v));
    const rows = (p.rates || []).map((r) => el('tr', null, el('td', { text: r.profile }), el('td', { text: r.capPct + ' %' }),
      el('td', { text: '×' + Number(r.multiplier).toFixed(2) }), el('td', { text: fmtRate(r.baseRate) }), el('td', { text: r.status })));
    const rates = rows.length
      ? el('table', { class: 'table table-sm w-auto mb-2' }, el('thead', null, el('tr', null, ['Profil', 'Plafond', 'Multiplicateur', 'Taux /jour/cash', 'Statut'].map((h) => el('th', { text: h })))), el('tbody', null, ...rows))
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
    await run(btn, async () => { await api('POST', '/api/admin/new-asset-proposals/' + p.id + '/' + action); await loadProposals(); });
  }

  async function loadProposals() {
    const list = await api('GET', '/api/admin/new-asset-proposals');
    const host = byId('adm-nap-list');
    host.replaceChildren(...(list.length ? list.map(proposalCard) : [el('div', { class: 'small text-muted', text: 'Aucune proposition en attente.' })]));
  }

  function body(useDefaults) {
    const out = {};
    FIELDS.forEach(({ key }) => { out[key] = useDefaults ? params[key].defaultValue : Number(byId('adm-mod-' + key).value); });
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
