package fr.ses10doigts.tradeIO5.service.optimizer;

/** Traduit un {@link Candidate} (nom → valeur) en politique / plafond / montant de base : seul endroit qui connaît les noms. */
@FunctionalInterface
public interface CandidateAdapter {

    ResolvedCandidate resolve(Candidate candidate);
}
