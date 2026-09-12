# TradeIO5 — Documentation

Point d'entrée unique pour comprendre l'état **actuel** de l'application sans lire le code.

## Pour un agent qui découvre ce projet

Lis dans cet ordre :

1. [`architecture/01-overview.md`](architecture/01-overview.md) — ce que l'app fait réellement, ce qu'elle ne fait PAS (point critique, mal représenté par les anciens documents).
2. [`architecture/02-tree-pipeline.md`](architecture/02-tree-pipeline.md) — le cœur métier (Indicator → Strategy → Opinion → Scenario → Decision).
3. Les autres fichiers de `architecture/` selon le besoin (données de marché, providers externes, veille média, sécurité, multi-utilisateur).
4. [`known-gaps/decision-to-order-gap.md`](known-gaps/decision-to-order-gap.md) — **à lire avant de supposer que l'app passe de vrais ordres.** C'est le malentendu le plus probable.
5. `api/` et `operations/` pour les détails d'intégration (endpoints REST, tools MCP, jobs planifiés, configuration).
6. [`glossary.md`](glossary.md) pour les acronymes/enums utilisés partout sans être réexpliqués.

Chaque fichier indique sa date de vérification par rapport au code (`Vérifié le : ...`). Un fichier sans code source cité doit être considéré comme suspect.

## Statut de ce redocumentage (2026-09-12)

Cette arborescence (`docs/architecture/`, `docs/api/`, `docs/operations/`, `docs/known-gaps/`, `docs/glossary.md`) a été rédigée en lisant le code source (contrôleurs, jobs, configuration, moteur `tree`, sécurité) et en croisant avec la documentation existante. Chaque affirmation d'architecture est vérifiée sur le code au 2026-09-12 sauf mention contraire explicite ("non vérifié", "à confirmer").

**Ce qui n'a pas été ré-audité dans ce lot** (laissé en l'état, toujours potentiellement utile mais non garanti à jour) :
- `docs/calibration/*.md` — verdicts de calibration statistique des indicateurs. Contenu de nature différente (résultats de backtests), pas une description d'architecture.
- `docs/specs-initiales/*.odt/.ods` — specs d'origine du projet, formats bureautiques non explorés.
- `docs/CODING_RULES.md` — toujours pertinent, inchangé.
- La couche repository/entity JPA (structure des tables) — non détaillée fichier par fichier, seulement via ce qu'en disent les services qui les consomment.

## Anciens documents

Les anciens documents narratifs (`docs/suivi/`, ancien `README.md` racine) ont été **archivés** dans [`archive/`](archive/) : ils mélangeaient journal de bord chronologique et état courant, ce qui produisait les conclusions erronées à l'origine de ce redocumentage. Les emplacements d'origine contiennent désormais un stub de redirection.

`docs/etudes/` et `docs/prompts/` restent en place : ce sont par construction des journaux de conception/implémentation datés (pas des documents prétendant décrire l'état courant), donc moins à risque de méprise — mais ils ne doivent **jamais** être lus comme référence d'architecture. Un fichier `README.md` a été ajouté dans chacun de ces deux dossiers pour le rappeler explicitement. Ils n'ont pas été déplacés dans `archive/` (voir note en fin de conversation sur les contraintes d'outillage).
