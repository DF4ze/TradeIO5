---
statut: charte
sujet: Charte de conservation de la documentation
modifié_le: 2026-11-10
empreinte: 407565fd97
---
# Charte de conservation

## Types (`statut` en en-tête)
| statut | Rôle | Cycle de vie |
|---|---|---|
| `courant` | état vérifié (architecture/, api/, operations/) | mis à jour avec le code ; contenu obsolète supprimé |
| `decision` | position tranchée de Clem (decisions/) | modifiée seulement sur nouvelle décision |
| `gap` | reste à faire (known-gaps/) | retiré une fois fait |
| `journal` | étude/calibration datée | archivée une fois sa décision reprise ailleurs |
| `archivé` | historique | jamais référence ; indexé dans `archive/README.md` |
| `index`/`charte` | README, CHARTE | — |

## En-tête
Géré par script (`doc_police.py index`) : `modifié_le`, `empreinte` (ne pas éditer). Géré à la main : `statut`, `sujet`, `vérifié_le` (jamais automatique : atteste un contrôle code ↔ doc), `remplacé_par`.

## Règles
- Pas de doc orpheline : tout `.md` est accessible depuis `docs/README.md` en ≤ 3 clics.
- Pas de lien cassé. Déplacer = `doc_police.py archive` (réécrit les liens).
- Taille cible 3–15 Ko ; > 20 Ko => à scinder. Profondeur max 2.
- Pas d'historique dans les docs courants. Une décision n'est écrite qu'à un seul endroit.
- Étude : archivée quand elle n'est plus modifiée depuis > 60 j et que sa décision est reprise.
- Un `vérifié_le` > 60 j est signalé (STALE).

## Police hebdomadaire
Dry-run par défaut ; auto-corrections limitées (liens après déplacement, bandeau d'archive) sur branche `docs/police-AAAA-SS`, jamais sur main ; le reste = propositions numérotées. Ne crée pas de doc, ne réécrit pas le fond, ne supprime rien. Baseline : `.doc-police/baseline.json`.
