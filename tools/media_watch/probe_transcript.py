#!/usr/bin/env python3
"""
Récupère le transcript brut d'une vidéo YouTube (via youtube-transcript-api) et/ou la liste des
dernières vidéos d'une chaîne (via son flux RSS), pour inspecter le format réel retourné avant de
câbler l'extraction en Java (docs/etude-veille-media-youtube.md, §6 "validation technique de
l'extraction transcript FR").

Complément à docs/etude-veille-media-youtube.md : le premier essai depuis l'environnement de build
a échoué (accès réseau sortant vers youtube.com bloqué depuis ce sandbox — même souci que
tools/calibration/fetch_real_klines.py pour l'API Binance). Ce script est prévu pour être exécuté
depuis une machine avec un accès réseau normal.

Usage:
    pip install youtube-transcript-api feedparser --break-system-packages

    # Lister les dernières vidéos d'une chaîne (pour récupérer un video-id à tester)
    python probe_transcript.py --channel-id UCuXgThwkFpefb41aKWKqrOw --latest 5

    # Tester le transcript d'une vidéo précise, écrire le détail complet en JSON
    python probe_transcript.py --video-id XXXXXXXXXXX --out transcript_sample.json
"""

import argparse
import json
import sys


def fetch_latest_videos(channel_id, limit):
    import feedparser

    url = f"https://www.youtube.com/feeds/videos.xml?channel_id={channel_id}"
    feed = feedparser.parse(url)
    if feed.bozo and not feed.entries:
        print(f"[ERREUR] Impossible de lire le flux RSS ({url}) : {feed.bozo_exception}", file=sys.stderr)
        return []
    videos = []
    for entry in feed.entries[:limit]:
        videos.append({
            "video_id": entry.yt_videoid,
            "title": entry.title,
            "published": entry.published,
            "url": entry.link,
        })
    return videos


def fetch_transcript(video_id, languages=("fr", "en")):
    # API >= 1.0 (v1.2.4 installée) : YouTubeTranscriptApi.get_transcript (classmethod) a été
    # remplacé par une API par instance : YouTubeTranscriptApi().fetch(...) -> FetchedTranscript
    # (objet itérable), .to_raw_data() pour retrouver le format liste de dicts
    # {'text', 'start', 'duration'} qu'utilisait l'ancienne API et qu'utilise summarize() ci-dessous.
    from youtube_transcript_api import YouTubeTranscriptApi
    from youtube_transcript_api._errors import TranscriptsDisabled, NoTranscriptFound

    try:
        fetched = YouTubeTranscriptApi().fetch(video_id, languages=list(languages))
        return fetched.to_raw_data()
    except (TranscriptsDisabled, NoTranscriptFound) as e:
        print(f"[ERREUR] Pas de transcript disponible pour {video_id} en {languages} : {e}", file=sys.stderr)
        return None
    except Exception as e:
        print(f"[ERREUR] Echec recuperation transcript {video_id} : {type(e).__name__}: {e}", file=sys.stderr)
        return None


def summarize(transcript, excerpt_seconds=180):
    full_text = " ".join(seg["text"] for seg in transcript)
    last = transcript[-1]

    # Extrait basé sur le temps couvert (pas un nombre de caractères fixe) : on veut simuler
    # exactement ce que la passe 1 (classification, docs/etude-veille-media-youtube.md §6) enverrait
    # au LLM le moins cher pour juger du thème global, donc "les X premières minutes réellement
    # parlées" est plus représentatif qu'un simple full_text[:N] qui peut couper en plein milieu
    # d'un mot ou, à l'inverse, être trop court si les segments sont denses.
    excerpt_segments = [seg for seg in transcript if seg["start"] < excerpt_seconds]
    excerpt_text = " ".join(seg["text"] for seg in excerpt_segments)

    return {
        "nb_segments": len(transcript),
        "duree_couverte_s": round(last["start"] + last["duration"], 1),
        "longueur_texte_caracteres": len(full_text),
        "premiers_segments_bruts": transcript[:5],
        "extrait_texte_debut_500c": full_text[:500],
        "extrait_texte_debut_timebased": {
            "fenetre_s": excerpt_seconds,
            "nb_segments_dans_fenetre": len(excerpt_segments),
            "longueur_caracteres": len(excerpt_text),
            "texte": excerpt_text,
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--video-id", help="ID YouTube d'une vidéo précise à tester")
    parser.add_argument("--channel-id", help="Channel ID pour lister les dernières vidéos avant de choisir")
    parser.add_argument("--latest", type=int, default=5, help="Nombre de vidéos à lister depuis --channel-id")
    parser.add_argument("--languages", default="fr,en", help="Langues à tenter, dans l'ordre (défaut: fr,en)")
    parser.add_argument("--excerpt-seconds", type=int, default=120,
                         help="Fenêtre (en secondes de transcript) utilisée pour l'extrait 'début de "
                              "vidéo'. Défaut 120s (2 min) : valeur validée empiriquement (docs/"
                              "etude-veille-media-youtube.md §6) comme suffisante pour juger du sujet "
                              "global d'une vidéo Cryptolyze")
    parser.add_argument("--out", help="Fichier JSON de sortie (transcript complet + résumé)")
    args = parser.parse_args()

    languages = tuple(l.strip() for l in args.languages.split(","))

    if args.channel_id and not args.video_id:
        videos = fetch_latest_videos(args.channel_id, args.latest)
        if not videos:
            sys.exit(1)
        print(f"Dernières vidéos ({len(videos)}) pour {args.channel_id} :")
        for v in videos:
            print(f"  - {v['video_id']}  {v['published']}  {v['title']}")
        print("\nRelancer avec --video-id <id> pour tester le transcript d'une vidéo précise.")
        return

    if not args.video_id:
        parser.error("--video-id ou --channel-id requis")

    transcript = fetch_transcript(args.video_id, languages)
    if transcript is None:
        sys.exit(1)

    summary = summarize(transcript, excerpt_seconds=args.excerpt_seconds)
    print(json.dumps(summary, ensure_ascii=False, indent=2))

    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(
                {"video_id": args.video_id, "transcript": transcript, "summary": summary},
                f, ensure_ascii=False, indent=2,
            )
        print(f"\nTranscript complet écrit dans {args.out}")


if __name__ == "__main__":
    main()
