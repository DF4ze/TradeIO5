---
statut: decision
sujet: accès des amis via tunnel SSH, création de comptes par l'admin
cree_le: 2026-11-11
modifié_le: 2026-11-11
empreinte: 5bc2654d17
---
# Accès des amis : tunnel SSH et comptes créés par l'admin

> Décisions de Clem (tranchées). Ne pas re-questionner sans demande explicite. Le « comment c'est fait » est dans [`../architecture/07-security.md`](../architecture/07-security.md), [`../api/rest-endpoints.md`](../api/rest-endpoints.md) et [`../operations/acces-amis-ssh.md`](../operations/acces-amis-ssh.md).

- **Principe** : TradeIO n'est pas exposé sur Internet ; chaque ami ouvre un tunnel SSH vers `127.0.0.1:9003` du VPS. Un compte TradeIO + un compte Linux `tio_<login>` sans aucun droit. Désactiver/supprimer le compte TradeIO coupe aussi l'accès VPS. L'infra (sshd, helper `tradeio-user`) est hors périmètre de l'app.
- **Rôle des amis** : `ROLE_USER` uniquement (pas de rôle « lecture seule » pour l'instant).
- **Mot de passe** : généré par le serveur, affiché une seule fois à l'admin.
- **E-mail** : placeholder `<login>@tradeio.local` (TODO gestion des utilisateurs : e-mail réel).
- **Login VPS** : identique au `username` TradeIO (pas de colonne séparée).
- **Clé publique** : l'ami l'envoie à l'admin, qui la colle dans l'onglet Utilisateurs (V1). Invitation automatique : plus tard.
- **Package client** : le serveur fournit un zip (scripts + `tradeio.conf` prérempli pour le compte).
- **Suppression** : désactivation par défaut, suppression explicite (VPS d'abord, puis app).
- **Archivage pour inactivité** : sans effet sur l'accès VPS.
- **Inscription publique** : fermée par défaut (`tradeio.signup.enabled`) ; liens d'inscription masqués.
