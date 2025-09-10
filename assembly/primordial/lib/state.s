#lib/state_mgmt.s
# State-Management mit Burst-Load/Store Pattern

# Row-above Burst-Load mit integriertem ENTER/EXIT
# Erwartet den initialisierten State in der zeile direkt über der Stelle an dem der IP zum Zeitpunkt des Aufruzfs des Markos ist
.MACRO STATIC3_LOAD D0 D1 D2
  DPLS            # alten DP sichern
  SYNC            # DP := IP (an PROC_START binden)

  SEKI -1|0
  SCNI D0 0|-1    # Slot 0 (x=0, y=-1)
  SEKI 1|0
  SCNI D1 0|-1    # Slot 1 (x=1)
  SEKI 1|0
  SCNI D2 0|-1    # Slot 2 (x=2)
  SEKI -1|0
  SEKI -1|0       # DP zurück nach x=0

  DPLS            # DP auf LS sichern
  SWPL            # Alte DP position nach unten auf den stack swappen
  SKLS            # alten DP wiederherstellen
.ENDM

# Row-above Burst-Store mit integriertem ENTER/EXIT
.MACRO STATIC3_STORE S0 S1 S2
  DPLS            # Caller DP sichern
  SWPL            # PROC_START position im LS nach unten swappen
  SKLS            # Active DP nach PROC_START holen
  #SEKI -1|0
  PPKI S0 0|-1
  SEKI 1|0
  PPKI S1 0|-1
  SEKI 1|0
  PPKI S2 0|-1
  SEKI -1|0
  SEKI -1|0

  SKLS            # Caller DP wiederherstellen
.ENDM