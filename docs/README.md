---
statut: index
sujet: Table de routage de la documentation
modifié_le: 2026-11-11
empreinte: 14b381edb7
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
| [`decisions/architecture-interface.md`](decisions/architecture-interface.md) | UX, pages, charte, logo |
| [`decisions/definition-pnl.md`](decisions/definition-pnl.md) | calcul du PnL |
| [`decisions/docteur-admin-ux.md`](decisions/docteur-admin-ux.md) | Docteur, page admin d'exécution, kill switch |
| [`decisions/espace-expert.md`](decisions/espace-expert.md) | presets système/expert |
| [`decisions/flyway-defauts-presets.md`](decisions/flyway-defauts-presets.md) | Flyway, défauts, sauvegarde DB |
| [`decisions/providers.md`](decisions/providers.md) | providers, Revolut X, monétisation |
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
[`rainbow-dca-chantiers-ouverts`](known-gaps/rainbow-dca-chantiers-ouverts.md) (objectif en cours : DCA intelligent) · [`front-gaps`](known-gaps/front-gaps.md) · [`doctor-diagnostic`](known-gaps/doctor-diagnostic.md) · [`nettoyages`](known-gaps/nettoyages.md) · [`code-level-inconsistencies`](known-gaps/code-level-inconsistencies.md)

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
