/* Ajouter un actif : 1 actif → 2 où acheter (frais de tous les exchanges comparés d'un coup, un seul choix exchange + route) → 3 montant de base par jour. Le profil déjà paramétré s'applique tel quel. L'exécution réelle reste désactivée. */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, F = TIO.fmt;
  const NAMES = { OKX: 'OKX', KRAKEN: 'Kraken', REVOLUT_X: 'Revolut X' };
  const FEE = { GREEN: ['Frais faibles', 'ok'], WARNING: ['Frais élevés', 'warn'], RED: ['Frais très élevés', 'down'] };
  /** Montant sur lequel les exchanges sont comparés : même base pour tous, indépendante du montant journalier. */
  const COMPARE_USD = 1000;
  const AMOUNTS = [10, 25, 50, 100];
  const sum = (legs, k) => (legs || []).reduce((acc, l) => acc + Number(l[k] || 0), 0);
  const arrow = (key) => String(key || '').split('>').join(' → ');
  const nameOf = (code, fallback) => NAMES[code] || fallback || code;

  TIO.start(function (root) {
    const st = { asset: null, baseAmount: 10, walletId: null, exchange: null, route: null, defaultRoute: null, list: [], lite: [], validated: false, validating: false };
    const assetBox = h('div', { class: 'asset-pick', role: 'group', 'aria-label': 'Actif' });
    const cmp = h('div', { class: 'stack' });
    const result = h('div', { class: 'stack' });
    const amount = h('input', { type: 'number', min: '0', step: 'any', inputmode: 'decimal', value: String(st.baseAmount) });
    const create = h('button', { class: 'btn btn--primary', type: 'button' }, 'Ajouter');
    const amountChips = h('div', { class: 'row' });

    /** Routes qui existent pour le wallet choisi : celles chiffrées après validation des frais, sinon celles du catalogue. */
    const routesOf = (walletId) => {
      if (st.validated) { const r = st.list.find((x) => String(x.walletId) === String(walletId)); return r && r.quote ? (r.quote.routes || []).map((x) => x.routeKey) : []; }
      const r = st.lite.find((x) => String(x.walletId) === String(walletId)); return r ? r.routes.map((x) => x.routeKey) : [];
    };
    const routeOk = () => !!(st.walletId && st.route && routesOf(st.walletId).includes(st.route));
    const ready = () => !!(st.asset && routeOk() && Number(amount.value) > 0);
    function sync() {
      create.disabled = !ready() || st.validating;
      create.textContent = st.asset && st.exchange ? 'Ajouter ' + st.asset + ' sur ' + nameOf(st.exchange) : 'Ajouter';
      Array.from(amountChips.children).forEach((b) => b.setAttribute('aria-pressed', String(Number(b.dataset.v) === Number(amount.value))));
    }

    // 1. actif
    function pickAsset(a) {
      st.asset = a; st.walletId = null; st.route = null; st.defaultRoute = null; st.exchange = null; st.validated = false; st.list = []; st.lite = []; TIO.clear(result);
      Array.from(assetBox.children).forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.asset === a)));
      loadRoutes();
    }
    TIO.safe(TIO.get('/api/rainbow-live/defaults'), null).then((defaults) => {
      const assets = (defaults && defaults.assets) || ['BTC'];
      if (defaults && Number(defaults.baseAmount) > 0) { st.baseAmount = Number(defaults.baseAmount); amount.value = String(st.baseAmount); }
      st.asset = assets[0];
      TIO.mount(assetBox, assets.map((a) => {
        const b = h('button', { class: 'asset-chip', type: 'button', 'aria-pressed': String(a === st.asset) }, ui.coin(a), h('span', null, a));
        b.dataset.asset = a;
        b.addEventListener('click', () => pickAsset(a));
        return b;
      }));
      loadRoutes();
    });

    // 2. comparateur : une ligne par exchange (meilleure route), autres routes dépliables, un seul bouton radio pour l'ensemble
    function select(r, routeKey, defaultRoute) { st.walletId = String(r.walletId); st.exchange = r.exchange; st.route = routeKey; st.defaultRoute = defaultRoute; TIO.clear(result); render(); }
    const routeLabel = (rt) => arrow(rt.routeKey) + ' · ' + ((rt.legs || []).length > 1 ? 'via passerelle · ' + rt.legs.length + ' ordres' : 'directe · 1 ordre');
    const liteLabel = (rt) => arrow(rt.routeKey) + ' · ' + (rt.direct ? 'directe' : 'via passerelle');
    const cost = (rt) => F.usd(Number(rt.totalCostPct) * COMPARE_USD / 100, 2);
    const detail = (rt) => 'frais ' + F.pct(sum(rt.legs, 'feePct'), 2) + ' · spread ' + F.pct(sum(rt.legs, 'spreadPct'), 2) + ' · slippage ' + F.pct(sum(rt.legs, 'slippagePct'), 2);
    function radio(r, key, defaultRoute) {
      const pick = h('input', { type: 'radio', name: 'tio-route' });
      pick.checked = String(r.walletId) === String(st.walletId) && key === st.route;
      pick.addEventListener('change', () => select(r, key, defaultRoute));
      return pick;
    }
    const logoOf = (name) => h('span', { class: 'xlogo', 'aria-hidden': 'true' }, String(name).slice(0, 1));
    const keyRow = (r, name) => h('div', { class: 'cmp-row', 'data-off': 'true' }, h('div', { class: 'cmp-main' }, logoOf(name),
        h('span', { class: 'grow' }, h('strong', null, name), h('span', { class: 'muted small' }, 'Sans clé d\'écriture, aucun ordre ne peut être passé.')),
        ui.chip('Écriture manquante', 'warn')),
      h('a', { class: 'btn', href: TIO.href('/app/exchanges?p=' + encodeURIComponent(r.exchange || '')) }, 'Ajouter la clé d\'écriture'));

    /** Avant validation des frais : exchanges et routes existantes (catalogue), route directe par défaut, aucun coût. */
    function liteRow(r) {
      const name = nameOf(r.exchange, r.walletName);
      if (!r.tradeKey) return keyRow(r, name);
      if (!r.routes.length) {
        return h('div', { class: 'cmp-row', 'data-off': 'true' }, h('div', { class: 'cmp-main' }, logoOf(name),
          h('span', { class: 'grow' }, h('strong', null, name), h('span', { class: 'muted small' }, r.error || 'Aucune route pour acheter ' + st.asset + ' sur cet exchange')),
          ui.chip('Aucune route', 'down')));
      }
      const def = r.routes.find((x) => x.routeKey === r.defaultRoute) || r.routes[0];
      const others = r.routes.filter((x) => x !== def);
      const sel = String(r.walletId) === String(st.walletId);
      const main = h('label', { class: 'cmp-main' }, radio(r, def.routeKey, def.routeKey), logoOf(name),
        h('span', { class: 'grow' }, h('strong', null, name), h('span', { class: 'muted small' }, liteLabel(def) + (def === r.routes[0] && def.direct ? ' (par défaut)' : ''))),
        h('span', { class: 'cmp-cost' }, h('span', { class: 'muted small' }, 'Frais non calculés')));
      const more = others.length ? h('details', { class: 'routes-more' }, h('summary', null, 'Autres routes (' + others.length + ')'),
        h('div', { class: 'stack' }, others.map((x) => h('label', { class: 'route' }, radio(r, x.routeKey, def.routeKey),
          h('span', { class: 'grow' }, h('span', { class: 'num' }, liteLabel(x))))))) : null;
      if (more && sel && others.some((x) => x.routeKey === st.route)) more.open = true;
      return h('div', { class: 'cmp-row', 'data-sel': String(sel) }, main, more);
    }

    /** Après validation des frais : coûts réels de chaque route, autres routes dépliables. */
    function row(r, cheapest) {
      const name = nameOf(r.exchange, r.walletName);
      const logo = logoOf(name);
      const q = r.quote;
      if (!r.tradeKey) return keyRow(r, name);
      const usable = q && q.status !== 'NO_PATH' && (q.routes || []).length;
      if (!usable) {
        return h('div', { class: 'cmp-row', 'data-off': 'true' }, h('div', { class: 'cmp-main' }, logo,
          h('span', { class: 'grow' }, h('strong', null, name), h('span', { class: 'muted small' }, r.error || (q && q.warning) || 'Aucun chemin d\'achat pour ' + st.asset)),
          ui.chip(!q ? 'Indisponible' : 'Aucun chemin', !q ? 'unknown' : 'down')));
      }
      const routes = q.routes.slice().sort((x, y) => Number(x.totalCostPct) - Number(y.totalCostPct));
      const def = routes.find((x) => x.routeKey === q.routeKey) || routes[0];
      const others = routes.filter((x) => x !== def);
      const fee = FEE[def.feeTestLevel];
      const sel = String(r.walletId) === String(st.walletId);
      const main = h('label', { class: 'cmp-main' }, radio(r, def.routeKey, q.routeKey), logo,
        h('span', { class: 'grow' }, h('strong', null, name), h('span', { class: 'muted small' }, routeLabel(def)),
          h('span', { class: 'row' }, def === cheapest ? ui.chip('Le moins cher', 'ok') : null, fee ? ui.chip(fee[0], fee[1]) : null)),
        h('span', { class: 'cmp-cost' }, h('span', { class: 'num' }, cost(def)), h('span', { class: 'muted small' }, detail(def))));
      const more = others.length ? h('details', { class: 'routes-more' }, h('summary', null, 'Autres routes (' + others.length + ')'),
        h('div', { class: 'stack' }, others.map((x) => h('label', { class: 'route' }, radio(r, x.routeKey, q.routeKey),
          h('span', { class: 'grow' }, h('span', { class: 'num' }, routeLabel(x)), h('span', { class: 'muted small route-sub' }, detail(x))),
          h('span', { class: 'num' }, cost(x)))))) : null;
      if (more && sel && others.some((x) => x.routeKey === st.route)) more.open = true;
      return h('div', { class: 'cmp-row', 'data-sel': String(sel) }, main, more);
    }

    const bestOf = (r) => r.quote.routes.slice().sort((x, y) => Number(x.totalCostPct) - Number(y.totalCostPct)).find((x) => x.routeKey === r.quote.routeKey) || r.quote.routes[0];
    /** Route la moins chère toutes routes et exchanges confondus (après validation des frais). */
    function cheapestOverall() {
      let best = null;
      st.list.filter((r) => r.tradeKey && r.quote && r.quote.status !== 'NO_PATH').forEach((r) => (r.quote.routes || []).forEach((rt) => {
        if (!best || Number(rt.totalCostPct) < Number(best.rt.totalCostPct)) best = { r, rt };
      }));
      return best;
    }
    const chosenCost = () => {
      const r = st.list.find((x) => String(x.walletId) === String(st.walletId));
      const rt = r && r.quote ? (r.quote.routes || []).find((x) => x.routeKey === st.route) : null;
      return rt ? Number(rt.totalCostPct) : null;
    };

    function render() {
      const lite = st.lite;
      const validate = h('button', { class: 'btn', type: 'button', disabled: st.validating || !lite.some((r) => r.tradeKey && r.routes.length), onclick: validateFees },
        st.validating ? 'Calcul en cours…' : st.validated ? 'Actualiser les frais' : 'Valider les frais');
      if (!(st.validated ? st.list : lite).length) {
        TIO.mount(cmp, ui.empty('Aucun exchange connecté', 'Reliez un exchange (clé de lecture et clé d\'écriture) pour comparer les frais.',
          h('a', { class: 'btn btn--primary', href: TIO.href('/app/exchanges') }, 'Connecter un exchange')));
        sync(); return;
      }
      const head = h('div', { class: 'row row--between' }, h('div', { class: 'note' }, st.validated
        ? 'Frais de ' + st.asset + ' sur vos exchanges, pour ' + F.usd(COMPARE_USD, 0) + ' investis (frais + demi-spread + slippage).'
        : 'Routes disponibles pour ' + st.asset + '. Les frais ne sont pas encore calculés : validez-les pour comparer les vraies routes.'), validate);
      let rows;
      if (st.validated) {
        const choices = st.list.filter((r) => r.tradeKey && r.quote && r.quote.status !== 'NO_PATH' && (r.quote.routes || []).length);
        const cheapest = choices.length > 1 ? choices.map(bestOf).sort((x, y) => Number(x.totalCostPct) - Number(y.totalCostPct))[0] : null;
        rows = st.list.slice().sort((a, b) => Number(!!b.tradeKey) - Number(!!a.tradeKey)).map((r) => row(r, cheapest));
      } else {
        rows = lite.slice().sort((a, b) => Number(!!b.tradeKey) - Number(!!a.tradeKey)).map(liteRow);
      }
      const hasChoice = (st.validated ? st.list.some((r) => r.tradeKey && r.quote && (r.quote.routes || []).length) : lite.some((r) => r.tradeKey && r.routes.length));
      const missing = !routeOk() && st.asset
        ? h('div', { class: 'banner banner--warn', role: 'alert' }, st.route
            ? 'La route ' + arrow(st.route) + ' n\'existe pas sur ' + nameOf(st.exchange) + ' : sélectionnez l\'un des choix disponibles.'
            : hasChoice ? 'Sélectionnez un exchange et une route.' : 'Aucune route n\'existe pour acheter ' + st.asset + ' sur vos exchanges : il n\'est pas possible de l\'ajouter pour le moment.')
        : null;
      TIO.mount(cmp, head, rows, missing, h('div', { class: 'row' }, h('a', { class: 'btn', href: TIO.href('/app/exchanges') }, '+ Connecter un autre exchange')));
      sync();
    }

    /** Affichage initial : exchanges et routes existantes, route directe présélectionnée ; aucun appel de marché, aucun frais. */
    async function loadRoutes() {
      if (!st.asset) return;
      const asset = st.asset;
      TIO.mount(cmp, h('div', { class: 'note' }, 'Recherche des routes chez vos exchanges…'));
      sync();
      try {
        const list = await TIO.get('/api/rainbow-live/onboarding/path-routes' + TIO.qs({ asset }));
        if (asset !== st.asset) return;
        st.lite = list;
        const first = list.find((r) => r.tradeKey && r.defaultRoute);
        if (first) { st.walletId = String(first.walletId); st.exchange = first.exchange; st.route = first.defaultRoute; st.defaultRoute = first.defaultRoute; }
        render();
      } catch (e) { TIO.mount(cmp, ''); TIO.toast(e.message, true); }
    }

    /** Bouton « Valider les frais » : chiffre les vraies routes de chaque exchange et rafraîchit « Où acheter ? ». */
    async function validateFees() {
      if (!st.asset || st.validating) return;
      const asset = st.asset;
      st.validating = true; render();
      try {
        const list = await TIO.get('/api/rainbow-live/onboarding/path-quotes' + TIO.qs({ asset, amount: COMPARE_USD }));
        if (asset !== st.asset) return;
        st.list = list; st.validated = true;
        const kept = list.find((r) => String(r.walletId) === String(st.walletId) && r.tradeKey && r.quote && (r.quote.routes || []).some((x) => x.routeKey === st.route));
        if (kept) { st.defaultRoute = kept.quote.routeKey; }
        else if (!st.route || !routeOk()) {
          const cheap = cheapestOverall();
          st.walletId = cheap ? String(cheap.r.walletId) : st.walletId; st.exchange = cheap ? cheap.r.exchange : st.exchange;
          st.route = cheap ? cheap.r.quote.routeKey : st.route; st.defaultRoute = cheap ? cheap.r.quote.routeKey : st.defaultRoute;
        }
      } catch (e) { TIO.toast(e.message, true); }
      finally { st.validating = false; render(); }
    }

    // 3. montant de base par jour
    AMOUNTS.forEach((v) => {
      const b = h('button', { class: 'btn', type: 'button', 'aria-pressed': 'false' }, F.usd(v, 0));
      b.dataset.v = String(v);
      b.addEventListener('click', () => { amount.value = String(v); sync(); });
      amountChips.appendChild(b);
    });
    amount.addEventListener('input', sync);

    async function doCreate() {
      const v = Number(amount.value);
      create.disabled = true;
      try {
        const r = await TIO.post('/api/rainbow-live/bindings', { assetSymbol: st.asset, walletId: Number(st.walletId), baseAmount: v,
          preferredRoute: st.route && st.route !== st.defaultRoute ? st.route : undefined });
        const check = r && r.check;
        TIO.mount(result, h('div', { class: 'banner' }, h('strong', null, st.asset + ' est suivi. '), 'Vous recevrez une décision chaque jour ; ',
          'l\'exécution réelle reste désactivée, vous l\'activez vous-même dans Actifs gérés.'),
          check ? h('div', { class: 'row' }, ui.statusChip(check.status), check.message && check.message !== 'OK' ? h('span', { class: 'muted small' }, check.message) : null) : null,
          h('div', { class: 'row' }, h('a', { class: 'btn btn--primary', href: TIO.href('/app/assets') }, 'Voir mes actifs')));
      } catch (e) { TIO.toast(e.message, true); } finally { sync(); }
    }

    create.addEventListener('click', async () => {
      if (!ready()) return;
      TIO.clear(result);
      if (st.validated) { doCreate(); return; }
      // Frais non validés : la route existe (vérifiée à la sélection) ; on la compare quand même à la moins chère avant d'ajouter.
      create.disabled = true;
      const before = st.walletId + '|' + st.route;
      await validateFees();
      if (!routeOk() || before !== st.walletId + '|' + st.route) return; // choix introuvable ou changé : l'utilisateur le voit et re-décide
      const mine = chosenCost(), best = cheapestOverall();
      if (mine === null || !best || mine <= Number(best.rt.totalCostPct) + 1e-9) { doCreate(); return; }
      const name = nameOf(best.r.exchange, best.r.walletName);
      TIO.mount(result, h('div', { class: 'banner banner--warn', role: 'alert' },
        h('strong', null, 'Une route moins chère existe. '),
        'Votre choix (' + nameOf(st.exchange) + ', ' + arrow(st.route) + ') coûte ' + cost({ totalCostPct: mine }) + ' pour ' + F.usd(COMPARE_USD, 0) + ' ; ' + name + ' (' + arrow(best.rt.routeKey) + ') coûte ' + cost(best.rt) + '.'),
        h('div', { class: 'row' },
          h('button', { class: 'btn btn--primary', type: 'button', onclick: () => select(best.r, best.rt.routeKey, best.r.quote.routeKey) }, 'Choisir la moins chère'),
          h('button', { class: 'btn', type: 'button', onclick: () => { TIO.clear(result); doCreate(); } }, 'Confirmer mon choix')));
    });

    TIO.mount(root, ui.pageHeader('Ajouter un actif', 'Choisissez l\'actif, où l\'acheter et le montant.'), ui.demoBanner(),
      h('div', { class: 'stack' },
        ui.card('1 · Actif', assetBox),
        ui.card('2 · Où acheter ?', cmp),
        ui.card('3 · Montant de base par jour', ui.field('Montant investi chaque jour ($)', amount, 'Avant modulation par la bande, la puissance et le biais.'), amountChips),
        h('div', { class: 'row' }, create), result));
    sync();
  });
})(window);
