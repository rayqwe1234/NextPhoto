"""Check complete translations and formatting arguments without Android dependencies."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1] / 'app/src/main/res'

def strings(folder):
    return {e.attrib['name']: e.text or '' for e in ET.parse(root / folder / 'strings.xml').getroot()}

base = strings('values')
for folder in ('values-zh', 'values-b+zh+Hant'):
    translated = strings(folder)
    assert translated.keys() == base.keys(), f'{folder}: missing or extra keys'
    for key, value in base.items():
        tokens = lambda text: sorted(re.findall(r'%\d+\$[a-zA-Z]', text))
        assert tokens(value) == tokens(translated[key]), f'{folder}: placeholder mismatch for {key}'
        assert translated[key].strip('" '), f'{folder}: empty {key}'
print(f'PASS: {len(base)} strings in English, Simplified Chinese, and Traditional Chinese; placeholders match')
