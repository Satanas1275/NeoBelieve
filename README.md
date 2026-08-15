# 🎧 NeoBelieve

Appli de musique en streaming **gratuite et sans pub**. Compte `rocknite-studio`,
remote control et partage arrivent dans une phase ultérieure — cette branche `main`
se concentre sur un **client Android v1 fonctionnel**.

> L'ancien prototype web (Flask + yt-dlp) est archivé sur la branche `legacy-web-prototype`.

## 🖥️ Plateformes

- 📱 **Android** — en cours (ce repo)
- 💻 **PC (Rust)** — prévu plus tard
- ☁️ **Backend / comptes rocknite-studio / remote** — prévu plus tard

## ✨ Scope du v1 Android

- 🔎 Recherche de titres (via [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor),
  moteur d'extraction YouTube Music sans clé API — même base que ReVanced/InnerTune/ViMusic)
- ▶️ Lecture en streaming via Media3/ExoPlayer, avec notification + lockscreen (MediaSession)
- 🌀 File d'attente : soit une vraie playlist, soit une **radio auto** (on lance un titre seul,
  la suite se peuple automatiquement avec les morceaux liés — comme sur YouTube Music)
- 📥 Téléchargement offline (WorkManager, stockage privé de l'app)
- 🎨 Thème clair/sombre, accent turquoise
- ⚡ Pensé pour rester fluide sur du matériel limité (LazyColumn, pas de dynamic color,
  cache Coil, pas de recompositions inutiles)

## 🏗️ Architecture

```
app/src/main/java/com/satanas1275/neobelieve/
├── data/
│   ├── model/        Track, QueueSource
│   ├── extractor/     MusicExtractor (NewPipeExtractor) + OkHttpDownloader
│   ├── local/         Room (downloads, historique)
│   └── repository/    MusicRepository (point d'entrée unique pour l'UI)
├── playback/          PlaybackService (MediaSessionService) + PlayerController
├── download/          DownloadTrackWorker (WorkManager)
└── ui/                MainViewModel + écrans Compose (search / player / library)
```

## ⚠️ À tester / affiner sur device

- Les content filters YouTube Music de NewPipeExtractor (`music_songs`) peuvent bouger
  d'une version à l'autre de la lib — à vérifier au premier build réel.
- La résolution de flux audio pour toute la queue n'est faite que sur les 5 premiers
  titres au lancement (pour ne pas bloquer le `play`) — à améliorer avec une résolution
  à la volée quand on approche de la fin de la queue chargée.
- Icône de lancement = placeholder turquoise, à remplacer par un vrai logo.

## 🚧 Roadmap

- [x] v1 Android : recherche, lecture, radio auto, download offline
- [ ] Backend Rust (comptes rocknite-studio, sync remote)
- [ ] Client PC (Rust)
- [ ] Bouton "partager" (lien profond `neobelieve://track/...`)

## 📜 Licence

CC BY-NC-SA 4.0 — voir [LICENSE.md](LICENSE.md). Pas de revente, attribution obligatoire.

## 🚀 Ouvrir le projet

Le wrapper Gradle n'est pas commit (jar binaire). Ouvre simplement le dossier dans
**Android Studio** (Ladybug ou plus récent) : il proposera de générer le wrapper
et de synchroniser automatiquement au premier lancement.

## 🖤 Auteur

Développé par **Satanas1275**.
