package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.entity.media.ContentSourceEntity;
import fr.ses10doigts.tradeIO5.model.entity.media.VideoContentEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.media.ContentPlatform;
import fr.ses10doigts.tradeIO5.model.enumerate.media.VideoContentStatus;
import fr.ses10doigts.tradeIO5.repository.ContentSourceRepository;
import fr.ses10doigts.tradeIO5.repository.VideoContentRepository;
import fr.ses10doigts.tradeIO5.service.tree.media.MediaCredentialResolver;
import fr.ses10doigts.tradeIO5.service.tree.media.youtube.TranscriptSegment;
import fr.ses10doigts.tradeIO5.service.tree.media.youtube.YoutubeRssClient;
import fr.ses10doigts.tradeIO5.service.tree.media.youtube.YoutubeTranscriptClient;
import fr.ses10doigts.tradeIO5.service.tree.media.youtube.YoutubeVideoRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Media - MediaWatchIngestionJob")
class MediaWatchIngestionJobTest {

    private ContentSourceRepository contentSourceRepository;
    private VideoContentRepository videoContentRepository;
    private YoutubeRssClient rssClient;
    private YoutubeTranscriptClient transcriptClient;
    private MediaCredentialResolver credentialResolver;
    private MediaWatchIngestionJob job;

    private final ApiCredentialDTO credential = new ApiCredentialDTO(WebProviderCode.YOUTUBE, "", "", "https://www.youtube.com");

    @BeforeEach
    void setUp() {
        contentSourceRepository = mock(ContentSourceRepository.class);
        videoContentRepository = mock(VideoContentRepository.class);
        rssClient = mock(YoutubeRssClient.class);
        transcriptClient = mock(YoutubeTranscriptClient.class);
        credentialResolver = mock(MediaCredentialResolver.class);

        job = new MediaWatchIngestionJob(contentSourceRepository, videoContentRepository, rssClient, transcriptClient, credentialResolver);

        when(credentialResolver.resolve(WebProviderCode.YOUTUBE)).thenReturn(credential);
    }

    @Test
    @DisplayName("Idempotence : une vidéo déjà connue n'est jamais retraitée (pas de save, pas de fetch transcript)")
    void pollActiveSources_isIdempotent_forAlreadyKnownVideo() {
        ContentSourceEntity source = source("Cryptolyze", "UCuXgThwkFpefb41aKWKqrOw");
        YoutubeVideoRef ref = new YoutubeVideoRef("ABCDEF12345", "Analyse marché", Instant.now());

        when(contentSourceRepository.findByActiveTrue()).thenReturn(List.of(source));
        when(rssClient.fetchLatestVideos(credential, source.getChannelId())).thenReturn(List.of(ref));
        when(videoContentRepository.existsBySourceAndVideoId(source, ref.videoId())).thenReturn(true);

        job.pollActiveSources();

        verify(videoContentRepository, never()).save(any());
        verify(transcriptClient, never()).fetchTranscript(any(), anyString());
    }

    @Test
    @DisplayName("Une nouvelle vidéo est ingérée avec son transcript sérialisé, statut PENDING")
    void pollActiveSources_ingestsNewVideo_withPendingStatus() {
        ContentSourceEntity source = source("Cryptolyze", "UCuXgThwkFpefb41aKWKqrOw");
        YoutubeVideoRef ref = new YoutubeVideoRef("ABCDEF12345", "Analyse marché", Instant.now());

        when(contentSourceRepository.findByActiveTrue()).thenReturn(List.of(source));
        when(rssClient.fetchLatestVideos(credential, source.getChannelId())).thenReturn(List.of(ref));
        when(videoContentRepository.existsBySourceAndVideoId(source, ref.videoId())).thenReturn(false);
        when(transcriptClient.fetchTranscript(credential, ref.videoId()))
                .thenReturn(Optional.of(List.of(new TranscriptSegment("Bonjour à tous", 0.0, 2.5))));

        job.pollActiveSources();

        ArgumentCaptor<VideoContentEntity> captor = ArgumentCaptor.forClass(VideoContentEntity.class);
        verify(videoContentRepository, times(1)).save(captor.capture());

        VideoContentEntity saved = captor.getValue();
        assertEquals(VideoContentStatus.PENDING, saved.getStatus());
        assertEquals("ABCDEF12345", saved.getVideoId());
        assertTrue(saved.getTranscript() != null && saved.getTranscript().contains("Bonjour"));
    }

    @Test
    @DisplayName("Vidéo sans transcript disponible -> statut ERROR avec errorReason explicite")
    void pollActiveSources_marksError_whenNoTranscript() {
        ContentSourceEntity source = source("Cryptolyze", "UCuXgThwkFpefb41aKWKqrOw");
        YoutubeVideoRef ref = new YoutubeVideoRef("NOCAPTIONS", "Vidéo sans sous-titres", Instant.now());

        when(contentSourceRepository.findByActiveTrue()).thenReturn(List.of(source));
        when(rssClient.fetchLatestVideos(credential, source.getChannelId())).thenReturn(List.of(ref));
        when(videoContentRepository.existsBySourceAndVideoId(source, ref.videoId())).thenReturn(false);
        when(transcriptClient.fetchTranscript(credential, ref.videoId())).thenReturn(Optional.empty());

        job.pollActiveSources();

        ArgumentCaptor<VideoContentEntity> captor = ArgumentCaptor.forClass(VideoContentEntity.class);
        verify(videoContentRepository, times(1)).save(captor.capture());

        VideoContentEntity saved = captor.getValue();
        assertEquals(VideoContentStatus.ERROR, saved.getStatus());
        assertEquals("no_transcript_available", saved.getErrorReason());
    }

    @Test
    @DisplayName("Isolation d'erreur : une source qui lève une exception n'empêche pas le traitement des autres")
    void pollActiveSources_isolatesErrors_betweenSources() {
        ContentSourceEntity sourceA = source("SourceEnErreur", "CHANNEL_A");
        ContentSourceEntity sourceB = source("SourceOK", "CHANNEL_B");
        YoutubeVideoRef refB = new YoutubeVideoRef("VIDEO_B", "Titre B", Instant.now());

        when(contentSourceRepository.findByActiveTrue()).thenReturn(List.of(sourceA, sourceB));
        when(rssClient.fetchLatestVideos(credential, "CHANNEL_A")).thenThrow(new RuntimeException("boom"));
        when(rssClient.fetchLatestVideos(credential, "CHANNEL_B")).thenReturn(List.of(refB));
        when(videoContentRepository.existsBySourceAndVideoId(sourceB, refB.videoId())).thenReturn(false);
        when(transcriptClient.fetchTranscript(credential, refB.videoId()))
                .thenReturn(Optional.of(List.of(new TranscriptSegment("Texte B", 0.0, 1.0))));

        assertDoesNotThrow(() -> job.pollActiveSources());

        verify(videoContentRepository, times(1)).save(any());
    }

    private ContentSourceEntity source(String displayName, String channelId) {
        return ContentSourceEntity.builder()
                .id(1L)
                .platform(ContentPlatform.YOUTUBE)
                .channelId(channelId)
                .displayName(displayName)
                .credibilityWeight(1.0)
                .active(true)
                .build();
    }
}
