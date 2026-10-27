#!/usr/bin/env python3
"""Opt-in Gemini smoke test. Never prints a key or writes a photo/request to disk."""
import argparse
import base64
import io
import json
import math
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
API = "https://generativelanguage.googleapis.com/v1beta/"
DEFAULT_MODEL = "gemini-3.1-flash-lite"
MAX_RESPONSE = 256 * 1024


class SmokeError(Exception):
    """Only locally authored messages are safe to print."""


def request_json(path, key, payload=None):
    body = json.dumps(payload, ensure_ascii=False).encode() if payload is not None else None
    request = urllib.request.Request(API + path, data=body, headers={
        "x-goog-api-key": key, "Content-Type": "application/json", "x-goog-api-client": "nutrix-smoke/1.0",
    })
    # Reject redirects to avoid forwarding the API key to another origin.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(request, timeout=100) as response:
            data = response.read(MAX_RESPONSE + 1)
        if len(data) > MAX_RESPONSE:
            raise SmokeError("Ответ Gemini слишком большой")
        return json.loads(data)
    except urllib.error.HTTPError as exc:
        explanations = {400: "Модель отклонила фото или JSON-схему", 401: "Ключ не принят",
                        403: "Нет доступа: проверьте ключ, проект и доступность региона", 404: "Модель недоступна",
                        429: "Квота или лимит запросов исчерпаны"}
        raise SmokeError(f"HTTP {exc.code}: {explanations.get(exc.code, 'Ошибка сервиса Gemini')}") from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise SmokeError("Соединение с Gemini не удалось. Проверьте HTTPS-доступ к generativelanguage.googleapis.com и настройки proxy/CA среды") from None


def prepare_image(path):
    try:
        from PIL import Image, ImageOps
    except ImportError:
        raise SmokeError("Установите окружение: bash tools/setup-gemini-test-env.sh") from None
    Image.MAX_IMAGE_PIXELS = 50_000_000
    with Image.open(path) as image:
        if image.width * image.height > Image.MAX_IMAGE_PIXELS:
            raise SmokeError("Слишком большое изображение")
        image = ImageOps.exif_transpose(image)
        image.thumbnail((1280, 1280))
        out = io.BytesIO()
        image.convert("RGB").save(out, format="JPEG", quality=85)  # No EXIF/GPS copied.
    data = out.getvalue()
    if not data or len(data) > 2 * 1024 * 1024:
        raise SmokeError("Фото не удалось уменьшить до 2 МиБ")
    return data


def payload(image, hint):
    if len(hint) > 1000:
        raise SmokeError("Подсказка длиннее 1000 символов")
    return {
        "systemInstruction": {"parts": [{"text": (ASSETS / "food-analysis-prompt.txt").read_text()}]},
        "contents": [{"role": "user", "parts": [
            {"text": "Проанализируй фото еды. Уточнение пользователя: " + (hint or "нет")},
            {"inlineData": {"mimeType": "image/jpeg", "data": base64.b64encode(image).decode()}},
        ]}],
        "generationConfig": {"responseMimeType": "application/json", "responseJsonSchema": json.loads((ASSETS / "food-analysis.schema.json").read_text()),
                             "maxOutputTokens": 4096, "candidateCount": 1},
    }


def parse_response(response):
    if response.get("promptFeedback", {}).get("blockReason") not in (None, "BLOCK_REASON_UNSPECIFIED"):
        raise SmokeError("Gemini не обработал изображение")
    candidates = response.get("candidates", [])
    if not candidates or candidates[0].get("finishReason") != "STOP":
        raise SmokeError("Gemini вернул незавершённый или заблокированный ответ")
    parts = candidates[0].get("content", {}).get("parts", [])
    text = "".join(p.get("text", "") for p in parts if not p.get("thought"))
    result = json.loads(text)
    required = json.loads((ASSETS / "food-analysis.schema.json").read_text())["required"]
    if not isinstance(result, dict) or any(k not in result for k in required) or type(result["isFood"]) is not bool:
        raise SmokeError("Неполная структура оценки")
    for field in ("portionGrams", "caloriesKcal", "proteinGrams", "fatGrams", "carbsGrams"):
        value = result[field]
        limit = 100_000 if field == "caloriesKcal" else 10_000
        if value is not None and (type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value <= limit):
            raise SmokeError("Некорректное числовое значение оценки")
    grams = result["portionGrams"]
    if grams == 0 or (grams is not None and sum(result[k] or 0 for k in ("proteinGrams", "fatGrams", "carbsGrams")) > grams * 1.1):
        raise SmokeError("Некорректная масса порции или БЖУ")
    if not result["isFood"]:
        raise SmokeError("На изображении не распознана еда")
    if not isinstance(result["dishName"], str) or not 1 <= len(result["dishName"].strip()) <= 200:
        raise SmokeError("Некорректное название блюда")
    ingredients = result["ingredients"]
    if not isinstance(ingredients, list) or not 1 <= len(ingredients) <= 32:
        raise SmokeError("Некорректный список продуктов")
    for ingredient in ingredients:
        if not isinstance(ingredient, dict) or not isinstance(ingredient.get("name"), str) or not 1 <= len(ingredient["name"].strip()) <= 120:
            raise SmokeError("Некорректное название продукта")
        value = ingredient.get("grams")
        if value is not None and (type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value <= 10_000):
            raise SmokeError("Некорректная масса продукта")
    if result["uncertainty"] is not None and (not isinstance(result["uncertainty"], str) or len(result["uncertainty"]) > 1000):
        raise SmokeError("Некорректное пояснение оценки")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Проверить ключ и перечислить модели, без фото и генерации")
    parser.add_argument("--image", type=Path, help="Явно выбранное тестовое фото еды")
    parser.add_argument("--hint", default="")
    parser.add_argument("--model", default=os.environ.get("GEMINI_MODEL", DEFAULT_MODEL))
    parser.add_argument("--dry-run", action="store_true", help="Подготовить фото/JSON без сети и API-ключа")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}", args.model):
        parser.error("Некорректное имя модели")
    if args.check and args.dry_run:
        parser.error("--dry-run работает только с --image и не выполняет проверку ключа")
    key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not args.dry_run and not key:
        parser.error("Добавьте GEMINI_API_KEY как секрет среды. Не передавайте ключ аргументом командной строки")
    if args.check:
        models, page = set(), None
        for _ in range(10):
            query = "models?pageSize=1000" + ("&pageToken=" + urllib.parse.quote(page, safe="") if page else "")
            response = request_json(query, key)
            models.update(m["name"].removeprefix("models/") for m in response.get("models", []) if "generateContent" in m.get("supportedGenerationMethods", []))
            page = response.get("nextPageToken")
            if not page:
                break
        else:
            raise SmokeError("Слишком много страниц моделей")
        print(json.dumps({"keyAccepted": True, "selectedModelAvailable": args.model in models, "models": sorted(models)}, ensure_ascii=False, indent=2))
    elif args.image:
        image = prepare_image(args.image)
        request = payload(image, args.hint)
        if args.dry_run:
            print(json.dumps({"model": args.model, "jpegBytes": len(image), "requestBytes": len(json.dumps(request).encode()), "networkUsed": False}))
        else:
            result = parse_response(request_json(f"models/{args.model}:generateContent", key, request))
            if result["portionGrams"]:
                result["per100g"] = {k: (None if result[k] is None else round(result[k] * 100 / result["portionGrams"], 1)) for k in ("caloriesKcal", "proteinGrams", "fatGrams", "carbsGrams")}
            print(json.dumps({"model": args.model, "estimate": result}, ensure_ascii=False, indent=2))
    else:
        parser.error("Нужно указать --check или --image PHOTO")


if __name__ == "__main__":
    try:
        main()
    except SmokeError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except (ValueError, OSError, KeyError, TypeError, AttributeError):
        # Upstream bodies/exceptions can contain sensitive data; expose only known local validation text.
        print("Проверка не завершена: проверьте фото, доступ к API, квоту и выбранную модель. Ключ и тело ответа не выводятся.", file=sys.stderr)
        sys.exit(1)
