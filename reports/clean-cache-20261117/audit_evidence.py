"""Audit archived cold-cache evidence; --capture additionally inspects the retained local build."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def read(name):
    return json.loads((HERE / name).read_text(encoding='utf-8-sig'))


def sha(data):
    return hashlib.sha256(data).hexdigest()


def totals(folder):
    suites = [ET.parse(path).getroot() for path in folder.glob('TEST-*.xml')]
    return dict(suites=len(suites), **{key: sum(int(s.attrib[key]) for s in suites)
                for key in ('tests', 'failures', 'errors', 'skipped')})


def text_log(name):
    data = (HERE / name).read_bytes()
    return data.decode('utf-16' if data.startswith((b'\xff\xfe', b'\xfe\xff')) else 'utf-8-sig')


protocol = read('protocol.json')
build = read('result.json')
runtime = read('runtime-result.json')
gold = read('gold-result.json')
initial = read('initial-runner-failure.json')
expected = dict(suites=68, tests=710, failures=0, errors=0, skipped=0)
assert protocol['initialCacheFileCount'] == 0 and build['initialCacheFileCount'] == 0
assert not protocol['privateLocalConfigPresent']
assert protocol['globalSettingsOverridden'] and protocol['userSettingsOverridden'] and protocol['strictChecksums']
assert initial['originalWrapperExitCode'] == 1 and initial['originalMavenExitCode'] is None
assert initial['liveMavenProcesses'] == 0
assert build['mavenExitCode'] == 0 and build['offline'] and '-o' in build['mavenCommand']
assert totals(HERE / 'initial-surefire') == expected
assert totals(HERE / 'surefire') == expected
assert 'BUILD SUCCESS' in text_log('maven-offline-verify.log')
assert runtime['passed'] and len(runtime['checks']) == 8 and all(runtime['checks'].values())
assert runtime['processExited'] and runtime['portClosed'] and runtime['modelLogRecords'] == 0
assert gold['passed'] and gold['exitCode'] == 0 and gold['dryRun'] and gold['modelLogRecords'] == 0
assert gold['overall']['total'] == 200 and gold['overall']['correct'] == 200
assert all(item['modelCalls'] == 0 for item in (runtime, gold)) and build['externalModelCalls'] == 0
old_protocol = json.loads((ROOT / 'reports/demo-http-20-20261117/protocol.json').read_bytes())
assert gold['datasetSha256'] == old_protocol['datasetSha256']

if '--capture' in sys.argv:
    assert not (HERE / 'manifest.json').exists(), 'Never overwrite archived evidence'
    source = Path(protocol['sourceDirectory'])
    cache = Path(protocol['repositoryDirectory'])
    source_zip = source.parent / 'source.zip'
    assert sha(source_zip.read_bytes()) == protocol['sourceArchiveSha256']
    shutil.copyfile(source_zip, HERE / 'source.zip')
    source_witness = {}
    with zipfile.ZipFile(source_zip) as archive:
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            data = archive.read(entry)
            assert (source / entry.filename).read_bytes() == data, 'Exported file changed: ' + entry.filename
            source_witness[entry.filename] = sha(data)
    assert not (source / 'src/main/resources/application-local.yml').exists()
    jars = list(cache.rglob('*.jar'))
    poms = list(cache.rglob('*.pom'))
    inventory = []
    by_digest = {}
    for path in sorted(jars + poms):
        data = path.read_bytes()
        checksum_path = Path(str(path) + '.sha1')
        assert checksum_path.exists(), 'Missing repository checksum: ' + str(path)
        assert hashlib.sha1(data).hexdigest() == checksum_path.read_text().split()[0].lower()
        relative = str(path.relative_to(cache)).replace('\\', '/')
        digest = sha(data)
        inventory.append(dict(path=relative, bytes=len(data), sha256=digest, sha1Verified=True))
        if path.suffix == '.jar':
            by_digest.setdefault(digest, []).append(relative)
    remote_files = list(cache.rglob('_remote.repositories'))
    for path in remote_files:
        for line in path.read_text().splitlines():
            if line and not line.startswith('#'):
                assert '>acceptance-public-mirror=' in line, 'Unexpected repository origin: ' + line
    jar_path = Path(build['executableJar'])
    assert sha(jar_path.read_bytes()) == build['executableJarSha256']
    embedded = []
    with zipfile.ZipFile(jar_path) as archive:
        for name in archive.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                digest = sha(archive.read(name))
                embedded.append(dict(entry=name, sha256=digest, cacheOrigins=by_digest.get(digest, [])))
    # Repackage may embed jarmode-tools from a cached build-tool jar rather than a top-level artifact.
    if any(not item['cacheOrigins'] for item in embedded):
        for path in jars:
            with zipfile.ZipFile(path) as archive:
                for name in archive.namelist():
                    if name.endswith('.jar'):
                        digest = sha(archive.read(name))
                        for item in embedded:
                            if item['sha256'] == digest and not item['cacheOrigins']:
                                item['cacheOrigins'].append(str(path.relative_to(cache)).replace('\\', '/') + '!' + name)
    assert all(item['cacheOrigins'] for item in embedded), 'Packaged dependency missing from cold cache'
    payload = dict(sourceFilesSha256=source_witness, cacheArtifacts=inventory,
                   repositoryOriginFileCount=len(remote_files), embeddedRuntimeLibraries=embedded,
                   jarCount=len(jars), pomCount=len(poms), sourceExportFileCount=len(source_witness))
    (HERE / 'dependency-witness.json').write_text(json.dumps(payload, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    manifest = dict(sourceCommit=protocol['sourceCommit'], sourceObjects=protocol['sourceObjects'],
                    testTotals=expected, runtimeChecks=8, gold='200/200', externalModelCalls=0,
                    cacheStartFiles=0, cacheJarCount=len(jars), cachePomCount=len(poms),
                    sourceExportFileCount=len(source_witness), embeddedRuntimeLibraryCount=len(embedded),
                    originalMavenExitCode=None, originalWrapperExitCode=1, verifiedOfflineMavenExitCode=0,
                    artifactsSha256={str(path.relative_to(HERE)).replace('\\', '/'): sha(path.read_bytes())
                                     for path in sorted(HERE.rglob('*')) if path.is_file()})
    (HERE / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

manifest = read('manifest.json')
for name, expected_sha in manifest['artifactsSha256'].items():
    assert sha((HERE / name).read_bytes()) == expected_sha, 'Archive changed: ' + name
assert sha((HERE / 'source.zip').read_bytes()) == protocol['sourceArchiveSha256']
assert sha((HERE / 'run_cold_build.ps1').read_bytes()) == protocol['runnerSha256']
assert sha((HERE / 'settings.xml').read_bytes()) == protocol['settingsSha256']
generated = subprocess.check_output(['git', 'archive', '--format=zip', protocol['sourceCommit'],
                                     'pom.xml', 'src', 'data', 'scripts'], cwd=ROOT)
assert sha(generated) == protocol['sourceArchiveSha256'], 'Archived source is not the frozen Git export'
for path, expected_object in protocol['sourceObjects'].items():
    actual = subprocess.check_output(['git', 'rev-parse', protocol['sourceCommit'] + ':' + path], cwd=ROOT, text=True).strip()
    assert actual == expected_object
witness = read('dependency-witness.json')
assert witness['jarCount'] == build['jarCount'] and witness['pomCount'] == build['pomCount']
assert len(witness['cacheArtifacts']) == build['jarCount'] + build['pomCount']
assert all(item['sha1Verified'] for item in witness['cacheArtifacts'])
assert all(item['cacheOrigins'] for item in witness['embeddedRuntimeLibraries'])
with zipfile.ZipFile(HERE / 'source.zip') as archive:
    for name, expected_sha in witness['sourceFilesSha256'].items():
        assert sha(archive.read(name)) == expected_sha
print(json.dumps(dict(audit='PASS', tests=710, runtimeChecks=8, gold='200/200', initialCacheFiles=0,
                      cachedJars=witness['jarCount'], cachedPoms=witness['pomCount'],
                      exportedFiles=witness['sourceExportFileCount'],
                      embeddedRuntimeLibraries=len(witness['embeddedRuntimeLibraries']), modelCalls=0)))
