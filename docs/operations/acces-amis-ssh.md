---
statut: courant
sujet: accès des amis via tunnel SSH, prérequis VPS et test réel
modifié_le: 2026-11-11
empreinte: 9090d7a07f
---
# Accès des amis : prérequis VPS et test réel

Décisions : [`../decisions/acces-amis-tunnel-ssh.md`](../decisions/acces-amis-tunnel-ssh.md). Endpoints : [`../api/rest-endpoints.md`](../api/rest-endpoints.md).

## Prérequis (VPS, root)

1. Infra déjà en place : sshd (bloc `Match Group tradeio-tunnel`), helper `/usr/local/sbin/tradeio-user` (hors périmètre de l'app).
2. Règle sudoers limitée à ce script (validée par `visudo -cf`) :
   `oklm ALL=(root) NOPASSWD: /usr/local/sbin/tradeio-user` dans `/etc/sudoers.d/tradeio-user` (0440).
   Contrôle : `sudo -u oklm sudo -n /usr/local/sbin/tradeio-user list` ne demande pas de mot de passe.
3. Scripts client déposés dans `tradeio.ssh.client-dir` (`tradeio-tunnel.bat/.ps1/.sh`, `README.md`) ; mise à jour = nouveau dépôt de fichiers, sans redéployer le jar.
4. Propriétés `tradeio.ssh.*` décommentées dans `application-prod.properties` (cf. [`configuration.md`](configuration.md)), puis redémarrage. `spring.jpa.hibernate.ddl-auto=validate`.
5. Migration `V2__user_ssh_access.sql` : recette puis prod ([`flyway.md`](flyway.md), [`recette.md`](recette.md)).

Risque assumé : une compromission de l'app permet de créer des comptes tunnel (impact limité : forward vers 9003 uniquement).

## Test réel

1. Onglet Utilisateurs de `/admin` : créer un utilisateur de test avec « Donner l'accès VPS » ; noter le mot de passe affiché.
2. Télécharger le « Package client », le lancer côté ami : le script affiche la clé publique.
3. « Coller la clé » : statut `ACTIVE` ; `tradeio-user list` sur le VPS montre `tio_<login>  never`.
4. Relancer le script : tunnel + `http://127.0.0.1:18080/` ; connexion avec le mot de passe généré.
5. « Désactiver » : le tunnel est refusé, `list` montre une date d'expiration passée ; « Activer » : rétabli.
6. « Réconcilier avec le VPS » : aucun écart. « Supprimer » : `list` ne contient plus le compte.
7. Panne simulée (retirer la règle sudoers) : un `Désactiver` donne le statut `ERROR` + message ; après correction, « Réessayer ».

Changer le port de TradeIO impose de mettre à jour `PermitOpen` (sshd), `TARGET` dans `tradeio-user` et `tradeio.ssh.remote`.
