#!/usr/bin/env python3
"""Generate a signed, exact-base Tinker DEX patch and GitHub discovery catalog.
Keep the immutable base APK/mapping; every revision is built against that base, never another patch.
"""
import argparse, hashlib, json, os, pathlib, re, subprocess, tempfile, zipfile
import xml.etree.ElementTree as ET


def command(args, **kwargs):
    result = subprocess.run([str(a) for a in args], capture_output=True, text=True, **kwargs)
    if result.returncode:
        raise RuntimeError(result.stdout[-5000:] + result.stderr[-5000:])
    return result.stdout


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for flag in ('base', 'new', 'cli', 'keystore', 'apksigner', 'aapt', 'out', 'base-id', 'alias'):
        p.add_argument('--'+flag, required=True)
    p.add_argument('--base-code', required=True, type=int)
    p.add_argument('--revision', required=True, type=int)
    p.add_argument('--notes', default='')
    p.add_argument('--password-env', default='READER_PATCH_STORE_PASSWORD')
    args = p.parse_args()
    require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,70}', args.base_id), 'Invalid base ID')
    require(0 < args.revision <= 2147483647 and 0 < args.base_code <= 2147483647,
            'Revision/base code must be positive 32-bit integers')
    require(os.environ.get(args.password_env), 'Set the signing password in the named environment variable')
    base, new = pathlib.Path(args.base).resolve(), pathlib.Path(args.new).resolve()
    # Reject resource/manifest changes: this first implementation intentionally loads DEX only.
    with zipfile.ZipFile(base) as a, zipfile.ZipFile(new) as b:
        # Install-time ART profiles embed DEX checksums and naturally change after R8.
        # They are not loaded from the patch; every actual resource and native library stays immutable.
        profiles = {'assets/dexopt/baseline.prof', 'assets/dexopt/baseline.profm'}
        resource = lambda name: name not in profiles and (name in ('AndroidManifest.xml', 'resources.arsc') or name.startswith(('res/', 'assets/', 'lib/')))
        names = {n for n in set(a.namelist()) | set(b.namelist()) if resource(n) and not n.endswith('/')}
        for name in names:
            require(name in a.namelist() and name in b.namelist() and a.read(name) == b.read(name), f'{name} changed: publish an APK/resource update instead')
        require(any(a.read(n) != b.read(n) for n in a.namelist() if n.endswith('.dex') and n in b.namelist()), 'No native code change')
    versions = []
    signers = []
    for apk in (base, new):
        badging = command([args.aapt, 'dump', 'badging', apk])
        version = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging).groups()
        versions.append(version)
        certificate = command([args.apksigner, 'verify', '--print-certs', apk])
        signers.append(re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)', certificate).group(1))
    require(versions[0] == versions[1] and int(versions[0][1]) == args.base_code, 'Keep base package/version for hotfix builds')
    require(signers[0] == signers[1], 'The new APK must use the base certificate')
    out = pathlib.Path(args.out).resolve(); out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='tinker-', dir=out) as temp:
        temp = pathlib.Path(temp)
        # Official semantic class comparator protects loaders across ALL DEX files.
        helper = pathlib.Path(__file__).with_name('CheckHotfixLoader.java').resolve()
        command(['javac', '-cp', args.cli, '-d', temp, helper])
        loader_check = command(['java', '-cp', str(temp)+os.pathsep+args.cli, 'CheckHotfixLoader', base, new])
        (out/'loader-check.txt').write_text(loader_check)
        config = ET.Element('tinkerPatch')
        def issue(name, values):
            node = ET.SubElement(config, 'issue', id=name)
            for key, value in values: ET.SubElement(node, key, value=value)
            return node
        issue('property', [('ignoreWarning','false'),('allowLoaderInAnyDex','true'),('removeLoaderForAllDex','true'),('useSign','false'),('isProtectedApp','false'),('supportHotplugComponent','false')])
        issue('dex', [('dexMode','jar'),('pattern','classes*.dex'),('loader','com.tencent.tinker.loader.*'),
                      ('loader','com.linguareader.app.hotfix.bootstrap.ReaderApplication'),
                      # The stricter all-DEX semantic check above already passed. Allow only
                      # these loaders' non-runtime debug differences in the CLI's per-DEX check.
                      ('ignoreChange','com.tencent.tinker.loader.*'),
                      ('ignoreChange','com.linguareader.app.hotfix.bootstrap.ReaderApplication')])
        package = ET.SubElement(config,'issue',id='packageConfig')
        for name,value in {'TINKER_ID':args.base_id,'NEW_TINKER_ID':args.base_id+'-r'+str(args.revision),
                           'BASE_VERSION_CODE':str(args.base_code),'PATCH_REVISION':str(args.revision), 'BASE_APK_SHA256':hashlib.sha256(base.read_bytes()).hexdigest()}.items():
            ET.SubElement(package,'configField',name=name,value=value)
        xml = temp/'config.xml'; ET.ElementTree(config).write(xml,encoding='UTF-8',xml_declaration=True)
        generated = temp/'generated'
        result = subprocess.run(['java','-jar',args.cli,'-old',str(base),'-new',str(new),'-config',str(xml),'-out',str(generated)],capture_output=True,text=True)
        (out/'tinker-build.log').write_text(result.stdout+result.stderr)
        if result.returncode: raise RuntimeError('Tinker generation failed; see '+str(out/'tinker-build.log'))
        unsigned = generated/'patch_unsigned.apk'
        require(unsigned.is_file(), 'Tinker did not generate patch_unsigned.apk; inspect tinker-build.log')
        artifact = out/f'{args.base_id}-r{args.revision}.lrpatch'
        command([args.apksigner,'sign','--ks',args.keystore,'--ks-key-alias',args.alias,
                 '--ks-pass','env:'+args.password_env,'--key-pass','env:'+args.password_env,
                 '--v1-signing-enabled','true','--v2-signing-enabled','false','--v3-signing-enabled','false',
                 '--min-sdk-version','23','--out',artifact,unsigned])
        verifier = pathlib.Path(__file__).with_name('VerifyHotfixSignature.java').resolve()
        command(['javac', '-d', temp, verifier])
        verification = command(['java', '-cp', temp, 'VerifyHotfixSignature', artifact, signers[0]])
        (out/'signature.txt').write_text(verification)
        require(artifact.stat().st_size <= 64 * 1024 * 1024, 'Patch exceeds the client download limit')
    patch = dict(baseId=args.base_id, baseVersionCode=args.base_code, revision=args.revision, assetName=artifact.name,
                 sha256=hashlib.sha256(artifact.read_bytes()).hexdigest(),bytes=artifact.stat().st_size,notes=args.notes)
    (out/'native-patches.json').write_text(json.dumps(dict(schemaVersion=1,patches=[patch]),ensure_ascii=False,indent=2))
    print(json.dumps(patch,ensure_ascii=False,indent=2))

if __name__ == '__main__': main()
