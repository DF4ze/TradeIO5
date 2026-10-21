/* Réglages : mode d'affichage, profil et modulation (bande, puissance, biais), clés de lecture, plafonds, alertes, apparence, compte. */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, F = TIO.fmt;
  const PREF = '/api/user/preferences', MOD = '/api/user/modulation-settings';
  const MODES = [{ value: 'SIMPLE', label: 'Simple' }, { value: 'ADVANCED', label: 'Avancé' }, { value: 'EXPERT', label: 'Expert' }];
  const PROFILES = [{ value: 'PRUDENT', label: 'Prudent', desc: 'Bande 20 – 50 %' }, { value: 'BALANCED', label: 'Équilibré', desc: 'Bande 30 – 70 %' }, { value: 'DYNAMIC', label: 'Dynamique', desc: 'Bande 40 – 90 %' }];
  const ALERTS = ['BUY_BLOCKED', 'DATA_UNAVAILABLE', 'NOT_TRADABLE', 'CREDENTIAL_PROBLEM', 'PLAN_PROBLEM', 'TRANSIT_STOCK'];

  async function savePrefs(prefs, patch) {
    const body = Object.assign({ theme: prefs.theme, displayMode: prefs.displayMode, alertsDisabled: prefs.alertsDisabled, maxOrderUsd: prefs.caps.maxOrderUsd, maxDayUsd: prefs.caps.maxDayUsd }, patch);
    return TIO.put(PREF, body);
  }
  const fail = (e) => TIO.toast(e.message, true);

  function displayCard(state, rebuild) {
    const link = h('p', { class: 'note' });
    const paint = () => TIO.mount(link, state.prefs.displayMode === 'EXPERT' ? ['Espace Expert : stratégies, presets et modulation détaillée sur ', h('a', { href: '/user' }, 'la page Expert'), '.'] : '');
    paint();
    return ui.card('Mode d\'affichage',
      ui.segmented(MODES, state.prefs.displayMode, async (v) => {
        try { state.prefs = await savePrefs(state.prefs, { displayMode: v }); document.body.dataset.mode = v.toLowerCase(); TIO.toast('Mode ' + MODES.find((m) => m.value === v).label + ' enregistré'); rebuild(); } catch (e) { fail(e); }
      }, 'Mode d\'affichage'),
      h('div', { class: 'note' }, 'Simple : décisions et PnL. Avancé : + bande d\'exposition, puissance, biais, plafonds. Expert : + paramètres de stratégie (page Expert).'), link);
  }

  function profileCard(state, onApplied) {
    const note = h('div', { class: 'note' });
    return ui.card('Profil de départ',
      ui.segmented(PROFILES.map((p) => ({ value: p.value, label: p.label })), state.prefs.startingProfile, async (v) => {
        try { const r = await TIO.put(PREF + '/profile', { profile: v }); state.prefs.startingProfile = v; if (onApplied) onApplied(r.modulation); TIO.toast('Profil enregistré'); } catch (e) { fail(e); }
      }, 'Profil de départ'),
      h('div', { class: 'note' }, PROFILES.map((p) => p.label + ' : ' + p.desc.toLowerCase()).join(' · ')), note);
  }

  function modulationCard(state) {
    return ui.widget('Modulation', () => TIO.get(MOD), (m) => {
      const L = m.limits, S = m.settings;
      const cur = { min: S.exposureMin, max: S.exposureMax, power: S.power, bias: S.bias };
      const out = (id) => h('span', { class: 'num', id });
      const minOut = out(), maxOut = out(), powOut = out(), biasOut = out();
      const bar = h('div');
      const paint = () => {
        minOut.textContent = F.pct(cur.min, 0); maxOut.textContent = F.pct(cur.max, 0); powOut.textContent = F.num(cur.power, 2) + '×'; biasOut.textContent = (cur.bias > 0 ? '+' : '') + F.num(cur.bias, 2);
        TIO.mount(bar, ui.bandBar({ min: cur.min, max: cur.max }));
      };
      const sMin = ui.slider({ min: 0, max: 100, step: 1, value: cur.min, label: 'Exposition minimale', onInput: (v) => { cur.min = Math.min(v, cur.max - L.minBandWidthPts); sMin.set(cur.min); paint(); } });
      const sMax = ui.slider({ min: 0, max: 100, step: 1, value: cur.max, label: 'Exposition maximale', onInput: (v) => { cur.max = Math.max(v, cur.min + L.minBandWidthPts); sMax.set(cur.max); paint(); } });
      const sPow = ui.slider({ min: L.powerMin, max: L.powerMax, step: 0.05, value: cur.power, marker: 1, label: 'Puissance', onInput: (v) => { cur.power = v; paint(); } });
      const sBias = ui.slider({ min: -L.biasMax, max: L.biasMax, step: 0.05, value: cur.bias, marker: 0, label: 'Biais', onInput: (v) => { cur.bias = v; paint(); } });
      paint();
      const row = (label, o, s, hint) => h('div', { class: 'stack' }, h('div', { class: 'row row--between' }, h('span', { class: 'lbl muted' }, label), o), s.el, hint ? h('div', { class: 'note' }, hint) : null);
      return [bar, row('Exposition minimale', minOut, sMin, 'Sous cette part en actifs, les achats sont amplifiés.'), row('Exposition maximale', maxOut, sMax, 'Au-dessus, les achats sont freinés et les ventes amplifiées. Écart minimal ' + F.num(L.minBandWidthPts, 0) + ' pts.'),
        row('Puissance', powOut, sPow, 'Multiplie tous les montants (repère 1×).'), row('Biais achat / vente', biasOut, sBias, '+ : achats favorisés, − : ventes favorisées.'),
        h('div', { class: 'row' }, h('button', { class: 'btn btn--primary', type: 'button', onclick: async () => {
          try { await TIO.put(MOD, { exposureMin: cur.min, exposureMax: cur.max, power: cur.power, bias: cur.bias }); TIO.toast('Modulation enregistrée'); } catch (e) { fail(e); }
        } }, 'Enregistrer'))];
    });
  }

  function keysCard() {
    return ui.card('Clés d\'échange (lecture)', ui.readKeyForm());
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
        try { state.prefs = await savePrefs(state.prefs, { maxOrderUsd: num(order), maxDayUsd: num(day) }); paintWarn(); TIO.toast('Plafonds enregistrés'); } catch (e) { fail(e); }
      } }, 'Enregistrer')));
  }

  function alertsCard(state) {
    const off = new Set(state.prefs.alertsDisabled || []);
    return ui.card('Alertes dans l\'application', ALERTS.map((t) => ui.setting(TIO.labels.alert[t], null, ui.switchEl({ label: TIO.labels.alert[t], checked: !off.has(t), onChange: async (on, input) => {
      if (on) off.delete(t); else off.add(t);
      try { state.prefs = await savePrefs(state.prefs, { alertsDisabled: [...off] }); } catch (e) { fail(e); if (on) off.add(t); else off.delete(t); input.checked = !on; }
    } }).el)), h('div', { class: 'note' }, 'Les alertes s\'affichent dans l\'application uniquement (pas d\'e-mail ni de notification).'));
  }

  function appearanceCard(state) {
    return ui.card('Apparence', ui.segmented([{ value: 'DARK', label: 'Sombre' }, { value: 'LIGHT', label: 'Clair' }], state.prefs.theme || 'DARK', async (v) => {
      try { state.prefs = await savePrefs(state.prefs, { theme: v }); TIO.setTheme(v === 'LIGHT' ? 'light' : 'dark'); } catch (e) { fail(e); }
    }, 'Thème'));
  }

  function accountCard() {
    return ui.card('Compte', h('div', { class: 'row' }, h('span', { class: 'avatar', 'aria-hidden': 'true' }, (document.body.dataset.user || '?').slice(0, 1).toUpperCase()), h('strong', null, document.body.dataset.user || '')),
      h('div', { class: 'row' }, h('a', { class: 'btn', href: '/api/auth/signoutForm' }, 'Se déconnecter'), h('a', { class: 'btn', href: TIO.demo ? '/app/settings' : '/app?demo=1' }, TIO.demo ? 'Quitter la démo' : 'Voir la démo'),
        h('a', { class: 'btn', href: TIO.href('/app/onboarding') }, 'Assistant de démarrage')));
  }

  TIO.start(function (root) {
    const holder = h('div', { class: 'grid grid--2' });
    const build = () => ui.load(holder, () => TIO.get(PREF), (prefs) => {
      const state = { prefs };
      const modCard = TIO.modeAtLeast('advanced') ? modulationCard(state) : null;
      const cards = [displayCard(state, build), profileCard(state, () => { if (modCard && modCard.reload) modCard.reload(); }), modCard, keysCard(), TIO.modeAtLeast('advanced') ? capsCard(state) : null, alertsCard(state), appearanceCard(state), accountCard()];
      return cards;
    });
    build();
    TIO.mount(root, ui.pageHeader('Réglages'), ui.demoBanner(), holder);
  });
})(window);
