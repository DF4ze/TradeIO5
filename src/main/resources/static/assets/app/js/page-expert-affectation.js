/* Espace Expert · onglet Affectation : choix des presets Trend / Rainbow UP / Rainbow DOWN d'un actif, écarts de provider, état du moteur (lecture seule),
   simulation « Affectation » (phases UP / DOWN colorées, preset Rainbow actif sur chaque phase). */
(function (g) {
  'use strict';
  const TIO = g.TIO, h = TIO.h, ui = TIO.ui, X = TIO.expert;
  const API = '/api/expert';

  TIO.start(function (root) {
    if (TIO.demo) { TIO.mount(root, ui.pageHeader('Espace Expert'), ui.empty('Pas de démo', "L'Espace Expert n'a pas de mode démo.")); return; }
    const st = { assets: [], asset: null, provider: X.param('provider') || '', trends: [], rainbows: [], saved: null, sel: { trend: null, up: null, down: null }, range: { mode: '24m', from: null, to: null } };
    const selBox = h('div', { class: 'xgrid' }), affBox = h('div', { class: 'stack' }), rangeBox = h('div');
    const same = () => st.saved && st.sel.trend === (st.saved.trend ? st.saved.trend.id : null) && st.sel.up === (st.saved.rainbowUp ? st.saved.rainbowUp.id : null) && st.sel.down === (st.saved.rainbowDown ? st.saved.rainbowDown.id : null);
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
      const [trends, rainbows, aff] = await Promise.all([X.presets('trend', st.asset), X.presets('rainbow', st.asset),
        X.req('GET', API + '/affectations/' + encodeURIComponent(st.asset)).catch((e) => (e instanceof X.Error && e.status === 404 ? null : Promise.reject(e)))]);
      st.trends = trends; st.rainbows = rainbows; st.saved = aff || { trend: null, rainbowUp: null, rainbowDown: null, enabled: false, complete: false, providerGaps: [] };
      st.sel = { trend: st.saved.trend ? st.saved.trend.id : null, up: st.saved.rainbowUp ? st.saved.rainbowUp.id : null, down: st.saved.rainbowDown ? st.saved.rainbowDown.id : null };
      const mem = X.memory.get('AFFECTATION', st.asset, '');
      if (mem) { st.range = mem.range || st.range; if (!X.param('provider') && mem.provider !== undefined) st.provider = mem.provider; sim.setLabel('Relancer'); } else sim.setLabel('Simuler');
      renderSelectors(); TIO.mount(rangeBox, X.rangeControl(st.range, () => sim.touch())); refresh();
    }
    async function switchAsset(sym) {
      if (isDirty() && !(await X.confirm('Sélection non enregistrée', ['La sélection de presets en cours sera perdue.'], 'Abandonner', true))) { renderSelectors(); return; }
      st.asset = sym; X.setParam('asset', sym);
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
      sel.addEventListener('change', () => { st.sel[key] = sel.value ? Number(sel.value) : null; refresh(); });
      return ui.field(label, sel);
    }
    function renderAffectation() {
      const s = st.saved;
      const gaps = (s.providerGaps || []).filter((x) => x.gap).map((x) => h('li', null, x.role.replace('RAINBOW_', 'Rainbow ').replace('TREND', 'Trend') + ' « ' + x.presetName + ' » : provider de référence ' + (x.referenceProvider || '—') + ', liens : ' + ((x.bindingProviders || []).join(', ') || 'aucun')));
      const engine = h('div', { class: 'row' }, ui.chip(s.enabled ? 'Moteur allumé' : 'Moteur éteint', s.enabled ? 'ok' : 'plain'),
        h('a', { class: 'small', href: '/app/assets/' + encodeURIComponent(st.asset) }, 'Fiche de l’actif (interrupteur du moteur)'));
      TIO.mount(affBox, engine,
        h('div', { class: 'xgrid' }, presetField('Trend', st.trends, 'trend'), presetField('Rainbow UP (Bull)', st.rainbows, 'up'), presetField('Rainbow DOWN (Bear)', st.rainbows, 'down')),
        complete() ? null : h('div', { class: 'banner banner--warn' }, 'Affectation incomplète : choisis un preset Trend, un Rainbow UP et un Rainbow DOWN (le même preset peut occuper UP et DOWN).'),
        gaps.length ? h('div', { class: 'banner' }, h('div', null, 'Écart de provider (information) :'), h('ul', null, gaps)) : null,
        h('div', { class: 'row' }, h('button', { type: 'button', class: 'btn btn--primary', disabled: !complete() || same(), onclick: assign }, 'Affecter'),
          isDirty() ? ui.chip('Sélection non enregistrée', 'warn') : null));
    }
    async function assign() {
      const lines = [];
      if (st.saved.enabled) lines.push({ warn: true, text: 'Moteur actif sur ' + st.asset + ' : le changement d’Affectation réinitialise son état.' });
      else lines.push('Affecter ces presets à ' + st.asset + ' ?');
      if (!(await X.confirm('Affecter', lines, 'Affecter'))) return;
      try {
        await X.req('PUT', API + '/affectations/' + encodeURIComponent(st.asset), { trendPresetId: st.sel.trend, rainbowUpId: st.sel.up, rainbowDownId: st.sel.down });
        TIO.toast('Affectation enregistrée');
        await loadAsset();
      } catch (e) { TIO.toast(X.errorText(e), true); }
    }

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
