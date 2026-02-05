const audioPlayer = document.getElementById('audio-player');
const playerInfo = document.getElementById('track-info');
const trackCover = document.getElementById('track-cover');
const searchBtn = document.getElementById('search-btn');
const searchInput = document.getElementById('search-input');
const resultsDiv = document.getElementById('search-results');

// Global queue variables
window.queue = [];
window.currentQueueIndex = -1;

// Load queue from localStorage
function loadQueue() {
    const stored = localStorage.getItem('musicQueue');
    if (stored) {
        window.queue = JSON.parse(stored);
    }
    window.currentQueueIndex = parseInt(localStorage.getItem('currentQueueIndex')) || -1;
}

// Save queue to localStorage
function saveQueue() {
    localStorage.setItem('musicQueue', JSON.stringify(window.queue));
}
window.saveQueue = saveQueue;

// Shuffle array utility
function shuffleArray(array) {
    for (let i = array.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [array[i], array[j]] = [array[j], array[i]];
    }
}

// Load recent tracks into queue for single plays
async function loadRecentToQueue(currentTitle) {
    try {
        const resp = await fetch('/recent');
        const recent = await resp.json();
        window.queue = recent.slice(0, 10);
        window.currentQueueIndex = window.queue.findIndex(track => track.title === currentTitle);
        if (window.currentQueueIndex === -1) window.currentQueueIndex = 0;
        localStorage.setItem('currentQueueIndex', window.currentQueueIndex);
        saveQueue();
    } catch (e) {
        console.error("Error loading recent to queue:", e);
    }
}

// Jouer un morceau (cache + music)
async function playTrack(url, title, toRecent = true, image = "", original_url = "") {
    if (!title) return;

    // Download image if web
    if (image && !image.startsWith("/")) {
        try {
            const resp = await fetch(`/download_image?url=${encodeURIComponent(image)}&title=${encodeURIComponent(title)}`);
            image = await resp.text();
        } catch (e) {
            console.error("Error downloading image:", e);
        }
    }

    const cachePath = `/music_cache/${encodeURIComponent(title)}.mp3`;
    const musicPath = `/music/${encodeURIComponent(title)}.mp3`;
    // Also try checking the exact URL if it is a path
    const directPath = url && url.startsWith("/") ? url : null;

    // Helper to play and update
    const playAndLog = (src) => {
        audioPlayer.src = src;
        audioPlayer.play();
        playerInfo.textContent = title;
        if (image && image.startsWith("http")) {
            trackCover.src = image;
        } else {
            trackCover.src = image || `/cover/${encodeURIComponent(title)}.jpg`;
        }
        loadRecentToQueue(title);
        if (toRecent) {
            fetch("/update_recent", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ type: "track", title: title, id: title, image: image, url: src, original_url: original_url })
            });
        }
    };

    if (directPath) {
        // Validation handled by browser/server, but we can HEAD
        playAndLog(directPath);
        return;
    }

    // Vérifie si déjà en music
    let r = await fetch(musicPath, { method: "HEAD" });
    if (r.ok) {
        playAndLog(musicPath);
        return;
    }

    // Vérifie si déjà en cache
    r = await fetch(cachePath, { method: "HEAD" });
    if (r.ok) {
        playAndLog(cachePath);
        return;
    }

    // Sinon download depuis YouTube
    if (url && !url.startsWith("/")) {  // <-- ne jamais envoyer un chemin local à yt_dlp
        try {
            const resp = await fetch(`/download?url=${encodeURIComponent(url)}&title=${encodeURIComponent(title)}&cache=1`);
            const data = await resp.json();
            if (data.path) {
                // For downloaded tracks from search, url IS the original url
                const finalOriginalUrl = original_url || url;
                playAndLog(data.path);
            } else {
                alert("Erreur téléchargement : " + (data.error || "inconnu"));
            }
        } catch (e) {
            console.error(e);
            alert("Impossible de télécharger la musique !");
        }
    } else {
        alert("Fichier local introuvable et URL YouTube manquante !");
    }
}

// Jouer un morceau CSV avec recherche auto
async function playCSVTrack(track) {
    const resp = await fetch("/get_track_path", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(track)
    });
    const data = await resp.json();
    if (data.path) startAudio(data.path, track['Track Name'], true, data.image || track['Album Image URL'], data.original_url);
    else alert("Impossible de trouver cette musique.");
}

// Play playlist depuis un index
function playPlaylist(tracks, startIndex = 0) {
    window.queue = [...tracks];
    if (window.shuffle) {
        shuffleArray(window.queue);
        window.currentQueueIndex = 0; // After shuffle, start from beginning
    } else {
        window.currentQueueIndex = startIndex;
    }
    localStorage.setItem('currentQueueIndex', window.currentQueueIndex);
    saveQueue();
    playNextInQueue();
}

function playNextInQueue() {
    if (window.currentQueueIndex >= 0 && window.currentQueueIndex < window.queue.length) {
        playCSVTrack(window.queue[window.currentQueueIndex]);
        audioPlayer.onended = () => {
            if (window.repeatMode === 1) {
                // Single repeat handled by audio.loop
                return;
            }
            window.currentQueueIndex++;
            if (window.currentQueueIndex >= window.queue.length) {
                if (window.repeatMode === 2) {
                    window.currentQueueIndex = 0;
                } else {
                    return;
                }
            }
            playNextInQueue();
        };
    }
}

// Recherche YouTube
if (searchBtn) {
    searchBtn.onclick = () => {
        const q = searchInput.value;
        resultsDiv.innerHTML = "Recherche...";
        fetch(`/search?q=${encodeURIComponent(q)}`).then(r => r.json()).then(data => {
            resultsDiv.innerHTML = "";
            data.forEach(track => {
                const div = document.createElement('div');
                div.className = 'track-item';
                div.innerHTML = `<strong>${track.title}</strong> – ${track.uploader} 
                    <button onclick='playTrack("${track.url}","${track.title}",true,"${track.thumbnail}")'>▶️</button>
                    <button onclick='downloadTrack("${track.url}","${track.title}")'>⬇️</button>`;
                resultsDiv.appendChild(div);
            });
        });
    };
}

// Télécharger permanent
async function downloadTrack(url, title, artist = "") {
    if (!title) return;
    if (!url) {
        // Rechercher sur YouTube
        const query = artist ? `${title} ${artist}` : title;
        const resp = await fetch(`/search?q=${encodeURIComponent(query)}`);
        const data = await resp.json();
        if (data.length > 0) {
            url = data[0].url;
            // title reste le titre original (CSV)
        } else {
            alert("Aucun résultat trouvé pour le téléchargement.");
            return;
        }
    }
    const resp2 = await fetch(`/download?url=${encodeURIComponent(url)}&title=${encodeURIComponent(title)}&cache=0`);
    const data2 = await resp2.json();
    if (data2.error) {
        alert("Erreur de téléchargement : " + data2.error);
    } else {
        console.log("Musique téléchargée sur le serveur dans /music/");
    }
}

// Start audio helper
// Start Audio Helper
function startAudio(path, title, toRecent = true, image = "", original_url = "") {
    audioPlayer.src = path;
    audioPlayer.play();
    playerInfo.textContent = title;
    if (image && image.startsWith("http")) {
        trackCover.src = image;
    } else {
        trackCover.src = `/cover/${encodeURIComponent(title)}.jpg`;
    }
    if (toRecent) {
        console.log("Updating recent with:", { type: "track", title: title, id: title, image: image, url: path, original_url: original_url });
        fetch("/update_recent", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ type: "track", title: title, id: title, image: image, url: path, original_url: original_url })
        }).then(response => {
            if (!response.ok) {
                console.error("Failed to update recent:", response.status);
            } else {
                console.log("Recent updated successfully");
            }
        }).catch(error => {
            console.error("Error updating recent:", error);
        });
    }
}

window.addEventListener("load", () => {
    loadQueue();
    const player = document.getElementById("audio-player");
    const lastTrack = localStorage.getItem("currentTrack");
    const lastTime = localStorage.getItem("currentTime") || 0;
    const savedVolume = localStorage.getItem("volume") || 1;
    player.volume = savedVolume;
    const volumeEl = document.getElementById("volume");
    if (volumeEl) volumeEl.value = savedVolume;

    // Check if we have an active remote device
    const activeDevice = localStorage.getItem("activeDevice");
    const playerState = localStorage.getItem("playerState");

    if (lastTrack) {
        player.src = lastTrack;
        player.currentTime = lastTime;
        // Only auto-play LOCALLY if NO remote device is active AND it was playing
        if (!activeDevice && playerState === "playing") {
            player.play();
        }
    }

    player.addEventListener("timeupdate", () => {
        localStorage.setItem("currentTime", player.currentTime);
    });

    player.addEventListener("play", () => {
        localStorage.setItem("playerState", "playing");
        localStorage.setItem("currentTrack", player.src);
        localStorage.setItem("currentTitle", playerInfo.textContent); // Save displayed title
        localStorage.setItem("currentImage", trackCover.src); // Save displayed image

        // --- Smart Pre-caching ---
        // Preload next track if in queue
        if (window.queue && window.queue.length > 0 && window.currentQueueIndex >= 0) {
            let nextIndex = window.currentQueueIndex + 1;
            if (nextIndex >= window.queue.length && window.repeatMode === 2) {
                nextIndex = 0;
            }

            if (nextIndex < window.queue.length) {
                const nextTrack = window.queue[nextIndex];
                console.log("Pre-caching next:", nextTrack['Track Name'] || nextTrack.title);

                // Use get_track_path to force optional download/cache
                // We don't need the result, just the server side action
                fetch("/get_track_path", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(nextTrack)
                }).catch(e => console.log("Pre-cache error", e));
            }
        }
    });

    player.addEventListener("pause", () => {
        localStorage.setItem("playerState", "paused");
    });

    // --- Remote Control Polling (Target Logic) ---
    setInterval(async () => {
        try {
            // Report Status
            const status = {
                status: player.paused ? "paused" : "playing",
                currentTime: player.currentTime,
                duration: player.duration || 0,
                id: player.src || "",
                title: playerInfo.textContent || ""
            };
            await fetch('/api/status/update', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(status)
            });

            // Poll commands
            const resp = await fetch('/api/commands');
            const data = await resp.json();
            if (data.commands) {
                data.commands.forEach(cmd => {
                    console.log("Remote command:", cmd);
                    if (cmd.action === "play_pause") {
                        if (player.paused) player.play(); else player.pause();
                    } else if (cmd.action === "play_track") {
                        const t = cmd.track;
                        // Call global startAudio? or playTrack?
                        // playTrack handles recent log etc.
                        playTrack(t.url, t.title, true, t.image, t.original_url);
                    } else if (cmd.action === "queue_track") {
                        // Add to queue
                        const t = cmd.track;
                        window.queue.push({
                            'Track Name': t.title,
                            'url': t.url,
                            'thumbnail': t.image
                        });
                        saveQueue();
                        // If nothing playing, play?
                        if (player.paused && player.src === "") {
                            playNextInQueue();
                        }
                    } else if (cmd.action === "next") {
                        // Assuming player2.js logic or similar
                        if (window.nextBtn) window.nextBtn.click();
                        else playNextInQueue();
                    } else if (cmd.action === "previous") {
                        if (window.prevBtn) window.prevBtn.click();
                    }
                });
            }
        } catch (e) { /* ignore offline */ }
    }, 1000);
});
