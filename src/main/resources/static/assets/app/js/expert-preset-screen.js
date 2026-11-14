/* Espace Expert : écran de preset commun aux onglets Trend et Rainbow (sélecteurs, formulaire typé, droits, enregistrement / duplication / suppression
   avec confirmations, simulation et graphique). Les différences Trend / Rainbow sont portées par la configuration passée à X.presetScreen. */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, X = TIO.expert;
  const API = '/api/expert';

  const TREND_CFG = {
    kind: 'trend', tab: 'trend', simType: 'TREND', title: 'Trend',
    groups: X.TREND_GROUPS,
    toValues: (d) => ({ name: d.header.name, referenceProvider: d.header.referenceProvider || null, trend: d.trend, rangeMapping: d.rangeMapping }),
    toBody: (v, rev) => ({ name: v.name, referenceProvider: v.referenceProvider || null, trend: v.trend, rangeMapping: v.rangeMapping, revision: rev }),
    draftOf: (v) => ({ trend: v.trend, rangeMapping: v.rangeMapping }),
    refKey: 'trend',
  };
  const RAINBOW_CFG = {
    kind: 'rainbow', tab: 'rainbow', simType: 'RAINBOW', title: 'Rainbow',
    groups: X.RAINBOW_GROUPS.map((grp, i) => [grp[0], grp[1], { fold: 'rainbow.' + i, tone: 'mix' }]),
    toValues: (d) => ({ name: d.header.name, referenceProvider: d.header.referenceProvider || null, analysisWindowMonths: d.analysisWindowMonths, tuning: d.tuning, globals: d.globals }),
    toBody: (v, rev) => ({ name: v.name, referenceProvider: v.referenceProvider || null, analysisWindowMonths: v.analysisWindowMonths, tuning: v.tuning, globals: v.globals, revision: rev }),
    draftOf: (v) => ({ analysisWindowMonths: v.analysisWindowMonths, tuning: v.tuning, globals: v.globals }),
    refKey: 'rainbow', signal: true,
  };
  X.presetConfigs = { trend: TREND_CFG, rainbow: RAINBOW_CFG };

  const settingsOf = (v) => { const c = JSON.parse(JSON.stringify(v)); delete c.name; delete c.referenceProvider; return JSON.stringify(c); };

  /** opts.regime ('UP' | 'DOWN') : instance figée sur un régime (liste filtrée, signal fixe) ; opts.embedded : retourne l'élément au lieu de monter la page. */
  X.presetScreen = function (cfg, root, opts) {
    opts = opts || {};
    const rg = opts.regime || null, rgSuffix = rg ? '.' + rg : '';
    const st = { assets: [], asset: null, provider: X.param('provider') || '', presets: [], header: null, baseline: null, form: null, range: { mode: '24m', from: null, to: null }, signal: rg || 'UP' };
    const selBox = h('div', { class: 'xgrid' }), presetBox = h('div', { class: 'stack' }), simBox = h('div', { class: 'stack' }), rangeBox = h('div');
    const url = (suffix) => API + '/' + cfg.kind + '-presets' + (suffix || '');
    const isDirty = () => !!(st.form && st.baseline && JSON.stringify(st.form.get()) !== JSON.stringify(st.baseline));
    const prevDirty = X.guard.dirty; X.guard.dirty = () => prevDirty() || isDirty();

    const assetInfo = () => st.assets.find((a) => a.symbol === st.asset) || null;
    const confirmDiscard = async () => !isDirty() || X.confirm('Modifications non enregistrées', ['Le brouillon en cours sera perdu.'], 'Abandonner', true);

    // ---- simulation
    const sim = X.simPanel({
      type: cfg.simType, kind: cfg.kind,
      build() {
        if (!st.header || !st.form) return null;
        const rp = X.rangeParams(st.range), vals = st.form.get();
        const ref = isDirty() ? { draft: cfg.draftOf(vals) } : { id: st.header.id };
        const body = { type: cfg.simType, assetSymbol: st.asset, provider: st.provider || null, from: rp.from, to: rp.to };
        body[cfg.refKey] = ref;
        if (cfg.signal) body.signal = st.signal;
        return { body, key: JSON.stringify(body) };
      },
      onFieldErrors: (map) => st.form && st.form.setErrors(map),
      onDone: () => X.memory.set(cfg.simType, st.asset, st.header.id, { provider: st.provider, range: st.range, signal: st.signal }),
    });

    const simBtn = sim.mirrorButton();
    function refresh() { renderActions(); sim.touch(); }

    // régime d'un preset : rôle de base (système) ou installation dans l'Affectation ; sinon nom « … Bull / Bear » ; sans indice : visible partout
    function regimesOf(p) {
      const set = new Set();
      if (p.baseRole === 'RAINBOW_BULL') set.add('UP'); else if (p.baseRole === 'RAINBOW_BEAR') set.add('DOWN');
      (p.usedBy || []).forEach((u) => { if (u.role === 'RAINBOW_UP') set.add('UP'); else if (u.role === 'RAINBOW_DOWN') set.add('DOWN'); });
      if (!set.size) { if (/\bbull\b/i.test(p.name)) set.add('UP'); else if (/\bbear\b/i.test(p.name)) set.add('DOWN'); }
      return set;
    }
    const visible = () => (rg ? st.presets.filter((p) => { const r = regimesOf(p); return !r.size || r.has(rg); }) : st.presets);

    // ---- chargement
    async function loadPresets(preferId) {
      st.presets = await X.presets(cfg.kind, st.asset);
      let id = preferId;
      if (!id) { try { id = Number(g.localStorage.getItem('tio.expert.preset.' + cfg.kind + '.' + st.asset + rgSuffix)); } catch (e) { id = null; } }
      const list = visible();
      const found = list.find((p) => p.id === id) || list.find((p) => p.origin === 'SYSTEM') || list[0];
      if (!found) { st.header = null; st.form = null; renderAll(); return; }
      await loadPreset(found.id);
    }
    async function loadPreset(id) {
      const d = await X.preset(cfg.kind, id);
      st.header = d.header;
      st.baseline = cfg.toValues(d);
      const a = assetInfo();
      st.form = X.form(cfg.groups, st.baseline, { providers: ((a && a.providers) || []).map((p) => p.provider), onChange: refresh });
      st.form.setReadOnly(!st.header.editable);
      try { g.localStorage.setItem('tio.expert.preset.' + cfg.kind + '.' + st.asset + rgSuffix, String(id)); } catch (e) { /* ignoré */ }
      const mem = X.memory.get(cfg.simType, st.asset, id);
      if (mem) { st.range = mem.range || st.range; if (!opts.ctx && !X.param('provider') && mem.provider !== undefined) st.provider = mem.provider; if (mem.signal && !rg) st.signal = mem.signal; sim.setLabel('Relancer'); } else sim.setLabel('Simuler');
      renderAll();
    }
    async function switchAsset(sym) {
      if (!(await confirmDiscard())) { renderSelectors(); return; }
      st.asset = sym; X.setParam('asset', sym);
      if (!assetInfo() || !assetInfo().providers.some((p) => p.provider === st.provider)) { st.provider = ''; X.setParam('provider', ''); }
      try { await loadPresets(); } catch (e) { TIO.mount(presetBox, ui.error(e, () => switchAsset(sym))); }
    }
    async function switchPreset(id) {
      if (!(await confirmDiscard())) { renderSelectors(); return; }
      try { await loadPreset(Number(id)); } catch (e) { TIO.mount(presetBox, ui.error(e)); }
    }

    // ---- rendu
    function presetSelect() {
      const mk = (list) => list.map((p) => h('option', { value: String(p.id) }, p.name + ((p.usedBy || []).length ? ' · installé' : '')));
      const vis = visible(), sys = vis.filter((p) => p.origin === 'SYSTEM'), mine = vis.filter((p) => p.origin !== 'SYSTEM');
      const sel = h('select', null, sys.length ? h('optgroup', { label: 'Système' }, mk(sys)) : null, mine.length ? h('optgroup', { label: 'Mes presets' }, mk(mine)) : null);
      sel.value = st.header ? String(st.header.id) : '';
      sel.addEventListener('change', () => switchPreset(sel.value));
      return ui.field(rg ? 'Preset ' + (rg === 'UP' ? 'Up (Bull)' : 'Down (Bear)') : 'Preset', sel, st.header && st.header.referenceProvider ? 'Provider de référence : ' + st.header.referenceProvider : null);
    }
    function renderSelectors() {
      if (opts.ctx) { TIO.mount(selBox, [presetSelect()]); return; }   // actif / provider / régime : écran englobant
      const a = assetInfo();
      const nodes = [
        X.selectField('Actif', X.assetOptions(st.assets), st.asset, switchAsset),
        X.selectField('Provider', X.providerOptions(a), st.provider, (v) => { st.provider = v; X.setParam('provider', v); sim.touch(); }, 'Source des bougies de la simulation'),
        presetSelect(),
      ];
      if (cfg.signal && !rg) nodes.push(X.selectField('Régime simulé', [{ value: 'UP', label: 'UP (Bull)' }, { value: 'DOWN', label: 'DOWN (Bear)' }], st.signal, (v) => { st.signal = v; sim.touch(); },
        'Le preset ne joue que sur ce régime du Trend de ton Affectation'));
      TIO.mount(selBox, nodes);
    }
    function renderActions() {
      const box = presetBox.querySelector('.xactions');
      if (!box || !st.header) return;
      const hd = st.header, dirty = isDirty(), system = hd.origin === 'SYSTEM';
      const btn = (label, cls, fn, disabled) => h('button', { type: 'button', class: 'btn ' + cls, disabled: !!disabled, onclick: fn }, label);
      TIO.mount(box,
        simBtn,
        hd.editable ? btn('Enregistrer', 'btn--primary', save, !dirty) : null,
        hd.editable ? btn('Annuler les modifications', '', discard, !dirty) : null,
        btn('Dupliquer', system && !hd.editable ? 'btn--primary' : '', duplicate),
        hd.deletable ? btn('Supprimer', 'btn--danger', remove) : null,
        dirty ? ui.chip('Brouillon non enregistré', 'warn') : null);
    }
    function renderPreset() {
      if (!st.header) { TIO.mount(presetBox, ui.empty('Aucun preset', 'Aucun preset disponible pour ' + st.asset + '.')); return; }
      const hd = st.header, system = hd.origin === 'SYSTEM';
      const note = system && !hd.editable ? h('div', { class: 'banner' }, 'Preset système : duplique-le pour le modifier. Tu peux le simuler tel quel.')
        : system ? h('div', { class: 'banner banner--warn' }, 'Preset système (administrateur) : toute modification se propage à toutes les Affectations qui l’utilisent.') : null;
      const usage = (hd.usedBy || []).length ? h('div', { class: 'small muted' }, 'Installé dans ton Affectation : ' + hd.usedBy.map((u) => u.role.replace('RAINBOW_', 'Rainbow ').replace('TREND', 'Trend') + (u.enabled ? ' (moteur actif)' : '')).join(', ')) : null;
      TIO.mount(presetBox, h('div', { class: 'row xactions' }), note, usage, st.form.el);
      renderActions();
    }
    function renderAll() { renderSelectors(); renderPreset(); TIO.mount(rangeBox, X.rangeControl(st.range, () => sim.touch())); sim.touch(); }

    // ---- actions
    function discard() { st.form.set(st.baseline); st.form.setErrors({}); st.form.setReadOnly(!st.header.editable); refresh(); }
    async function save() {
      const vals = st.form.get(), hd = st.header;
      const settingsChanged = settingsOf(vals) !== settingsOf(st.baseline);
      const lines = [];
      if (settingsChanged && hd.origin === 'SYSTEM') {
        try {
          const imp = await X.req('GET', url('/' + hd.id + '/impact'));
          lines.push('Preset système « ' + hd.name + ' » : la modification se propage à ' + imp.affectations + ' Affectation(s), dont ' + imp.activeEngines + ' à moteur actif, et réinitialise leur moteur.');
          if (imp.activeEngines > 0) lines.push({ warn: true, text: 'Moteurs actifs concernés : ' + imp.activeEngines + '. L’enregistrement réinitialise leur état.' });
        } catch (e) { TIO.toast(X.errorText(e), true); return; }
      } else if (settingsChanged) {
        const active = (hd.usedBy || []).filter((u) => u.enabled);
        if (active.length) lines.push({ warn: true, text: 'Moteur actif sur ' + [...new Set(active.map((u) => u.assetSymbol))].join(', ') + ' : l’enregistrement réinitialise son état.' });
      }
      let asNew = false;
      if (hd.origin === 'SYSTEM' && hd.editable && vals.name !== hd.name) {
        const pick = await X.modal('Nom modifié', [
          h('p', null, 'Vous avez modifié le nom : « ' + hd.name + ' » -> « ' + vals.name + ' ».'),
          h('p', null, 'Renommer : le preset existant est modifié en place (réglages et nom, propagés aux Affectations).'),
          h('p', null, 'Créer nouveau : un nouveau preset système est ajouté, l’original reste inchangé.'),
          ...lines.map((l) => h('p', l.warn ? { class: 'err' } : null, l.text || l))],
          [{ label: 'Annuler', value: false }, { label: 'Créer nouveau', value: 'new' }, { label: 'Renommer', value: 'rename', primary: true }]);
        if (!pick) return;
        asNew = pick === 'new';
      } else if (lines.length && !(await X.confirm('Enregistrer « ' + hd.name + ' »', lines, 'Enregistrer'))) return;
      try {
        const d = asNew ? await X.req('POST', url('/' + hd.id + '/save-as-system'), cfg.toBody(vals, hd.revision))
                        : await X.req('PUT', url('/' + hd.id), cfg.toBody(vals, hd.revision));
        TIO.toast(asNew ? 'Nouveau preset système créé' : 'Preset enregistré');
        if (asNew) { st.baseline = null; await loadPresets(d.header.id); return; }
        st.presets = await X.presets(cfg.kind, st.asset);
        st.header = d.header; st.baseline = cfg.toValues(d);
        st.form.set(st.baseline); st.form.setErrors({}); st.form.setReadOnly(!st.header.editable);
        renderAll();
      } catch (e) {
        if (e instanceof X.Error && e.code === 'PRESET_REVISION_CONFLICT') {
          if (await X.confirm('Preset modifié entre-temps', ['Une autre modification a été enregistrée. Recharger la version à jour ? Ton brouillon sera perdu.'], 'Recharger')) { st.baseline = null; await loadPresets(hd.id); }
        } else if (e instanceof X.Error && e.code === 'VALIDATION') { st.form.setErrors(e.details); TIO.toast(X.errorText(e), true); }
        else TIO.toast(X.errorText(e), true);
      }
    }
    async function duplicate() {
      const hd = st.header;
      const assetSel = h('select', null, st.assets.map((a) => h('option', { value: a.symbol }, a.symbol))); assetSel.value = st.asset;
      const name = h('input', { type: 'text', maxlength: '60' }); name.value = hd.name + ' (copie)';
      const err = h('div', { class: 'err', role: 'alert' });
      const body = [ui.field('Actif cible', assetSel), ui.field('Nom de la copie', name), err];
      for (;;) {
        const ok = await X.modal('Dupliquer « ' + hd.name + ' »', body, [{ label: 'Annuler', value: false }, { label: 'Dupliquer', value: true, primary: true }]);
        if (ok !== true) return;
        try {
          const d = await X.req('POST', url('/' + hd.id + '/duplicate'), { assetSymbol: assetSel.value, name: name.value });
          TIO.toast('Copie créée');
          if (assetSel.value !== st.asset) { st.baseline = null; st.asset = assetSel.value; X.setParam('asset', st.asset); }
          await loadPresets(d.header.id);
          return;
        } catch (e) { err.textContent = e instanceof X.Error && e.code === 'VALIDATION' ? Object.values(e.details || {}).join(' ; ') : X.errorText(e); }
      }
    }
    async function remove() {
      const hd = st.header;
      if (!(await X.confirm('Supprimer « ' + hd.name + ' »', ['La suppression est définitive.'], 'Supprimer', true))) return;
      try { await X.req('DELETE', url('/' + hd.id)); TIO.toast('Preset supprimé'); st.baseline = null; await loadPresets(); }
      catch (e) {
        if (e instanceof X.Error && e.code === 'PRESET_IN_USE') {
          const list = (e.details || []).map((u) => h('li', null, u.assetSymbol + ' · ' + u.role));
          await X.modal('Preset utilisé', [h('p', null, 'Ce preset est installé dans :'), h('ul', null, list), h('p', null, 'Change l’Affectation avant de le supprimer.')], [{ label: 'Fermer', value: true, primary: true }]);
        } else TIO.toast(X.errorText(e), true);
      }
    }

    // ---- assemblage : graphique à gauche, réglages du preset à droite (largeur réglable), empilés sur écran étroit
    const SIDE_KEY = 'tio.expert.sidewidth', HIDE_KEY = 'tio.expert.sidehidden';
    const store = { get(k) { try { return g.localStorage.getItem(k); } catch (e) { return null; } }, set(k, v) { try { g.localStorage.setItem(k, v); } catch (e) { /* ignoré */ } } };
    if (root) root.classList.add('tio-main--wide');
    const sideCard = ui.card('Preset ' + cfg.title + (rg ? (rg === 'UP' ? ' · Bull' : ' · Bear') : ''), presetBox);
    sideCard.classList.add('xwork-side');
    const split = h('div', { class: 'xsplit', role: 'separator', 'aria-orientation': 'vertical', tabindex: '0', title: 'Glisser pour redimensionner (double-clic : taille par défaut)' }, h('i'));
    const work = h('div', { class: 'xwork' });
    const toggle = h('button', { type: 'button', class: 'btn xwide-only' });
    function applySide() {
      const hidden = !opts.embedded && store.get(HIDE_KEY) === '1', w = Number(store.get(SIDE_KEY));
      work.classList.toggle('xwork--noside', hidden);
      if (w > 0) work.style.setProperty('--xside', w + 'px'); else work.style.removeProperty('--xside');
      toggle.textContent = hidden ? 'Afficher les réglages' : 'Masquer les réglages';
    }
    function setWidth(px) {
      const total = work.getBoundingClientRect().width;
      const w = Math.round(Math.min(Math.max(px, 300), Math.max(300, total * 0.7)));
      store.set(SIDE_KEY, String(w)); applySide(); return w;
    }
    split.addEventListener('pointerdown', (e) => {
      e.preventDefault(); split.setPointerCapture(e.pointerId); split.classList.add('xsplit--drag');
      const move = (ev) => setWidth(work.getBoundingClientRect().right - ev.clientX - 3);
      const up = () => { split.classList.remove('xsplit--drag'); split.removeEventListener('pointermove', move); split.removeEventListener('pointerup', up); split.removeEventListener('pointercancel', up); };
      split.addEventListener('pointermove', move); split.addEventListener('pointerup', up); split.addEventListener('pointercancel', up);
    });
    split.addEventListener('dblclick', () => { try { g.localStorage.removeItem(SIDE_KEY); } catch (e) { /* ignoré */ } applySide(); });
    split.addEventListener('keydown', (e) => {
      if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
      e.preventDefault();
      const cur = sideCard.getBoundingClientRect().width;
      setWidth(cur + (e.key === 'ArrowLeft' ? 24 : -24));
    });
    toggle.addEventListener('click', () => { store.set(HIDE_KEY, store.get(HIDE_KEY) === '1' ? '0' : '1'); applySide(); });
    const selCard = ui.card(opts.ctx ? (rg === 'UP' ? 'Bull (Up)' : 'Bear (Down)') : 'Sélection', selBox), simCard = ui.card('Simulation', h('div', { class: 'row' }, rangeBox, toggle), sim.el);
    selCard.classList.add('xwork-sel'); simCard.classList.add('xwork-sim');
    TIO.mount(work, h('div', { class: 'xwork-left' }, selCard, simCard), sideCard, split);
    applySide();
    if (!opts.embedded) TIO.mount(root, ui.pageHeader('Espace Expert', cfg.title), X.mobileNotice(), X.tabs(cfg.tab), work);
    TIO.mount(presetBox, ui.loading(4));
    (async () => {
      if (opts.ctx) {
        try { st.assets = opts.ctx.assets; st.asset = opts.ctx.asset; st.provider = opts.ctx.provider; await loadPresets(); } catch (e) { TIO.mount(presetBox, ui.error(e, () => g.location.reload())); }
        return;
      }
      try {
        st.assets = await X.loadAssets();
        if (!st.assets.length) { TIO.mount(presetBox, ui.empty('Aucun actif', 'Aucun actif disponible.')); return; }
        st.asset = (X.param('asset') && st.assets.find((a) => a.symbol === X.param('asset'))) ? X.param('asset') : (st.assets.find((a) => a.simulable) || st.assets[0]).symbol;
        X.setParam('asset', st.asset);
        await loadPresets();
      } catch (e) { TIO.mount(presetBox, ui.error(e, () => g.location.reload())); }
    })();
    work.setProvider = (v) => { st.provider = v; sim.touch(); };
    work.setAsset = async (sym) => { st.asset = sym; try { await loadPresets(); } catch (e) { TIO.mount(presetBox, ui.error(e)); } };
    return work;
  };

  /** Écran Rainbow par régime : sélection commune (actif, provider, régime simulé UP / DOWN / Les 2) puis, par régime, preset + formulaire + simulation.
   *  « Les 2 » : deux colonnes (Up / Down), chacune avec son menu de preset. Paramètre d'URL « regime ». */
  X.regimeScreen = function (cfg, root) {
    const VIEWS = { up: ['UP'], down: ['DOWN'], both: ['UP', 'DOWN'] };
    const ctx = { assets: [], asset: null, provider: X.param('provider') || '', view: VIEWS[X.param('regime')] ? X.param('regime') : 'up' };
    let insts = [];
    root.classList.add('tio-main--wide');
    const selBox = h('div', { class: 'xgrid' }), body = h('div');
    const assetInfo = () => ctx.assets.find((a) => a.symbol === ctx.asset) || null;
    const confirmDiscard = async () => !X.guard.dirty() || X.confirm('Modifications non enregistrées', ['Le brouillon en cours sera perdu.'], 'Abandonner', true);
    function paintSel() {
      TIO.mount(selBox,
        X.selectField('Actif', X.assetOptions(ctx.assets), ctx.asset, async (v) => {
          if (!(await confirmDiscard())) { paintSel(); return; }
          ctx.asset = v; X.setParam('asset', v);
          if (!assetInfo() || !assetInfo().providers.some((p) => p.provider === ctx.provider)) { ctx.provider = ''; X.setParam('provider', ''); }
          paintSel(); insts.forEach((i) => { i.setProvider(ctx.provider); i.setAsset(v); });
        }),
        X.selectField('Provider', X.providerOptions(assetInfo()), ctx.provider, (v) => { ctx.provider = v; X.setParam('provider', v); insts.forEach((i) => i.setProvider(v)); }, 'Source des bougies de la simulation'),
        X.selectField('Régime simulé', [{ value: 'up', label: 'UP (Bull)' }, { value: 'down', label: 'DOWN (Bear)' }, { value: 'both', label: 'Les 2 (Bull et Bear)' }], ctx.view, async (v) => {
          if (!(await confirmDiscard())) { paintSel(); return; }
          ctx.view = v; X.setParam('regime', v); render();
        }, 'Le preset ne joue que sur ce régime du Trend de ton Affectation'));
    }
    function render() {
      X.guard.dirty = () => false;
      insts = VIEWS[ctx.view].map((r) => X.presetScreen(cfg, null, { regime: r, embedded: true, ctx }));
      TIO.mount(body, insts.length > 1 ? h('div', { class: 'xdual' }, insts) : insts);
    }
    TIO.mount(root, ui.pageHeader('Espace Expert', cfg.title), X.mobileNotice(), X.tabs(cfg.tab), ui.card('Sélection', selBox), body);
    TIO.mount(body, ui.loading(4));
    (async () => {
      try {
        ctx.assets = await X.loadAssets();
        if (!ctx.assets.length) { TIO.mount(body, ui.empty('Aucun actif', 'Aucun actif disponible.')); return; }
        ctx.asset = (X.param('asset') && ctx.assets.find((a) => a.symbol === X.param('asset'))) ? X.param('asset') : (ctx.assets.find((a) => a.simulable) || ctx.assets[0]).symbol;
        X.setParam('asset', ctx.asset);
        if (!assetInfo().providers.some((p) => p.provider === ctx.provider)) ctx.provider = '';
        paintSel(); render();
      } catch (e) { TIO.mount(body, ui.error(e, () => g.location.reload())); }
    })();
  };
})(window);
