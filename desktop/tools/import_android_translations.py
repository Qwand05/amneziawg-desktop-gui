#!/usr/bin/env python3
"""Import the Android app's translations into the desktop GUI.

Reads ui/src/main/res/values-<locale>/strings.xml and updates
desktop/src/main/resources/i18n/strings_<locale>.properties for every key of
the English desktop file (strings.properties) that has an Android string of the
same name. Keys that only exist on the desktop are kept as they are, so this
can be re-run whenever the Android translations change:

    python3 desktop/tools/import_android_translations.py

Android plurals become key.one / key.few / key.many / key.other.
"""

import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "ui", "src", "main", "res")
I18N = os.path.join(ROOT, "desktop", "src", "main", "resources", "i18n")

# Android resource directory -> desktop file suffix.
LOCALES = {
    "values-de": "de",
    "values-es-rES": "es",
    "values-fr": "fr",
    "values-it": "it",
    "values-ja": "ja",
    "values-pl-rPL": "pl",
    "values-pt-rBR": "pt_BR",
    "values-pt-rPT": "pt_PT",
    "values-ru": "ru",
    "values-tr-rTR": "tr",
    "values-uk-rUA": "uk",
    "values-zh-rCN": "zh_CN",
}

# Android strings whose wording only needs adapting for the desktop.
TRANSFORMS = {
    "version_title": lambda s: s.replace("Android", "Linux"),
    # Android ends it with an escaped newline.
    "import_disclaimer": lambda s: re.sub(r"(\\n|\s)+$", "", s),
}


def read_properties(path):
    """Ordered key -> raw (still escaped) value of a .properties file."""
    result = {}
    if not os.path.exists(path):
        return result
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line.strip() or line.lstrip().startswith(("#", "!")):
                continue
            key, _, value = line.partition("=")
            result[key.strip()] = value
    return result


def android_text(element):
    text = "".join(element.itertext())
    # Android escapes; keep \n and \uXXXX, which .properties understands too.
    text = re.sub(r"\\([\'\"@?])", r"\1", text)
    if len(text) >= 2 and text[0] == text[-1] == '"':
        text = text[1:-1]
    return text.strip(" ")


def read_android(path):
    result = {}
    if not os.path.exists(path):
        return result
    for element in ET.parse(path).getroot():
        name = element.get("name")
        if element.get("translatable") == "false":
            continue
        if element.tag == "string":
            result[name] = android_text(element)
        elif element.tag == "plurals":
            for item in element:
                result[f"{name}.{item.get('quantity')}"] = android_text(item)
    return result


def escape(value):
    return value.replace("\n", "\\n") if not value.startswith(" ") else "\\" + value


def main():
    english = read_properties(os.path.join(I18N, "strings.properties"))
    plural_bases = {k.rsplit(".", 1)[0] for k in english if re.search(r"\.(one|few|many|other)$", k)}
    for res_dir, suffix in LOCALES.items():
        android = read_android(os.path.join(RES, res_dir, "strings.xml"))
        target = os.path.join(I18N, f"strings_{suffix}.properties")
        values = read_properties(target)
        imported = 0
        for key, text in android.items():
            base = key.rsplit(".", 1)[0] if "." in key else key
            if key not in english and base not in plural_bases:
                continue
            if not text:
                continue
            values[key] = escape(TRANSFORMS.get(base, lambda s: s)(text))
            imported += 1
        # Same order as the English file, then plural forms English lacks (few, many).
        order = list(english)
        extra = sorted(k for k in values if k not in english)
        with open(target, "w", encoding="utf-8") as f:
            f.write(f"# AmneziaWG desktop GUI: {suffix} strings.\n")
            f.write(f"# Partly imported from ui/src/main/res/{res_dir} by desktop/tools/import_android_translations.py.\n")
            for key in order + extra:
                if key in values:
                    f.write(f"{key}={values[key]}\n")
        print(f"{suffix}: {imported} imported, {len(values)} total", file=sys.stderr)


if __name__ == "__main__":
    main()
