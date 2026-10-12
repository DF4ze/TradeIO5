# Étude — Liens stratégie / provider / actif (vocabulaire et modèle cible)

Statut : **décision de Clem du 2026-10-11**, validée en conversation. Le modèle cible n'est **pas** encore implémenté (le code contient une étape intermédiaire, cf. §5). Prompt d'implémentation : [`../prompts/prompt-implementation-liens-strategie-provider-actif.md`](../prompts/prompt-implementation-liens-strategie-provider-actif.md). État du code actuel : [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md).

## 1. Besoin

Avoir le même actif (ex. BTC) sur plusieurs providers (OKX **et** Kraken) pour un même utilisateur, chacun avec sa stratégie, car les volumes actif/stable et donc les tailles de bag diffèrent par provider. Aujourd'hui un seul binding par actif est possible (contrainte `(user, actif)`), ce qui empêche d'ajouter BTC sur Kraken quand BTC existe sur OKX.

## 2. Vocabulaire et hiérarchie (décidés)

```
Stratégie   ex. « TrendMix » : l'algorithme, sans actif
 └ Preset   calibré pour UN actif précis ; appartient à une stratégie
            ├ système : fourni à tous (suivi sans copie)
            └ custom  : créé/dupliqué par l'utilisateur (« BTC perso… »)
Lien        (utilisateur, provider, actif)  ->  stratégie + preset choisi
            clé d'unicité = (utilisateur, provider, actif) ; le preset est un attribut, hors clé
```

- L'utilisateur lie un couple **provider × actif** à une **stratégie** ; le preset utilisé est choisi dans le lien (système ou custom). Les presets sont internes à la stratégie.
- Un preset ne peut s'appliquer qu'à l'actif pour lequel il est calibré ; une stratégie sans preset pour l'actif demandé n'est **pas bindable**.
- Un seul lien par `(provider, actif)`, quel que soit le preset ; une même stratégie (et un même preset) peut servir plusieurs liens.
- Les tailles de bag (`bagPercent`), la priorité de passage du cash et l'interrupteur d'exécution appartiennent au **lien**, donc au provider, pas au global.
- **Bindable** = (1) un preset de la stratégie existe pour l'actif ET (2) une route d'achat/vente existe sur le provider (tradabilité, `BindingCheck`/`PathFinder`) ET (3) wallet et clé du provider existent. Aujourd'hui un KO de tradabilité n'empêche pas d'enregistrer le lien (statut revérifié à chaque passe) : comportement conservé tant que Clem ne demande pas de le durcir.

## 3. Écarts avec le code actuel

| Sujet | Aujourd'hui | Cible |
|---|---|---|
| « Stratégie » | n'existe que comme `RainbowLivePreset.mode` (`FIXED` / `TREND_MIX`) ; « Stratégie Actif » (`RainbowAssetStrategy`) = en fait un **preset système** | vocabulaire aligné ; pas de nouvelle entité (le `mode` fait office de stratégie) |
| Cible du binding | un preset, qui porte l'actif | idem (le preset porte l'actif) ; choix système/custom dans le lien |
| Unicité | `(user, actif, wallet)` (étape intermédiaire) | idem ; **un preset peut alimenter plusieurs liens** |
| Run quotidien | `RainbowLiveRun` unique `(preset, jour)` : simulation **et** snapshot live du wallet réel | simulation par preset (partagée) ; **snapshot live par lien** |
| Plan d'ordres / exécuteur | indexés par actif seul | indexés par `(actif, wallet)` ; `walletId` dans l'étape et le `clOrdId` |

## 4. Décisions d'architecture proposées (à valider par Clem avant code)

- **Simulation partagée, live par lien.** L'état du moteur, le wallet fictif et le run de simulation restent par preset : le signal (achat/vente voulu) est le même pour tous les liens d'un preset. Ce qui dépend du wallet réel (cash, position, bag, action live plafonnée, réservation de cash) est stocké **par lien**. Conséquence : deux liens sur le **même** preset ont le même signal mais des tailles différentes ; pour des signaux différents par provider, utiliser deux presets.
- Vocabulaire : le terme « binding » reste dans le code et l'API tant que Clem n'a pas choisi le nom du lien ; le renommage sera un lot séparé.

## 5. Implémentation

Livrée en 4 lots (unicité et presets partagés, snapshot live par lien `RainbowLiveBindingRun`, plan/exécuteur indexés par `(actif, wallet)`, front). État courant : [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md) et [`../architecture/10-execution-plan.md`](../architecture/10-execution-plan.md). SQL manuel sur base existante : [`../operations/flyway.md`](../operations/flyway.md).

## 6. Questions ouvertes

- Nom du lien en remplacement de « binding » (Lien, Affectation, Slot…).
- Clé `(provider, actif)` ou `(wallet, actif)` : équivalent tant qu'il y a un compte par exchange.
- « Stratégie » doit-elle devenir une entité (d'autres stratégies que TrendMix) ou rester le `mode` du preset ?
- Un état moteur indépendant par lien (au lieu de partagé par preset) est-il nécessaire ? Non retenu pour l'instant (§4).
