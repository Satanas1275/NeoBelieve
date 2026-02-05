let devicesOpen, devicesOverlay, devicesClose, deviceInput, deviceScan, devicesList, deviceActions, deviceName, deviceType, deviceRemote, remoteRefresh, remoteAddCurrent, remotePlaylist;
let remoteMusicInfo, remoteMusicTitle, remoteMusicTime; // New UI references
let remotePollingInterval = null;
let remotePlaylistData = [];



let devices = [];
window.activeDevice = null;


function formatTime(seconds) {
    if (!seconds || isNaN(seconds)) return "0:00";
    const m = Math.floor(seconds / 60);
    const s = Math.floor(seconds % 60);
    return `${m}:${s < 10 ? '0' : ''}${s}`;
}

function stopRemotePolling() {
    if (remotePollingInterval) {
        clearInterval(remotePollingInterval);
        remotePollingInterval = null;
    }
    if (remoteMusicInfo) remoteMusicInfo.classList.add("hidden");
}

function startRemotePolling() {
    stopRemotePolling();
    if (!activeDevice) return;

    const poll = async () => {
        try {
            const controller = new AbortController();
            const id = setTimeout(() => controller.abort(), 2000);
            const resp = await fetch(`http://${activeDevice.host}/api/music/sync`, { signal: controller.signal });
            clearTimeout(id);
            const json = await resp.json();

            if (json.ok && json.data && json.data.id && json.data.status === "playing") {
                updateRemoteMusicUI(json.data);
            } else {
                if (remoteMusicInfo) remoteMusicInfo.classList.add("hidden");
            }
        } catch (e) {
            // console.warn("Remote sync failed:", e);
            if (remoteMusicInfo) remoteMusicInfo.classList.add("hidden");
        }
    };

    poll(); // Initial call
    remotePollingInterval = setInterval(poll, 1000);
}

function updateRemoteMusicUI(data) {
    if (remoteMusicInfo) remoteMusicInfo.classList.remove("hidden");

    // Lookup metadata from cached playlist
    // We try to match by ID. The sync 'id' might be the file path or a generated ID.
    // The playlist items usually have 'id' or 'url'.
    const item = remotePlaylistData.find(i => i.id === data.id || i.url === data.id) || {};

    // Determine Title
    // Use data.id as fallback, but prefer item.title
    // If data.id looks like a path/url, we might want to clean it up if no title found?
    // For now, adhere to "item.title || item.id || data.id"
    const title = item.title || item['Track Name'] || data.id || "Unknown";

    if (remoteMusicTitle) remoteMusicTitle.textContent = title;

    // Determine Cover
    let coverUrl = item.image || item.thumbnail || item['Album Image URL'] || "";
    if (coverUrl && coverUrl.startsWith("/")) {
        // Prepend remote host
        coverUrl = `http://${activeDevice.host}${coverUrl}`;
    } else if (!coverUrl) {
        coverUrl = "/static/img/default_cover.jpg";
    }

    // Main Player Bar Hijack
    const trackInfo = document.getElementById("track-info");
    const currentTimeEl = document.getElementById("current-time");
    const durationEl = document.getElementById("duration");
    const progress = document.getElementById("progress");
    const playBtn = document.getElementById("play");
    const playImg = playBtn ? playBtn.querySelector("img") : null;
    const trackCover = document.getElementById("track-cover");

    if (trackInfo) trackInfo.textContent = title;
    if (trackCover) trackCover.src = coverUrl;

    const current = formatTime(data.currentTime);
    const total = formatTime(data.duration);

    if (currentTimeEl) currentTimeEl.textContent = current;
    if (durationEl) durationEl.textContent = total;
    if (remoteMusicTime) remoteMusicTime.textContent = `${current} / ${total}`;

    if (progress && data.duration > 0) {
        progress.value = (data.currentTime / data.duration) * 100;
    }

    if (playImg) {
        if (data.status === "playing") {
            playImg.src = "/static/img/pause.png";
        } else {
            playImg.src = "/static/img/play.png";
        }
    }
}





function loadDevices() {
    try {
        devices = JSON.parse(localStorage.getItem("nbDevices") || "[]");
    } catch (e) {
        devices = [];
    }
}

function saveDevices() {
    localStorage.setItem("nbDevices", JSON.stringify(devices));
}

function renderDevices() {
    if (!devicesList) return;
    devicesList.innerHTML = "";

    // Add "Ce PC" (Local Device) option
    const localCard = document.createElement("div");
    localCard.className = "device-card" + (!activeDevice ? " active" : "");
    const localMeta = document.createElement("div");
    localMeta.className = "device-meta";
    const localTitle = document.createElement("strong");
    localTitle.textContent = "Ce PC";
    const localType = document.createElement("span");
    localType.className = "status-pill";
    localType.textContent = "Local";
    localMeta.appendChild(localTitle);
    localMeta.appendChild(localType);
    const localBtn = document.createElement("button");
    localBtn.className = "pill-btn ghost";
    localBtn.textContent = "Choisir";
    localBtn.onclick = () => selectDevice(null);
    localCard.appendChild(localMeta);
    localCard.appendChild(localBtn);
    localCard.onclick = () => selectDevice(null);
    devicesList.appendChild(localCard);

    if (devices.length === 0) {
        const p = document.createElement("p");
        p.className = "subtle";
        p.textContent = "Aucun autre appareil tonhub.";
        devicesList.appendChild(p);
        return;
    }
    devices.forEach((device) => {
        const card = document.createElement("div");
        card.className = "device-card" + (activeDevice && activeDevice.host === device.host ? " active" : "");
        const meta = document.createElement("div");
        meta.className = "device-meta";
        const title = document.createElement("strong");
        title.textContent = device.name || device.host;
        const type = document.createElement("span");
        type.className = "status-pill";
        type.textContent = device.device_type || "Unknown";
        meta.appendChild(title);
        meta.appendChild(type);
        const btn = document.createElement("button");
        btn.className = "pill-btn ghost";
        btn.textContent = "Choisir";
        btn.onclick = () => selectDevice(device);
        card.appendChild(meta);
        card.appendChild(btn);
        card.onclick = () => selectDevice(device);
        devicesList.appendChild(card);
    });
}


async function checkDevice(host) {
    const urls = [
        `http://${host}/neobelieve/available`,
        `http://${host}/neobelieve/avaliable`
    ];
    let lastError = null;
    for (const url of urls) {
        try {
            const resp = await fetch(url);
            const data = await resp.json();
            const isAvailable = data.available || data.avaliable;
            if (!data.ok || !isAvailable) {
                throw new Error("Device not available");
            }
            return {
                host,
                device_type: data.device_type || "Unknown",
                name: data.name || host
            };
        } catch (e) {
            lastError = e;
        }
    }
    throw lastError || new Error("Device not available");
}

function setActiveDevice(device) {
    activeDevice = device;
    window.activeDevice = device; // Ensure global visibility
    localStorage.setItem("nbActiveDevice", JSON.stringify(device));

    if (deviceActions) {
        if (device) {
            deviceActions.classList.remove("hidden");
        } else {
            deviceActions.classList.add("hidden");
        }
    }

    if (device) {
        if (deviceName) deviceName.textContent = device.name || device.host;
        if (deviceType) deviceType.textContent = device.device_type || "Unknown";
        if (deviceRemote) {
            if ((device.device_type || "").toLowerCase() === "lumatv") {
                deviceRemote.classList.remove("hidden");
            } else {
                deviceRemote.classList.add("hidden");
            }
        }

        // Pause local player if switching to remote
        const localAudio = document.getElementById("audio-player");
        if (localAudio && !localAudio.paused) {
            localAudio.pause();
        }

        refreshRemotePlaylist();
        startRemotePolling();
    } else {
        // Switch to Local
        stopRemotePolling();
        if (remoteMusicInfo) remoteMusicInfo.classList.add("hidden");

        // Restore local UI if needed...
        // Assuming local player updates itself on timeupdate/play events or page reload.
        // We might want to trigger a UI update from local state here if possible.
        // For now, let's just clear the remote overrides (which stopRemotePolling does for the info box)
        // But the main player override stays until local plays?
        // Actually updateRemoteMusicUI hijacked the elements. We should probably reset them.
        const trackInfo = document.getElementById("track-info");
        if (trackInfo) trackInfo.textContent = "Local Player Ready";
        // Or real local state if we had it handy. 
        // Since player.js loads from localStorage on load, maybe we can reload that?
        // But simply setting text to "Ready" is enough feedback.

        const playBtn = document.getElementById("play");
        const playImg = playBtn ? playBtn.querySelector("img") : null;
        if (playImg) playImg.src = "/static/img/play.png";
    }

    renderDevices();
}


function selectDevice(device) {
    setActiveDevice(device);
}

async function refreshRemotePlaylist() {
    if (!activeDevice || !remotePlaylist) return;
    remotePlaylist.innerHTML = "<span class=\"subtle\">Chargement...</span>";
    try {
        const resp = await fetch(`http://${activeDevice.host}/api/playlist`);
        const data = await resp.json();
        if (!data.ok) throw new Error("Playlist error");
        remotePlaylistData = data.items || [];
        remotePlaylist.innerHTML = "";

        data.items.forEach((item) => {
            const row = document.createElement("div");
            row.className = "remote-item";
            const label = document.createElement("span");
            label.textContent = item.title || item.id || "Sans titre";
            const remove = document.createElement("button");
            remove.textContent = "Retirer";
            remove.onclick = async () => {
                await fetch(`http://${activeDevice.host}/api/playlist/remove`, {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ id: item.id })
                });
                refreshRemotePlaylist();
            };
            row.appendChild(label);
            row.appendChild(remove);
            remotePlaylist.appendChild(row);
        });
        if (data.items.length === 0) {
            remotePlaylist.innerHTML = "<span class=\"subtle\">Playlist vide.</span>";
        }
    } catch (e) {
        remotePlaylist.innerHTML = "<span class=\"subtle\">Impossible de charger la playlist.</span>";
    }
}

async function addCurrentToRemote() {
    if (!activeDevice) return;
    const audio = document.getElementById("audio-player");
    const info = document.getElementById("track-info");
    if (!audio || !info || !audio.src) {
        alert("Aucun titre en cours.");
        return;
    }
    const title = info.textContent || "Sans titre";
    const id = audio.src;
    const url = audio.src;
    await fetch(`http://${activeDevice.host}/api/playlist/add`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ id, title, url })
    });
    refreshRemotePlaylist();
}

async function sendRemoteAction(action) {
    if (!activeDevice) return;
    await fetch(`http://${activeDevice.host}/api/remote`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action })
    });
}

function restoreActiveDevice() {
    try {
        const stored = JSON.parse(localStorage.getItem("nbActiveDevice") || "null");
        if (stored) setActiveDevice(stored);
    } catch (e) { }
}

document.addEventListener("DOMContentLoaded", () => {
    // Initialiser les références DOM
    devicesOpen = document.getElementById("devices-open");
    devicesOverlay = document.getElementById("devices-overlay");
    devicesClose = document.getElementById("devices-close");
    deviceInput = document.getElementById("device-input");
    deviceScan = document.getElementById("device-scan");
    devicesList = document.getElementById("devices-list");
    deviceActions = document.getElementById("device-actions");
    deviceName = document.getElementById("device-name");
    deviceType = document.getElementById("device-type");
    deviceRemote = document.getElementById("device-remote");
    remoteRefresh = document.getElementById("remote-refresh");
    remoteAddCurrent = document.getElementById("remote-add-current");
    remotePlaylist = document.getElementById("remote-playlist");

    remoteMusicInfo = document.getElementById("remote-music-info");
    remoteMusicTitle = document.getElementById("remote-music-title");
    remoteMusicTime = document.getElementById("remote-music-time");

    loadDevices();
    renderDevices();
    restoreActiveDevice();

    // Attacher les gestionnaires d'événements après le DOM
    if (devicesOpen) {
        devicesOpen.onclick = () => {
            if (devicesOverlay) devicesOverlay.classList.remove("hidden");
        };
    }

    if (devicesClose) {
        devicesClose.onclick = () => {
            if (devicesOverlay) devicesOverlay.classList.add("hidden");
        };
    }

    if (deviceScan) {
        deviceScan.onclick = async () => {
            const host = (deviceInput ? deviceInput.value.trim() : "").replace("http://", "").replace("https://", "");
            if (!host) return;
            deviceScan.disabled = true;
            deviceScan.textContent = "Recherche...";
            try {
                const device = await checkDevice(host);
                devices = devices.filter(d => d.host !== device.host);
                devices.push(device);
                saveDevices();
                setActiveDevice(device);
            } catch (e) {
                alert("Appareil introuvable ou indisponible.");
            } finally {
                deviceScan.disabled = false;
                deviceScan.textContent = "Ajouter";
                if (deviceInput) deviceInput.value = "";
                renderDevices();
            }
        };
    }

    if (remoteRefresh) {
        remoteRefresh.onclick = refreshRemotePlaylist;
    }

    if (remoteAddCurrent) {
        remoteAddCurrent.onclick = addCurrentToRemote;
    }

    if (deviceRemote) {
        deviceRemote.querySelectorAll("button[data-action]").forEach((btn) => {
            btn.onclick = () => sendRemoteAction(btn.dataset.action);
        });
    }
});
