import os
import re
import json
import time
import hashlib
import threading
from io import BytesIO
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FuturesTimeoutError
from collections import deque
from datetime import datetime
from urllib.parse import quote, urlparse, parse_qs

from flask import Flask, jsonify, request, send_file, render_template
from PIL import Image, ImageOps

try:
    import yt_dlp
except Exception:
    yt_dlp = None

try:
    import syncedlyrics
except Exception:
    syncedlyrics = None

try:
    from ytmusicapi import YTMusic
except Exception:
    YTMusic = None

APP_NAME = "NeoBelieve"
BASE_DIR = os.path.abspath(os.path.dirname(__file__))
DATA_DIR = os.path.join(BASE_DIR, "data")
CACHE_DIR = os.path.join(DATA_DIR, "cache")
CACHE_MUSIC_DIR = os.path.join(CACHE_DIR, "music")
CACHE_LYRICS_DIR = os.path.join(CACHE_DIR, "lyrics")
MUSIC_DIR = os.path.join(DATA_DIR, "music")
COVERS_DIR = os.path.join(DATA_DIR, "covers")
DB_DIR = os.path.join(DATA_DIR, "db")

PLAYLIST_JSON = os.path.join(DB_DIR, "playlists.json")
HISTORY_JSON = os.path.join(DB_DIR, "history.json")
CACHE_JSON = os.path.join(DB_DIR, "cache.json")
DOWNLOADS_JSON = os.path.join(DB_DIR, "downloads.json")
SETTINGS_JSON = os.path.join(DB_DIR, "settings.json")
DEVICES_JSON = os.path.join(DB_DIR, "devices.json")

CACHE_TTL_SECONDS = 3 * 24 * 60 * 60
YTDLP_SEARCH_TIMEOUT = 60
BAD_THUMB_URL = "https://i.ytimg.com/vi/UCgQna2EqpzqzfBjlSmzT72w/hqdefault.jpg"
BAD_THUMB_HASH = None
BAD_THUMB_MAX_DIST = 6
THUMB_HASH_SIZE = 8
thumb_hash_cache = {}
thumb_hash_lock = threading.Lock()
search_metadata_cache = {}
search_metadata_lock = threading.Lock()
ytmusic_client = None
ytmusic_client_lock = threading.Lock()

os.makedirs(DB_DIR, exist_ok=True)

app = Flask(__name__)

playback_lock = threading.Lock()
volume_lock = threading.Lock()
download_lock = threading.Lock()

current_playback = {
    "id": None,
    "currentTime": 0,
    "duration": 0,
    "status": "stopped",
    "timestamp": 0,
}

current_volume = {"volume": 80}

remote_lock = threading.Lock()
remote_queue = deque()


class _YTDLPLogger:
    def __init__(self):
        self.errors = []

    def debug(self, msg):
        return None

    def warning(self, msg):
        return None

    def error(self, msg):
        if msg:
            self.errors.append(str(msg))


def _load_json(path, default):
    if not os.path.exists(path):
        return default
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def _save_json(path, data):
    tmp_path = f"{path}.tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    os.replace(tmp_path, path)


def _safe_title(value):
    value = value or "track"
    value = re.sub(r"[^a-zA-Z0-9\-_. ]+", "", value).strip()
    return value or "track"


def _hash_url(url):
    return hashlib.sha256(url.encode("utf-8")).hexdigest()[:12]


def _yt_video_id(url):
    if not url:
        return None
    match = re.search(r"[?&]v=([^&]+)", url)
    if match:
        return match.group(1)
    match = re.search(r"youtu\.be/([^?&/]+)", url)
    if match:
        return match.group(1)
    match = re.search(r"youtube\.com/shorts/([^?&/]+)", url)
    if match:
        return match.group(1)
    match = re.search(r"youtube\.com/embed/([^?&/]+)", url)
    if match:
        return match.group(1)
    return None


def _yt_cover_url(video_id):
    if not video_id:
        return None
    return f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg"


def _get_online_mode():
    settings = _load_json(SETTINGS_JSON, {})
    if os.getenv("OFFLINE") == "1":
        return False
    return settings.get("online", True)


def _set_online_mode(value):
    settings = _load_json(SETTINGS_JSON, {})
    settings["online"] = bool(value)
    _save_json(SETTINGS_JSON, settings)


def _load_devices():
    return _load_json(DEVICES_JSON, [])


def _save_devices(devices):
    _save_json(DEVICES_JSON, devices)


def _cache_key(url, title):
    return f"{_safe_title(title)}-{_hash_url(url)}"


def _cache_path(key):
    return os.path.join(CACHE_MUSIC_DIR, f"{key}.mp3")


def _download_path(title):
    return os.path.join(MUSIC_DIR, f"{_safe_title(title)}.mp3")


def _cover_path(key):
    return os.path.join(COVERS_DIR, f"{key}.jpg")


def _touch_cache_entry(key, entry):
    cache = _load_json(CACHE_JSON, {})
    cache[key] = entry
    _save_json(CACHE_JSON, cache)


def _cleanup_cache():
    cache = _load_json(CACHE_JSON, {})
    now = time.time()
    changed = False
    for key, entry in list(cache.items()):
        last_played = entry.get("last_played", 0)
        downloaded = entry.get("downloaded", False)
        if downloaded:
            continue
        if now - last_played > CACHE_TTL_SECONDS:
            path = _cache_path(key)
            cover = entry.get("cover_path")
            if os.path.exists(path):
                try:
                    os.remove(path)
                except Exception:
                    pass
            if cover and os.path.exists(cover):
                try:
                    os.remove(cover)
                except Exception:
                    pass
            cache.pop(key, None)
            changed = True
    if changed:
        _save_json(CACHE_JSON, cache)


def _save_cover_from_url(url, key):
    if not url:
        return None
    try:
        import requests
        resp = requests.get(url, timeout=10)
        if resp.status_code == 200:
            path = _cover_path(key)
            with open(path, "wb") as f:
                f.write(resp.content)
            return path
    except Exception:
        return None
    return None


def _ahash_from_bytes(content):
    img = Image.open(BytesIO(content))
    img = ImageOps.exif_transpose(img)
    img = img.convert("L").resize((THUMB_HASH_SIZE, THUMB_HASH_SIZE))
    pixels = list(img.getdata())
    avg = sum(pixels) / len(pixels)
    bits = 0
    for i, p in enumerate(pixels):
        if p >= avg:
            bits |= 1 << i
    return bits


def _hamming(a, b):
    return (a ^ b).bit_count()


def _get_thumb_hash(url):
    if not url:
        return None
    with thumb_hash_lock:
        if url in thumb_hash_cache:
            return thumb_hash_cache[url]
    try:
        import requests
        resp = requests.get(url, timeout=5)
        if resp.status_code != 200:
            return None
        h = _ahash_from_bytes(resp.content)
    except Exception:
        return None
    with thumb_hash_lock:
        thumb_hash_cache[url] = h
    return h


def _is_bad_thumb(url):
    global BAD_THUMB_HASH
    if not url:
        return False
    if url == BAD_THUMB_URL:
        return True
    if BAD_THUMB_HASH is None:
        BAD_THUMB_HASH = _get_thumb_hash(BAD_THUMB_URL)
        if BAD_THUMB_HASH is None:
            return False
    h = _get_thumb_hash(url)
    if h is None:
        return False
    return _hamming(h, BAD_THUMB_HASH) <= BAD_THUMB_MAX_DIST


def _yt_dlp_info(url, download=False, outtmpl=None):
    if yt_dlp is None:
        return None, "yt-dlp not installed"
    logger = _YTDLPLogger()
    ydl_opts = {
        "quiet": True,
        "no_warnings": True,
        "extract_flat": False,
        "noplaylist": True,
        "logger": logger,
    }
    if download:
        ydl_opts.update(
            {
                "format": "bestaudio/best",
                "outtmpl": outtmpl,
                "postprocessors": [
                    {
                        "key": "FFmpegExtractAudio",
                        "preferredcodec": "mp3",
                        "preferredquality": "192",
                    }
                ],
            }
        )
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=download)
        return info, None
    except Exception as e:
        details = str(e)
        if logger.errors:
            details = " | ".join(logger.errors + [details])
        return None, _friendly_ytdlp_error(details)


def _friendly_ytdlp_error(error):
    if not error:
        return "yt-dlp error"
    raw = str(error).strip()
    raw = re.sub(r"\s+", " ", raw)
    raw = re.sub(r"^ERROR:\s*", "", raw, flags=re.IGNORECASE)
    lower = raw.lower()

    if "this video is restricted" in lower or "google workspace administrator" in lower or "workspace administrator" in lower or "network administrator restrictions" in lower:
        return "Video restreinte par Google Workspace ou le réseau (administrateur). Essaie un autre compte/réseau."
    if "private video" in lower:
        return "Vidéo privée."
    if "this video is unavailable" in lower or "video unavailable" in lower:
        return "Vidéo indisponible."
    if "copyright" in lower and "blocked" in lower:
        return "Vidéo bloquée pour droits d'auteur."
    if "sign in to confirm your age" in lower:
        return "Vidéo avec limite d'âge (connexion requise)."
    if "not available in your country" in lower:
        return "Vidéo non disponible dans ce pays."

    return raw


def _parse_types_filter(types_filter, default_types=None):
    all_types = {"artist", "playlist", "track"}
    if not types_filter:
        return set(default_types or all_types)

    normalized = (
        types_filter.lower()
        .replace("+", " ")
        .replace(",", " ")
        .replace("/", " ")
        .replace("&", " ")
    )
    tokens = normalized.split()
    allowed = set()
    aliases = {
        "artist": "artist",
        "artists": "artist",
        "artiste": "artist",
        "artistes": "artist",
        "playlist": "playlist",
        "playlists": "playlist",
        "liste": "playlist",
        "listes": "playlist",
        "track": "track",
        "tracks": "track",
        "song": "track",
        "songs": "track",
        "titre": "track",
        "titres": "track",
        "morceau": "track",
        "morceaux": "track",
        "et": None,
        "and": None,
        "tout": "all",
        "tous": "all",
        "all": "all",
        "*": "all",
    }
    for token in tokens:
        mapped = aliases.get(token)
        if mapped == "all":
            return all_types
        if mapped in all_types:
            allowed.add(mapped)
    return allowed or set(default_types or all_types)


def _normalize_music_url(url):
    if not url:
        return None
    if url.startswith("http"):
        return url
    if url.startswith("/watch"):
        return f"https://music.youtube.com{url}"
    if url.startswith("/browse"):
        return f"https://music.youtube.com{url}"
    if re.fullmatch(r"[A-Za-z0-9_-]{11}", url):
        return f"https://music.youtube.com/watch?v={url}"
    return url


def _classify_music_url(url):
    if not url:
        return None
    normalized = _normalize_music_url(url)
    parsed = urlparse(normalized)
    path_parts = [part for part in parsed.path.split("/") if part]
    if len(path_parts) >= 2 and path_parts[0] == "browse":
        browse_id = path_parts[1]
        if len(browse_id) == 24:
            return "artist"
        if len(browse_id) == 17:
            return "playlist"
        if browse_id.startswith("VL") or browse_id.startswith("MPSP") or browse_id.startswith("MPRE"):
            return "playlist"
        return "browse_other"
    if parsed.path == "/watch":
        return "track"
    if parsed.path.startswith("/channel/") or parsed.path.startswith("/@"):
        return "artist"
    if parsed.path == "/playlist":
        return "playlist"
    return "other"


def _classify_entry(entry, url):
    entry_type = _classify_music_url(url)
    if entry_type in {"artist", "playlist", "track"}:
        return entry_type

    raw_type = (entry.get("_type") or "").lower()
    entry_id = entry.get("id") or ""
    if raw_type == "channel":
        return "artist"
    if raw_type == "playlist":
        return "playlist"
    if entry_id.startswith("UC") and len(entry_id) == 24:
        return "artist"
    if entry_id.startswith("VL") or entry_id.startswith("MPRE") or entry_id.startswith("MPSP"):
        return "playlist"
    if re.fullmatch(r"[A-Za-z0-9_-]{11}", entry_id):
        return "track"
    return entry_type


def _pick_best_thumbnail(thumbnails):
    if not thumbnails:
        return None
    return max(
        thumbnails,
        key=lambda t: (t.get("width", 0) * t.get("height", 0), t.get("width", 0)),
    )


def _extract_cover_from_entry(entry, entry_type=None, video_id=None):
    thumb = entry.get("thumbnail")
    if thumb and not _is_bad_thumb(thumb):
        return thumb
    thumbs = entry.get("thumbnails") or []
    best = _pick_best_thumbnail(thumbs)
    if best:
        best_url = best.get("url")
        if best_url and not _is_bad_thumb(best_url):
            return best_url
    if entry_type == "track":
        fallback = _yt_cover_url(video_id)
        if fallback and not _is_bad_thumb(fallback):
            return fallback
    return None


def _get_ytmusic_client():
    global ytmusic_client
    if YTMusic is None:
        return None
    with ytmusic_client_lock:
        if ytmusic_client is None:
            try:
                ytmusic_client = YTMusic()
            except Exception:
                ytmusic_client = None
        return ytmusic_client


def _resolve_browse_metadata(entry_id, entry_type):
    if not entry_id or entry_type not in {"artist", "playlist"}:
        return {}

    cache_key = (entry_type, entry_id)
    with search_metadata_lock:
        if cache_key in search_metadata_cache:
            return search_metadata_cache[cache_key]

    ytmusic = _get_ytmusic_client()
    if ytmusic is None:
        return {}

    resolved = {}
    try:
        if entry_type == "artist":
            artist = ytmusic.get_artist(entry_id)
            resolved["title"] = artist.get("name")
            resolved["uploader"] = artist.get("name")
            best = _pick_best_thumbnail(artist.get("thumbnails"))
            if best:
                resolved["cover"] = best.get("url")
        elif entry_type == "playlist":
            if entry_id.startswith("MPRE"):
                album = ytmusic.get_album(entry_id)
                resolved["title"] = album.get("title")
                artists = album.get("artists") or []
                if artists:
                    resolved["uploader"] = ", ".join(a.get("name") for a in artists if a.get("name"))
                best = _pick_best_thumbnail(album.get("thumbnails"))
                if best:
                    resolved["cover"] = best.get("url")
            else:
                playlist = ytmusic.get_playlist(entry_id, limit=1)
                resolved["title"] = playlist.get("title")
                resolved["uploader"] = playlist.get("author")
                best = _pick_best_thumbnail(playlist.get("thumbnails"))
                if best:
                    resolved["cover"] = best.get("url")
    except Exception:
        resolved = {}

    with search_metadata_lock:
        search_metadata_cache[cache_key] = resolved
    return resolved


def _resolve_track_metadata(video_id):
    if not video_id:
        return {}

    cache_key = ("track", video_id)
    with search_metadata_lock:
        if cache_key in search_metadata_cache:
            return search_metadata_cache[cache_key]

    ytmusic = _get_ytmusic_client()
    if ytmusic is None:
        return {}

    resolved = {}
    try:
        song = ytmusic.get_song(video_id)
        details = song.get("videoDetails") or {}
        if details.get("title"):
            resolved["title"] = details.get("title")
        artist = details.get("author") or details.get("channelName")
        if artist:
            resolved["uploader"] = artist
        thumbs = ((details.get("thumbnail") or {}).get("thumbnails")) or []
        best = _pick_best_thumbnail(thumbs)
        if best:
            resolved["cover"] = best.get("url")
    except Exception:
        resolved = {}

    with search_metadata_lock:
        search_metadata_cache[cache_key] = resolved
    return resolved


def _iter_search_entries(entries):
    for entry in entries or []:
        if not isinstance(entry, dict):
            continue
        if entry.get("_type") == "playlist" and entry.get("entries"):
            for sub in _iter_search_entries(entry.get("entries")):
                yield sub
            continue
        yield entry


def _entry_to_search_item(entry, include_types):
    raw_url = entry.get("webpage_url") or entry.get("url")
    url = _normalize_music_url(raw_url)
    entry_type = _classify_entry(entry, url)

    if entry_type not in include_types:
        return None

    raw_id = entry.get("id")
    parsed = urlparse(url or "")
    query = parse_qs(parsed.query)
    path_parts = [part for part in parsed.path.split("/") if part]
    browse_id = None
    if len(path_parts) >= 2 and path_parts[0] == "browse":
        browse_id = path_parts[1]
    if not browse_id and entry_type in {"artist", "playlist"}:
        browse_id = raw_id

    video_id = None
    if entry_type == "track":
        video_id = query.get("v", [None])[0] or _yt_video_id(url)
        if not video_id and raw_id and re.fullmatch(r"[A-Za-z0-9_-]{11}", raw_id):
            video_id = raw_id

    title = entry.get("title") or ""
    uploader = entry.get("uploader") or entry.get("artist") or entry.get("creator") or ""
    cover = _extract_cover_from_entry(entry, entry_type=entry_type, video_id=video_id)

    if entry_type in {"artist", "playlist"} and browse_id:
        resolved = _resolve_browse_metadata(browse_id, entry_type)
        if not title:
            title = resolved.get("title") or title
        if not uploader:
            uploader = resolved.get("uploader") or uploader
        if not cover:
            cover = resolved.get("cover")
    elif entry_type == "track" and video_id:
        resolved = _resolve_track_metadata(video_id)
        if not title:
            title = resolved.get("title") or title
        if not uploader:
            uploader = resolved.get("uploader") or uploader
        if not cover:
            cover = resolved.get("cover")

    if not uploader and title and " - " in title:
        # Fallback léger quand yt-dlp/ytmusic ne donnent pas d'artiste.
        uploader = title.split(" - ", 1)[0].strip()

    if not url:
        if entry_type in {"artist", "playlist"} and browse_id:
            url = f"https://music.youtube.com/browse/{browse_id}"
        elif entry_type == "track" and video_id:
            url = f"https://music.youtube.com/watch?v={video_id}"

    if not title:
        title = f"{entry_type}:{browse_id or video_id or _hash_url(url or 'fallback')}"
    if not url:
        return None

    return {
        "id": browse_id or video_id or raw_id or _hash_url(url),
        "title": title,
        "artist": uploader,
        "url": url,
        "cover": cover,
        "type": entry_type,
    }


def _track_item(video_id, title, artist, cover=None):
    if not video_id or not title:
        return None
    url = f"https://music.youtube.com/watch?v={video_id}"
    if cover and _is_bad_thumb(cover):
        cover = None
    if not cover:
        fallback = _yt_cover_url(video_id)
        cover = None if _is_bad_thumb(fallback) else fallback
    return {
        "id": video_id,
        "title": title,
        "artist": artist or "",
        "url": url,
        "cover": cover,
        "type": "track",
    }


def _resolve_playlist_tracks(entry_id, limit=12):
    ytmusic = _get_ytmusic_client()
    if ytmusic is None:
        return None, "ytmusicapi not installed or unavailable"

    items = []
    try:
        if entry_id.startswith("MPRE"):
            album = ytmusic.get_album(entry_id)
            album_title = album.get("title") or ""
            album_artists = album.get("artists") or []
            default_artist = ", ".join(a.get("name") for a in album_artists if a.get("name"))
            for track in (album.get("tracks") or [])[:limit]:
                video_id = track.get("videoId")
                title = track.get("title")
                artists = track.get("artists") or []
                artist = ", ".join(a.get("name") for a in artists if a.get("name")) or default_artist
                thumbs = track.get("thumbnails") or album.get("thumbnails") or []
                best = _pick_best_thumbnail(thumbs)
                item = _track_item(video_id, title, artist, cover=best.get("url") if best else None)
                if item:
                    if not item.get("artist"):
                        item["artist"] = album_title
                    items.append(item)
        else:
            playlist_id = entry_id[2:] if entry_id.startswith("VL") else entry_id
            playlist = ytmusic.get_playlist(playlist_id, limit=limit)
            playlist_author = playlist.get("author") or ""
            playlist_thumbs = playlist.get("thumbnails") or []
            for track in (playlist.get("tracks") or [])[:limit]:
                video_id = track.get("videoId")
                title = track.get("title")
                artists = track.get("artists") or []
                artist = ", ".join(a.get("name") for a in artists if a.get("name")) or playlist_author
                thumbs = track.get("thumbnails") or playlist_thumbs
                best = _pick_best_thumbnail(thumbs)
                item = _track_item(video_id, title, artist, cover=best.get("url") if best else None)
                if item:
                    items.append(item)
    except Exception as e:
        return None, _friendly_ytdlp_error(str(e))
    return items, None


def _resolve_artist_tracks(entry_id, fallback_title="", limit=12):
    ytmusic = _get_ytmusic_client()
    if ytmusic is None:
        return None, "ytmusicapi not installed or unavailable"

    items = []
    try:
        artist = ytmusic.get_artist(entry_id)
        artist_name = artist.get("name") or fallback_title or ""

        # Prend d'abord les titres "Top songs" si disponibles.
        for track in ((artist.get("songs") or {}).get("results") or []):
            video_id = track.get("videoId")
            title = track.get("title")
            artists = track.get("artists") or []
            track_artist = ", ".join(a.get("name") for a in artists if a.get("name")) or artist_name
            thumbs = track.get("thumbnails") or artist.get("thumbnails") or []
            best = _pick_best_thumbnail(thumbs)
            item = _track_item(video_id, title, track_artist, cover=best.get("url") if best else None)
            if item:
                items.append(item)
            if len(items) >= limit:
                break

        if len(items) < limit and artist_name:
            extra = ytmusic.search(artist_name, filter="songs", limit=limit * 2)
            seen = {it["id"] for it in items}
            for track in extra:
                video_id = track.get("videoId")
                title = track.get("title")
                artists = track.get("artists") or []
                track_artist = ", ".join(a.get("name") for a in artists if a.get("name"))
                if video_id in seen:
                    continue
                if artist_name and track_artist and artist_name.lower() not in track_artist.lower():
                    continue
                thumbs = track.get("thumbnails") or []
                best = _pick_best_thumbnail(thumbs)
                item = _track_item(video_id, title, track_artist or artist_name, cover=best.get("url") if best else None)
                if item:
                    items.append(item)
                    seen.add(item["id"])
                if len(items) >= limit:
                    break
    except Exception as e:
        return None, _friendly_ytdlp_error(str(e))
    return items, None


def _unique_playlist_name(playlists, base_name):
    base = (base_name or "Playlist").strip() or "Playlist"
    existing = {p.get("name") for p in playlists}
    if base not in existing:
        return base
    i = 2
    while True:
        candidate = f"{base} ({i})"
        if candidate not in existing:
            return candidate
        i += 1


def _yt_dlp_search(query, limit=10):
    if yt_dlp is None:
        return None, "yt-dlp not installed"
    ydl_opts = {
        "quiet": True,
        "no_warnings": True,
        "extract_flat": True,
        "skip_download": True,
        "lazy_playlist": True,
        "playlistend": limit,
        "socket_timeout": 10,
        "retries": 1,
        "extractor_retries": 1,
        "noplaylist": False,
        "extractor_args": {"youtube": {"player_client": ["android"]}},
    }
    search = f"https://music.youtube.com/search?q={quote(query)}"
    def _do_search():
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            return ydl.extract_info(search, download=False)
    with ThreadPoolExecutor(max_workers=1) as executor:
        future = executor.submit(_do_search)
        try:
            info = future.result(timeout=YTDLP_SEARCH_TIMEOUT)
            return info.get("entries", []), None
        except FuturesTimeoutError:
            return None, f"timeout after {YTDLP_SEARCH_TIMEOUT}s"
        except Exception as e:
            return None, str(e)


def _add_history(item):
    history = _load_json(HISTORY_JSON, [])
    item = dict(item)
    item["played_at"] = int(time.time())
    history = [h for h in history if h.get("id") != item.get("id")]
    history.insert(0, item)
    history = history[:100]
    _save_json(HISTORY_JSON, history)


def _add_download_entry(entry):
    downloads = _load_json(DOWNLOADS_JSON, [])
    downloads = [d for d in downloads if d.get("id") != entry.get("id")]
    downloads.append(entry)
    _save_json(DOWNLOADS_JSON, downloads)


@app.route("/")
def index():
    return render_template("index.html", app_name=APP_NAME)


@app.route("/api/status")
def api_status():
    return jsonify({"ok": True, "online": _get_online_mode()})


@app.route("/api/online", methods=["POST"])
def api_online():
    payload = request.get_json(silent=True) or {}
    value = bool(payload.get("online", True))
    _set_online_mode(value)
    return jsonify({"ok": True, "online": _get_online_mode()})


@app.route("/api/search")
def api_search():
    if not _get_online_mode():
        return jsonify({"ok": False, "error": "offline"}), 400
    q = request.args.get("q") or ""
    if not q:
        return jsonify({"ok": False, "error": "missing q"}), 400
    include_types = _parse_types_filter(request.args.get("types"), default_types={"track", "artist"})
    entries, error = _yt_dlp_search(q, limit=12)
    if entries is None:
        return jsonify({"ok": False, "error": error or "search failed"}), 500
    items = []
    seen = set()
    for entry in _iter_search_entries(entries):
        item = _entry_to_search_item(entry, include_types=include_types)
        if not item:
            continue
        dedupe_key = (item.get("type"), item.get("id"), item.get("url"))
        if dedupe_key in seen:
            continue
        seen.add(dedupe_key)
        items.append(item)
        if len(items) >= 12:
            break
    return jsonify({"ok": True, "items": items})


@app.route("/api/search/expand")
def api_search_expand():
    if not _get_online_mode():
        return jsonify({"ok": False, "error": "offline"}), 400

    entry_type = (request.args.get("type") or "").strip().lower()
    entry_id = (request.args.get("id") or "").strip()
    title = (request.args.get("title") or "").strip()
    if entry_type not in {"artist", "playlist"}:
        return jsonify({"ok": False, "error": "type must be artist or playlist"}), 400
    if not entry_id:
        return jsonify({"ok": False, "error": "missing id"}), 400

    if entry_type == "artist":
        items, error = _resolve_artist_tracks(entry_id, fallback_title=title, limit=12)
    else:
        items, error = _resolve_playlist_tracks(entry_id, limit=12)

    if items is None:
        return jsonify({"ok": False, "error": error or "expand failed"}), 500
    return jsonify({"ok": True, "items": items})


@app.route("/api/cache/play", methods=["POST"])
def api_cache_play():
    payload = request.get_json(silent=True) or {}
    url = payload.get("url")
    title = payload.get("title") or "Track"
    artist = payload.get("artist") or ""
    cover_url = payload.get("cover")
    if not cover_url:
        cover_url = _yt_cover_url(_yt_video_id(url))
    if _is_bad_thumb(cover_url):
        cover_url = None
    if not url:
        return jsonify({"ok": False, "error": "missing url"}), 400

    key = _cache_key(url, title)
    path = _cache_path(key)
    cover_path = _cover_path(key)

    if os.path.exists(path):
        _touch_cache_entry(
            key,
            {
                "id": key,
                "title": title,
                "artist": artist,
                "url": url,
                "path": path,
                "cover_path": cover_path if os.path.exists(cover_path) else None,
                "last_played": time.time(),
                "downloaded": False,
            },
        )
        return jsonify({"ok": True, "file_url": f"/api/cache/file?key={quote(key)}", "key": key})

    if not _get_online_mode():
        return jsonify({"ok": False, "error": "offline and not cached"}), 400

    info, error = _yt_dlp_info(url, download=True, outtmpl=os.path.join(CACHE_MUSIC_DIR, f"{key}.%(ext)s"))
    if not info:
        return jsonify({"ok": False, "error": error or "download failed"}), 500

    if cover_url:
        _save_cover_from_url(cover_url, key)

    _touch_cache_entry(
        key,
        {
            "id": key,
            "title": title,
            "artist": artist,
            "url": url,
            "path": path,
            "cover_path": cover_path if os.path.exists(cover_path) else None,
            "last_played": time.time(),
            "downloaded": False,
        },
    )
    _cleanup_cache()
    return jsonify({"ok": True, "file_url": f"/api/cache/file?key={quote(key)}", "key": key})


@app.route("/api/cache/prefetch", methods=["POST"])
def api_cache_prefetch():
    if not _get_online_mode():
        return jsonify({"ok": False, "error": "offline"}), 400
    payload = request.get_json(silent=True) or {}
    items = payload.get("items") or []
    def _prefetch():
        for item in items[:3]:
            url = item.get("url")
            title = item.get("title") or "Track"
            artist = item.get("artist") or ""
            cover_url = item.get("cover")
            if not cover_url:
                cover_url = _yt_cover_url(_yt_video_id(url))
            if _is_bad_thumb(cover_url):
                cover_url = None
            if not url:
                continue
            key = _cache_key(url, title)
            path = _cache_path(key)
            if os.path.exists(path):
                continue
            _yt_dlp_info(url, download=True, outtmpl=os.path.join(CACHE_MUSIC_DIR, f"{key}.%(ext)s"))
            if cover_url:
                _save_cover_from_url(cover_url, key)
            _touch_cache_entry(
                key,
                {
                    "id": key,
                    "title": title,
                    "artist": artist,
                    "url": url,
                    "path": path,
                    "cover_path": _cover_path(key) if os.path.exists(_cover_path(key)) else None,
                    "last_played": time.time(),
                    "downloaded": False,
                },
            )
        _cleanup_cache()

    threading.Thread(target=_prefetch, daemon=True).start()
    return jsonify({"ok": True})


@app.route("/api/cache/file")
def api_cache_file():
    key = request.args.get("key") or ""
    if not key:
        return jsonify({"ok": False, "error": "missing key"}), 400
    path = _cache_path(key)
    if not os.path.exists(path):
        return jsonify({"ok": False, "error": "not found"}), 404
    return send_file(path, as_attachment=False)


@app.route("/api/cache/list")
def api_cache_list():
    items = list(_load_json(CACHE_JSON, {}).values())
    for item in items:
        item["file_url"] = f"/api/cache/file?key={quote(item.get('id', ''))}"
    return jsonify({"ok": True, "items": items})


@app.route("/api/download", methods=["POST"])
def api_download():
    payload = request.get_json(silent=True) or {}
    url = payload.get("url")
    title = payload.get("title") or "Track"
    artist = payload.get("artist") or ""
    cover_url = payload.get("cover")
    if not cover_url:
        cover_url = _yt_cover_url(_yt_video_id(url))
    if _is_bad_thumb(cover_url):
        cover_url = None
    if not url:
        return jsonify({"ok": False, "error": "missing url"}), 400

    safe_title = _safe_title(title)
    path = _download_path(title)

    with download_lock:
        info, error = _yt_dlp_info(url, download=True, outtmpl=os.path.join(MUSIC_DIR, f"{safe_title}.%(ext)s"))
    if not info:
        return jsonify({"ok": False, "error": error or "download failed"}), 500

    key = _cache_key(url, title)
    if cover_url:
        _save_cover_from_url(cover_url, key)
    entry = {
        "id": key,
        "title": title,
        "artist": artist,
        "url": url,
        "path": path,
        "cover_path": _cover_path(key) if os.path.exists(_cover_path(key)) else None,
        "downloaded": True,
        "downloaded_at": int(time.time()),
    }
    _add_download_entry(entry)
    _touch_cache_entry(key, dict(entry, last_played=time.time()))
    return jsonify({"ok": True, "id": safe_title})


@app.route("/api/download/list")
def api_download_list():
    items = _load_json(DOWNLOADS_JSON, [])
    for item in items:
        if os.path.exists(_download_path(item.get("title") or "")):
            item["file_url"] = f"/api/download/file?title={quote(item.get('title',''))}"
        else:
            item["file_url"] = None
    return jsonify({"ok": True, "items": items})


@app.route("/api/download/file")
def api_download_file():
    title = request.args.get("title") or ""
    if not title:
        return jsonify({"ok": False, "error": "missing title"}), 400
    path = _download_path(title)
    if not os.path.exists(path):
        return jsonify({"ok": False, "error": "not found"}), 404
    return send_file(path, as_attachment=False)


@app.route("/api/download/delete", methods=["POST"])
def api_download_delete():
    payload = request.get_json(silent=True) or {}
    title = payload.get("title")
    if not title:
        return jsonify({"ok": False, "error": "missing title"}), 400
    path = _download_path(title)
    if os.path.exists(path):
        try:
            os.remove(path)
        except Exception:
            pass
    downloads = _load_json(DOWNLOADS_JSON, [])
    downloads = [d for d in downloads if d.get("title") != title]
    _save_json(DOWNLOADS_JSON, downloads)
    return jsonify({"ok": True})


@app.route("/api/playlists")
def api_playlists():
    items = _load_json(PLAYLIST_JSON, [])
    return jsonify({"ok": True, "items": items})


@app.route("/api/playlists/create", methods=["POST"])
def api_playlists_create():
    payload = request.get_json(silent=True) or {}
    name = payload.get("name")
    if not name:
        return jsonify({"ok": False, "error": "missing name"}), 400
    playlists = _load_json(PLAYLIST_JSON, [])
    if any(p.get("name") == name for p in playlists):
        return jsonify({"ok": False, "error": "exists"}), 400
    playlists.append({"name": name, "items": []})
    _save_json(PLAYLIST_JSON, playlists)
    return jsonify({"ok": True})


@app.route("/api/playlists/add", methods=["POST"])
def api_playlists_add():
    payload = request.get_json(silent=True) or {}
    name = payload.get("name")
    item = payload.get("item")
    if not name or not item:
        return jsonify({"ok": False, "error": "missing name/item"}), 400
    playlists = _load_json(PLAYLIST_JSON, [])
    for pl in playlists:
        if pl.get("name") == name:
            pl["items"] = [i for i in pl.get("items", []) if i.get("id") != item.get("id")]
            pl["items"].append(item)
            break
    _save_json(PLAYLIST_JSON, playlists)
    return jsonify({"ok": True})


@app.route("/api/playlists/import", methods=["POST"])
def api_playlists_import():
    payload = request.get_json(silent=True) or {}
    entry_id = (payload.get("id") or "").strip()
    title = (payload.get("title") or "").strip() or "Playlist"
    if not entry_id:
        return jsonify({"ok": False, "error": "missing id"}), 400

    items, error = _resolve_playlist_tracks(entry_id, limit=100)
    if items is None:
        return jsonify({"ok": False, "error": error or "import failed"}), 500
    if not items:
        return jsonify({"ok": False, "error": "empty playlist"}), 400

    playlists = _load_json(PLAYLIST_JSON, [])
    final_name = _unique_playlist_name(playlists, title)
    playlists.append({"name": final_name, "items": items})
    _save_json(PLAYLIST_JSON, playlists)
    return jsonify({"ok": True, "name": final_name, "count": len(items)})


@app.route("/api/playlists/remove", methods=["POST"])
def api_playlists_remove():
    payload = request.get_json(silent=True) or {}
    name = payload.get("name")
    item_id = payload.get("id")
    if not name or not item_id:
        return jsonify({"ok": False, "error": "missing name/id"}), 400
    playlists = _load_json(PLAYLIST_JSON, [])
    for pl in playlists:
        if pl.get("name") == name:
            pl["items"] = [i for i in pl.get("items", []) if i.get("id") != item_id]
            break
    _save_json(PLAYLIST_JSON, playlists)
    return jsonify({"ok": True})


@app.route("/api/history")
def api_history():
    return jsonify({"ok": True, "items": _load_json(HISTORY_JSON, [])})


@app.route("/api/history/add", methods=["POST"])
def api_history_add():
    payload = request.get_json(silent=True) or {}
    item = payload.get("item")
    if not item:
        return jsonify({"ok": False, "error": "missing item"}), 400
    _add_history(item)
    return jsonify({"ok": True})


@app.route("/api/lyrics")
def api_lyrics():
    title = request.args.get("title") or ""
    artist = request.args.get("artist") or ""
    if not title:
        return jsonify({"ok": False, "error": "missing title"}), 400
    key = _safe_title(f"{title}-{artist}")
    cache_path = os.path.join(CACHE_LYRICS_DIR, f"{key}.json")
    if os.path.exists(cache_path):
        return jsonify({"ok": True, "data": _load_json(cache_path, {}), "cached": True})
    if not _get_online_mode():
        return jsonify({"ok": False, "error": "offline"}), 400
    if syncedlyrics is None:
        return jsonify({"ok": False, "error": "syncedlyrics not installed"}), 500
    try:
        synced = syncedlyrics.search(f"{title} {artist}")
        data = {"title": title, "artist": artist, "synced": synced}
        _save_json(cache_path, data)
        return jsonify({"ok": True, "data": data, "cached": False})
    except Exception as e:
        return jsonify({"ok": False, "error": str(e)}), 500


@app.route("/api/cover")
def api_cover():
    key = request.args.get("key") or ""
    if not key:
        return jsonify({"ok": False, "error": "missing key"}), 400
    path = _cover_path(key)
    if not os.path.exists(path):
        return jsonify({"ok": False, "error": "not found"}), 404
    return send_file(path, as_attachment=False)


@app.route("/api/playback", methods=["GET", "POST"])
def api_playback():
    global current_playback
    if request.method == "POST":
        payload = request.get_json(silent=True) or {}
        with playback_lock:
            current_playback.update(
                {
                    "id": payload.get("id"),
                    "currentTime": payload.get("currentTime", 0),
                    "duration": payload.get("duration", 0),
                    "status": payload.get("status", "stopped"),
                    "timestamp": time.time(),
                }
            )
        return jsonify({"ok": True})
    else:
        with playback_lock:
            return jsonify({"ok": True, "data": current_playback})


@app.route("/api/volume", methods=["GET", "POST"])
def api_volume():
    global current_volume
    if request.method == "POST":
        payload = request.get_json(silent=True) or {}
        volume = payload.get("volume", 80)
        volume = max(0, min(100, int(volume)))
        with volume_lock:
            current_volume["volume"] = volume
        return jsonify({"ok": True, "volume": volume})
    else:
        with volume_lock:
            return jsonify({"ok": True, "volume": current_volume["volume"]})


@app.route("/api/remote", methods=["POST"])
def api_remote_action():
    payload = request.get_json(silent=True) or {}
    action = payload.get("action")
    if not action:
        return jsonify({"ok": False, "error": "missing action"}), 400
    with remote_lock:
        remote_queue.append({"action": action})
        if len(remote_queue) > 50:
            remote_queue.popleft()
    return jsonify({"ok": True})


@app.route("/api/remote/next")
def api_remote_next():
    with remote_lock:
        if remote_queue:
            item = remote_queue.popleft()
            return jsonify({"ok": True, "action": item.get("action")})
    return jsonify({"ok": True, "action": None})


@app.route("/api/remote/available")
def api_remote_available():
    return jsonify({"ok": True, "available": True, "device_type": "LumaTV", "name": "LumaTVonLocalhost"})


@app.route("/api/devices")
def api_devices():
    return jsonify({"ok": True, "items": _load_devices()})


@app.route("/api/devices/add", methods=["POST"])
def api_devices_add():
    payload = request.get_json(silent=True) or {}
    name = payload.get("name") or "Device"
    host = payload.get("host")
    port = payload.get("port")
    if not host or not port:
        return jsonify({"ok": False, "error": "missing host/port"}), 400
    devices = _load_devices()
    device_id = f"{host}:{port}"
    devices = [d for d in devices if d.get("id") != device_id]
    devices.append({"id": device_id, "name": name, "host": host, "port": int(port)})
    _save_devices(devices)
    return jsonify({"ok": True})


@app.route("/api/devices/remove", methods=["POST"])
def api_devices_remove():
    payload = request.get_json(silent=True) or {}
    device_id = payload.get("id")
    if not device_id:
        return jsonify({"ok": False, "error": "missing id"}), 400
    devices = _load_devices()
    devices = [d for d in devices if d.get("id") != device_id]
    _save_devices(devices)
    return jsonify({"ok": True})


if __name__ == "__main__":
    _cleanup_cache()
    app.run(host="0.0.0.0", port=5050, debug=True)
