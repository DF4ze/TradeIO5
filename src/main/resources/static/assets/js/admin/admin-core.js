/*
 * Page Admin d'exécution (/admin) : noyau commun. Contrat : API REST /api/admin/** (cf. docs/api/rest-endpoints.md).
 * Tout ce qui vient du serveur ou de l'utilisateur est inséré via textContent / createTextNode (jamais en HTML).
 * Un fichier JS par onglet (console, credentials, bindings, journal) ; les données d'un onglet ne sont chargées qu'à sa
 * première ouverture. Les mêmes motifs de blocage sont traduits ici, dans UNE seule table (BLOCK_MESSAGES).
 */
(function () {
  'use strict';

  const root = document.getElementById('admin-root');
  if (!root) { return; }
  const $ = (id) => document.getElementById(id);

  const DASH = '—';
  const STATE_REFRESH_MS = 20000;

  const core = { state: null, username: root.dataset.username || '' };
  const tabs = new Map();
  const stateListeners = [];

  /* ------------------------------------------------------------------ DOM */

  /** Création de nœud DOM ; les enfants texte passent par createTextNode (pas d'injection HTML). */
  function el(tag, props, ...children) {
    const n = document.createElement(tag);
    if (props) {
      for (const [k, v] of Object.entries(props)) {
        if (v == null || v === false) { continue; }
        if (k === 'class') { n.className = v; }
        else if (k === 'text') { n.textContent = v; }
        else if (k.startsWith('on')) { n.addEventListener(k.slice(2), v); }
        else { n.setAttribute(k, v === true ? '' : v); }
      }
    }
    for (const c of children.flat()) {
      if (c == null || c === false) { continue; }
      n.append(c.nodeType ? c : document.createTextNode(String(c)));
    }
    return n;
  }

  const empty = (text) => el('div', { class: 'text-muted fst-italic', text });

  function fmtDate(value) {
    if (!value) { return DASH; }
    return String(value).replace('T', ' ').replace(/(\.\d+)?Z?$/, '');
  }

  /* ------------------------------------------------------------------ API */

  class ApiError extends Error {
    constructor(status, message) { super(message); this.status = status; }
  }

  /** Appel same-origin (cookie JWT) sur un chemin absolu. 401/403 ⇒ /login ; erreurs serveur {"error": "..."} ⇒ ApiError. */
  async function api(method, path, body) {
    let res;
    try {
      res = await fetch(path, {
        method, credentials: 'same-origin', redirect: 'follow',
        headers: body === undefined ? { Accept: 'application/json' }
          : { Accept: 'application/json', 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body)
      });
    } catch (e) {
      throw new ApiError(0, 'Erreur réseau : serveur injoignable.');
    }
    if (res.status === 401 || res.status === 403 || (res.redirected && /\/login/.test(res.url))) {
      window.location.href = '/login';
      throw new ApiError(res.status || 401, 'Accès refusé ou session expirée, redirection vers la page de connexion.');
    }
    if (res.status === 204) { return null; }
    const text = await res.text();
    let data = null;
    if (text) { try { data = JSON.parse(text); } catch (e) { data = null; } }
    if (!res.ok) {
      throw new ApiError(res.status, (data && data.error) ? data.error : 'Erreur HTTP ' + res.status);
    }
    if (data === null) { throw new ApiError(res.status, 'Réponse inattendue du serveur.'); }
    return data;
  }

  /* ------------------------------------------------------------------ alertes */

  function showAlert(message) {
    const box = $('adm-alert');
    box.textContent = message;
    box.classList.remove('d-none');
  }
  function hideAlert() { $('adm-alert').classList.add('d-none'); }

  /** Exécute une action en désactivant le bouton ; toute erreur est affichée dans le bandeau d'alerte. */
  async function run(button, action) {
    hideAlert();
    if (button) { button.disabled = true; }
    try {
      return await action();
    } catch (e) {
      showAlert(e.message);
      return undefined;
    } finally {
      if (button) { button.disabled = false; }
    }
  }

  /* ------------------------------------------------------------------ motifs de blocage (table unique) */

  const BLOCK_MESSAGES = {
    MODE_NOT_LIVE: ['Mode LIVE non actif : aucun ordre réel n\'est envoyé.',
      'Vérifier le mode et le déverrouillage LIVE (bandeau) ; en DRY_RUN c\'est le comportement attendu.'],
    PORT_UNAVAILABLE: ['Aucun client d\'ordres disponible pour cet exchange.',
      'LIVE doit être déverrouillé et l\'exchange câblé (voir « exchanges » dans le bandeau).'],
    KILL_SWITCH: ['Kill switch engagé : aucun ordre envoyé.', 'Étape 4 : lever le kill switch.'],
    PLAN_EXPIRED: ['Plan expiré (durée de vie dépassée).', 'Étape 1 : replanifier.'],
    PLAN_NOT_EXECUTABLE: ['Plan non exécutable (déjà pris en charge ou statut final).',
      'Choisir un autre plan ou replanifier.'],
    NO_BINDING: ['Aucun binding lié à cet actif et à ce wallet.', 'Onglet Bindings : créer le binding.'],
    BINDING_DISABLED: ['Exécution désactivée sur le binding.',
      'Le propriétaire active « Exécution activée » (onglet Bindings).'],
    NOT_TRADABLE: ['Actif non tradable sur cet exchange.', 'Onglet Bindings : revérifier le binding.'],
    NOT_TRADABLE_WITHOUT_FIAT: ['Aucun chemin sans passage par le fiat (taxation) pour cet actif.',
      'Choisir un autre exchange ou renoncer à cet actif.'],
    CREDENTIAL_INVALID: ['Clé TRADE absente, désactivée, refusée ou illisible.',
      'Onglet Credentials : saisir ou réactiver la clé (clé maître présente ?).'],
    FIRST_LIVE_NOT_VALIDATED: ['1er ordre réel non validé (armement puis confirmation).',
      'Étapes 2 et 3 : armer, puis le propriétaire confirme.'],
    NO_BASE_AMOUNT: ['Montant de base introuvable pour calculer les plafonds.',
      'Vérifier le run du preset au jour du plan.'],
    CAP_ORDER: ['Plafond par ordre dépassé.', 'Définitif pour ce plan : aucune action.'],
    CAP_DAY: ['Plafond journalier dépassé.', 'Définitif pour ce plan : aucune action.'],
    CAP_ABSOLUTE_ORDER: ['Plafond absolu par ordre dépassé.', 'Définitif pour ce plan : aucune action.'],
    CAP_ABSOLUTE_DAY: ['Plafond absolu journalier dépassé.', 'Définitif pour ce plan : aucune action.'],
    LIVE_LOCKED: ['Exécution LIVE verrouillée (live-unlocked non posé).', 'Déverrouillage LIVE : étape d, avec Clem.'],
    MASTER_KEY_MISSING: ['Clé maître TRADEIO_MASTER_KEY absente : aucune clé Trade lisible.',
      'La poser dans l\'environnement du processus, puis relancer l\'application.'],
    '51155': ['OKX 51155 : paire interdite par la conformité du compte (ex. paires USDT en EEA).',
      'Passer par une paire en USDC.'],
    'EOrder:Order minimum not met': ['Kraken : ordre sous le minimum de la paire (taille ou coût).',
      'Augmenter la taille ou le montant ; voir les limites de la paire.'],
    'EOrder:Insufficient funds': ['Kraken : solde insuffisant pour cet ordre.', 'Vérifier le solde disponible de la devise.'],
    'EOrder:Invalid price': ['Kraken : prix invalide (tick ou valeur).', 'Saisir un prix multiple du tick de la paire.'],
    'EOrder:Unknown pair': ['Kraken : paire inconnue.', 'Choisir une paire de la liste du catalogue.'],
    'EOrder:Trading agreement required': ['Kraken : accord de trading non signé pour cette paire.',
      'Accepter les conditions côté compte Kraken.'],
    'EOrder:': ['Kraken : ordre refusé par l\'exchange.', 'Voir le message de l\'exchange ci-dessous.'],
    'EAPI:Invalid key': ['Kraken : clé API refusée.', 'Onglet Credentials : saisir une clé valide.'],
    'EAPI:Invalid signature': ['Kraken : signature refusée (secret erroné ?).', 'Onglet Credentials : ressaisir la clé secrète.'],
    'EAPI:Invalid nonce': ['Kraken : nonce refusé (issue inconnue, rien n\'est renvoyé).', 'Réessayer plus tard.'],
    'EGeneral:Permission denied': ['Kraken : droit manquant sur la clé ou IP non autorisée.',
      'Cocher les droits requis (jamais Withdraw) et autoriser l\'IP du VPS.'],
    'EGeneral:Invalid arguments': ['Kraken : paramètres invalides.', 'Vérifier la paire, la taille et le prix.'],
    'EService:': ['Kraken : service indisponible ou occupé.', 'Réessayer plus tard.'],
    CREDENTIAL_REJECTED: ['Clé Trade refusée par l\'exchange.', 'Onglet Credentials : vérifier clé, droits et IP.'],
    EXCHANGE_UNAVAILABLE: ['Exchange injoignable ou réponse illisible.', 'Réessayer plus tard.'],
    PAIR_UNKNOWN: ['Paire native inconnue au catalogue.', 'Rafraîchir le catalogue (sinon paire inexistante).']
  };

  /** Phrase + action suggérée pour un code de blocage (ou un texte contenant un code connu, ex. 51155). */
  function explain(code) {
    const key = String(code == null ? '' : code).trim();
    let hit = BLOCK_MESSAGES[key];
    if (!hit) {
      const known = Object.keys(BLOCK_MESSAGES).find((k) => key.includes(k));
      hit = known ? BLOCK_MESSAGES[known] : null;
    }
    if (!hit && key.startsWith('CAP_')) { hit = ['Plafond dépassé (' + key + ').', 'Définitif pour ce plan : aucune action.']; }
    return hit ? { code: key, text: hit[0], action: hit[1] }
      : { code: key, text: 'Motif inconnu : ' + (key || DASH), action: 'Consulter le journal d\'audit du plan.' };
  }

  /** Motifs d'un plan : {"BTC":"KILL_SWITCH","*":"MODE_NOT_LIVE"} ou "BTC:CAP_ORDER;ETH:NOT_TRADABLE" ⇒ lignes expliquées. */
  function explainBlocks(blocked) {
    let pairs = [];
    if (blocked && typeof blocked === 'object') {
      pairs = Object.entries(blocked);
    } else if (typeof blocked === 'string' && blocked) {
      pairs = blocked.split(';').filter(Boolean).map((p) => { const i = p.indexOf(':'); return i < 0 ? ['*', p] : [p.slice(0, i), p.slice(i + 1)]; });
    }
    return pairs.map(([asset, code]) => {
      const e = explain(code);
      return el('div', { class: 'small' },
        el('strong', { text: (asset === '*' ? 'Global' : asset) + ' : ' }), e.code + ' — ' + e.text + ' → ' + e.action);
    });
  }

  /* ------------------------------------------------------------------ bandeau permanent */

  function setBanner(id, text, cls) {
    const n = $(id);
    n.textContent = text;
    n.className = cls || '';
  }

  function renderBanner(s) {
    setBanner('adm-mode', s.mode, s.liveExecution ? 'text-danger' : '');
    setBanner('adm-live', s.liveUnlocked ? 'déverrouillé' : 'verrouillé', s.liveUnlocked ? 'text-danger' : 'text-success');
    setBanner('adm-master', s.masterKeyConfigured ? 'présente' : 'ABSENTE', s.masterKeyConfigured ? 'text-success' : 'text-danger');
    const who = s.killSwitchUpdatedBy ? ' (' + s.killSwitchUpdatedBy + ', ' + fmtDate(s.killSwitchUpdatedAt) + ')' : '';
    setBanner('adm-ks', (s.killSwitch ? 'ENGAGÉ' : 'LEVÉ') + who, s.killSwitch ? 'text-success' : 'text-danger');
    $('adm-caps').textContent = 'Plafonds absolus : ' + s.absoluteMaxOrderUsd + ' USD / ordre, ' + s.absoluteMaxDayUsd
      + ' USD / jour · exchanges : ' + (s.orderProviders.length ? s.orderProviders.join(', ') : 'aucun');
    $('adm-banner').className = 'alert d-flex flex-wrap align-items-center gap-3 py-2 '
      + (s.killSwitch ? 'alert-secondary' : 'alert-warning');
  }

  async function refreshState() {
    core.state = await api('GET', '/api/admin/execution/state');
    renderBanner(core.state);
    stateListeners.forEach((fn) => fn(core.state));
    return core.state;
  }

  /* ------------------------------------------------------------------ onglets (chargement paresseux) */

  function register(name, tab) { tabs.set(name, Object.assign({ loaded: false }, tab)); }

  function activate(name) {
    const tab = tabs.get(name);
    if (!tab) { return; }
    if (!tab.loaded) { tab.loaded = true; tab.load(); } else if (tab.show) { tab.show(); }
  }

  function start() {
    document.querySelectorAll('[data-adm-tab]').forEach((btn) => {
      btn.addEventListener('shown.bs.tab', () => activate(btn.dataset.admTab));
    });
    refreshState().catch((e) => showAlert(e.message));
    setInterval(() => { refreshState().catch(() => { /* bandeau périmé jusqu'au prochain tour */ }); }, STATE_REFRESH_MS);
    activate('console');
  }

  window.AdminCore = Object.assign(core, {
    el, empty, api, ApiError, run, showAlert, hideAlert, explain, explainBlocks, fmtDate, refreshState, register, start, DASH,
    onState: (fn) => stateListeners.push(fn), byId: $
  });
})();
