# TradeIO5 — Documentation

Point d'entrée unique pour comprendre l'état **actuel** de l'application sans lire le code.

## Pour un agent qui découvre ce projet

Lis dans cet ordre :

1. [`architecture/01-overview.md`](architecture/01-overview.md) — ce que l'app fait réellement, ce qu'elle ne fait PAS (point critique, mal représenté par les anciens documents).
2. [`architecture/02-tree-pipeline.md`](architecture/02-tree-pipeline.md) — le cœur métier (Indicator → Strategy → Opinion → Scenario → Decision).
3. Les autres fichiers de `architecture/` selon le besoin (données de marché, providers externes, veille média, sécurité, multi-utilisateur, [bench grandeur nature Rainbow](architecture/08-rainbow-bench-grandeur-nature.md), [Trend unifié](architecture/09-trend.md), [plan d'ordres dry-run](architecture/10-execution-plan.md), [exécution réelle verrouillée](architecture/10-execution-reelle.md)).
4. [`known-gaps/decision-to-order-gap.md`](known-gaps/decision-to-order-gap.md) — **à lire avant de supposer que l'app passe de vrais ordres.** C'est le malentendu le plus probable.
5. `api/` et `operations/` pour les détails d'intégration (endpoints REST, tools MCP, jobs planifiés, configuration, [Flyway et règle des défauts d'initialisation](operations/flyway.md)).
6. [`glossary.md`](glossary.md) pour les acronymes/enums utilisés partout sans être réexpliqués.

Chaque fichier indique sa date de vérification par rapport au code (`Vérifié le : ...`). Un fichier sans code source cité doit être considéré comme suspect.

## Objectif en cours

Automatiser le **DCA intelligent** (Rainbow ATR) : état de la chaîne et chantiers ouverts dans [`known-gaps/rainbow-dca-chantiers-ouverts.md`](known-gaps/rainbow-dca-chantiers-ouverts.md).

## Ce qui n'est pas garanti à jour

- `docs/calibration/*.md` — protocole et résultats de benchs, pas de l'architecture. Le verdict « un jeu global par actif suffit » (bench Rainbow ATR) est **invalidé** (cf. `calibration-rainbow-atr-v3-regime.md`).
- `docs/specs-initiales/*.odt/.ods` — specs d'origine, formats bureautiques non explorés.
- La couche repository/entity JPA — non détaillée table par table.

## Anciens documents

Les anciens documents narratifs (`docs/suivi/`, ancien `README.md` racine) ont été **archivés** dans [`archive/`](archive/) : ils mélangeaient journal de bord chronologique et état courant, ce qui produisait les conclusions erronées à l'origine de ce redocumentage. Les emplacements d'origine contiennent désormais un stub de redirection.

`docs/etudes/` et `docs/prompts/` restent en place : ce sont par construction des journaux de conception/implémentation datés (pas des documents prétendant décrire l'état courant), donc moins à risque de méprise — mais ils ne doivent **jamais** être lus comme référence d'architecture. Un fichier `README.md` a été ajouté dans chacun de ces deux dossiers pour le rappeler explicitement. Ils n'ont pas été déplacés dans `archive/` (voir note en fin de conversation sur les contraintes d'outillage).
