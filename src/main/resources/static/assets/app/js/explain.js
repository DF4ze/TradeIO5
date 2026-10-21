/* Explication d'une décision : frise Signal → Modulation → Plafonds → Plan → Exécution → Résultat et « Pourquoi ce montant ? ». */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, F = TIO.fmt;

  const eps = 0.005;
  const stage = (title, text, kind) => h('div', { class: 'st ' + (kind || '') }, h('div', { class: 't' }, title), h('div', { class: 'd' }, text));
  const calc = (label, value, hint) => h('div', { class: 'row row--between' }, h('div', null, h('div', null, label), hint ? h('div', { class: 'muted small' }, hint) : null), h('div', { class: 'num' }, value));

  /** Frise Signal → Modulation → Plafonds → Plan → Exécution → Résultat pour une décision (et le plan du même jour). */
  function pipeline(sym, d, plan) {
    const m = TIO.parseMod(d.modulation);
    const pa = plan && (plan.assets || []).find((a) => a.assetSymbol === sym);
    const steps = (pa && pa.steps) || [];
    const buy = m && m.signalBuyUsd > 0, sell = m && m.signalSellQty > 0;
    const final = m ? (TIO.isNum(m.finalAmountUsd) ? m.finalAmountUsd : m.modulatedBuyUsd) : null;
    const reduced = m && buy && TIO.isNum(final) && final < m.modulatedBuyUsd - eps;

    const sSignal = m ? stage('Signal', buy ? 'Achat de base ' + F.usd(m.signalBuyUsd) : sell ? 'Vente ' + F.qty(m.signalSellQty) + ' ' + sym : 'Aucun signal aujourd\'hui', buy || sell ? 'ok' : '') : stage('Signal', F.dash, 'unknown');
    const sMod = m ? (m.exposureStatus === 'UNKNOWN'
      ? stage('Modulation', 'Exposition inconnue : bande ignorée · × ' + F.num(m.sideBuy, 2) + ' = ' + F.usd(m.modulatedBuyUsd), 'warn')
      : stage('Modulation', '× ' + F.num(m.sideBuy, 2) + ' (puissance) × ' + F.num(m.bandBuy, 2) + ' (bande) = ' + F.usd(m.modulatedBuyUsd) + (m.productCapped ? ' · produit plafonné' : ''), 'ok')) : stage('Modulation', F.dash, 'unknown');
    let capText = 'Aucun plafond atteint', capKind = 'ok';
    if (d.blockReason) { capText = TIO.blockLabel(d.blockReason); capKind = TIO.statusKind(d.blockReason) === 'unknown' ? 'warn' : TIO.statusKind(d.blockReason); }
    else if (m && m.hardCap) { capText = 'Plafond de bande atteint · ' + F.usd(final); capKind = 'warn'; }
    else if (reduced) { capText = 'Réduit à ' + F.usd(final) + ' (cash, position ou plafonds)'; capKind = 'warn'; }
    const sCap = m || d.blockReason ? stage('Plafonds', capText, capKind) : stage('Plafonds', F.dash, 'unknown');
    const sPlan = pa ? stage('Plan', (TIO.labels.plan[plan.status] || plan.status) + ' · ' + (pa.outcome || '') + (TIO.isNum(Number(pa.costPct)) && pa.costPct !== null ? ' · coût ' + F.pct(Number(pa.costPct), 2) : '') + (pa.blockReason ? ' · ' + TIO.blockLabel(pa.blockReason) : ''),
      pa.outcome === 'OK' ? 'ok' : pa.outcome === 'BLOCKED' ? 'down' : pa.outcome === 'DISABLED' ? 'warn' : '') : stage('Plan', plan ? 'Aucune ligne pour cet actif' : F.dash, 'unknown');
    const filled = steps.filter((s) => s.status === 'FILLED').length;
    const bad = steps.some((s) => ['REJECTED', 'CANCELED', 'UNKNOWN'].includes(s.status));
    const sExec = plan ? (steps.length
      ? stage('Exécution', filled + '/' + steps.length + ' ordre(s) exécuté(s)' + (plan.mode ? ' · ' + plan.mode : ''), bad ? 'down' : filled === steps.length ? 'ok' : 'warn')
      : stage('Exécution', plan.executionBlockReason ? TIO.blockLabel(plan.executionBlockReason) : 'Aucun ordre à envoyer', plan.executionBlockReason ? 'warn' : '')) : stage('Exécution', F.dash, 'unknown');
    const qty = steps.reduce((t, s) => t + (Number(s.filledSz) || 0), 0), fee = steps.reduce((t, s) => t + (Number(s.feeAmount) || 0), 0);
    const carryOut = pa && Number(pa.carryOutUsd) > 0 ? Number(pa.carryOutUsd) : 0;
    const sRes = filled ? stage('Résultat', F.qty(qty) + ' ' + sym + ' reçus · frais ' + F.usd(fee) + (carryOut ? ' · reporté ' + F.usd(carryOut) : ''), 'ok')
      : carryOut ? stage('Résultat', 'Reporté ' + F.usd(carryOut) + ' au jour suivant', 'warn') : stage('Résultat', F.dash, 'unknown');
    return h('div', { class: 'pipe' }, [sSignal, sMod, sCap, sPlan, sExec, sRes]);
  }

  /** « Pourquoi ce montant ? » : Simple = une phrase ; Avancé = chaîne de calcul ; Expert = journal brut. */
  function why(sym, d) {
    const m = TIO.parseMod(d.modulation);
    if (!m) return ui.empty('Pas de détail pour ce jour', 'Le détail du calcul est conservé à partir de la première passe.');
    const final = TIO.isNum(m.finalAmountUsd) ? m.finalAmountUsd : m.modulatedBuyUsd;
    const out = [];
    out.push(h('p', null, m.signalBuyUsd > 0
      ? 'Le signal du jour proposait ' + F.usd(m.signalBuyUsd) + '. Après vos réglages et les plafonds : ' + F.usd(final) + '.'
      : m.signalSellQty > 0 ? 'Le signal du jour proposait de vendre ' + F.qty(m.signalSellQty) + ' ' + sym + '.' : 'Le signal du jour ne proposait aucun achat.'));
    if (TIO.modeAtLeast('advanced') && m.signalBuyUsd > 0) {
      out.push(h('div', { class: 'stack' },
        calc('Signal du jour', F.usd(m.signalBuyUsd)),
        calc('× Puissance et biais', F.num(m.sideBuy, 2), 'puissance ' + F.num(m.power, 2) + ' · biais ' + F.num(m.bias, 2)),
        calc('× Facteur de bande', F.num(m.bandBuy, 2), m.exposureStatus === 'UNKNOWN' ? 'exposition inconnue : bande ignorée' : 'exposition ' + F.pct(m.exposureBefore, 0) + ' · bande ' + F.pct(m.bandMin, 0) + ' – ' + F.pct(m.bandMax, 0)),
        calc('= Montant modulé', F.usd(m.modulatedBuyUsd), m.productCapped ? 'produit plafonné à ' + F.num(m.productMax, 1) + '×' : null),
        calc('Après plafonds', F.usd(final), m.hardCap ? 'plafond de bande atteint' : 'cash, position, plafonds'),
        TIO.isNum(m.exposureBefore) && TIO.isNum(m.exposureAfter) ? calc('Exposition', F.pct(m.exposureBefore, 1) + ' → ' + F.pct(m.exposureAfter, 1)) : null));
    }
    if (TIO.modeAtLeast('expert')) {
      out.push(h('details', null, h('summary', null, 'Journal brut'), h('table', { class: 'tbl' }, h('tbody', null,
        Object.entries(m).map(([k, v]) => h('tr', null, h('td', { class: 'muted' }, k), h('td', null, String(v))))))));
    }
    return out;
  }

  TIO.explain = { pipeline, why };
})(window);
