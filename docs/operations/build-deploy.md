---
statut: courant
vérifié_le: 2026-11-25
modifié_le: 2026-11-24
empreinte: 61c8db8991
---
# Gateway d'abord : build, tests, recette, déploiement, VPS

## Quel outil pour quoi (gateway en priorité)
| Besoin | Outil |
|---|---|
| Compiler, tester, package, bench, recette, déployer | **playbook gateway** (ci-dessous) — jamais de script ni de commande `mvn` ad hoc |
| Compiler vite / lancer ou arrêter l'appli locale | MCP JetBrains (`build_project`, run configurations) |
| État du VPS, services, logs | `getStatus`, `getAllServicesStatus`, `getServiceLogs`, `status:tradeio-5` |
| Commande VPS ponctuelle | `execCommand` (`runAsUser: root` si besoin) ; si récurrente => opération playbook |
| Envoi de fichier au VPS | step `upload` d'une opération (jamais `uploadFile` isolé pour un jar : > 80 s) |
| Lire/écrire fichiers du projet | `device_bash` sur les dossiers connectés |
| Fichiers hors workspace (`D:\Installs\…`) | `readLocalFile` / `listLocalDir` |
| Commande locale hors playbook | `execLocalCommand` en dernier recours (approbation Clem) ; si elle revient, la mettre dans le playbook |
| Base locale | `queryLocalDb` |

Ne pas refaire à la main ce qu'une opération fait déjà (build, envoi, recette, déploiement, contrôles).

## Playbook : build, tests, recette, déploiement

**Règle : ne jamais créer de script (`.bat`, `.sh`, `.ps1`) ni de commande ad hoc pour builder/tester/déployer.** Tout passe par les opérations du playbook du ssh-tunnel-gateway (`D:\Installs\Ssh-tunnel\playbook.json`). Pas d'approbation à demander ; opération manquante => l'ajouter au playbook (rechargé automatiquement, aucun redémarrage).

## Boucle d'utilisation (outils MCP `ssh-gateway`)
1. `executeOperation(name, args?)` : lance en arrière-plan, retour immédiat. `args` remplace `{args}` (lettres, chiffres, `_ . , : = + - / espace`).
2. `waitOperation(name)` : long-poll ≤ 55 s, rend la main dès la fin ; renvoie état + résumé extrait du log (`BUILD`, totaux de tests, tests en échec, erreurs de compilation) + fin du log. Rappeler tant que « toujours en cours ». Jamais de `sleep`/`ping` de polling.
3. `cancelOperation(name)` tue Maven/script et ses enfants. `checkOperation` = instantané sans attente. Logs : `C:\temp\gateway-ops\<op>\output.log` (+ `status.json`).

Un step `remote` échoue si exit ≠ 0 et a un timeout (`timeoutSec`, défaut 300 s) ; un step `local` aussi (défaut 2400 s). `runAsUser: root` sur un step `remote` = `sudo -u root`.

## Opérations TradeIO-5
| Opération | Rôle | Durée typique |
|---|---|---|
| `compile:tradeio-5` | `mvn test-compile` offline | ~10 s |
| `test-class:tradeio-5` `FooTest,Bar*Test` | tests ciblés | 15 s – 2 min |
| `test:tradeio-5` | toute la suite (long) | minutes |
| `build:tradeio-5` | `clean package` avec tests | minutes |
| `manual:tradeio-5` `<mode> [-Dopt=…]` | benchs/études (`OptimizerManualRunnerTest`) ; **demander avant les benchs coûteux** | variable |
| `package:tradeio-5` | jar sans tests -> `target\TradeIO5-deploy.jar` | ~30 s |
| `recette:tradeio-5` | package + envoi VPS + scripts + `recette.sh` ; échoue si verdict KO | ~3–4 min |
| `deploy:tradeio-5` | **prod** : `deploy-prod.sh` sur le jar de la dernière recette OK | ~2 min |
| `deploy-sans-recette:tradeio-5` | **prod** sans modif DB : package + envoi + `--sans-recette` | ~3 min |
| `status:tradeio-5` | état du service prod | secondes |

`deploy:*` : uniquement sur « go » explicite de Clem ([`../decisions/recette.md`](../decisions/recette.md)). Détail recette/déploiement/rollback : [`recette.md`](recette.md).

## Compiler plus vite
Le MCP JetBrains (`build_project`, JVM chaud) vérifie la compilation en quelques secondes ; Maven reste pour tests ciblés et package. Appli locale JetBrains : arrêter avant d'écrire une migration Flyway (voir `CLAUDE.md`).

## Gateway (ssh-tunnel-gateway)
Code : `D:\Documents\Spring\ssh-tunnel-gateway`. Déploiement : `deploy:ssh-tunnel-gateway` puis `restartGateway` (MCP coupé ~5 s). Jar précédent conservé : `D:\Installs\Ssh-tunnel\ssh-tunnel-gateway-<version>.jar` ; si la gateway ne redémarre pas, lancer ce jar à la main (`startTunnels.bat` ne bascule pas seul). Ne pas modifier `startTunnels.bat` pendant que la gateway tourne (cmd relit le fichier en cours d'exécution).
