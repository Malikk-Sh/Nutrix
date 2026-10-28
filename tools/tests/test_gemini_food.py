import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

MODULE = Path(__file__).resolve().parents[1] / "gemini_food.py"
spec = importlib.util.spec_from_file_location("gemini_food", MODULE)
food = importlib.util.module_from_spec(spec)
spec.loader.exec_module(food)


class GeminiFoodTest(unittest.TestCase):
    def estimate(self):
        return dict(isFood=True, dishName="Гречка", ingredients=[dict(name="Гречка", grams=200)],
                    portionGrams=200, caloriesKcal=220, proteinGrams=8, fatGrams=2,
                    carbsGrams=40, uncertainty="Масло неизвестно")

    def envelope(self, data, reason="STOP"):
        return dict(candidates=[dict(finishReason=reason, content=dict(parts=[dict(text=json.dumps(data))]))])

    def test_hint_is_escaped_and_schema_is_shared(self):
        hint = 'Соус "без сахара"\nи масло'
        body = json.loads(json.dumps(food.payload(b"jpeg", hint)))
        self.assertIn(hint, body["contents"][0]["parts"][0]["text"])
        self.assertEqual(body["generationConfig"]["responseJsonSchema"], json.loads((food.ASSETS / "food-analysis.schema.json").read_text()))

    def test_unknown_weight_stays_null(self):
        value = self.estimate()
        value["portionGrams"] = None
        self.assertIsNone(food.parse_response(self.envelope(value))["portionGrams"])

    def test_rejects_negative_mass_excess_macros_and_truncation(self):
        for change in (dict(portionGrams=-1), dict(proteinGrams=1000), dict(caloriesKcal=float("nan"))):
            with self.subTest(change=change), self.assertRaises(food.SmokeError):
                food.parse_response(self.envelope(self.estimate() | change))
        with self.assertRaises(food.SmokeError):
            food.parse_response(self.envelope(self.estimate(), "MAX_TOKENS"))

    def test_http_error_reports_status_without_upstream_secret(self):
        class FailingOpener:
            def open(self, *args, **kwargs):
                raise urllib.error.HTTPError("https://example.invalid", 429, "PRIVATE-SECRET", {}, io.BytesIO(b"PRIVATE-SECRET"))
        with patch("urllib.request.build_opener", return_value=FailingOpener()):
            with self.assertRaises(food.SmokeError) as error:
                food.request_json("models", "PRIVATE-SECRET")
        self.assertIn("429", str(error.exception))
        self.assertNotIn("PRIVATE-SECRET", str(error.exception))

    def test_png_is_reencoded_as_small_jpeg_without_metadata(self):
        from PIL import Image, PngImagePlugin
        with tempfile.TemporaryDirectory() as directory:
            image = Image.new("RGB", (2000, 1500), "green")
            metadata = PngImagePlugin.PngInfo()
            metadata.add_text("private", "metadata")
            path = Path(directory) / "image.png"
            image.save(path, pnginfo=metadata)
            data = food.prepare_image(path)
            result = Image.open(io.BytesIO(data))
            self.assertEqual(result.format, "JPEG")
            self.assertLessEqual(max(result.size), 1280)
            self.assertFalse(result.getexif())
            self.assertNotIn("private", result.info)


if __name__ == "__main__":
    unittest.main()
