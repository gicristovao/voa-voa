"""Descarrega as zonas geográficas UAS (ED-318) da ANAC, valida-as e guarda-as em data/.

Corre automaticamente no GitHub Actions a cada 12 horas.
Se o download falhar ou o ficheiro vier inválido, termina com erro e
mantém a última versão boa que já está no repositório.
"""

import hashlib
import json
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ANAC_URL = "https://dnt.anac.pt/index.php?action=download-json"
USER_AGENT = "voa-voa/1.0 (+https://github.com/gicristovao/voa-voa)"
MIN_ZONES = 50

DATA_DIR = Path(__file__).resolve().parent.parent / "data"
ZONES_FILE = DATA_DIR / "zones.json"
META_FILE = DATA_DIR / "meta.json"


def download() -> bytes:
    req = urllib.request.Request(ANAC_URL, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.read()


def validate(raw: bytes) -> dict:
    text = raw.decode("utf-8-sig")
    data = json.loads(text)
    if data.get("type") != "FeatureCollection":
        raise ValueError(f"Tipo inesperado: {data.get('type')!r}")
    features = data.get("features")
    if not isinstance(features, list) or len(features) < MIN_ZONES:
        n = len(features) if isinstance(features, list) else 0
        raise ValueError(f"Poucas zonas no ficheiro: {n}")
    for f in features:
        props = f.get("properties") or {}
        geom = f.get("geometry") or {}
        if not props.get("identifier") or not props.get("type"):
            raise ValueError("Zona sem identificador ou tipo")
        if geom.get("type") not in ("Polygon", "MultiPolygon", "Point"):
            raise ValueError(f"Geometria desconhecida: {geom.get('type')!r}")
    return data


def main() -> int:
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    DATA_DIR.mkdir(exist_ok=True)
    meta = json.loads(META_FILE.read_text()) if META_FILE.exists() else {}

    try:
        data = validate(download())
    except Exception as exc:  # noqa: BLE001
        print(f"ERRO: {exc}", file=sys.stderr)
        return 1

    # Só as zonas contam para saber se houve alterações (o 'issued' muda sempre).
    features_hash = hashlib.sha256(
        json.dumps(data["features"], sort_keys=True, ensure_ascii=False).encode()
    ).hexdigest()

    changed = features_hash != meta.get("featuresHash")
    if changed:
        ZONES_FILE.write_text(
            json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
        )
        meta["updatedAt"] = now
        meta["issued"] = (data.get("metadata") or {}).get("issued")
        meta["count"] = len(data["features"])
        meta["featuresHash"] = features_hash
    meta["checkedAt"] = now
    META_FILE.write_text(json.dumps(meta, indent=2) + "\n")

    print(f"{len(data['features'])} zonas; {'ALTERADAS' if changed else 'sem alterações'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
