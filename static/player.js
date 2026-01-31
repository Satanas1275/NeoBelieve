const audioPlayer = document.getElementById('audio-player');
const playerInfo = document.getElementById('player-info');
const searchBtn = document.getElementById('search-btn');
const searchInput = document.getElementById('search-input');
const resultsDiv = document.getElementById('search-results');

// Jouer un morceau (cache + music)
async function playTrack(url, title, toRecent=true, image="") {
    if(!title) return;

    // Download image if web
    if(image && !image.startsWith("/")){
        try {
            const resp = await fetch(`/download_image?url=${encodeURIComponent(image)}&title=${encodeURIComponent(title)}`);
            image = await resp.text();
        } catch(e){
            console.error("Error downloading image:", e);
        }
    }

    const cachePath = `/music_cache/${encodeURIComponent(title)}.mp3`;
    const musicPath = `/music/${encodeURIComponent(title)}.mp3`;

    // Vérifie si déjà en music
    let r = await fetch(musicPath,{method:"HEAD"});
    if(r.ok){
        audioPlayer.src = musicPath;
        audioPlayer.play();
        playerInfo.textContent = title;
        if(toRecent){
            fetch("/update_recent", {
                method:"POST",
                headers:{"Content-Type":"application/json"},
                body: JSON.stringify({type:"track", title:title, id:title, image:image, url:musicPath})
            });
        }
        return;
    }

    // Vérifie si déjà en cache
    r = await fetch(cachePath,{method:"HEAD"});
    if(r.ok){
        audioPlayer.src = cachePath;
        audioPlayer.play();
        playerInfo.textContent = title;
        if(toRecent){
            fetch("/update_recent", {
                method:"POST",
                headers:{"Content-Type":"application/json"},
                body: JSON.stringify({type:"track", title:title, id:title, image:image, url:cachePath})
            });
        }
        return;
    }

    // Sinon download depuis YouTube
    if(url && !url.startsWith("/")){  // <-- ne jamais envoyer un chemin local à yt_dlp
        try {
            const resp = await fetch(`/download?url=${encodeURIComponent(url)}&title=${encodeURIComponent(title)}&cache=1`);
            const data = await resp.json();
            if(data.path){
                audioPlayer.src = data.path;
                audioPlayer.play();
                playerInfo.textContent = title;
                if(toRecent){
                    fetch("/update_recent", {
                        method:"POST",
                        headers:{"Content-Type":"application/json"},
                        body: JSON.stringify({type:"track", title:title, id:title, image:image, url:data.path})
                    });
                }
            } else {
                alert("Erreur téléchargement : "+(data.error||"inconnu"));
            }
        } catch(e){
            console.error(e);
            alert("Impossible de télécharger la musique !");
        }
    } else {
        alert("Fichier local introuvable et URL YouTube manquante !");
    }
}

// Jouer un morceau CSV avec recherche auto
async function playCSVTrack(track){
    const resp = await fetch("/get_track_path",{
        method:"POST",
        headers:{"Content-Type":"application/json"},
        body: JSON.stringify(track)
    });
    const data = await resp.json();
    if(data.path) startAudio(data.path, track['Track Name'], true, data.image || track['Album Image URL']);
    else alert("Impossible de trouver cette musique.");
}

// Play playlist depuis un index
function playPlaylist(tracks, startIndex=0){
    let index=startIndex;

    async function playNext(){
        if(index >= tracks.length) return;

        const t = tracks[index];
        await playCSVTrack(t);

        audioPlayer.onended = ()=>{
            index++;
            playNext();
        };
    }

    playNext();
}

// Recherche YouTube
searchBtn.onclick = ()=>{
    const q=searchInput.value;
    resultsDiv.innerHTML="Recherche...";
    fetch(`/search?q=${encodeURIComponent(q)}`).then(r=>r.json()).then(data=>{
        resultsDiv.innerHTML="";
        data.forEach(track=>{
            const div=document.createElement('div');
            div.className='track-item';
            div.innerHTML=`<strong>${track.title}</strong> – ${track.uploader} 
                <button onclick='playTrack("${track.url}","${track.title}",true,"${track.thumbnail}")'>▶️</button>
                <button onclick='downloadTrack("${track.url}","${track.title}")'>⬇️</button>`;
            resultsDiv.appendChild(div);
        });
    });
}

// Télécharger permanent
async function downloadTrack(url, title) {
    if (!title) return;
    if (!url) {
        // Rechercher sur YouTube
        const query = title;
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
        alert("Musique téléchargée sur le serveur dans /music/");
    }
}

// Start audio helper
function startAudio(path,title,toRecent=true,image=""){
    audioPlayer.src=path;
    audioPlayer.play();
    playerInfo.textContent = title;
    if(toRecent){
        fetch("/update_recent", {
            method:"POST",
            headers:{"Content-Type":"application/json"},
            body: JSON.stringify({type:"track", title:title, id:title, image:image, url:path})
        });
    }
}

window.addEventListener("load", ()=>{
    const player = document.getElementById("audio-player");
    const lastTrack = localStorage.getItem("currentTrack");
    const lastTime = localStorage.getItem("currentTime") || 0;
    if(lastTrack){
        player.src = lastTrack;
        player.currentTime = lastTime;
        player.play();
    }

    player.addEventListener("timeupdate",()=>{
        localStorage.setItem("currentTime", player.currentTime);
    });

    player.addEventListener("play",()=>{
        localStorage.setItem("currentTrack", player.src);
    });
});
