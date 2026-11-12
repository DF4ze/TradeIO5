---
statut: gap
vérifié_le: 2026-11-08
modifié_le: 2026-11-11
empreinte: 27ceea1f1a
---
# Front — écarts restants

Vérifié le : 2026-11-08.

| Écart | État |
|---|---|
| Écran **Données** (`asset_provider`, `candleSource`) | bloqué : aucun endpoint ; `AssetInitializer` écrase les valeurs au démarrage (à résoudre avant) |
| Page **Santé** (état des sources, jobs) | aucun back |
| Admin en pages séparées | non fait : `/admin` garde ses onglets, habillés charte |
| Dépôts / retraits (crypto) | non suivis : une vente supérieure aux achats connus est plafonnée (`incomplete`) ; à gérer via un registre de transferts (dépôt = coût d'entrée à renseigner ou prix du jour, retrait = sortie au PRU sans gain, transfert entre wallets du même utilisateur = PRU reporté). Le PnL par actif n'est pas affecté par les dépôts/retraits de stables ; la courbe de patrimoine l'est (« hors dépôts/retraits ») |

## TODO (idées validées, non faites)

| Sujet | Détail |
|---|---|
| **Gestion des utilisateurs (admin)** | e-mail réel à la place du placeholder `<login>@tradeio.local` ; réinitialisation du mot de passe ; invitation automatique (envoi de la clé par l'ami) à la place du collage manuel ; rôle « lecture seule » |
| **Tutoriels de création de clé API** | un par exchange (OKX, Kraken) : permissions à cocher, retraits désactivés, restriction d'IP (adresse du serveur) |
| Réglages utilisateur : canal d'alerte | in-app aujourd'hui ; e-mail / Telegram à ajouter |
| Réglages utilisateur : digest | résumé périodique (quotidien/hebdo) |
| Réglages utilisateur : sécurité | mot de passe, 2FA, sessions actives |
| Réglages utilisateur : export | CSV des ordres et du PnL |
| Réglages utilisateur : suppression du compte | + export RGPD |
| Autres idées | pause par lien, fréquence de passe, stablecoin de passerelle préféré, devise d'affichage, fuseau horaire, mode discret, « revenir aux valeurs du profil » |
