/* TradeIO — noyau front : construction DOM (textContent uniquement), appels API, formats. Aucun HTML injecté. */
(function (g) {
  'use strict';
  const TIO = g.TIO = g.TIO || {};
  const SVG_NS = 'http://www.w3.org/2000/svg';

  TIO.demo = new URLSearchParams(g.location.search).get('demo') === '1';

  function append(el, children) {
    for (const c of children) {
      if (c === null || c === undefined || c === false) continue;
      if (Array.isArray(c)) append(el, c);
      else if (c instanceof Node) el.appendChild(c);
      else el.appendChild(document.createTextNode(String(c)));
    }
  }
  function setAttrs(el, attrs) {
    if (!attrs) return;
    for (const [k, v] of Object.entries(attrs)) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') el.setAttribute('class', v);
      else if (k === 'text') el.textContent = String(v);
      else if (k === 'style' && typeof v === 'object') for (const [p, val] of Object.entries(v)) el.style.setProperty(p, String(val));
      else if (k === 'dataset') for (const [p, val] of Object.entries(v)) el.dataset[p] = String(val);
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
      else el.setAttribute(k, v === true ? '' : String(v));
    }
  }
  /** h('div', {class:'x'}, 'texte', node) */
  TIO.h = function (tag, attrs, ...children) {
    const el = document.createElement(tag);
    setAttrs(el, attrs);
    append(el, children);
    return el;
  };
  TIO.svg = function (tag, attrs, ...children) {
    const el = document.createElementNS(SVG_NS, tag);
    setAttrs(el, attrs);
    append(el, children);
    return el;
  };
  TIO.clear = function (el) { while (el.firstChild) el.removeChild(el.firstChild); return el; };
  TIO.mount = function (el, ...nodes) { TIO.clear(el); append(el, nodes); return el; };

  // ---------- API ----------
  class ApiError extends Error {
    constructor(status, message) { super(message); this.status = status; }
  }
  TIO.ApiError = ApiError;

  TIO.api = async function (method, url, body) {
    if (TIO.demo) return TIO.demoApi(method, url, body);
    const opts = { method, credentials: 'same-origin', headers: { Accept: 'application/json' } };
    if (body !== undefined) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
    const res = await fetch(url, opts);
    if (res.status === 401 || res.status === 403) {
      g.location.href = '/login';
      throw new ApiError(res.status, 'Session expirée');
    }
    let data = null;
    const text = await res.text();
    if (text) { try { data = JSON.parse(text); } catch (e) { data = null; } }
    if (!res.ok) throw new ApiError(res.status, (data && (data.error || data.message)) || ('Erreur ' + res.status));
    return data;
  };
  const once = new Map();
  /** GET mémorisé pour la durée de la page (plusieurs widgets, une seule requête) ; un échec n'est pas mémorisé. */
  TIO.getOnce = function (url) {
    if (!once.has(url)) once.set(url, TIO.api('GET', url).catch((e) => { once.delete(url); throw e; }));
    return once.get(url);
  };
  TIO.forget = (url) => once.delete(url);
  TIO.get = (url) => TIO.api('GET', url);
  TIO.put = (url, body) => TIO.api('PUT', url, body);
  TIO.post = (url, body) => TIO.api('POST', url, body);
  /** Échec isolé : la valeur de repli est utilisée (le reste de la page continue de s'afficher). */
  TIO.safe = function (promise, fallback) { return promise.catch(() => fallback); };
  TIO.qs = function (params) {
    const q = Object.entries(params).filter(([, v]) => v !== null && v !== undefined && v !== '')
      .map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v)).join('&');
    return q ? '?' + q : '';
  };

  // ---------- formats ("—" pour toute donnée absente) ----------
  const DASH = '—';
  const isNum = (n) => typeof n === 'number' && Number.isFinite(n);
  const nf = (min, max) => new Intl.NumberFormat('fr-FR', { minimumFractionDigits: min, maximumFractionDigits: max });
  TIO.fmt = {
    dash: DASH,
    num(n, digits) { return isNum(n) ? nf(digits ?? 0, digits ?? 0).format(n) : DASH; },
    usd(n, digits) {
      if (!isNum(n)) return DASH;
      const d = digits ?? (Math.abs(n) >= 1000 ? 0 : 2);
      return nf(d, d).format(n) + ' $';
    },
    signedUsd(n, digits) { return isNum(n) ? (n > 0 ? '+' : '') + TIO.fmt.usd(n, digits) : DASH; },
    pct(n, digits) { return isNum(n) ? nf(digits ?? 1, digits ?? 1).format(n) + ' %' : DASH; },
    signedPct(n, digits) { return isNum(n) ? (n > 0 ? '+' : '') + TIO.fmt.pct(n, digits) : DASH; },
    qty(n) {
      if (!isNum(n)) return DASH;
      const a = Math.abs(n);
      return nf(0, a >= 100 ? 2 : a >= 1 ? 4 : 6).format(n);
    },
    price(n) { return isNum(n) ? nf(2, n < 1 ? 6 : 2).format(n) + ' $' : DASH; },
    day(s) { if (!s) return DASH; const d = new Date(s + 'T00:00:00Z'); return d.toLocaleDateString('fr-FR', { day: 'numeric', month: 'short', timeZone: 'UTC' }); },
    dayLong(s) { if (!s) return DASH; const d = new Date(s + 'T00:00:00Z'); return d.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' }); },
    dateTime(s) { if (!s) return DASH; const d = new Date(s); return Number.isNaN(d.getTime()) ? DASH : d.toLocaleString('fr-FR', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }); },
    /** « dans 3 h 20 » / « dans 12 min » / « imminente ». */
    until(s, now) {
      if (!s) return DASH;
      const ms = new Date(s).getTime() - (now ?? Date.now());
      if (!Number.isFinite(ms)) return DASH;
      if (ms <= 60000) return 'imminente';
      const min = Math.round(ms / 60000);
      if (min < 60) return 'dans ' + min + ' min';
      const h = Math.floor(min / 60), m = min % 60;
      return 'dans ' + h + ' h' + (m ? ' ' + String(m).padStart(2, '0') : '');
    },
    days(n) { return isNum(n) ? nf(0, 0).format(Math.round(n)) + ' j' : DASH; },
  };
  TIO.isNum = isNum;
  TIO.sign = (n) => (isNum(n) ? (n > 0 ? 'up' : n < 0 ? 'down' : '') : '');
  TIO.clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

  // ---------- thème ----------
  TIO.setTheme = function (theme) { document.documentElement.setAttribute('data-theme', theme === 'light' ? 'light' : 'dark'); };

  // ---------- notification brève ----------
  TIO.toast = function (message, isError) {
    const old = document.querySelector('.toast');
    if (old) old.remove();
    const t = TIO.h('div', { class: 'toast' + (isError ? ' toast--err' : ''), role: isError ? 'alert' : 'status' }, message);
    document.body.appendChild(t);
    g.setTimeout(() => t.remove(), 3200);
  };

  // ---------- page courante ----------
  TIO.page = () => document.body.dataset.page;
  TIO.mode = () => document.body.dataset.mode || 'simple';
  TIO.modeAtLeast = function (level) {
    const order = { simple: 0, advanced: 1, expert: 2 };
    return (order[TIO.mode()] ?? 0) >= order[level];
  };
})(window);
