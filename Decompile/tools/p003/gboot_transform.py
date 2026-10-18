"""Pure, byte-preserving whole-method smali replacement. No filesystem writes."""
import hashlib
import re


class PatchError(ValueError):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _methods(source):
    lines = source.splitlines(keepends=True)
    spans = []
    offset = 0
    opened = None
    for raw in lines:
        line = raw.rstrip(b'\r\n').decode('utf-8')
        if line.startswith('.method '):
            opened = (offset, line)
        if line == '.end method' and opened is not None:
            signature = opened[1].rsplit(' ', 1)[-1]
            spans.append((signature, opened[0], offset + len(raw), opened[1]))
            opened = None
        offset += len(raw)
    return spans


def _normalized(method):
    return (method.decode('utf-8').replace('\r\n', '\n').rstrip('\n') + '\n').encode('utf-8')


def _validate_replacement(after, signature, original_header):
    lines = after.splitlines()
    if (not lines or lines[0] != original_header or lines[-1] != '.end method'
            or sum(line.startswith('.method ') for line in lines) != 1
            or lines.count('.end method') != 1 or '\r' in after):
        raise PatchError('invalid-replacement')
    declarations = [re.fullmatch(r'\s*\.(locals|registers) (\d+)', line) for line in lines]
    declarations = [match for match in declarations if match]
    if len(declarations) != 1:
        raise PatchError('invalid-replacement')
    args, return_type = signature.split('(', 1)[1].split(')', 1)
    parameters = re.findall(r'\[*L[^;]+;|\[*[ZBSCIFJD]', args)
    parameter_count = sum(2 if p in ('J', 'D') else 1 for p in parameters)
    if ' static ' not in original_header:
        parameter_count += 1
    declared = int(declarations[0][2])
    total = declared + parameter_count if declarations[0][1] == 'locals' else declared
    expected_return = ('return-void' if return_type == 'V' else
                       'return-object' if return_type.startswith(('L', '[')) else
                       'return-wide' if return_type in ('J', 'D') else 'return')
    instructions = []
    for line in lines[1:-1]:
        instruction = line.strip()
        if instruction.startswith('...'):
            raise PatchError('invalid-replacement')
        if not instruction or instruction.startswith(('#', '.', ':')):
            continue
        code_tokens = ''.join(re.split(r'("(?:\\.|[^"\\])*")', instruction)[::2])
        if ('...' in code_tokens or re.search(r'(?<![\w/\-:])[vp][A-Za-z]\w*(?=[,\s}]|$)', code_tokens)
                or re.search(r'\{[^}]*\b(?:storageType|context)\b', code_tokens)):
            raise PatchError('invalid-replacement')
        for kind, index in re.findall(r'\b([vp])(\d+)\b', instruction.split('"', 1)[0]):
            if int(index) >= (total if kind == 'v' else parameter_count):
                raise PatchError('invalid-replacement')
        if instruction.startswith('return') and instruction.split()[0] != expected_return:
            raise PatchError('invalid-replacement')
        instructions.append(instruction)
    for index, instruction in enumerate(instructions):
        opcode = instruction.split()[0]
        if opcode.startswith('move-result'):
            if index == 0 or not instructions[index - 1].startswith('invoke-'):
                raise PatchError('invalid-replacement')
            previous_type = instructions[index - 1].rsplit(')', 1)[-1]
            expected = ('move-result-object' if previous_type.startswith(('L', '[')) else
                        'move-result-wide' if previous_type in ('J', 'D') else
                        'move-result' if previous_type != 'V' else None)
            if opcode != expected:
                raise PatchError('invalid-replacement')


def patch_class(source_bytes: bytes, expected_class_sha256: str,
                patches: list[dict]) -> tuple[bytes, dict]:
    valid_sha = lambda value: isinstance(value, str) and re.fullmatch(r'[0-9a-fA-F]{64}', value)
    if (not isinstance(source_bytes, bytes) or not valid_sha(expected_class_sha256)
            or not isinstance(patches, list) or not patches):
        raise PatchError('invalid-input')
    try:
        text = source_bytes.decode('utf-8-sig')
        for patch in patches:
            if (not isinstance(patch, dict) or not isinstance(patch.get('descriptor'), str)
                    or re.fullmatch(r'L[^;\s]+;->[^\s]+\([^\s]*\)[^\s]+', patch['descriptor']) is None
                    or not valid_sha(patch.get('beforeSha256'))
                    or not isinstance(patch.get('afterSmali'), str)):
                raise PatchError('invalid-input')
            patch['afterSmali'].encode('utf-8')
        if len({p['descriptor'] for p in patches}) != len(patches):
            raise PatchError('invalid-input')
    except (UnicodeError, TypeError) as error:
        raise PatchError('invalid-input') from error
    if _sha(source_bytes).lower() != expected_class_sha256.lower():
        raise PatchError('class-hash-mismatch')
    newline = b'\r\n' if b'\r\n' in source_bytes else b'\n'
    spans = _methods(source_bytes)
    selected = []
    owners = re.findall(r'(?m)^\.class[^\r\n]*\s(L[^;]+;)\r?$', text)
    for patch in patches:
        owner, signature = patch['descriptor'].split('->', 1)
        matches = [item for item in spans if item[0] == signature] if owners == [owner] else []
        if len(matches) != 1:
            raise PatchError('method-not-unique')
        selected.append((patch, matches[0]))
    for patch, (_, start, end, _) in selected:
        if _sha(_normalized(source_bytes[start:end])).lower() != patch['beforeSha256'].lower():
            raise PatchError('method-hash-mismatch')
    replacements = []
    methods = []
    for patch, (signature, start, end, header) in selected:
        before = source_bytes[start:end]
        after = patch['afterSmali'].replace('\r\n', '\n').rstrip('\n') + '\n'
        _validate_replacement(after, signature, header)
        after_bytes = after.replace('\n', newline.decode()).encode('utf-8')
        if not before.endswith(b'\n'):
            after_bytes = after_bytes[:-len(newline)]
        replacements.append((start, end, after_bytes))
        methods.append({'descriptor': patch['descriptor'], 'beforeSha256': _sha(_normalized(before)),
                        'afterSha256': _sha(after.encode('utf-8'))})
    result = source_bytes
    for start, end, after in sorted(replacements, reverse=True):
        result = result[:start] + after + result[end:]
    # Restore the original spans from the result: every byte outside the plan must survive.
    rebuilt = bytearray()
    cursor = 0
    shifted = 0
    for start, end, after in sorted(replacements):
        result_start = start + shifted
        result_end = result_start + len(after)
        if result[result_start:result_end] != after:
            raise PatchError('verification-failed')
        rebuilt.extend(result[cursor:result_start])
        rebuilt.extend(source_bytes[start:end])
        cursor = result_end
        shifted += len(after) - (end - start)
    rebuilt.extend(result[cursor:])
    if bytes(rebuilt) != source_bytes or len(_methods(result)) != len(spans):
        raise PatchError('verification-failed')
    for patch, _ in selected:
        matches = [m for m in _methods(result) if m[0] == patch['descriptor'].split('->', 1)[1]]
        expected_after = (patch['afterSmali'].replace('\r\n', '\n').rstrip('\n') + '\n').encode('utf-8')
        if len(matches) != 1 or _normalized(result[matches[0][1]:matches[0][2]]) != expected_after:
            raise PatchError('verification-failed')
    return result, {'methods': methods, 'nonTargetBytesUnchanged': True,
                    'beforeClassSha256': _sha(source_bytes), 'afterClassSha256': _sha(result)}
