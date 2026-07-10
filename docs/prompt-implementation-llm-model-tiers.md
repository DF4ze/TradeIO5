# Prompt d'implémentation — Système de niveaux de modèles LLM (Peu coûteux / Normal / Puissant)

Ce prompt est autonome : il peut être donné tel quel à une session d'implémentation qui n'a pas le contexte des discussions précédentes. Il couvre une brique d'infrastructure générique (sélection du modèle LLM par niveau de coût/capacité), **pas** une feature applicative précise.

Avant de commencer, lire dans l'ordre :
1. `model/enumerate/OpenAIModel.java` — catalogue actuel des modèles concrets, seulement 2 valeurs aujourd'hui (`GPT_4_1_MINI`, `GPT_4_1`).
2. `configuration/properties/OpenAIProperties.java` — un seul `defaultModel` configuré globalement (`tradeio.openai.*`).
3. `service/connector/OpenAIService.java` — `ask(String userInput)` utilise systématiquement `props.defaultModel()`, aucun moyen d'override par appel aujourd'hui.
4. `service/tree/opinion/advisor/OpenAIAdvisor.java` et `AbstractAdvisor.java` — seul appelant actuel de `OpenAIService.ask`, à ne pas casser.
5. `service/tree/opinion/advisor/DecisionAdvisor.java` et `model/enumerate/tree/indicator/AdvisorType.java` — l'abstraction advisor existante (une seule valeur `LLM_OPENAI` actuellement).

## Contexte / besoin

L'utilisateur veut pouvoir choisir, à chaque appel LLM, un **niveau** logique parmi 3 — Peu coûteux / Normal / Puissant — plutôt que de coder en dur un modèle nommé à chaque site d'appel. Le niveau est mappé en configuration vers un modèle concret chez le fournisseur, ce qui permet de changer de modèle (ou de fournisseur) sans toucher au code appelant, et de maîtriser le coût par cas d'usage (tâche simple à haut volume → niveau bas, tâche fine à faible volume → niveau plus élevé).

**Cas d'usage déclencheur** (illustratif, pas à implémenter ici) : `docs/etude-veille-media-youtube.md` prévoit un pipeline en 2 passes LLM par vidéo — une passe de classification légère à faire sur un gros volume (niveau bas) et une passe d'extraction plus fine sur un sous-ensemble filtré (niveau intermédiaire). Cette feature n'est pas encore implémentée et ne fait pas partie de ce prompt — garder simplement l'API assez générique pour qu'elle puisse la consommer une fois prête.

**Note** : ce système n'existe pas encore dans TradeIO5 (vérifié) — l'utilisateur avait initialement cru l'avoir déjà demandé ici, il s'agissait en fait d'un autre projet. C'est donc une création complète, pas une reprise d'un travail existant.

## À faire (proposition de design, à valider/adapter à l'implémentation)

1. Introduire une notion de **niveau** découplée du modèle concret, ex. `enum LlmTier { CHEAP, NORMAL, POWERFUL }` (nom à trancher). `OpenAIModel` reste le catalogue des modèles concrets disponibles chez le fournisseur — ne pas fusionner les deux notions.
2. `OpenAIModel` n'a que 2 valeurs aujourd'hui : évaluer s'il faut un 3ᵉ modèle concret pour distinguer clairement "Normal" de "Puissant" (sinon 2 niveaux se retrouveraient mappés sur le même modèle, ce qui n'est pas forcément un problème en soi mais doit être un choix explicite, pas un oubli).
3. `OpenAIProperties` : remplacer/compléter le `defaultModel` unique par un mapping niveau → modèle concret, configurable (ex. `tradeio.openai.model.cheap`, `tradeio.openai.model.normal`, `tradeio.openai.model.powerful`). Prévoir un comportement de fallback documenté si un niveau n'est pas configuré (pas d'exception au démarrage).
4. `OpenAIService` : ajouter une variante de `ask(...)` acceptant un `LlmTier` en paramètre, en conservant la méthode actuelle sans paramètre (comportement par défaut, pour ne rien casser côté `OpenAIAdvisor`).
5. Trancher explicitement si l'abstraction reste mono-fournisseur (OpenAI uniquement, cohérent avec `AdvisorType.LLM_OPENAI` qui n'a qu'une valeur aujourd'hui) ou si elle doit anticiper un futur multi-fournisseur. Ne pas sur-ingiéner si rien ne l'exige actuellement côté code, mais documenter le choix fait plutôt que de le laisser implicite.
6. Tests : résolution niveau → modèle concret depuis la config ; comportement de fallback si un niveau n'est pas configuré ; non-régression sur `OpenAIAdvisor` (même modèle par défaut qu'avant ce changement).

## Hors scope

Ne pas implémenter la feature "veille média YouTube" (`docs/etude-veille-media-youtube.md`) dans ce prompt — uniquement l'infrastructure de sélection de modèle qu'elle (et d'autres futurs appelants) consommera ensuite.
