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
      example: '90 jours avec 3 000 USD de cash, 3 liens et multiplicateur moyen 1 : environ 11 USD de base par lien et par jour.' }
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

  async function load() { render(await api('GET', '/api/admin/rainbow-modulation')); }

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
