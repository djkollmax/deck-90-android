# Novedades

## 0.1.13 — 2026-11-21

### Mejor
- **El frente del estéreo, más nítido:** la foto nueva de la PC, al doble de definición. Las teclas quedan en el mismo
  lugar.
- **El display muestra los espacios entre palabras** con sus segmentos apagados, como las demás letras (antes quedaban
  vacíos).

## 0.1.12 — 2026-11-20

### Arreglos
- **La radio ya no se queda callada después de un rato.** Algunas radios cortan la transmisión cada tanto: antes el
  estéreo pasaba solo a la memoria siguiente o se quedaba quieto. Ahora vuelve a sintonizar la misma radio al toque.
- **Con la pantalla apagada, la reconexión funciona:** mientras espera para volver a probar, la tablet o el celular no
  se duermen, no apagan el Wi‑Fi y Android no cierra la app (antes, si se cortaba con la pantalla apagada, no volvía
  nunca). Y sigue probando una
  vez por minuto durante media hora, no solo dos minutos.
- **El servidor también se reconecta:** si se corta el Wi‑Fi o el servidor tarda, sigue el mismo tema desde donde
  estaba, en vez de quedar en NO SERVER.

## 0.1.11 — 2026-11-20

### Nuevo
- **Las teclas grandes de los temas muestran todos los temas**, no solo 12: deslizás hacia abajo y aparecen el 13, el
  14… (asoma la fila de abajo para que se note que hay más). Vale para el pendrive, el CD, el servidor y el teléfono,
  y el tema que suena se acomoda solo a la vista. En la radio siguen las 12 memorias.

### Arreglos
- **La radio que se corta se vuelve a sintonizar sola**, cada vez más espaciado (como el RECONECTANDO de la PC), y el
  display dice RECONNECTING… Antes, si una radio se cortaba dos veces, quedaba en NO SIGNAL hasta tocarla.
- USB: ◀ CARPETA y CARPETA ▶ sin pendrive cerraban la app. Ahora te piden elegirlo.
- MÚSICA → LO QUE SUENA salía vacío: ahora muestra los temas que suenan.
- La primera vez que abrís DISCOS o ARTISTAS, mientras se lee la biblioteca, ya no sale vacío: espera y los muestra.
- CD: EJECT ya no congela la pantalla mientras la lectora termina de leer.
- En el auto, un servidor lento ya no traba las otras listas (radios locales, el teléfono).
- MODE a USB mientras lee la carpeta queda en USB.

## 0.1.10 — 2026-11-19

### Nuevo
- **TEL: la música del teléfono, la tablet y la tarjeta SD.** En SERVER, **MODE apretado un segundo** (o las pestañas
  **SERVIDOR · TELÉFONO · USB** arriba de MÚSICA) cambia de fuente. TELÉFONO usa la biblioteca de música que Android ya
  tiene armada: toda la música de la memoria y de la SD, con **AL AZAR, RECIENTES, DISCOS, ARTISTAS** y la búsqueda,
  igual que tu Jellyfin. No hay que elegir carpetas y lo que copies aparece solo. La primera vez Android pide permiso
  para leer la música. En el auto aparece como **TEL · MÚSICA DEL TELÉFONO**, y el display dice **TEL** abajo de PLAY.
- Sin servidor: en la pantalla para entrar a Jellyfin, **¿SIN SERVIDOR? LA MÚSICA DEL TELÉFONO**.

### Arreglos en USB
- Un archivo que el celular no puede tocar (o dañado) se saltea, en vez de parar todo con NO USB.
- Ya no aparecen como temas las listas (.m3u, .pls), los .wma ni los MIDI, ni las carpetas del teléfono que no son
  música (las notas de voz de WhatsApp, los tonos, las alarmas, las grabaciones).
- Al elegir la carpeta dice cuál: Android no deja elegir la memoria del teléfono entera, así que es la carpeta **Music**
  (o el pendrive, o la tarjeta SD entera).

### Arreglos en SERVER
- Un tema de un compilado ahora dice **quién lo canta**, no "Various Artists".
- Si el servidor no puede mandar un tema (un archivo dañado o que no puede convertir), **pasa al siguiente** en vez de
  quedarse en NO SERVER. Si el servidor ya no te reconoce, pide entrar de nuevo.
- Para entrar de nuevo ya está puesto tu usuario: solo falta la contraseña.
- Los discos nuevos que agregás al servidor aparecen solos (la biblioteca se lee otra vez cada media hora al abrir
  MÚSICA; antes tardaba hasta 6 horas).
- La copia de seguridad de Android ya no guarda el permiso del servidor: en un celular nuevo se entra otra vez.

## 0.1.9 — 2026-11-18

### Nuevo
- **SERVER: tu música de Jellyfin, como el DM-90 de la PC.** MODE pasa ahora de RADIO a USB, CD y **SERVER**. La
  primera vez pide la dirección del servidor, el usuario y la contraseña; Deck 90 guarda el permiso que da Jellyfin,
  nunca la contraseña.
- **MÚSICA** (la tecla de arriba, o EJECT) abre las listas: **AL AZAR** (100 temas de toda la biblioteca),
  **RECIENTES**, **DISCOS**, **ARTISTAS**, **LO QUE SUENA** y una búsqueda que encuentra artistas, discos y temas
  mientras escribís.
- Suena en la calidad del archivo (FLAC 24/96, MP3 320); lo que el celular no reproduce (ALAC, WMA) el servidor lo
  manda como FLAC.
- Las teclas como en USB: ◀◀ ▶▶ pasan de tema, 1 a 6 (y 7 a 12 manteniendo) van a un tema, **BAND** pasa al disco
  siguiente (mantenida, al anterior), **SCAN** es RAND y **LOC** es REP.
- **En Android Auto**, la carpeta **SERVER · MÚSICA**: al azar, recientes, discos y artistas por letra.
- Fuera de casa anda con **Tailscale** prendido en el celular: poné como servidor la dirección de Tailscale de la PC.

## 0.1.8 — 2026-11-14

### Arreglos
- **El CD se quedaba en LEYENDO y la lectora expulsaba el disco.** Al leer la lista de temas, la app esperaba más
  datos de los que manda la lectora, y después de esperar la reiniciaba, una y otra vez. Ahora lee la lista entera y
  sigue. Si algo falla, deja tranquila a la lectora unos segundos antes de volver a probar.

## 0.1.7 — 2026-11-14

### Nuevo
- **USB: la música de un pendrive.** Enchufalo al celular o a la tablet (con un adaptador OTG) y apretá **MODE**
  un segundo para elegirlo; también sirve una carpeta del celular o de la tarjeta de memoria. Cada carpeta con música
  es un disco: ◀◀ ▶▶ pasan de tema, 1 a 6 (y 7 a 12 manteniendo) van a un tema, BAND pasa de carpeta (mantenido, a la
  anterior), SCAN es RAND y LOC es REP. En el display se ven el tema, el artista y el disco (los de cada archivo) y la
  calidad, como FLAC 24/96. Parado, las teclas de arriba pasan de carpeta y **CARPETAS** las muestra todas.
- **CD: una lectora de CD o DVD por USB.** Enchufala con un adaptador OTG y elegí CD con MODE (o tocá la ranura del
  CD). La primera vez Android pregunta si Deck 90 puede usar la lectora. Los CD de música suenan tal cual están en el
  disco, con los nombres de los temas y la tapa (de MusicBrainz, como el DC-90 de la PC); los discos con MP3 o FLAC
  también. EJECT abre la lectora. Muchas lectoras piden más corriente de la que da un celular: si no arranca, usá un
  cable en Y o un hub USB con fuente.
- **MODE pasa de RADIO a USB, a CD y de vuelta a la radio.** En Android Auto aparecen las carpetas **USB** y **CD**.
- **Apagado de verdad:** PWR dice **GOOD BYE** y el frente queda en penumbra; al final del SLEEP, lo mismo. Al prender
  dice **HELLO**. Apagado, solo prenden PWR, SEL, BAND y las memorias.

## 0.1.6 — 2026-11-14

### Cambios
- **LOCAL en el display se prende solo en la banda LOCAL:** en rojo con las radios de donde estás; en las demás bandas
  queda apagado (antes estaba siempre prendido). Además está un poco más a la derecha, debajo del recuadro de STEREO
  y MONO.

## 0.1.5 — 2026-11-13

### Nuevo
- **SCAN y LOC son dos teclas:** en el frente del estéreo, la mitad izquierda es SCAN (tocada prueba cada radio 8
  segundos; mantenida, la búsqueda del mundo) y la derecha es LOC.
- **LOC, las radios de donde estés:** tocada, pasa a la banda **LOCAL**, con las doce radios más escuchadas cerca del
  celular (la primera vez pide la ubicación aproximada); LOC otra vez vuelve a la banda de antes. Mantenida, abre la
  búsqueda con todas las radios de la zona. Si viajás, LOCAL se pone al día sola: en Mar del Plata, las de Mar del
  Plata; en Londres, las de Londres.
- **RADIOS LOCALES en Android Auto:** una carpeta más, con las de la zona donde va el auto.
- **CERCA en la búsqueda:** la misma lista de la zona, desde la búsqueda.

### Arreglos
- **El display recuerda cómo lo dejaste** (SDM, HDB, DISP, la cara de los VU y el color del espectro) aunque se
  actualice o cierres la app.
- **La búsqueda ya no muestra radios que no suenan:** las de video y las listas por segmentos (HLS) quedan afuera.

## 0.1.4 — 2026-11-13

### Cambios
- **La temperatura de donde estés:** DISP en TEMPERATURA (o HORA Y TEMPERATURA) muestra el clima de donde está el
  celular, con el nombre del lugar, y lo sigue si viajás. La primera vez pide la ubicación aproximada: alcanza con la
  ciudad. Si no la das, DISP dice NO WEATHER.
- **Las notas de cada versión en GitHub** dicen qué cambió, también las de las versiones anteriores.

## 0.1.3 — 2026-11-13

### Arreglos
- **Los vúmetros del estéreo ya no se cortan cada unos segundos.** Cuando cambiaba la línea de abajo de la sintonía
  (el tema y la radio se alternan), bajaban a cero un instante y volvían a subir. Ahora se quedan firmes, y el
  display redibuja solo lo que cambió.
- **Al cambiar de radio** ya no aparece por un instante en los vúmetros el sonido de la radio anterior.

### Cambios
- **Menos batería:** con otra app adelante o la pantalla apagada, la pantalla de la radio deja de dibujarse. La radio
  sigue sonando igual.

## 0.1.2 — 2026-11-12

### Arreglos
- **Los vúmetros y el espectro van con el sonido que escuchás**, no un cuarto de segundo antes (más con Bluetooth):
  cada medición espera el momento en que ese sonido sale por el parlante.
- **El espectro fluido:** a pantalla completa, acostado, se trababa en celulares de gama media. Ahora va a 60 cuadros
  por segundo.

## 0.1.1 — 2026-11-12

### Nuevo
- **Apagado automático (SLEEP):** mantené PWR un segundo y elegís 15, 30, 60 o 90 minutos, o sin apagado. Al final
  la radio baja sola en 10 segundos y se apaga, aunque la pantalla esté apagada. Los minutos que faltan se ven en el
  display.

### Arreglos
- **SCAN mantenido en las teclas de abajo** (celular parado y tablet) abre la búsqueda de radios del mundo, como en
  el frente del estéreo.

## 0.1.0 — 2026-11-12

### Nuevo
- **La radio de Deck 90 en el celular, la tablet y el auto:** las mismas bandas y memorias que en la PC (FM1 a FM5,
  HI-FI y AM, doce memorias por banda), con la mejor calidad que transmite cada radio.
- **El frente del estéreo del auto con su display,** con las mismas teclas que en la PC: SDM con sus 36 colores y
  looks, HDB, DISP (hora, temperatura, tiempo), A.MEM, SCAN, VOL, ATT y PWR.
- **Buscar cualquier radio del mundo:** SCAN mantenido, por nombre y país.
- **VU y espectro** del sonido que sale por el parlante: dos VU de aguja con tres caras y un espectro con tres
  colores.
- **Celular parado:** la radio y el tema en grande, y las bandas y memorias como teclas grandes.
- **Tablet:** todo más grande en cualquier posición; acostada suma ESTÉREO, el frente solo a pantalla completa.
- **Android Auto:** las bandas y las memorias en la pantalla del auto, con el tema que pasa la radio.
