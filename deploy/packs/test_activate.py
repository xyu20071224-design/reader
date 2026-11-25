import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import shlex
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

HERE = Path(__file__).resolve().parent

def module(name):
    spec=importlib.util.spec_from_file_location(name,HERE/(name+'.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

a=module('activate');c=module('install_config')

class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='lr-activate-');self.root=Path(self.temporary.name);self.public=self.root/'public'
    def tearDown(self):self.temporary.cleanup()
    def stage(self,identity='one',version='1.0.0',data=b'payload'):
        stage=self.root/('stage-'+identity+'-'+version+'-'+str(len(list(self.root.iterdir()))));stage.mkdir()
        for name in ['activate.py','install-ubuntu.sh','install_config.py']:shutil.copy2(HERE/name,stage/name)
        body={'packId':identity,'version':version,'type':'dictionary','schemaVersion':1,'minAppVersion':1}
        archive=stage/'input.lrpack'
        with zipfile.ZipFile(archive,'w') as z:z.writestr('manifest.json',json.dumps(body));z.writestr('data',data)
        sha=a.digest(archive);relative='site/packs/files/'+sha+'/fixture.lrpack';destination=stage/relative;destination.parent.mkdir(parents=True);archive.rename(destination)
        p={'packId':identity,'version':version,'type':'dictionary','minAppVersion':1,'name':'fixture.lrpack','bytes':destination.stat().st_size,'sha256':sha,'urls':['https://example.test:8443/packs/files/'+sha+'/fixture.lrpack']}
        (stage/'site/packs/index.json').write_text(json.dumps({'formatVersion':1,'packs':[p]}))
        (stage/'deployment.json').write_text(json.dumps({'formatVersion':1,'baseUrl':'https://example.test:8443'}));self.resum(stage);return stage,p
    def resum(self,stage):
        rows=[a.digest(f)+'  '+f.relative_to(stage).as_posix() for f in sorted(stage.rglob('*')) if f.is_file() and f.name!='SHA256SUMS']
        (stage/'SHA256SUMS').write_text('\n'.join(rows)+'\n')
    def index(self):return (self.public/'packs/index.json').read_bytes()

    def test_verified_activation_is_idempotent_and_preserves_unrelated_catalogue(self):
        one,p=self.stage();a.activate(one,self.public);first=self.index();self.assertFalse(a.activate(one,self.public)['changed']);self.assertEqual(first,self.index())
        two,_=self.stage('two');a.activate(two,self.public);result=json.loads(self.index());self.assertEqual(['one','two'],[p['packId'] for p in result['packs']]);self.assertIn('one@1.0.0',result['publishedVersions']);self.assertTrue((self.public/'packs/files'/p['sha256']/p['name']).is_file())
    def test_upload_tampering_extra_files_and_archive_identity_fail_before_catalogue_change(self):
        one,_=self.stage();a.activate(one,self.public);before=self.index()
        two,_=self.stage('two');(two/'install-ubuntu.sh').write_text('tampered');self.assertRaises(ValueError,a.activate,two,self.public);self.assertEqual(before,self.index())
        shutil.copy2(HERE/'install-ubuntu.sh',two/'install-ubuntu.sh');self.resum(two);(two/'unchecked').write_text('extra');self.assertRaises(ValueError,a.activate,two,self.public);self.assertEqual(before,self.index())
        (two/'unchecked').unlink();index=two/'site/packs/index.json';v=json.loads(index.read_text());v['packs'][0]['packId']='wrong';index.write_text(json.dumps(v));self.resum(two);self.assertRaises(ValueError,a.activate,two,self.public);self.assertEqual(before,self.index())
    def test_index_write_failure_retains_old_history_and_allows_a_safe_retry(self):
        one,_=self.stage();a.activate(one,self.public);before=self.index();two,_=self.stage('two');replace=os.replace
        def fail(source,target):
            if Path(target).resolve()==(self.public/'packs/index.json').resolve():raise OSError('injected publication failure')
            replace(source,target)
        with patch.object(a.os,'replace',side_effect=fail):self.assertRaises(OSError,a.activate,two,self.public)
        self.assertEqual(before,self.index());self.assertFalse((self.public/'packs/.publish.lock').exists());a.activate(two,self.public);self.assertEqual(2,len(json.loads(self.index())['packs']))
    def test_version_history_survives_upgrade_and_blocks_changed_old_version(self):
        one,_=self.stage();a.activate(one,self.public);two,_=self.stage('one','2.0.0');a.activate(two,self.public);before=self.index();old,_=self.stage('one','1.0.0',b'changed');self.assertRaisesRegex(ValueError,'Same pack version',a.activate,old,self.public);self.assertEqual(before,self.index())
    def test_corrupt_existing_object_and_foreign_lock_are_not_overwritten(self):
        one,p=self.stage();a.activate(one,self.public);before=self.index();lock=self.public/'packs/.publish.lock';lock.write_text('foreign live owner');self.assertRaisesRegex(ValueError,'locked',a.activate,one,self.public);self.assertEqual('foreign live owner',lock.read_text());lock.unlink()
        (self.public/'packs/files'/p['sha256']/p['name']).write_bytes(b'bad');self.assertRaisesRegex(ValueError,'modified',a.activate,one,self.public);self.assertEqual(before,self.index())
    def test_symlinked_objects_are_rejected_without_touching_external_files(self):
        one,p=self.stage();a.activate(one,self.public);before=self.index();file=self.public/'packs/files'/p['sha256']/p['name'];outside=self.root/'outside';outside.write_bytes(file.read_bytes());file.unlink();file.symlink_to(outside);self.assertRaisesRegex(ValueError,'Symlink',a.activate,one,self.public);self.assertEqual(before,self.index());self.assertEqual(p['bytes'],outside.stat().st_size)
    def test_weak_or_mismatched_metadata_is_rejected(self):
        stage,p=self.stage();self.assertRaisesRegex(ValueError,'target differs',a.activate,stage,self.public,'https://wrong.test');self.assertFalse((self.public/'packs/index.json').exists())
        index=stage/'site/packs/index.json';body=index.read_text().replace('"formatVersion": 1','"formatVersion": true');index.write_text(body);self.resum(stage);self.assertRaises(ValueError,a.activate,stage,self.public)
    def test_http_verifier_checks_actual_index_range_headers_and_full_digest(self):
        stage,p=self.stage();a.activate(stage,self.public);body=(self.public/'packs/files'/p['sha256']/p['name']).read_bytes();bad={'range':False,'full':False}
        class Response(io.BytesIO):
            def __init__(self,data,status=200,headers=None):super().__init__(data);self.status=status;self.headers=headers or {}
        class Opener:
            def open(_,request,timeout):
                if request.full_url.endswith('/health'):return Response(b'ok\n')
                if request.full_url.endswith('/index.json'):return Response(self.index())
                if request.get_header('Range'):return Response(body[:100],200 if bad['range'] else 206,{'Content-Range':f'bytes 0-99/{len(body)}'})
                return Response(b'x'*len(body) if bad['full'] else body)
        with patch.object(a.urllib.request,'build_opener',return_value=Opener()):
            self.assertTrue(a.check_http(self.public,'https://example.test:8443',True)['httpVerified']);bad['range']=True;self.assertRaisesRegex(ValueError,'Range',a.check_http,self.public,'https://example.test:8443');bad['range']=False;bad['full']=True;self.assertRaisesRegex(ValueError,'checksum',a.check_http,self.public,'https://example.test:8443',True)

class ConfigTests(unittest.TestCase):
    def setUp(self):self.temp=tempfile.TemporaryDirectory(prefix='lr-nginx-');self.root=Path(self.temp.name)
    def tearDown(self):self.temp.cleanup()
    def changes(self):return c.configuration('62.234.28.97',8443,str(self.root/'public'),str(self.root/'service'),str(self.root/'nginx'),str(self.root/'systemd'))
    def test_generated_config_uses_only_dedicated_tls_and_is_idempotent(self):
        changes=self.changes();calls=[];c.apply_managed(changes,lambda:calls.append('validate'),lambda:calls.append('reload'));before={p:p.read_bytes() for p,r in changes.items() if r[0]=='file'};c.apply_managed(changes,lambda:None,lambda:None);self.assertEqual(before,{p:p.read_bytes() for p in before});site=next(v[1] for p,v in changes.items() if p.name=='linguareader-packs' and v[0]=='file');self.assertIn('listen 8443 ssl;',site);self.assertNotIn('listen 80',site);self.assertNotIn('listen 443',site);self.assertIn('max_ranges 1;',site);self.assertEqual(['validate','reload'],calls)
    def test_unmanaged_existing_names_fail_before_any_write(self):
        changes=self.changes();first=next(iter(changes));first.parent.mkdir(parents=True);first.write_text('another service');self.assertRaisesRegex(ValueError,'unmanaged',c.apply_managed,changes,lambda:None,lambda:None);self.assertEqual('another service',first.read_text());self.assertFalse((self.root/'systemd/linguareader-packs-renew.timer').exists())
    def test_validation_and_reload_failure_restore_previous_files_links_modes(self):
        changes=self.changes();c.apply_managed(changes,lambda:None,lambda:None);before={p:(p.read_bytes(),p.stat().st_mode&0o777) for p,r in changes.items() if r[0]=='file'};link=next(p for p,r in changes.items() if r[0]=='symlink');target=os.readlink(link)
        modified={p:(r[0],r[1]+'\n# candidate' if r[0]=='file' else r[1],r[2]) for p,r in changes.items()};restored=[]
        def fail():raise RuntimeError('injected failure')
        for validate,reload in [(fail,lambda:None),(lambda:None,fail)]:
            self.assertRaises(RuntimeError,c.apply_managed,modified,validate,reload,lambda:restored.append(True));self.assertEqual(before,{p:(p.read_bytes(),p.stat().st_mode&0o777) for p in before});self.assertEqual(target,os.readlink(link))
        self.assertEqual([True,True],restored)
    def test_first_install_failure_removes_only_new_owned_files(self):
        changes=self.changes();unrelated=self.root/'nginx/sites-available/existing';unrelated.parent.mkdir(parents=True);unrelated.write_text('old site')
        def fail():raise RuntimeError('validation')
        self.assertRaises(RuntimeError,c.apply_managed,changes,fail,lambda:None);self.assertTrue(all(not p.exists() and not p.is_symlink() for p in changes));self.assertEqual('old site',unrelated.read_text())
    def test_mid_write_failure_restores_prior_files_and_still_runs_recovery(self):
        changes=self.changes();c.apply_managed(changes,lambda:None,lambda:None)
        before={p:p.read_bytes() for p,r in changes.items() if r[0]=='file'}
        original=c.replace;failed=[False];restored=[]
        modified={p:(r[0],r[1]+'\n# candidate' if r[0]=='file' else r[1],r[2]) for p,r in changes.items()}
        def fail(file,record):
            if file.name=='reload-nginx.sh' and not failed[0]:failed[0]=True;raise OSError('injected config write failure')
            original(file,record)
        with patch.object(c,'replace',side_effect=fail):self.assertRaises(OSError,c.apply_managed,modified,lambda:None,lambda:None,lambda:restored.append(True))
        self.assertEqual(before,{p:p.read_bytes() for p in before});self.assertEqual([True],restored)
    def health_clock(self, codes, body='ok\n'):
        elapsed=[0.0];calls=[];sleeps=[]
        def run(command,**kwargs):
            calls.append(kwargs);code=codes[min(len(calls)-1,len(codes)-1)]
            return subprocess.CompletedProcess(command,code,stdout=body,stderr='connection refused\n' if code else '')
        def sleep(value):elapsed[0]+=value;sleeps.append(value)
        return run,lambda:elapsed[0],sleep,calls,sleeps
    def test_health_retries_connection_refusal_then_confirms_actual_success(self):
        run,clock,sleep,calls,sleeps=self.health_clock([7,7,0])
        with contextlib.redirect_stderr(io.StringIO()),contextlib.redirect_stdout(io.StringIO()):c.wait_for_health(['curl'],1,run,clock,sleep)
        self.assertEqual(3,len(calls));self.assertEqual([0.25,0.25],sleeps)
    def test_health_does_not_retry_http_tls_dns_timeout_or_wrong_body(self):
        for code in [22,35,60,6,28]:
            run,clock,sleep,calls,sleeps=self.health_clock([code])
            with contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(subprocess.CalledProcessError) as failure:c.wait_for_health(['curl'],1,run,clock,sleep)
            self.assertEqual(code,failure.exception.returncode);self.assertEqual(1,len(calls));self.assertEqual([],sleeps)
        run,clock,sleep,calls,sleeps=self.health_clock([0],'wrong service')
        self.assertRaisesRegex(ValueError,'health response mismatch',c.wait_for_health,['curl'],1,run,clock,sleep)
        self.assertEqual(1,len(calls));self.assertEqual([],sleeps)
    def test_health_deadline_still_restores_old_configuration(self):
        changes=self.changes();c.apply_managed(changes,lambda:None,lambda:None);before={p:p.read_bytes() for p,r in changes.items() if r[0]=='file'}
        modified={p:(r[0],r[1]+'\n# candidate' if r[0]=='file' else r[1],r[2]) for p,r in changes.items()}
        run,clock,sleep,calls,sleeps=self.health_clock([7]);order=[]
        def reload():c.wait_for_health(['curl'],0.5,run,clock,sleep)
        timer=self.root/'systemd/linguareader-packs-renew.timer'
        def before_restore():self.assertIn('# candidate',timer.read_text());order.append('timer')
        def after_restore():self.assertEqual(before,{p:p.read_bytes() for p in before});order.append('runtime')
        with contextlib.redirect_stderr(io.StringIO()):self.assertRaises(TimeoutError,c.apply_managed,modified,lambda:None,reload,after_restore,before_restore)
        self.assertEqual(['timer','runtime'],order);self.assertEqual(2,len(calls));self.assertEqual(0.5,clock())
    def test_timer_recovery_preserves_the_preexisting_enabled_and_active_states(self):
        for attempted in [False,True]:
            for enabled,active in [(False,False),(True,False),(False,True),(True,True)]:
                commands=[]
                c.restore_timer_state(enabled,active,attempted,lambda args,**kwargs:commands.append(args))
                expected=[]
                if attempted and not active:expected.append(['systemctl','stop',c.SERVICE])
                if attempted and not enabled:expected.append(['systemctl','disable',c.SERVICE])
                self.assertEqual(expected,commands)
    def test_certbot_bootstrap_pins_tls_source_and_disables_inherited_config_and_cache(self):
        script=(HERE/'install-ubuntu.sh').read_text()
        command=next(line.strip() for line in script.splitlines() if 'certbot/bin/python" -m pip ' in line)
        self.assertEqual(['PIP_CONFIG_FILE=/dev/null','$SERVICE_ROOT/certbot/bin/python','-m','pip','--isolated','--no-cache-dir','install','--index-url','https://pypi.org/simple','certbot>=5.4,<6'],shlex.split(command))
        self.assertNotIn('--trusted-host',command)
    def test_render_rejects_port_path_and_host_injection(self):
        for host in ['a;touch /tmp/x','999.999.999.999','[::1]']:
            self.assertRaises(ValueError,c.configuration,host,8443,'/srv/public','/opt/service','/etc/nginx','/etc/systemd/system')
        self.assertRaises(ValueError,c.configuration,'example.test',443,'/srv/public','/opt/service','/etc/nginx','/etc/systemd/system');self.assertRaises(ValueError,c.configuration,'example.test',8443,'/srv/../other','/opt/service','/etc/nginx','/etc/systemd/system')

if __name__=='__main__':unittest.main(verbosity=2)
