import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {createDictionary,publish,digest} from '../../scripts/publish_packs.mjs';
import {packageDeployment} from './package.mjs';
const here=path.dirname(new URL(import.meta.url).pathname);
async function fixture(fn){const dir=fs.mkdtempSync(path.join(os.tmpdir(),'lr-package-'));try{const database=path.join(dir,'database'),license=path.join(dir,'license'),pack=path.join(dir,'ecdict.lrpack');fs.writeFileSync(database,'fixture');fs.writeFileSync(license,'MIT');await createDictionary({database,license,out:pack});await fn({dir,database,license,pack});}finally{fs.rmSync(dir,{recursive:true,force:true});}}
function python(...args){return spawnSync('python3',[path.join(here,'activate.py'),...args],{encoding:'utf8'});}
function extract(archive,directory){fs.mkdirSync(directory);const r=spawnSync('python3',['-c',"import tarfile,sys; t=tarfile.open(sys.argv[1]); assert all((m.isfile() or m.isdir()) and not m.name.startswith('/') and '..' not in m.name.split('/') for m in t.getmembers()); t.extractall(sys.argv[2],**({'filter':'data'} if hasattr(tarfile,'data_filter') else {}))",archive,directory],{encoding:'utf8'});assert.equal(r.status,0,r.stderr);}

test('Node publication, authenticated packaging and Python activation agree on identity/history and retries',()=>fixture(async({dir,pack,database,license})=>{
 const site=path.join(dir,'site'),server=path.join(dir,'server'),archive=path.join(dir,'upload.tar.gz');await publish({root:server,packs:[pack]});
 fs.writeFileSync(database,'version two');await createDictionary({database,license,out:pack,version:'2.0.0'});await publish({root:site,packs:[pack]});
 const result=await packageDeployment({site,out:archive});assert.equal(result.sha256,await digest(archive));const stage=path.join(dir,'stage');extract(archive,stage);
 const sums=fs.readFileSync(path.join(stage,'SHA256SUMS'),'utf8');assert.match(sums,/  site\/packs\/index\.json/);assert.match(sums,/  install-ubuntu\.sh/);assert.match(sums,/  activate\.py/);assert.match(sums,/  deployment\.json/);
 let r=python('verify','--stage',stage);assert.equal(r.status,0,r.stderr);r=python('activate','--stage',stage,'--root',server);assert.equal(r.status,0,r.stderr);
 const index=path.join(server,'packs/index.json'),before=fs.readFileSync(index),catalog=JSON.parse(before);assert.equal(catalog.packs[0].version,'2.0.0');assert.ok(catalog.publishedVersions['ecdict@1.0.0']);assert.ok(catalog.publishedVersions['ecdict@2.0.0']);
 r=python('activate','--stage',stage,'--root',server);assert.equal(r.status,0,r.stderr);assert.equal(JSON.parse(r.stdout).changed,false);assert.deepEqual(fs.readFileSync(index),before);
 fs.appendFileSync(path.join(stage,'site/packs/index.json'),' ');r=python('activate','--stage',stage,'--root',server);assert.notEqual(r.status,0);assert.match(r.stderr,/checksum mismatch/);assert.deepEqual(fs.readFileSync(index),before);
}));

test('packaging refuses a wrong target, broken object and tampered metadata while retaining the previous archive',()=>fixture(async({dir,pack})=>{
 const site=path.join(dir,'site'),archive=path.join(dir,'upload.tar.gz'),catalog=await publish({root:site,packs:[pack]});await packageDeployment({site,out:archive});const before=fs.readFileSync(archive);
 await assert.rejects(packageDeployment({site,out:archive,baseUrl:'https://different.test'}),/target differs/);assert.deepEqual(fs.readFileSync(archive),before);
 const p=catalog.packs[0],object=path.join(site,'packs/files',p.sha256,p.name);fs.writeFileSync(object,'modified');await assert.rejects(packageDeployment({site,out:archive}),/Invalid source object/);assert.deepEqual(fs.readFileSync(archive),before);
}));

test('failed archive activation keeps the previous complete package and leaves no temporary output',()=>fixture(async({dir,pack})=>{
 const site=path.join(dir,'site'),archive=path.join(dir,'upload.tar.gz');await publish({root:site,packs:[pack]});await packageDeployment({site,out:archive});const before=fs.readFileSync(archive),rename=fs.renameSync;
 try{fs.renameSync=(source,target)=>{if(target===archive)throw new Error('injected archive rename failure');return rename(source,target);};await assert.rejects(packageDeployment({site,out:archive}),/injected/);}finally{fs.renameSync=rename;}
 assert.deepEqual(fs.readFileSync(archive),before);assert.deepEqual(fs.readdirSync(dir).filter(n=>n.startsWith('upload.tar.gz.')),[]);
}));
