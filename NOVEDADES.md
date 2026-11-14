# Novedades

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
