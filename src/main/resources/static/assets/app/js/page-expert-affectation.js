/* Espace Expert · onglet Affectation : Affectations nommées d'un actif (Système en lecture seule, les vôtres créées / renommées / supprimées), choix des presets
   Trend / Rainbow UP / Rainbow DOWN, écarts de provider, état (jouée si un lien la référence),
   simulation « Affectation » (phases UP / DOWN colorées, preset Rainbow actif sur chaque phase). */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, X = TIO.expert;
  const API = '/api/expert';

  TIO.start(function (root) {
    if (TIO.demo) { TIO.mount(root, ui.pageHeader('Espace Expert'), ui.empty('Pas de démo', "L'Espace Expert n'a pas de mode démo.")); return; }
    const st = { assets: [], asset: null, provider: X.param('provider') || '', trends: [], rainbows: [], affs: [], name: '', saved: null, sel: { trend: null, up: null, down: null }, range: { mode: '24m', from: null, to: null } };
    const selBox = h('div', { class: 'xgrid' }), affBox = h('div', { class: 'stack' }), rangeBox = h('div');
    const sameSel = () => !!st.saved && st.sel.trend === (st.saved.trend ? st.saved.trend.id : null) && st.sel.up === (st.saved.rainbowUp ? st.saved.rainbowUp.id : null) && st.sel.down === (st.saved.rainbowDown ? st.saved.rainbowDown.id : null);
    const isSystem = () => !!(st.saved && st.saved.system);
    const same = () => st.saved && st.name === (st.saved.name || '') && st.sel.trend === (st.saved.trend ? st.saved.trend.id : null) && st.sel.up === (st.saved.rainbowUp ? st.saved.rainbowUp.id : null) && st.sel.down === (st.saved.rainbowDown ? st.saved.rainbowDown.id : null);
    const isDirty = () => !!st.saved && !same();
    X.guard.dirty = isDirty;
    const assetInfo = () => st.assets.find((a) => a.symbol === st.asset) || null;
    const complete = () => st.sel.trend && st.sel.up && st.sel.down;

    const sim = X.simPanel({
      type: 'AFFECTATION', kind: 'affectation',
      build() {
        if (!complete()) return null;
        const rp = X.rangeParams(st.range);
        const body = { type: 'AFFECTATION', assetSymbol: st.asset, provider: st.provider || null, from: rp.from, to: rp.to,
          trend: { id: st.sel.trend }, rainbowUp: { id: st.sel.up }, rainbowDown: { id: st.sel.down } };
        return { body, key: JSON.stringify(body) };
      },
      onDone: () => X.memory.set('AFFECTATION', st.asset, '', { provider: st.provider, range: st.range }),
    });

    function refresh() { renderAffectation(); sim.setEnabled(!!complete()); sim.touch(); }

    async function loadAsset() {
      const [trends, rainbows, affs] = await Promise.all([X.presets('trend', st.asset), X.presets('rainbow', st.asset),
        X.req('GET', API + '/affectations?asset=' + encodeURIComponent(st.asset))]);
      st.affs = affs;
      const keep = affs.find((a) => a.id === st.affId);
      const aff = keep || affs.find((a) => !a.system) || affs.find((a) => a.system) || null;
      st.affId = aff ? aff.id : null;
      st.trends = trends; st.rainbows = rainbows; st.saved = aff || { trend: null, rainbowUp: null, rainbowDown: null, enabled: false, complete: false, providerGaps: [] };
      st.name = st.saved.name || '';
      st.sel = { trend: st.saved.trend ? st.saved.trend.id : null, up: st.saved.rainbowUp ? st.saved.rainbowUp.id : null, down: st.saved.rainbowDown ? st.saved.rainbowDown.id : null };
      const mem = X.memory.get('AFFECTATION', st.asset, '');
      if (mem) { st.range = mem.range || st.range; if (!X.param('provider') && mem.provider !== undefined) st.provider = mem.provider; sim.setLabel('Relancer'); } else sim.setLabel('Simuler');
      renderSelectors(); TIO.mount(rangeBox, X.rangeControl(st.range, () => sim.touch())); refresh();
    }
    async function switchAsset(sym) {
      if (isDirty() && !(await X.confirm('Sélection non enregistrée', ['La sélection de presets en cours sera perdue.'], 'Abandonner', true))) { renderSelectors(); return; }
      st.asset = sym; st.affId = null; X.setParam('asset', sym);
      if (!assetInfo() || !assetInfo().providers.some((p) => p.provider === st.provider)) { st.provider = ''; X.setParam('provider', ''); }
      try { await loadAsset(); } catch (e) { TIO.mount(affBox, ui.error(e, () => switchAsset(sym))); }
    }
    function renderSelectors() {
      TIO.mount(selBox, X.selectField('Actif', X.assetOptions(st.assets), st.asset, switchAsset),
        X.selectField('Provider', X.providerOptions(assetInfo()), st.provider, (v) => { st.provider = v; X.setParam('provider', v); sim.touch(); }, 'Source des bougies de la simulation'));
    }
    function presetField(label, list, key) {
      const mk = (ps) => ps.map((p) => h('option', { value: String(p.id) }, p.name));
      const sys = list.filter((p) => p.origin === 'SYSTEM'), mine = list.filter((p) => p.origin !== 'SYSTEM');
      const sel = h('select', null, h('option', { value: '' }, '— à choisir —'), sys.length ? h('optgroup', { label: 'Système' }, mk(sys)) : null, mine.length ? h('optgroup', { label: 'Mes presets' }, mk(mine)) : null);
      sel.value = st.sel[key] ? String(st.sel[key]) : '';
      sel.disabled = isSystem();
      sel.addEventListener('change', () => { st.sel[key] = sel.value ? Number(sel.value) : null; refresh(); });
      return ui.field(label, sel);
    }
    function renderAffectation() {
      const s = st.saved;
      const gaps = (s.providerGaps || []).filter((x) => x.gap).map((x) => h('li', null, x.role.replace('RAINBOW_', 'Rainbow ').replace('TREND', 'Trend') + ' « ' + x.presetName + ' » : provider de référence ' + (x.referenceProvider || '—') + ', liens : ' + ((x.bindingProviders || []).join(', ') || 'aucun')));
      const isNew = !s.id;
      const nameInput = h('input', { type: 'text', maxlength: '64', value: st.name, placeholder: 'Nom de l’Affectation', 'aria-label': 'Nom de l’Affectation', disabled: isSystem() });
      let saveBtn = null;
      nameInput.addEventListener('input', () => { st.name = nameInput.value; if (saveBtn) saveBtn.disabled = !complete() || !st.name.trim() || same(); });
      const affSel = h('select', { 'aria-label': 'Affectation' }, st.affs.map((a) => h('option', { value: String(a.id) }, a.name + (a.system ? ' (Système)' : ''))),
        isNew ? h('option', { value: 'new' }, '+ Nouvelle (non enregistrée)') : null, h('option', { value: '__new' }, '+ Nouvelle Affectation…'));
      affSel.value = isNew ? 'new' : String(s.id);
      affSel.addEventListener('change', () => pickAff(affSel.value));
      const engine = h('div', { class: 'row' }, ui.chip(s.enabled ? 'Jouée (lien actif)' : 'Non jouée (aucun lien)', s.enabled ? 'ok' : 'plain'),
        st.saved.name ? h('span', { class: 'small' }, st.saved.name + (st.saved.system ? ' (Système)' : '')) : null,
        h('a', { class: 'small', href: '/app/assets/' + encodeURIComponent(st.asset) }, 'Fiche de l’actif'));
      TIO.mount(affBox, ui.field('Affectation', affSel), ui.field('Nom', nameInput), engine,
        h('div', { class: 'xgrid' }, presetField('Trend', st.trends, 'trend'), presetField('Rainbow UP (Bull)', st.rainbows, 'up'), presetField('Rainbow DOWN (Bear)', st.rainbows, 'down')),
        complete() ? null : h('div', { class: 'banner banner--warn' }, 'Affectation incomplète : choisis un preset Trend, un Rainbow UP et un Rainbow DOWN (le même preset peut occuper UP et DOWN).'),
        gaps.length ? h('div', { class: 'banner' }, h('div', null, 'Écart de provider (information) :'), h('ul', null, gaps)) : null,
        isSystem() ? h('div', { class: 'banner' }, 'Affectation Système : lecture seule, commune à tous. Créez la vôtre pour la modifier.') : null,
        h('div', { class: 'row' },
          isSystem() ? null : (saveBtn = h('button', { type: 'button', class: 'btn btn--primary', disabled: !complete() || !st.name.trim() || same(), onclick: assign }, isNew ? 'Créer' : 'Enregistrer')),
          !isNew && !isSystem() ? h('button', { type: 'button', class: 'btn btn--danger', onclick: remove }, 'Supprimer') : null,
          isDirty() ? ui.chip('Modifications non enregistrées', 'warn') : null));
    }
    async function pickAff(v) {
      if (v === 'new') return;
      if (isDirty() && !(await X.confirm('Modifications non enregistrées', ['Les modifications en cours seront perdues.'], 'Abandonner', true))) { renderAffectation(); return; }
      if (v === '__new') {
        const base = st.affs.find((a) => a.system) || st.saved;
        st.affId = null;
        st.saved = { id: null, name: '', system: false, enabled: false, complete: false, trend: null, rainbowUp: null, rainbowDown: null, providerGaps: [] };
        st.name = ''; st.sel = { trend: base && base.trend ? base.trend.id : null, up: base && base.rainbowUp ? base.rainbowUp.id : null, down: base && base.rainbowDown ? base.rainbowDown.id : null };
        refresh(); return;
      }
      st.affId = Number(v);
      try { await loadAsset(); } catch (e) { TIO.toast(X.errorText(e), true); }
    }
    async function remove() {
      if (!(await X.confirm('Supprimer', ['Supprimer l’Affectation « ' + st.saved.name + ' » ? Impossible si un lien l’a utilisée.'], 'Supprimer', true))) return;
      try { await X.req('DELETE', API + '/affectations/' + st.saved.id); TIO.toast('Affectation supprimée'); st.affId = null; await loadAsset(); } catch (e) { TIO.toast(X.errorText(e), true); }
    }
    async function assign() {
      const lines = [];
      const create = !st.saved.id;
      if (create) lines.push('Créer l’Affectation « ' + st.name.trim() + ' » sur ' + st.asset + ' ?');
      else if (st.saved.enabled && !sameSel()) lines.push({ warn: true, text: 'Affectation jouée (un lien la référence) : changer ses presets réinitialise son moteur, rejoué sur l’historique à la prochaine passe.' });
      else lines.push('Enregistrer « ' + st.name.trim() + ' » ?');
      if (!(await X.confirm('Affectation', lines, create ? 'Créer' : 'Enregistrer'))) return;
      try {
        const body = { name: st.name.trim(), trendPresetId: st.sel.trend, rainbowUpId: st.sel.up, rainbowDownId: st.sel.down };
        if (create) { const made = await X.req('POST', API + '/affectations', Object.assign({ assetSymbol: st.asset }, body)); st.affId = made.id; }
        else await X.req('PUT', API + '/affectations/' + st.saved.id, body);
        TIO.toast('Affectation enregistrée');
        await loadAsset();
      } catch (e) { TIO.toast(X.errorText(e), true); }
    }

    root.classList.add('tio-main--wide');
    TIO.mount(root, ui.pageHeader('Espace Expert', 'Affectation'), X.mobileNotice(), X.tabs('affectation'),
      h('div', { class: 'grid' }, ui.card('Sélection', selBox, ui.cardOpts({ span: true })), ui.card('Affectation', affBox, ui.cardOpts({ span: true })),
        ui.card('Simulation', rangeBox, sim.el, ui.cardOpts({ span: true }))));
    TIO.mount(affBox, ui.loading(3));
    (async () => {
      try {
        st.assets = await X.loadAssets();
        if (!st.assets.length) { TIO.mount(affBox, ui.empty('Aucun actif', 'Aucun actif disponible.')); return; }
        st.asset = (X.param('asset') && st.assets.find((a) => a.symbol === X.param('asset'))) ? X.param('asset') : (st.assets.find((a) => a.simulable) || st.assets[0]).symbol;
        X.setParam('asset', st.asset);
        await loadAsset();
      } catch (e) { TIO.mount(affBox, ui.error(e, () => g.location.reload())); }
    })();
  });
})(window);
