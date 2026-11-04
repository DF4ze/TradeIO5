/* Onboarding en 5 étapes : clé de lecture → actif → chemin d'achat (Fee Test par exchange, choix de l'exchange) → profil → montant et confirmation. L'exécution réelle reste désactivée. */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, F = TIO.fmt;
  const PROFILES = [
    { value: 'PRUDENT', label: 'Prudent', desc: 'Garde beaucoup de stable : bande d\'exposition 20 – 50 %.' },
    { value: 'BALANCED', label: 'Équilibré', desc: 'Compromis : bande d\'exposition 30 – 70 %.' },
    { value: 'DYNAMIC', label: 'Dynamique', desc: 'Plus exposé aux actifs : bande d\'exposition 40 – 90 %.' },
  ];

  TIO.start(function (root) {
    const st = { asset: null, walletId: null, route: null, defaultRoute: null, quoted: false, profile: 'BALANCED', amount: '' };
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

    // 2. actif (pastilles avec logo)
    const quotes = h('div', { class: 'stack' });
    const assetBox = h('div', { class: 'asset-pick', role: 'group', 'aria-label': 'Actif' });
    function pickAsset(a) {
      st.asset = a; st.walletId = null; st.route = null; st.defaultRoute = null; st.quoted = false;
      if (typeof syncTrade === 'function') syncTrade();
      TIO.mount(quotes, '');
      Array.from(assetBox.children).forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.asset === a)));
    }
    function refreshWallets() {
      TIO.safe(TIO.get('/api/rainbow-live/defaults'), null).then((defaults) => {
        const assets = (defaults && defaults.assets) || ['BTC'];
        if (!assets.includes(st.asset)) st.asset = assets[0];
        TIO.mount(assetBox, assets.map((a) => {
          const b = h('button', { class: 'asset-chip', type: 'button', 'aria-pressed': String(a === st.asset) }, ui.coin(a), h('span', null, a));
          b.dataset.asset = a;
          b.addEventListener('click', () => pickAsset(a));
          return b;
        }));
      });
    }
    refreshWallets();
    step(2, 'Choisir un actif', [assetBox,
      h('div', { class: 'row' }, h('button', { class: 'btn btn--primary', type: 'button', onclick: () => { if (!st.asset) { TIO.toast('Choisissez un actif', true); return; } done(1); } }, 'Continuer'))]);

    // 3. chemin d'achat : Fee Test par exchange et par route ; un seul choix (exchange + route) pour le lien
    const FEE = { GREEN: ['Frais faibles', 'ok'], WARNING: ['Frais élevés', 'warn'], RED: ['Frais très élevés', 'down'] };
    const quoteAmount = h('input', { type: 'number', min: '0', step: 'any', inputmode: 'decimal', value: '10' });
    const arrow = (key) => String(key || '').split('>').join(' → ');
    const sum = (legs, k) => (legs || []).reduce((acc, l) => acc + Number(l[k] || 0), 0);
    function routeRow(r, rt, isDefault) {
      const pick = h('input', { type: 'radio', name: 'tio-route' });
      pick.checked = String(r.walletId) === String(st.walletId) && rt.routeKey === st.route;
      pick.addEventListener('change', () => { st.walletId = String(r.walletId); st.route = rt.routeKey; st.defaultRoute = r.quote.routeKey; syncTrade(); });
      const fee = FEE[rt.feeTestLevel];
      const detail = 'frais ' + F.pct(sum(rt.legs, 'feePct'), 2) + ' · spread ' + F.pct(sum(rt.legs, 'spreadPct'), 2) + ' · slippage ' + F.pct(sum(rt.legs, 'slippagePct'), 2);
      return h('label', { class: 'route' }, pick,
        h('span', { class: 'grow' }, h('span', { class: 'num' }, arrow(rt.routeKey)), isDefault ? ui.chip('Par défaut', 'accent') : null,
          h('span', { class: 'muted small route-sub' }, detail + (rt.status === 'BELOW_MIN' ? ' · sous le minimum de l\'exchange' : '')),
          rt.warnings && rt.warnings.length ? h('span', { class: 'muted small route-sub' }, rt.warnings.join(' · ')) : null),
        h('span', { class: 'num' }, F.pct(Number(rt.totalCostPct), 2)), fee ? ui.chip(fee[0], fee[1]) : null);
    }
    function quoteCard(r) {
      const q = r.quote;
      const name = r.exchange || r.walletName;
      const sub = r.walletName && r.walletName.toLowerCase() !== String(name).toLowerCase() ? h('span', { class: 'muted small' }, r.walletName) : null;
      const usable = q && q.status !== 'NO_PATH' && (q.routes || []).length;
      const head = h('div', { class: 'row' }, h('strong', null, name), sub,
        !q ? ui.chip('Indisponible', 'unknown') : q.status === 'NO_PATH' ? ui.chip('Aucun chemin', 'down') : null);
      if (!usable) return h('div', { class: 'card stack' }, head, h('div', { class: 'note' }, r.error || (q && q.warning) || 'Aucun chemin d\'achat'));
      const routes = q.routes.slice().sort((x, y) => Number(x.totalCostPct) - Number(y.totalCostPct));
      const def = routes.find((x) => x.routeKey === q.routeKey) || routes[0];
      const others = routes.filter((x) => x !== def);
      const more = others.length ? h('details', { class: 'routes-more' }, h('summary', null, 'Autres routes (' + others.length + ')'),
        h('div', { class: 'stack' }, others.map((x) => routeRow(r, x, false)))) : null;
      if (more && String(r.walletId) === String(st.walletId) && others.some((x) => x.routeKey === st.route)) more.open = true;
      return h('div', { class: 'card stack' }, head, routeRow(r, def, true), more);
    }
    async function loadQuotes() {
      const v = Number(quoteAmount.value);
      if (!(v > 0)) { TIO.toast('Indiquez un montant positif', true); return; }
      if (!st.asset) { TIO.toast('Choisissez d\'abord un actif (étape 2)', true); return; }
      st.quoted = false; syncTrade();
      TIO.mount(quotes, h('div', { class: 'note' }, 'Calcul des frais sur chaque exchange…'));
      try {
        const list = await TIO.get('/api/rainbow-live/onboarding/path-quotes' + TIO.qs({ asset: st.asset, amount: v }));
        if (!list.length) { TIO.mount(quotes, h('div', { class: 'note' }, 'Aucun exchange relié : enregistrez d\'abord une clé de lecture à l\'étape 1.')); return; }
        const usable = list.filter((r) => r.quote && r.quote.status === 'OK');
        const kept = list.find((r) => String(r.walletId) === String(st.walletId) && r.quote && (r.quote.routes || []).some((x) => x.routeKey === st.route));
        if (kept) { st.defaultRoute = kept.quote.routeKey; } else {
          const cheapest = usable.slice().sort((x, y) => Number(x.quote.totalCostPct) - Number(y.quote.totalCostPct))[0];
          st.walletId = cheapest ? String(cheapest.walletId) : null;
          st.route = cheapest ? cheapest.quote.routeKey : null;
          st.defaultRoute = st.route;
        }
        st.amount = String(v);
        st.quoted = !!st.route;
        syncTrade();
        TIO.mount(quotes, [...list.map(quoteCard), h('div', { class: 'note' }, 'Coût = frais + demi-spread + slippage, en % du montant. La route la moins chère est présélectionnée ; vous pouvez en choisir une autre, mais un seul exchange et une seule route par actif.')]);
      } catch (e) { TIO.mount(quotes, ''); TIO.toast(e.message, true); }
    }
    // grisé tant que les frais ne sont pas calculés pour l'actif et le montant courants et qu'aucune route n'est sélectionnée
    const tradeBtn = h('button', { class: 'btn btn--primary', type: 'button', onclick: () => { if (!ready()) return; done(2); } }, 'Trader avec cette route');
    tradeBtn.disabled = true;
    const ready = () => !!(st.quoted && st.walletId && st.route);
    const syncTrade = () => { tradeBtn.disabled = !ready(); };
    quoteAmount.addEventListener('input', () => { st.quoted = false; syncTrade(); });
    step(3, 'Chemin d\'achat et frais', [h('div', { class: 'note' }, 'Fee Test : coût réel de l\'achat de l\'actif sur chaque exchange relié, pour choisir où le trader.'),
      ui.field('Montant d\'un achat ($)', quoteAmount, 'Les frais dépendent du montant (minimum de l\'exchange, slippage).'),
      h('div', { class: 'row' }, h('button', { class: 'btn', type: 'button', onclick: loadQuotes }, 'Calculer les frais')), quotes,
      h('div', { class: 'row' }, tradeBtn)]);

    // 3. profil
    const desc = h('div', { class: 'note' }, PROFILES[1].desc);
    step(4, 'Choisir un profil', [ui.segmented(PROFILES.map((p) => ({ value: p.value, label: p.label })), st.profile, (v) => { st.profile = v; desc.textContent = PROFILES.find((p) => p.value === v).desc; }, 'Profil'), desc,
      h('div', { class: 'note' }, 'Vous pourrez ajuster la bande, la puissance et le biais dans les réglages.'), next(3)]);

    // 4. montant + confirmation
    const amount = h('input', { type: 'number', min: '0', step: 'any', inputmode: 'decimal', placeholder: 'ex. 10' });
    steps[2].addEventListener('toggle', () => { if (!steps[2].open && st.amount && !amount.value) amount.value = st.amount; });
    const result = h('div', { class: 'stack' });
    const create = h('button', { class: 'btn btn--primary', type: 'button', onclick: async () => {
      const v = Number(amount.value);
      if (!(v > 0)) { TIO.toast('Indiquez un montant de base positif', true); return; }
      if (!st.asset || !st.walletId) { TIO.toast('Choisissez un actif et un exchange (étapes 2 et 3)', true); return; }
      create.disabled = true;
      try {
        await TIO.put('/api/user/preferences/profile', { profile: st.profile });
        const presets = await TIO.get('/api/rainbow-live/presets' + TIO.qs({ asset: st.asset }));
        const preset = presets.find((p) => p.enabled) || presets[0];
        if (!preset) throw new Error('Aucune stratégie disponible pour ' + st.asset + ' : contactez l\'administrateur.');
        const r = await TIO.post('/api/rainbow-live/bindings', { assetSymbol: st.asset, presetId: preset.id, walletId: Number(st.walletId), baseAmount: v,
          preferredRoute: st.route && st.route !== st.defaultRoute ? st.route : undefined });
        const check = r && r.check;
        steps[4].dataset.done = 'true';
        TIO.mount(result, h('div', { class: 'banner' }, h('strong', null, st.asset + ' est suivi. '), 'Vous recevrez une décision chaque jour ; ',
          'l\'exécution réelle reste désactivée, vous l\'activez vous-même dans Actifs gérés.'),
          check ? h('div', { class: 'row' }, ui.statusChip(check.status), check.message && check.message !== 'OK' ? h('span', { class: 'muted small' }, check.message) : null) : null,
          h('div', { class: 'row' }, h('a', { class: 'btn btn--primary', href: TIO.href('/app/assets') }, 'Voir mes actifs')));
      } catch (e) { TIO.toast(e.message, true); } finally { create.disabled = false; }
    } }, 'Créer mon suivi');
    step(5, 'Montant et confirmation', [ui.field('Montant de base par jour ($)', amount, 'Avant modulation par la bande, la puissance et le biais.'),
      h('div', { class: 'banner banner--warn' }, 'Rien n\'est acheté à cette étape : le suivi calcule des décisions. L\'exécution réelle est désactivée par défaut.'), h('div', { class: 'row' }, create), result]);

    go(0);
    TIO.mount(root, ui.pageHeader('Démarrage', 'Cinq étapes pour recevoir votre première décision.'), ui.demoBanner(), h('div', { class: 'stack' }, steps));
  });
})(window);
