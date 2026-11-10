---
statut: archivé
sujet: Spec initiale — résuméChatGpt
archivé_le: 2026-11-10
raison: Spec de départ (jan-fév 2026) antérieure à l'implémentation ; l'état courant est dans architecture/
modifié_le: 2026-11-10
empreinte: e27fd4c18a
---
# résuméChatGpt

> ⚠ **ARCHIVÉ le 2026-11-10** — spec initiale, souvent dépassée par le code. Original : `../sources/specs-initiales/résuméChatGpt.odt`. Ne jamais lire comme référence de l'état courant.

### 🎯 **Objectif de TradeIO**

Développer un **bot de trading crypto** en **Java Spring Boot**, capable de gérer des stratégies automatisées, multi-utilisateurs, et connecté à différents exchanges ou wallets.

### 🧠 **Stratégies principales intégrées**

#### 1\. **Gridbot avec Trailing Stop**

  - Achats déclenchés quand le prix passe sous un seuil

  - Ventes déclenchées au-dessus d’un seuil

  - Trailing stop appliqué à l'achat et à la vente
    
      - Delta configurable (ex. 0,5 %)
      - Peut évoluer avec le temps ou la volatilité

#### 2\. **DCA intelligent**

  - Achat périodique (ex. 25 €/semaine)
  - Ajustement en fonction d’indicateurs (RSI, Rainbow Chart…)
  - Augmentation de l’achat en cas de sous-cotation
  - Diminution (voire vente) en cas de surcotation

### ⚙️ **Autres fonctionnalités prévues**

  - Rééquilibrage automatique du portefeuille selon une pondération cible

  - Mode “Crash Recovery” :
    
      - Détection de chute brutale (ex. -15%/24h)
      - Actions spécifiques (renforcement ou pause)

  - Journalisation intelligente (JSON, CSV, Email)

  - Rapports réguliers sur les investissements

  - Alerte en cas de variation brutale de prix

  - Transfert d’un % des profits vers une adresse externe (Ledger)

### 👥 **Multi-utilisateurs**

  - Chaque utilisateur a son propre portefeuille
  - Bot potentiellement monétisé via % des transactions
  - Gestion des profils via base MySQL
  - Gestion des erreurs si aucun profil actif défini

### 🔗 **Intégration**

  - Exchanges : Binance, Kraken, Crypto.com
  - Wallets : Metamask, Ledger
  - Utilisation de Lombok, Thymeleaf, Bootstrap, jQuery
  - *application.properties* (pas YAML)

### ✅ **Ce qui est déjà en place** :

  - ✅ Application **Java Spring Boot** avec **Thymeleaf**, Bootstrap, jQuery.
  - ✅ Fichier *application.properties* (et non YAML), gestion des profils (*dev*, *prod*, etc.).
  - ✅ Base de données **MySQL** fonctionnelle.
  - ✅ Gestion des utilisateurs (*User*), avec multi-wallet (*Wallet*), sources (*WalletSource*), et un système multi-utilisateur prévu.
  - ✅ L’application s’arrête proprement si aucun profil n’est défini.
  - ✅ Lombok utilisé.

### 🧠 **Modules/fonctionnalités déjà définis (mais pas forcément codés)** :

#### 🎯 1. **Gridbot avec Trailing Stop**

  - Achats/ventes selon une grille.
  - Déclenchement basé sur un **trailing stop intelligent**.
  - Espacement des lignes calculé dynamiquement au lancement.
  - Mise en pause si le prix sort de la grille.

#### 📉 2. **DCA intelligent**

  - Achat périodique (ex. 25 €/semaine).
  - Montant modulé selon indicateurs comme **RSI** ou **Rainbow Chart**.
  - Capacité à diminuer ou suspendre les achats si l’actif est surcoté.

#### 🛑 3. **Trailing Stop autonome**

  - Delta ajustable selon la volatilité.
  - Plus strict en cas de stagnation.
  - Déclenche un ordre marché à -0,5 % de retournement.

#### ⚖️ 4. **Rééquilibrage automatique du portefeuille**

  - Ex : 40%-30%-30%.
  - Ajustement progressif par rapport à une pondération cible.

#### 📉 5. **Mode Crash Recovery**

  - Détection de chute brutale (ex. -15% / 24h).
  - Réaction : soit achat agressif, soit suspension des actions.

#### 📊 6. **Journalisation & rapports**

  - Historique des actions.
  - Suivi des investissements.
  - Exports JSON/CSV hebdo.

#### 🔔 7. **Système d’alertes**

  - Hausse ou baisse brutale de prix.

#### 🔁 8. **Exécution en parallèle**

  - Tous les modules fonctionnent indépendamment (DCA, Grid, Alertes...).

#### 🔄 9. **Transfert d'une partie des profits**

  - Vers un wallet externe (ex. Ledger) selon un pourcentage.
