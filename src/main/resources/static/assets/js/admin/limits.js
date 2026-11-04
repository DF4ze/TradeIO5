/* Onglet Plafonds : GET/PUT /api/admin/limits. Trois catégories, un champ par paramètre (défaut + borne haute affichés). */
(function () {
  'use strict';
  const core = window.AdminCore;
  if (!core) { return; }
  const { el, api, run, byId } = core;

  /* Catégories -> réglages. L'impact de chaque réglage est détaillé (OrderExecutor.capBlock, FeeTest, UserPreferencesService). */
  const CATEGORIES = [
    { title: 'Plafonds absolus (USD) — dernier filet de sécurité',
      intro: 'Plafonds en dollars qu\'aucun réglage (multiplicateur, baseAmount, plafond personnel) ne peut dépasser.',
      fields: [
        { key: 'absoluteMaxOrderUsd', label: 'Plafond absolu par ordre (USD)', step: '1',
          what: 'Montant maximal d\'une étape d\'achat, en USD (cotation assimilée à l\'USD). Au-delà, l\'ordre est bloqué (CAP_ABSOLUTE_ORDER). Borne aussi les plafonds personnels que les utilisateurs saisissent dans leurs préférences, et le contrôle de coût du testeur d\'ordre.',
          up: 'Des ordres plus gros deviennent possibles, en réel. Les plafonds personnels existants ne bougent pas.',
          down: 'Les ordres au-dessus sont bloqués dès le prochain run. Les plafonds personnels déjà saisis au-dessus ne sont pas modifiés mais ne dépassent jamais ce nouveau plafond, et une paire dont le minimum dépasse ce plafond devient inutilisable.',
          example: 'Plafond 30, ordre prévu 45 USD : bloqué ; avec 50, il part (si les autres plafonds le permettent).' },
        { key: 'absoluteMaxDayUsd', label: 'Plafond absolu par jour et par utilisateur (USD)', step: '1',
          what: 'Total maximal envoyé par un utilisateur sur la journée (UTC) : ordres déjà envoyés + ordres du run en cours. Au-delà, le run est bloqué (CAP_ABSOLUTE_DAY). Doit rester ≥ plafond absolu par ordre.',
          up: 'Plus de dépense quotidienne possible par utilisateur, en réel.',
          down: 'Les runs qui feraient dépasser le total du jour sont bloqués ; ce qui est déjà envoyé n\'est pas annulé.',
          example: 'Plafond 100, déjà 80 USD envoyés aujourd\'hui, nouvel ordre de 30 : bloqué (110 > 100).' }
      ] },
    { title: 'Plafonds relatifs au preset (multiplicateurs)',
      intro: 'Plafonds exprimés en multiples du montant de base (baseAmount) configuré sur chaque liaison. Toujours bornés par les plafonds absolus ci-dessus.',
      fields: [
        { key: 'maxOrderMultiplier', label: 'Multiplicateur par ordre (× baseAmount)', step: '0.1',
          what: 'Un ordre ne peut pas dépasser ce multiple du baseAmount de sa liaison. Protège d\'une modulation (puissance, boost) qui gonflerait un ordre au-delà de ce que l\'utilisateur a prévu.',
          up: 'Les ordres modulés peuvent dépasser davantage le montant de base.',
          down: 'Les ordres au-dessus du multiple sont bloqués (CAP_ORDER). À 1, aucun ordre ne peut dépasser le baseAmount.',
          example: 'baseAmount 10 USD, multiplicateur 1,5 : un ordre jusqu\'à 15 USD passe, 16 USD est bloqué.' },
        { key: 'maxDayMultiplier', label: 'Multiplicateur par jour (× somme des baseAmount)', step: '0.1',
          what: 'Total journalier d\'un utilisateur ≤ ce multiple de la somme des baseAmount de toutes ses liaisons.',
          up: 'Plus de dépense quotidienne autorisée par rapport à ses montants de base.',
          down: 'Runs bloqués plus tôt dans la journée (CAP_DAY).',
          example: 'Trois liaisons à 10 USD, multiplicateur 2 : jusqu\'à 60 USD par jour.' }
      ] },
    { title: 'Frais (Fee Test)',
      intro: 'Seuils appliqués au coût total estimé d\'un chemin d\'achat (frais + écart du carnet), en % du montant.',
      fields: [
        { key: 'feeWarnPct', label: 'Seuil d\'avertissement (% de coût total)', step: '0.05',
          what: 'En dessous : VERT. À partir de ce seuil : ORANGE, un avertissement est affiché au plan et dans les devis de liaison. Ne bloque jamais rien.',
          up: 'Moins d\'avertissements : on tolère des coûts plus élevés sans alerte.',
          down: 'Plus de chemins signalés en orange.',
          example: 'Seuil 0,2 : un chemin à 0,15 % est vert, à 0,4 % orange.' },
        { key: 'feeRedPct', label: 'Seuil bloquant (% de coût total)', step: '0.05',
          what: 'Au-delà de ce coût : ROUGE. Au plan, simple avertissement ; à l\'exécution, une étape dont le coût re-devisé dépasse ce seuil n\'est PAS envoyée (COST_RED). Doit rester ≥ seuil d\'avertissement.',
          up: 'On accepte d\'exécuter des étapes plus chères : risque de payer plus de frais ou de glissement.',
          down: 'Plus d\'étapes non envoyées à l\'exécution ; trop bas, presque aucun ordre ne part.',
          example: 'Seuil 0,8 : une étape re-devisée à 0,9 % n\'est pas envoyée ; à 0,8 % elle part (orange).' }
      ] }
  ];

  const ALL = CATEGORIES.flatMap((c) => c.fields);
  let params = {};

  function help(f, p) {
    const line = (title, text) => el('p', { class: 'adm-mod-line' }, el('strong', { text: title + ' ' }), text);
    return el('div', { class: 'adm-mod-help' },
      el('p', { class: 'adm-mod-line', text: f.what }),
      line('▲ Si vous augmentez :', f.up),
      line('▼ Si vous diminuez :', f.down),
      line('Exemple :', f.example),
      el('p', { class: 'adm-mod-range', text: 'Défaut ' + p.defaultValue + ' · maximum ' + p.max }));
  }

  function field(f) {
    const p = params[f.key] || {};
    const input = el('input', { type: 'number', class: 'form-control', id: 'adm-lim-' + f.key, step: f.step,
      min: f.key.startsWith('fee') ? '0' : f.step, max: p.max, required: true, value: p.value });
    return el('div', { class: 'col-12 col-lg-6 adm-mod-field' },
      el('label', { class: 'form-label', for: 'adm-lim-' + f.key, text: f.label }), input, help(f, p));
  }

  function category(c) {
    return el('div', { class: 'col-12' },
      el('h6', { class: 'mt-2', text: c.title }),
      el('p', { class: 'small text-muted', text: c.intro }),
      el('div', { class: 'row g-3' }, ...c.fields.map(field)));
  }

  function render(dto) {
    params = dto.parameters;
    byId('adm-lim-fields').replaceChildren(...CATEGORIES.map(category));
    byId('adm-lim-meta').textContent = dto.updatedBy ? 'Dernière modification : ' + core.fmtDate(dto.updatedAt) + ' par ' + dto.updatedBy : '';
  }

  async function load() { render(await api('GET', '/api/admin/limits')); }

  function body(useDefaults) {
    const out = {};
    ALL.forEach(({ key }) => { out[key] = useDefaults ? params[key].defaultValue : Number(byId('adm-lim-' + key).value); });
    return out;
  }

  byId('adm-lim-form').addEventListener('submit', (e) => {
    e.preventDefault();
    run(byId('adm-lim-save'), async () => render(await api('PUT', '/api/admin/limits', body(false))));
  });
  byId('adm-lim-defaults').addEventListener('click', (e) => {
    run(e.currentTarget, async () => render(await api('PUT', '/api/admin/limits', body(true))));
  });

  core.register('limits', { load: () => run(null, load), show: () => run(null, load) });
})();
