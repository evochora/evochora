.REG %TEST1 0
.REG %TEST2 1

.PLACE STRUCTURE:10 10|10
.PLACE ENERGY:12 12|10
.PLACE DATA:14 14|10
.PLACE CODE:16 16|10

SETI %TEST1 DATA:2
SETI %TEST2 DATA:2
ADDI %TEST1 DATA:2

CALL MY_PROC WITH %TEST2 %TEST1
POKI %TEST1 0|1

.ORG 0|3
.INCLUDE "C:\Users\raine\IdeaProjects\evochora\src\main\resources\org\evochora\organism\prototypes\lib\lib.s"
.INCLUDE "C:\Users\raine\IdeaProjects\evochora\src\main\resources\org\evochora\organism\prototypes\lib\lib2.s"
#.REQUIRE "C:\Users\raine\IdeaProjects\evochora\src\main\resources\org\evochora\organism\prototypes\lib\lib.s" AS LIB