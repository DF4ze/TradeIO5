---
statut: index
sujet: Table de routage de la documentation
modifié_le: 2026-11-24
empreinte: 9ef6897742
---
# TradeIO-5 — Documentation

Table de routage : ne lire que la ligne utile. Hiérarchie de confiance : `decisions/` (positions de Clem) > `architecture/` (état vérifié du code) > `known-gaps/` (reste à faire) > le reste (journaux, jamais une référence). Règles de tenue : [`CHARTE.md`](CHARTE.md).

## À lire d'abord
| Doc | Lire si… |
|---|---|
| [`architecture/01-overview.md`](architecture/01-overview.md) | tu découvres l'app : ce qu'elle fait et ne fait PAS |
| [`architecture/02-tree-pipeline.md`](architecture/02-tree-pipeline.md) | tu touches Indicator → Strategy → Opinion → Scenario → Decision |
| [`known-gaps/decision-to-order-gap.md`](known-gaps/decision-to-order-gap.md) | tu supposes que l'app passe de vrais ordres |
| [`CODING_RULES.md`](CODING_RULES.md) | tu écris du code |
| [`glossary.md`](glossary.md) | un acronyme/enum n'est pas clair |

## Décisions de Clem (tranchées — ne pas re-questionner)
| Doc | Lire si… |
|---|---|
| [`decisions/regles-transverses.md`](decisions/regles-transverses.md) | USDC, fiscalité, cache candles, hygiène repo |
| [`decisions/liens-strategie-provider-actif.md`](decisions/liens-strategie-provider-actif.md) | presets/liens/affectations, provider du signal |
| [`decisions/modulation-exposition-puissance.md`](decisions/modulation-exposition-puissance.md) | curseurs exposition/puissance |
| [`decisions/chemin-achat-execution.md`](decisions/chemin-achat-execution.md) | ordres réels, Fee Test, plan dry-run |
| [`decisions/passerelle-actif-non-stable.md`](decisions/passerelle-actif-non-stable.md) | PAXG/Kraken, routes via USDC |
| [`decisions/wallet-reel-capital-commun.md`](decisions/wallet-reel-capital-commun.md) | wallet réel, pools cash, exchanges utilisés |
| [`decisions/fil-rouge-dca-automatisation.md`](decisions/fil-rouge-dca-automatisation.md) | Trend, Rainbow ATR, pine, bench, calibrations |
| [`decisions/full-autonome-v1.md`](decisions/full-autonome-v1.md) | cible Full Autonome v1 : exposition constante par profil (table de calage, marge k), acquis mesurés, E1 et E2 mesurés (coupure au switch : E1 `COUPURE_UTILE` mais fragile, E2 `COUPURE_NON_ROBUSTE` ; PAXG non conclu), D1a mesuré (preset existant générique sur actifs jamais vus), C5 fait (taux par profil éditables en admin, proposition d'onboarding au taux du profil), D1b fait (proposition, alerte, validation admin), reste à faire (arbitrage des bandes de profil) |
| [`decisions/architecture-interface.md`](decisions/architecture-interface.md) | UX, pages, charte, logo |
| [`decisions/definition-pnl.md`](decisions/definition-pnl.md) | calcul du PnL |
| [`decisions/docteur-admin-ux.md`](decisions/docteur-admin-ux.md) | Docteur, page admin d'exécution, kill switch |
| [`decisions/espace-expert.md`](decisions/espace-expert.md) | presets système/expert, option « coupure au switch » voulue (non implémentée) |
| [`decisions/flyway-defauts-presets.md`](decisions/flyway-defauts-presets.md) | Flyway, défauts, sauvegarde DB |
| [`decisions/providers.md`](decisions/providers.md) | providers, Revolut X, monétisation |
| [`decisions/actifs-dynamiques.md`](decisions/actifs-dynamiques.md) | liste d'actifs non figée : source providers, éligibilité, simulation seule avant validation, liste noire, lots |
| [`decisions/recette.md`](decisions/recette.md) | recette avant prod, go prod, données nettoyées |
| [`decisions/acces-amis-tunnel-ssh.md`](decisions/acces-amis-tunnel-ssh.md) | accès des amis (tunnel SSH), création de comptes par l'admin, inscription fermée |

## Architecture (état vérifié du code)
| Doc | Lire si… |
|---|---|
| [`architecture/03-ownership-and-lifecycle.md`](architecture/03-ownership-and-lifecycle.md) | multi-user, credentials, cycle de vie |
| [`architecture/04-market-data.md`](architecture/04-market-data.md) | candles, cache DB |
| [`architecture/05-external-providers.md`](architecture/05-external-providers.md) | providers de données externes |
| [`architecture/06-media-watch.md`](architecture/06-media-watch.md) | veille média YouTube |
| [`architecture/07-security.md`](architecture/07-security.md) | auth, sécurité |
| [`architecture/08-rainbow-live.md`](architecture/08-rainbow-live.md) | Rainbow live : affectations, run quotidien, presets, Trend→Rainbow |
| [`architecture/08a-rainbow-entites.md`](architecture/08a-rainbow-entites.md) | schéma d'entités Rainbow live |
| [`architecture/08b-rainbow-bindings-expert.md`](architecture/08b-rainbow-bindings-expert.md) | liens (bindings), disponibilité, presets Expert |
| [`architecture/08c-rainbow-chemin-achat-wallet.md`](architecture/08c-rainbow-chemin-achat-wallet.md) | dimensionnement réel, Fee Test, PathFinder, groupes d'actifs |
| [`architecture/08d-rainbow-modulation.md`](architecture/08d-rainbow-modulation.md) | modulation : paramètres admin/user |
| [`architecture/09-trend.md`](architecture/09-trend.md) | Trend unifié |
| [`architecture/10-execution-plan.md`](architecture/10-execution-plan.md) | plan d'ordres dry-run |
| [`architecture/14-execution-reelle.md`](architecture/14-execution-reelle.md) | exécution réelle, page Admin |
| [`architecture/11-front-app.md`](architecture/11-front-app.md) | front `/app` |
| [`architecture/12-doctor.md`](architecture/12-doctor.md) | Docteur / alarmes |
| [`architecture/13-espace-expert.md`](architecture/13-espace-expert.md) | Espace Expert |
| [`architecture/13a-rejeu-politique-ath-couts.md`](architecture/13a-rejeu-politique-ath-couts.md) | rejeu : politique de modulation dans le temps, plafond ATH, coûts, épisodes |
| [`architecture/15-optimiseur.md`](architecture/15-optimiseur.md) | optimiseur : évaluateur de paramètres, moteur de recherche, calibrage walk-forward par profil (POLICY_L2), rapport JSON, endpoint admin |
| [`architecture/15a-optimiseur-presets-l1.md`](architecture/15a-optimiseur-presets-l1.md) | optimiseur de presets L1 par actif (PRESET_L1) : espace δ, veto d'exposition, walk-forward, cadences |
| [`architecture/15b-carte-sensibilite-rainbow.md`](architecture/15b-carte-sensibilite-rainbow.md) | carte de sensibilité Rainbow (étape A) : un réglage à la fois, Bull / Bear seul, PnL réel, lecture gel |
| [`architecture/15c-optimisation-par-blocs.md`](architecture/15c-optimisation-par-blocs.md) | optimisation par blocs Rainbow (étape B) : descente de coordonnée, plateau, garde-fou d'exposition, 4 rotations, challenger exporté, runner `tune` |
| [`architecture/15d-mesure-exposition-egale.md`](architecture/15d-mesure-exposition-egale.md) | mesure à exposition égale Rainbow (B') et frontière exposition → gain / drawdown (C1) : DCA fixe calé, bande de bruit, règles de verdict, niveau par profil, runners `exposure-parity` et `exposure-frontier` |
| [`architecture/15f-politique-exposition-regime.md`](architecture/15f-politique-exposition-regime.md) | politique d'exposition par régime contre exposition constante à plafond de drawdown égal (C2) : `REGIME_BAND` à 3 paramètres, réglage par blocs, règle de verdict, runner `exposure-policy` |
| [`architecture/15g-calage-exposition-pire-cas.md`](architecture/15g-calage-exposition-pire-cas.md) | calage de l'exposition par profil sur le pire cas (fenêtres de 24 mois + historique) avec marge k mesurée par calage ancré (C3) : règle de k, runner `exposure-calibration`, équivalence onboarding |
| [`architecture/15h-mise-en-production-exposition.md`](architecture/15h-mise-en-production-exposition.md) | mise en production du calage (C4) : niveau en taux de base, invariance au capital, bandes du live et onboarding, wallet BTC + ETH, facteur de réduction f, runner `exposure-production` |
| [`architecture/15i-reglage-recent.md`](architecture/15i-reglage-recent.md) | réglage récent (D0) : réglage conjoint sur les 24 mois avant T, test en avançant 3 / 6 mois, comparateurs (preset, DCA égal, aléatoire), règle de verdict, runner `recent-tune` |
| [`architecture/15j-diagnostic-switchs.md`](architecture/15j-diagnostic-switchs.md) | diagnostic des switchs Bull → Bear (E0) : régime du rejeu, 6 mesures par switch contre hasard, règle de verdict, runner `switch-diagnostic` ; résultats : [`etudes/etude-diagnostic-switchs.md`](etudes/etude-diagnostic-switchs.md) |
| [`architecture/15m-pipeline-nouvel-actif.md`](architecture/15m-pipeline-nouvel-actif.md) | pipeline d'accueil d'un nouvel actif (D1b) : déclencheur au lien, historique provider / Binance bench, preset BTC + calage C3 allégé, drapeaux, table `new_asset_proposal`, alarme Docteur « actif à valider », écran admin Valider / Refuser, ligne de taux dédiée `asset_base_rate` |
| [`architecture/15l-transfert-presets.md`](architecture/15l-transfert-presets.md) | transfert des presets existants sur actifs jamais vus (D1a) : stockage bench Binance USDT étiqueté, paires preset × actif, règles de verdict, runner `transfer` ; résultats : [`etudes/etude-transfert-presets.md`](etudes/etude-transfert-presets.md) |
| [`architecture/15k-coupure-switch.md`](architecture/15k-coupure-switch.md) | coupure du bag au switch Bull → Bear (E1) : hook `SwitchCut` du moteur de rejeu, preset pur, comparaison à plafond de drawdown égal, runner `switch-cut` (BTC, ETH, PAXG, ré-entrée 20 % / 0 %) ; E2 : robustesse (taille de ré-entrée, bande de bruit, portefeuille BTC + ETH), runner `switch-robustness` ; résultats : [`etudes/etude-coupure-switch.md`](etudes/etude-coupure-switch.md), [`etudes/etude-coupure-robustesse.md`](etudes/etude-coupure-robustesse.md) |

## API & opérations
| Doc | Lire si… |
|---|---|
| [`api/rest-endpoints.md`](api/rest-endpoints.md) | web, auth, décision, ETF, veille média (admin) |
| [`api/rest-rainbow-live.md`](api/rest-rainbow-live.md) | endpoints Rainbow live (liens, wallet, décisions, modulation, run) |
| [`api/rest-execution-plans.md`](api/rest-execution-plans.md) | endpoints plans d'ordres / état d'exécution |
| [`api/rest-expert-portfolio.md`](api/rest-expert-portfolio.md) | endpoints Espace Expert, patrimoine, préférences |
| [`api/mcp-tools.md`](api/mcp-tools.md) | tools MCP |
| [`operations/configuration.md`](operations/configuration.md) | propriétés, secrets, environnement |
| [`operations/flyway.md`](operations/flyway.md) | migrations (Flyway actif), défauts d'initialisation |
| [`operations/recette.md`](operations/recette.md) | recette sur copie de prod (scripts `tools/recette/`), déploiement prod, retour arrière |
| [`operations/acces-amis-ssh.md`](operations/acces-amis-ssh.md) | prérequis VPS (sudoers, scripts client) et test réel de l'accès des amis |
| [`operations/dev-sync.md`](operations/dev-sync.md) | recopier la prod vers la base de dev (scripts `tools/dev-sync/`), anti-dérive |
| [`operations/scheduled-jobs.md`](operations/scheduled-jobs.md) | jobs planifiés |

## Reste à faire (known-gaps)
[`rainbow-dca-chantiers-ouverts`](known-gaps/rainbow-dca-chantiers-ouverts.md) (objectif en cours : DCA intelligent) · [`front-gaps`](known-gaps/front-gaps.md) · [`doctor-diagnostic`](known-gaps/doctor-diagnostic.md) · [`actifs-dynamiques-lots`](known-gaps/actifs-dynamiques-lots.md) · [`nettoyages`](known-gaps/nettoyages.md) · [`code-level-inconsistencies`](known-gaps/code-level-inconsistencies.md)

## Interface, sécurité, mind-set
| Doc | Lire si… |
|---|---|
| [`graphique/inventaire-options-affichage.md`](graphique/inventaire-options-affichage.md) | maquettes/GUI : actions et infos affichées (à tenir à jour) |
| [`graphique/charte-graphique.md`](graphique/charte-graphique.md) | tokens, typo, composants |
| [`graphique/remarques-gui.md`](graphique/remarques-gui.md) | besoins d'interface collectés |
| [`securite/remarques-audit.md`](securite/remarques-audit.md) | phase d'audit |
| [`mindset/`](mindset/) | philosophie d'investissement (Cryptolyze : [bear market](mindset/bear-market-exposition-accumulation.md), [prise de profits](mindset/prise-de-profits.md), [DeFi](mindset/pas-a-pas-defi.md)) |

## Journaux (jamais une référence d'état courant)
- Études actives (conception en cours) : [`etudes/README.md`](etudes/README.md)
- Calibrations (protocoles/résultats de benchs ; verdict « un jeu global par actif » invalidé) : [`calibration/README.md`](calibration/README.md)
- Archives (études closes, specs initiales, anciens suivis, sources PDF/ODT) : [`archive/README.md`](archive/README.md)
