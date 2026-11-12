# Changelog

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
