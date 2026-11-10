/* Exchanges : liste des exchanges de l'utilisateur et gestion de leurs clés (lecture seule / écriture). Les secrets ne sont jamais renvoyés par l'API. */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui;
  const NAMES = { OKX: 'OKX', KRAKEN: 'Kraken', REVOLUT_X: 'Revolut X' };
  const nameOf = (code) => NAMES[code] || code;

  TIO.start(function (root) {
    const st = { data: null, sel: new URLSearchParams(g.location.search).get('p'), adding: false, replace: {}, confirm: false };
    const list = h('div', { class: 'xch-list' });
    const detail = h('div', { class: 'stack' });
    const view = () => ((st.data && st.data.exchanges) || []).find((e) => e.provider === st.sel) || null;
    const supported = () => ((st.data && st.data.supported) || []).find((s) => s.provider === st.sel) || null;

    async function load() {
      st.data = await TIO.get('/api/exchanges');
      const ex = st.data.exchanges;
      if (!st.sel || (!view() && !st.adding)) st.sel = ex.length ? ex[0].provider : null;
      render();
    }
    const reload = () => load().catch((e) => TIO.toast(e.message, true));
    const select = (code) => { st.sel = code; st.adding = false; st.replace = {}; st.confirm = false; render(); };

    function keyChips(ex) {
      return h('span', { class: 'row' },
        ex.read.configured ? ui.chip('Lecture', 'ok') : ui.chip('Lecture manquante', 'warn'),
        ex.trade.configured ? ui.chip('Écriture', 'ok') : ui.chip('Écriture manquante', 'warn'));
    }
    function renderList() {
      const ex = st.data.exchanges;
      const free = st.data.supported.filter((s) => !ex.some((e) => e.provider === s.provider));
      const items = ex.map((e) => {
        const b = h('button', { class: 'xitem', type: 'button', 'aria-pressed': String(e.provider === st.sel && !st.adding) },
          h('span', { class: 'xlogo', 'aria-hidden': 'true' }, nameOf(e.provider).slice(0, 1)),
          h('span', { class: 'grow' }, h('strong', null, nameOf(e.provider)), keyChips(e),
            h('span', { class: 'muted small' }, e.linkedAssets.length ? e.linkedAssets.join(' · ') + ' liés' : 'Aucun actif lié')));
        b.addEventListener('click', () => select(e.provider));
        return b;
      });
      const add = h('button', { class: 'btn xadd', type: 'button', 'aria-expanded': String(st.adding), disabled: !free.length || null,
        onclick: () => { st.adding = !st.adding; render(); } }, '+ Ajouter un exchange');
      const picks = st.adding ? h('div', { class: 'row' }, free.map((s) => {
        const b = h('button', { class: 'btn btn--primary', type: 'button' }, nameOf(s.provider));
        b.addEventListener('click', () => { st.sel = s.provider; st.adding = false; st.replace = {}; st.confirm = false; render(); });
        return b;
      })) : null;
      TIO.mount(list, items, add, picks, !free.length && !st.adding ? h('div', { class: 'note' }, 'Tous les exchanges pris en charge sont ajoutés.') : null);
    }

    // formulaire de saisie d'une clé (lecture ou écriture)
    function keyForm(scope, needsPassphrase) {
      const key = h('input', { type: 'password', autocomplete: 'off', spellcheck: 'false' });
      const secret = h('input', { type: 'password', autocomplete: 'off', spellcheck: 'false' });
      const pass = h('input', { type: 'password', autocomplete: 'off', spellcheck: 'false' });
      const submit = h('button', { class: 'btn btn--primary', type: 'button', onclick: async () => {
        submit.disabled = true;
        try {
          await TIO.post('/api/credentials/' + (scope === 'READ' ? 'read' : 'trade'),
            { provider: st.sel, apiKey: key.value, secretKey: secret.value, passphrase: needsPassphrase ? pass.value : null, enabled: true });
          TIO.toast(scope === 'READ' ? 'Clé de lecture enregistrée' : 'Clé d\'écriture enregistrée');
          st.replace[scope] = false;
          await reload();
        } catch (e) { TIO.toast(e.message, true); submit.disabled = false; }
      } }, 'Enregistrer');
      return h('div', { class: 'stack' }, ui.field('Clé API', key), ui.field('Clé secrète', secret),
        needsPassphrase ? ui.field('Passphrase', pass) : null, h('div', { class: 'row' }, submit));
    }
    // exchange à clé générée par le serveur : (1) générer la paire, (2) enregistrer la clé publique chez l'exchange, (3) coller la clé API reçue
    function keyPairForm(scope) {
      const box = h('div', { class: 'stack' });
      const base = '/api/credentials/keypair/' + st.sel + '/' + scope;
      const fail = (e) => TIO.toast(e.message, true);
      function drawEmpty() {
        TIO.mount(box, h('div', { class: 'note' }, 'La clé privée est générée et chiffrée par TradeIO, elle ne sort jamais du serveur. Vous recevez la clé publique à enregistrer chez l\'exchange.'),
          h('div', { class: 'row' }, h('button', { class: 'btn btn--primary', type: 'button', onclick: async () => {
            try { draw(await TIO.post(base)); } catch (e) { fail(e); }
          } }, 'Générer la clé')));
      }
      function draw(p) {
        const pub = h('textarea', { readonly: 'readonly', rows: '5', spellcheck: 'false', class: 'ipbox' });
        pub.value = p.publicKeyPem;
        const apiKey = h('input', { type: 'password', autocomplete: 'off', spellcheck: 'false' });
        const ok = h('button', { class: 'btn btn--primary', type: 'button', onclick: async () => {
          ok.disabled = true;
          try {
            await TIO.post(base + '/promote', { apiKey: apiKey.value });
            TIO.toast(scope === 'READ' ? 'Clé de lecture enregistrée' : 'Clé d\'écriture enregistrée');
            st.replace[scope] = false;
            await reload();
          } catch (e) { fail(e); ok.disabled = false; }
        } }, 'Valider et enregistrer');
        TIO.mount(box,
          h('div', { class: 'note' }, p.hint),
          ui.field('Clé publique à enregistrer chez l\'exchange', pub, 'Empreinte ' + p.fingerprint.slice(0, 16) + '… · valable jusqu\'au ' + new Date(p.expiresAt).toLocaleString()),
          h('div', { class: 'row' }, h('button', { class: 'btn', type: 'button', onclick: async () => {
            try { await g.navigator.clipboard.writeText(p.publicKeyPem); TIO.toast('Clé publique copiée'); } catch (e) { TIO.toast('Copie impossible : sélectionnez le texte', true); }
          } }, 'Copier'),
          h('button', { class: 'btn', type: 'button', onclick: async () => { try { draw(await TIO.post(base)); } catch (e) { fail(e); } } }, 'Régénérer'),
          h('button', { class: 'btn', type: 'button', onclick: async () => { try { await TIO.api('DELETE', base); drawEmpty(); } catch (e) { fail(e); } } }, 'Annuler')),
          ui.field('Clé API fournie par l\'exchange', apiKey), h('div', { class: 'row' }, ok));
      }
      TIO.get(base).then(draw).catch((e) => { if (e.status === 404) drawEmpty(); else fail(e); });
      return box;
    }
    const perm = (text, warn) => h('div', { class: 'perm' + (warn ? ' perm--warn' : '') }, h('span', { 'aria-hidden': 'true' }, warn ? '⚠' : '✓'), text);

    function keyCard(scope, ex, needsPassphrase) {
      const read = scope === 'READ';
      const state = ex ? (read ? ex.read : ex.trade) : { configured: false };
      const title = read ? 'Clé lecture seule' : 'Clé écriture';
      const intro = read ? 'Consulter soldes, ordres et historique. Aucune action possible avec cette clé.'
        : 'Passe les ordres d\'achat et de vente. Obligatoire pour exécuter.';
      const perms = read ? [perm('Consulter les soldes'), perm('Consulter ordres et historique')]
        : [perm('Créer et annuler des ordres'), perm('Retraits : à laisser désactivés', true)];
      const asideChip = state.configured ? ui.chip('Active', state.enabled ? 'ok' : 'warn') : ui.chip('Manquante', 'warn');
      const body = [h('div', { class: 'note' }, intro)];
      if (state.configured && !st.replace[scope]) {
        body.push(h('div', { class: 'masked' }, state.hint ? 'Clé ••••••••' + state.hint : 'Clé enregistrée (chiffrée, jamais affichée)'), ...perms);
        const row = h('div', { class: 'row' });
        if (read) {
          const test = h('button', { class: 'btn', type: 'button', onclick: async () => {
            test.disabled = true;
            try { const r = await TIO.post('/api/exchanges/' + st.sel + '/test'); TIO.toast(r.ok ? 'Connexion OK' : r.message, !r.ok); }
            catch (e) { TIO.toast(e.message, true); } finally { test.disabled = false; }
          } }, 'Tester');
          row.appendChild(test);
        }
        row.appendChild(h('button', { class: 'btn', type: 'button', onclick: () => { st.replace[scope] = true; render(); } }, 'Remplacer'));
        row.appendChild(h('button', { class: 'btn btn--danger', type: 'button', onclick: async () => {
          try { await TIO.api('DELETE', '/api/exchanges/' + st.sel + '/keys/' + scope); TIO.toast('Clé supprimée'); await reload(); } catch (e) { TIO.toast(e.message, true); }
        } }, 'Supprimer la clé'));
        body.push(row);
      } else {
        const sup = supported();
        body.push(...perms, sup && sup.keyPair ? keyPairForm(scope) : keyForm(scope, needsPassphrase));
        if (state.configured) body.push(h('div', { class: 'row' }, h('button', { class: 'btn', type: 'button', onclick: () => { st.replace[scope] = false; render(); } }, 'Annuler')));
      }
      return ui.card(title, ...body, ui.cardOpts({ aside: asideChip }));
    }

    function renderDetail() {
      const ex = view(), sup = supported();
      if (!ex && !sup) {
        TIO.mount(detail, ui.empty('Aucun exchange', 'Ajoutez un exchange pour relier vos clés. C\'est facultatif tant que vous ne gérez pas d\'actif.'));
        return;
      }
      const needsPassphrase = ex ? ex.needsPassphrase : sup.needsPassphrase;
      const head = h('div', { class: 'row' }, h('span', { class: 'xlogo', 'aria-hidden': 'true' }, nameOf(st.sel).slice(0, 1)), h('h2', { class: 'xtitle' }, nameOf(st.sel)),
        ex ? (ex.read.configured && ex.trade.configured ? ui.chip('Prêt à exécuter', 'ok') : ex.trade.configured ? ui.chip('Lecture manquante', 'warn') : ui.chip('Lecture seule : exécution impossible', 'warn')) : ui.chip('Nouvel exchange', 'accent'));
      const keys = h('div', { class: 'xkeys' }, keyCard('READ', ex, needsPassphrase), keyCard('TRADE', ex, needsPassphrase));
      let danger = null;
      if (ex) {
        const linked = ex.linkedAssets.length ? 'Les actifs ' + ex.linkedAssets.join(', ') + ' liés seront archivés (historique conservé).' : 'Aucun actif lié.';
        danger = h('section', { class: 'card card--danger' },
          h('div', { class: 'note' }, 'Supprimer retire les deux clés. ' + linked),
          h('div', { class: 'row' }, h('button', { class: 'btn btn--danger', type: 'button', onclick: async () => {
            if (!st.confirm) { st.confirm = true; render(); return; }
            try { await TIO.api('DELETE', '/api/exchanges/' + st.sel); TIO.toast('Exchange supprimé'); st.sel = null; st.confirm = false; await reload(); } catch (e) { TIO.toast(e.message, true); }
          } }, st.confirm ? 'Confirmer la suppression' : 'Supprimer cet exchange')));
      }
      const ip = st.data.serverIp ? h('section', { class: 'card' }, h('div', { class: 'card-title' }, 'Adresse IP de TradeIO'),
        h('div', { class: 'note' }, 'Certains exchanges imposent une restriction d\'IP à la création de la clé API : ajoutez cette adresse à la liste autorisée.'),
        h('div', { class: 'row' }, h('code', { class: 'num ipbox' }, st.data.serverIp),
          h('button', { class: 'btn', type: 'button', onclick: async () => {
            try { await g.navigator.clipboard.writeText(st.data.serverIp); TIO.toast('Adresse copiée'); } catch (e) { TIO.toast('Copie impossible : sélectionnez l\'adresse', true); }
          } }, 'Copier'))) : null;
      TIO.mount(detail, head, ip, keys, danger);
    }
    function render() { renderList(); renderDetail(); }

    TIO.mount(root, ui.pageHeader('Exchanges', 'Clés de lecture et d\'écriture par exchange.'), ui.demoBanner(),
      h('div', { class: 'xch' }, list, detail));
    TIO.mount(list, ui.loading ? ui.loading(2) : '');
    load().catch((e) => TIO.mount(list, ui.error(e, load)));
  });
})(window);
