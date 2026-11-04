/* Réglages utilisateur : mode d'affichage, alertes dans l'application, compte. (Les réglages de stratégie sont sur une autre page.) */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui;
  const MODES = [{ value: 'SIMPLE', label: 'Simple' }, { value: 'ADVANCED', label: 'Avancé' }, { value: 'EXPERT', label: 'Expert' }];
  const ALERTS = ['BUY_BLOCKED', 'DATA_UNAVAILABLE', 'NOT_TRADABLE', 'CREDENTIAL_PROBLEM', 'PLAN_PROBLEM', 'TRANSIT_STOCK'];
  const fail = (e) => TIO.toast(e.message, true);

  function displayCard(state, rebuild) {
    const link = h('p', { class: 'note' });
    TIO.mount(link, state.prefs.displayMode === 'EXPERT' ? ['Espace Expert : stratégies, presets et modulation détaillée sur ', h('a', { href: '/user' }, 'la page Expert'), '.'] : '');
    return ui.card('Mode d\'affichage',
      ui.segmented(MODES, state.prefs.displayMode, async (v) => {
        try { state.prefs = await ui.savePrefs(state.prefs, { displayMode: v }); document.body.dataset.mode = v.toLowerCase(); TIO.toast('Mode ' + MODES.find((m) => m.value === v).label + ' enregistré'); rebuild(); } catch (e) { fail(e); }
      }, 'Mode d\'affichage'),
      h('div', { class: 'note' }, 'Simple : décisions et PnL. Avancé : + bande d\'exposition, puissance, biais, plafonds, part vendable et achat minimum. Expert : + paramètres de stratégie (page Expert).'), link);
  }

  function alertsCard(state) {
    const off = new Set(state.prefs.alertsDisabled || []);
    return ui.card('Alertes dans l\'application', ALERTS.map((t) => ui.setting(TIO.labels.alert[t], null, ui.switchEl({ label: TIO.labels.alert[t], checked: !off.has(t), onChange: async (on, input) => {
      if (on) off.delete(t); else off.add(t);
      try { state.prefs = await ui.savePrefs(state.prefs, { alertsDisabled: [...off] }); } catch (e) { fail(e); if (on) off.add(t); else off.delete(t); input.checked = !on; }
    } }).el)), h('div', { class: 'note' }, 'Les alertes s\'affichent dans l\'application uniquement (pas d\'e-mail ni de notification).'));
  }

  function accountCard() {
    return ui.card('Compte', h('div', { class: 'row' }, h('span', { class: 'avatar', 'aria-hidden': 'true' }, (document.body.dataset.user || '?').slice(0, 1).toUpperCase()), h('strong', null, document.body.dataset.user || '')),
      h('div', { class: 'row' }, h('a', { class: 'btn', href: '/api/auth/signoutForm' }, 'Se déconnecter'), h('a', { class: 'btn', href: TIO.demo ? '/app/settings/user' : '/app?demo=1' }, TIO.demo ? 'Quitter la démo' : 'Voir la démo'),
        h('a', { class: 'btn', href: TIO.href('/app/onboarding') }, 'Assistant de démarrage'), h('a', { class: 'btn', href: TIO.href('/app/exchanges') }, 'Mes exchanges')));
  }

  TIO.start(function (root) {
    const holder = h('div', { class: 'grid grid--2' });
    const build = () => ui.load(holder, () => TIO.get('/api/user/preferences'), (prefs) => {
      const state = { prefs };
      return [displayCard(state, build), alertsCard(state), accountCard()];
    });
    build();
    TIO.mount(root, ui.pageHeader('Réglages utilisateur', 'Votre compte et votre affichage.'), ui.demoBanner(), ui.settingsTabs('user'), holder);
  });
})(window);
