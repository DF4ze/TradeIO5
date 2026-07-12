package fr.ses10doigts.tradeIO5.service.tree.media.youtube;

import java.time.Instant;

/**
 * Référence légère à une vidéo telle qu'exposée par le flux RSS d'une chaîne YouTube
 * ({@code GET /feeds/videos.xml?channel_id=...}), avant tout enrichissement (transcript, etc.).
 */
public record YoutubeVideoRef(String videoId, String title, Instant publishedAt) {
}
