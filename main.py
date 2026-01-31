from flask import Flask, render_template, request, jsonify, send_from_directory
import os, csv, json, re, hashlib
import yt_dlp
import requests
from spotapi import Song

app = Flask(__name__)

# --- Dossiers ---
CSV_FOLDER = "playlists_csv"
MUSIC_FOLDER = "music"
CACHE_FOLDER = "music_cache"
DATA_FILE = "data.json"

os.makedirs(MUSIC_FOLDER, exist_ok=True)
os.makedirs(CACHE_FOLDER, exist_ok=True)

# --- data.json ---
if not os.path.exists(DATA_FILE):
    with open(DATA_FILE,"w",encoding="utf-8") as f:
        json.dump({"recent":[]},f)

def load_data():
    with open(DATA_FILE,"r",encoding="utf-8") as f:
        return json.load(f)

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
            return f"/{CACHE_FOLDER if CACHE_FOLDER in p else MUSIC_FOLDER}/{mp3_filename}"

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

    # URL YouTube
    video_url = best.get("webpage_url") or best.get("url")
    if not video_url:
        raise Exception("Impossible de récupérer l'URL YouTube du track")

    # Télécharger dans cache
    ydl_opts_download = {
        "format":"bestaudio/best",
        "outtmpl": os.path.join(CACHE_FOLDER, filename),
        "quiet": True,
        "postprocessors":[{"key":"FFmpegExtractAudio","preferredcodec":"mp3","preferredquality":"192"}]
    }
    with yt_dlp.YoutubeDL(ydl_opts_download) as ydl2:
        ydl2.download([video_url])
        return f"/{CACHE_FOLDER}/{mp3_filename}"

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

# --- Downloaded tracks ---
def get_downloaded_tracks():
    tracks=[]
    for f in os.listdir(MUSIC_FOLDER):
        if f.endswith(".mp3"):
            tracks.append({
                "title": os.path.splitext(f)[0],
                "path": f"/music/{f}",
                "image": ""  # plus tard on peut stocker cover
            })
    return tracks

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
    return send_from_directory(CACHE_FOLDER,filename)

@app.route('/update_recent',methods=['POST'])
def update_recent():
    item=request.get_json()
    add_to_recent(item)
    return jsonify({"status":"ok"})

@app.route("/get_track_path",methods=["POST"])
def get_track_path():
    track = request.get_json()
    path = get_or_download_track(track)
    image = download_image(track.get('Album Image URL', ''), track.get('Track Name', ''))
    return jsonify({"path":path, "image": image})

@app.route('/downloaded')
def downloaded_page():
    files = get_downloaded_tracks()
    return render_template("downloaded.html", files=files)

@app.route('/download_image')
def download_image_route():
    url = request.args.get('url')
    title = request.args.get('title', '')
    if not url:
        return '', 400
    local_url = download_image(url, title)
    return local_url

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
    app.run(debug=True)
