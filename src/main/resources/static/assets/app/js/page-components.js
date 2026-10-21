/* Catalogue des composants communs (données d'exemple fixes, aucun appel API). */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, F = TIO.fmt;

  function sampleSeries() {
    const out = []; let v = 10000;
    for (let i = 0; i < 40; i++) { v *= 1 + Math.sin(i / 4) * 0.01 + 0.003; out.push({ x: new Date(Date.UTC(2026, 0, 1 + i)).toISOString().slice(0, 10), y: Math.round(v) }); }
    return out;
  }
  const section = (title, ...children) => ui.card(title, ...children);

  TIO.start(function (root) {
    const tokens = ['--bg', '--surface', '--text', '--muted', '--line', '--accent', '--up', '--down', '--warn', '--s0', '--s1', '--s2', '--s3', '--s4', '--s5', '--s6'];
    const sw1 = ui.switchEl({ label: 'Exemple actif', checked: true }), sw2 = ui.switchEl({ label: 'Exemple inactif', checked: false }), sw3 = ui.switchEl({ label: 'Exemple désactivé', checked: true, disabled: true });
    const sl = ui.slider({ min: 0.5, max: 2, step: 0.05, value: 1.25, marker: 1, label: 'Exemple de curseur' });
    const val = h('span', { class: 'num' }, '1,25×');
    sl.input.addEventListener('input', () => { val.textContent = F.num(Number(sl.input.value), 2) + '×'; });
    TIO.mount(root,
      h('header', { class: 'tio-head' }, h('h1', null, 'Composants'), h('div', { class: 'sub' }, 'Catalogue des composants communs, thème ' + document.documentElement.dataset.theme + '.')),
      h('div', { class: 'grid grid--3' },
        section('Jetons de couleur', h('div', { class: 'chips' }, tokens.map((t) => h('span', { class: 'chip chip--plain', style: { background: 'var(' + t + ')', color: ['--bg', '--surface', '--line', '--text'].includes(t) ? 'var(--text)' : '#0f1119', border: '1px solid var(--line)' } }, t.slice(2))))),
        section('Pastilles de statut', h('div', { class: 'chips' }, ui.statusChip('OK'), ui.statusChip('PARTIAL'), ui.statusChip('BLOCKED'), ui.statusChip(null)), h('div', { class: 'chips' }, ui.chip('Exécution réelle', 'ok'), ui.chip('Simulation', 'accent'), ui.chip('Prochaine passe dans 5 h', 'accent', 'chip--plain')), h('div', null, ui.tag('AVANCÉ'), ' ', ui.tag('EXPERT'))),
        section('Sélecteurs', ui.periods([{ value: '7', label: '7 j' }, { value: '30', label: '30 j' }, { value: '90', label: '90 j' }, { value: '365', label: '1 an' }, { value: 'all', label: 'Tout' }], '30', () => {}),
          ui.segmented([{ value: 'a', label: 'Simple' }, { value: 'b', label: 'Avancé' }, { value: 'c', label: 'Expert' }], 'b', () => {}, 'Exemple'),
          ui.pchips([{ value: 'a', label: 'Par actif' }, { value: 'b', label: 'Par provider' }], 'a', () => {}, 'Périmètre')),
        section('Interrupteurs', ui.setting('Actif', 'Interrupteur activé', sw1.el), ui.setting('Inactif', 'Interrupteur coupé', sw2.el), ui.setting('Désactivé', 'Non modifiable', sw3.el)),
        section('Barre et curseur', ui.bar(72), h('div', { class: 'row row--between' }, h('span', { class: 'muted' }, 'Puissance'), val), sl.el, ui.bandBar({ min: 30, max: 70, freeMin: 40, freeMax: 60, value: 52 })),
        section('Jauge d\'exposition', ui.gauge({ value: 52, bandMin: 30, bandMax: 70 })),
        section('Courbe de valeur', TIO.charts.area(sampleSeries(), { label: 'Exemple' }), ui.note('hors dépôts/retraits')),
        section('Prix moyen vs cours', TIO.charts.priceVsAvg(68400, 59400)),
        section('Valeurs', h('div', { class: 'num value-xl' }, F.usd(12345.6, 0)), h('div', { class: 'num value-md up' }, '▲ ' + F.signedUsd(743, 0)), h('div', { class: 'num value-md down' }, '▽ ' + F.signedPct(-3.2)), h('div', { class: 'num' }, F.dash)),
        section('États', ui.loading(), ui.empty('Rien à afficher', 'Texte d\'explication.'), ui.error(new Error('Exemple d\'erreur'), () => {})),
        section('Frise de décision', h('div', { class: 'pipe' }, [h('div', { class: 'st ok' }, h('div', { class: 't' }, 'Signal'), h('div', { class: 'd' }, 'Achat de base 25 $')), h('div', { class: 'st warn' }, h('div', { class: 't' }, 'Plafonds'), h('div', { class: 'd' }, 'Réduit à 20 $')), h('div', { class: 'st down' }, h('div', { class: 't' }, 'Plan'), h('div', { class: 'd' }, 'Bloqué')), h('div', { class: 'st' }, h('div', { class: 't' }, 'Résultat'), h('div', { class: 'd' }, F.dash))]),
          h('div', { class: 'row small' }, h('span', { class: 'carry', 'aria-hidden': 'true' }), 'Carry (reporté)')),
        section('Boutons et champs', h('div', { class: 'row' }, h('button', { class: 'btn btn--primary', type: 'button' }, 'Principal'), h('button', { class: 'btn', type: 'button' }, 'Secondaire'), h('button', { class: 'btn btn--danger', type: 'button' }, 'Danger')),
          ui.field('Champ', h('input', { type: 'text', placeholder: 'Exemple' }), 'Aide du champ'), h('div', { class: 'banner banner--warn' }, 'Bandeau d\'avertissement'), h('div', { class: 'coin-row row' }, ['BTC', 'ETH', 'PAXG'].map(ui.coin)))));
  });
})(window);
