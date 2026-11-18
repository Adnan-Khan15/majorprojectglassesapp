import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import wer  # noqa: E402


class WerTest(unittest.TestCase):
    def test_normalisation_and_distance(self):
        self.assertEqual(wer.norm("Where are my house-keys?"), ["where", "are", "my", "house", "keys"])
        self.assertEqual(wer.edit_distance("a b c".split(), "a b c".split()), 0)
        self.assertEqual(wer.edit_distance("a b c".split(), "a x c".split()), 1)    # substitution
        self.assertEqual(wer.edit_distance("a b c".split(), "a c".split()), 1)      # deletion
        self.assertEqual(wer.edit_distance("a b".split(), "a b c d".split()), 2)    # insertions

    def test_score_per_engine_and_condition(self):
        refs = {"q01.wav": ("where are my keys", "quiet"), "q02.wav": ("read this label", "noisy")}
        rows = [
            {"file": "q01.wav", "engine": "g", "transcript": "where are my keys", "ms": "900", "error": ""},
            {"file": "q02.wav", "engine": "g", "transcript": "read the label", "ms": "1100", "error": ""},
            {"file": "q01.wav", "engine": "a", "transcript": "", "ms": "", "error": "boom"},
        ]
        by = wer.score(rows, refs)
        self.assertEqual(by["g"]["err"], 1)
        self.assertEqual(by["g"]["words"], 7)
        self.assertEqual(by["g"]["cond"]["noisy"], [1, 3])
        self.assertEqual(by["a"]["failed"], 1)
        self.assertEqual(by["a"]["err"], 4)


if __name__ == "__main__":
    unittest.main()
