package fr.ses10doigts.tradeIO5.service.optimizer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Annulation et progression d'une recherche (partagées entre le thread de calcul et le service qui l'observe). */
public final class SearchControl {

    /** Reçoit la progression {@code 0..1} et l'étape courante. */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(double fraction, String stage);
    }

    /** Levée quand la recherche est annulée. */
    public static final class CancelledException extends RuntimeException {
        public CancelledException() {
            super("Recherche annulée");
        }
    }

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicLong evaluations = new AtomicLong();
    private final ProgressListener listener;
    private final SearchControl parent;

    public SearchControl(ProgressListener listener) {
        this(listener, null);
    }

    private SearchControl(ProgressListener listener, SearchControl parent) {
        this.listener = listener == null ? (f, s) -> {
        } : listener;
        this.parent = parent;
    }

    /** Contrôle d'une sous-recherche : son propre compteur d'essais et sa progression, annulé dès que le parent l'est. */
    public SearchControl child(ProgressListener childListener) {
        return new SearchControl(childListener, this);
    }

    public static SearchControl none() {
        return new SearchControl(null);
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get() || parent != null && parent.isCancelled();
    }

    void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancelledException();
        }
    }

    long countEvaluation() {
        return evaluations.incrementAndGet();
    }

    long evaluations() {
        return evaluations.get();
    }

    void progress(double fraction, String stage) {
        listener.onProgress(Math.clamp(fraction, 0.0, 1.0), stage);
    }
}
