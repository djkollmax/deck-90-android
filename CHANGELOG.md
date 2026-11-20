# Changelog

## 0.1.10

- TEL (Tel.kt): SERVER's second source, the phone's music from MediaStore (IS_MUSIC, apps' and voice folders and
  clips under 20 s left out), grouped by ALBUM_ID with ALBUM_ARTIST, TRACK as disc×1000+n, BITRATE and (Android 14)
  samplerate/bits_per_sample. Both sources implement MusicLib, so the face's lists, the car's (root "tel") and the
  player are shared; TEL ids are "t<n>" inside the same jf: media ids, and Jf.stream/Jf.cover send them to content://
  (the song, its album art). Music.source ("srv"/"tel") is kept; MODE held in SERVER and the face's tabs switch it,
  USB going to the USB mode. READ_MEDIA_AUDIO (READ_EXTERNAL_STORAGE up to Android 12) is asked when TEL is chosen.
- USB: only what the phone plays is listed (no playlists, WMA, MIDI); the phone's own non-music folders (Android,
  WhatsApp, ringtones, recordings…) are skipped; a file the player can't parse or decode is skipped (up to three in a
  row) instead of stopping on NO USB; the picker's toast names the folders Android lets be chosen.
- SERVER: a song's artist is its performers (Artists), the album artist only without them (a compilation said
  "Various Artists"). A song the server answers with an error status is skipped (up to three in a row); a 401 signs
  out (Jf.expired) and asks to sign in again; no answer at all is still NO SERVER.
- The sign-in sheet keeps the user's name; MÚSICA's home reads the library again when it is older than 30 minutes.
- Android backup (backup_rules.xml, data_rules.xml) leaves out the jellyfin preferences, so the token stays on the phone.

## 0.1.9

- SERVER mode (Jellyfin.kt), as deck-90's DM-90 (electron/jellyfin.js): sign-in by user and password
  (/Users/AuthenticateByName); the token is kept in the app's private settings, never the password. Music libraries
  only, read in pages of 2,000 without images and cached (jellyfin-library.json, 6 h). Songs play from
  deck90://jf/<id>, which RadioService.Resolver turns into /Audio/<id>/universal with the token, so the token never
  reaches the car or the notification; the server sends the file as it is or FLAC when the phone can't play it.
- MODE: RADIO → USB → CD → SERVER. Keys as on USB; BAND steps albums in the library's order. The queue, its index
  and position are kept for the car's resumption (Store.jfQueue).
- The face: a sign-in sheet and a MÚSICA window (AL AZAR, RECIENTES, DISCOS, ARTISTAS, LO QUE SUENA, SERVIDOR, live
  search), the display's source SRV, the big keys show the songs playing.
- Android Auto: SERVER · MÚSICA with AL AZAR, RECIENTES, DISCOS and ARTISTAS by letter, an artist's albums, an
  album's songs (asked off the main thread).

## 0.1.8

- CD: a short packet ends a command's data stage (READ TOC answers fewer bytes than the 804 asked). Reading on took
  the drive's status as data, waited 10 s for a status that never came and reset the drive, over and over: the face
  stayed on READING and drives ejected the disc. After a failure the drive is left alone 15 s (Cd.resting).

## 0.1.7

- USB mode (Usb.kt): a pendrive or any folder picked with Android's folder picker (MODE held); folders with music are
  discs (songs in natural name order); the files' tags (ID3, Vorbis, MP4: title, artist, album, cover) replace the
  names once the player reads them (`replaceMediaItem`). Keys: ◀◀ ▶▶, 1–6/7–12, BAND (held: back), SCAN = RAND,
  LOC = REP (disc, song, off), EJECT reads the folder again. Android Auto: a USB folder with the discs.
- CD mode (CdDrive.kt, Cd.kt): a CD/DVD drive on USB OTG, spoken to directly (bulk-only mass storage, SCSI MMC: INQUIRY,
  TEST UNIT READY / REQUEST SENSE, READ TOC, READ CD, READ 10, START STOP UNIT). Audio tracks play as seekable WAV
  streams (cdda://track/N, CdDataSource); MusicBrainz names by disc id (the same as deck-90's cdnames.js) and the Cover
  Art Archive's cover; data discs as ISO 9660/Joliet (cdda://file/N). The drive is polled every 3 s while CD is
  chosen; a disc put in plays. Android Auto: a CD folder.
- MODE: RADIO → USB → CD; the face's slot goes to the CD. The display names the source under PLAY (Deck 90 7.10.22's
  display, patch "source").
- Power: PWR off (or SLEEP's end) shows GOOD BYE, then dims the front (a veil over the photo); HELLO on power on;
  off, only PWR, SEL, BAND and the memories act (Now.power, shared with the service).

## 0.1.6

- The display's LOCAL lights only in the LOCAL band (setRadio({ local }), from Deck 90 7.10.21's display, patch
  "local"), centred under the STEREO/MONO box; dark otherwise.

## 0.1.5

- SCAN and LOC are two keys on the stereo's face (the rocker's halves): SCAN as before, LOC new.
- LOC: a LOCAL band with the 12 most-heard stations near the phone (Radio Browser: within 40 km by coordinates, plus
  the town as region and in the name, merged by name, the best stream of each kept). Refreshed when it is more than
  12 hours old or the phone is more than 25 km away. LOC tapped goes to LOCAL and back to the band before it; held,
  the search opens with the whole area's list (also its CERCA key).
- Android Auto's root gets RADIOS LOCALES (band:LOCAL), refreshed in the background when stale.
- The WebView keeps localStorage (domStorageEnabled): the face's looks, brightness and DISP survive a restart or an
  update.
- Search results skip what the player can't play: HLS, video codecs and TV streams.

## 0.1.4

- DISP's weather where the phone is: its approximate location (ACCESS_COARSE_LOCATION, asked for the first time DISP
  shows the temperature), rounded to about a kilometre before it goes to Open-Meteo, every 15 minutes so it follows a
  trip; the place's name from Android's geocoder; the last place kept for when location is off. No fixed place left.
- The release job writes every published version's notes from NOVEDADES.md (the first four had none).
- The display synced from Deck 90 7.10.19 (patch "steady").

## 0.1.3

- The stereo's meters no longer drop for an instant when the line under the tuning changes (every 4 s, between the
  song and the station): the face called setPlayback(false) before every update, which zeroes the meters, and
  setRadio, which redraws the whole display. It now sets the mode once and tells the display only what changed: a
  new line rewrites that line, a new quality or SLEEP the footer, and only a new station or band the whole display.
- A new station drops the meter readings still queued for the old one's discarded sound.
- Out of sight (another app, the screen off) the face stops drawing and polling; the radio plays on in the service.
- The build's actions on their Node 24 versions (checkout v5, setup-java v5, upload-artifact v6, download-artifact v7).

## 0.1.2

- The meters move with the sound that is heard, not ahead of it: the tap sees the sound when it goes into the audio
  buffer (a quarter of a second or more early; more over Bluetooth), so Levels measures in ~12 ms blocks, stamps each
  with when it is heard (the renderer's buffer time minus its playing position, TimedAudioRenderer) and the page gets
  the block being heard. The audio thread reuses its buffers (no garbage 40 times a second).
- The spectrum at 60 frames a second on a mid-range phone (it dropped to 23 full screen): its peaks glow with a halo
  instead of a canvas blur, and the meters' canvas is drawn at most at 2× the screen's pixels.

## 0.1.1

- SLEEP: PWR held steps 15 → 30 → 60 → 90 minutes → off; the radio fades out over its last 10 seconds and pauses.
  It runs with the player in the service (screen off or app closed too); the minutes left take turns with the
  quality in the display's corner and show under the station. PWR off cancels it.
- SCAN held on the bottom keys (standing up, and the tablet) opens the world radio search too, as SCAN held on the
  stereo's face does.

## 0.1.0

- The radio of Deck 90 (its seven bands and twelve memories each, the PC's stations) as a Media3 library service:
  the phone and Android Auto browse and play the same player. A memory plays with its band as the list (◀◀ ▶▶
  change stations); its stream is found when played (a known address, the last one that played, or Radio
  Browser's best by codec and bitrate) and looked up again once when it fails. ICY titles are the song.
- The phone: the car stereo's front from Deck 90 (the photo, the DP940 display component, the hotspots) in a
  WebView, its keys working the radio; the meters from the player's own sound (a tee before the speakers).
- Standing up the screen is used whole: the station and song, the bands and the twelve memories as big keys (held
  keeps the playing station there), and the transport keys.
- VU and spectrum views (RADIO · VU · ESPECTRO standing up; the display touched lying down, full screen): two VU
  needles from each channel's RMS around the station's own level, with PEAK, and a 28-band analyzer (2048-point
  FFT in Levels.kt, +3 dB per octave, peaks held).
- The phone's screen redesigned as 90s hi-fi gear: brushed metal, molded keys, a recessed glass for the station;
  three VU faces and three spectrum colours, chosen by touching the meter standing up. The spectrum shows 14 wide
  bars (each the louder of two measured bands) with taller segments. The faces and scales are
  drawn once to a cached canvas, each frame only the needles and bars.
- Tablets: the stacked screen either way up (only a phone lying down shows the face alone), the face at most 30% of
  the height when wide, the memories in two rows of six, bigger letters and keys. Lying down a tablet adds
  ESTÉREO, the face alone as big as it fits (the display's tap goes RADIO → VU → ESPECTRO → ESTÉREO).
- The stereo's keys as on the PC (src/ui/stereo/stereo.js): SDM through the PC's 6 lights and 30 looks (the three
  that change the whole front too; held, the one before), HDB its four brightnesses, DISP NORMAL · HORA ·
  TEMPERATURA · HORA Y TEMPERATURA · TIEMPO (the weather from Open-Meteo), the radio line taking turns
  between the song and the station, VOL ▲ one step and ▼ three with the volume big on the display, PWR off and on,
  the PC's toasts. SCAN held opens the world radio search (Radio Browser, by name and country); a station found
  plays as a tuned station and the numbers keep it in the band.
