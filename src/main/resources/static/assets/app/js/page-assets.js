/* Mes actifs : une carte par lien (statut, position, valeur, PnL, dernière décision, montant de base, interrupteur d'exécution). */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, F = TIO.fmt;
  const BIND = '/api/rainbow-live/bindings';

  async function load() {
    const [bindings, live, perf] = await Promise.all([TIO.get(BIND), TIO.safe(TIO.get('/api/rainbow-live/live-wallet'), []), TIO.safe(TIO.get('/api/portfolio/assets'), [])]);
    const liveBy = Object.fromEntries(live.map((l) => [l.assetSymbol, l]));
    const perfBy = Object.fromEntries(perf.map((p) => [p.assetSymbol, p]));
    return bindings.map((b) => ({ b, live: liveBy[b.assetSymbol] || null, perf: perfBy[b.assetSymbol] || null }));
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

  function assetCard(item, refresh) {
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

    const amount = h('input', { type: 'number', min: '0', step: 'any', value: b.baseAmount ?? '', placeholder: 'preset', inputmode: 'decimal' });
    const saveAmount = h('button', { class: 'btn', type: 'button', onclick: () => {
      const v = amount.value === '' ? null : Number(amount.value);
      call(() => TIO.api('PUT', BIND + '/' + b.id, v === null ? { resetBaseAmount: true } : { baseAmount: v }), 'Montant de base enregistré');
    } }, 'Enregistrer');

    const rows = [
      h('div', { class: 'row' }, ui.coin(sym), h('div', { class: 'grow' }, h('div', { class: 'num value-md' }, sym), h('div', { class: 'muted small' }, (b.walletName || '') + (b.exchange ? ' · ' + b.exchange : ''))),
        ui.statusChip(b.tradability, null)),
      h('div', { class: 'stats' },
        ui.stat('Position', F.qty(p && p.quantity)), ui.stat('Valeur', F.usd(p && p.value, 0)),
        ui.stat('PnL', h('span', { class: TIO.sign(p && p.pnl) }, p ? (p.pnl > 0 ? '▲ ' : p.pnl < 0 ? '▽ ' : '') + F.signedUsd(p.pnl, 0) + ' (' + F.signedPct(p.pnlPct) + ')' : F.dash))),
      h('div', { class: 'row' }, h('span', { class: 'muted small' }, 'Dernière décision'), decisionChip(item.live)),
      h('div', { class: 'muted small' }, TIO.labels.check[b.tradability] || ''),
      ui.setting('Montant de base', 'Par jour avant modulation. Vide = valeur du preset.', h('div', { class: 'row' }, h('div', { class: 'field', style: { width: '110px' } }, amount), saveAmount)),
      ui.setting('Exécution réelle', b.executionEnabled ? 'Activée : des ordres réels peuvent partir.' : 'Désactivée : simulation, rien n\'est envoyé.', sw.el),
      zone,
    ];
    if (b.executionEnabled && b.firstLiveArmed && !b.firstLiveConfirmed) {
      const z = h('div', { class: 'banner banner--warn stack' }, h('div', null, 'Premier ordre réel armé : votre confirmation est requise avant tout envoi pour ' + sym + '.'),
        h('button', { class: 'btn btn--primary', type: 'button', onclick: () => {
          TIO.clear(zone);
          zone.appendChild(confirmRow('Confirmer le premier ordre réel pour ' + sym + ' ? Cette étape autorise l\'envoi du tout premier ordre.', 'Confirmer',
            () => call(() => TIO.api('POST', BIND + '/' + b.id + '/confirm-first-live'), 'Premier ordre confirmé'), () => TIO.clear(zone)));
        } }, 'Confirmer le premier ordre'));
      rows.splice(rows.length - 1, 0, z);
    } else if (b.executionEnabled && !b.firstLiveArmed) {
      rows.splice(rows.length - 1, 0, ui.note('En attente d\'armement du premier ordre par l\'administrateur.'));
    }
    if (TIO.modeAtLeast('advanced')) {
      const bag = ui.slider({ min: 0, max: 100, step: 5, value: b.bagPercent, label: 'Part de la position offerte à la vente' });
      const out = h('span', { class: 'num' }, F.pct(b.bagPercent, 0));
      bag.input.addEventListener('input', () => { out.textContent = F.pct(Number(bag.input.value), 0); });
      rows.splice(rows.length - 1, 0, h('div', { class: 'stack' }, h('div', { class: 'row row--between' }, h('span', { class: 'lbl muted' }, 'Part de la position offerte'), out), bag.el,
        h('div', { class: 'row' }, h('button', { class: 'btn', type: 'button', onclick: () => call(() => TIO.api('PUT', BIND + '/' + b.id, { bagPercent: Number(bag.input.value) }), 'Part enregistrée') }, 'Enregistrer'))));
    }
    rows.push(h('a', { class: 'btn', href: TIO.href('/app/assets/' + encodeURIComponent(sym)) }, 'Voir la fiche'));
    return h('section', { class: 'card', 'aria-label': sym }, rows);
  }

  TIO.start(function (root) {
    const list = h('div', { class: 'grid grid--3' });
    const refresh = () => ui.load(list, load, (items) => {
      if (!items.length) return ui.empty('Aucun actif suivi', 'Reliez un actif à un exchange pour démarrer.', h('a', { class: 'btn btn--primary', href: TIO.href('/app/onboarding') }, 'Démarrer'));
      return items.map((it) => assetCard(it, refresh));
    });
    refresh();
    TIO.mount(root, ui.pageHeader('Mes actifs'), ui.demoBanner(), list,
      h('div', { class: 'row' }, h('a', { class: 'btn', href: TIO.href('/app/onboarding') }, 'Ajouter un actif')));
  });
})(window);
