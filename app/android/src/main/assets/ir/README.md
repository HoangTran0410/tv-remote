# ir/codes.json

Remote codes for the Phone app's infrared remote (`phone/ir/IrRemote.kt`), made by `dev/gen/ir_codes.py` from [Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB) (CC0-1.0, public domain; thanks to its contributors). Only `parsed` signals of protocols `Ir.kt` encodes (NEC, NECext, Samsung32, SIRC, RC5, RC6, RCA) and the Xiaomi raw frames are kept, keys named as in `Scenes.KEYS`. The commit used is in the file's `src`. Re-run: clone Flipper-IRDB to an empty folder, then `python3 -I dev/gen/ir_codes.py <folder>`.
