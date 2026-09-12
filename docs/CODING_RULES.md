# Coding rules

Règles de développement pour ce projet, à respecter par tout contributeur (humain ou agent).

## Principes généraux

- **Ne pas coder ce dont on n'a pas besoin.** Pas de généralisation anticipée (config DB, multi-actif,
  abstraction) tant que le besoin concret n'est pas là. Exemple : pas de table de config pour les
  paramètres d'indicateur avant d'avoir plus d'un actif à calibrer.
- **Tester le plus simple au début pour confirmer les bases.** Valider la logique/les hypothèses sur
  un périmètre réduit (un seul actif, un prototype léger) avant d'investir dans l'intégration complète.

<!-- Ajouter ici les futures règles, au fil des décisions prises avec Clem. -->
