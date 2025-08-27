.ORG 0|0
NOP          # Position [0,0]
NOP          # Position [1,0]
.ORG 0|1     # Setze Position auf [0,1]
.INCLUDE "test_include_org_inc.s"
NOP          # Position [0,2] (sollte sein)