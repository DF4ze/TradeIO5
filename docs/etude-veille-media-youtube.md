# Étude — Veille média YouTube multi-chaînes (transcripts → Opinion EXTERNAL)

**Statut : Plan validé (2026-07-10, complété le 2026-07-10 avec les réponses de l'utilisateur sur fraîcheur/langue/coût), implémentation non démarrée.**

Objectif : exploiter automatiquement le contenu de chaînes YouTube crypto/macro (point de départ : Cryptolyze, `channel_id` `UCuXgThwkFpefb41aKWKqrOw`) comme source de signal, en généralisant à plusieurs chaînes dès la conception. Fait suite à une discussion de conception (pas d'étude séparée) : voir §1 pour le verdict d'architecture retenu.

## 1. Verdict d'architecture (rappel de la discussion)

Une vidéo n'est **pas** un `Indicator` (mesure numérique calculée sur des bougies) — c'est du texte libre, multi-actifs, multi-thèses, multi-horizons. Le bon quartier existant est `OpinionScope.EXTERNAL` (« LLM, news, sentiment externe »), déjà implémenté par `ExternalMarketOpinion` + `DecisionAdvisor`/`OpenAIAdvisor`.

Mais ce pattern ne peut pas être copié tel quel : `DecisionAdvisor.advise(OpinionContext)` est un appel LLM **synchrone, en live, à chaque cycle de décision**. Pour de la vidéo, c'est le mauvais mode — publication irrégulière (quelques vidéos/semaine), une vidéo peut couvrir plusieurs actifs/horizons différents, et ré-analyser à chaque cycle de décision serait coûteux et souvent redondant.

**Décision retenue** : découpler en pipeline asynchrone (ingestion + extraction, hors du hot path) puis consommation (nouvelle `MarketOpinion` scope `EXTERNAL` qui lit un store au lieu d'appeler un LLM en live). `DecisionEngine` sait déjà arbitrer plusieurs opinions `EXTERNAL` (règle d'unanimité, cf. commentaire `ExternalMarketOpinion`) — aucun changement requis à ce niveau.

## 2. Point ouvert ajouté : détection de contenu hors-sujet

Cryptolyze (et vraisemblablement d'autres chaînes) publie aussi des vidéos à thème sans lien avec de l'analyse de marché — cf. `docs/cryptolyze/*.pdf` déjà présents dans le repo (« Pas à Pas DeFi », « La prise de Profits : Psychologie et Stratégie »), qui sont des prises de notes manuelles de l'utilisateur sur ce type de contenu. Le pipeline doit détecter ça et **ne rien extraire** plutôt que de forcer un signal de marché à partir d'un contenu qui n'en contient pas.

Générique par construction (pas spécifique à Cryptolyze) : le filtre vit dans le service d'extraction commun, pas dans un client par chaîne.

**Décision (confirmée par l'utilisateur, cf. §6) : option A, filtre en 2 passes.** Les vidéos font 15-20 min pour de l'analyse de marché, et les hors-sujet sont encore plus longues (~30 min) — donc justement le cas le plus fréquent où on veut économiser, c'est le hors-sujet. Passer tout le transcript dans un appel d'extraction pour ensuite jeter le résultat serait le pire des cas côté coût.

| Option | Principe | Coût | Retenue |
|---|---|---|---|
| A. Filtre en 2 passes | Passe 1 : petit prompt de classification sur titre + **extrait tronqué** du transcript (pas le texte entier) → thème global. Passe 2 (extraction claims, transcript complet) seulement si passe 1 = "analyse de marché" | Passe 1 courte/pas chère même sur les vidéos longues ; passe 2 (chère) uniquement sur le sous-ensemble pertinent | **Oui** |
| B. Filtre intégré | Un seul prompt (pertinence + claims) sur le transcript complet | 1 appel LLM/vidéo, mais cet appel unique embarque tout le texte même pour du hors-sujet de 30 min | Non |

Détail technique de la passe 1 à trancher à l'implémentation (cf. §6) : quelle portion du transcript envoyer (les premières minutes suffisent probablement à identifier le thème — Cryptolyze annonce généralement le sujet en intro) et sur quel critère tronquer (nombre de caractères vs. minutes de transcript, en s'appuyant sur les timestamps si l'API de transcript les fournit).

**Modèle LLM par passe.** Besoin d'un système à 3 niveaux (Peu coûteux / Normal / Puissant) plutôt que de coder un modèle en dur par appel — voir le prompt dédié **`docs/prompt-implementation-llm-model-tiers.md`**, à traiter comme un **prérequis externe** de ce lot, dans une session d'implémentation séparée (ce n'est pas une reprise d'un travail déjà fait dans TradeIO5 — vérifié, l'utilisateur pensait à un autre projet). Pour cette feature une fois le système de niveaux disponible :
- **Passe 1 (classification)** → niveau **Peu coûteux** : tâche simple (thème global oui/non), gros volume d'appels (toutes les vidéos, y compris hors-sujet).
- **Passe 2 (extraction claims)** → niveau **Normal** : tâche plus fine (structurer plusieurs affirmations avec symbole/sentiment/horizon/confiance à partir d'un texte long), volume plus faible (seulement les vidéos jugées pertinentes par la passe 1) — le niveau **Puissant** reste disponible pour d'autres cas d'usage futurs si "Normal" s'avère insuffisant à l'usage.

Dépendance de planning : le Lot 2 ci-dessous ne peut démarrer qu'une fois `docs/prompt-implementation-llm-model-tiers.md` implémenté (ou, à défaut, en repli temporaire sur `OpenAIService.ask` tel quel avec le modèle unique actuel — option dégradée, à éviter si possible vu l'écart de coût passe 1/passe 2).

## 3. Modèle de données (nouvelles entités)

- **`ContentSource`** — config multi-chaînes : `id`, `platform` (YOUTUBE pour l'instant), `channelId`, `displayName`, `credibilityWeight`, `active`. Ajouter un youtuber = une ligne de config, pas de code. Équivalent conceptuel de `WebProviderCode` mais pour des sources de contenu plutôt que des providers de data.
- **`VideoContent`** — une vidéo ingérée : `source` (FK `ContentSource`), `videoId`, `title`, `publishedAt`, `transcript`, `status` (`PENDING` / `PROCESSED` / `IRRELEVANT` / `ERROR`).
- **`MediaClaim`** — une affirmation extraite (0..N par vidéo) : `videoContent` (FK), `symbol`, `sentiment` (bullish/bearish/neutre), `horizon` (court/moyen/long terme), `confidence` (du LLM), `excerpt` (citation source, pour audit).

## 4. Lots d'implémentation

**Lot 1 — Ingestion générique multi-source**
- `ContentSource` (entité + config initiale : Cryptolyze)
- Client RSS générique paramétré par `channelId` (`https://www.youtube.com/feeds/videos.xml?channel_id=...`)
- Client transcript générique paramétré par `videoId`
- Job planifié (premier `@Scheduled` du projet — `service/scheduler/` existe mais est vide à ce jour) : poll périodique par `ContentSource` active, détection des vidéos non encore vues, écriture `VideoContent` en statut `PENDING`

**Lot 2 — Classification + extraction LLM (2 passes, option A §2)**
- Prérequis externe : système de niveaux LLM disponible (`docs/prompt-implementation-llm-model-tiers.md`, §5)
- Passe 1 (classification, niveau **Peu coûteux**) : prompt léger sur titre + extrait tronqué du transcript → `category` + `is_market_relevant`. Statut `VideoContent` mis à jour directement en `IRRELEVANT` si négatif, sans jamais déclencher la passe 2
- Passe 2 (extraction, niveau **Normal**, transcript complet) : uniquement si passe 1 positive → prompt d'extraction de `claims` structurés
- `TranscriptExtractionService` : orchestration des 2 passes (avec leurs niveaux respectifs), consomme les `VideoContent` en `PENDING`, persiste les `MediaClaim`

**Lot 3 — Intégration Opinion / décision**
- Nouvelle `MarketOpinion` (scope `EXTERNAL`), ex. `MediaMarketOpinion` : au moment de la décision, lit les `MediaClaim` frais pour le symbole évalué (fenêtre de fraîcheur courte — cf. §5, cadence Cryptolyze ~4 vidéos d'analyse/semaine), agrège en `OpinionSignal` pondéré par `credibilityWeight` (source) × `confidence` (claim) × décroissance temporelle. Une thèse répétée sur plusieurs vidéos successives se retrouve naturellement re-confirmée (nouveaux claims similaires à chaque nouvelle vidéo) sans mécanisme dédié
- Câblage dans `MarketOpinionRegistry` — pas de changement à `DecisionEngine`

**Lot 4 — Observabilité / ops**
- Traçabilité bout en bout (pouvoir remonter d'un signal jusqu'à l'extrait vidéo source, via `sources`/`reason` de `OpinionSignal`)
- Gestion des erreurs (pas de sous-titres disponibles, chaîne indisponible, transcript vide)
- Ajout d'une nouvelle chaîne = ajout `ContentSource`, aucun code à toucher

## 5. Fraîcheur, langue, coût — réponses de l'utilisateur (2026-07-10)

**Fraîcheur/décroissance des `MediaClaim`** : Cryptolyze publie ~4 vidéos d'analyse de marché/semaine (le reste étant du hors-sujet filtré en passe 1, §2). Cadence élevée → fenêtre de fraîcheur **courte**, de l'ordre de quelques jours plutôt que de semaines (valeur exacte à calibrer à l'implémentation, mais l'ordre de grandeur est fixé par cette cadence). Autre effet à exploiter plutôt qu'à combattre : Cryptolyze répète souvent les mêmes thèmes tant qu'ils restent d'actualité au moment de la vidéo — donc une thèse toujours valide se retrouve naturellement ré-émise en `MediaClaim` à chaque nouvelle vidéo, ce qui la maintient "fraîche" sans mécanisme de prolongation dédié.

**Langue** : FR pour Cryptolyze et pour toutes les autres chaînes envisagées à ce jour par l'utilisateur. Point technique restant (pas un choix produit, une vérification d'implémentation) : confirmer que l'extraction de transcript fonctionne correctement en FR avec la mécanique retenue (RSS + endpoint `timedtext`, §architecture précédente) — sous-titres FR pas garantis disponibles à 100 % selon les chaînes (auto-générés vs. ajoutés par le créateur), à tester en pratique sur une vraie vidéo Cryptolyze avant de généraliser à d'autres sources.

**Coût LLM** : vidéos de 15-20 min pour l'analyse de marché (donc un volume de texte conséquent), et jusqu'à ~30 min pour le hors-sujet — confirme que l'option A (2 passes, §2) est la bonne : la passe 1 (classification, extrait tronqué) filtre à faible coût avant que la passe 2 (extraction complète, coûteuse) ne soit déclenchée uniquement sur le sous-ensemble pertinent.

## 6. Points ouverts restants à trancher à l'implémentation

- Portion exacte de transcript envoyée en passe 1 (nombre de minutes/caractères, découpage par timestamps si disponibles) et seuil précis de la fenêtre de fraîcheur en Lot 3 (§5 fixe l'ordre de grandeur "quelques jours", pas la valeur exacte)
- Validation technique de l'extraction transcript FR (§5) sur un échantillon réel avant de généraliser à d'autres chaînes
- Coût LLM à l'échelle une fois le volume réel observé (nb chaînes suivies × vidéos/semaine × tokens passe 2)
