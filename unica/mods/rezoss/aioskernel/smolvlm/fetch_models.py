#!/usr/bin/env python3
"""Fetch the pinned SM8550 community export; verify every model before use."""
import hashlib
import pathlib
import sys
import urllib.request

REVISION = 'b7ff15a7727e5f006715571220bc097188938850'
FILES = {
    'hybrid_llama_qnn.pte': '5c2a99c16f5a30f7a89d110aa529f6b819a4369b0a1a7f390435534a9aa586de',
    'text_embedding_qnn.pte': 'f707b44ec523b7cc37df084bc60ad51accc3916631c2a29f883ed5c9a4141343',
    'vision_encoder_qnn.pte': '36c7e7154ccca001f5ad489510bcd3255914401a03d597cbe13f78dbf0d2b073',
    'tokenizer.json': '2500f374cdc89040032a7d8437b6ed0fe745667b1194c17914d062ee31701605',
}


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    destination = pathlib.Path(sys.argv[1])
    destination.mkdir(parents=True, exist_ok=True)
    for name, expected in FILES.items():
        target = destination / name
        if target.exists():
            if digest(target) != expected:
                raise SystemExit(f'Refusing to overwrite mismatched existing {target}')
            continue
        temporary = destination / (name + '.part')
        if temporary.exists():
            raise SystemExit(f'Remove or inspect interrupted download {temporary}')
        url = f'https://huggingface.co/K9FxNa/smolvlm_500m_instruct_hybrid/resolve/{REVISION}/{name}'
        with urllib.request.urlopen(url, timeout=120) as source, temporary.open('xb') as out:
            while chunk := source.read(1024 * 1024):
                out.write(chunk)
        if digest(temporary) != expected:
            raise SystemExit(f'Hash mismatch: {temporary}')
        temporary.rename(target)
        print(f'{expected}  {target}', flush=True)


if __name__ == '__main__':
    main()
