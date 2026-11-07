/* Espace Expert · onglet Rainbow (écran de preset commun, voir expert-preset-screen.js). */
(function (g) {
  'use strict';
  const TIO = g.TIO;
  TIO.start(function (root) {
    if (TIO.demo) { TIO.mount(root, TIO.ui.pageHeader('Espace Expert'), TIO.ui.empty('Pas de démo', "L'Espace Expert n'a pas de mode démo.")); return; }
    TIO.expert.presetScreen(TIO.expert.presetConfigs.rainbow, root);
  });
})(window);
