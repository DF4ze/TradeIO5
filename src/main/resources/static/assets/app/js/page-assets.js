/* Actifs gérés : les actifs que TradeIO gère pour l'utilisateur, groupés par provider (exchange).
 * Ligne = actif (position et valeur LIVE, PnL, dernière décision) ; le détail replié porte les réglages du lien. */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, F = TIO.fmt;
  const BIND = '/api/rainbow-live/bindings';
  let openId = null; // lien dont le détail est ouvert (un seul à la fois, conservé après rafraîchissement)

  async function load() {
    const iso = (o) => new Date(Date.now() + o * 86400000).toISOString().slice(0, 10);
    const [bindings, live, perf, hold, presets, decisions] = await Promise.all([
      TIO.get(BIND),
      TIO.safe(TIO.get('/api/rainbow-live/live-wallet'), []),
      TIO.safe(TIO.get('/api/portfolio/asset-performance'), []),
      TIO.safe(TIO.get('/api/portfolio/holdings'), null),
      TIO.safe(TIO.get('/api/rainbow-live/presets'), []),
      TIO.safe(TIO.get('/api/rainbow-live/decisions' + TIO.qs({ from: iso(-30), to: iso(0) })), [])]);
    // état de la stratégie par lien : celui de la décision la plus récente
    const stateBy = {};
    decisions.filter((d) => d.strategyState).sort((x, y) => (x.day < y.day ? -1 : 1)).forEach((d) => { stateBy[d.bindingId] = d.strategyState; });
    const liveBy = Object.fromEntries(live.map((l) => [l.assetSymbol, l]));
    const perfBy = Object.fromEntries(perf.map((p) => [p.assetSymbol, p]));
    const presetBase = Object.fromEntries(presets.map((p) => [p.id, p.globals && p.globals.baseAmount]));
    const lines = Object.fromEntries(((hold && hold.assets) || []).map((l) => [l.assetSymbol, l]));
    const count = {};
    bindings.forEach((b) => { count[b.assetSymbol] = (count[b.assetSymbol] || 0) + 1; });
    /** Mesure du lien : prix moyen et PnL propres au provider (wallet) du lien ; repli sur l'agrégat si le détail manque. */
    const perfOf = (agg, walletId) => {
      const part = agg && (agg.parts || []).find((x) => x.walletId === walletId);
      return part ? { ...agg, quantity: part.quantity, avgBuyPrice: part.avgBuyPrice, pnl: part.pnl, pnlPct: part.pnlPct, realizedPnl: part.realizedPnl, realizedPct: part.realizedPct, totalPnl: part.totalPnl, fees: part.fees, sells: part.sells, incomplete: part.incomplete } : agg || null;
    };
    const items = bindings.map((b) => {
      const line = lines[b.assetSymbol];
      const part = line && (line.parts || []).find((x) => x.walletId === b.walletId);
      const pos = part ? { quantity: part.quantity, value: part.value } : (line && !(line.parts || []).length ? { quantity: line.quantity, value: line.value } : null);
      return { b, live: liveBy[b.assetSymbol] || null, state: TIO.stateText(stateBy[b.id]), perf: perfOf(perfBy[b.assetSymbol], b.walletId), pos, presetBase: presetBase[b.presetId], shared: count[b.assetSymbol] > 1 };
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
  const EM = TIO.execMode, confirmRow = EM.confirmRow, FEE_LEVEL = EM.FEE_LEVEL, routeText = EM.routeText;
  const pnlText = (p) => (p && TIO.isNum(p.pnlPct) ? (p.pnlPct > 0 ? '▲ ' : p.pnlPct < 0 ? '▽ ' : '') + F.signedPct(p.pnlPct) : F.dash);

  /** Chemin d'achat du lien : route, Fee Test réalisé (coût %, niveau, montant coté, date) et « Recalculer ». */
  function pathPanel(item, call) {
    const b = item.b;
    const route = routeText(b);
    const lvl = FEE_LEVEL[b.feeTestLevel];
    const amount = b.baseAmount ?? item.presetBase;
    const recompute = h('button', { class: 'btn', type: 'button', onclick: () => {
      recompute.disabled = true;
      call(() => TIO.api('POST', BIND + '/' + b.id + '/fee-test' + (TIO.isNum(b.baseAmount) ? '' : TIO.qs({ amount })), null), 'Fee Test recalculé');
    } }, 'Recalculer');
    const detail = TIO.isNum(b.feeTestPct)
      ? [h('span', { class: 'num' }, F.pct(Number(b.feeTestPct), 2)), lvl ? ui.chip(lvl[0], lvl[1]) : null,
        h('span', { class: 'muted small' }, 'pour ' + F.usd(Number(b.feeTestAmount), 0) + ' · ' + F.dateTime(b.feeTestAt))]
      : [h('span', { class: 'muted' }, 'Pas encore calculé')];
    return h('div', { class: 'stack' },
      h('div', { class: 'row row--between' }, h('span', { class: 'lbl' }, 'Chemin d\'achat'), h('span', { class: 'num' }, route || F.dash)),
      h('div', { class: 'row' }, h('span', { class: 'lbl' }, 'Fee Test'), detail, TIO.isNum(amount) ? recompute : null),
      b.preferredRoute ? h('div', { class: 'muted small' }, 'Route choisie par vous (imposée à l\'exécution).') : null);
  }

  /** Réglages du lien (replié) : montant de base, mode d'exécution, premier ordre, part vendable et achat minimum (Avancé). */
  function settingsPanel(item, refresh) {
    const b = item.b, p = item.perf, sym = b.assetSymbol;
    const zone = h('div', { class: 'stack' });
    const call = async (fn, okMsg) => {
      try { await fn(); TIO.toast(okMsg); refresh(); } catch (e) { TIO.toast(e.message, true); }
    };
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
        ui.stat('Latent', ui.profit(p && p.pnl)),
        ui.stat('Réalisé', ui.profit(p && p.realizedPnl)),
        ui.stat('Total', ui.profit(p && p.totalPnl))),
      p && p.fees > 0 ? h('div', { class: 'muted small' }, 'Frais cumulés ' + F.usd(p.fees, 2) + ' · ' + p.sells + ' vente(s)') : null,
      p && p.incomplete ? ui.note('Historique incomplet : des ventes dépassent les achats enregistrés (dépôt ou transfert non suivi).') : null,
      h('div', { class: 'muted small' }, TIO.labels.check[b.tradability] || ''),
      pathPanel(item, call),
      ui.setting('Montant de base', baseDesc, h('div', { class: 'row' }, h('div', { class: 'field', style: { width: '110px' } }, amount), saveAmount)),
      ui.setting('Exécution', EM.describe(EM.modeOf(b)), EM.control(item, refresh)),
    ];
    if (b.executionEnabled && !b.firstLiveConfirmed) {
      rows.push(h('div', { class: 'banner banner--warn stack' }, h('div', null, 'Votre confirmation du premier ordre réel est requise avant tout envoi pour ' + sym + '.'),
        h('button', { class: 'btn btn--primary', type: 'button', onclick: () => {
          TIO.clear(zone);
          zone.appendChild(confirmRow('Confirmer le premier ordre réel pour ' + sym + ' ? Cette étape autorise l\'envoi du tout premier ordre.', 'Confirmer',
            () => call(() => TIO.api('POST', BIND + '/' + b.id + '/confirm-first-live'), 'Premier ordre confirmé'), () => TIO.clear(zone)));
        } }, 'Confirmer le premier ordre')));
    }
    rows.push(zone);
    if (TIO.modeAtLeast('advanced')) rows.push(EM.limits(item, refresh));
    const removeZone = h('div', { class: 'stack' });
    rows.push(h('div', { class: 'row' }, h('a', { class: 'btn', href: TIO.href('/app/assets/' + encodeURIComponent(sym) + '?wallet=' + encodeURIComponent(b.walletId)) }, 'Voir la fiche'),
      h('button', { class: 'btn btn--danger', type: 'button', onclick: () => {
        TIO.clear(removeZone);
        removeZone.appendChild(confirmRow('Retirer ' + sym + (b.exchange ? ' de ' + b.exchange : '') + ' ? TradeIO cesse de le gérer : plus aucun ordre ne partira. Vos actifs sur l\'exchange ne sont pas touchés et l\'historique est conservé.', 'Retirer',
          () => call(async () => { await TIO.api('DELETE', BIND + '/' + b.id); openId = null; }, sym + ' retiré des actifs gérés'), () => TIO.clear(removeZone)));
      } }, 'Retirer cet actif')));
    rows.push(removeZone);
    return h('div', { class: 'asset-detail stack' }, rows);
  }

  function assetRow(item, refresh) {
    const b = item.b, sym = b.assetSymbol, pos = item.pos, p = item.perf;
    const exec = EM.chip(b);
    const st = item.state;
    const feeLvl = b.feeTestLevel === 'WARNING' || b.feeTestLevel === 'RED' ? FEE_LEVEL[b.feeTestLevel] : null;
    const feeChip = feeLvl ? ui.chip(feeLvl[0] + (TIO.isNum(b.feeTestPct) ? ' · ' + F.pct(Number(b.feeTestPct), 2) : ''), feeLvl[1]) : null;
    const head = h('summary', { class: 'asset-row' },
      ui.coin(sym),
      h('div', { class: 'asset-id' }, h('div', { class: 'asset-name' }, h('span', { class: 'num asset-sym' }, sym), h('span', { class: 'asset-caret', 'aria-hidden': 'true' }, '›')),
        h('div', { class: 'muted small' }, pos ? F.qty(pos.quantity) + ' ' + sym : 'Aucune position')),
      h('div', { class: 'asset-strat' }, st
        ? [h('div', { class: 'asset-strat-label' }, h('span', { class: 'jr-dot jr-dot--' + st.tone, 'aria-hidden': 'true' }), st.label), h('div', { class: 'muted small' }, st.detail)]
        : h('span', { class: 'muted small' }, 'Pas d\'état de stratégie')),
      h('div', { class: 'asset-state' }, feeChip, decisionChip(item.live), exec),
      h('div', { class: 'asset-val' }, h('div', { class: 'num' }, pos && TIO.isNum(pos.value) ? F.usd(pos.value, 0) : F.dash),
        h('div', { class: 'num small ' + TIO.sign(p && p.pnlPct) }, pnlText(p))));
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
      }, true);
      g.scrollTo(0, y);
    };
    refresh();
    TIO.mount(root, ui.pageHeader('Actifs gérés', 'Les actifs que TradeIO gère pour vous, par exchange.'), ui.demoBanner(),
      h('div', { class: 'row' }, h('a', { class: 'btn btn--primary', href: TIO.href('/app/onboarding') }, '+ Ajouter un actif')), list,
      h('div', { class: 'row' }, h('a', { class: 'btn', href: TIO.href('/app/onboarding') }, '+ Ajouter un actif')));
  });
})(window);
