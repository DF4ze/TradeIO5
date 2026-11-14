---
statut: journal
sujet: Prompt lot 1 du simulateur complet — cœur partagé live/simu, rejeu mono-actif, parité prod
modifié_le: 2026-11-14
empreinte: 5ac7d8cd6c
---
# Prompt — Simulateur complet, lot 1 : cœur partagé + rejeu mono-actif + parité prod

**Lis d'abord [`prompt-simulateur-complet.md`](prompt-simulateur-complet.md)** (contexte, décisions, repères code, règles, méthode) : il vaut pour ce lot. Ce fichier ne donne que le spécifique du lot 1.

## Objectif
Un rejeu **mono-actif (BTC d'abord)** qui se comporte comme la prod avec un capital `C0` fixé au départ, et qui **retrouve les actions et montants enregistrés** par la prod d'octobre 2026 à aujourd'hui. Pas de frais, pas de minimum d'ordre, pas de carry, pas d'écran dans ce lot (lots 3 et 5) : on valide d'abord le signal modulé et le cash.

## Périmètre
- Un seul lien (binding) et son affectation ; cash = `C0` seul (pas de wallet partagé : lot 2). Réglages du lien (bande, puissance, biais, marge, `baseAmount`, `bagPercent`) lus sur le lien, surchargeables en entrée.
- Source des candles = celle du lien (`RainbowLiveBinding` / `LiveSlot#candleSource`), sans fallback silencieux. Fenêtre D1 : **720 jours comme le live** (`RainbowLiveDefaultPresets.D1_LOOKBACK_CANDLES`) en mode parité, paramétrable ensuite ; l'ATH et l'automate moon dépendent de cette profondeur, donc la parité l'exige.
- `C0` par défaut = valeur du wallet réel du lien (cash USDC + actifs au prix) ; en test de parité, `C0` et position initiale = état du **premier** `RainbowLiveBindingRun` de la période (`cashUsd`, `positionQty`, clôture du jour).

## À construire
1. **Extraire un cœur pur partagé** (sans I/O, sans entité JPA, sans exchange) : `(LiveDemand, cash disponible, exposition, réglages effectifs, position tradable) -> décision (action, montant, quantité, raison de blocage, journal de modulation)`. Il remplace la logique de `LiveSizing#apply` + `LiveModulation#modulate` ; le live l'appelle puis écrit `RainbowLiveLiveBlock`, la simulation l'appelle directement. Rendre `LiveDemand`/`CashLedger`/`ExposureLedger` accessibles (visibilité) sans casser les gardes d'architecture. Non-régression stricte du live (montants identiques, tests existants verts).
2. **Rejeu de la demande de signal** : produire la `LiveDemand` du jour depuis le rejeu (`RainbowAtrReplay`/`RainbowAtrStrategy#step`/`ReferenceSizer`) **avec les mêmes règles que `RainbowTrendLiveService#run`** : position virtuelle du moteur pour le signal, vente = fraction × position **tradable réelle** simulée (`bagPercent` × quantité détenue), `withLinkBaseAmount`. Ne pas dupliquer : extraire ce qui est commun (calcul de la demande à partir de l'état, de la bande et du signal) si besoin.
3. **Moteur de rejeu complet mono-actif** (`service/expert/simulation/…`, pur) : boucle jour par jour sur la plage — Trend Mix → régime/jeu (`RainbowSetSelector`, barres inactives comme le live : pas de nouvelles entrées hors régime) → demande → cœur partagé → mise à jour du wallet fermé (cash, quantité, exposition `P`/`V` au cours de clôture) → état suivant. Sortie : série journalière (cash, quantité, valeur actifs, valeur totale, action, montant, raison, journal de modulation), valeur finale, DCA fixe de même budget (même base, mêmes barres actives) pour la référence.
4. **Outil/test de parité** : comparer, jour par jour, la simulation aux `RainbowLiveBindingRun` (action, montant, quantité) et `RainbowLiveRun` (jeu, régime, action voulue) du lien. Écarts attendus et à **expliquer, pas masquer** : le live décide à 23:55 UTC sur la bougie encore ouverte (clôture ≈ cours de 23:55) alors que le rejeu voit la clôture finale ; jours manquants ; changements de config/preset en cours de période (hash `configHash`) ; resync d'état (`RainbowLiveEngineState`). Livrer un rapport lisible des écarts (jour, attendu, obtenu, cause probable). Seuil d'acceptation à proposer à Clem (ex. même action et montant à ε près sur ≥ X % des jours, écarts résiduels tous expliqués).

## Base de parité : copie de la prod en local
Clem joue **en local avec la config de prod** : base locale = copie de la prod, jamais de lecture directe de la prod. Procédure existante : `operations/dev-sync.md` (VPS `tools/dev-sync/export-prod.sh` → scp → `import-dev.ps1 -Dump … -Go`). Vérifie que l'export contient bien `rainbow_live_run`, `rainbow_live_binding_run`, `rainbow_live_engine_state`, `rainbow_live_binding`, presets et affectations (la liste des tables omises est dans le doc ; `candle` est omise ⇒ les D1 sont rechargés par `MarketDatasetEngine` depuis les exchanges publics, à signaler à Clem si les clés/quotas posent problème). Si la copie manque une donnée utile à la parité, propose un complément à `tools/dev-sync/` plutôt qu'un contournement. Ne lance pas l'import toi-même sans l'accord de Clem (efface la base locale).

## Questions à poser à Clem avant de coder
- Seuil d'acceptation de la parité et traitement des écarts dus au 23:55 vs clôture.
- Date exacte de début de la période de parité (premier run du lien BTC en prod ?) et lien/affectation à prendre.
- Où exposer le moteur dans ce lot sans écran (endpoint de test admin ? test d'intégration seul ?). Proposition : service + test d'intégration + endpoint expert sous `ExpertAccessGuard`, sans UI.

## Livrables et porte de sortie
Cœur extrait (live non régressé), moteur de rejeu mono-actif, rapport de parité BTC. Porte : parité atteinte (ou écarts résiduels tous expliqués et acceptés par Clem). Docs : `architecture/13-espace-expert.md` (nouveau type et cœur partagé), `08d-rainbow-modulation.md` (cœur partagé), `known-gaps/` si besoin, puis doc-police.
