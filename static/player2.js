const audio = document.getElementById("audio-player");
const playBtn = document.getElementById("play");
const progress = document.getElementById("progress");
const volume = document.getElementById("volume");
const currentTimeEl = document.getElementById("current-time");
const durationEl = document.getElementById("duration");
const trackInfo = document.getElementById("track-info");
// const trackCover = document.getElementById("track-cover");
const playImg = playBtn.querySelector("img");
const repeatBtn = document.getElementById("repeat");
const shuffleBtn = document.getElementById("shuffle");
const nextBtn = document.getElementById("next");
const prevBtn = document.getElementById("prev");
const queueBtn = document.getElementById("queue-btn");
const repeatImg = repeatBtn ? repeatBtn.querySelector("img") : null;
const shuffleImg = shuffleBtn ? shuffleBtn.querySelector("img") : null;

// Queue modal elements (initialized lazily)
let queueModal, queueList, closeQueue, addQueueInput, addQueueBtn;

let isPlaying = false;
window.repeatMode = 0; // 0: off, 1: single track, 2: playlist
window.shuffle = false;

playBtn.onclick = () => {
    if (window.activeDevice) {
        sendRemoteAction("play_pause");
        // Optimistically update icon? 
        // We rely on polling for the icon state, but instant feedback is nice.
        // Let's toggle it optimistically or just wait for poll. 
        // Wait for poll is safer.
        return;
    }
    if (audio.paused) audio.play();
    else audio.pause();
};


audio.onplay = () => { playImg.src = "/static/img/pause.png"; isPlaying = true; };
audio.onpause = () => { playImg.src = "/static/img/play.png"; isPlaying = false; };

audio.onended = () => {
    if (window.repeatMode === 1) {
        // Single repeat handled by audio.loop
        return;
    }
    if (window.queue.length > 0 && window.currentQueueIndex >= 0) {
        window.currentQueueIndex++;
        if (window.currentQueueIndex >= window.queue.length) {
            if (window.repeatMode === 2) {
                window.currentQueueIndex = 0;
            } else {
                return;
            }
        }
        localStorage.setItem('currentQueueIndex', window.currentQueueIndex);
        playCSVTrack(window.queue[window.currentQueueIndex]);
    }
};

audio.ontimeupdate = () => {
    if (window.activeDevice) return; // Let remote sync handle UI
    progress.value = (audio.currentTime / audio.duration) * 100 || 0;
    currentTimeEl.textContent = formatTime(audio.currentTime);
    durationEl.textContent = formatTime(audio.duration);
};


progress.oninput = () => {
    audio.currentTime = (progress.value / 100) * audio.duration;
};

volume.oninput = () => {
    audio.volume = volume.value;
    localStorage.setItem("volume", volume.value);
};

document.getElementById("repeat").onclick = () => {
    window.repeatMode = (window.repeatMode + 1) % 3;
    if (window.repeatMode === 0) {
        audio.loop = false;
        if (repeatImg) repeatImg.src = "/static/img/repeat.png";
    } else if (window.repeatMode === 1) {
        audio.loop = true;
        if (repeatImg) repeatImg.src = "/static/img/repeat_on_one.png";
    } else if (window.repeatMode === 2) {
        audio.loop = false;
        if (repeatImg) repeatImg.src = "/static/img/repeat_on.png";
    }
};

document.getElementById("shuffle").onclick = () => {
    window.shuffle = !window.shuffle;
    if (shuffleImg) shuffleImg.src = window.shuffle ? "/static/img/shuffle_on.png" : "/static/img/shuffle.png";
};

if (nextBtn) {
    nextBtn.onclick = () => {
        if (window.activeDevice) {
            sendRemoteAction("next");
            return;
        }
        if (window.queue.length > 0) {
            window.currentQueueIndex = (window.currentQueueIndex + 1) % window.queue.length;
            localStorage.setItem('currentQueueIndex', window.currentQueueIndex);
            playCSVTrack(window.queue[window.currentQueueIndex]);
        }
    };
}

if (prevBtn) {
    prevBtn.onclick = () => {
        if (window.activeDevice) {
            sendRemoteAction("previous");
            return;
        }
        if (window.queue.length > 0) {
            window.currentQueueIndex = (window.currentQueueIndex - 1 + window.queue.length) % window.queue.length;
            localStorage.setItem('currentQueueIndex', window.currentQueueIndex);
            playCSVTrack(window.queue[window.currentQueueIndex]);
        }
    };
}


if (queueBtn) {
    queueBtn.onclick = () => {
        if (!queueModal) {
            queueModal = document.getElementById("queue-modal");
            queueList = document.getElementById("queue-list");
            closeQueue = document.getElementById("close-queue");
            addQueueInput = document.getElementById("add-queue-input");
            addQueueBtn = document.getElementById("add-queue-btn");

            if (closeQueue) {
                closeQueue.onclick = () => {
                    if (queueModal) queueModal.style.display = "none";
                };
            }

            if (addQueueBtn) {
                addQueueBtn.onclick = async () => {
                    const query = addQueueInput ? addQueueInput.value.trim() : "";
                    if (!query) return;
                    try {
                        const resp = await fetch(`/search?q=${encodeURIComponent(query)}`);
                        const data = await resp.json();
                        if (data.length > 0) {
                            const track = {
                                'Track Name': data[0].title,
                                'Artist Name(s)': data[0].uploader,
                                url: data[0].url,
                                thumbnail: data[0].thumbnail
                            };
                            window.queue.push(track);
                            window.saveQueue();
                            updateQueueDisplay();
                            if (addQueueInput) addQueueInput.value = "";
                        } else {
                            alert("Aucun résultat trouvé.");
                        }
                    } catch (e) {
                        console.error("Error adding to queue:", e);
                        alert("Erreur lors de l'ajout.");
                    }
                };
            }
        }
        updateQueueDisplay();
        if (queueModal) queueModal.style.display = "block";
    };
}

function updateQueueDisplay() {
    if (!queueList) return;
    queueList.innerHTML = "";
    window.queue.forEach((track, index) => {
        const li = document.createElement("li");
        li.style.display = "flex";
        li.style.justifyContent = "space-between";
        li.style.alignItems = "center";
        li.style.padding = "5px 0";
        li.style.borderBottom = "1px solid #ccc";

        const title = document.createElement("span");
        title.textContent = track['Track Name'] || track.title || "Unknown";
        if (index === window.currentQueueIndex) {
            title.style.fontWeight = "bold";
            title.style.color = "blue";
        }
        li.appendChild(title);

        const removeBtn = document.createElement("button");
        removeBtn.textContent = "Retirer";
        removeBtn.style.marginLeft = "10px";
        removeBtn.onclick = () => {
            window.queue.splice(index, 1);
            if (index < window.currentQueueIndex) {
                window.currentQueueIndex--;
            } else if (index === window.currentQueueIndex) {
                if (window.queue.length > 0) {
                    window.currentQueueIndex = Math.min(window.currentQueueIndex, window.queue.length - 1);
                    playCSVTrack(window.queue[window.currentQueueIndex]);
                } else {
                    window.currentQueueIndex = -1;
                }
            }
            localStorage.setItem('currentQueueIndex', window.currentQueueIndex);
            window.saveQueue();
            updateQueueDisplay();
        };
        li.appendChild(removeBtn);

        queueList.appendChild(li);
    });
}

// Récupération du track depuis le localStorage
let currentTrack = localStorage.getItem("currentTrack");
let currentTitle = localStorage.getItem("currentTitle");
let currentImage = localStorage.getItem("currentImage");

if (currentTrack) {
    let displayName = currentTitle;

    if (!displayName) {
        // Fallback: Extract filename from path if title not saved
        let pathParts = currentTrack.split('/');
        let filename = pathParts[pathParts.length - 1];
        let trackName = filename.replace('.mp3', '');
        displayName = decodeURIComponent(trackName);

        // Try to remove ID suffix if present (loose regex)
        // e.g. "Title-NTpbbQUBbuo" -> "Title"
        // Also handle UUIDs (36 chars) for migrated files
        displayName = displayName.replace(/-([a-zA-Z0-9_-]{11}|[a-f0-9-]{36})$/, "");
    }

    // Mettre à jour le track info et le cover
    trackInfo.textContent = displayName || "Unknown";
    trackCover.src = currentImage || `/cover/${encodeURIComponent(displayName)}.jpg`;

    // Charger le morceau dans le player
    audio.src = currentTrack;
}

function formatTime(sec) {
    if (!sec) return "0:00";
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60).toString().padStart(2, "0");
    return `${m}:${s}`;
}

// Appel pour lancer une musique
async function startAudio(path, title, coverUrl, original_url = "") {
    if (window.activeDevice) {
        // Send remote play command
        try {
            await fetch(`http://${window.activeDevice.host}/api/remote/play`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    url: path,
                    title: title,
                    image: coverUrl,
                    original_url: original_url
                })
            });
            // Update local info optimistically?
            trackInfo.textContent = title + " (Remote)";
        } catch (e) {
            console.error("Remote play failed", e);
            alert("Erreur de lecture à distance");
        }
        return;
    }

    // Normal local play
    audio.src = path;
    trackInfo.textContent = title;
    trackCover.src = coverUrl || "/static/img/default_cover.jpg";
    audio.play();
}
