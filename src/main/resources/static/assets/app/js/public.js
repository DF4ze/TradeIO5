/* TradeIO — pages publiques (accueil, connexion, inscription) : thème sombre par défaut, clair au choix (mémorisé dans ce navigateur). */
(function () {
  'use strict';
  var root = document.documentElement;
  function stored() { try { return localStorage.getItem('tio-theme'); } catch (e) { return null; } }
  root.setAttribute('data-theme', stored() === 'light' ? 'light' : 'dark');
  document.addEventListener('DOMContentLoaded', function () {
    var b = document.getElementById('theme-toggle');
    if (!b) return;
    function sync() {
      var light = root.getAttribute('data-theme') === 'light';
      b.setAttribute('aria-pressed', light ? 'true' : 'false');
      b.setAttribute('aria-label', light ? 'Passer en thème sombre' : 'Passer en thème clair');
    }
    sync();
    b.addEventListener('click', function () {
      var next = root.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
      root.setAttribute('data-theme', next);
      try { localStorage.setItem('tio-theme', next); } catch (e) { /* stockage indisponible : thème valable pour cette page seulement */ }
      sync();
    });
  });
})();
