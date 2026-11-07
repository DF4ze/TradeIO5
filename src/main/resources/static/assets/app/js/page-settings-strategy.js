/* Réglages stratégiques : profil de départ (Prudent, Équilibré, Dynamique, Full, Custom), modulation (bande, puissance, biais, marge)
 * et son historique, plafonds de dépense, puis par actif et exchange : mode d'exécution, part vendable, achat minimum. */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, F = TIO.fmt, EM = TIO.execMode;
  const PREF = '/api/user/preferences', MOD = '/api/user/modulation-settings', BIND = '/api/rainbow-live/bindings';
  const PROFILES = [{ value: 'PRUDENT', label: 'Prudent', desc: 'Bande 20 – 50 %' }, { value: 'BALANCED', label: 'Équilibré', desc: 'Bande 30 – 70 %' },
    { value: 'DYNAMIC', label: 'Dynamique', desc: 'Bande 40 – 90 %' }, { value: 'FULL', label: 'Full', desc: 'Bande 0 – 100 % (toute l\'échelle)' }];
  const profileLabel = (p) => (p === 'CUSTOM' ? 'Custom' : (PROFILES.find((x) => x.value === p) || {}).label || F.dash);
  const fail = (e) => TIO.toast(e.message, true);

  /** Profil de départ ; Custom n'est pas un choix : on y bascule dès qu'un paramètre de modulation est modifié. */
  function profileCard(state) {
    const holder = h('div', { class: 'stack' });
    const paint = () => {
      const cur = state.profile, custom = cur === 'CUSTOM' || state.preview;
      const seg = ui.segmented(PROFILES.map((p) => ({ value: p.value, label: p.label })), custom ? null : cur, async (v) => {
        try {
          const r = await TIO.put(PREF + '/profile', { profile: v });
          state.profile = r.profile; state.preview = false; state.prefs.startingProfile = r.profile;
          state.reloadModulation(); state.reloadHistory(); paint(); TIO.toast('Profil ' + profileLabel(v) + ' enregistré');
        } catch (e) { fail(e); }
      }, 'Profil de départ');
      TIO.mount(holder, [seg,
        custom ? h('div', { class: 'row' }, ui.chip(state.preview && cur !== 'CUSTOM' ? 'Custom (non enregistré)' : 'Custom', 'accent'),
          h('span', { class: 'muted small' }, 'Modulation modifiée à la main. Choisir un profil remplace la bande d\'exposition (puissance, biais et marge sont conservés).')) : null,
        h('div', { class: 'note' }, PROFILES.map((p) => p.label + ' : ' + p.desc.toLowerCase()).join(' · '))]);
    };
    state.paintProfile = paint;
    paint();
    return ui.card('Profil de départ', holder, ui.cardOpts({ span: true }));
  }

  function modulationCard(state) {
    const card = ui.widget('Modulation', () => TIO.get(MOD), (m) => {
      const L = m.limits, S = m.settings;
      const cur = { min: S.exposureMin, max: S.exposureMax, power: S.power, bias: S.bias, margin: TIO.isNum(S.regulationMarginPct) ? S.regulationMarginPct : L.marginDefaultPct };
      const base = Object.assign({}, cur);
      state.profile = m.profile || state.profile;
      const out = (id) => h('span', { class: 'num', id });
      const minOut = out(), maxOut = out(), marOut = out(), powOut = out(), biasOut = out();
      const bar = h('div');
      const paint = () => {
        minOut.textContent = F.pct(cur.min, 0); maxOut.textContent = F.pct(cur.max, 0); marOut.textContent = F.pct(cur.margin, 0); powOut.textContent = F.num(cur.power, 2) + '×'; biasOut.textContent = (cur.bias > 0 ? '+' : '') + F.num(cur.bias, 2);
        TIO.mount(bar, ui.exposureBand({ min: cur.min, max: cur.max, marginPct: cur.margin }));
        const touched = Object.keys(base).some((k) => cur[k] !== base[k]);
        if (touched !== !!state.preview) { state.preview = touched; state.paintProfile(); }
      };
      const sMin = ui.slider({ min: 0, max: 100, step: 1, value: cur.min, label: 'Exposition minimale', onInput: (v) => { cur.min = Math.min(v, cur.max - L.minBandWidthPts); sMin.set(cur.min); paint(); } });
      const sMax = ui.slider({ min: 0, max: 100, step: 1, value: cur.max, label: 'Exposition maximale', onInput: (v) => { cur.max = Math.max(v, cur.min + L.minBandWidthPts); sMax.set(cur.max); paint(); } });
      const sMar = ui.slider({ min: L.marginMinPct, max: L.marginMaxPct, step: 1, value: cur.margin, marker: L.marginDefaultPct, label: 'Marge de régulation', onInput: (v) => { cur.margin = v; paint(); } });
      const sPow = ui.slider({ min: L.powerMin, max: L.powerMax, step: 0.05, value: cur.power, marker: 1, label: 'Puissance', onInput: (v) => { cur.power = v; paint(); } });
      const sBias = ui.slider({ min: -L.biasMax, max: L.biasMax, step: 0.05, value: cur.bias, marker: 0, label: 'Biais', onInput: (v) => { cur.bias = v; paint(); } });
      paint();
      const row = (label, o, s, hint) => h('div', { class: 'stack' }, h('div', { class: 'row row--between' }, h('span', { class: 'lbl muted' }, label), o), s.el, hint ? h('div', { class: 'note' }, hint) : null);
      return [bar, h('div', { class: 'grid grid--2' }, row('Exposition minimale', minOut, sMin, 'Sous cette part en actifs, les achats sont amplifiés.'), row('Exposition maximale', maxOut, sMax, 'Au-dessus, les achats sont freinés et les ventes amplifiées. Écart minimal ' + F.num(L.minBandWidthPts, 0) + ' pts.'),
        row('Marge de régulation', marOut, sMar, 'Largeur de la zone de transition près de chaque borne, en % de la bande (défaut ' + F.num(L.marginDefaultPct, 0) + ' %). Plus elle est grande, plus la zone libre est étroite et la régulation précoce.'),
        row('Puissance', powOut, sPow, 'Multiplie tous les montants (repère 1×).'), row('Biais achat / vente', biasOut, sBias, '+ : achats favorisés, − : ventes favorisées.')),
        h('div', { class: 'note' }, 'Modifier un de ces réglages bascule le profil sur Custom ; chaque changement enregistré est ajouté à l\'historique ci-dessous.'),
        h('div', { class: 'row' }, h('button', { class: 'btn btn--primary', type: 'button', onclick: async () => {
          try {
            const r = await TIO.put(MOD, { exposureMin: cur.min, exposureMax: cur.max, power: cur.power, bias: cur.bias, regulationMarginPct: cur.margin });
            state.profile = r.profile || state.profile; state.preview = false; state.prefs.startingProfile = state.profile;
            TIO.toast('Modulation enregistrée'); state.reloadModulation(); state.reloadHistory(); state.paintProfile();
          } catch (e) { fail(e); }
        } }, 'Enregistrer'))];
    }, { span: true });
    state.reloadModulation = () => card.reload();
    return card;
  }

  const changeParts = (b, a) => {
    const parts = [];
    if (b.exposureMin !== a.exposureMin || b.exposureMax !== a.exposureMax) parts.push('Bande ' + F.pct(b.exposureMin, 0) + ' – ' + F.pct(b.exposureMax, 0) + ' → ' + F.pct(a.exposureMin, 0) + ' – ' + F.pct(a.exposureMax, 0));
    if (b.power !== a.power) parts.push('Puissance ' + F.num(b.power, 2) + '× → ' + F.num(a.power, 2) + '×');
    if (b.bias !== a.bias) parts.push('Biais ' + F.num(b.bias, 2) + ' → ' + F.num(a.bias, 2));
    if (b.regulationMarginPct !== a.regulationMarginPct) parts.push('Marge ' + F.pct(b.regulationMarginPct, 0) + ' → ' + F.pct(a.regulationMarginPct, 0));
    return parts;
  };
  function historyCard(state) {
    const card = ui.widget('Historique de la modulation', () => TIO.get(MOD + '/history'), (items) => {
      if (!items.length) return ui.empty('Aucun changement', 'Chaque profil choisi ou réglage modifié sera listé ici.');
      return h('div', { class: 'list' }, items.map((e) => {
        const title = e.source === 'PROFILE' ? 'Profil ' + profileLabel(e.profileAfter) : 'Modification manuelle → ' + profileLabel(e.profileAfter);
        const parts = changeParts(e.before, e.after);
        return h('div', { class: 'item small' }, h('span', { class: 'num', style: { width: '120px' } }, F.dateTime(e.at)),
          h('div', { class: 'grow' }, h('strong', null, title), h('div', { class: 'muted' }, parts.length ? parts.join(' · ') : 'Profil changé, valeurs inchangées')));
      }));
    });
    state.reloadHistory = () => card.reload();
    return card;
  }

  function capsCard(state) {
    const c = state.prefs.caps;
    const order = h('input', { type: 'number', min: '0', step: 'any', value: c.maxOrderUsd ?? '', inputmode: 'decimal' });
    const day = h('input', { type: 'number', min: '0', step: 'any', value: c.maxDayUsd ?? '', inputmode: 'decimal' });
    const warn = h('div', { class: 'stack' });
    const paintWarn = () => TIO.mount(warn, (state.prefs.warnings || []).map((w) => h('div', { class: 'banner banner--warn', role: 'status' }, w)));
    paintWarn();
    const num = (el) => (el.value === '' ? null : Number(el.value));
    return ui.card('Plafonds de dépense',
      h('div', { class: 'note' }, 'Vos plafonds ne font que resserrer les plafonds absolus du serveur (' + F.usd(c.absoluteMaxOrderUsd, 0) + ' par ordre, ' + F.usd(c.absoluteMaxDayUsd, 0) + ' par jour). Vide = pas de plafond personnel.'),
      ui.field('Par ordre ($)', order), ui.field('Par jour ($)', day), warn,
      h('div', { class: 'row' }, h('button', { class: 'btn btn--primary', type: 'button', onclick: async () => {
        try { state.prefs = await ui.savePrefs(state.prefs, { maxOrderUsd: num(order), maxDayUsd: num(day) }); paintWarn(); TIO.toast('Plafonds enregistrés'); } catch (e) { fail(e); }
      } }, 'Enregistrer')));
  }

  /** Widget large : par exchange, la liste des actifs avec leur mode (Désactivé / Simulation / Actif) puis, en Avancé, part vendable et achat minimum. */
  function assetsWidget() {
    let widget;
    const refresh = () => widget.reload();
    const load = async () => {
      const [bindings, presets] = await Promise.all([TIO.get(BIND), TIO.safe(TIO.get('/api/rainbow-live/affectations'), [])]);
      const presetBase = Object.fromEntries(presets.map((p) => [p.id, p.globals && p.globals.baseAmount]));
      return bindings.map((b) => ({ b, presetBase: presetBase[b.affectationId] }));
    };
    const render = (items) => {
      if (!items.length) return ui.empty('Aucun actif géré', 'Reliez un actif à un exchange pour régler son mode et ses limites.', h('a', { class: 'btn btn--primary', href: TIO.href('/app/onboarding') }, 'Démarrer'));
      const groups = new Map();
      items.forEach((it) => { const k = it.b.exchange || 'Autre'; if (!groups.has(k)) groups.set(k, []); groups.get(k).push(it); });
      return [h('div', { class: 'note' }, 'Désactivé : rien n\'est joué. Simulation : toute la chaîne est jouée et chiffrée jusqu\'au plan d\'ordres (tag « Simulation » au Journal, dans Actifs gérés et sur les graphiques), sans rien envoyer. Actif : ordres réels (validation du premier ordre).'),
        ...[...groups.entries()].sort((x, y) => (x[0] < y[0] ? -1 : 1)).map(([name, list]) => h('section', { class: 'xgrp', 'aria-label': name },
          h('div', { class: 'xgrp-head' }, h('span', null, name), h('span', { class: 'muted small' }, list.length + (list.length > 1 ? ' actifs' : ' actif'))),
          list.sort((x, y) => x.b.priority - y.b.priority).map((it) => {
            const zone = h('div', { class: 'xzone stack' });
            return h('div', { class: 'xrow' }, h('div', { class: 'xid' }, ui.coin(it.b.assetSymbol), h('strong', { class: 'num' }, it.b.assetSymbol), EM.chip(it.b)),
              EM.control(it, refresh, zone), EM.limitsInline(it, refresh), zone);
          })))];
    };
    widget = ui.widget('Actifs et exchanges', load, render, { span: true });
    return widget;
  }

  TIO.start(function (root) {
    const holder = h('div', { class: 'grid grid--2' });
    const build = () => ui.load(holder, () => TIO.get(PREF), (prefs) => {
      const state = { prefs, profile: prefs.startingProfile, preview: false, reloadModulation() {}, reloadHistory() {}, paintProfile() {} };
      const advanced = TIO.modeAtLeast('advanced');
      const profile = profileCard(state);
      const mod = advanced ? modulationCard(state) : null;
      const hist = advanced ? historyCard(state) : null;
      return [profile, mod, assetsWidget(), advanced ? capsCard(state) : null, hist];
    });
    build();
    TIO.mount(root, ui.pageHeader('Réglages stratégiques', 'Comment TradeIO achète et vend pour vous.'), ui.demoBanner(), ui.settingsTabs('strategy'), holder);
  });
})(window);
