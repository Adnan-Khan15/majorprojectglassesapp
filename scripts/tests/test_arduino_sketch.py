"""The Arduino IDE sketch must match the PlatformIO sources it is generated from."""
import os
import subprocess
import sys
import unittest

SCRIPT = os.path.join(os.path.dirname(__file__), "..", "make_arduino_sketch.py")


class ArduinoSketchTest(unittest.TestCase):
    def test_sketch_is_up_to_date(self):
        r = subprocess.run([sys.executable, SCRIPT, "--check"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


if __name__ == "__main__":
    unittest.main()
