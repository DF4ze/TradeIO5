# Charte graphique — piste B « Rainbow doux » (sombre et clair)

Vérifié le : 2026-10-20. Source de vérité visuelle : maquettes (Artifact « TradeIO Maquettes » : `PlaqueB_Tel`, `PlaqueB_PC`, boards `Widgets · piste B`, `… · Rainbow`, `… courbes v3`). Cette page fixe les règles ; en cas d'écart, les valeurs ci-dessous font foi pour l'implémentation.

Périmètre : thèmes sombre (défaut) et clair, mêmes noms de tokens, valeurs différentes ; le choix se fait dans les réglages personnels (« Apparence », préférence serveur). Pas de variantes de style en parallèle.

Organisation des écrans : [`../etudes/etude-architecture-interface.md`](../etudes/etude-architecture-interface.md). Données affichées et backs nécessaires : [`inventaire-options-affichage.md`](inventaire-options-affichage.md).

## 1. Principes

- Sobre, sombre, lisible ; le Rainbow est un **accent de marque discret**, jamais un aplat.
- Violet = couleur d'accent (actions, sélection, repères). Le vert/rouge reste le code du trading (hausse/baisse, achat/vente).
- Une information n'est jamais portée par la couleur seule : toujours un symbole (▲ ▽), un libellé ou une forme (hachuré, pointillé).
- Mobile d'abord ; le PC réutilise les mêmes composants (nav latérale au lieu de la barre flottante).
- Les valeurs chiffrées sont en chiffres tabulaires. Donnée absente ⇒ « — ».

## 2. Tokens (mode sombre)

Variables CSS posées sur la racine ; tout composant n'utilise que ces variables.

```css
:root[data-theme="dark"] {
  --bg:      #0f1119;   /* fond de page */
  --surface: #171a27;   /* cartes */
  --text:    #eef0f8;
  --muted:   #8f94ad;   /* libellés, axes, secondaire */
  --line:    #272b40;   /* filets, pistes de barres */
  --accent:  #9d86ea;   /* violet : actions, sélection */
  --up:      #3fb67a;   /* hausse, achat, OK */
  --down:    #e5645c;   /* baisse, vente, bloqué */
  --warn:    #e0a63a;   /* attention, carry */
  --r: 24px;            /* rayon des cartes */
  --pad: 20px;          /* padding des cartes */
  --gap: 16px;          /* espacement entre cartes */
  /* spectre Rainbow, du rouge au violet */
  --s0:#ef7a70; --s1:#f0a35f; --s2:#e6cb68; --s3:#86c98c; --s4:#62b4c4; --s5:#8b93dc; --s6:#b98ad0;
}
```

Fond de page : `--bg` + deux lueurs violettes très douces (`radial-gradient(700px 420px at 10% -8%, rgba(150,110,240,.17), transparent 65%)` et `radial-gradient(520px 380px at 100% 28%, rgba(130,110,230,.09), transparent 60%)`).

Mode clair : `:root[data-theme="light"]` redéfinit les mêmes tokens (source : `static/assets/app/css/tradeio.css`). Token supplémentaire `--accent-text` : violet utilisé pour du **texte** (≥ 4,5:1 sur la surface dans les deux thèmes) ; `--accent` reste réservé aux aplats et repères.

## 3. Typographie

- Police unique : **Manrope** (400 / 500 / 600 / 700 / 800), chargée une fois ; repli `system-ui`.
- Chiffres : `font-variant-numeric: tabular-nums lining-nums`, graisse 800.
- Échelle : patrimoine 40 px · valeur de widget 28–34 px · valeur secondaire 18–24 px · titre de carte 13 px (700, `--muted`) · texte 12–14 px · note, axe 11 px.
- Titres de carte précédés d'un point violet 6 px.

## 4. Formes et espacement

- Carte : fond `--surface`, bordure 1 px `--line`, rayon 24 px, padding 20 px, ombre douce `0 10px 30px rgba(0,0,0,.18)`, contenu en colonne avec 12 px d'espacement.
- Sous-tuile (stat dans une carte) : fond `--bg`, rayon 18 px, padding 10×14 px.
- Pastille (chip) : rayon complet, 11 px / 700, texte couleur d'état sur fond de cette couleur à 16 % ; hauteur ≈ 24 px.
- Carte de tête (patrimoine) : léger dégradé `linear-gradient(160deg, accent 16 % mélangé au fond, surface 60 %)`.
- Écarts entre cartes : 16 px (mobile), 22–26 px (PC, catalogue).

## 5. Le Rainbow (règles d'usage)

Spectre `--s0 … --s6`. Toujours **fluide** (dégradés), **léger** (opacités faibles sur les pistes et aires), **porteur de sens** :

| Usage | Règle |
|---|---|
| Courbe de valeur (patrimoine, 24 h, valeur) | trait en dégradé vertical `--s0` (bas) → `--s3` (haut) ; aire sous la courbe en fondu 30 % → 0 |
| Pistes de curseurs / barres | piste en dégradé à 28 % d'opacité ; partie active (valeur) en couleur pleine par `clip-path` ; curseur rond clair avec halo de la couleur de sa position |
| Barres de progression « plus c'est plein, mieux c'est » (autonomie, marge) | `--s0 → --s3` |
| Exposition dans le temps | trait vert dans la zone libre, orange puis rouge vers les bornes |
| Jauge d'exposition | arc ouvert 270° (départ 225°), 0 % et 100 % étiquetés, dégradé spectre sur la bande 30–60 % de l'arc, repère rond blanc à la position |
| Pastille d'actif | cercle à dégradé de deux teintes du spectre + symbole (₿ Ξ Au ◎), pas de logos officiels |
| Filet de carte | trait de 2 px en haut de la carte, spectre complet, opacité 50 %, inset 26 px |
| Éléments de marque | avatar utilisateur `--s0 → --s5`, onglet actif `linear-gradient(135deg, --accent, #6f78c0)` |

Interdits : aplat Rainbow, plus de deux éléments Rainbow saturés dans un même écran mobile, texte coloré par le spectre.

## 6. Composants

| Composant | Règles |
|---|---|
| Statut | OK `--up` · ATTENTION `--warn` · BLOQUÉ `--down` · INCONNU `--muted` avec contour pointillé ; toujours libellé + pastille |
| Navigation mobile | pilule flottante en bas, 4 onglets (Accueil, Mes actifs, Journal, Réglages), onglet actif en dégradé violet |
| Navigation PC | barre latérale arrondie flottante (200 px) : logo, onglets, avatar en bas |
| En-tête | salutation, pastille verte « Exécution réelle » (ou « Simulation »), pastille violette « Prochaine passe dans N h » |
| Segmenté | pilule sur fond `--bg`, option active en dégradé violet |
| Interrupteur | piste 34×20 px, active en dégradé violet |
| Sélecteur de période | 7 j · 30 j · 90 j · 1 an · Tout, option active `--accent` sur texte `--bg` |
| Puces de périmètre | contour `--line` ; active : fond violet 24 %, contour et texte `--accent` |
| Calendrier de décisions | case 1 jour : achat `--s3`, renforcé `--s5`, vente `--s0`, rien `--line`, **carry = hachuré orange** (`repeating-linear-gradient(135deg, --s1 0 2px, transparent 2px 6px)` + contour) |
| Infobulle de graphique | fond `--bg`, contour `--line`, rayon 10 px, texte 11,5 px |

### Graphiques

- Bibliothèque : TradingView Lightweight Charts (graphique de la fiche) ; widgets simples en SVG.
- Achat : triangle plein ▲ `--up` ; vente : triangle ▽ `--down` ; prix moyen d'achat : pointillé `--muted`.
- Bandes Rainbow : zones de dégradé léger ; **bandes recalculées (non figées) en pointillé, libellées « simulé »**.
- Courbe actifs : dégradé `--s6 → --s4` ; courbe stable : `--s4 → --s3` ; total : pointillé clair 40 %.
- Bornes d'exposition (min 40 % / max 80 %) : pointillés `--s1`, bande entre elles en violet 7 %, zone libre 50–70 % en violet 10 %. Les courbes d'actifs et de stable restent entre les bornes.
- Écart entre deux courbes comparées : aire verte quand l'une est devant, rouge sinon ; fond du graphique teinté par la tendance du marché (vert 5 % / rouge 5 %).
- Réel (gains encaissés) en escalier ; potentiel (latent) en courbe ; DCA fixe en pointillé `--muted`.

## 7. Catalogue de widgets

Widgets retenus pour piocher en V1 (en V2 : choisis par l'utilisateur). Chacun est une carte autonome (données §2 de l'inventaire).

Patrimoine · Exposition (arc) · Décisions du jour · Puissance (0,5×–2×, repère 1×) · Biais achat / vente (−1 … +1) · Prix moyen vs cours · Clés d'échange · Répartition par actif · Variation 24 h · Prochain achat estimé · Prochaine passe · Cash et autonomie · Zone libre · Reporté (carry) · Investi par mois · Stable vs actifs (périmètre : par actif, actif/provider, par provider ; bornes min/max) · Répartition dans le temps · Exposition dans le temps · Rainbow vs DCA fixe (réel / potentiel) · Investi vs valeur · Record et recul · Marge de sécurité · Calendrier des décisions.

## 8. Implémentation (Thymeleaf + JS vanilla)

- Tokens dans une feuille de styles commune ; thème via `data-theme` sur `<html>` (sombre par défaut), préférence enregistrée côté serveur.
- Aucune couleur en dur dans les composants ; aucun `innerHTML` (`textContent` / création de nœuds).
- Dégradés de valeur : `linear-gradient` + `clip-path` (pas de canvas pour les barres et curseurs).
- Contrastes : texte principal et secondaire ≥ 4,5:1 sur `--surface`. Mesuré : sombre texte 15,2 · muted 5,8 ; clair muted 6,3. Pastilles : couleur du texte = `color-mix(var(--c) 78%, var(--text))` ⇒ ≥ 5,1:1 dans les deux thèmes.
- Accessibilité : lien « Aller au contenu » en tête de page, focus visible, `prefers-reduced-motion` (animations coupées), `forced-colors` (contours système), graphique du cours doublé d'un tableau de données repliable (`<details>`), information jamais portée par la couleur seule.
