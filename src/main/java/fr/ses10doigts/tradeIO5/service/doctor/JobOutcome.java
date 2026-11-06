package fr.ses10doigts.tradeIO5.service.doctor;

/** Résultat d'un job pour le journal : nombre d'éléments en erreur isolée + résumé lisible. */
public record JobOutcome(int errors, String summary) {
}
