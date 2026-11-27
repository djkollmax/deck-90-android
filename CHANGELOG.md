# Changelog

## 0.1.27

- Android Auto: the root is three tabs, RADIO (the bands as folders, each with its first stations as subtitle), LOCALES
  and MÚSICA (USB, CD, SERVER, TEL). Android Auto shows at most four root tabs (`BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT`,
  usually 4) and the root had up to twelve children, so FM5, HI-FI, AM, LOCALES and the music could be dropped.
- Content style hints (`MediaConstants.EXTRAS_KEY_CONTENT_STYLE_*`): memories, LOCALES, albums and DISCOS' letters as a
  grid, the rest as lists; the root's own defaults on the `LibraryParams` returned with it (keeping `isRecent`).
- `CarArt.kt`: the car's pictures through `ArtProvider` (content://com.djkollmax.deck90.art), since Android Auto reads
  artwork only from content URIs and a picture sent as data in a list of twelve goes over the binder's limit. A memory,
  a band, a letter of DISCOS and a list without its own picture get a little DP940 display drawn with the face's own
  16-segment font (`FONT_DATA`, read from `assets/face/dp940-display.js`); the tabs and folders get Tabler icons
  (MIT, `res/drawable-nodpi/car_*.png`, see THIRD_PARTY.md); Jellyfin's and Android's album covers, a list's picture
  and the CD's cover are fetched once and kept in the cache. The memories' and the tuned station's items carry their
  display as artwork (the playing screen, the notification), the songs and albums their cover's content URI
  (`Jf.cover` stays the http address for the rest). The CD's tracks in the browse tree no longer carry the cover as
  data (twelve covers decoded to bitmaps went over the binder's limit).
- LISTAS in the car's SERVER (`jflists`, `jfplist:<id>`): the lists with how far each went (`EXTRAS_KEY_COMPLETION_*`,
  from `Jf.places()`, one call), and in each SEGUIR (`jflgo:<id>`) and its songs (`jfl:<id>:<i>`), played as the whole
  list (`listQueue`) from the place kept, not the song's album.
- Where a list is (`Jf.placeSet`) is sent by `RadioService` (every 15 s while playing, on a pause and on a new song),
  from `Store.jfList`, which the phone's lists and the car's set and any other queue clears; the activity's own sender
  is gone, so it's kept with the screen off and from the car too.
- `RadioService.menus()`, every 3 s: `notifyChildrenChanged` for MÚSICA and its folders when the USB, the CD (its
  names and cover too), the server's sign-in or TEL's permission change, and for RADIO and the bands when a memory
  changes.
- Tests: `CarArtTest` (what a display says, and that the face's script still has the font).

## 0.1.26

- The bands' stations as in deck-90 7.10.39: FM5 1–4 FluxFM, Jazzradio, Venice Classic Radio, FluxFM ChillHop (Mother
  Earth closed); FM2 1 Urbana Play 104.3 (Con Vos answers only its site's player), Delta's HLS address, "Blue FM
  100.7"; AM 2 AM 750 (Radio 10, same), "Continental 590". `Bands.renewed` replaces the kept ones that came with the
  app (by name and search), in `listFromJson`.
- HLS: `media3-exoplayer-hls`; `RadioBrowser.parse` keeps HLS (`Found.hls`); a station whose address (or last played
  one) is `.m3u8` goes to the player as `APPLICATION_M3U8` (`Store.mime`). The playlist is read through the Resolver,
  and its pieces are asked at the real address (Media3's ParsingLoadable uses the resolved URI).
- `RadioBrowser.fits`: a memory looked up by name plays only an answer with every word searched in its name (or the
  search's words together), as deck-90's `radioFits`. Tests for the three.

## 0.1.25

- The pad's song keys (more than 12: CD, USB, SERVER) are sized by `padFit`, run by a ResizeObserver on the pad as
  well as on a new song: their height was worked out only when the display's text changed, so turning the tablet
  while the room was small left the rows squashed and overlapping until the next song. Never shorter than what a key
  holds; at most 120 px, so a tall tablet shows more songs at once.

## 0.1.24

- CD names: `Cd.lookup` asks MusicBrainz again while the same disc is in (after 4, 12, 30 and 60 s) when there's no
  answer; `get` and `bytes` throw on anything but 200 and 404 (503 when MusicBrainz is busy), and the cover is asked
  three times. A disc MusicBrainz didn't know is asked again after a day (`at`), as on the PC. The first insert used
  to stay on TRACK nn until the disc was put in again.
- `Cd.onNames`: the service replaces the CD's media items when the names or the cover arrive (`replaceMediaItems`,
  same URIs, so playback goes on), for the notification and Android Auto.
- The pad's keys in CD mode are the places in the disc's list (`Cd.at`, `cd.at` in the state, `mem` by place): a
  disc whose audio starts at track 2 lit and played one key off. Test: `Cd.at`.

## 0.1.23

- The face synced from Deck 90 7.10.38: `Deck90DotText` (dot-glyphs.js) turns characters with no glyph into what
  they stand for (½ → 1/2, ² → 2, Ø → O) before the DP940 and the double DIN draw them; glyphs for ¿ and ¡.

## 0.1.22

- AUX's meters (`AuxMeter` in `Aux.kt`): Android's `Visualizer` on session 0 (the output mix), 1024-sample 8-bit
  snapshots at its max capture rate, `SCALING_MODE_AS_PLAYED`; each gives peak, RMS and Levels' 28 bands (same dB),
  served by the bridge's `levels()` and `spectrum()` while it's on. RECORD_AUDIO (asked once, on AUX) and
  MODIFY_AUDIO_SETTINGS; on only in AUX with the stereo on and the face on screen. A tablet that gives no engine, or
  silence for 6 s while the other app plays, is told once. `Levels.fft` is shared. Tests: a sine's levels and band.

## 0.1.21

- AUX works: `Store.mode` read `aux` back as `radio`, so the mode never held and the keys and the display stayed on
  the radio. The modes are `Store.MODES` now (`modeOf`, with a test). AUX also switches the stereo on when it's off.

## 0.1.20

- AUX (`Aux.kt`): a `NotificationListenerService` (`AuxListener`, switched on by the person) lets
  `MediaSessionManager.getActiveSessions` show the other apps' players; mode `aux` shows the playing one (or the last)
  on the display and in `aux` of the state, SEL/◀◀/▶▶ go to its transport controls, PWR pauses it. The face has an
  AUX hotspot on both fronts (as Deck 90 7.10.34); in AUX no band or memory is marked, and the DISC mark stays off.
- LISTAS: `Jf.lists`, `Jf.listSongs`, `Jf.place`, `Jf.placeSet` (Jellyfin playlists and the user's DisplayPreferences
  for client `deck90`, `list:<id>`, as Deck 90 7.10.36). A list plays from the song touched, the one it was left on
  from its second; while it plays, the place is sent every 15 s and on pause.
- Tests: the lists and the place both ways against the pretend Jellyfin.

## 0.1.19

- Face synced from deck-90 7.10.34: the double-DIN photo with the DP940-style buttons (same size and key places).

## 0.1.18

- Face synced from deck-90 7.10.33: the redrawn double-DIN photo (`img/front-ddin.jpg`, same size and key places).

## 0.1.17

- The lists' home took its counts from the last state, which could still be the other source's right after SOURCE
  changed (TEL showed the server's 1415 albums). `jfServer()` now gives the shown source's albums, artists and
  reading, and `jfHome` uses them.

## 0.1.16

- SERVER: `Jf.alt`, the server's other address, learnt from `/System/Info/Public`'s `LocalAddress` after signing in
  and after each library reading (kept with the token). A request whose connection is never made (refused, no route,
  connect timeout: `Jf.unreached`) is tried at `alt`, and when that answers the two swap (`swap`), so the player's
  addresses follow. The player's no-answer path asks `Jf.reach()` before it retries. Connect timeout 6 s.
- `jfList` no longer blocks on `lib.read()` when a library is kept: it reads again in the background; only an empty
  list carries an error. `Jf.why()` tells a connection that never came (Tailscale asleep, not home) from a server
  that answered too slowly.
- Face: `body[data-front]` mirrors the stereo's front; a tablet lying down with the double DIN, in VU or ESPECTRO, hides
  the song line and gives the face 38vh, doubling the meters' height.
- Tests: the home address taken over when the one in use stops answering.

## 0.1.15

- SERVER: the token goes in the `Authorization: MediaBrowser … Token=` header only (no `X-Emby-Authorization`), and
  a song's address carries it as `ApiKey` plus the same header through the resolver (`Jf.streamHeaders`): Jellyfin 12
  turns `api_key` off. A GET that gets no answer, and a POST that never connected, is asked once more after 1.5 s.
- Errors are per list: `MusicLib.fails` counts failures and `jfList` reports `error` only when it grew during that
  request (a stale `Jf.error` used to show "No contesta el servidor" on later, unrelated lists, even an empty queue);
  any answer from the server clears it. `Jf.why()` turns exceptions into the person's words; the face shows them with
  PROBAR DE NUEVO, and ENTRAR AL SERVIDOR when signed out.
- A song answering 401 no longer signs out by itself: `Jf.check()` asks `/Users/{id}`; only a 401 there does.
- Sign-in answers as soon as the token is in; the library is read afterwards and the face told (`faceJf('read')`).
  A reading waited for gives up after 3 minutes, not 1.
- `JfAlbum.artistId` holds every album artist ("id1,id2"), `by()` matches any.
- TEL: the not-music folders match whole names (`call` dropped Callejeros and Calle 13); songs under 20 s are kept
  when their album has a longer one (`Tel.shortOut`); albums get their year.
- CD: a data disc reads the last data track's file system (multisession), falling back to the first; the disc id and
  the TOC sent to MusicBrainz start at the first audio track; an MP3 CD's files get their tags as USB's do.
- Tests: `JellyfinServerTest` against a small pretend Jellyfin (sign-in, the library, a dropped connection, per-list
  errors, the 401 check, where the token goes); TEL's folders and short songs; a mixed-mode CD's disc id.

## 0.1.14

- The double-DIN front from deck-90 7.10.30 (HDB held; held again, the DP940; kept as `front` in the face's look):
  sync-face.sh copies `ddin-display.js`, `dot-glyphs.js` and `img/front-ddin.jpg`; face.js lays its keys from the
  photo's pixels (`DDIN`, the PC's `STEREO_DDIN` plus the display as `view`), builds `Deck90DoubleDin` in place of
  the DP940's display, and feeds it the second dot line (`line2`: the station, town and country; `album`: the album,
  year and place in the list), the clock and temperature, and a 20-band spectrum from Levels' 28 (`ddSpectrum`).
  The face's width follows its ratio (`--face-ratio`); a tablet lying down gives it 44vh.

## 0.1.13

- Face synced from deck-90 7.10.29: the sharper front (img/front.jpg, 4002×1078, registered onto the old one) and
  dp940-display.js's "blank cells" (a space shows its cell's unlit segments).

## 0.1.12

- A station whose stream ends by itself (its server closes the connection; ExoPlayer sees the end of the item) went
  on to the band's next memory (MEDIA_ITEM_TRANSITION_REASON_AUTO) or to STATE_ENDED: the same station is tuned
  again, at once the first time.
- While waiting to retry the player holds no wake or Wi-Fi lock (it's idle), so with the screen off the CPU slept, the
  Handler's retry never ran and the Wi-Fi could switch off: the service holds a partial wake lock and a Wi-Fi lock
  from the drop until it plays again or the person stops it, and keeps the service in the foreground meanwhile
  (onUpdateNotification with startInForegroundRequired while reconnecting): Media3 let the stopped player's service go
  to the background, where Android froze or closed it with the screen off.
- Retries go on once a minute after the 2–60 s steps, for half an hour (MAX_TRIES), instead of stopping after ~2 min.
- SERVER: an error with no HTTP answer (Wi-Fi gone, server restarting) retries the same way and resumes the song at
  its position, instead of NO SERVER at once.

## 0.1.11

- Big keys: in USB, CD and SERVER one key per song (state's memories up to 999), scrolling three rows at a time with the
  top of a fourth showing; the song playing is scrolled into view. The radio keeps its twelve memories.
- Radio drops: after the first retry (a new address from Radio Browser), tries again after 2, 4, 8, 15, 30 and 60 s with
  Now.error "RECONNECT" (RECONNECTING… on the display); half a minute playing well starts the count over. `retried`
  was never reset, so a second drop of the same station stopped on NO SIGNAL for good.
- USB: discnext/discprev with no folders computed `% 0` (ArithmeticException, the app closed).
- MÚSICA → LO QUE SUENA asked the MediaController from the list's thread (it threw; the list came empty): asked on the
  main thread (onMain). Jf.read/Tel.read asked while a reading runs wait for it instead of answering empty.
- CD EJECT runs off the main thread (CdDrive's commands are synchronized with the reads playing).
- RadioService's background executor has three threads (a slow server held LOCALES and the rest of the car's lists).
- The kept SERVER queue is parsed once, not at every refresh (four times a second).

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
