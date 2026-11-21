# SmartGlasses: Arduino IDE sketch

Generated from the PlatformIO project in `esp32-firmware/` by `scripts/make_arduino_sketch.py`.
**Don't edit files here**: change the originals in `esp32-firmware/src`, `include` or `lib`, then re-run the script.

## Open and flash with the Arduino IDE (2.x)

1. **Board support:** Tools → Board → Boards Manager → search **esp32** (by Espressif Systems) → install version **2.0.17**.
   Version 3.x will NOT compile: the audio code uses the 2.0.x I2S driver.
2. **Libraries:** Sketch → Include Library → Manage Libraries → install:
   - **NimBLE-Arduino** by h2zero, version **2.3.0 or newer**
   - **VL53L0X** by **Pololu**, version 1.3.1 (not the Adafruit one)
3. **Open** `SmartGlasses.ino` (File → Open). The other files in this folder open as tabs.
4. **Tools menu** (these settings matter):
   | Setting | Value |
   |---|---|
   | Board | **XIAO_ESP32S3** |
   | PSRAM | **OPI PSRAM** (required: photos and the speech buffer live in PSRAM) |
   | USB CDC On Boot | **Enabled** (serial console over USB) |
   | Flash Size | 8MB |
   | Partition Scheme | Default 4MB with spiffs (or the 8MB default) |
   | Core Debug Level | None or Error |
5. Plug in the board (hold **BOOT** while plugging in if it isn't detected), pick its port, click **Upload**.
6. Tools → Serial Monitor at **115200** baud, line ending "Newline". Type `?` and press Enter for the test commands.

The prebuilt image on the Releases page (`smartglasses-firmware-…-merged.bin`) is the same firmware, if you'd rather not set up the IDE.
