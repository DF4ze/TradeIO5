/* Onboarding en 4 étapes : clé de lecture → actif et exchange → profil → montant et confirmation. L'exécution réelle reste désactivée. */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, F = TIO.fmt;
  const PROFILES = [
    { value: 'PRUDENT', label: 'Prudent', desc: 'Garde beaucoup de stable : bande d\'exposition 20 – 50 %.' },
    { value: 'BALANCED', label: 'Équilibré', desc: 'Compromis : bande d\'exposition 30 – 70 %.' },
    { value: 'DYNAMIC', label: 'Dynamique', desc: 'Plus exposé aux actifs : bande d\'exposition 40 – 90 %.' },
  ];

  TIO.start(function (root) {
    const st = { asset: null, walletId: null, profile: 'BALANCED', amount: '' };
    const steps = [];
    function step(n, title, body) {
      const d = h('details', { class: 'step' }, h('summary', null, h('span', { class: 'n' }, String(n)), title), h('div', { class: 'body' }, body));
      steps.push(d);
      return d;
    }
    const go = (i) => steps.forEach((d, k) => { d.open = k === i; });
    const done = (i) => { steps[i].dataset.done = 'true'; go(i + 1); };
    const next = (i, label) => h('div', { class: 'row' }, h('button', { class: 'btn btn--primary', type: 'button', onclick: () => done(i) }, label || 'Continuer'));

    // 1. clé de lecture
    step(1, 'Relier votre exchange (clé de lecture)', [ui.readKeyForm(() => { refreshWallets(); done(0); }),
      h('div', { class: 'row' }, h('button', { class: 'btn', type: 'button', onclick: () => done(0) }, 'J\'ai déjà relié mon exchange'))]);

    // 2. actif + wallet
    const assetSel = h('select'), walletSel = h('select'), walletInfo = h('div', { class: 'note' });
    function refreshWallets() {
      Promise.all([TIO.safe(TIO.get('/api/rainbow-live/wallets'), []), TIO.safe(TIO.get('/api/rainbow-live/defaults'), null)]).then(([wallets, defaults]) => {
        TIO.mount(assetSel, ((defaults && defaults.assets) || ['BTC']).map((a) => h('option', { value: a }, a)));
        TIO.mount(walletSel, wallets.map((w) => h('option', { value: String(w.id) }, w.name + (w.exchange ? ' · ' + w.exchange : ''))));
        TIO.mount(walletInfo, wallets.length ? '' : 'Aucun wallet : enregistrez d\'abord une clé de lecture à l\'étape 1.');
        st.asset = assetSel.value; st.walletId = walletSel.value;
      });
    }
    assetSel.addEventListener('change', () => { st.asset = assetSel.value; });
    walletSel.addEventListener('change', () => { st.walletId = walletSel.value; });
    refreshWallets();
    step(2, 'Choisir un actif et un exchange', [ui.field('Actif', assetSel), ui.field('Wallet', walletSel), walletInfo,
      h('div', { class: 'row' }, h('button', { class: 'btn', type: 'button', onclick: refreshWallets }, 'Actualiser'),
        h('button', { class: 'btn btn--primary', type: 'button', onclick: () => { if (!walletSel.value) { TIO.toast('Aucun wallet disponible', true); return; } done(1); } }, 'Continuer'))]);

    // 3. profil
    const desc = h('div', { class: 'note' }, PROFILES[1].desc);
    step(3, 'Choisir un profil', [ui.segmented(PROFILES.map((p) => ({ value: p.value, label: p.label })), st.profile, (v) => { st.profile = v; desc.textContent = PROFILES.find((p) => p.value === v).desc; }, 'Profil'), desc,
      h('div', { class: 'note' }, 'Vous pourrez ajuster la bande, la puissance et le biais dans les réglages.'), next(2)]);

    // 4. montant + confirmation
    const amount = h('input', { type: 'number', min: '0', step: 'any', inputmode: 'decimal', placeholder: 'ex. 10' });
    const result = h('div', { class: 'stack' });
    const create = h('button', { class: 'btn btn--primary', type: 'button', onclick: async () => {
      const v = Number(amount.value);
      if (!(v > 0)) { TIO.toast('Indiquez un montant de base positif', true); return; }
      if (!st.asset || !st.walletId) { TIO.toast('Choisissez un actif et un wallet (étapes 1 et 2)', true); return; }
      create.disabled = true;
      try {
        await TIO.put('/api/user/preferences/profile', { profile: st.profile });
        const presets = await TIO.get('/api/rainbow-live/presets' + TIO.qs({ asset: st.asset }));
        const preset = presets.find((p) => p.enabled) || presets[0];
        if (!preset) throw new Error('Aucune stratégie disponible pour ' + st.asset + ' : contactez l\'administrateur.');
        const r = await TIO.post('/api/rainbow-live/bindings', { assetSymbol: st.asset, presetId: preset.id, walletId: Number(st.walletId), baseAmount: v });
        const check = r && r.check;
        steps[3].dataset.done = 'true';
        TIO.mount(result, h('div', { class: 'banner' }, h('strong', null, st.asset + ' est suivi. '), 'Vous recevrez une décision chaque jour ; ',
          'l\'exécution réelle reste désactivée, vous l\'activez vous-même dans Actifs gérés.'),
          check ? h('div', { class: 'row' }, ui.statusChip(check.status), h('span', { class: 'muted small' }, check.message || '')) : null,
          h('div', { class: 'row' }, h('a', { class: 'btn btn--primary', href: TIO.href('/app/assets') }, 'Voir mes actifs')));
      } catch (e) { TIO.toast(e.message, true); } finally { create.disabled = false; }
    } }, 'Créer mon suivi');
    step(4, 'Montant et confirmation', [ui.field('Montant de base par jour ($)', amount, 'Avant modulation par la bande, la puissance et le biais.'),
      h('div', { class: 'banner banner--warn' }, 'Rien n\'est acheté à cette étape : le suivi calcule des décisions. L\'exécution réelle est désactivée par défaut.'), h('div', { class: 'row' }, create), result]);

    go(0);
    TIO.mount(root, ui.pageHeader('Démarrage', 'Quatre étapes pour recevoir votre première décision.'), ui.demoBanner(), h('div', { class: 'stack' }, steps));
  });
})(window);
