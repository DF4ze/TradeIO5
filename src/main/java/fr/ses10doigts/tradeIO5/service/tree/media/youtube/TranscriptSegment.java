package fr.ses10doigts.tradeIO5.service.tree.media.youtube;

/**
 * Un segment de transcript YouTube (timedtext), tel que retourné par
 * {@link YoutubeTranscriptClient#fetchTranscript}.
 */
public record TranscriptSegment(String text, double startSeconds, double durationSeconds) {
}
