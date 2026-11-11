/* TradeIO — composants communs (charte : carte, pastille, puces, segmenté, interrupteur, barre, curseur, jauge, états). */
(function (g) {
  'use strict';
  const TIO = g.TIO;
  const h = TIO.h, svg = TIO.svg;
  const ui = TIO.ui = {};

  // ---------- libellés ----------
  TIO.labels = {
    action: { BUY: 'Achat', SELL: 'Vente', NONE: 'Rien à faire', BLOCKED: 'Bloqué' },
    block: {
      NONE: 'Aucun blocage', INSUFFICIENT_CASH: 'Cash insuffisant', UNAVAILABLE: 'Données indisponibles',
      NOT_TRADABLE_WITHOUT_FIAT: 'Actif non tradable sans monnaie fiat', BAND_LIMIT: "Bande d'exposition atteinte",
      NO_PATH: "Aucun chemin d'achat", BELOW_MIN: "Sous le minimum de l'exchange",
      INSUFFICIENT_FUNDS_AFTER_FEES: 'Fonds insuffisants après frais', READING_UNAVAILABLE: 'Lecture des soldes indisponible',
      CAP_ORDER: 'Plafond par ordre', CAP_DAY: 'Plafond par jour', CAP_ORDER_CLAMPED: 'Ordre limité au plafond', CAP_ABSOLUTE_ORDER_CLAMPED: 'Ordre limité au plafond absolu',
    },
    plan: {
      PLANNED: 'Prévu', BLOCKED: 'Bloqué', EXPIRED: 'Expiré', DISABLED: 'Désactivé', SUPERSEDED: 'Remplacé',
      EXECUTING: 'En cours', EXECUTED: 'Exécuté', PARTIAL: 'Partiel', FAILED: 'Échec', CANCELLED: 'Annulé',
    },
    step: { PLANNED: 'Prévu', SUBMITTED: 'Envoyé', FILLED: 'Exécuté', PARTIAL: 'Partiel', REJECTED: 'Rejeté', CANCELED: 'Annulé', UNKNOWN: 'Inconnu' },
    check: {
      OK: 'Prêt', WALLET_DISABLED: 'Wallet désactivé', CREDENTIAL_INVALID: 'Clé invalide', PROVIDER_UNSUPPORTED: 'Exchange non supporté',
      BALANCE_UNAVAILABLE: 'Solde illisible', TRADABLE_VIA_INTERMEDIARY: 'Tradable via intermédiaire', NOT_TRADABLE_WITHOUT_FIAT: 'Non tradable sans fiat',
    },
    alert: {
      BUY_BLOCKED: 'Achat bloqué', DATA_UNAVAILABLE: 'Données indisponibles', NOT_TRADABLE: 'Actif non tradable',
      CREDENTIAL_PROBLEM: 'Problème de clé', PLAN_PROBLEM: 'Problème de plan', TRANSIT_STOCK: 'Stock en transit',
    },
    fee: { GREEN: 'Frais OK', WARNING: 'Frais élevés', RED: 'Frais excessifs' },
  };
  const OK = new Set(['OK', 'TRADABLE_VIA_INTERMEDIARY', 'EXECUTED', 'FILLED', 'GREEN', 'PLANNED', 'EXECUTING', 'SUBMITTED']);
  const WARN = new Set(['PARTIAL', 'EXPIRED', 'WARNING', 'SUPERSEDED', 'BELOW_MIN', 'BAND_LIMIT', 'INSUFFICIENT_CASH', 'CAP_ORDER', 'CAP_DAY', 'DISABLED', 'NO_ACTION']);
  const DOWN = new Set(['BLOCKED', 'FAILED', 'REJECTED', 'RED', 'ERROR', 'CREDENTIAL_INVALID', 'WALLET_DISABLED', 'PROVIDER_UNSUPPORTED',
    'NOT_TRADABLE_WITHOUT_FIAT', 'NO_PATH', 'INSUFFICIENT_FUNDS_AFTER_FEES', 'CANCELLED', 'CANCELED']);
  /** Code métier → 'ok' | 'warn' | 'down' | 'unknown' (donnée absente ou code inconnu). */
  TIO.statusKind = function (code) {
    if (!code) return 'unknown';
    if (OK.has(code)) return 'ok';
    if (WARN.has(code)) return 'warn';
    if (DOWN.has(code)) return 'down';
    return 'unknown';
  };
  TIO.blockLabel = (code) => (code ? (TIO.labels.block[code] || code) : TIO.fmt.dash);

  // ---------- éléments simples ----------
  ui.chip = function (label, kind, extraClass) {
    return h('span', { class: 'chip' + (kind ? ' chip--' + kind : '') + (extraClass ? ' ' + extraClass : '') }, label);
  };
  const KIND_TEXT = { ok: 'OK', warn: 'ATTENTION', down: 'BLOQUÉ', unknown: 'INCONNU' };
  /** Pastille de statut unique : toujours libellé + pastille ; pointillé si inconnu. */
  ui.statusChip = function (code, label) {
    const kind = TIO.statusKind(code);
    return ui.chip(label || KIND_TEXT[kind], kind);
  };
  ui.tag = (text) => h('span', { class: 'tag' }, text);
  ui.card = function (title, ...children) {
    let opts = {};
    if (children.length && children[children.length - 1] && children[children.length - 1].__cardOpts) opts = children.pop();
    return h('section', { class: 'card' + (opts.hero ? ' card--hero' : '') + (opts.span ? ' span-all' : ''), 'aria-label': title || null },
      title ? h('div', { class: 'card-title' }, title, opts.aside ? h('span', { class: 'push' }, opts.aside) : null) : null, children);
  };
  ui.cardOpts = (o) => Object.assign({ __cardOpts: true }, o);
  ui.stat = (k, v, cls) => h('div', { class: 'stat' }, h('div', { class: 'k' }, k), h('div', { class: 'v' + (cls ? ' ' + cls : '') }, v));
  // Icônes crypto (CC0, spothq/cryptocurrency-icons) : img/crypto/<symbole>.svg, liste dans crypto-icons.json.
  // Pastille glyphe par défaut ; remplacée par l'icône si le symbole est connu, retour au glyphe si le chargement échoue.
  const ICON_DIR = '/assets/app/img/crypto/';
  let iconSet = null;
  const iconReady = g.fetch ? g.fetch(ICON_DIR + 'crypto-icons.json').then((r) => (r.ok ? r.json() : [])).catch(() => []).then((l) => { iconSet = new Set(l); }) : Promise.resolve();
  ui.coin = function (symbol) {
    const s = String(symbol || '?').toUpperCase();
    const glyph = { BTC: '₿', ETH: 'Ξ', XAU: 'Au', PAXG: 'Au', SOL: '◎' }[s] || s.slice(0, 2);
    let hash = 0;
    for (const ch of s) hash = (hash * 31 + ch.charCodeAt(0)) % 7;
    const el = h('span', { class: 'coin', 'aria-hidden': 'true', style: { '--c1': 'var(--s' + hash + ')', '--c2': 'var(--s' + ((hash + 3) % 7) + ')' } }, glyph);
    const apply = () => {
      if (!iconSet || !iconSet.has(s.toLowerCase())) return;
      const img = h('img', { src: ICON_DIR + encodeURIComponent(s.toLowerCase()) + '.svg', alt: '', width: 36, height: 36, loading: 'lazy' });
      img.addEventListener('error', () => { el.classList.remove('coin--img'); el.textContent = glyph; });
      el.classList.add('coin--img');
      el.textContent = '';
      el.appendChild(img);
    };
    if (iconSet) apply(); else iconReady.then(apply);
    return el;
  };
  ui.note = (text) => h('div', { class: 'note' }, text);

  // ---------- états ----------
  ui.loading = function (lines) {
    const n = lines || 3;
    const box = h('div', { class: 'stack', role: 'status', 'aria-label': 'Chargement' });
    for (let i = 0; i < n; i++) box.appendChild(h('div', { class: 'skeleton', style: { width: (i === 0 ? 55 : 90 - i * 12) + '%' } }));
    return box;
  };
  ui.empty = (title, text, action) => h('div', { class: 'state' }, h('strong', null, title), text ? h('span', null, text) : null, action || null);
  ui.error = function (err, retry) {
    return h('div', { class: 'state state--error', role: 'alert' }, h('strong', null, 'Chargement impossible'),
      h('span', { class: 'muted' }, (err && err.message) || 'Erreur inconnue'),
      retry ? h('button', { class: 'btn', type: 'button', onclick: retry }, 'Réessayer') : null);
  };
  /** Charge puis affiche ; échec ⇒ état d'erreur avec « Réessayer ». render(data) renvoie un nœud (ou une liste). */
  /** `soft` : rechargement silencieux, l'ancien contenu reste affiché jusqu'à l'arrivée des données (pas de scintillement). */
  ui.load = async function (el, loader, render, soft) {
    if (!(soft && el.firstChild)) TIO.mount(el, ui.loading());
    try {
      const data = await loader();
      TIO.mount(el, render(data));
    } catch (e) {
      TIO.mount(el, ui.error(e, () => ui.load(el, loader, render)));
    }
  };

  /** Carte autonome : titre + corps chargé indépendamment (un widget en échec n'empêche pas les autres). */
  ui.widget = function (title, loader, render, opts) {
    const o = opts || {};
    const body = h('div', { class: 'stack' });
    const card = h('section', { class: 'card' + (o.hero ? ' card--hero' : '') + (o.span ? ' span-all' : ''), 'aria-label': title },
      h('div', { class: 'card-title' }, title, o.info || null, o.aside ? h('span', { class: 'push' }, o.aside) : null), body);
    ui.load(body, loader, render);
    card.reload = () => ui.load(body, loader, render, true);
    return card;
  };

  // ---------- sélecteurs ----------
  function group(cls, options, current, onChange, label) {
    const box = h('div', { class: cls, role: 'group', 'aria-label': label || null });
    const buttons = options.map((o) => {
      const b = h('button', { type: 'button', 'aria-pressed': String(o.value === current), dataset: { value: o.value } }, o.label);
      b.addEventListener('click', () => {
        buttons.forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
        onChange(o.value);
      });
      return b;
    });
    buttons.forEach((b) => box.appendChild(b));
    return box;
  }
  ui.periods = (options, current, onChange) => group('periods', options, current, onChange, 'Période');
  ui.segmented = (options, current, onChange, label) => group('seg', options, current, onChange, label);
  ui.pchips = function (options, current, onChange, label) {
    const box = h('div', { class: 'chips', role: 'group', 'aria-label': label || null });
    const buttons = options.map((o) => {
      const b = h('button', { class: 'pchip', type: 'button', 'aria-pressed': String(o.value === current) }, o.label);
      b.addEventListener('click', () => { buttons.forEach((x) => x.setAttribute('aria-pressed', String(x === b))); onChange(o.value); });
      return b;
    });
    buttons.forEach((b) => box.appendChild(b));
    return box;
  };

  ui.switchEl = function (opts) {
    const input = h('input', { type: 'checkbox', role: 'switch', 'aria-label': opts.label, disabled: !!opts.disabled });
    input.checked = !!opts.checked;
    if (opts.onChange) input.addEventListener('change', () => opts.onChange(input.checked, input));
    return { el: h('span', { class: 'switch' + (opts.state ? ' switch--state' : '') }, input, h('i', { 'aria-hidden': 'true' })), input };
  };
  ui.setting = function (label, desc, control) {
    return h('div', { class: 'setting' }, h('div', null, h('div', { class: 'lbl' }, label), desc ? h('div', { class: 'desc' }, desc) : null), control);
  };
  ui.field = function (label, input, hint, id) {
    const fid = id || ('f' + Math.random().toString(36).slice(2, 8));
    input.id = fid;
    return h('div', { class: 'field' }, h('label', { for: fid }, label), input, hint ? h('div', { class: 'hint' }, hint) : null);
  };
  /** Champ numérique stylé : [préfixe] valeur [unité]. o = { value, unit, prefix, min, step, placeholder, label, onChange(value|null) }. Retourne { el, input, get() }. */
  ui.numberInput = function (o) {
    const input = h('input', { type: 'number', min: o.min ?? '0', step: o.step ?? 'any', inputmode: 'decimal', placeholder: o.placeholder || '', 'aria-label': o.label || null });
    input.value = o.value ?? '';
    const get = () => (input.value === '' ? null : Number(input.value));
    if (o.onChange) input.addEventListener('change', () => o.onChange(get()));
    const el = h('label', { class: 'numbox' }, o.prefix ? h('span', { class: 'nb-u' }, o.prefix) : null, input, o.unit ? h('span', { class: 'nb-u' }, o.unit) : null);
    return { el, input, get };
  };
  ui.bar = (pct) => h('div', { class: 'bar', role: 'img', 'aria-label': TIO.fmt.pct(pct, 0), style: { '--p': TIO.clamp(pct || 0, 0, 100) + '%' } }, h('i'));

  /** Curseur à piste arc-en-ciel (partie active pleine par clip-path, halo de la couleur de la position). */
  ui.slider = function (o) {
    const input = h('input', { type: 'range', min: o.min, max: o.max, step: o.step, 'aria-label': o.label, disabled: !!o.disabled });
    input.value = o.value;
    const root = h('div', { class: 'rslider' }, h('div', { class: 'trk' }), h('div', { class: 'fill' }));
    if (o.marker !== undefined) root.appendChild(h('div', { class: 'mark', style: { left: ((o.marker - o.min) / (o.max - o.min) * 100) + '%' } }));
    root.appendChild(input);
    const sync = () => {
      const f = TIO.clamp((Number(input.value) - o.min) / (o.max - o.min), 0, 1);
      root.style.setProperty('--p', (f * 100) + '%');
      root.style.setProperty('--halo', 'var(--s' + Math.round(f * 6) + ')');
    };
    input.addEventListener('input', () => { sync(); if (o.onInput) o.onInput(Number(input.value)); });
    input.addEventListener('change', () => { if (o.onChange) o.onChange(Number(input.value)); });
    sync();
    return { el: root, input, set(v) { input.value = v; sync(); } };
  };

  // ---------- jauge en arc (270°, départ 225°) ----------
  let gaugeSeq = 0;
  ui.gauge = function (o) {
    const cx = 100, cy = 96, r = 74;
    const pt = (f) => { const a = (225 - 270 * f) * Math.PI / 180; return [cx + r * Math.cos(a), cy - r * Math.sin(a)]; };
    const arc = (f0, f1) => {
      const [x0, y0] = pt(f0), [x1, y1] = pt(f1);
      return 'M' + x0.toFixed(2) + ' ' + y0.toFixed(2) + ' A' + r + ' ' + r + ' 0 ' + ((f1 - f0) * 270 > 180 ? 1 : 0) + ' 1 ' + x1.toFixed(2) + ' ' + y1.toFixed(2);
    };
    const id = 'gg' + (++gaugeSeq);
    const value = TIO.isNum(o.value) ? TIO.clamp(o.value, 0, 100) : null;
    const el = svg('svg', { class: 'gauge', viewBox: '0 0 200 168', role: 'img',
      'aria-label': o.label || ('Exposition ' + (value === null ? 'indisponible' : TIO.fmt.pct(value, 0))) });
    const defs = svg('defs', null, svg('linearGradient', { id, gradientUnits: 'userSpaceOnUse', x1: cx - r, y1: 0, x2: cx + r, y2: 0 },
      ['--s0', '--s1', '--s2', '--s3', '--s4', '--s5', '--s6'].map((v, i) => svg('stop', { offset: (i / 6 * 100) + '%', 'stop-color': 'var(' + v + ')' }))));
    el.appendChild(defs);
    el.appendChild(svg('path', { class: 'g-track', d: arc(0, 1) }));
    if (TIO.isNum(o.bandMin) && TIO.isNum(o.bandMax) && o.bandMax > o.bandMin) {
      el.appendChild(svg('path', { class: 'g-band', d: arc(o.bandMin / 100, o.bandMax / 100), stroke: 'url(#' + id + ')' }));
    }
    const l0 = pt(0), l1 = pt(1);
    el.appendChild(svg('text', { class: 'g-lbl', x: l0[0] - 4, y: l0[1] + 18, 'text-anchor': 'middle' }, '0 %'));
    el.appendChild(svg('text', { class: 'g-lbl', x: l1[0] + 4, y: l1[1] + 18, 'text-anchor': 'middle' }, '100 %'));
    el.appendChild(svg('text', { class: 'g-val', x: cx, y: cy + 8, 'text-anchor': 'middle' }, value === null ? TIO.fmt.dash : TIO.fmt.pct(value, 0)));
    if (value !== null) {
      const [mx, my] = pt(value / 100);
      el.appendChild(svg('circle', { class: 'g-mark', cx: mx.toFixed(2), cy: my.toFixed(2), r: 7 }));
    }
    return el;
  };

  // ---------- infobulles : un seul style (.tip), deux usages ----------
  // survol (graphiques) : ui.tooltip(wrap) -> {show(x,y,lines), hide()}
  ui.tipEl = function (extra) { return h('div', Object.assign({ class: 'tip hidden', role: 'status' }, extra || {})); };
  ui.tooltip = function (wrap) {
    const tip = ui.tipEl();
    wrap.appendChild(tip);
    return {
      show(x, y, lines) { TIO.mount(tip, lines.map((l, i) => h('div', { class: i === 0 ? 'muted' : '' }, l))); tip.classList.remove('hidden'); tip.style.left = x + 'px'; tip.style.top = y + 'px'; },
      hide() { tip.classList.add('hidden'); },
    };
  };
  // survol/focus de tout élément [data-tip] (attribut `title` des h() converti en data-tip) : même infobulle, positionnée au-dessus
  (function () {
    let tip = null;
    function hide() { if (tip) { tip.remove(); tip = null; } }
    function show(el) {
      hide();
      const txt = el.getAttribute('data-tip');
      if (!txt) return;
      tip = ui.tipEl({ class: 'tip tip--fixed' });
      tip.textContent = txt; tip.classList.remove('hidden');
      document.body.appendChild(tip);
      const r = el.getBoundingClientRect(), w = tip.offsetWidth, hh = tip.offsetHeight;
      const left = Math.max(8, Math.min(window.innerWidth - w - 8, r.left + r.width / 2 - w / 2));
      const top = r.top - hh - 8 < 8 ? r.bottom + 8 : r.top - hh - 8;
      tip.style.left = left + 'px'; tip.style.top = top + 'px';
    }
    const pick = (e) => e.target && e.target.closest ? e.target.closest('[data-tip]') : null;
    document.addEventListener('pointerover', (e) => { const el = pick(e); if (el) show(el); });
    document.addEventListener('pointerout', (e) => { const el = pick(e); if (el && !el.contains(e.relatedTarget)) hide(); });
    document.addEventListener('focusin', (e) => { const el = pick(e); if (el) show(el); });
    document.addEventListener('focusout', hide);
    document.addEventListener('scroll', hide, true);
  })();
  // clic (détail) : ui.bubble(opener, content, title) -> ferme via ×, Échap ou clic extérieur ; bottom-sheet en mobile
  let bubble = null;
  ui.closeBubble = function () {
    if (!bubble) return;
    bubble.el.remove();
    if (bubble.shade) bubble.shade.remove();
    document.removeEventListener('keydown', bubble.onKey, true);
    document.removeEventListener('pointerdown', bubble.onDown, true);
    const back = bubble.opener;
    bubble = null;
    if (back && back.isConnected) back.focus();
  };
  ui.bubble = function (opener, content, title) {
    ui.closeBubble();
    const sheet = window.matchMedia('(max-width: 700px)').matches;
    const close = h('button', { class: 'tip-close', type: 'button', 'aria-label': 'Fermer' }, '×');
    close.addEventListener('click', ui.closeBubble);
    const el = h('div', { class: 'tip tip--pop' + (sheet ? ' tip--sheet' : ''), role: 'dialog', 'aria-label': title, tabindex: '-1' }, close, content);
    const shade = sheet ? h('div', { class: 'tip-shade' }) : null;
    if (shade) document.body.appendChild(shade);
    document.body.appendChild(el);
    if (!sheet) {
      const r = opener.getBoundingClientRect(), w = Math.min(420, window.innerWidth - 24);
      el.style.width = w + 'px';
      el.style.left = Math.max(12, Math.min(window.innerWidth - w - 12, r.right - w)) + 'px';
      const below = r.bottom + 8, fits = below + el.offsetHeight < window.innerHeight - 12;
      el.style.top = (window.scrollY + (fits ? below : Math.max(12, r.top - 8 - el.offsetHeight))) + 'px';
    }
    const onKey = (e) => { if (e.key === 'Escape') ui.closeBubble(); };
    const onDown = (e) => { if (!el.contains(e.target) && e.target !== opener) ui.closeBubble(); };
    document.addEventListener('keydown', onKey, true);
    document.addEventListener('pointerdown', onDown, true);
    bubble = { el, shade, opener, onKey, onDown };
    el.focus();
  };

  // ---------- en-tête de page (état d'exécution + prochaine passe) ----------
  ui.pageHeader = function (title, sub) {
    const chips = h('div', { class: 'row' });
    const head = h('header', { class: 'tio-head' }, h('h1', null, title), sub ? h('div', { class: 'sub' }, sub) : null, chips);
    if (TIO.demo) chips.appendChild(ui.chip('Mode démo', 'accent'));
    Promise.all([TIO.safe(TIO.get('/api/rainbow-live/execution-state'), null), TIO.safe(TIO.get('/api/rainbow-live/next-pass'), null)]).then(([state, pass]) => {
      if (state) {
        if (state.killSwitch) chips.appendChild(ui.chip('Exécution suspendue (kill switch)', 'warn'));
        else if (state.liveExecution) chips.appendChild(ui.chip('Exécution réelle', 'ok'));
        else chips.appendChild(ui.chip('Simulation', 'sim'));
      } else {
        chips.appendChild(ui.chip('État inconnu', 'unknown'));
      }
      if (pass && pass.enabled && pass.nextRunAt) chips.appendChild(ui.chip('Prochaine passe ' + TIO.fmt.until(pass.nextRunAt), 'accent', 'chip--plain'));
    });
    return head;
  };
  ui.demoBanner = function () {
    return TIO.demo ? h('div', { class: 'banner banner--demo', role: 'note' },
      "Mode démo : données fictives, lecture seule. Aucune action n'est envoyée et l'exécution n'est jamais touchée.") : null;
  };

  /** Journal de modulation (JSON texte) → objet, ou null si absent / illisible. */
  TIO.parseMod = function (text) {
    if (!text) return null;
    try { const v = JSON.parse(text); return v && typeof v === 'object' ? v : null; } catch (e) { return null; }
  };
  /** Barre de bande d'exposition : bande [min,max] en violet 7 %, zone libre en violet 10 %, repère = exposition. */
  ui.bandBar = function (o) {
    const pos = (v) => TIO.clamp(v, 0, 100) + '%';
    const root = h('div', { class: 'bandbar', role: 'img',
      'aria-label': 'Bande ' + TIO.fmt.pct(o.min, 0) + ' à ' + TIO.fmt.pct(o.max, 0) + (TIO.isNum(o.value) ? ', exposition ' + TIO.fmt.pct(o.value, 0) : '') });
    root.appendChild(h('i', { class: 'bb-band', style: { left: pos(o.min), width: TIO.clamp(o.max - o.min, 0, 100) + '%' } }));
    if (TIO.isNum(o.freeMin) && TIO.isNum(o.freeMax) && o.freeMax > o.freeMin) {
      root.appendChild(h('i', { class: 'bb-free', style: { left: pos(o.freeMin), width: TIO.clamp(o.freeMax - o.freeMin, 0, 100) + '%' } }));
    }
    // repères de bande : pas de trait quand la borne est au bord de la barre (0 % ou 100 %), il ferait un artefact
    if (o.min > 0.5 && o.min < 99.5) root.appendChild(h('i', { class: 'bb-edge', style: { left: pos(o.min) } }));
    if (o.max > 0.5 && o.max < 99.5) root.appendChild(h('i', { class: 'bb-edge', style: { left: pos(o.max) } }));
    if (TIO.isNum(o.value)) root.appendChild(h('i', { class: 'bb-mark', style: { left: pos(o.value) } }));
    return root;
  };
  /**
   * Bande d'exposition lisible : hors bande (hachuré), marge de régulation basse (rouge → orange), zone libre (vert → bleu → violet),
   * marge haute (orange → rouge), repères Min / Max et bornes de la zone libre, point = exposition actuelle.
   * o : { min, max, marginPct (% de la largeur de bande), value (exposition actuelle, facultative) }
   */
  ui.exposureBand = function (o) {
    const min = o.min, max = o.max, width = max - min;
    const marginPts = TIO.clamp((o.marginPct || 0) / 100 * width, 0, width / 2);
    const fmin = min + marginPts, fmax = max - marginPts;
    const pct = (n) => TIO.fmt.pct(n, Number.isInteger(Math.round(n * 10) / 10) ? 0 : 1);
    const pos = (v) => TIO.clamp(v, 0, 100) + '%';
    const seg = (cls, from, to) => h('i', { class: 'xb-seg ' + cls, style: { left: pos(from), width: TIO.clamp(to - from, 0, 100) + '%' } });
    // un libellé ancré sur sa position, rabattu vers l'intérieur près des bords pour ne pas déborder
    const label = (cls, at, text) => h('span', { class: 'xb-lbl ' + cls + (at < 12 ? ' xb-lbl--l' : at > 88 ? ' xb-lbl--r' : ''), style: { left: pos(at) } }, text);
    const v = TIO.isNum(o.value) ? o.value : null;
    const where = v === null ? null : v < min ? ['Sous la bande', 'warn'] : v > max ? ['Au-dessus de la bande', 'warn']
      : v < fmin ? ['Marge basse', 'warn'] : v > fmax ? ['Marge haute', 'warn'] : ['Zone libre', 'ok'];
    // strictement la même structure que les curseurs (.rslider : piste de 6 px, repère rond) ; marges = zones grises translucides qui rougissent vers la borne, zone libre verte
    const track = h('div', { class: 'rslider', style: { '--halo': 'var(--s3)' } });
    const bar = h('div', { class: 'xb-bar' }, seg('xb-out', 0, min), seg('xb-mlow', min, fmin), seg('xb-free', fmin, fmax), seg('xb-mhigh', fmax, max), seg('xb-out', max, 100));
    track.appendChild(bar);
    if (v !== null) track.appendChild(h('i', { class: 'rdot', style: { left: pos(v) } }));
    const root = h('div', { class: 'xband', role: 'img',
      'aria-label': 'Bande d\'exposition de ' + pct(min) + ' à ' + pct(max) + ', zone libre de ' + pct(fmin) + ' à ' + pct(fmax) + (v !== null ? ', exposition actuelle ' + pct(v) : '') });
    root.appendChild(h('div', { class: 'xb-top' }, v !== null ? label('xb-now', v, pct(v)) : null));
    root.appendChild(track);
    root.appendChild(h('div', { class: 'xb-scale' }, label('xb-edge', min, 'Min ' + pct(min)), label('xb-edge', max, 'Max ' + pct(max))));
    if (marginPts > 0) root.appendChild(h('div', { class: 'xb-scale xb-scale--sub' }, label('xb-sub', fmin, pct(fmin)), label('xb-sub', fmax, pct(fmax))));
    root.appendChild(h('div', { class: 'xb-legend' },
      h('span', null, h('i', { class: 'xb-key xb-free' }), 'Zone libre'),
      h('span', null, h('i', { class: 'xb-key xb-mlow' }), 'Marge de régulation · ' + TIO.fmt.num(o.marginPct, 0) + ' % de la bande (' + TIO.fmt.num(marginPts, 1) + ' pts)'),
      h('span', null, h('i', { class: 'xb-key xb-out' }), 'Hors bande')));
    root.status = where;
    return root;
  };
  ui.alerts = function () {
    const box = h('div', { class: 'stack' });
    TIO.safe(TIO.get('/api/user/alerts'), []).then((alerts) => {
      alerts.forEach((a) => box.appendChild(h('div', { class: 'banner banner--' + (a.severity === 'ERROR' ? 'down' : 'warn'), role: 'alert' },
        h('strong', null, (TIO.labels.alert[a.type] || a.type) + (a.assetSymbol ? ' · ' + a.assetSymbol : '') + ' — '), a.message || '',
        a.assetSymbol ? h('span', null, ' ', h('a', { href: TIO.href('/app/assets/' + encodeURIComponent(a.assetSymbol)) }, 'Voir')) : null)));
    });
    return box;
  };
  /** Onglets des deux pages de réglages (navigation, pas d'état) : Stratégie / Utilisateur. */
  ui.settingsTabs = (active) => ui.segmented([{ value: 'strategy', label: 'Stratégie' }, { value: 'user', label: 'Utilisateur' }], active,
    (v) => { if (v !== active) g.location.href = TIO.href('/app/settings/' + v); }, 'Réglages');
  /** Enregistre les préférences (le PUT remplace le bloc complet : on renvoie les valeurs courantes + le changement). Thème : sombre seul. */
  ui.savePrefs = (prefs, patch) => TIO.put('/api/user/preferences',
    Object.assign({ displayMode: prefs.displayMode, alertsDisabled: prefs.alertsDisabled, maxOrderUsd: prefs.caps.maxOrderUsd, maxDayUsd: prefs.caps.maxDayUsd }, patch));
  /** Pastille « i » à droite d'un titre : infobulle au survol (ou au focus/tap) ; `.set(lignes, alerte)` remplit le contenu. */
  ui.info = () => {
    const pop = h('span', { class: 'info-pop', role: 'tooltip' });
    const el = h('span', { class: 'info', tabindex: '0', 'aria-label': 'Détails' }, 'i', pop);
    el.set = (lines, warn) => {
      TIO.clear(pop);
      lines.filter(Boolean).forEach((l) => pop.appendChild(h('span', { style: { display: 'block' } }, l)));
      el.classList.toggle('info--warn', !!warn);
    };
    return el;
  };
  /** Montant signé coloré (vert/rouge) avec flèche ; « — » sans valeur. */
  ui.profit = (n, digits) => h('span', { class: 'num ' + TIO.sign(n) }, TIO.isNum(n) ? (n > 0 ? '▲ ' : n < 0 ? '▽ ' : '') + TIO.fmt.signedUsd(n, digits == null ? 0 : digits) : TIO.fmt.dash);
  const num = (v) => (v === null || v === undefined || v === '' ? null : Number(v));
  /** Libellé, détail chiffré et couleur de l'état de la machine (nul si la passe du jour n'a pas été jouée). */
  const F = TIO.fmt;
  const ZONES = ['Extrême bas', 'Zone X2', 'Zone X1', 'Zone X0,5', "Pas d'achat", 'Extrême haut'];
  TIO.zoneLabel = (z) => ZONES[z] || 'zone inconnue';
  TIO.trendLabel = (t) => ({ UP: 'haussière (Bull)', DOWN: 'baissière (Bear)', RANGE: 'range' }[t] || t || null);
  TIO.stateText = function (st) {
    if (!st) return null;
    const px = (v) => (TIO.isNum(num(v)) ? F.price(num(v)) : F.dash);
    const armed = TIO.isNum(num(st.armedDays)) && st.armedDays > 0 ? ' · armé depuis ' + F.days(st.armedDays) : '';
    switch (st.kind) {
      case 'MOON_ACTIVE': return { tone: 'accent', label: 'To the moon actif', detail: 'plus haut ' + px(st.referencePrice) + ' · stop à ' + px(st.triggerPrice) };
      case 'SELL_TRAILING': return { tone: 'warn', label: 'Trailing-stop de vente actif', detail: 'plus haut ' + px(st.referencePrice) + ' · stop à ' + px(st.triggerPrice) + armed };
      case 'COOLDOWN': return { tone: 'accent', label: 'Cooldown', detail: 'reprise dans ' + F.days(st.cooldownDays) + (st.buyLocked ? ' · puis attente sous Down2 (' + px(st.down2) + ')' : '') };
      case 'LOCKED_UNTIL_DOWN2': return { tone: 'warn', label: 'Vendu · attente sous Down2', detail: 'clôture ' + px(st.close) + ' · Down2 ' + px(st.down2) };
      case 'BUY_TRAILING': return { tone: 'info', label: "Attente trailing-stop d'achat", detail: 'plus bas ' + px(st.referencePrice) + ' · déclenche à ' + px(st.triggerPrice) + armed };
      default: return { tone: 'muted', label: 'En surveillance', detail: (ZONES[st.zone] || 'Zone inconnue') + ' · clôture ' + px(st.close) };
    }
  };
  ui.pnlNote = () => h('div', { class: 'note' }, 'hors dépôts/retraits');
})(window);
