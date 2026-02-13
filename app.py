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
from urllib.parse import quote

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
    ydl_opts = {
        "quiet": True,
        "no_warnings": True,
        "extract_flat": False,
        "noplaylist": True,
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
        return None, str(e)


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
    entries, error = _yt_dlp_search(q, limit=12)
    if not entries:
        return jsonify({"ok": False, "error": error or "no results"}), 500
    items = []
    for entry in entries:
        if entry.get("_type") in {"channel", "playlist"}:
            continue
        title = entry.get("title") or ""
        uploader = entry.get("uploader") or entry.get("artist") or entry.get("creator") or ""
        url = entry.get("webpage_url") or entry.get("url")
        if url and not url.startswith("http"):
            url = f"https://www.youtube.com/watch?v={url}"
        if url and not url.startswith("http"):
            url = f"https://music.youtube.com/watch?v={url}"
        if url and ("/channel/" in url or "/@" in url) and "watch?v=" not in url:
            continue
        video_id = entry.get("id") or _yt_video_id(url)
        thumb = _yt_cover_url(video_id)
        if not url:
            continue
        if _is_bad_thumb(thumb):
            thumb = None
        items.append(
            {
                "id": entry.get("id") or _hash_url(url),
                "title": title,
                "artist": uploader,
                "url": url,
                "cover": thumb,
            }
        )
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

    def _run():
        with download_lock:
            _yt_dlp_info(url, download=True, outtmpl=os.path.join(MUSIC_DIR, f"{safe_title}.%(ext)s"))
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

    threading.Thread(target=_run, daemon=True).start()
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
