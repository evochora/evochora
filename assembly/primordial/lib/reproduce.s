# lib/reproduce.s
# Exportierte Reproduktion des Organimus
.INCLUDE "lib/state.s"

.REG %CONTCORD %LR0 # Koordinate um die Reproduktion fortzusetzen
#.REG %FORKCORD %LR1 # Koorinate in der rechten oberen Ecke, von der aus der FORK Prozess gestartet werden kann

# ----------------------------
# Constants
# ----------------------------
.DEFINE REPRODUCTION_PAUSE_THRESHOLD DATA:5000
.DEFINE REPRODUCTION_CHILD_INITAL_ENERGY DATA:5000

.PROC CONTINUE EXPORT REF ER

  .PREG %SHELL   %PR0     # Hüllen Molekül als Terminator Symbol
  .PREG %DIRMASK %PR1     # Richtung der Reproduktion als Bitmaske - static local state
  .PREG %DIRVEC  %PR2     # Richtung der Reproduktion als Vektor
  .PREG %SIDEVEC %PR3     # In der ersten und letzten Zeile Vektor zu Hüllenseite, sonst DATA:0, im local state DATA:1 für rechts (letzte Zeile) und DATA:-1 für links (erste Zeile)
  .PREG %TMP     %PR4     # Tempräres register für schenlle Vergleiche

  JMPI CONTINUE_INIT

  # 3 DATA-Felder in der Zeile über CONTINUE_INIT (x=0..2, y=-1 relativ zu CONTINUE_INIT)
  .PLACE DATA:0  0|1    # %DIRMASK
  .PLACE DATA:-1  1|1    # %DIRVEC

  .ORG 0|2
  CONTINUE_INIT:                                  # State laden
    STATIC2_LOAD %DIRMASK %SIDEVEC
    JMPI CONTINUE_INIT2

  .ORG 0|3
  CONTINUE_INIT2:                                 # Wir brauchen ein Shell Molekül als Terminator Symbol
    DPLS                                          # alten DP sichern
    SYNC
    SEKI -1|0
    SCNI %SHELL -1|0                              # Hüllen Molekül als Terminator Symbol merken

    GTI %DIRMASK DATA:0                           # Wenn es schon eine Richtung gibt,...
      JMPI CONTINUE_DIRVEC                        # ... dann nicht neu zufällig auswählen

    # Richtung der Reproduktion zufällig auswählen
    SETI %DIRMASK DATA:15                         # Alle 4 Richtungen
    RBIR %DIRMASK %DIRMASK                        # Zufälliges gesetztes Bit aus %DR0 -> %DR1
    JMPI CONTINUE_DIRVEC

  .ORG 0|4
  CONTINUE_DIRVEC:
    B2VR %DIRVEC %DIRMASK                         # Bitmaske -> Einheitsvektor

    # %SIDEVEC aus State und %DIRVEC berechnen
    IFI %SIDEVEC DATA:0
      JMPI CONTINUE_SIDEVEC_0

    LTI %SIDEVEC DATA:0
      JMPI CONTINUE_SIDEVEC_L

    SETR %SIDEVEC %DIRVEC
    RTRI %SIDEVEC DATA:1 DATA:0
    JMPI CONTINUE_SIDEVEC_DONE

  .ORG 0|5
  CONTINUE_SIDEVEC_L:
    SETR %SIDEVEC %DIRVEC
    RTRI %SIDEVEC DATA:0 DATA:1
    JMPI CONTINUE_SIDEVEC_DONE

  CONTINUE_SIDEVEC_0:
    SETV %SIDEVEC 0|0
    JMPI CONTINUE_SIDEVEC_DONE

  CONTINUE_SIDEVEC_DONE:
    # Wenn wir eine Koordinate zum fortsetzen haben, dann nach CONTINUE_LOOP, sonst zu Kante laufen
    LRDS %CONTCORD
    PUSV 0|0
    INS
      JMPI CONTINUE_LOOP
    JMPI CONTINUE_BORDERMOVE

  .ORG 0|6
  ## Beide DPs an die Ausgangsposition setzen
  # in %DIRVEC laufen, bis ich auf die hülle treffe
  CONTINUE_BORDERMOVE:
    INPR %DIRVEC                   # Wenn das Molekül nicht passierbar ist,...
      PEEK %TMP %DIRVEC            # ... dann lösche ich es

    SCAN %TMP %DIRVEC              # Was liegt da vor mir?
    IFR %TMP %SHELL                # Ist es meine Hülle?...
      JMPI CONTINUE_TURNLEFTCORNER # ... Dann laufe zur Ecke

    SEEK %DIRVEC                   # Laufe vorwärts
    JMPI CONTINUE_BORDERMOVE

  CONTINUE_TURNLEFTCORNER:
    RTRI %DIRVEC DATA:0 DATA:1     # Nach links drehen
    JMPI CONTINUE_CORNERMOVE

  .ORG 0|7
  # bis in die linke ecke laufen
  CONTINUE_CORNERMOVE:
    INPR %DIRVEC                   # Wenn das Molekül nicht passierbar,...
      PEEK %TMP %DIRVEC            # ... dann lösche ich es

    SCAN %TMP %DIRVEC              # Was liegt da vor mir?
    IFR %TMP %SHELL                # Ist es meine Hülle?...
      JMPI CONTINUE_CORNERFINISH   # ... Dann sind wir in der Ecke angekommen und erstmal fertig

    SEEK %DIRVEC                   # Laufe vorwärts
    JMPI CONTINUE_CORNERMOVE

  # In Reporduktionsrichtung drehen und Position merken
  CONTINUE_CORNERFINISH:
    RTRI %DIRVEC DATA:1 DATA:0     # Nach rechts drehen
    DPLR %CONTCORD                 # Continue-Position erreicht -> speichern
    #DPLR %FORKCORD                 # FORK-Position merken
    JMPI CONTINUE_LOOP

  .ORG 0|8
  CONTINUE_LOOP:
    NRG ER                                        # ER in %NRG lesen (Cost 1)
    LTI ER REPRODUCTION_PAUSE_THRESHOLD           # wenn ER < Threshold ...
      JMPI CONTINUE_SAVE_AND_RET                  # ... dann State speichern & zurück

    # DP0 und DP1 sichern und an Continue-Position holen
    ADPI DATA:0
    DPLS
    SKLR %CONTCORD
    ADPI DATA:1
    DPLS
    SKLR %CONTCORD
    JMPI CONTINUE_WRITEINIT

  .ORG 0|9
  CONTINUE_WRITEINIT:
    # Schreibvorgang vorbereiten
    INPR %DIRVEC                   # Wenn das Molekül nicht passierbar,...
      PEEK %TMP %DIRVEC            # ... dann lösche ich es
    SEEK %DIRVEC                   # Vorwärts
    INPR %DIRVEC                   # Wenn das Molekül nicht passierbar,...
      PEEK %TMP %DIRVEC            # ... dann lösche ich es
    POKE %SHELL %DIRVEC            # Hülle schreiben
    SEEK %DIRVEC                   # Vorwärts
    #INPR %DIRVEC                   # Wenn das Molekül nicht passierbar,...
    #  PEEK %TMP %DIRVEC            # ... dann lösche ich es


    # Lese DP auf die Hülle setzen
    ADPI DATA:0
    SEEK %DIRVEC

    # Nach innen drehen zum Lesen
    RTRI %DIRVEC DATA:0 DATA:1
    RTRI %DIRVEC DATA:0 DATA:1

    # Stop-Molekül auf den DS legen, damit Schreib-DP weiß wo er aufhören muss, und Lesevorgang aktivieren
    PUSH %SHELL
    JMPI CONTINUE_READLINE

  .ORG 0|10
  # Loop: Nicht passierbares PEEKen, auf Hülle testen, Molekül vor uns auf den DS legen, vorwärts
  CONTINUE_READLINE:
    INPR %DIRVEC                   # Wenn das Molekül nicht passierbar,...
      PEEK %TMP %DIRVEC            # ... dann lösche ich es

    SCAN %TMP %DIRVEC              # Was liegt da vor mir?
    IFR %TMP %SHELL                # Ist es meine Hülle?...
      JMPI CONTINUE_WRITESTART     # ... Dann mit dem Schreiben anfangen

    PUSH %TMP                      # Molekül auf den DS legen
    SEEK %DIRVEC                   # Laufe vorwärts
    JMPI CONTINUE_READLINE

  CONTINUE_WRITESTART:
    ADPI DATA:1                    # Schreinvorgang einleiten
    RTRI %DIRVEC DATA:0 DATA:1     # Richtung nach außen drehen zum schreiben
    RTRI %DIRVEC DATA:0 DATA:1
    JMPI CONTINUE_WRITELINE

  .ORG 0|11
  CONTINUE_WRITELINE:              # Schreib Loop
    ##INPR %DIRVEC                   # Wenn das Molekül nicht passierbar,...
    ##  PEEK %TMP %DIRVEC            # ... dann lösche ich es

    POP %TMP                       # Nächstes Symbol von DS holen

    IFR %TMP %SHELL                # Kommt als nächstes das Terminator Symbol?
      JMPI CONTINUE_WRITEFINISH    # Dann Zeilenabschluss

    ##POKE %TMP %DIRVEC              # Schreiben
    PPKR %TMP %DIRVEC

    SETV %TMP 0|0
    IFR %SIDEVEC %TMP              # Wenn wir nicht an der Seite der Hülle sind, ...
      JMPI CONTINUE_WRITEFORWARD   # ... dann normale Zeile schreiben

    # Hüllen Seite schreiben
    INPR %SIDEVEC                  # Wenn das Molekül nicht passierbar,...
      PEEK %TMP %SIDEVEC           # ... dann lösche ich es
    POKE %SHELL %SIDEVEC

  CONTINUE_WRITEFORWARD:
    SEEK %DIRVEC                   # Vorwärts
    JMPI CONTINUE_WRITELINE

  .ORG 0|12
  CONTINUE_WRITEFINISH:            # Hüllenabschluss schreiben, zurück zum Anfang und zur nächsten Zeile
    INPR %DIRVEC                   # Wenn das Molekül nicht passierbar,...
      PEEK %TMP %DIRVEC            # ... dann lösche ich es

    SETV %TMP 0|0
    IFR %SIDEVEC %TMP              # Wenn wir nicht an der Seite der Hülle sind, ...
      JMPI CONTINUE_WRITE_FINISH_END   # ... dann normale Zeile schreiben

    # Hüllen Seite schreiben
    INPR %SIDEVEC                  # Wenn das Molekül nicht passierbar,...
      PEEK %TMP %SIDEVEC           # ... dann lösche ich es
    POKE %SHELL %SIDEVEC
    JMPI CONTINUE_WRITE_FINISH_END

  .ORG 0|13
  CONTINUE_WRITE_FINISH_END:
    #SEEK %DIRVEC
    INPR %DIRVEC                   # Wenn das Molekül nicht passierbar,...
      PEEK %TMP %DIRVEC            # ... dann lösche ich es
    POKE %SHELL %DIRVEC            # Hüllenabschluss der Zeile schreiben

    SETV %TMP 0|0
    IFR %SIDEVEC %TMP              # Wenn wir nicht an der Seite der Hülle sind, ...
      JMPI CONTINUE_WRITEFINISH_NOSIDE   # ... dann normale Zeileabschluss schreiben
    JMPI CONTINUE_WRITEFINISH_CORNER

  .ORG 0|14
  CONTINUE_WRITEFINISH_CORNER:
    # Ecke schreiben
    #INPR %SIDEVEC                  # Wenn das Molekül nicht passierbar,...
    #  PEEK %TMP %SIDEVEC           # ... dann lösche ich es
    #POKE %SHELL %SIDEVEC
    SEEK %DIRVEC                   # Vorwärts
    INPR %SIDEVEC                  # Wenn das Molekül nicht passierbar,...
      PEEK %TMP %SIDEVEC           # ... dann lösche ich es
    POKE %SHELL %SIDEVEC

    # Wenn %SIDEVEC links, dann ist alles kopiert und wir können zum FORK
    CRSR %TMP %DIRVEC %SIDEVEC     # X-Produkt ist 1, wenn %SIDEVEC nach rechts zeigt
    GTI %TMP DATA:0                # Wenn X-Produkt > 0, ...
      JMPI CONTINUE_FORK           # ... dann mit FORK beginnen

    JMPI CONTINUE_WRITEFINISH_NOSIDE

  .ORG 0|15
  CONTINUE_WRITEFINISH_NOSIDE:
    SETV %SIDEVEC 0|0              # Es ist mindestens eine Zeile geschrieben, wir sind also nicht mehr in der ersten
    SKLR %CONTCORD                 # Zurück zum Anfang

    # Eine Zeile nach unter
    RTRI %DIRVEC DATA:1 DATA:0     # Nach recht drehen
    INPR %DIRVEC                   # Wenn das Molekül nicht passierbar,...
      PEEK %TMP %DIRVEC            # ... dann lösche ich es
    SEEK %DIRVEC                   # Vorwärts

    SCAN %TMP %DIRVEC              # Was ist vor uns (nach unten)?
    IFR %TMP %SHELL                # Ist das die Hülle?...
      SETR %SIDEVEC %DIRVEC        # ... Dann sind wir in der Ecke und müssen und das mit %SIDEVEC merken

    RTRI %DIRVEC DATA:0 DATA:1     # Nach links drehen -> Reproduktionsrichtung
    DPLR %CONTCORD                 # Continue-Position erreicht -> speichern

    # DP0 und DP1 wiederherstellen
    ADPI DATA:1
    SKLS
    ADPI DATA:0
    SKLS
    JMPI CONTINUE_LOOP

  .ORG 0|16
  CONTINUE_FORK:
    
    RTRI %DIRVEC DATA:0 DATA:1     # Richtung nach innen drehen zum schreiben
    RTRI %DIRVEC DATA:0 DATA:1
    SEEK %DIRVEC                   # Einen schritt vorwärt um innerhalb der Hülle zu sein

  # So lange nach Norden laufen, bis ich an der Hülle bin
  CONTINUE_FORK_NORTH:
    SCNI %TMP 0|-1                 # Was liegt nördlich?
    IFR %TMP %SHELL                # Ist das die Hülle?
      JMPI CONTINUE_FORK_WEST      # ... Dann nach Westen laufen
    SEKI 0|-1                      # Sonst einen Schritt nach Norden
    JMPI CONTINUE_FORK_NORTH       # Weiter nach Norden

  # So lange nach Westen laufen, bis ich an der Hülle bin 
  CONTINUE_FORK_WEST:
    SCNI %TMP -1|0                 # Was liegt westlich?
    IFR %TMP %SHELL                # Ist das die Hülle?
      JMPI CONTINUE_FORK_FINISH    # ... Dann FORKen
    SEKI -1|0                      # Sonst einen Schritt nach Westen
    JMPI CONTINUE_FORK_WEST       # Weiter nach Westen
    
  # FORK ausführen
  .ORG 0|17  
  CONTINUE_FORK_FINISH:
    SEKI -1|0                      # Einen Schritt nach Westen
    PUSV 1|0                       # Die obere linke Ecke des Kindes liegt im Osten
    PUSI REPRODUCTION_CHILD_INITAL_ENERGY   # Energie für das Kind auf den DS legen
    GDVS                           # Kind bekommt selbe DV Richtung wie Elternorganismus
    FRKS                           # FORKen, Alle 3 Werte werden vom Stack entfernt

    #SKLR %FORKCORD                 # Schreib DP an die obere rechte Ecke holen
    #SEEK %DIRVEC                   # 2x vorwärts neben die linke obere Ecke des Kindes
    #SEEK %DIRVEC

    #PUSH %DIRVEC                   # Die obere linke Ecke des Kindes liegt in Reproduktionsrichtung
    #PUSI REPRODUCTION_CHILD_INITAL_ENERGY   # Energie für das Kind auf den DS legen
    #GDVS                           # Kind bekommt selbe DV Richtung wie Elternorganismus
    #FRKS                           # FORKen, Alle 3 Werte werden vom Stack entfernt

    # Aufräumen und fertig
    #SETV %SIDEVEC 0|-1             # %SIDEVEC auf links für neue Reproduktion
    RTRI %DIRVEC DATA:0 DATA:1     # %DIRVEC wieder nach außen drehen
    RTRI %DIRVEC DATA:0 DATA:1
    RTRI %SIDEVEC DATA:0 DATA:1    # %SIDEVEC auf links für neue Reproduktion
    RTRI %SIDEVEC DATA:0 DATA:1
    SETI %DIRMASK DATA:0           # Beim nächsten Mal wird eine Richtung neu zufällig gewählt
    CRLR %CONTCORD                 # Beim nächsten Mal brauchen wir eine leere Zeilen Koordinate, damit von vorne begonnen wird
    #CRLR %FORKCORD                 # Auch die Koordinate für das FORK muss neu berechnet werden

    # DP0 und DP1 wiederherstellen
    ADPI DATA:1
    SKLS
    ADPI DATA:0
    SKLS

    JMPI CONTINUE_SAVE_AND_RET

  .ORG 0|18
  CONTINUE_SAVE_AND_RET:
    SKLS                                          # alten DP wiederherstellen

    SETV %TMP 0|0
    IFR %SIDEVEC %TMP                             # Für Mittelzeile sind wir fertig
      JMPI CONTINUE_SIDVEC_STORE_0

    CRSR %SIDEVEC %DIRVEC %SIDEVEC                # X-Produkt gibt 1 für rechts und -1 für links
    JMPI CONTINUE_STORE_STATE

  CONTINUE_SIDVEC_STORE_0:
    SETI %SIDEVEC DATA:0

  CONTINUE_STORE_STATE:
    # vor RET: "in einem Rutsch" speichern
    STATIC2_STORE %DIRMASK %SIDEVEC
    RET
.ENDP