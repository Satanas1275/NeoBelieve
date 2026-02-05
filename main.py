from flask import Flask, render_template, request, jsonify, send_from_directory
import os, csv, json, re, hashlib, uuid
import yt_dlp
import requests
from spotapi import Song
import shutil

app = Flask(__name__)

# --- Dossiers ---
CSV_FOLDER = "playlists_csv"
MUSIC_FOLDER = "music"
CACHE_FOLDER = "music_cache"
DATA_FILE = "data.json"
MUSIC_DATA_FILE = "music_data.json"
DEVICE_TYPE = os.environ.get("NEOBELIEVE_DEVICE_TYPE", "NeoBelieve")
DEVICE_NAME = os.environ.get("NEOBELIEVE_DEVICE_NAME", "NeoBelieve")

os.makedirs(MUSIC_FOLDER, exist_ok=True)
os.makedirs(CACHE_FOLDER, exist_ok=True)

# --- data.json ---
if not os.path.exists(DATA_FILE):
    with open(DATA_FILE,"w",encoding="utf-8") as f:
        json.dump({"recent":[]},f)

def load_data():
    try:
        with open(DATA_FILE,"r",encoding="utf-8") as f:
            content = f.read().strip()
            if not content: return {"recent":[]}
            return json.loads(content)
    except Exception:
        return {"recent":[]}

def save_data(data):
    with open(DATA_FILE,"w",encoding="utf-8") as f:
        json.dump(data,f,indent=2)

def add_to_recent(item):
    item['image'] = download_image(item['image'], item['title'])
    data = load_data()
    recent = data.get("recent", [])
    recent = [r for r in recent if r.get("id") != item.get("id")]
    recent.insert(0,item)
    if len(recent) > 20: recent = recent[:20]
    data["recent"] = recent
    save_data(data)

# --- Music Database ---
class MusicDatabase:
    def __init__(self, filepath):
        self.filepath = filepath
        self.data = self.load()

    def load(self):
        if not os.path.exists(self.filepath):
            return {"tracks": {}}
        try:
            with open(self.filepath, "r", encoding="utf-8") as f:
                return json.load(f)
        except:
            return {"tracks": {}}

    def save(self):
        with open(self.filepath, "w", encoding="utf-8") as f:
            json.dump(self.data, f, indent=2)

    def add_track(self, track_id, info):
        self.data["tracks"][track_id] = info
        self.save()

    def get_track(self, track_id):
        return self.data["tracks"].get(track_id)

    def get_all_tracks(self):
        return list(self.data["tracks"].values())
    
    def find_by_filename(self, filename):
        for t in self.data["tracks"].values():
            if t.get("filename") == filename:
                return t
        return None

db = MusicDatabase(MUSIC_DATA_FILE)

def migrate_files():
    # Scan music and music_cache
    for folder in [MUSIC_FOLDER, CACHE_FOLDER]:
        if not os.path.exists(folder): continue
        for filename in os.listdir(folder):
            if not filename.endswith(".mp3"): continue
            
            # Check if already in DB
            if db.find_by_filename(filename):
                continue
                
            # Parse filename
            name_part = os.path.splitext(filename)[0]
            # Try to detect existing ID format {name}-{id}.mp3
            # Assuming ID is roughly youtube length (11 chars) or uuid (36)
            # Regex for ID at end: -([a-zA-Z0-9_-]{11}|[a-f0-9-]{36})$
            match = re.search(r'-([a-zA-Z0-9_-]{11}|[a-f0-9-]{36})$', name_part)
            
            if match:
                track_id = match.group(1)
                title = name_part[:-len(track_id)-1] # remove -id
            else:
                # Needs migration
                track_id = str(uuid.uuid4())
                title = name_part
                new_filename = f"{title}-{track_id}.mp3"
                try:
                    os.rename(os.path.join(folder, filename), os.path.join(folder, new_filename))
                    filename = new_filename
                    print(f"Migrated {title} to {new_filename}")
                except Exception as e:
                    print(f"Failed to migrate {filename}: {e}")
                    continue

            # Register in DB
            db.add_track(track_id, {
                "id": track_id,
                "title": title,
                "filename": filename,
                "path": f"/{folder}/{filename}",
                "location": folder,
                "source": "migrated"
            })

migrate_files()

def get_recent_url(title):
    data = load_data()
    for item in data.get("recent", []):
        if sanitize_filename(item.get("title", "")) == title or item.get("id") == title:
             return item.get("original_url") or item.get("url_youtube")
    return None

# --- Playlists CSV ---

def load_playlists():
    playlists=[]
    for filename in os.listdir(CSV_FOLDER):
        if filename.endswith(".csv"):
            filepath = os.path.join(CSV_FOLDER, filename)
            with open(filepath,newline='',encoding='utf-8') as csvfile:
                reader = csv.DictReader(csvfile)
                reader.fieldnames = [n.strip() for n in reader.fieldnames]
                tracks = [{k.strip():v.strip() for k,v in row.items()} for row in reader]
                if not tracks: continue
                playlist_name = os.path.splitext(filename)[0].replace("_"," ")
                images=[]
                for t in tracks:
                    img_url = t.get("Album Image URL","")
                    if img_url and img_url not in images: images.append(img_url)
                if len(tracks)==1:
                    grid_type="single"
                    images=[{"url": images[0] if images else ""}]
                else:
                    grid_type="double"
                    images=images[:4]
                    while len(images)<4: images.append("")
                    images=[{"url":img} for img in images]
                    # Download cover images
                playlists.append({
                    "name": playlist_name,
                    "filename": filename,
                    "tracks":{"total":len(tracks)},
                    "images":images,
                    "grid_type":grid_type
                })
    return playlists

# --- YouTube search + download ---
def sanitize_filename(name):
    return re.sub(r'[\\/*?:"<>|]',"",name)

def download_image(url, title=None, folder="data/img"):
    if not url or url.startswith("/"):
        return url  # already local or empty
    try:
        # Get extension
        ext = 'jpg'  # default
        if '.' in url:
            ext = url.split('.')[-1].split('?')[0].lower()
            if ext not in ['jpg', 'jpeg', 'png', 'gif']:
                ext = 'jpg'
        # Filename
        if title:
            base_name = sanitize_filename(title)
        else:
            base_name = hashlib.md5(url.encode()).hexdigest()
        hash_name = base_name + '.' + ext
        path = os.path.join(folder, hash_name)
        if os.path.exists(path):
            return f"/{folder}/{hash_name}"  # already downloaded
        os.makedirs(folder, exist_ok=True)
        response = requests.get(url, timeout=10)
        if response.status_code == 200:
            with open(path, 'wb') as f:
                f.write(response.content)
            return f"/{folder}/{hash_name}"
    except Exception as e:
        print(f"Error downloading image {url}: {e}")
    return url  # fallback

def search_youtube(query, max_results=5):
    ydl_opts={"format":"bestaudio/best","quiet":True,"skip_download":True,"extract_flat":"in_playlist"}
    results=[]
    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        search=ydl.extract_info(f"ytsearch{max_results}:{query}",download=False)
        for entry in search['entries']:
            results.append({
                "title": entry.get("title"),
                "uploader": entry.get("uploader"),
                "url": entry.get("url"),
                "thumbnail": entry.get("thumbnail",""),
                "id": entry.get("id")
            })
    return results

def get_or_download_track(track):
    title = track.get("Track Name") or track.get("title")
    artist = track.get("Artist Name(s)") or track.get("uploader","")
    search_query = f"{title} {artist}"
    filename = sanitize_filename(title)
    mp3_filename = f"{filename}.mp3"

    # Vérifie en local (cache ou music)
    local_paths = [os.path.join(CACHE_FOLDER, mp3_filename), os.path.join(MUSIC_FOLDER, mp3_filename)]
    for p in local_paths:
        if os.path.exists(p):
            return f"/{CACHE_FOLDER if CACHE_FOLDER in p else MUSIC_FOLDER}/{mp3_filename}", None

    # Recherche YouTube

    # Recherche YouTube
    ydl_opts={"format":"bestaudio/best","quiet":True,"skip_download":True,"extract_flat":"in_playlist"}
    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        results=ydl.extract_info(f"ytsearch5:{search_query}", download=False)["entries"]
        # Filtrer artiste exact
        matches = [e for e in results if artist.lower() in e.get("uploader","").lower()]
        if not matches:
            matches = results
        # Priorité Official
        for e in matches:
            if re.search(r"official", e.get("title",""), re.I):
                best = e
                break
        else:
            best = matches[0]

    # Create filename with ID
    track_id = best.get("id")
    safe_title = sanitize_filename(title)
    mp3_filename = f"{safe_title}-{track_id}.mp3"
    
    # URL YouTube (Restored)
    video_url = best.get("webpage_url") or best.get("url")
    if not video_url:
        raise Exception("Impossible de récupérer l'URL YouTube du track")

    # Check if exists (using new DB check or file check)
    existing = db.get_track(track_id)
    if existing and os.path.exists(os.path.join(existing['location'], existing['filename'])):
        return existing['path'], video_url

    # Download
    ydl_opts_download = {
        "format":"bestaudio/best",
        "outtmpl": os.path.join(CACHE_FOLDER, f"{safe_title}-{track_id}"),
        "quiet": True,
        "postprocessors":[{"key":"FFmpegExtractAudio","preferredcodec":"mp3","preferredquality":"192"}]
    }
    with yt_dlp.YoutubeDL(ydl_opts_download) as ydl2:
        ydl2.download([video_url])
        
        # Download image locally
        raw_image_url = best.get("thumbnail","")
        local_image_path = ""
        if raw_image_url:
            # We want to match {title}-{id}.jpg/png if possible, or just follow db
            # download_image uses sanitize_filename(title)
            # Let's use the same base name as the mp3 for consistency: f"{title}-{track_id}"
            local_image_path = download_image(raw_image_url, f"{title}-{track_id}")

        # Register in DB
        db.add_track(track_id, {
            "id": track_id,
            "title": title,
            "artist": artist,
            "filename": mp3_filename,
            "path": f"/{CACHE_FOLDER}/{mp3_filename}",
            "location": CACHE_FOLDER,
            "original_url": video_url,
            "source": "youtube",
            "image": local_image_path
        })
        
        return f"/{CACHE_FOLDER}/{mp3_filename}", video_url

def get_or_download_cover(title, artist=""):
    folder = "data/img"
    os.makedirs(folder, exist_ok=True)

    filename = sanitize_filename(f"{artist} - {title}".strip()) + ".jpg"
    path = os.path.join(folder, filename)

    if os.path.exists(path):
        return f"/{folder}/{filename}"

    url = get_spotify_cover(title, artist)
    if not url:
        return None

    try:
        r = requests.get(url, timeout=10)
        if r.status_code == 200:
            with open(path, "wb") as f:
                f.write(r.content)
            return f"/{folder}/{filename}"
    except Exception as e:
        print("Download cover error:", e)

    return None

def get_downloaded_tracks():
    # Return tracks from DB that are in MUSIC_FOLDER
    # Or just all tracks? The route is /downloaded usually implying local music.
    # Let's filter by location or just return all known tracks.
    # The scan logic in migration should have populated DB.
    
    tracks = []
    all_tracks = db.get_all_tracks()
    
    # If DB is empty for some reason (e.g. first run before migration completed?), fall back to scan?
    # But migration runs at startup.
    
    for t in all_tracks:
        if t.get("location") in [MUSIC_FOLDER, CACHE_FOLDER]:
            tracks.append(t)
            
    # Also scan for files not in DB (validating sync)
    # This might be redundant if migration works well, but safe.
    # For now, rely on DB.
    return tracks


def get_cache_size():
    total = 0
    if not os.path.exists(CACHE_FOLDER):
        return 0
    for root, _dirs, files in os.walk(CACHE_FOLDER):
        for name in files:
            path = os.path.join(root, name)
            try:
                total += os.path.getsize(path)
            except OSError:
                pass
    return total

def get_spotify_cover(title, artist=""):
    try:
        song = Song()
        query = f"{title} {artist}".strip()
        results = song.query_songs(query, limit=1)

        items = results["data"]["searchV2"]["tracksV2"]["items"]
        if not items:
            return None

        sources = (
            items[0]["item"]["data"]
            ["albumOfTrack"]["coverArt"]["sources"]
        )

        return sources[0]["url"] if sources else None

    except Exception as e:
        print("Spotify cover error:", e)
        return None

# --- Routes ---
@app.route('/')
def index():
    playlists = load_playlists()
    data = load_data()
    recent = data.get("recent",[])
    downloaded = get_downloaded_tracks()
    return render_template("index.html", playlists=playlists, recent=recent, downloaded=downloaded)

@app.route('/settings')
def settings():
    cache_size = get_cache_size()
    return render_template("settings.html", cache_size=cache_size)

@app.route('/playlist/<playlist_name>')
def show_playlist(playlist_name):
    playlists = load_playlists()
    playlist = next((p for p in playlists if p['name']==playlist_name), None)
    if not playlist: return f"Playlist '{playlist_name}' introuvable", 404
    filepath=os.path.join(CSV_FOLDER,playlist['filename'])
    with open(filepath,newline='',encoding='utf-8') as csvfile:
        reader = csv.DictReader(csvfile)
        reader.fieldnames = [n.strip() for n in reader.fieldnames]
        tracks=[{k.strip():v.strip() for k,v in row.items()} for row in reader]

    # Add to recent removed from here (moved to frontend "Play" button)

    return render_template("playlist.html",playlist=playlist,tracks=tracks)

@app.route('/search')
def search():
    q = request.args.get('q','')
    results=search_youtube(q)
    return jsonify(results)

@app.route('/download')
def download():
    url = request.args.get('url')
    title = request.args.get('title')
    if not title:
        return jsonify({'error': 'Title is required'}), 400
    title = sanitize_filename(title)
    cache = request.args.get('cache', '1') == '1'
    folder = CACHE_FOLDER if cache else MUSIC_FOLDER
    path = f'/{"music_cache" if cache else "music"}/{title}.mp3'
    ydl_opts = {
        'format': 'bestaudio/best',
        'outtmpl': os.path.join(folder, title),
        'quiet': True,
        'postprocessors': [{'key': 'FFmpegExtractAudio', 'preferredcodec': 'mp3', 'preferredquality': '192'}]
    }
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.download([url])
        return jsonify({'path': path})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/music/<filename>')
def serve_music(filename):
    return send_from_directory(MUSIC_FOLDER,filename)

@app.route('/music_cache/<filename>')
def serve_cache(filename):
    path = os.path.join(CACHE_FOLDER, filename)
    if not os.path.exists(path):
        # Auto-recovery logic
        title = os.path.splitext(filename)[0]
        print(f"File missing: {filename}. Attempting recovery for title: {title}")
        
        # 1. Try to find original URL in recent data
        url = get_recent_url(title)
        
        # 2. If no URL, maybe we can search? (Optional, but users request implied "put a url in data.json")
        if url:
             print(f"Found URL for recovery: {url}")
             try:
                 ydl_opts = {
                    'format': 'bestaudio/best',
                    'outtmpl': os.path.join(CACHE_FOLDER, title),
                    'quiet': True,
                    'postprocessors': [{'key': 'FFmpegExtractAudio', 'preferredcodec': 'mp3', 'preferredquality': '192'}]
                 }
                 with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                    ydl.download([url])
             except Exception as e:
                 print(f"Recovery failed: {e}")
        else:
            print("No URL found for recovery.")
            
    return send_from_directory(CACHE_FOLDER,filename)

@app.route('/update_recent',methods=['POST'])
def update_recent():
    item=request.get_json()
    add_to_recent(item)
    return jsonify({"status":"ok"})

@app.route("/get_track_path",methods=["POST"])
def get_track_path():
    track = request.get_json()
    path, original_url = get_or_download_track(track)
    image = download_image(track.get('Album Image URL', ''), track.get('Track Name', ''))
    return jsonify({"path":path, "image": image, "original_url": original_url})

@app.route('/downloaded')
def downloaded_page():
    files = get_downloaded_tracks()
    return render_template("downloaded.html", files=files)

# --- Remote Control & Sync ---
COMMAND_QUEUE = []
PLAYER_STATUS = {"status": "paused", "currentTime": 0, "duration": 0, "id": "", "title": ""}

@app.route('/api/remote', methods=['POST'])
def remote_action():
    data = request.get_json()
    action = data.get('action')
    if action:
        COMMAND_QUEUE.append({"action": action})
    return jsonify({"ok": True})

@app.route('/api/remote/play', methods=['POST'])
def remote_play_track():
    data = request.get_json()
    # Expect: url, title, image, etc.
    COMMAND_QUEUE.append({
        "action": "play_track",
        "track": data
    })
    return jsonify({"ok": True})

@app.route('/api/commands', methods=['GET'])
def get_commands():
    if not COMMAND_QUEUE:
        return jsonify({"commands": []})
    cmds = list(COMMAND_QUEUE)
    COMMAND_QUEUE.clear()
    return jsonify({"commands": cmds})

@app.route('/api/status/update', methods=['POST'])
def update_status():
    global PLAYER_STATUS
    PLAYER_STATUS = request.get_json()
    return jsonify({"ok": True})

@app.route('/api/music/sync', methods=['GET'])
def music_sync():
    return jsonify({"ok": True, "data": PLAYER_STATUS})

# Playlist API (Stub for compatibility if needed, but we use remote/play mostly)
@app.route('/api/playlist/add', methods=['POST'])
def playlist_add():
    # For now, treat "add" as "play" or just add to queue?
    # User calls addCurrentToRemote -> /api/playlist/add
    data = request.get_json()
    COMMAND_QUEUE.append({
        "action": "queue_track", # Or play_track if queue behavior
        "track": data
    })
    return jsonify({"ok": True})

@app.route('/download_image')
def download_image_route():
    url = request.args.get('url')
    title = request.args.get('title', '')
    if not url:
        return '', 400
    local_url = download_image(url, title)
    return local_url

@app.route('/api/cache/clear', methods=['POST'])
def clear_cache():
    if os.path.exists(CACHE_FOLDER):
        for filename in os.listdir(CACHE_FOLDER):
            path = os.path.join(CACHE_FOLDER, filename)
            try:
                if os.path.isfile(path) or os.path.islink(path):
                    os.unlink(path)
                elif os.path.isdir(path):
                    shutil.rmtree(path)
            except Exception:
                pass
    return jsonify({"ok": True})

@app.route('/neobelieve/avaliable')
def neobelieve_avaliable():
    return jsonify({
        "ok": True,
        "available": True,
        "avaliable": True,
        "device_type": DEVICE_TYPE,
        "name": DEVICE_NAME
    })

@app.route('/neobelieve/available')
def neobelieve_available():
    return jsonify({
        "ok": True,
        "available": True,
        "avaliable": True,
        "device_type": DEVICE_TYPE,
        "name": DEVICE_NAME
    })

@app.route("/data/img/<path:filename>")
def serve_cover(filename):
    folder = "data/img"
    path = os.path.join(folder, filename)

    if not os.path.exists(path):
        return "", 404

    return send_from_directory(folder, filename)

@app.route("/cover/<title>.jpg")
def cover_by_name(title):
    cover = get_or_download_cover(title)
    if not cover:
        return "", 404
    return send_from_directory("data/img", os.path.basename(cover))


if __name__ == '__main__':
    app.run(host='0.0.0.0', debug=True)
