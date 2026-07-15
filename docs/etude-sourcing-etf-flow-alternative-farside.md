# Étude : sourcing d'un flux ETF Bitcoin/Ethereum plus fiable que le scraping Farside

> Contexte : `EtfFlowIndicator`/`FarsideEtfFlowClient` scrapent du HTML Farside non versionné
> (`docs/etude-nouvelles-opinions-indicateurs-non-branches.md` §5). Plan retenu et validé : brancher
> `ETF_FLOW` comme `StrategyType.CONFIDENCE_MODULATOR` (patron `MovementQualificationStrategy`/
> `OrderFlowStrategy`), restreint à BTC*/ETH*, jamais comme signal directionnel. Cette étude cherche
> une source plus robuste qu'un scraping HTML brut avant de coder ce branchement.

## 0. Verdict en une ligne

Il existe une meilleure option qu'un scraping HTML : **CoinGlass** expose une API REST officielle,
documentée et versionnée pour ce flux exact (`/api/etf/bitcoin/flow-history`,
`/api/etf/ethereum/flow-history`), au même palier de coût que Coinalyze déjà en place dans le
projet. **SoSoValue** propose une alternative comparable, moins mature commercialement. Le
scraping Farside n'est donc plus la seule option viable — mais si Clem préfère rester sur Farside
pour des raisons de coût (gratuit), le go déjà donné reste défendable : §6 détaille les risques
opérationnels à documenter dans ce cas.

## 1. Comparatif des pistes évaluées

| Piste | API officielle | Coût | Fraîcheur | Fiabilité structurelle | Intégration `ApiCredentialDTO` |
|---|---|---|---|---|---|
| **CoinGlass** | Oui, REST JSON versionné, doc publique | Payant, dès $29/mois (palier Hobbyist) | Quotidien, même cadence que Farside (post-clôture US) | Élevée — schéma JSON stable, contrat documenté | Immédiate, même patron que `COINALYZE` |
| **SoSoValue** | Oui, REST JSON, clé `x-soso-api-key` | Palier "Demo" gratuit actuel, palier payant "à venir" (pas encore public au moment de l'étude) | Quotidien | Correcte mais moins éprouvée : accès historiquement par vague/liste d'attente | Immédiate, même patron |
| **Farside (tier payant/API officielle)** | Non — aucune API officielle publiée par Farside lui-même | — | — | Un wrapper tiers (Parse.bot) existe mais scrape lui-même Farside : ajoute un intermédiaire sans supprimer le risque de fond | N/A |
| **Glassnode** | Oui (`institutions.UsSpotEtfFlowsNet/All`), couvre BTC+ETH par émetteur | Élevé — paliers API Glassnode typiquement institutionnels (bien au-dessus de CoinGlass pour un besoin aussi ciblé) | Intraday (rafraîchi 00h-18h UTC) | Élevée | Possible mais coût disproportionné pour ce seul besoin |
| **Bloomberg / Refinitiv** | Oui, mais terminal/licence institutionnelle | Très élevé (milliers $/mois), hors échelle du projet | Bloomberg capture l'activité de création/rachat, proche du flux réel mais pas exhaustif non plus | Élevée mais coût rédhibitoire | Non pertinent à ce stade |
| **Twelve Data / Polygon.io** | Aucune couverture identifiée des flux ETF spot crypto (ce sont des fournisseurs de cours, pas de flux de fonds) | — | — | — | Écarté : pas de donnée à consommer |
| **API officielle émetteur (BlackRock, Fidelity...)** | Aucune API publique par émetteur — les flux quotidiens sont reconstruits par des tiers (CoinGlass, SoSoValue, Farside, The Block) à partir des publications NAV/parts en circulation de chaque émetteur, pas d'un flux structuré direct | — | — | Aucun accès direct possible | Écarté |
| **Proxy on-chain (réserves wallets custodian)** | N/A | Gratuit (déjà un MCP blockchain disponible dans l'environnement) | Temps réel | **Non viable** : ~90 % des actifs des ETF BTC sont custodiés chez Coinbase Custody dans des wallets omnibus mutualisés entre émetteurs — la part de chaque ETF n'est pas attribuable on-chain, seulement via un registre interne du custodian | Écarté |

## 2. Détail par piste

### 2.1 CoinGlass — recommandation principale

Endpoint `GET https://open-api-v4.coinglass.com/api/etf/bitcoin/flow-history` (et équivalent
`/ethereum/`), disponible sur **tous** les paliers payants y compris le moins cher (Hobbyist,
$29/mois ou $348/an). Réponse JSON stable :

```json
{
  "data": [{
    "timestamp": 1704931200000,
    "flow_usd": 655300000,
    "price_usd": 46663,
    "etf_flows": [
      {"etf_ticker": "GBTC", "flow_usd": -95100000},
      {"etf_ticker": "IBIT", "flow_usd": 111700000}
    ]
  }]
}
```

C'est structurellement le même contrat que ce qu'`EtfFlowResponse` expose déjà (`total` + `byIssuer`
par ticker) — le remplacement de `FarsideEtfFlowClient` par un `CoinglassEtfFlowClient` ne
changerait pas `EtfFlowIndicator` ni `EtfFlowProvider`, seulement l'implémentation `fetch()` (JSON
Jackson au lieu de Jsoup, comme les autres clients externes du projet). Intégration credential
identique au patron `COINALYZE` déjà en place (`WebProviderCode` + clé dans
`application-dev.properties`, résolue par `IndicatorCredentialResolver`).

Coût : $29/mois pour un besoin ciblé (2 endpoints) est le principal point d'arbitrage face à
Farside qui est gratuit.

### 2.2 SoSoValue — alternative viable, moins mature commercialement

API officielle documentée (`sosovalue.gitbook.io`), couvre BTC/ETH/SOL avec flux net par émetteur +
total, historique jusqu'à 300 jours. Palier "Demo" gratuit actuellement disponible pour tous les
utilisateurs SoSoValue ; un palier payant est annoncé mais son tarif n'était pas encore public au
moment de la recherche (juillet 2026). L'accès à la clé API s'est historiquement fait par vagues
(premiers inscrits), ce qui est moins prévisible qu'un simple achat de plan CoinGlass. À garder en
second choix, ou en secours si le tarif CoinGlass évolue défavorablement.

### 2.3 Farside — pas de tier payant/API officielle

Recherche confirmée : Farside ne publie aucune API officielle, payante ou non. Un wrapper tiers
existe (Parse.bot) mais il scrape lui-même farside.co.uk — il ajoute un intermédiaire commercial
sans supprimer la fragilité structurelle (toujours dépendant de la mise en page HTML de Farside),
et sans garantie de continuité du service tiers lui-même.

### 2.4 Bloomberg / Refinitiv / Twelve Data / Polygon.io

Bloomberg capture l'activité de création/rachat des parts, une proxy assez proche du flux réel mais
pas exhaustive, et accessible seulement via terminal/licence institutionnelle — hors échelle
budgétaire du projet (le projet a déjà arbitré en faveur du gratuit sur SP500/NASDAQ en basculant
de Twelve Data payant vers Yahoo Finance gratuit, cf. mémoire `tradeio5_etat_indicateurs`). Twelve
Data (déjà utilisé pour DXY) et Polygon.io sont des fournisseurs de cours/quotes ; aucune couverture
identifiée des flux de fonds ETF crypto chez l'un ou l'autre — écartés faute de donnée à consommer,
pas par arbitrage de coût.

### 2.5 APIs émetteurs et proxy on-chain — écartés, sans alternative

Aucun émetteur (BlackRock, Fidelity, etc.) ne publie d'API de flux quotidien : les agrégateurs
(CoinGlass, SoSoValue, Farside, The Block) reconstruisent tous le flux à partir des publications
NAV/parts en circulation de chaque émetteur — il n'existe pas de "source primaire" à contourner.

Le proxy on-chain (variation des réserves de wallets custodian connus) est structurellement
non-viable : environ 90 % des actifs des ETF BTC spot sont custodiés par Coinbase Custody dans des
wallets **omnibus**, mutualisés entre plusieurs émetteurs. La part de chaque ETF dans ce wallet
n'est pas une information on-chain — elle n'existe que dans le registre interne du custodian. Aucun
signal directionnel fiable par émetteur ne peut être reconstruit ainsi.

## 3. Recommandation

**Basculer `FarsideEtfFlowClient` vers CoinGlass** (`CoinglassEtfFlowClient`, même interface
`EtfFlowProvider`), pour ~$29/mois, avant de coder le branchement `CONFIDENCE_MODULATOR`. Gain
principal : un contrat JSON documenté et versionné remplace un parsing HTML dont la étude actuelle
documente déjà la fragilité (`FarsideEtfFlowClient` javadoc, §mécanisme de détection de rupture de
structure). Le patron d'intégration (credential, resolver, indicateur, tests) ne change pas — seul
le client change, ce qui limite le risque de régression.

Si le coût de $29/mois n'est pas souhaité pour un signal qui reste un simple modulateur de
confidence (jamais un signal directionnel), deux repli possibles, par ordre de préférence :
1. **SoSoValue palier Demo** (gratuit aujourd'hui) — même robustesse structurelle qu'une API REST
   officielle, mais accès et pérennité du palier gratuit moins garantis dans le temps.
2. **Rester sur Farside scrapé** — le go de Clem est déjà acquis pour cette option ; §4 documente
   les risques opérationnels à surveiller dans ce cas.

## 4. Si le choix reste Farside (scraping) : risques opérationnels à documenter

Pas de blocage de principe — uniquement les points à surveiller/documenter puisque la source reste
un scraping HTML non contractuel :

- **Rupture de structure silencieuse.** Farside peut changer sa mise en page sans préavis ; le
  parsing actuel détecte déjà plusieurs modes d'échec par étape (en-tête introuvable, aucune colonne
  émetteur, format de nombre invalide — cf. javadoc `FarsideEtfFlowClient.parse`), ce qui limite le
  risque de faux silence mais ne l'élimine pas totalement (un changement qui produit un HTML
  toujours "valide" syntaxiquement mais sémantiquement différent ne serait pas détecté).
- **Absence de SLA.** Aucun engagement de disponibilité ni de délai de publication — en cas de panne
  prolongée du site, `EtfFlowResponse.invalid()` couvre déjà le cas côté indicateur, mais rien
  n'alerte proactivement qu'une panne dure depuis N jours.
- **Blocage anti-scraping possible.** Rate-limiting ou blocage IP/User-Agent côté Farside restent
  possibles à tout moment sans préavis contractuel, contrairement à une clé API révocable mais
  documentée.
- **Pas de recours en cas d'erreur de donnée.** Une valeur publiée erronée par Farside (déjà arrivé
  historiquement sur ce type de tableau agrégé) n'a pas de canal de correction/notification, à
  la différence d'une API commerciale avec support.
- **Cohérent avec le statut "modulateur" déjà acté.** Ces risques renforcent — sans la remettre en
  cause — la décision déjà prise de ne jamais laisser `ETF_FLOW` créer un signal directionnel, y
  compris s'il reste sourcé via Farside.

## 5. Sources consultées

- CoinGlass API docs — endpoint ETF Flows History (`docs.coinglass.com/reference/etf-flows-history`),
  pricing (`coinglass.com/pricing`).
- SoSoValue developer portal (`sosovalue.com/developer`) et doc API
  (`sosovalue.gitbook.io/soso-value-api-doc`).
- Farside Investors (`farside.co.uk`) — absence d'API officielle confirmée ; wrapper tiers Parse.bot
  identifié comme scraper de second niveau.
- Glassnode docs (`docs.glassnode.com/basic-api/endpoints/institutions`) — métriques
  `UsSpotEtfFlowsNet`/`UsSpotEtfFlowsAll`.
- The Block, Bitwise/Grayscale/VanEck 10-K SEC filings, CoinGape — structure de garde (custody)
  omnibus des ETF BTC spot, concentration ~90 % chez Coinbase Custody.
