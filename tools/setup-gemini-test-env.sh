#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
python3 -m venv .venv-gemini
.venv-gemini/bin/python -m pip install --disable-pip-version-check -r tools/requirements-gemini.txt
printf '%s\n' 'Окружение готово. Проверка доступа: .venv-gemini/bin/python tools/gemini_food.py --check' 'Ключ читается только из переменной окружения GEMINI_API_KEY.'
