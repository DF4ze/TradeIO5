/* TradeIO — démarrage commun : conserve ?demo=1 dans la navigation interne, rend la page. */
(function (g) {
  'use strict';
  const TIO = g.TIO;
  /** Lien interne (ajoute ?demo=1 en mode démo). */
  TIO.href = function (path) { return TIO.demo ? path + (path.includes('?') ? '&' : '?') + 'demo=1' : path; };
  TIO.start = function (render) {
    if (TIO.demo) document.querySelectorAll('a[href^="/app"]').forEach((a) => a.setAttribute('href', TIO.href(a.getAttribute('href'))));
    const root = document.getElementById('app');
    try {
      render(root);
    } catch (e) {
      TIO.mount(root, TIO.ui.error(e, () => g.location.reload()));
    }
  };
})(window);
