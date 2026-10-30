# Debug-Funktionen: Live-Dashboard und Diagnose-Aufnahme

Stand: 2026-10-30. Entworfen mit dem Owner; Entwürfe der Bildschirme wurden im Browser gezeigt und
abgenommen (Live: Umschalter Pult/Liste; Aufnahme: Hinweis-Dialog und Spuransicht).

## Absicht

Nomla soll unter echten Bedingungen – mitten im Gespräch in einem Kanal – zeigen und mitschneiden
können, was die Audio- und Netzkette tut. Zwei Zwecke:

1. **Selbst verstehen:** Ein technisch interessierter Nutzer (zuerst der Owner) sieht live, welche
   Verarbeitungsstufen aktiv sind und was jede mit dem Signal macht – ein „Nerd-Dashboard“.
2. **Fehler finden und echtes Feedback sammeln:** Eine kurze Aufnahme hält jede Stufe einzeln fest,
   senden wie empfangen, dazu die gesamte Telemetrie von Audio bis Netzwerk. Man hört sofort, an
   welcher Stufe es kaputt geht (z. B. abgehackte Sprache beim Moto G56 mit Echo-Unterdrückung),
   bewertet jede Spur und schickt alles als ZIP an die Entwickler. Die Entwickler (auch KI-Agenten)
   werten Audio und Telemetrie zusammen aus.

## Ziele

- Ein Schalter **„Debug-Funktionen aktivieren“** in den Einstellungen. Aus = keine zusätzliche
  Messung, keine Kosten, nichts sichtbar.
- Ist er an, lässt sich in der Kanalansicht von der Kanalliste über den Chat **weiter wischen** zu
  zwei Debug-Seiten: **Live** und **Aufnahme**.
- **Live-Seite:** aktive Stufen und Telemetrie in Echtzeit, zwei Ansichten mit Umschalter (siehe unten).
- **Aufnahme-Seite:** Aufnahme aller Stufen und aller Telemetrie über eine wählbare Dauer, Spuransicht
  zum Anhören, Bewertung je Spur, Notiz, Teilen als ZIP.
- Aufnahme nur mit Einverständnis: Hinweis-Dialog und die Mumble-Aufnahmeanzeige für alle.

## Nicht-Ziele

- Kein automatischer Upload, kein eigener Server für Berichte. Geteilt wird nur über das Android-
  Teilen-Menü, durch den Nutzer.
- Keine Aufnahme außerhalb einer Sitzung (dafür gibt es den Mikrofontest).
- Keine dauerhafte Speicherung von Aufnahmen in der App.
- Keine Änderung des Audioverhaltens: Die Messung beobachtet nur, sie verändert kein Signal.

## Anti-Ziele (wäre ein Fehlschlag, auch wenn es technisch funktioniert)

- Messung oder Aufnahme verursacht selbst Aussetzer, Latenz oder Knacken in der laufenden Sitzung
  (Allokationen, Locks oder I/O im Audio-Thread).
- Aufnahme ohne sichtbare Anzeige für die anderen, oder Aufnahme auf einem Server, der sie verbietet.
- Debug aus, aber trotzdem laufende Messung oder spürbarer Akku-/CPU-Verbrauch.
- Ein ZIP, das Servernamen, Nutzernamen, Passwörter, Zertifikate oder Tokens enthält.

## Bedienung

### Einstellungen

- Neuer Schalter „Debug-Funktionen aktivieren“ (DE/EN und alle App-Sprachen, KI-übersetzt wie üblich).
  *Ort: Builder-Entscheidung, sinnvoll ganz unten in den allgemeinen oder den Audio-Einstellungen.*
- **In allen Builds** (foss für Play, F-Droid und Website, ebenso beta), nicht hinter einem Build-Flag.
  Standard: aus.
- Ausschalten während einer Aufnahme bricht die Aufnahme ab und setzt die Aufnahmeanzeige zurück.

### Kanalansicht

- `ChannelFragment` zeigt Kanalliste und Chat als Tabs (Handy) oder nebeneinander (breite Bildschirme).
  Mit Debug an kommen zwei weitere Seiten **hinter** dem Chat dazu: „Live“, dann „Aufnahme“.
  *Bei der Nebeneinander-Ansicht: Builder-Entscheidung, wie die Debug-Seiten erreichbar sind (z. B. als
  weitere Tabs im rechten Bereich); sie müssen erreichbar sein.*
- Ohne verbundene Sitzung zeigen beide Seiten einen leeren Zustand mit Erklärung.

### Live-Seite

Oben ein Umschalter **Pult | Liste**; die Wahl bleibt gespeichert (pro Gerät).

- **Pult:** jede Sende-Stufe ein senkrechter Kanalzug mit LED (an/aus/Warnung) und Pegelmeter (Pegel
  nach der Stufe, mit Peak-Hold). Darunter: Gate-Verlauf der letzten Sekunden, dann Blöcke Echo,
  Empfang, Netz, Gerät. Tippen auf einen Kanalzug wechselt in die Liste, zu dieser Stufe gescrollt
  und aufgeklappt.
- **Liste:** jede Stufe eine Zeile (an/aus, Pegelbalken, Änderung in dB); unter der gewählten bzw.
  aufgeklappten Stufe alle ihre Kennzahlen. Darunter Empfang, Netz, Gerät.
- Stufen, die gerade nicht aktiv sind (z. B. kein Resampler, weil das Mikrofon schon 48 kHz liefert),
  erscheinen ausgegraut, nicht ausgeblendet – man soll sehen, *dass* sie aus sind.
- Aktualisierung etwa 10–20 Mal pro Sekunde; Messung nur, solange die Seite sichtbar ist (oder eine
  Aufnahme läuft).

### Aufnahme-Seite

1. Dauer wählen (Standard 10 s; *Auswahl und Obergrenze: Builder-Entscheidung, Speicherbedarf beachten*).
2. „Aufnehmen“ → Hinweis-Dialog (siehe unten), sofern nicht abgewählt.
3. Nomla prüft zuerst `ServerSettings.recordingAllowed`. Verbietet der Server Aufnahmen, startet keine
   Aufnahme, das Flag wird **nicht** gesendet (murmur wirft einen Client, der trotzdem
   `recording = true` setzt, vom Server), und die App sagt warum. Sendet der Server das Feld nicht,
   gilt wie heute „erlaubt“ (solche Server können Aufnahmen nicht verbieten) – *Designer-Entscheidung*.
   Sonst setzt Nomla den eigenen `UserState.recording = true`; das Senden des eigenen Flags ist neu.
4. Während der Aufnahme: Fortschritt, Abbrechen möglich. Am Ende, bei Abbruch, bei Trennung der
   Verbindung oder wenn Debug ausgeschaltet wird: `recording = false`.
5. Danach die Spuransicht: alle Spuren untereinander mit gemeinsamer Zeitachse und Abspielkopf;
   Antippen einer Spur spielt nur diese ab (Wiedergabe über die normale Audioausgabe, ohne die
   Sitzung zu stören – *wie genau: Builder-Entscheidung*). Pro Spur drei Smileys (gut/mittel/schlecht,
   optional). Eine Notiz für die ganze Aufnahme. Telemetrie-Auszüge als Zeitspuren unter dem Audio.
6. „Teilen“ erzeugt das ZIP und öffnet das Teilen-Menü (`FileProvider`, explizit, nur diese Datei).
7. Die Aufnahme liegt nur im Speicher bzw. im Cache der App; sie verschwindet beim Verwerfen, bei einer
   neuen Aufnahme und spätestens beim Prozessende. Geteilte ZIPs im Cache werden aufgeräumt.

### Hinweis-Dialog vor der Aufnahme

Wortlaut vom Owner abgenommen (DE; EN und die anderen Sprachen sinngemäß übersetzt):

> **Nur mit Einverständnis aufnehmen**
> Die Aufnahme enthält auch die Stimmen der anderen im Kanal. Nimm nur auf, wenn alle damit
> einverstanden sind. Während der Aufnahme sehen alle auf dem Server, dass du aufnimmst. Erlaubt der
> Server keine Aufnahmen, startet sie nicht.
> ☐ Nicht mehr anzeigen — [Abbrechen] [Aufnehmen]

Die Mumble-Aufnahmeanzeige für die anderen läuft unabhängig von der Checkbox jedes Mal.

### Stummschaltung und Push-to-talk

Wenn der Nutzer stummgeschaltet ist (oder bei Push-to-talk nicht drückt), wird auf der Sendeseite
**nichts aufgenommen**: Alle Sende-Spuren enthalten in diesen Phasen Stille, und die Telemetrie
markiert die Phasen. Was man bewusst nicht sendet, landet nie in einem ZIP. Empfangsspuren und
Telemetrie laufen weiter.

## Messpunkte

### Senden (Audio, je Stufe eine Spur und ein Live-Pegel)

1. Mikrofon roh – was `AudioRecord` liefert, in seiner **echten Abtastrate** (eigene WAV mit dieser
   Rate; die Spuransicht richtet Spuren nach Zeit aus, nicht nach Samples – *Designer-Entscheidung*).
   Quelle, Rate und Plattform-Effekte stehen in der Telemetrie; vor Herstellerfilter kommen wir nicht.
2. Nach Resampler (nur wenn aktiv).
3. Nach WebRTC APM (AEC3 + AGC2; laufen in einem Aufruf). *Getrennte Abgriffe AEC/AGC sind ein
   späterer Schritt, falls nötig.*
4. Nach Rauschunterdrückung (RNNoise oder Speex, je nach Einstellung).
5. Nach Gate (VAD) – Stille, wo nicht gesendet wird.
6. Gesendet – nach Boost, durch Opus kodiert und wieder dekodiert mit der tatsächlichen Bitrate und
   Paketgröße, so wie das Gegenüber es hört.

Die heutige Kette steht in `CapturePipeline` (Resample → `CapturePreprocessor` → `InputMode` → Boost)
und `AudioInput`; die Sitzung kodiert danach mit Opus.

### Empfangen

- Je Sprecher, der während der Aufnahme spricht: das dekodierte Signal nach Jitter-Puffer und
  Paketverlust-Verdeckung (PLC), so wie es in den Mix geht.
- Der fertige Mix, wie er an `AudioTrack` geht – zugleich die Referenz für die Echo-Unterdrückung.
- Netzseitig ist das kein Audio, sondern Telemetrie (Ankunft, Verlust, Verspätung je Paket).

### Telemetrie (Live und als Zeitreihe in der Aufnahme)

Grundsatz vom Owner: **alles, was hilft, von Audio bis Netzwerk** – „bis hin zu Puffergrößen von
UDP-Sockets“. Mindestens:

- **Pegel je Stufe:** RMS und Peak in dBFS, Übersteuerungen (Samples an der Grenze) je Stufe.
- **Aufnahme-System:** Quelle, Abtastrate, Kanalzahl, Puffergröße von `AudioRecord`, gelesene vs.
  erwartete Samples (Lücken/Überläufe), Lesedauer je Frame, Zustand „Silenced“, aktive
  Plattform-Effekte (AEC/NS/AGC des Herstellers), Audio-Modus.
- **WebRTC APM:** was `AudioProcessing::GetStatistics()` in der mitgelieferten Version liefert – ERL,
  ERLE, Delay (Median, Streuung, aktuell), Anteil divergenter Filter. *Heute nicht über JNI
  erreichbar; die Anbindung wird erweitert.* AGC2-Gain, Sprachpegel und Rest-Echo-Wahrscheinlichkeit
  gibt die Version nicht her; WebRTC wird dafür **nicht** gepatcht (Upstream-Treue). Stattdessen zeigt
  die Stufe die Pegeldifferenz vor/nach dem APM – *Designer-Entscheidung*.
- **Rauschunterdrückung:** Dämpfung (Energie vorher/nachher), RNNoise-Sprachwahrscheinlichkeit.
- **Gate/VAD:** Modus, Wahrscheinlichkeit bzw. Pegel, Rauschboden, Sprachpegel, Schwelle, offen/Hold,
  „zu nah“.
- **Encoder:** Bitrate, Frames pro Paket, Paketrate, ggf. FEC/DTX.
- **Netz:** Transport (UDP oder TCP-Tunnel), Ping UDP/TCP mit Streuung, Verlust/späte/Resync-Zähler
  aus den Mumble-Pings (beide Richtungen), Jitter, Sende- und Empfangspuffer der Sockets
  (`SO_SNDBUF`/`SO_RCVBUF`), Paketabstände gesendet/empfangen, Wechsel UDP↔TCP.
- **Empfang je Sprecher:** Jitter-Puffer-Tiefe, späte/verlorene Pakete, PLC-Frames, Dekodierfehler.
- **Wiedergabe:** `AudioTrack`-Puffergröße, Underruns (`getUnderrunCount`), Ausgabelatenz, Mix-Pegel
  und Übersteuerung.
- **Gerät/Route:** Ausgabegerät (Lautsprecher/Hörmuschel/Bluetooth/Kabel, Name und Typ des Headsets),
  Bluetooth-Codec bzw. SCO/LE Audio soweit abfragbar, Lautstärke des Streams.
- **Ereignisse mit Zeitstempel:** Routenwechsel, Audiofokus, Einstellungsänderungen, Start/Ende des
  Sprechens, Verbindungsereignisse.

## ZIP-Inhalt

- Je Spur eine WAV (16 bit mono; 48 kHz, außer der rohen Mikrofonspur in ihrer echten Rate).
- `meta.json`: App-Version und Flavor, Gerät (Hersteller, Modell, Android-Version/SDK), Route und
  Headset, die vollständige Audio-Konfiguration (alle relevanten Einstellungen und die tatsächlich
  gewählte Quelle/Rate/Effekte), Dauer, Startzeit, ob der Server die Aufnahmeanzeige akzeptiert hat,
  Bewertung je Spur, Notiz, Liste der Spuren mit Bedeutung.
- Telemetrie als CSV (pro 10-ms-Frame für Audio-Werte, pro Ereignis/Paket für Netz und Ereignisse);
  *Aufteilung in Dateien: Builder-Entscheidung, Spalten im README des ZIP erklärt.*
- Kurzes `README.txt` im ZIP: was welche Datei ist.
- Sprecher werden im ZIP nicht mit Namen geführt, sondern neutral nummeriert.
- **Nicht** enthalten: Servername/-adresse, Nutzernamen, Kanalnamen, Passwörter, Zertifikate, Tokens,
  Chat-Text.

## Auswertung

Ein Skript im Repo (`tools/`) liest ein solches ZIP und erzeugt einen Bericht: Spektren und Pegel je
Stufe, Aussetzer und abgeschnittene Silben, Echo-Dämpfung vs. Sprachverlust je Stufe, Korrelation mit
Netz- und Puffer-Ereignissen, neben den Smiley-Bewertungen. *Umfang: Builder-Entscheidung; es muss ohne
Netz und ohne Abspielen von Ton laufen.*

## Randbedingungen

- Echtzeit: Die Abgriffe im Audio-Thread schreiben nur in vorab angelegte Ringpuffer; keine
  Allokation, kein Lock, kein I/O pro Frame. Auswertung, Kodierung (Opus-Rundweg) und Dateien laufen
  außerhalb der Audio-Threads.
- Debug aus = Abgriffe inaktiv (kein Kopieren); die Kosten müssen dann vernachlässigbar sein.
- Speicher: Ringpuffer nur während einer Aufnahme; Größe aus Dauer × Spuren abgeleitet und begrenzt.
- Bestehende Architektur nutzen: `AppContainer`, `AudioWorker`, Settings-`Pref`s, `whileViewStarted`,
  explizite Intents, `FileProvider`.
- Tests: Einheitstests für Abgriffe, Ringpuffer, ZIP-Inhalt (insbesondere: keine Server-/Nutzerdaten),
  Aufnahmeanzeige setzen/zurücksetzen in allen Abbruchpfaden, Server-Ablehnung; UI-Tests für die
  Sichtbarkeit der Debug-Seiten je nach Schalter.
- Website: `technik.html` beschreibt die Debug-Funktionen; `index.html`, falls die Einrichtung
  berührt ist; Datenschutz-Abschnitt: Aufnahmen bleiben lokal, bis man sie selbst teilt.
- Store-Angaben: Weil alle Builds die Funktion haben, gehört sie in die Play-Datenschutzangaben
  (Data safety) und die Datenschutzerklärung auf der Website – beides steht ohnehin für 1.0.0 an.
- Akustische Tests am Gerät mit Testtönen nur nach Ankündigung und OK des Owners, mit lokalem murmur.

## Vom Owner entschieden

- Debug-Schalter in den Einstellungen; Debug-Seiten hinter dem Chat per Wischen.
- Live-Seite mit beiden Ansichten (Pult und Liste) und Umschalter; Tipp auf Kanalzug springt in die
  Liste.
- Aufnahme unter echten Bedingungen im Kanal, senden und empfangen in allen Stufen, dazu alle
  Telemetrie bis zu Socket-Puffern.
- Mumble-Aufnahmeanzeige nutzen; Einverständnis-Dialog mit „Nicht mehr anzeigen“.
- Wortlaut des Dialogs wie oben.
- Debug-Schalter in allen Builds, in den App-Einstellungen.
- Stumm/Push-to-talk nicht gedrückt = keine Aufnahme der Sendeseite.
- Bewertung je Spur mit drei Smileys; Teilen als ZIP mit Metadaten zu Gerät, Headset, Audio-Konfiguration.

## Vom Designer entschieden (zur Prüfung durch den Owner)

- Fehlt `recordingAllowed` in der Server-Konfiguration, gilt Aufnehmen als erlaubt.
- Kein Patch an WebRTC für AGC2-/Rest-Echo-Werte; Pegeldifferenz vor/nach APM stattdessen.
- Rohe Mikrofonspur in echter Abtastrate; Ausrichtung der Spuren nach Zeit.

## Dem Builder überlassen

- Genaue Lage des Schalters, Darstellung auf breiten Bildschirmen, Dauer-Auswahl und Obergrenze.
- Technik der Abgriffe und Ringpuffer, Wiedergabe der Spuren, Aufteilung der CSV-Dateien.
- Wortlaut der Texte (DE/EN plus alle App-Sprachen), außer dem Inhalt des Hinweis-Dialogs oben.
- Reihenfolge der Umsetzung in kleinen Commits.
