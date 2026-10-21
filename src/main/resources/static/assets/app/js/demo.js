/* TradeIO — mode démo (?demo=1) : jeu de données FICTIF, même forme que l'API, lecture seule, jamais d'appel réseau.
   Les écritures de réglages restent en mémoire du navigateur ; l'exécution (interrupteur, 1er ordre, clés) est refusée. */
(function (g) {
  'use strict';
  const TIO = g.TIO;
  if (!TIO.demo) return;

  const DAY = 86400000;
  const now = new Date();
  const todayUtc = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
  const iso = (offset) => new Date(todayUtc + offset * DAY).toISOString().slice(0, 10);
  function rng(seed) { let s = seed >>> 0; return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; }; }
  const r2 = (n) => Math.round(n * 100) / 100;

  const ASSETS = {
    BTC: { id: 1, price: 68400, qty: 0.0825, invested: 4900, avg: 59400, base: 25, ex: 'OKX', wallet: 1, action: 'BUY', amount: 31.4 },
    ETH: { id: 2, price: 2480, qty: 1.42, invested: 3300, avg: 2330, base: 15, ex: 'OKX', wallet: 1, action: 'NONE', amount: 0 },
    PAXG: { id: 3, price: 2650, qty: 0.75, invested: 1900, avg: 2480, base: 10, ex: 'KRAKEN', wallet: 2, action: 'BLOCKED', amount: 0 },
  };
  const bySymbol = (s) => ASSETS[String(s).toUpperCase()];
  const byId = (id) => Object.entries(ASSETS).find(([, a]) => a.id === Number(id));

  let prefs = { theme: 'DARK', displayMode: 'SIMPLE', startingProfile: 'BALANCED', alertsDisabled: [], caps: { maxOrderUsd: null, maxDayUsd: null, absoluteMaxOrderUsd: 30, absoluteMaxDayUsd: 100 }, warnings: [] };
  let modulation = { settings: { exposureMin: 30, exposureMax: 70, power: 1, bias: 0 }, limits: { minBandWidthPts: 10, powerMin: 0.5, powerMax: 2, biasMax: 0.5 } };

  function summary(period) {
    const n = { '7d': 7, '30d': 30, '90d': 90, '1y': 365, all: 200 }[period] || 30;
    const rand = rng(42);
    const points = [];
    let total = 11800;
    for (let i = n - 1; i >= 0; i--) {
      total *= 1 + (rand() - 0.46) * 0.018;
      const exposure = TIO.clamp(52 + Math.sin((n - i) / 9) * 9 + (rand() - 0.5) * 3, 25, 80);
      points.push({ day: iso(-i), total: r2(total), stable: r2(total * (1 - exposure / 100)), assets: r2(total * exposure / 100), exposurePct: r2(exposure) });
    }
    const first = points[0], last = points[points.length - 1];
    return { period, from: first.day, to: last.day, current: last, series: points,
      pnl: { amount: r2(last.total - first.total), pct: r2((last.total / first.total - 1) * 100), since: first.day }, autonomyDays: 142, flowsIncluded: false };
  }
  function portfolioAssets() {
    return Object.entries(ASSETS).map(([symbol, a]) => {
      const value = r2(a.qty * a.price), pnl = r2(value - a.invested);
      return { assetSymbol: symbol, quantity: a.qty, price: a.price, value, investedNet: a.invested, pnl, pnlPct: r2(pnl / a.invested * 100), avgBuyPrice: a.avg };
    });
  }
  const binding = ([symbol, a]) => ({ id: a.id, assetSymbol: symbol, presetId: 10 + a.id, presetName: 'Trend Mix ' + symbol, walletId: a.wallet, walletName: 'Wallet ' + a.ex,
    exchange: a.ex, bagPercent: 100, priority: a.id, baseAmount: a.base, tradability: symbol === 'PAXG' ? 'TRADABLE_VIA_INTERMEDIARY' : 'OK', quoteMember: 'USDC', routeKey: symbol + '-USDC',
    executionEnabled: symbol !== 'PAXG', firstLiveArmed: symbol !== 'PAXG', firstLiveConfirmed: symbol === 'BTC' });
  function modulationJournal(a, d) {
    const signal = a.base * (1 + (d % 4) * 0.35);
    const bandBuy = 0.8 + (d % 3) * 0.15;
    const product = r2(1 * bandBuy);
    return { signalBuyUsd: r2(signal), signalSellQty: 0, power: 1, bias: 0, sideBuy: 1, sideSell: 1, bandMin: 30, bandMax: 70, exposureStatus: 'OK', exposureBefore: r2(48 + (d % 7)),
      lHigh: 1, lLow: 1, bandBuy, bandSell: 1, productBuy: product, productSell: 1, productCapped: false, hardCap: false, marginPct: 10, sellBoostMax: 1.5, productMax: 3,
      modulatedBuyUsd: r2(signal * product), modulatedSellQty: 0, action: 'BUY', finalAmountUsd: r2(signal * product), finalQuantity: r2(signal * product / a.price * 1e6) / 1e6, exposureAfter: r2(49 + (d % 7)) };
  }
  function decisions() {
    const out = [];
    for (const [symbol, a] of Object.entries(ASSETS)) {
      for (let d = 0; d < 30; d++) {
        const k = (d * 7 + a.id * 3) % 10;
        const action = d === 0 ? a.action : k < 4 ? 'BUY' : k < 5 && symbol === 'PAXG' ? 'BLOCKED' : 'NONE';
        const mod = modulationJournal(a, d);
        const buy = action === 'BUY';
        out.push({ bindingId: a.id, assetSymbol: symbol, walletId: a.wallet, day: iso(-d), status: 'OK', action,
          amountUsdc: buy ? mod.finalAmountUsd : null, quantity: buy ? mod.finalQuantity : null,
          blockReason: action === 'BLOCKED' ? 'INSUFFICIENT_CASH' : null, modulation: JSON.stringify(Object.assign({}, mod, buy ? {} : { action, finalAmountUsd: 0, finalQuantity: 0 })),
          computedAt: new Date(todayUtc - d * DAY + 85500000).toISOString() });
      }
    }
    return out.sort((x, y) => (y.day < x.day ? -1 : y.day > x.day ? 1 : 0));
  }
  function plans() {
    const out = [];
    for (let d = 0; d < 12; d++) {
      const executed = d > 0;
      const assets = Object.entries(ASSETS).map(([symbol, a]) => {
        const k = (d * 7 + a.id * 3) % 10;
        const buy = (d === 0 ? a.action === 'BUY' : k < 4);
        const carry = symbol === 'ETH' && d % 5 === 2;
        return { assetSymbol: symbol, action: buy ? 'BUY' : 'NONE', outcome: buy ? 'OK' : 'NO_ACTION', blockReason: null, costPct: buy ? 0.18 : null, feeTestLevel: buy ? 'GREEN' : null, warning: null,
          walletId: a.wallet, carryInUsd: carry ? 4.2 : 0, carryOutUsd: symbol === 'ETH' && d % 5 === 1 ? 4.2 : 0,
          steps: buy ? [{ rank: 1, instId: symbol + '-USDC', side: 'BUY', ordType: 'market', sz: null, px: null, quoteAmount: a.base, clOrdId: 'demo' + d + symbol, feePct: 0.08, spreadPct: 0.02, slippagePct: 0.03,
            estimation: null, status: executed ? 'FILLED' : 'PLANNED', filledSz: executed ? r2(a.base / a.price * 1e6) / 1e6 : null, avgFillPx: executed ? a.price : null, feeAmount: executed ? 0.02 : null, feeCurrency: 'USDC',
            receivedAmount: null, receivedCurrency: symbol, realSlippagePct: executed ? 0.04 : null, lastError: null }] : [] };
      });
      out.push({ id: 100 - d, day: iso(-d), revision: 1, status: executed ? 'EXECUTED' : 'PLANNED', mode: 'DRY_RUN', totalCostPct: 0.18, feeTestLevel: 'GREEN', blockReason: null,
        createdAt: new Date(todayUtc - d * DAY + 85600000).toISOString(), expiresAt: new Date(todayUtc - d * DAY + 85600000 + 6 * 3600000).toISOString(),
        executionBlockReason: null, executionStartedAt: null, executionFinishedAt: null, assets });
    }
    return out;
  }
  function bands(id, period, simulated) {
    const entry = byId(id);
    if (!entry) throw new TIO.ApiError(404, 'Lien introuvable');
    const [symbol, a] = entry;
    const n = { '7d': 7, '30d': 30, '90d': 90, '1y': 365, all: 365 }[period] || 90;
    const rand = rng(7 + a.id);
    let close = a.price * 0.82;
    const pts = [], hist = [];
    for (let i = n - 1; i >= 0; i--) {
      close *= 1 + (rand() - 0.47) * 0.03;
      hist.push(close); const w = hist.slice(-20); const sma = w.reduce((a, b) => a + b, 0) / w.length, atr = sma * 0.035;
      const zone = Math.max(0, Math.min(5, Math.round(2 + (close - sma) / atr)));
      pts.push({ day: iso(-i), close: r2(close), sma: r2(sma), atr: r2(atr), boundDown2: r2(sma - 2 * atr), boundDown1: r2(sma - atr), boundUp1: r2(sma + atr), boundUp2: r2(sma + 2 * atr), boundUp3: r2(sma + 3 * atr),
        zone, activeSet: 'BULL', trendRegime: 'BULL' });
    }
    return { bindingId: a.id, assetSymbol: symbol, presetName: 'Trend Mix ' + symbol, simulated: !!simulated, points: pts };
  }
  function transactions(id) {
    const entry = byId(id);
    if (!entry) throw new TIO.ApiError(404, 'Lien introuvable');
    const [symbol, a] = entry;
    const rand = rng(99 + a.id);
    const trades = [];
    for (let i = 0; i < 14; i++) {
      const px = a.avg * (0.85 + rand() * 0.3), usd = a.base * (0.8 + rand() * 0.8);
      trades.push({ id: 1000 + i, timestamp: new Date(todayUtc - (i * 6 + 1) * DAY + 3600000).toISOString().slice(0, 19), side: 'BUY', quantity: r2(usd / px * 1e6) / 1e6, price: r2(px), fee: 0.02, feeCurrency: 'USDC' });
    }
    return { bindingId: a.id, assetSymbol: symbol, avgBuyPrice: a.avg, trades };
  }
  function liveWallet() {
    return Object.entries(ASSETS).map(([symbol, a]) => {
      const mod = modulationJournal(a, 0);
      const blocked = a.action === 'BLOCKED';
      return { assetSymbol: symbol, bindingId: a.id, presetId: 10 + a.id, presetName: 'Trend Mix ' + symbol, walletId: a.wallet, walletName: 'Wallet ' + a.ex, exchange: a.ex, bagPercent: 100, priority: a.id,
        tradability: symbol === 'PAXG' ? 'TRADABLE_VIA_INTERMEDIARY' : 'OK', tradabilityMessage: null, executionEnabled: symbol !== 'PAXG', firstLiveArmed: symbol !== 'PAXG', firstLiveConfirmed: symbol === 'BTC', routeKey: symbol + '-USDC', transit: [],
        snapshot: { day: iso(0), status: 'OK', fetchedAt: new Date().toISOString(), cashUsd: 4180, cashByMember: { USDC: 4180 }, positionQty: a.qty, tradableQty: a.qty, cashReserved: 0,
          blockReason: blocked ? 'INSUFFICIENT_CASH' : 'NONE', liveActionType: a.action, liveActionAmountUsdc: a.action === 'BUY' ? a.amount : null, liveActionQuantity: a.action === 'BUY' ? r2(a.amount / a.price * 1e6) / 1e6 : null,
          modulation: JSON.stringify(a.action === 'BUY' ? Object.assign({}, mod, { finalAmountUsd: a.amount, modulatedBuyUsd: a.amount }) : mod) } };
    });
  }
  const refuse = (what) => { throw new TIO.ApiError(400, what + " : indisponible en mode démo."); };

  TIO.demoApi = async function (method, url, body) {
    const u = new URL(url, g.location.origin), p = u.pathname, q = u.searchParams;
    await new Promise((r) => g.setTimeout(r, 60));
    let m;
    if (method === 'GET') {
      if (p === '/api/portfolio/summary') return summary(q.get('period') || '30d');
      if (p === '/api/portfolio/assets') return portfolioAssets();
      if (p === '/api/rainbow-live/execution-state') return { mode: 'DRY_RUN', liveExecution: false, killSwitch: true };
      if (p === '/api/rainbow-live/next-pass') return { enabled: true, nextRunAt: new Date(Date.now() + 5 * 3600000 + 20 * 60000).toISOString() };
      if (p === '/api/rainbow-live/live-wallet') return liveWallet();
      if (p === '/api/rainbow-live/bindings') return Object.entries(ASSETS).map(binding);
      if (p === '/api/rainbow-live/wallets') return [{ id: 1, name: 'Wallet OKX', exchange: 'OKX' }, { id: 2, name: 'Wallet KRAKEN', exchange: 'KRAKEN' }];
      if (p === '/api/rainbow-live/decisions') { const s = q.get('assetSymbol'); return decisions().filter((d) => !s || d.assetSymbol === s); }
      if (p === '/api/rainbow-live/execution-plans') return plans();
      if (p === '/api/rainbow-live/execution-plans/latest') return plans()[0];
      if ((m = p.match(/^\/api\/rainbow-live\/execution-plans\/(\d+)\/events$/))) return { page: 0, size: 50, totalElements: 0, items: [] };
      if ((m = p.match(/^\/api\/rainbow-live\/bindings\/(\d+)\/bands$/))) return bands(m[1], q.get('period') || '90d', q.get('simulated') === 'true');
      if ((m = p.match(/^\/api\/rainbow-live\/bindings\/(\d+)\/transactions$/))) return transactions(m[1]);
      if ((m = p.match(/^\/api\/rainbow-live\/bindings\/(\d+)\/check$/))) return { status: 'OK', message: 'Prêt', ok: true, executable: true, blocked: false, quoteMember: 'USDC', routeKey: 'X-USDC' };
      if (p === '/api/rainbow-live/defaults') return { assets: ['BTC', 'ETH', 'PAXG'], cashCurrency: 'USDC', defaultName: 'Trend Mix', baseAmount: 10 };
      if (p === '/api/rainbow-live/presets') return [{ id: 11, assetSymbol: q.get('asset') || 'BTC', name: 'Trend Mix', enabled: true }];
      if (p === '/api/user/preferences') return prefs;
      if (p === '/api/user/modulation-settings') return modulation;
      if (p === '/api/user/alerts') return [
        { type: 'BUY_BLOCKED', severity: 'WARNING', assetSymbol: 'PAXG', bindingId: 3, message: 'Achat PAXG bloqué : cash insuffisant sur Wallet KRAKEN.', day: iso(0) }];
      if (p === '/api/rainbow-live/ui-mode') return { mode: 'SIMPLE' };
    }
    if (method === 'PUT') {
      if (p === '/api/user/preferences') { prefs = Object.assign({}, prefs, body, { caps: Object.assign({}, prefs.caps, { maxOrderUsd: body.maxOrderUsd ?? null, maxDayUsd: body.maxDayUsd ?? null }), alertsDisabled: body.alertsDisabled || [] }); return prefs; }
      if (p === '/api/user/preferences/profile') { const R = { PRUDENT: [20, 50], BALANCED: [30, 70], DYNAMIC: [40, 90] }[body.profile]; modulation.settings.exposureMin = R[0]; modulation.settings.exposureMax = R[1]; prefs.startingProfile = body.profile; return { profile: body.profile, modulation: modulation.settings }; }
      if (p === '/api/user/modulation-settings') { modulation = Object.assign({}, modulation, { settings: Object.assign({}, modulation.settings, body) }); return modulation; }
      if (/\/execution$/.test(p)) refuse("Interrupteur d'exécution");
    }
    if (method === 'POST') {
      if (/confirm-first-live/.test(p)) refuse('Confirmation du premier ordre');
      if (p.startsWith('/api/credentials')) refuse('Enregistrement de clé');
      if (p === '/api/rainbow-live/bindings') refuse("Création d'un lien");
    }
    throw new TIO.ApiError(404, 'Non disponible en mode démo : ' + method + ' ' + p);
  };
  TIO.demoSymbols = Object.keys(ASSETS);
  TIO.demoBySymbol = bySymbol;
})(window);
