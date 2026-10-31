/* Actifs gérés : les actifs que TradeIO gère pour l'utilisateur, groupés par provider (exchange).
 * Ligne = actif (position et valeur LIVE, PnL, dernière décision) ; le détail replié porte les réglages du lien. */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, F = TIO.fmt;
  const BIND = '/api/rainbow-live/bindings';
  let openId = null; // lien dont le détail est ouvert (un seul à la fois, conservé après rafraîchissement)

  async function load() {
    const [bindings, live, perf, hold, presets] = await Promise.all([
      TIO.get(BIND),
      TIO.safe(TIO.get('/api/rainbow-live/live-wallet'), []),
      TIO.safe(TIO.get('/api/portfolio/asset-performance'), []),
      TIO.safe(TIO.get('/api/portfolio/holdings'), null),
      TIO.safe(TIO.get('/api/rainbow-live/presets'), [])]);
    const liveBy = Object.fromEntries(live.map((l) => [l.assetSymbol, l]));
    const perfBy = Object.fromEntries(perf.map((p) => [p.assetSymbol, p]));
    const presetBase = Object.fromEntries(presets.map((p) => [p.id, p.globals && p.globals.baseAmount]));
    const lines = Object.fromEntries(((hold && hold.assets) || []).map((l) => [l.assetSymbol, l]));
    const count = {};
    bindings.forEach((b) => { count[b.assetSymbol] = (count[b.assetSymbol] || 0) + 1; });
    const items = bindings.map((b) => {
      const line = lines[b.assetSymbol];
      const part = line && (line.parts || []).find((x) => x.walletId === b.walletId);
      const pos = part ? { quantity: part.quantity, value: part.value } : (line && !(line.parts || []).length ? { quantity: line.quantity, value: line.value } : null);
      return { b, live: liveBy[b.assetSymbol] || null, perf: perfBy[b.assetSymbol] || null, pos, presetBase: presetBase[b.presetId], shared: count[b.assetSymbol] > 1 };
    });
    // groupes par provider, du plus gros au plus petit
    const groups = new Map();
    items.forEach((it) => {
      const key = it.b.exchange || 'Autre';
      if (!groups.has(key)) groups.set(key, { name: key, items: [], total: 0 });
      const gr = groups.get(key);
      gr.items.push(it);
      gr.total += (it.pos && TIO.isNum(it.pos.value)) ? it.pos.value : 0;
    });
    return [...groups.values()].sort((a, b) => b.total - a.total);
  }

  function decisionChip(live) {
    const s = live && live.snapshot;
    if (!s) return ui.chip('Pas encore de passe', 'unknown');
    if (s.status && s.status !== 'OK') return ui.chip(s.status === 'UNAVAILABLE' ? 'Données indisponibles' : 'Non tradable', s.status === 'UNAVAILABLE' ? 'unknown' : 'down');
    if (s.liveActionType === 'BUY') return ui.chip('▲ Achat ' + F.usd(s.liveActionAmountUsdc), 'ok');
    if (s.liveActionType === 'SELL') return ui.chip('▽ Vente ' + F.qty(s.liveActionQuantity), 'down');
    if (s.liveActionType === 'BLOCKED') return ui.chip('Bloqué · ' + TIO.blockLabel(s.blockReason), 'warn');
    return ui.chip('Rien à faire', 'plain');
  }
  /** Zone de confirmation en ligne (aucune boîte de dialogue du navigateur). */
  function confirmRow(message, okLabel, onOk, onCancel) {
    return h('div', { class: 'banner banner--warn stack', role: 'alertdialog', 'aria-label': message },
      h('div', null, message),
      h('div', { class: 'row' }, h('button', { class: 'btn btn--primary', type: 'button', onclick: onOk }, okLabel), h('button', { class: 'btn', type: 'button', onclick: onCancel }, 'Annuler')));
  }
  const pnlText = (p) => (p && TIO.isNum(p.pnlPct) ? (p.pnlPct > 0 ? '▲ ' : p.pnlPct < 0 ? '▽ ' : '') + F.signedPct(p.pnlPct) : F.dash);

  /** Réglages du lien (replié) : montant de base, exécution réelle, premier ordre, part vendable (Expert). */
  function settingsPanel(item, refresh) {
    const b = item.b, p = item.perf, sym = b.assetSymbol;
    const zone = h('div', { class: 'stack' });
    const call = async (fn, okMsg) => {
      try { await fn(); TIO.toast(okMsg); refresh(); } catch (e) { TIO.toast(e.message, true); }
    };
    const sw = ui.switchEl({ checked: b.executionEnabled, label: 'Exécution réelle ' + sym, onChange: (on, input) => {
      TIO.clear(zone);
      if (!on) { call(() => TIO.api('PUT', BIND + '/' + b.id + '/execution', { enabled: false }), 'Exécution désactivée pour ' + sym); return; }
      input.checked = false;
      zone.appendChild(confirmRow('Activer l\'exécution réelle pour ' + sym + ' ? Des ordres réels pourront partir sur ' + (b.exchange || 'l\'exchange') + ' (dans les plafonds).', 'Activer',
        () => call(() => TIO.api('PUT', BIND + '/' + b.id + '/execution', { enabled: true }), 'Exécution activée pour ' + sym), () => TIO.clear(zone)));
    } });

    const amount = h('input', { type: 'number', min: '0', step: 'any', value: b.baseAmount ?? '', placeholder: TIO.isNum(item.presetBase) ? String(item.presetBase) : 'preset', inputmode: 'decimal' });
    const saveAmount = h('button', { class: 'btn', type: 'button', onclick: () => {
      const v = amount.value === '' ? null : Number(amount.value);
      call(() => TIO.api('PUT', BIND + '/' + b.id, v === null ? { resetBaseAmount: true } : { baseAmount: v }), 'Montant de base enregistré');
    } }, 'Enregistrer');
    const baseDesc = b.baseAmount == null
      ? 'Par jour avant modulation. Actuellement : ' + (TIO.isNum(item.presetBase) ? F.usd(item.presetBase, 0) : 'valeur du preset') + ' (preset). Vide = preset.'
      : 'Par jour avant modulation. Vide = valeur du preset' + (TIO.isNum(item.presetBase) ? ' (' + F.usd(item.presetBase, 0) + ').' : '.');

    const rows = [
      h('div', { class: 'stats' },
        ui.stat('Prix moyen', p && TIO.isNum(p.avgBuyPrice) ? F.price(p.avgBuyPrice) : F.dash),
        ui.stat('Cours', p && TIO.isNum(p.price) ? F.price(p.price) : F.dash),
        ui.stat('PnL', h('span', { class: TIO.sign(p && p.pnl) }, p && TIO.isNum(p.pnl) ? F.signedUsd(p.pnl, 0) : F.dash))),
      item.shared ? ui.note('Prix moyen et PnL cumulent tous les exchanges où ' + sym + ' est détenu.') : null,
      h('div', { class: 'muted small' }, TIO.labels.check[b.tradability] || ''),
      ui.setting('Montant de base', baseDesc, h('div', { class: 'row' }, h('div', { class: 'field', style: { width: '110px' } }, amount), saveAmount)),
      ui.setting('Exécution réelle', b.executionEnabled ? 'Activée : des ordres réels peuvent partir.' : 'Désactivée : simulation, rien n\'est envoyé.', sw.el),
    ];
    if (b.executionEnabled && b.firstLiveArmed && !b.firstLiveConfirmed) {
      rows.push(h('div', { class: 'banner banner--warn stack' }, h('div', null, 'Premier ordre réel armé : votre confirmation est requise avant tout envoi pour ' + sym + '.'),
        h('button', { class: 'btn btn--primary', type: 'button', onclick: () => {
          TIO.clear(zone);
          zone.appendChild(confirmRow('Confirmer le premier ordre réel pour ' + sym + ' ? Cette étape autorise l\'envoi du tout premier ordre.', 'Confirmer',
            () => call(() => TIO.api('POST', BIND + '/' + b.id + '/confirm-first-live'), 'Premier ordre confirmé'), () => TIO.clear(zone)));
        } }, 'Confirmer le premier ordre')));
    } else if (b.executionEnabled && !b.firstLiveArmed) {
      rows.push(ui.note('En attente d\'armement du premier ordre par l\'administrateur.'));
    }
    rows.push(zone);
    if (TIO.modeAtLeast('expert')) {
      const bag = ui.slider({ min: 0, max: 100, step: 5, value: b.bagPercent, label: 'Part vendable de la position' });
      const out = h('span', { class: 'num' }, F.pct(b.bagPercent, 0));
      bag.input.addEventListener('input', () => { out.textContent = F.pct(Number(bag.input.value), 0); });
      rows.push(h('div', { class: 'stack' },
        h('div', { class: 'row row--between' }, h('span', { class: 'lbl' }, 'Part vendable de la position'), out), bag.el,
        h('div', { class: 'desc muted small' }, 'Limite ce que TradeIO peut vendre de votre ' + sym + ' : au maximum ce pourcentage de la quantité détenue. 100 % (défaut) = toute la position est vendable ; 50 % = la moitié reste toujours intouchable. Ne limite pas les achats.'),
        h('div', { class: 'row' }, h('button', { class: 'btn', type: 'button', onclick: () => call(() => TIO.api('PUT', BIND + '/' + b.id, { bagPercent: Number(bag.input.value) }), 'Part vendable enregistrée') }, 'Enregistrer'))));
    }
    rows.push(h('div', { class: 'row' }, h('a', { class: 'btn', href: TIO.href('/app/assets/' + encodeURIComponent(sym)) }, 'Voir la fiche')));
    return h('div', { class: 'asset-detail stack' }, rows);
  }

  function assetRow(item, refresh) {
    const b = item.b, sym = b.assetSymbol, pos = item.pos, p = item.perf;
    const exec = b.executionEnabled ? ui.chip(b.firstLiveArmed ? 'Réel' : 'Réel · en attente', b.firstLiveArmed ? 'ok' : 'warn') : null;
    const head = h('summary', { class: 'asset-row' },
      ui.coin(sym),
      h('div', { class: 'asset-id' }, h('div', { class: 'num asset-sym' }, sym),
        h('div', { class: 'muted small' }, pos ? F.qty(pos.quantity) + ' ' + sym : 'Aucune position')),
      h('div', { class: 'asset-val' }, h('div', { class: 'num' }, pos && TIO.isNum(pos.value) ? F.usd(pos.value, 0) : F.dash),
        h('div', { class: 'num small ' + TIO.sign(p && p.pnlPct) }, pnlText(p))),
      h('div', { class: 'asset-state' }, decisionChip(item.live), exec),
      h('span', { class: 'asset-caret', 'aria-hidden': 'true' }, '›'));
    const det = h('details', { class: 'asset-item' }, head, settingsPanel(item, refresh));
    det.open = openId === b.id;
    det.addEventListener('toggle', () => {
      if (det.open) {
        openId = b.id;
        det.closest('.asset-list-root').querySelectorAll('details.asset-item[open]').forEach((d) => { if (d !== det) d.open = false; });
      } else if (openId === b.id) openId = null;
    });
    return det;
  }

  function providerGroup(gr, refresh) {
    return h('section', { class: 'card provider', 'aria-label': gr.name },
      h('header', { class: 'provider-head' },
        h('span', { class: 'provider-name' }, gr.name),
        h('span', { class: 'muted small' }, gr.items.length + (gr.items.length > 1 ? ' actifs' : ' actif')),
        h('span', { class: 'num provider-total' }, gr.total > 0 ? F.usd(gr.total, 0) : F.dash)),
      h('div', { class: 'asset-list' }, gr.items.map((it) => assetRow(it, refresh))));
  }

  TIO.start(function (root) {
    const list = h('div', { class: 'stack asset-list-root' });
    const refresh = async () => {
      const y = g.scrollY;
      await ui.load(list, load, (groups) => {
      if (!groups.length) return ui.empty('Aucun actif géré', 'Reliez un actif à un exchange pour que TradeIO le gère.', h('a', { class: 'btn btn--primary', href: TIO.href('/app/onboarding') }, 'Démarrer'));
      return groups.map((gr) => providerGroup(gr, refresh));
      });
      g.scrollTo(0, y);
    };
    refresh();
    TIO.mount(root, ui.pageHeader('Actifs gérés', 'Les actifs que TradeIO gère pour vous, par exchange.'), ui.demoBanner(), list,
      h('div', { class: 'row' }, h('a', { class: 'btn', href: TIO.href('/app/onboarding') }, '+ Ajouter un actif')));
  });
})(window);
