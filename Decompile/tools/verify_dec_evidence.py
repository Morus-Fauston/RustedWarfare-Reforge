"""只读核验 DEC 归档身份与迁移关系，不执行采集器或改写旧清单。"""
from pathlib import Path
import argparse
import csv
import hashlib
import json
import re

MIGRATION = 'Docs/迁移与记录/2026-10-17-完整报告实际迁移.files.json'
REPAIRS = 'Docs/迁移与记录/2026-10-17-DEC报告修复.files.json'
START = '<!-- DEC-MAINTENANCE-START -->'
END = '<!-- DEC-MAINTENANCE-END -->'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().lower()


def inside(root, value):
    path = (root / value).resolve()
    if not path.is_relative_to(root):
        raise ValueError('Path outside workspace: ' + str(value))
    return path


def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def verify(root, migration_name=MIGRATION, repairs_name=REPAIRS):
    root = root.resolve()
    migration = load(inside(root, migration_name))
    repairs = load(inside(root, repairs_name)) if repairs_name else {'reports': []}
    report_updates = {row['path']: row for row in repairs.get('reports', [])}
    moved = {inside(root, row['old']): row for row in migration['files']}
    references = {inside(root, row['path']): row for row in migration.get('referenceChanges', [])}
    errors, counts = [], {'reports': 0, 'artifacts': 0, 'logs': 0, 'methodLocations': 0, 'links': 0}

    def check(condition, kind, path):
        if not condition:
            errors.append({'kind': kind, 'path': str(path)})

    def original(path):
        """旧清单必须对旧字节核验，不拿新报告的新哈希冒充旧身份。"""
        if path in moved:
            return inside(root, moved[path]['originalBytes'])
        if path in references:
            return inside(root, references[path]['originalBytes'])
        return path

    dec_rows = [row for row in migration['files'] if row['new'].startswith('Docs/Research-DEC/DEC-')]
    check(bool(dec_rows), 'empty-dec-report-inventory', migration_name)
    for row in dec_rows:
        counts['reports'] += 1
        target, backup = inside(root, row['new']), inside(root, row['originalBytes'])
        check(not inside(root, row['old']).exists(), 'old-report-path-reappeared', row['old'])
        check(backup.is_file() and digest(backup) == row['originalSha256'].lower(), 'original-report-identity', row['old'])
        check(target.is_file(), 'missing-report', row['new'])
        if not target.is_file():
            continue
        data = target.read_bytes()
        update = report_updates.get(row['new'])
        expected = update['sha256'] if update else row['migratedSha256']
        check(digest(target) == expected.lower(), 'current-report-identity', row['new'])
        if update:
            # 维护块是显式追加，不允许删除或改写原报告技术正文。
            begin = data.find(START.encode())
            end = data.find(END.encode())
            check(begin >= 0 and end > begin and data.count(START.encode()) == 1 and data.count(END.encode()) == 1,
                  'maintenance-block', row['new'])
            if begin >= 0 and end > begin:
                body = data[:begin] + data[end + len(END.encode()) + 2:]
                check(hashlib.sha256(body).hexdigest() == row['migratedSha256'].lower(), 'technical-body-changed', row['new'])

    # 迁移基线也固定了旧 manifest/CSV/脚本，防止被修改的清单自证通过。
    counts['baselineFiles'] = 0
    for row in migration.get('protected', []):
        if not row['path'].startswith('.scratch/开发票据/独立反编译/'):
            continue
        counts['baselineFiles'] += 1
        path = inside(root, row['path'])
        saved = original(path)
        check(saved.is_file() and digest(saved) == row['sha256'].lower(), 'baseline-file-identity', path)
        if path in references:
            check(path.is_file() and digest(path) == references[path]['afterSha256'].lower(), 'baseline-current-reference', path)

    batches = {inside(root, row['old']).parent for row in dec_rows}
    for batch in sorted(batches):
        manifest = batch / 'manifest.json'
        check(manifest.is_file(), 'missing-manifest', manifest)
        if not manifest.is_file():
            continue
        record = load(manifest)
        for row in record.get('artifacts', []):
            counts['artifacts'] += 1
            path = inside(root, str(batch / row['path']))
            saved = original(path)
            check(saved.is_file() and digest(saved) == row['sha256'].lower(), 'archive-artifact-identity', path)
            if saved.is_file():
                size = row.get('sizeBytes', row.get('bytes'))
                if size is not None:
                    check(saved.stat().st_size == size, 'archive-artifact-size', path)
            if path in references:
                check(path.is_file() and digest(path) == references[path]['afterSha256'].lower(), 'rebased-reference-identity', path)
        for command in record.get('commands', []):
            for stream in ('stdout', 'stderr'):
                if stream in command and stream + 'Sha256' in command:
                    counts['logs'] += 1
                    path = inside(root, str(batch / command[stream]))
                    check(path.is_file() and digest(path) == command[stream + 'Sha256'].lower(), 'command-log-identity', path)
        methods = batch / 'methods.csv'
        if methods.exists():
            for row in csv.DictReader(methods.open(encoding='utf-8-sig', newline='')):
                counts['methodLocations'] += 1
                path = inside(root, str(batch / row['evidencePath']))
                location = re.search(r'(\d+)-(\d+)', row['evidenceLocation'])
                check(path.is_file(), 'missing-method-evidence', path)
                if path.is_file() and location:
                    lines = path.read_text(encoding='utf-8-sig').splitlines()
                    check(1 <= int(location[1]) <= int(location[2]) <= len(lines), 'method-range', path)

    for path in sorted((root / 'Docs/Research-DEC').glob('*.md')):
        for match in re.finditer(r'\]\((?:<([^>\n]+)>|([^\n)]+))\)', path.read_text(encoding='utf-8-sig')):
            url = match[1] or match[2]
            if re.match(r'^(https?:|mailto:|#)', url):
                continue
            counts['links'] += 1
            target = inside(root, str(path.parent / url.split('#')[0]))
            check(target.exists(), 'broken-report-link', url)
            anchor = re.search(r'#L(\d+)(?:-L(\d+))?$', url)
            if anchor and target.is_file():
                lines = target.read_text(encoding='utf-8-sig').splitlines()
                check(1 <= int(anchor[1]) <= int(anchor[2] or anchor[1]) <= len(lines), 'report-line-range', url)
    return {'passed': not errors, 'checks': counts, 'errors': errors,
            'coverage': '归档产物/日志身份、报告迁移与已登记维护块、方法文件/行号及本地链接；不重新证明语义、输入工具当前身份、原版运行或研究验收。'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    try:
        result = verify(args.root)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        result = {'passed': False, 'errors': [{'kind': 'verification-error', 'detail': str(exc)}]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
