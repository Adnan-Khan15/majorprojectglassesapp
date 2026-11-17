// Serial test console (115200 baud). Single keys act instantly; words need Enter.
//   p              simulate a button tap (photo + distance -> phone)
//   h              simulate a 2 s hold-to-talk (Phase 3 M6)
//   ?              list commands
//   caps           what's connected and working, settings, reset reason
//   tone [hz] [ms] speaker test tone (default 1000 Hz, 500 ms)
//   vol [0-100]    volume as % of the ceiling (saved)
//   ceil [1-100]   loudness ceiling as % of full scale (saved; default 50)
//   mic [s]        mic level meter, both PDM slots, every 250 ms (default 3 s)
//   tof [n]        n single ToF readings (default 10), then median/spread
//   set <key> <v>  hold | tofn | tofb | micgain   (saved)
#pragma once

namespace console {
typedef void (*Action)();
void begin(Action tap, Action hold);
/** Call from loop(): reads available serial input and runs complete commands. */
void poll();
}
