---
statut: courant
sujet: Mind-set Cryptolyze — Pas à pas DeFi
modifié_le: 2026-11-10
empreinte: 1a73b2461a
---
# Pas à pas DeFi

> Source : PDF Cryptolyze (original : `archive/sources/cryptolyze/Pas à Pas DeFi.pdf`). Conversion texte brute — vérifier le PDF pour mise en page/figures. Référence de **mind-set** (philosophie d'investissement), pas une spec d'implémentation.

DÉCOUVRE
LA DeFi !
​

Maintenant que tu as vu et compris le potentiel des stratégies DeFi,
c'est le moment de transformer la théorie en résultats concrets.
Fini d'observer, place à la pratique ! Ce guide a été conçu pour
t'accompagner pas à pas vers tes premiers rendements. Nous allons
revoir les bases indispensables et configurer ensemble tes outils pour
que tu puisses profiter pleinement de la DeFi dès aujourd'hui.

Tu y trouveras :
●​ Les notions théoriques indispensables ;
○​ Wallet
○​ Lending
○​ Pool de liquidité
○​ Gestion du risque
●​ Un tutoriel pas à pas pour interagir avec une pool de liquidité.
○​ Comment faire pas à pas les étapes pour interagir avec la DeFi.

Disclaimer : Ce guide se veut être un tutoriel et non un conseil de
placement. Il a pour objectif de montrer comment utiliser les outils liés à
l'écosystème et ne doit en aucun cas servir de conseil financier.
Toute perte réalisée durant le tutoriel ne pourra être retournée contre RIFT
ou un tiers. La crypto et la DeFi comportent des risques ; je vous invite à
utiliser ces outils avec la plus grande prudence.

Pour utiliser la DeFi, il te faut évidemment un wallet (Rabby) qui te servira
d’interface avec la blockchain.

Ouvrir un wallet
Ce guide pas à pas explique comment installer, configurer et sécuriser ton
premier portefeuille Rabby Wallet :
Prérequis :
Pour commencer, équipe-toi d’un navigateur basé sur Chromium
(Chrome, Brave, Edge) ou de Firefox, compatible avec l’extension Rabby.
Installation de l’extension
1.​ Rendez-vous sur le site officiel : https://rabby.io — méfie-toi des
clones publicitaires.

2.​ Clique sur « Download for Chrome/Brave/Firefox ».

3.​ Dans le Chrome Web Store, sélectionner « Ajouter à Chrome », puis
confirmer.

4.​ Épingle l’icône du lapin bleu dans la barre d’outils pour un accès
rapide.

Création d’un nouveau wallet
1.​ Clique sur l’icône Rabby ▸ « Get started ».

2.​ Choisis « Create New Seed Phrase » ; si tu possèdes déjà une seed,
cliquez plutôt sur « Import ».

3.​ Définit un mot de passe robuste ; pour plus de facilité, utilise un
gestionnaire de mots de passe comme Bitwarden.

4.​ Lis attentivement les avertissements, puis clique sur « Show Seed
Phrase ».

5.​ Note les 12 mots sur papier ou sur une plaque métallique ; n’effectue
ni capture d’écran ni stockage cloud. Cette phrase est la clé d’accès à
toutes vos crypto-monnaies Il ne faut pas qu’elle soit vulnérable à un
malware.

6.​ Confirme la seed. Rabby affichera jusqu’à 50 adresses ; coche celles à
activer (une seule suffit pour commencer).

Tu bloques sur une étape ou tu n'es pas sûr de toi ? C'est
normal, la pratique peut parfois être compliqué.
Si tu souhaites aller plus loin, sache que nos offres sont conçues
pour t'accompagner de A à Z. Tu y retrouveras une formation
détaillée sur la DeFi, une communauté active pour échanger
et poser toutes tes questions, ainsi que des outils performants
pour t'aider à naviguer sereinement dans ce nouvel écosystème.
Je t'invite à cliquer sur ce lien pour réserver un rendez-vous avec
un membre de l'équipe et en discuter.

Recevoir des fonds
Vous avez maintenant créé votre wallet et sécurisé les informations
importantes. Il va falloir obtenir des fonds sur ce wallet. Je vous invite à
sélectionner le réseau Base (Layer 2 Ethereum) : c’est un réseau à faibles
frais, sécurisé et compatible avec de nombreuses d’Apps.
1.​ Dans Rabby, cliquez sur Receive

2.​ Choisissez le réseau “base”.

3.​ Copiez l’adresse (identique sur tous les réseaux EVM).

4.​ Depuis un exchange, rendez-vous dans votre portefeuille et cliquez
sur Retrait.

5.​ Sélectionnez le token à envoyer, collez l’adresse, puis choisissez le
même réseau pour l’envoi.

Attention : vérifiez que l’adresse copiée correspond bien au réseau choisi.
Je vous conseille d’effectuer d’abord un test en envoyant une petite
somme de crypto pour vous assurer de la bonne réception.
Vous avez maintenant votre wallet prêt : passons à la DeFi ! Prêt(e) ?
C’est parti.

Faire son premier emprunt :
La première étape consiste à vous rendre sur Aave, une plateforme de
lending.

AAVE
Vous avez oublié ce qu’est le lending ? Pas de problème, voici une
explication.

Théorie :
​
Le lending en DeFi désigne l’activité de prêt et d’emprunt d’actifs
numériques sur des plateformes automatisées et décentralisées. Tout
repose sur des contrats intelligents qui exécutent les transactions sans
intermédiaire humain.
Les deux parties principales
Le prêteur dépose ses cryptos dans un pool de liquidité.
1.​ L’emprunteur y accède en déposant une garantie (collatéral)
suffisante.

Comment ça fonctionne ?
1.​ Sur-collatéralisation obligatoire​
L’emprunteur doit déposer un collatéral dont la valeur dépasse celle
du prêt. Cela limite les risques pour les prêteurs.​
Exemple : pour emprunter 100 USDC, il faut déposer environ 150 $
d’ETH.

2.​ Pools de liquidité​
Les fonds prêtés sont mutualisés : chaque utilisateur interagit avec
une pool plutôt qu’avec une personne en particulier. Les intérêts
sont calculés en temps réel via un algorithme.

3.​ Taux dynamiques​
Les taux d’intérêt varient selon l’offre et la demande. Certains
protocoles proposent aussi un taux stable.

Avantages du lending décentralisé
-​ Accessibilité mondiale 24 h/24, 7 j/7 – sans contrôle d’identité (KYC)
-​ Revenus passifs – les prêteurs perçoivent des intérêts exprimés en
APY (Annual Percentage Yield)
-​ Transparence totale – toutes les opérations sont visibles, consultables
et auditées sur la blockchain
Principaux risques à connaître
-​ Volatilité du collatéral​
Si la valeur du collatéral chute trop, la position de l’emprunteur peut
être liquidée automatiquement.
-​ Failles techniques​
Les smart contracts ne sont pas infaillibles : une erreur de code ou un
oracle mal configuré peut entraîner des pertes.
-​ Taux instables​
En période de forte demande ou de crise, les taux peuvent fluctuer
fortement, affectant la rentabilité pour les prêteurs et le coût pour les
emprunteurs.
Petit glossaire utile
-​ APY : rendement annuel composé
-​ LTV (Loan-to-Value) : ratio entre le montant emprunté et la valeur du
collatéral
-​ Health Factor : indicateur de sécurité d’un emprunt (plus il est élevé,
mieux c’est)
-​ Flash Loan : prêt sans collatéral, remboursé instantanément

C’est plus clair ? Très bien, commençons !

Pratique :
Lorsque tu es sur le site, connecte ton wallet à Aave. Une fois connecté, tu
verras quatre blocs :

Assets to supply : ce sont les actifs disponibles dans ton wallet que tu peux
déposer sur la plateforme.
​
Your supplies : ce sont les actifs que tu as déjà déposés sur Aave et qui te
permettront d’emprunter.
Lorsque tu effectues un dépôt, tu perçois un taux d’intérêt lié à ton actif.
Par exemple, dans ma capture d’écran, pour le BTC, le taux est de 0,05%
par an.
Assets to borrow : ce sont les actifs que tu peux emprunter sur Aave, en
fonction de ce que tu as déposé dans « Your supplies »

Lorsque tu empruntes, un taux d’intérêt est appliqué ; c’est le coût annuel
de ton emprunt, ce taux est variable. Sur l’ETH, il est par exemple de 2,72%.
Your borrows : ce sont les montants que tu as empruntés. Attention : si tu
empruntes de l’ETH et que son prix augmente, tu devras rembourser
davantage ; à l’inverse, si le prix baisse, tu rembourseras moins. Au début, il
est préférable d’emprunter des stablecoins, pour plus de sérénité le temps
de tester.
À savoir : en haut à gauche, tu as les informations sur tes positions
actuelles, ton taux annuel (positif ou négatif) et ton niveau de liquidation.
Surveille-les bien : tu peux être liquidé si ta LTV (% d’emprunt) dépasse le
seuil critique. Il est conseillé de ne pas dépasser 30% de LTV ; si ta LVT est
trop importante tu risques de perdre une part conséquente de tes actifs.​
​

Pour savoir quel est ce seuil je t’invite à cliquer sur les assets :

Voilà, tu as désormais réalisé ton dépôt sur Aave (BTC ou ETH
recommandés), sélectionné USDC pour emprunter et veillé à ne pas avoir
une LTV trop élevée.

Ouvrir ta première pool :
Nous allons créer notre pool sur BTC. Tu te demandes sûrement comment
fonctionnent les pools de liquidité et à quoi elles servent.

Théorie :
Pour faire simple, les pools de liquidité utilisent des teneurs de marché
automatisés (AMM) qui remplacent les carnets d’ordres traditionnels. Au
lieu de chercher un acheteur ou un vendeur spécifique, les utilisateurs
échangent directement contre la pool de liquidité.
Cela permet :
-​ Faciliter les échanges décentralisés​
Les pools offrent des transactions instantanées sans avoir besoin de
contrepartie directe. Elles assurent une liquidité permanente 24 h/24
sur des DEX comme Uniswap, PancakeSwap ou SushiSwap.
-​ Réduire les frais et les délais​
Contrairement aux échanges centralisés, les pools éliminent les
intermédiaires et diminuent significativement les coûts de
transaction. Les échanges se font directement via le smart contract.
-​ Générer des revenus passifs​
Les fournisseurs de liquidité perçoivent une part des frais de chaque
transaction. Typiquement, ils touchent entre 0,3% et 1% de
commission par échange.
Le tout de manière décentralisée. Tu comprends maintenant l’utilité
réelle des pools de liquidité : leur rendement provient des échanges
effectués sur ces pools, dont les frais sont redistribués aux fournisseurs de
liquidité.

Pratique :
Tu as récupéré tes USDC sur ton wallet. Dans cet exemple, nous allons
ouvrir une pool BTC/USDC : comme tu l’as compris, nous serons la
contrepartie des échanges sur cette pool et nous serons rémunéré pour
cela.
1.​ Rends-toi sur Uniswap (Uniswap).

2.​ Connecte ton wallet, comme sur Aave.

3.​ Clique sur Explorer (en haut à gauche) → Pools.

4.​ Sélectionne la pool cbBTC/USDC (adresse :
0xfBB6Eed8e7aa03B138556eeDaF5D271A5E1e43ef).​

Attention : cette pool peut ne plus être disponible selon le moment
où tu consultes ces lignes.

5.​ Sur la page de la pool, clique sur ajouter de la liquidité (en haut à
droite).

6.​ Valide les deux actifs à déposer : cbBTC et USDC.

7.​ Choisis Canal personnalisé pour définir précisément le range sur
lequel tes actifs seront placés et généreront des frais.

​
Attention : si le prix sort de ce range (à la hausse ou à la baisse), tu ne
générera plus de frais et ta position perdra de son intérêt.​
Pour débuter, privilégie un range suffisamment large pour générer
continuellement des frais.

8.​ Si tu manques de cbBTC (la quantité varie selon le range
sélectionné), échange une partie de tes USDC en cbBTC :
​
a. Dupliquer l’onglet Uniswap.​
b. Dans le nouvel onglet, clique sur Négocier (en haut à gauche).​
c. Sélectionne USDC → cbBTC et saisis la quantité requise.​
d. Valide l’échange, puis retourne à la page de ta pool.

9.​ Une fois tes cbBTC et USDC à disposition, dépose-les sur la pool pour
commencer à générer tes premiers revenus.

10.​Retrouve tes positions et tes frais gagnés dans Pool (toujours en haut
à gauche du site).

Alors, c’était compliqué ? Tu as réussi à ouvrir ta première pool en DeFi !
Cela va t’ouvrir énormément de portes et d’opportunités, mais attention : la
DeFi comporte des risques et il y a beaucoup de choses à maîtriser donc
prend le temps.

Évaluation de Tes Risques Actuels
Faisons un point complet sur les risques que tu encours actuellement dans ta
stratégie DeFi.

1. Risque d'Emprunt sur AAVE
Le point le plus critique de ta stratégie concerne ton emprunt sur AAVE. Ce risque,
souvent sous-estimé par les débutants, nécessite une surveillance constante.
Maintiens impérativement un ratio LTV suffisamment bas pour absorber une
chute. Lorsque ta LTV augmente, tu te rapproches dangereusement du seuil de
liquidation : la moindre volatilité pourrait alors te faire perdre une partie
conséquente de tes actifs en collatéral ("Your supplies").
Pour contrôler ta LTV, tu disposes de deux leviers :
●​ Ajouter du collatéral supplémentaire
●​ Rembourser une partie de ta dette

Selon tes besoins et ta situation, tu peux utiliser l'une ou l'autre de ces stratégies,
voire les deux simultanément.

2. Risque de Pool de Liquidité
Tes actifs empruntés sont déployés sur une pool de liquidité avec un range de prix
que tu as défini. Ces actifs serviront à rembourser ton emprunt, tandis que les frais
générés constitueront le bénéfice que tu auras réalisé.
Deux points cruciaux à surveiller :
●​ Ne pas perdre tes actifs dans cette pool par manque de surveillance
●​ Sélectionner des actifs dans lesquels tu as une forte conviction

Si tu sors par le bas de ton range, tu subiras une perte latente (ton PMA sera
supérieur au bas de ton range). Dans ce scénario, tu ne pourras pas rembourser
ton emprunt intégralement et tu ne générera pas de frais supplémentaires.
Le choix des actifs constitue donc un élément fondamental de ta gestion du
risque.

3. Gestion Personnelle et Sécurité
La sécurité de tes actifs repose sur trois piliers essentiels :
Conservation des clés privées :
●​ Conserve correctement tes clés privées
●​ Ne les divulgue jamais à personne
●​ Ne les perds jamais, sous peine de perdre définitivement tes actifs

Tests préalables :
●​ Teste toujours avec de petits montants avant de déposer des sommes
importantes
●​ Les outils DeFi peuvent être complexes et les erreurs coûteuses
●​ Mieux vaut vérifier le bon fonctionnement avant d'engager des capitaux
conséquents

Formation continue :
●​ Prends le temps de comprendre parfaitement la DeFi et ses mécanismes
●​ Une mauvaise compréhension peut mener à une perte latente, voire à une
perte cristallisée définitive

En résumé : La gestion des risques en DeFi exige une vigilance constante sur trois
fronts : la surveillance de tes ratios d'emprunt, le choix judicieux de tes pools de
liquidité, et une sécurité personnelle irréprochable. Chaque négligence peut avoir
des conséquences financières importantes.

​

Bravo, tu as réussi à générer tes premiers rendements !
Tu en veux plus et tu veux aller plus loin ?
Nos offres sont conçues pour t'accompagner de A à Z. Tu auras
accès à une formation détaillée sur la DeFi, une communauté
active pour poser toutes tes questions, ainsi que des outils pour
naviguer sereinement dans cet écosystème.
Clique sur ce lien pour réserver un créneau avec un membre
de l'équipe et en discuter de vive voix.​

​
https://s.rifter.fr/rdv-defi

