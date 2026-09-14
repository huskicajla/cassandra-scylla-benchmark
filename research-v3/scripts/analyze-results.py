import argparse
from pathlib import Path
import subprocess
import sys

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Pokrece zajednicku short-v3 analizu bez pisanja u sirove rezultate.')
    parser.add_argument('results', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    analysis = Path(__file__).resolve().parents[2] / 'Analiza_research-v3_short_v3'
    script = analysis / 'reprodukcija/analyze_short.py'
    if not script.is_file():
        parser.error('Nedostaje susjedni folder Analiza_research-v3_short_v3.')
    command = [sys.executable, str(script), '--results', str(args.results.resolve()), '--output', str((args.output or analysis).resolve())]
    raise SystemExit(subprocess.call(command))
