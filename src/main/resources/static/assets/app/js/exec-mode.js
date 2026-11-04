/* Mode d'exécution d'un lien (actif × exchange) : désactivé / simulation / actif, puis part vendable et achat minimum.
 * Partagé par « Actifs gérés » et « Réglages stratégiques ». Aucune boîte de dialogue du navigateur : confirmation en ligne. */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, F = TIO.fmt;
  const BIND = '/api/rainbow-live/bindings';
  const MODES = [{ value: 'DISABLED', label: 'Désactivé' }, { value: 'SIMULATION', label: 'Simulation' }, { value: 'ACTIVE', label: 'Actif' }];
  const FEE_LEVEL = { GREEN: ['Frais faibles', 'ok'], WARNING: ['Frais élevés', 'warn'], RED: ['Frais très élevés', 'down'] };
  const routeText = (b) => String(b.feeTestRoute || b.preferredRoute || b.routeKey || '').split('>').join(' → ');
  const modeOf = (b) => b.executionMode || (b.executionEnabled ? 'ACTIVE' : 'DISABLED');

  /** Zone de confirmation en ligne. */
  function confirmRow(message, okLabel, onOk, onCancel) {
    return h('div', { class: 'banner banner--warn stack', role: 'alertdialog', 'aria-label': message },
      h('div', null, message),
      h('div', { class: 'row' }, h('button', { class: 'btn btn--primary', type: 'button', onclick: onOk }, okLabel), h('button', { class: 'btn', type: 'button', onclick: onCancel }, 'Annuler')));
  }

  /** Puce d'état d'exécution : Réel, Réel · à confirmer, Simulation ou Désactivé. */
  function chip(b) {
    const m = modeOf(b);
    if (m === 'ACTIVE') return ui.chip(b.firstLiveConfirmed ? 'Réel' : 'Réel · à confirmer', b.firstLiveConfirmed ? 'ok' : 'warn');
    if (m === 'SIMULATION') return ui.chip('Simulation', 'sim');
    return h('span', { class: 'chip chip--plain' }, h('span', { class: 'x-off', 'aria-hidden': 'true' }, '✕'), 'Désactivé');
  }

  const describe = (m) => (m === 'ACTIVE' ? 'Actif : des ordres réels peuvent partir.'
    : m === 'SIMULATION' ? 'Simulation : toute la chaîne est jouée et chiffrée (visible au Journal), rien n\'est envoyé.'
      : 'Désactivé : rien n\'est joué ni envoyé.');

  /**
   * Sélecteur Désactivé / Simulation / Actif d'un lien (item = { b, presetBase }). « Actif » passe par la validation en ligne
   * (Fee Test calculé au besoin, puis confirmation du 1ᵉʳ ordre réel) ; les deux autres s'appliquent tout de suite.
   * `refresh` recharge l'écran appelant (aussi après une annulation, pour remettre le sélecteur dans son état réel).
   */
  function control(item, refresh, zoneHost) {
    const b = item.b, sym = b.assetSymbol, current = modeOf(b);
    const zone = zoneHost || h('div', { class: 'stack' });
    const call = async (fn, okMsg) => {
      try { await fn(); TIO.toast(okMsg); refresh(); } catch (e) { TIO.toast(e.message, true); refresh(); }
    };
    async function activation() {
      zone.appendChild(h('div', { class: 'note' }, 'Calcul du chemin d\'achat et des frais…'));
      let cur = b;
      const amt = b.baseAmount ?? item.presetBase;
      if (!TIO.isNum(b.feeTestPct) && TIO.isNum(amt)) {
        try { cur = await TIO.api('POST', BIND + '/' + b.id + '/fee-test' + (TIO.isNum(b.baseAmount) ? '' : TIO.qs({ amount: amt }))); } catch (e) { cur = b; }
      }
      TIO.clear(zone);
      const lvl = FEE_LEVEL[cur.feeTestLevel];
      const path = TIO.isNum(cur.feeTestPct)
        ? 'Chemin d\'achat : ' + routeText(cur) + ' · Fee Test ' + F.pct(Number(cur.feeTestPct), 2) + (lvl ? ' (' + lvl[0].toLowerCase() + ')' : '') + ' pour ' + F.usd(Number(cur.feeTestAmount), 0) + '.'
        : 'Chemin d\'achat et Fee Test non calculés pour le moment.';
      const msg = 'Mettre ' + sym + ' sous la gestion de TradeIO sur ' + (b.exchange || 'l\'exchange') + ' ? ' + path
        + ' Des ordres réels pourront partir sur cet exchange (dans les plafonds). Vous validez ici le premier ordre réel.';
      zone.appendChild(confirmRow(msg, 'Mettre sous gestion',
        () => call(async () => {
          await TIO.api('PUT', BIND + '/' + b.id + '/execution', { mode: 'ACTIVE' });
          if (!b.firstLiveConfirmed) await TIO.api('POST', BIND + '/' + b.id + '/confirm-first-live');
        }, sym + ' est sous la gestion de TradeIO'), () => { TIO.clear(zone); refresh(); }));
    }
    const seg = ui.segmented(MODES, current, (v) => {
      TIO.clear(zone);
      if (v === current) return;
      if (v === 'ACTIVE') { activation(); return; }
      call(() => TIO.api('PUT', BIND + '/' + b.id + '/execution', { mode: v }), v === 'SIMULATION' ? sym + ' en simulation : rien ne sera envoyé' : 'Exécution désactivée pour ' + sym);
    }, 'Mode d\'exécution ' + sym);
    return zoneHost ? seg : h('div', { class: 'stack' }, seg, zone);
  }

  /** Version sur une ligne : part vendable (slider) + achat minimum, enregistrés dès qu'on lâche le curseur / quitte le champ. */
  function limitsInline(item, refresh) {
    const b = item.b, sym = b.assetSymbol;
    const out = h('span', { class: 'num' }, F.pct(b.bagPercent, 0));
    const save = async () => {
      const v = min.get();
      try {
        await TIO.api('PUT', BIND + '/' + b.id, Object.assign({ bagPercent: Number(bag.input.value) }, v === null ? { resetMinBuy: true } : { minBuyUsd: v }));
        TIO.toast('Réglages de ' + sym + ' enregistrés'); refresh();
      } catch (e) { TIO.toast(e.message, true); refresh(); }
    };
    const bag = ui.slider({ min: 0, max: 100, step: 5, value: b.bagPercent, label: 'Part vendable de la position ' + sym, onInput: (v) => { out.textContent = F.pct(v, 0); }, onChange: save });
    const min = ui.numberInput({ value: b.minBuyUsd, unit: '$', placeholder: 'aucun', label: 'Achat minimum ' + sym, onChange: save });
    return [h('div', { class: 'xbag', title: 'Part vendable de la position : au maximum ce % de la quantité détenue peut être vendu (100 % = tout). Ne limite pas les achats.' }, h('span', { class: 'small muted' }, 'Part vendable'), bag.el, out),
      h('div', { class: 'xmin', title: 'Un achat sous ce minimum est reporté et cumulé jusqu\'à l\'atteindre (vide = aucun)' }, h('span', { class: 'small muted' }, 'Achat min.'), min.el)];
  }

  /** Part vendable de la position (bag) et achat minimum d'un lien ; un seul « Enregistrer ». */
  function limits(item, refresh) {
    const b = item.b, sym = b.assetSymbol;
    const bag = ui.slider({ min: 0, max: 100, step: 5, value: b.bagPercent, label: 'Part vendable de la position ' + sym });
    const out = h('span', { class: 'num' }, F.pct(b.bagPercent, 0));
    bag.input.addEventListener('input', () => { out.textContent = F.pct(Number(bag.input.value), 0); });
    const min = h('input', { type: 'number', min: '0', step: 'any', value: b.minBuyUsd ?? '', placeholder: 'aucun', inputmode: 'decimal' });
    const save = h('button', { class: 'btn', type: 'button', onclick: async () => {
      const v = min.value === '' ? null : Number(min.value);
      try {
        await TIO.api('PUT', BIND + '/' + b.id, Object.assign({ bagPercent: Number(bag.input.value) }, v === null ? { resetMinBuy: true } : { minBuyUsd: v }));
        TIO.toast('Réglages de ' + sym + ' enregistrés'); refresh();
      } catch (e) { TIO.toast(e.message, true); }
    } }, 'Enregistrer');
    return h('div', { class: 'stack' },
      h('div', { class: 'row row--between' }, h('span', { class: 'lbl' }, 'Part vendable de la position'), out), bag.el,
      h('div', { class: 'desc muted small' }, 'Limite ce que TradeIO peut vendre de votre ' + sym + ' : au maximum ce pourcentage de la quantité détenue. 100 % (défaut) = toute la position est vendable ; 50 % = la moitié reste toujours intouchable. Ne limite pas les achats.'),
      ui.field('Achat minimum ($)', min, 'Un achat (mise du jour + montants déjà reportés) sous ce minimum n\'est pas passé : il est reporté et cumulé jusqu\'à l\'atteindre. Vide = aucun minimum propre (celui de l\'exchange s\'applique toujours).'),
      h('div', { class: 'row' }, save));
  }

  TIO.execMode = { MODES, FEE_LEVEL, routeText, modeOf, describe, confirmRow, chip, control, limits, limitsInline };
})(window);
