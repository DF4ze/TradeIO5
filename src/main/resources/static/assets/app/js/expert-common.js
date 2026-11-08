/* Espace Expert : briques communes aux écrans Trend / Rainbow / Affectation (client API avec codes d'erreur, champs typés, formulaire de preset,
   sélecteurs actif / provider, plage, simulation asynchrone avec sondage, fenêtre de résultats, confirmations). Aucune donnée de trading dans le rendu serveur. */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, F = TIO.fmt;
  const X = TIO.expert = {};

  // ---------------------------------------------------------------- API
  class XError extends Error {
    constructor(status, body) {
      super((body && body.error) || ('Erreur ' + status));
      this.status = status; this.code = body && body.code; this.details = body && body.details;
    }
  }
  X.Error = XError;
  X.req = async function (method, url, body) {
    const opts = { method, credentials: 'same-origin', headers: { Accept: 'application/json' } };
    if (body !== undefined) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
    const res = await fetch(url, opts);
    if (res.status === 401) { g.location.href = '/login'; throw new XError(401, null); }
    const text = await res.text();
    let data = null;
    if (text) { try { data = JSON.parse(text); } catch (e) { data = null; } }
    if (!res.ok) throw new XError(res.status, data);
    return data;
  };
  const API = '/api/expert';

  // ---------------------------------------------------------------- paramètres d'URL (actif / provider partagés entre onglets)
  X.param = (k) => new URLSearchParams(g.location.search).get(k);
  X.setParam = function (k, v) {
    const u = new URL(g.location.href);
    if (v === null || v === undefined || v === '') u.searchParams.delete(k); else u.searchParams.set(k, v);
    g.history.replaceState(null, '', u.pathname + u.search);
  };
  X.guard = { dirty: () => false };
  g.addEventListener('beforeunload', (e) => { if (X.guard.dirty()) { e.preventDefault(); e.returnValue = ''; } });

  // ---------------------------------------------------------------- modale de confirmation (sans dialogue natif)
  X.modal = function (title, body, buttons) {
    return new Promise((resolve) => {
      const close = (v) => { overlay.remove(); g.removeEventListener('keydown', onKey); resolve(v); };
      const onKey = (e) => { if (e.key === 'Escape') close(null); };
      const foot = h('div', { class: 'row xmodal-foot' }, buttons.map((b) => h('button', { type: 'button', class: 'btn' + (b.primary ? ' btn--primary' : '') + (b.danger ? ' btn--danger' : ''), onclick: () => close(b.value) }, b.label)));
      const box = h('div', { class: 'xmodal-box', role: 'dialog', 'aria-modal': 'true', 'aria-label': title }, h('h2', null, title), h('div', { class: 'stack' }, body), foot);
      const overlay = h('div', { class: 'xmodal' }, box);
      overlay.addEventListener('click', (e) => { if (e.target === overlay) close(null); });
      g.addEventListener('keydown', onKey);
      document.body.appendChild(overlay);
      const first = foot.querySelector('.btn--primary') || foot.querySelector('button');
      if (first) first.focus();
    });
  };
  /** Confirmation : lignes de texte (les lignes {warn:true} sont mises en avant). Résout true / false. */
  X.confirm = async function (title, lines, okLabel, danger) {
    const body = lines.map((l) => (typeof l === 'string' ? h('p', null, l) : h('p', { class: 'banner banner--warn' }, l.text)));
    const v = await X.modal(title, body, [{ label: 'Annuler', value: false }, { label: okLabel || 'Confirmer', value: true, primary: !danger, danger: !!danger }]);
    return v === true;
  };
  /** Navigation avec avertissement si un brouillon n'est pas enregistré. */
  X.go = async function (url) {
    if (X.guard.dirty() && !(await X.confirm('Modifications non enregistrées', ['Le brouillon en cours sera perdu.'], 'Quitter', true))) return;
    X.guard.dirty = () => false;
    g.location.href = url;
  };

  // ---------------------------------------------------------------- messages d'erreur lisibles
  X.errorText = function (e) {
    if (!(e instanceof XError)) return e && e.message ? e.message : 'Erreur inattendue';
    const d = e.details;
    switch (e.code) {
      case 'ELIGIBILITY_INSUFFICIENT': {
        const prov = d && Array.isArray(d.providers) ? d.providers.map((p) => p.provider + ' : ' + p.available + (p.maxDepth ? ' (max ' + p.maxDepth + ')' : '')).join(' ; ') : '';
        return 'Historique insuffisant : ' + (d && d.requiredPoints ? d.requiredPoints + ' points requis' : 'points requis non atteints') + (prov ? ' · disponibles — ' + prov : '') + '.';
      }
      case 'SIMULATION_ALREADY_RUNNING': return 'Une simulation est déjà en cours : attendez sa fin.';
      case 'SIMULATION_RATE_LIMITED': return 'Trop de simulations rapprochées : patientez un instant.';
      case 'SIMULATION_BUSY': return 'File de simulations saturée : réessayez dans un moment.';
      case 'EXPERT_MODE_REQUIRED': return "L'Espace Expert exige le mode d'affichage Expert (Réglages utilisateur).";
      case 'PRESET_SYSTEM_READ_ONLY': return 'Preset système : dupliquez-le pour l’adapter.';
      case 'VALIDATION': return 'Réglages invalides : voir les champs signalés.';
      default: return e.message;
    }
  };

  // ---------------------------------------------------------------- champs typés
  const T = (k, label, unit, help, type) => ({ path: ['trend', k], k, label, unit, help, type: type || 'int' });
  X.TREND_REGRESSION_FIELDS = [
    T('shortWindow', 'Fenêtre courte', 'j', 'Horizon court du swing (pente et régression).'),
    T('mediumWindow', 'Fenêtre moyenne', 'j', 'Horizon intermédiaire du swing.'),
    T('longWindow', 'Fenêtre longue', 'j', 'Horizon long du swing.'),
    T('slopeScale', 'Échelle de pente', '', 'Normalise la pente avant comparaison aux seuils.', 'num'),
    T('enter', "Seuil d'entrée", '', 'Score à franchir pour entrer dans un régime.', 'num'),
    T('exit', 'Seuil de sortie', '', 'Score sous lequel on quitte le régime.', 'num'),
    T('confirm', 'Jours de confirmation', 'j', 'Durée de confirmation avant de valider un changement.'),
  ];
  X.TREND_SMA_FIELDS = [
    T('smaPeriod', 'Période SMA', 'j', 'Moyenne mobile de référence du Trend.'),
    T('atrPeriod', 'Période ATR', 'j', "Volatilité utilisée pour le stop suiveur."),
    T('atrMultiplier', 'Multiplicateur ATR', '×', 'Distance du stop suiveur, en multiples d’ATR.', 'num'),
    T('wickDown', 'Mèches baissières', '', 'Les mèches basses comptent dans le stop.', 'bool'),
    T('wickUp', 'Mèches haussières', '', 'Les mèches hautes comptent dans le stop.', 'bool'),
  ];
  X.TREND_MIX_FIELDS = [
    { path: ['rangeMapping'], k: 'rangeMapping', label: 'Résolution des RANGE', unit: '', help: 'Régime retenu quand le Trend est indécis.', type: 'enum',
      options: [['KEEP_PREVIOUS', 'Garder le régime précédent'], ['TO_BEAR', 'RANGE → DOWN (Bear)'], ['TO_BULL', 'RANGE → UP (Bull)']] },
  ];
  /** Groupes du formulaire Trend : régression et SMA séparés, chacun repliable ; `tone` colore le titre. */
  X.TREND_GROUPS = [
    ['Régression linéaire', X.TREND_REGRESSION_FIELDS, { fold: 'trend.reg', tone: 'reg' }],
    ['SMA et bandes ATR', X.TREND_SMA_FIELDS, { fold: 'trend.sma', tone: 'sma' }],
    ['Composition', X.TREND_MIX_FIELDS, { fold: 'trend.mix', tone: 'mix' }],
  ];
  X.TREND_FIELDS = X.TREND_GROUPS.flatMap((grp) => grp[1]);
  const REENTRY = [['TRAILING_STOP', 'Stop suiveur'], ['IMMEDIATE', 'Immédiat'], ['FIXED_DELAY', 'Délai fixe']];
  const R = (grp, k, label, unit, help, type, extra) => Object.assign({ path: [grp, k], k, label, unit, help, type: type || 'num', group: null }, extra || {});
  X.RAINBOW_GROUPS = [
    ['Général', [
      { path: ['analysisWindowMonths'], k: 'analysisWindowMonths', label: "Fenêtre d'analyse", unit: 'mois', help: "Profondeur d'historique utilisée pour l'analyse.", type: 'int' },
      R('tuning', 'smaPeriod', 'Période SMA', 'j', 'Moyenne centrale des bornes.', 'int'),
      R('tuning', 'atrPeriod', 'Période ATR', 'j', 'Volatilité des bornes.', 'int'),
      R('globals', 'baseAmount', 'Montant de base', '$', "Montant d'achat de référence avant multiplicateurs.")]],
    ['Bornes ATR', [
      R('tuning', 'atrMultDown2', 'Multiplicateur borne −2', '×', 'Borne extrême basse = SMA − ATR × valeur.'),
      R('tuning', 'atrMultDown1', 'Multiplicateur borne −1', '×', 'Borne basse intermédiaire.'),
      R('tuning', 'atrMultUp1', 'Multiplicateur borne +1', '×', 'Borne haute 1.'),
      R('tuning', 'atrMultUp2', 'Multiplicateur borne +2', '×', 'Borne haute 2 (limite « pas d’achat »).'),
      R('tuning', 'atrMultUp3', 'Multiplicateur borne +3', '×', 'Borne extrême haute.')]],
    ['Ré-entrée et stops', [
      R('tuning', 'buyReentryMode', 'Mode de ré-entrée achat', '', "Sortie de l'armement d'achat.", 'enum', { options: REENTRY }),
      R('tuning', 'sellReentryMode', 'Mode de ré-entrée vente', '', "Sortie de l'armement de vente.", 'enum', { options: REENTRY }),
      R('tuning', 'trailingStopBuyPct', 'Stop suiveur achat', '%', 'Rebond depuis le plus bas qui déclenche l’achat.'),
      R('tuning', 'trailingStopSellPct', 'Stop suiveur vente', '%', 'Repli depuis le plus haut qui déclenche la vente.')]],
    ['Rythme et règles', [
      R('tuning', 'cooldownDays', 'Délai après vente', 'j', 'Pause posée après une vente.', 'int'),
      R('tuning', 'fixedDelayDays', 'Délai fixe', 'j', 'Délai du mode « délai fixe » (achat et vente).', 'int'),
      R('tuning', 'sellFraction', 'Fraction vendue', '0–1', 'Part de la position vendue à chaque vente.'),
      R('tuning', 'allowSellDuringCooldown', 'Vente pendant le délai', '', 'Autorise l’armement de vente pendant le délai (jamais l’achat).', 'bool'),
      R('tuning', 'cooldownAfterSellOn', 'Délai après vente actif', '', 'Sinon aucun délai n’est posé après une vente.', 'bool'),
      R('tuning', 'blockBuyAfterSellUntilDown2', 'Achat bloqué après vente', '', 'Jusqu’à un nouveau franchissement sous la borne −2.', 'bool')]],
    ['Modulation ATH', [
      R('globals', 'athOn', 'Modulation ATH', '', 'Active les facteurs selon la distance à l’ATH.', 'bool'),
      R('globals', 'athRefDdBuyPct', 'Drawdown de référence achat', '%', 'Distance à l’ATH où le facteur d’achat est maximal.'),
      R('globals', 'athRefDdSellPct', 'Drawdown de référence vente', '%', 'Distance à l’ATH où le facteur de vente est minimal.'),
      R('globals', 'athBuyMin', 'Facteur achat min', '×', 'Facteur d’achat près de l’ATH.'),
      R('globals', 'athBuyMax', 'Facteur achat max', '×', 'Facteur d’achat loin de l’ATH.'),
      R('globals', 'athSellMax', 'Facteur vente max', '×', 'Facteur de vente près de l’ATH.'),
      R('globals', 'athSellMin', 'Facteur vente min', '×', 'Facteur de vente loin de l’ATH.')]],
    ['Zones et multiplicateurs', [
      R('globals', 'multX2', 'Multiplicateur zone X2', '×', 'Achat dans la zone X2.'),
      R('globals', 'multX1', 'Multiplicateur zone X1', '×', 'Achat dans la zone X1.'),
      R('globals', 'multX0_5', 'Multiplicateur zone X0,5', '×', 'Achat dans la zone X0,5.'),
      R('globals', 'multTriggered', 'Multiplicateur déclenché', '×', 'Achat déclenché par le stop suiveur.')]],
    ['To the moon', [
      R('globals', 'moonOn', 'Mode To the moon', '', 'Active la réserve et les sorties moon.', 'bool'),
      R('globals', 'moonReservePct', 'Réserve moon', '%', 'Part de la position protégée.'),
      R('globals', 'moonReserveRatchet', 'Cliquet de réserve', '', 'La réserve ne peut que monter.', 'bool'),
      R('globals', 'moonTrailingStopPct', 'Stop suiveur moon', '%', 'Repli depuis le plus haut qui déclenche le stop moon.'),
      R('globals', 'moonStopSellPct', 'Vente au stop moon', '%', 'Part vendue quand le stop moon se déclenche.')]],
  ];
  X.RAINBOW_FIELDS = X.RAINBOW_GROUPS.flatMap((grp) => grp[1]);

  const getAt = (o, path) => path.reduce((v, k) => (v === null || v === undefined ? v : v[k]), o);
  function setAt(o, path, v) {
    let t = o;
    for (let i = 0; i < path.length - 1; i++) { if (t[path[i]] === null || t[path[i]] === undefined) t[path[i]] = {}; t = t[path[i]]; }
    t[path[path.length - 1]] = v;
  }
  const clone = (o) => JSON.parse(JSON.stringify(o));

  /**
   * Formulaire de preset. groups : [[titre|null, champs]] ; values : { name, referenceProvider, ...réglages } (copié).
   * opts : { providers: ['KRAKEN',…], onChange() }. Retourne { el, get(), set(values), setErrors(map), setReadOnly(b) }.
   */
  X.form = function (groups, initial, opts) {
    const o = opts || {};
    let values = clone(initial), readOnly = false;
    const inputs = [], errBoxes = new Map();
    const foldKey = (k) => 'tio.expert.fold.' + k;
    const isOpen = (k) => { try { return g.localStorage.getItem(foldKey(k)) !== '0'; } catch (e) { return true; } };
    const remember = (k, open) => { try { g.localStorage.setItem(foldKey(k), open ? '1' : '0'); } catch (e) { /* ignoré */ } };
    const body = h('div', { class: 'stack' }), summary = h('div');
    const notify = () => { if (o.onChange) o.onChange(); };

    function control(f) {
      const cur = getAt(values, f.path);
      let input;
      if (f.type === 'bool') {
        input = h('input', { type: 'checkbox' }); input.checked = !!cur;
        input.addEventListener('change', () => { setAt(values, f.path, input.checked); notify(); });
      } else if (f.type === 'enum') {
        input = h('select', null, f.options.map(([v, l]) => h('option', { value: v }, l)));
        input.value = cur === null || cur === undefined ? '' : String(cur);
        input.addEventListener('change', () => { setAt(values, f.path, input.value); notify(); });
      } else {
        input = h('input', { type: 'number', step: f.type === 'int' ? '1' : 'any', inputmode: 'decimal' });
        input.value = cur === null || cur === undefined ? '' : cur;
        input.addEventListener('input', () => { setAt(values, f.path, input.value === '' ? null : Number(input.value)); notify(); });
      }
      inputs.push({ f, input });
      const id = 'xf-' + f.path.join('-');
      input.id = id;
      const err = h('div', { class: 'err', role: 'alert' });
      errBoxes.set(f.path.join('.'), err);
      const label = h('label', { for: id }, f.label, f.unit ? h('span', { class: 'muted' }, ' (' + f.unit + ')') : null);
      return h('div', { class: 'field xfield' + (f.type === 'bool' ? ' xfield--bool' : '') }, label, input, f.help ? h('div', { class: 'hint' }, f.help) : null, err);
    }
    /** Groupe de champs : fieldset simple, ou section repliable (<details>, état mémorisé) si `opts.fold`. */
    function group([title, fields, gopts]) {
      const grid = h('div', { class: 'xgrid' }, fields.map(control));
      if (!gopts || !gopts.fold) return h('fieldset', { class: 'xgroup' }, title ? h('legend', null, title) : null, grid);
      const det = h('details', { class: 'xgroup xgroup--fold xgroup--' + (gopts.tone || 'mix') }, h('summary', null, h('i', { class: 'xgroup-dot' }), title,
        h('span', { class: 'muted small xgroup-count' }, ' · ' + fields.length + ' réglage' + (fields.length > 1 ? 's' : ''))), grid);
      det.open = isOpen(gopts.fold);
      det.addEventListener('toggle', () => remember(gopts.fold, det.open));
      return det;
    }
    function build() {
      inputs.length = 0; errBoxes.clear();
      const head = h('div', { class: 'xgrid' });
      const name = h('input', { type: 'text', maxlength: '60' }); name.value = values.name || '';
      name.addEventListener('input', () => { values.name = name.value; notify(); });
      inputs.push({ input: name, meta: true });
      const nameErr = h('div', { class: 'err', role: 'alert' }); errBoxes.set('name', nameErr);
      head.appendChild(h('div', { class: 'field xfield' }, h('label', null, 'Nom du preset'), name, nameErr));
      const prov = h('select', null, h('option', { value: '' }, '— défaut de l’actif —'), (o.providers || []).map((p) => h('option', { value: p }, p)));
      prov.value = values.referenceProvider || '';
      prov.addEventListener('change', () => { values.referenceProvider = prov.value || null; notify(); });
      inputs.push({ input: prov, meta: true });
      const provErr = h('div', { class: 'err', role: 'alert' }); errBoxes.set('referenceProvider', provErr);
      head.appendChild(h('div', { class: 'field xfield' }, h('label', null, 'Provider de référence'), prov, h('div', { class: 'hint' }, 'Source des bougies par défaut de ce preset.'), provErr));
      TIO.mount(body, head, groups.map(group), summary);
      applyReadOnly();
    }
    function applyReadOnly() { inputs.forEach(({ input }) => { input.disabled = readOnly; }); }
    build();
    return {
      el: body,
      get: () => clone(values),
      set(v) { values = clone(v); build(); },
      setReadOnly(b) { readOnly = !!b; applyReadOnly(); },
      /** map { 'tuning.smaPeriod': 'message', … } (ou clés nues) ; renvoie les messages sans champ. */
      setErrors(map) {
        errBoxes.forEach((box) => { box.textContent = ''; });
        const rest = [];
        Object.entries(map || {}).forEach(([key, msg]) => {
          const box = errBoxes.get(key) || [...errBoxes.entries()].find(([k]) => k.split('.').pop() === key.split('.').pop())?.[1];
          if (box) box.textContent = msg; else rest.push(key + ' ' + msg);
        });
        TIO.mount(summary, rest.length ? h('div', { class: 'banner banner--down', role: 'alert' }, rest.join(' ; ')) : null);
      },
    };
  };

  // ---------------------------------------------------------------- données communes
  X.loadAssets = () => X.req('GET', API + '/assets');
  X.presets = (kind, asset) => X.req('GET', API + '/' + kind + '-presets?asset=' + encodeURIComponent(asset));
  X.preset = (kind, id) => X.req('GET', API + '/' + kind + '-presets/' + id);

  /** Sélecteurs actif / provider / (preset) : liste de <select> dans une rangée. onChange(key, value). */
  X.selectField = function (label, options, value, onChange, hint) {
    const sel = h('select', null, options.map((op) => h('option', { value: op.value }, op.label)));
    sel.value = value === null || value === undefined ? '' : value;
    sel.addEventListener('change', () => onChange(sel.value));
    return ui.field(label, sel, hint);
  };
  X.assetOptions = (assets) => assets.map((a) => ({ value: a.symbol, label: a.symbol + (a.name ? ' · ' + a.name : '') + (a.simulable ? '' : ' (non simulable)') }));
  X.providerOptions = (asset) => [{ value: '', label: '— provider du preset —' }].concat(((asset && asset.providers) || []).map((p) => ({ value: p.provider, label: p.provider })));

  X.tabs = function (active) {
    const keep = () => { const q = new URLSearchParams(); ['asset', 'provider'].forEach((k) => { if (X.param(k)) q.set(k, X.param(k)); }); const s = q.toString(); return s ? '?' + s : ''; };
    return h('nav', { class: 'xtabs', 'aria-label': 'Espace Expert' }, [['trend', 'Trend'], ['rainbow', 'Rainbow'], ['affectation', 'Affectation']].map(([k, l]) =>
      h('a', { class: 'xtab', href: '/app/expert/' + k + keep(), 'aria-current': k === active ? 'page' : null, onclick: (e) => { e.preventDefault(); if (k !== active) X.go('/app/expert/' + k + keep()); } }, l)));
  };
  X.mobileNotice = () => h('div', { class: 'banner banner--warn xmobile', role: 'note' }, "Vue incomplète sur téléphone : l'Espace Expert se lit et se règle mieux sur un PC.");

  // ---------------------------------------------------------------- plage de simulation
  const MONTHS = { '6m': 6, '12m': 12, '24m': 24 };
  function isoMonthsAgo(n) { const d = new Date(); d.setUTCMonth(d.getUTCMonth() - n); return d.toISOString().slice(0, 10); }
  /** État de plage { mode: '6m'|'12m'|'24m'|'max'|'custom', from, to } → paramètres de requête. */
  X.rangeParams = (r) => {
    if (r.mode === 'max') return { from: '2009-01-01', to: null };
    if (MONTHS[r.mode]) return { from: isoMonthsAgo(MONTHS[r.mode]), to: null };
    return { from: r.from || null, to: r.to || null };
  };
  X.rangeControl = function (range, onChange) {
    const from = h('input', { type: 'date', 'aria-label': 'Début' }), to = h('input', { type: 'date', 'aria-label': 'Fin' });
    from.value = range.from || ''; to.value = range.to || '';
    const periods = ui.periods([{ value: '6m', label: '6 mois' }, { value: '12m', label: '12 mois' }, { value: '24m', label: '24 mois' }, { value: 'max', label: 'Maximum' }], range.mode,
      (v) => { range.mode = v; range.from = null; range.to = null; from.value = ''; to.value = ''; onChange(); });
    const custom = () => { range.mode = 'custom'; range.from = from.value || null; range.to = to.value || null; onChange(); };
    from.addEventListener('change', custom); to.addEventListener('change', custom);
    return h('div', { class: 'row xrange' }, h('span', { class: 'small muted' }, 'Plage'), periods, from, h('span', { class: 'muted' }, '→'), to);
  };

  // ---------------------------------------------------------------- mémoire de la dernière simulation (paramètres seulement)
  const memKey = (type, asset, presetId) => 'tio.expert.last.' + type + '.' + asset + '.' + (presetId || '');
  X.memory = {
    get(type, asset, presetId) { try { return JSON.parse(g.localStorage.getItem(memKey(type, asset, presetId))); } catch (e) { return null; } },
    set(type, asset, presetId, v) { try { g.localStorage.setItem(memKey(type, asset, presetId), JSON.stringify(v)); } catch (e) { /* ignoré */ } },
  };

  // ---------------------------------------------------------------- simulation asynchrone
  const STATES = { QUEUED: 'En file d’attente…', LOADING_DATA: 'Chargement des bougies…', COMPUTING: 'Calcul en cours…' };
  /** Lance et suit une simulation. cb : { onProgress(text, pct), onDone(result), onError(err) }. Retourne { cancel() }. */
  X.simulate = function (body, cb) {
    let stop = false, id = null;
    (async () => {
      try {
        const created = await X.req('POST', API + '/simulations', body);
        id = created.id;
        for (;;) {
          if (stop) return;
          const sim = await X.req('GET', API + '/simulations/' + id);
          if (sim.state === 'DONE') { cb.onDone(sim.result); return; }
          if (sim.state === 'FAILED' || sim.state === 'CANCELLED') {
            const er = sim.error;
            cb.onError(er ? new XError(422, { error: er.message, code: er.code, details: er.details }) : new XError(500, { error: 'Simulation interrompue' }));
            return;
          }
          cb.onProgress(STATES[sim.state] || sim.state, Math.round((sim.progress || 0) * 100));
          await new Promise((r) => g.setTimeout(r, 700));
        }
      } catch (e) { if (!stop) cb.onError(e); }
    })();
    return { cancel() { stop = true; if (id) X.req('DELETE', API + '/simulations/' + id).catch(() => null); } };
  };

  // ---------------------------------------------------------------- fenêtre de résultats (14 lignes du pine + métriques Trend)
  function lineValue(l) {
    let v = null;
    if (l.value !== null && l.value !== undefined) v = l.unit === '$' ? F.usd(l.value, Math.abs(l.value) >= 1000 ? 0 : 2) : l.unit === '%' ? F.pct(l.value, 1) : F.num(l.value, 2);
    if (v && l.text) return v + ' · ' + l.text;
    return v || l.text || F.dash;
  }
  X.resultsWindow = function (result, kindTrend) {
    const m = result.meta;
    const substituted = m.substituted ? h('div', { class: 'banner banner--warn' }, 'Provider substitué : demandé ' + (m.requestedProvider || '—') + ', utilisé ' + (m.usedProvider || '—') + '.') : null;
    const warns = (m.warnings || []).map((w) => h('div', { class: 'banner banner--warn' }, w));
    const info = h('div', { class: 'small muted' }, [m.assetSymbol, m.usedProvider ? 'via ' + m.usedProvider + (m.providerSymbol ? ' (' + m.providerSymbol + ')' : '') : null, F.dayLong(m.from) + ' → ' + F.dayLong(m.to), m.candles + ' bougies',
      m.trendLabel ? 'Trend : ' + m.trendLabel : null, m.rainbowLabel ? 'Rainbow : ' + m.rainbowLabel : null].filter(Boolean).join(' · '));
    const lines = h('div', { class: 'scroll-x' }, h('table', { class: 'tbl' }, h('tbody', null, (result.lines || []).map((l) => h('tr', null, h('td', { class: 'muted' }, l.label), h('td', { class: 'num' + (l.unit && l.value !== null && /gain|Gain|PnL/.test(l.label) ? ' ' + TIO.sign(l.value) : '') }, lineValue(l)))))));
    let metrics = null;
    if (kindTrend && result.trendMetrics) {
      const t = result.trendMetrics;
      metrics = h('div', { class: 'stats' }, ui.stat('Bascules', F.num(t.switches)), ui.stat('Phases', F.num(t.phases)), ui.stat('Durée moyenne', F.days(t.averagePhaseBars)),
        ui.stat('Durée moy. UP', F.days(t.averageUpBars)), ui.stat('Durée moy. DOWN', F.days(t.averageDownBars)), ui.stat('Plus longue / courte', F.days(t.longestPhaseBars) + ' / ' + F.days(t.shortestPhaseBars)),
        ui.stat('Part UP', F.pct(t.upShare * 100, 0)), ui.stat('Barres résolues / non', F.num(t.resolvedBars) + ' / ' + F.num(t.unresolvedBars)), ui.stat('Dernier régime', t.lastRegime || F.dash));
    }
    const phases = kindTrend && (result.phases || []).length ? h('details', null, h('summary', { class: 'small' }, 'Phases et bascules'),
      h('div', { class: 'scroll-x' }, h('table', { class: 'tbl' }, h('thead', null, h('tr', null, ['Régime', 'Début', 'Fin', 'Barres'].map((t) => h('th', null, t)))),
        h('tbody', null, result.phases.map((p) => h('tr', null, h('td', null, p.regime), h('td', null, F.dayLong(p.start)), h('td', null, F.dayLong(p.end)), h('td', null, F.num(p.bars)))))))) : null;
    return h('div', { class: 'stack' }, info, substituted, warns, metrics, lines, phases);
  };

  /**
   * Panneau « simulation » réutilisable (bouton, avancement, erreurs, résultat périmé, graphique). cfg :
   * { type, kind (clé de mémorisation du graphique), build() → {body, key} | null (corps de requête + empreinte des entrées), onFieldErrors(map), canRun() }.
   * Retourne { el, touch() (à appeler dès qu'une entrée change), run(), memory(v) }.
   */
  X.simPanel = function (cfg) {
    const status = h('div', { class: 'stack' }), results = h('div', { class: 'stack' }), chartBox = h('div', { class: 'xchart' });
    const btn = h('button', { type: 'button', class: 'btn btn--primary' }, 'Simuler');
    const cancel = h('button', { type: 'button', class: 'btn', hidden: true }, 'Annuler');
    const staleChip = h('span', { class: 'chip chip--warn', hidden: true }, 'Résultat périmé : entrées modifiées depuis la simulation');
    let chart = null, job = null, lastKey = null, hasResult = false;
    const bar = h('div', { class: 'row' }, btn, cancel, staleChip);
    function ensureChart() { if (!chart) chart = TIO.expertChart(chartBox, { kind: cfg.kind }); return chart; }
    function setIdle() { btn.disabled = false; cancel.hidden = true; }
    function run() {
      const req = cfg.build();
      if (!req || job) return;
      TIO.mount(status);
      btn.disabled = true; cancel.hidden = false;
      const prog = h('div', { class: 'row small muted' });
      TIO.mount(status, prog);
      job = X.simulate(req.body, {
        onProgress: (t, pct) => TIO.mount(prog, t + ' ' + pct + ' %', ui.bar(pct)),
        onDone: (res) => {
          job = null; setIdle(); TIO.mount(status);
          lastKey = req.key; hasResult = true; staleChip.hidden = true; btn.textContent = 'Simuler';
          TIO.mount(results, X.resultsWindow(res, cfg.type === 'TREND'));
          const c = ensureChart();
          if (c) c.update(res.chart);
          if (cfg.onDone) cfg.onDone(res);
        },
        onError: (e) => {
          job = null; setIdle();
          TIO.mount(status, h('div', { class: 'banner banner--down', role: 'alert' }, X.errorText(e)));
          if (e instanceof XError && e.code === 'VALIDATION' && cfg.onFieldErrors) cfg.onFieldErrors(e.details);
        },
      });
    }
    btn.addEventListener('click', run);
    cancel.addEventListener('click', () => { if (job) { job.cancel(); job = null; } setIdle(); TIO.mount(status, h('div', { class: 'small muted' }, 'Simulation annulée.')); });
    return {
      el: h('div', { class: 'stack' }, bar, status, chartBox, results),
      run,
      touch() { const req = cfg.build(); staleChip.hidden = !(hasResult && (!req || req.key !== lastKey)); },
      setLabel(t) { btn.textContent = t; },
      setEnabled(b) { btn.disabled = !b || !!job; },
    };
  };
})(window);
